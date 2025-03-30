package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{Actor, ActorLogging, ActorNotFound, ActorRef}
import core.entity.event.{ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, ScheduleEvent, SpontaneousEvent}
import core.types.CoreTypes.Tick
import core.entity.state.BaseState
import core.entity.event.control.execution.AcknowledgeTickEvent
import core.entity.control.LamportClock
import core.util.JsonUtil

import org.apache.pekko.cluster.sharding.{ClusterSharding, ShardRegion}
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.{Dependency, Identify}
import org.interscity.htc.core.entity.event.control.load.{InitializeEntityAckEvent, InitializeEvent}

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Base actor class that provides the basic structure for the actors in the system. All actors
  * should extend this class.
  * @param actorId
  *   The id of the actor
  * @param timeManager
  *   The actor reference of the time manager
  * @param data
  *   The data to initialize the actor. This is used to maintain the actor state and to restore from
  *   a snapshot.
  * @param dependencies
  *   The dependencies of the actor. This is used to send messages to other actors.
  * @tparam T
  *   The state of the actor
  */
abstract class BaseActor[T <: BaseState](
  protected var actorId: String,
  private val timeManager: ActorRef = null,
  private val creatorManager: ActorRef = null,
  private val data: Any = null,
  protected val dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()
)(implicit m: Manifest[T])
    extends Actor
    with ActorLogging {

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0
  protected var state: T = uninitialized
  private var currentTimeManager: ActorRef = uninitialized
  private var isInitialized: Boolean = false

  /** Initializes the actor. This method is called before the actor starts processing messages. It
    * registers the actor with the time manager and calls the onStart method.
    */
  override def preStart(): Unit = {
    super.preStart()
    logEvent(s"Starting actor with actorId: $actorId")
    if (data != null) {
      state = JsonUtil.convertValue[T](data)
      startTick = state.getStartTick
    }
    onStart()
  }

  private def onFinishInitialize(): Unit =
    if (!isInitialized && creatorManager != null) {
      isInitialized = true
      creatorManager ! InitializeEntityAckEvent(
        entityId = actorId
      )
    }

  /** Starts the actor. This method is called on start the actor before starts processing messages.
    * If you want to perform any action before the actor starts processing messages, you should
    * override this method.
    */
  protected def onStart(): Unit = ()

  protected def handleEvent: Receive = {
    case event => logEvent(s"Event not handled $event")
  }

  private def initialize(event: InitializeEvent): Unit = {
    actorId = event.id
    if (event.data.data != null) {
      state = JsonUtil.convertValue[T](event.data.data)
      startTick = state.getStartTick
    }
    dependencies.clear()
    dependencies ++= event.data.dependencies
    onInitialize(event)
    onFinishInitialize()
  }

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
    * @tparam D
    *   The type of the data
    */
  protected def sendMessageTo(
                               entityId: String,
                               classType: String,
                               data: AnyRef,
                               eventType: String = "default"
  ): Unit = {
    lamportClock.increment()
    val shardingRegion = getShardRef(classType)
    logEvent(
      s"Sending message to ${entityId} with Lamport clock ${getLamportClock}"
    )
    shardingRegion ! EntityEnvelopeEvent(
      entityId,
      ActorInteractionEvent(
        tick = currentTick,
        lamportTick = getLamportClock,
        actorRefId = getActorId,
        actorClassType = getClass.getName,
        actorPathRef = self.path.name,
        data = data,
        eventType = eventType,
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
    actSpontaneous(event)
  }

  /** Sends an acknowledge tick event to the time manager. This method is called when the actor
    * receives a spontaneous event and the actor finish processing the event and no needs schedule
    * new tick in this current tick.
    */
  protected def sendAcknowledgeTick(): Unit =
    if (timeManager != null && timeManager != self) {
      timeManager ! AcknowledgeTickEvent(
        tick = currentTick,
        actorRef = self,
        timeManager = currentTimeManager
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
    logEvent(
      s"Received interaction from ${sender().path.name} with Lamport clock ${getLamportClock}"
    )
    actInteractWith(event)
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
  protected def logEvent(eventInfo: String): Unit =
    log.info(s"$actorId: $eventInfo")

  override def receive: Receive = {
    case event: SpontaneousEvent         => handleSpontaneous(event)
    case event: ActorInteractionEvent => handleInteractWith(event)
    case event: DestructEvent            => destruct(event)
    case event: EntityEnvelopeEvent   => handleEnvelopeEvent(event)
    case event: InitializeEvent          => initialize(event)
    case event: ShardRegion.StartEntity  => handleStartEntity(event)
    case event                           => handleEvent(event)
  }

  private def handleEnvelopeEvent(entityEnvelopeEvent: EntityEnvelopeEvent): Unit =
    entityEnvelopeEvent.event match {
      case event: InitializeEvent          => initialize(event)
      case event: SpontaneousEvent         => handleSpontaneous(event)
      case event: ActorInteractionEvent => handleInteractWith(event)
      case event: DestructEvent            => destruct(event)
      case event                           => handleEvent(event)
    }

  private def handleStartEntity(event: ShardRegion.StartEntity): Unit =
    actorId = event.entityId

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
  ): Unit =
    timeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(actorId, getClass.getName, self.path.name),
      scheduleEvent = scheduleTick.map(
        tick =>
          ScheduleEvent(
            tick = tick,
            actorRef = self,
            identify = Identify(actorId, getClass.getName, self.path.name)
          )
      ),
      timeManager = currentTimeManager,
      destruct = destruct
    )

  protected def selfSpontaneous(): Unit =
    self ! SpontaneousEvent(currentTick, currentTimeManager)

  protected def scheduleEvent(tick: Tick): Unit =
    timeManager ! ScheduleEvent(
      tick = tick,
      actorRef = self,
      identify = Identify(actorId, getClass.getName, self.path.name)
    )

  protected def getActorRef(path: String): ActorRef =
    Await.result(getActorRefFromPath(path), Duration.Inf)

  private def getActorRefFromPath(path: String): Future[ActorRef] = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timeout: Timeout = Timeout(3, TimeUnit.SECONDS)
    context.system.actorSelection(path)
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
  protected def getActorId: String = actorId

  protected def getPath: String = self.path.toString

  protected def getSelfShard: ActorRef =
    ClusterSharding(context.system).shardRegion(getClass.getName)

  protected def getShardName: String = getClass.getName

  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className)

  protected def toIdentify: Identify = Identify(getActorId, getShardName, self.path.name)
}
