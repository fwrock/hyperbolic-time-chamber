package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ ActorLogging, ActorNotFound, ActorRef, Stash }
import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{ IdUtil, JsonUtil }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.persistence.{ SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer }
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{ AcknowledgeTickEvent, DestructEvent, RegisterActorEvent }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import org.slf4j.LoggerFactory

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

/** Base actor class that provides the basic structure for the actors in the system. All actors
  * should extend this class.
  * @param actorId
  *   The id of the actor
  * @param timeManager
  *   The actor reference of the time manager
  * @tparam T
  *   The state of the actor
  */
abstract class BaseActor[T <: BaseState](
  protected var actorId: String = null,
  protected var shardId: String = null,
  private var timeManager: ActorRef = null,
  private var creatorManager: ActorRef = null
)(implicit m: Manifest[T])
    extends ActorSerializable
    with ActorLogging
    with Stash {

  protected val config = ConfigFactory.load()
  protected var startTick: Tick = MinValue
  protected val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0

  protected var state: T = uninitialized
  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef] = uninitialized
  protected var entityId: String = actorId
  private var currentTimeManager: ActorRef = uninitialized
  protected var isInitialized: Boolean = false

  protected val dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()

  private val snapShotInterval = 1000

  override def persistenceId: String = s"${getClass.getName}-${self.path.name}"

  /** Initializes the actor. This method is called before the actor starts processing messages. It
    * registers the actor with the time manager and calls the onStart method.
    */
  override def preStart(): Unit = {
    super.preStart()
    onStart()
  }

  private def onFinishInitialize(): Unit =
    if (!isInitialized && creatorManager != null) {
      isInitialized = true
      creatorManager ! InitializeEntityAckEvent(
        entityId = entityId
      )
    }

  /** Starts the actor. This method is called on start the actor before starts processing messages.
    * If you want to perform any action before the actor starts processing messages, you should
    * override this method.
    */
  protected def onStart(): Unit = ()

  protected def handleEvent: Receive = {
    case event => logInfo(s"Event not handled $event")
  }

  private def initialize(event: InitializeEvent): Unit =
    if (!isInitialized) {
      actorId = event.id
      entityId = event.id
      timeManager = event.data.timeManager
      creatorManager = event.data.creatorManager
      state = JsonUtil.convertValue[T](event.data.data)
      dependencies ++= event.data.dependencies
      reporters = event.data.reporters
      if (state != null) {
        startTick = state.getStartTick
        onInitialize(event)
        registerOnTimeManager()
        onFinishInitialize()
      } else {
        onFinishInitialize()
        context.stop(self)
      }
    } else {
//      logError(
//        s"Actor already initialized with id= $entityId, state= $state, not initializing again with $event"
//      )
    }

  private def registerOnTimeManager(): Unit =
    timeManager ! RegisterActorEvent(
      startTick = startTick,
      actorId = entityId,
      identify = Some(
        Identify(
          id = IdUtil.format(entityId),
          shardId = IdUtil.format(shardId),
          classType = getClass.getName,
          actorRef = getSelfShard.path.toString
        )
      )
    )

  protected def onInitialize(event: InitializeEvent): Unit = ()

  /** Sends a message to another simulation actor.
    * @param actorId
    *   The id of the entity in the shard region and simulation
    * @param actorRef
    *   The actor reference of the actor. This is the shard region actor reference.
    * @param data
    *   The data to send
    * @param eventType
    *   The type of the event
    */
  protected def sendMessageTo(
    entityId: String,
    shardId: String,
    data: AnyRef,
    eventType: String = "default"
  ): Unit = {
    lamportClock.increment()
//    logInfo(
//      s"Sending message to ${entityId} and shardId $shardId with Lamport clock ${getLamportClock} and tick ${currentTick} and data ${data}"
//    )
    val shardingRegion = getShardRef(IdUtil.format(shardId))

    shardingRegion ! EntityEnvelopeEvent(
      IdUtil.format(entityId),
      ActorInteractionEvent(
        tick = currentTick,
        lamportTick = getLamportClock,
        actorRefId = IdUtil.format(getActorId),
        shardRefId = IdUtil.format(getShardId),
        actorClassType = getClass.getName,
        actorPathRef = self.path.name,
        data = data,
        eventType = eventType
      )
    )
  }

  /** This method is called when the actor receives a message from another actor. It updates the
    * Lamport clock and calls the actInteractWith method.
    * @param otherClock
    *   The Lamport clock of the other actor
    */
  private def updateLamportClock(otherClock: Long): Unit =
    lamportClock.update(otherClock)

  /** Gets the current Lamport clock of the actor.
    * @return
    *   The current Lamport clock
    */
  private def getLamportClock: Long =
    lamportClock.getClock

  /** Handles the spontaneous event. This method is called when the actor receives a spontaneous
    * event. The spontaneous events are thrown by the time manager.
    * @param event
    *   The spontaneous event
    */
  private def handleSpontaneous(event: SpontaneousEvent): Unit = {
    currentTick = event.tick
    currentTimeManager = event.actorRef
    try actSpontaneous(event)
    catch
      case e: Exception =>
//        logError(
//          s"$entityId Error spontaneous event at tick ${event.tick} and lamport $getLamportClock state= $state, isInitialized= $isInitialized",
//          e
//        )
        e.printStackTrace()
        onFinishSpontaneous()
    save(event)
  }

  /** Sends an acknowledge tick event to the time manager. This method is called when the actor
    * receives a spontaneous event and the actor finish processing the event and no needs schedule
    * new tick in this current tick.
    */
  protected def sendAcknowledgeTick(): Unit =
    if (timeManager != null && timeManager != self) {
      timeManager ! AcknowledgeTickEvent(
        tick = currentTick,
        actorRef = getPath,
        timeManagerRef = currentTimeManager.path.toString
      )
    }

  /** This method is called when the actor receives a spontaneous event. It should be overridden by
    * the actor to handle the spontaneous event. The spontaneous events are thrown by the time
    * manager.
    *
    * @param event
    *   The spontaneous event
    */
  protected def actSpontaneous(event: SpontaneousEvent): Unit = ()

  /** Handles the interaction with another actor. This method is called when the actor receives an
    * interaction event. It updates the Lamport clock and calls the actInteractWith method.
    * @param event
    *   The interaction event
    */
  private def handleInteractWith(event: ActorInteractionEvent): Unit = {
    updateLamportClock(event.lamportTick)
//    logInfo(
//      s"Received interaction from ${sender().path.name} with Lamport clock ${getLamportClock} and tick ${currentTick} and data ${event.data}"
//    )
    actInteractWith(event)
    save(event)
  }

  /** This method is called when the actor receives an interaction event. It should be overridden by
    * the actor to handle the interaction event.
    * @param event
    *   The interaction event
    */
  def actInteractWith(event: ActorInteractionEvent): Unit = ()

  /** Logs an event. This method is called when the actor wants to log an event.
    * @param eventInfo
    *   The information of the event
    */
  protected def logInfo(eventInfo: String): Unit =
    log.info(s"$entityId: $eventInfo")

  protected def logDebug(eventInfo: String): Unit =
    log.debug(s"$entityId: $eventInfo")

  protected def logWarn(eventInfo: String): Unit =
    log.warning(s"$entityId: $eventInfo")

  protected def logError(eventInfo: String, throwable: Throwable): Unit =
    log.error(throwable, s"$entityId: $eventInfo")

  protected def logError(eventInfo: String): Unit =
    log.error(s"$entityId: $eventInfo")

  override def receive: Receive = {
    case event: SpontaneousEvent              => handleSpontaneous(event)
    case event: ActorInteractionEvent         => handleInteractWith(event)
    case event: DestructEvent                 => destruct(event)
    case event: EntityEnvelopeEvent           => handleEnvelopeEvent(event)
    case event: InitializeEvent               => initialize(event)
    case event: ShardRegion.StartEntity       => handleStartEntity(event)
    case SaveSnapshotSuccess(metadata)        =>
    case SaveSnapshotFailure(metadata, cause) =>
    case event                                => handleEvent(event)
  }

  private def save(event: Any): Unit =
    persist(event) {
      e =>
        context.system.eventStream.publish(e)
        if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
          saveSnapshot(state)
    }

  def receiveCommand: Receive = receive

  def receiveRecover: Receive = {
    case snapshot: SnapshotOffer =>
      state = snapshot.snapshot.asInstanceOf[T]
      logInfo(s"Recovered state: $state")
    case _ => receive
  }

  private def handleEnvelopeEvent(entityEnvelopeEvent: EntityEnvelopeEvent): Unit =
    entityEnvelopeEvent.event match {
      case event: InitializeEvent         => initialize(event)
      case event: SpontaneousEvent        => handleSpontaneous(event)
      case event: ActorInteractionEvent   => handleInteractWith(event)
      case event: DestructEvent           => destruct(event)
      case event: ShardRegion.StartEntity => handleStartEntity(event)
      case event                          => handleEvent(event)
    }

  private def handleStartEntity(event: ShardRegion.StartEntity): Unit =
//    logInfo(s"Starting entity with id ${event.entityId}")
    entityId = event.entityId

  /** Handles the destruction event. This method is called when the actor receives a destruction
    * event. It calls the destruct method.
    * @param event
    *   The destruction event
    */
  private def destruct(event: DestructEvent): Unit = {
    onDestruct(event)
    context.stop(self)
  }

  /** Called when the actor is finished. This method is called when the actor finishes processing
    * messages. It calls the onDestruct method.
    */
  protected def selfDestruct(): Unit =
    self ! DestructEvent(currentTick, currentTimeManager.path.toString)

  /** Finishes the actor. This method is called when the actor finishes processing messages. It
    * calls the onDestruct method.
    */
  protected def onDestruct(event: DestructEvent): Unit = {}

  /** Finishes the spontaneous event. This method is called when the actor finishes processing the
    * spontaneous event. This method allows the actor to schedule a new tick in the time manager.
    * @param scheduleTick
    *   The tick to schedule a new event
    */
  protected def onFinishSpontaneous(
    scheduleTick: Option[Tick] = None,
    destruct: Boolean = false
  ): Unit = {
    currentTimeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(
        id = IdUtil.format(getActorId),
        shardId = IdUtil.format(getShardId),
        classType = getClass.getName,
        actorRef = getPath
      ),
      scheduleEvent = None,
      timeManager = currentTimeManager,
      destruct = destruct
    )
    scheduleTick.foreach(
      tick =>
        timeManager ! ScheduleEvent(
          tick = tick,
          actorRef = getPath,
          identify = Some(
            Identify(
              id = IdUtil.format(getActorId),
              shardId = IdUtil.format(getShardId),
              classType = getClass.getName,
              actorRef = getPath
            )
          )
        )
    )
  }

  /** Sends a spontaneous event to itself. This method is used to trigger a spontaneous event in the
    * actor.
    */
  protected def selfSpontaneous(): Unit =
    self ! SpontaneousEvent(currentTick, currentTimeManager)

  /** Schedules an event at a specific tick. This method is used to schedule an event in the time
    * manager.
    * @param tick
    *   The tick at which the event should be scheduled
    */
  protected def scheduleEvent(tick: Tick): Unit =
    timeManager ! ScheduleEvent(
      tick = tick,
      actorRef = getPath,
      identify = Some(
        Identify(
          id = getActorId,
          shardId = getShardId,
          classType = getClass.getName,
          actorRef = getPath
        )
      )
    )

  protected def getActorRef(path: String): ActorRef =
    Await.result(getActorRefFromPath(path), Duration.Inf)

  private def getActorRefFromPath(path: String): Future[ActorRef] = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timeout: Timeout = Timeout(3, TimeUnit.SECONDS)
    context.system
      .actorSelection(path)
      .resolveOne()
      .recover {
        case _: ActorNotFound =>
          log.warning(s"Actor not found: $path")
          null
      }
  }

  /** Gets the time manager actor reference.
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = timeManager

  /** Gets the actor id.
    * @return
    *   The actor id
    */
  protected def getActorId: String = entityId

  protected def getPath: String = self.path.toString

  /** Gets the actor reference of the shard region for the current actor.
    * @return
    *   The actor reference of the shard region
    */
  protected def getSelfShard: ActorRef =
    ClusterSharding(context.system).shardRegion(getShardId)

  /** Gets the shard name for the current actor.
    * @return
    *   The shard name
    */
  protected def getShardId: String = shardId.replace(":", "_").replace(";", "_")

  /** Gets the actor reference of the shard region for a given class name.
    *
    * @param className
    *   The class name of the shard region
    * @return
    *   The actor reference of the shard region
    */
  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className.replace(":", "_").replace(";", "_"))

  protected def toIdentify: Identify =
    Identify(
      id = getActorId,
      shardId = getShardId,
      classType = getClass.getName,
      actorRef = self.path.name
    )
}
