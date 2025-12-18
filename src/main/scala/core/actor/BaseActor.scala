package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ActorLogging, ActorNotFound, ActorRef, ActorSelection, Stash}
import core.entity.event.{ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{IdUtil, JsonUtil, StringUtil}

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.{ClusterSharding, ShardRegion}
import org.apache.pekko.persistence.{DeleteMessagesSuccess, DeleteSnapshotsFailure, DeleteSnapshotsSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotMetadata, SnapshotOffer, SnapshotSelectionCriteria}
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, RegisterActorEvent}
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartEntityAckEvent}
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{LoadBalancedDistributed, PoolDistributed}

import java.util.UUID
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
  * @tparam T
  *   The state of the actor
  */
abstract class BaseActor[T <: BaseState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends ActorSerializable
    with ActorLogging
    with Stash {

  protected val config = ConfigFactory.load()
  private var isInitialized: Boolean = false
  private val snapShotInterval = try {
    config.getInt("htc.simulation.snapshot-interval")
  } catch {
    case _: Exception => 1000
  }
  
  // Load window execution configuration
  windowExecutionEnabled = try {
    config.getInt("htc.time-manager.window-size") > 1
  } catch {
    case _: Exception => false
  }

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0
  
  // Window-based execution state
  private var currentWindowEnd: Tick = 0
  private var windowExecutionEnabled: Boolean = false

  protected var entityId: String =
    if (properties != null) properties.entityId
    else {
      try
        core.actor.manager.RandomSeedManager.deterministicUUID()
      catch {
        case _: Exception => UUID.randomUUID().toString // Fallback
      }
    }
  protected var shardId: String = getShardId
  protected var state: T = uninitialized
  protected val dependencies: mutable.Map[String, Dependency] =
    if (properties != null) properties.dependencies else mutable.Map[String, Dependency]()

  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef] =
    if (properties != null) properties.reporters else null
  protected var timeManager: ActorRef = if (properties != null) properties.timeManager else null
  protected var creatorManager: ActorRef =
    if (properties != null) properties.creatorManager else null
  private var currentTimeManager: ActorRef = uninitialized

  override def persistenceId: String = s"${getClass.getName}-${self.path.name}"

  /** Initializes the actor. This method is called before the actor starts processing messages. It
    * registers the actor with the time manager and calls the onStart method.
    */
  override def preStart(): Unit = {
    super.preStart()
    if (properties.data != null) {
      try {
        state = JsonUtil.convertValue[T](properties.data)
        if (state != null) {
          startTick = state.getStartTick
        }
        creatorManager ! StartEntityAckEvent(entityId = entityId)
        if (state != null && state.isSetScheduleOnTimeManager) {
          registerOnTimeManager()
        }
      } catch {
        case e: Exception =>
          logError(s"Error on start actor $entityId: ${e.getMessage}", e)
          e.printStackTrace()
      }
    }
    onStart()
  }

  private def onFinishInitialize(event: InitializeEvent): Unit =
    if (!isInitialized && creatorManager != null) {
      isInitialized = true
      creatorManager ! InitializeEntityAckEvent(
        entityId = entityId
      )
//      persistAsync(event) { e =>
//        context.system.eventStream.publish(e)
//        saveSnapshot(state)
//      }
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
      entityId = event.id
      timeManager = event.data.timeManager
      creatorManager = event.data.creatorManager
      state = JsonUtil.convertValue[T](event.data.data)
      dependencies.clear()
      dependencies ++= event.data.dependencies
      reporters = event.data.reporters
      if (state != null) {
        startTick = state.getStartTick
        onInitialize(event)
        if (state.isSetScheduleOnTimeManager) {
          registerOnTimeManager()
        }
        onFinishInitialize(event)
      } else {
        onFinishInitialize(event)
        context.stop(self)
      }
    }

  private def registerOnTimeManager(): Unit =
    if (properties.actorType == LoadBalancedDistributed) {
      state.eventsAmount += 1
      state.totalEventsAmount += 1
      timeManager ! RegisterActorEvent(
        startTick = startTick,
        actorId = entityId,
        identify = Some(
          Identify(
            id = IdUtil.format(entityId),
            resourceId = IdUtil.format(properties.resourceId),
            classType = getClass.getName,
            actorRef = getSelfShard.path.toString,
            actorType = properties.actorType.toString
          )
        )
      )
    } else {
      state.eventsAmount += 1
      state.totalEventsAmount += 1
      timeManager ! RegisterActorEvent(
        startTick = startTick,
        actorId = entityId,
        identify = Some(
          Identify(
            id = IdUtil.format(entityId),
            resourceId = IdUtil.format(properties.resourceId),
            classType = getClass.getName,
            actorRef = self.path.toString,
            actorType = properties.actorType.toString
          )
        )
      )
    }

  protected def onInitialize(event: InitializeEvent): Unit = ()

  /** Sends a message to another simulation actor.
    * @param entityId
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
    shardId: String = null,
    data: AnyRef,
    eventType: String = "default",
    actorType: CreationTypeEnum = LoadBalancedDistributed
  ): Unit = {
    lamportClock.increment()
    state.eventsAmount += 1
    state.totalEventsAmount += 1
    if (actorType == PoolDistributed) {
      sendMessageToPool(entityId, data, eventType)
    } else {
      sendMessageToShard(entityId, shardId, data, eventType)
    }
  }

  private def sendMessageToShard(
    entityId: String,
    shardId: String,
    data: AnyRef,
    eventType: String = "default"
  ): Unit = {
    val shardingRegion = getShardRef(IdUtil.format(StringUtil.getModelClassName(shardId)))

    shardingRegion ! EntityEnvelopeEvent(
      IdUtil.format(entityId),
      ActorInteractionEvent(
        tick = currentTick,
        lamportTick = getLamportClock,
        actorRefId = IdUtil.format(getEntityId),
        shardRefId = IdUtil.format(getShardId),
        actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorPathRef = self.path.name,
        data = data,
        eventType = eventType,
        actorType = properties.actorType.toString,
        resourceId = properties.resourceId
      )
    )
  }

  private def sendMessageToPool(
    entityId: String,
    data: AnyRef,
    eventType: String = "default"
  ): Unit = {
    val pool = getActorPoolRef(entityId)
    pool ! ActorInteractionEvent(
      tick = currentTick,
      lamportTick = getLamportClock,
      actorRefId = IdUtil.format(getEntityId),
      shardRefId = IdUtil.format(getShardId),
      actorClassType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
      actorPathRef = self.path.name,
      data = data,
      eventType = eventType,
      actorType = properties.actorType.toString,
      resourceId = properties.resourceId
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
    // Don't ignore old ticks - in event-driven model with distributed actors,
    // asynchronous messages (EnterLinkConfirm, ArriveAtNode) arrive with timing offsets
    // Only update currentTick if event is newer (allow tick to stay same or advance)
    if (event.tick > currentTick) {
      currentTick = event.tick
    }
    currentTimeManager = event.actorRef
    
    // Check if lookahead is enabled
    if (event.hasLookahead) {
      // Actor can process multiple ticks up to safe horizon
      actSpontaneousWithLookahead(event)
    } else if (windowExecutionEnabled && currentWindowEnd > currentTick) {
      // Window-based execution: process ticks within window
      actSpontaneousWithWindow(event)
    } else {
      // Conservative mode: single tick execution
      try actSpontaneous(event)
      catch
        case e: Exception =>
          e.printStackTrace()
          onFinishSpontaneous()
    }
    save(event)
  }
  
  /** Execute spontaneous logic within a time window.
    * Processes multiple ticks up to window boundary.
    * Can be combined with lookahead for maximum efficiency.
    */
  protected def actSpontaneousWithWindow(event: SpontaneousEvent): Unit = {
    val effectiveHorizon = if (event.hasLookahead) {
      Math.min(event.effectiveSafeHorizon, currentWindowEnd)
    } else {
      currentWindowEnd
    }
    
    // Execute ticks within window
    while (currentTick < effectiveHorizon) {
      try {
        actSpontaneous(event.copy(tick = currentTick))
      } catch {
        case e: Exception =>
          e.printStackTrace()
          onFinishSpontaneous()
          return
      }
      currentTick += 1
    }
    
    // Report completion at window end (aggregate FinishEvent)
    onFinishSpontaneous(Some(currentTick))
  }
  
  /** Execute spontaneous logic with lookahead optimization.
    * Subclasses can override to implement multi-tick processing.
    * Default implementation falls back to single tick execution.
    */
  protected def actSpontaneousWithLookahead(event: SpontaneousEvent): Unit = {
    try actSpontaneous(event)
    catch
      case e: Exception =>
        e.printStackTrace()
        onFinishSpontaneous()
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
//    case SaveSnapshotSuccess(metadata)        => cleanSnapshot(metadata)
//    case SaveSnapshotFailure(metadata, cause) => logWarn(s"Failed to save snapshot: $cause")
//    case DeleteMessagesSuccess(_)             => ()
//    case DeleteSnapshotsSuccess(_)            => ()
//    case DeleteSnapshotsFailure(_, cause)     =>
//      logWarn(s"Failed to clear old snapshots: $cause")
    case event                                => handleEvent(event)
  }

  private def save(event: Any): Unit = ()
//    persistAsync(event) {
//      e =>
//        context.system.eventStream.publish(e)
//        if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
//          saveSnapshot(state)
//    }

  private def cleanSnapshot(metadata: SnapshotMetadata): Unit = {
    deleteMessages(metadata.sequenceNr)
    deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = metadata.sequenceNr - (snapShotInterval * 2)))
  }

  protected def deepCopyState(original: T): T = {
    if (original == null) return original
    try {
      JsonUtil.convertValue[T](original)
    } catch {
      case _: Throwable => original
    }
  }

  def receiveCommand: Receive = receive

  def receiveRecover: Receive = {
    case snapshot: SnapshotOffer =>
      state = snapshot.snapshot.asInstanceOf[T]
//      logInfo(s"Recovered state non-null: ${state != null}")
//    case event: InitializeEvent =>
//      state = JsonUtil.convertValue[T](event.data.data)
//      if (event.data.dependencies != null) {
//        dependencies.clear()
//        dependencies ++= event.data.dependencies
//      }
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
    entityId = event.entityId

  /** Handles the destruction event. This method is called when the actor receives a destruction
    * event. It calls the destruct method.
    * @param event
    *   The destruction event
    */
  private def destruct(event: DestructEvent): Unit = {
    onDestruct(event)
    selfDestruct()
  }

  /** Called when the actor is finished. This method is called when the actor finishes processing
    * messages. It calls the onDestruct method.
    */
  protected def selfDestruct(): Unit =
    context.stop(self)

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
    if (scheduleTick.isEmpty) {
      state.eventsAmount += 1
      state.totalEventsAmount += 1
    } else {
      state.eventsAmount += 2
      state.totalEventsAmount += 2
    }
    currentTimeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      identify = Identify(
        id = IdUtil.format(getEntityId),
        resourceId = IdUtil.format(properties.resourceId),
        classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorRef = getPath
      ),
      scheduleTick = scheduleTick.map(_.toString),
      scheduleEvent = None,
      timeManager = currentTimeManager,
      destruct = destruct,
      eventsAmount = state.eventsAmount
    )
    scheduleTick.foreach(
      tick =>
        timeManager ! ScheduleEvent(
          tick = tick,
          actorRef = getPath,
          identify = Some(
            Identify(
              id = IdUtil.format(getEntityId),
              resourceId = IdUtil.format(properties.resourceId),
              classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
              actorRef = getPath
            )
          )
        )
    )
    state.eventsAmount = 0
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
          id = getEntityId,
          resourceId = IdUtil.format(properties.resourceId),
          classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
          actorRef = getPath
        )
      )
    )

  protected def report(data: Any, label: String = null): Unit =
    report(
      event = ReportEvent(
        entityId = entityId,
        tick = currentTick,
        lamportTick = getLamportClock,
        data = data,
        label = label
      )
    )

  protected def report(event: ReportEvent): Unit = {
    val defaultReportType = ReportTypeEnum.valueOf(
      Some(config.getString("htc.report-manager.default-strategy")).getOrElse("csv")
    )
    val reportType = if (state.getReporterType != null) {
      state.getReporterType
    } else {
      defaultReportType
    }
    if (reporters.contains(reportType)) {
      reporters(reportType) ! event
    } else {
      reporters(defaultReportType) ! event
    }
  }

  protected def report(data: Any): Unit = {
    val event = ReportEvent(
      entityId = entityId,
      tick = currentTick,
      lamportTick = getLamportClock,
      data = data
    )
    report(event)
  }

  protected def getActorPoolRef(entityId: String): ActorSelection =
    context.system.actorSelection(s"/user/${IdUtil.format(entityId)}")

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

  protected def getDependency(entityId: String): Dependency =
    dependencies(IdUtil.format(entityId))

  /** Gets the time manager actor reference.
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = timeManager

  /** Gets the actor id.
    * @return
    *   The actor id
    */
  protected def getEntityId: String = entityId

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
  protected def getShardId: String = getClass.getName

  /** Gets the actor reference of the shard region for a given class name.
    *
    * @param className
    *   The class name of the shard region
    * @return
    *   The actor reference of the shard region
    */
  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className)

  protected def toIdentify: Identify =
    Identify(
      id = getEntityId,
      resourceId = IdUtil.format(properties.resourceId),
      classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
      actorRef = self.path.name
    )
}
