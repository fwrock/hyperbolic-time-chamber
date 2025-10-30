package org.interscity.htc
package core.actor

import core.entity.actor.properties.{Properties, SimulationBaseProperties}
import core.entity.control.LamportClock
import core.entity.event.control.load.InitializeEvent
import core.entity.event.control.report.ReportEvent
import core.entity.event.control.simulation.{AdvanceToTick, TickCompleted}
import core.entity.event.{ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.entity.state.{BaseState, SimulationBaseState}
import core.enumeration.CreationTypeEnum.{LoadBalancedDistributed, PoolDistributed}
import core.enumeration.{CreationTypeEnum, ReportTypeEnum, TimePolicyEnum}
import core.types.Tick
import core.util.{IdUtil, JsonUtil, StringUtil}

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorLogging, ActorNotFound, ActorRef, ActorSelection, Stash}
import org.apache.pekko.cluster.sharding.{ClusterSharding, ShardRegion}
import org.apache.pekko.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, RegisterActorEvent}
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartEntityAckEvent}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Base actor class that provides the basic structure for the actors in the system. All actors
  * should extend this class.
  *
  * @param actorId
  *   The id of the actor
  * @param timeManager
  *   The actor reference of the time manager
  * @tparam T
  *   The state of the actor
  */
abstract class SimulationBaseActor[T <: SimulationBaseState](
  private val properties: SimulationBaseProperties
)(implicit m: Manifest[T])
    extends BaseActor[T](properties = properties) {
  
  private var isInitialized: Boolean = false

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0
  
  protected val dependencies: mutable.Map[String, Dependency] =
    if (properties != null) properties.dependencies else mutable.Map[String, Dependency]()

  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef] =
    if (properties != null) properties.reporters else null
  protected var timeManager: ActorRef = if (properties != null) properties.timeManager else null
  protected var creatorManager: ActorRef =
    if (properties != null) properties.creatorManager else null
  private var currentTimeManager: ActorRef = uninitialized

  // Política de tempo para registro no LTM correto
  protected var timePolicy: TimePolicyEnum.TimePolicyEnum =
    if (properties != null && properties.timePolicy.isDefined) {
      properties.timePolicy.get
    } else {
      TimePolicyEnum.autoDetect(getClass.getName)
    }

  override def persistenceId: String = s"${getClass.getName}-${self.path.name}"

  /** Initializes the actor. This method is called before the actor starts processing messages. It
    * registers the actor with the time manager and calls the onStart method.
    */
  override def onStart(): Unit = {
    super.onStart()
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
  }

  private def onFinishInitialize(): Unit =
    if (!isInitialized && creatorManager != null) {
      isInitialized = true
      creatorManager ! InitializeEntityAckEvent(
        entityId = entityId
      )
    }

  override def handleEvent: Receive = {
    case event: SpontaneousEvent => handleSpontaneous(event)
    case event: ActorInteractionEvent => handleInteractWith(event)
    case event: InitializeEvent => initialize(event)
    case event: AdvanceToTick => handleAdvanceToTick(event)
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
        onFinishInitialize()
      } else {
        onFinishInitialize()
        context.stop(self)
      }
    }

  private def registerOnTimeManager(): Unit =
    if (properties.actorType == LoadBalancedDistributed) {
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
      logDebug(s"Ator $entityId registrado com política de tempo: $timePolicy")
    } else {
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
      logDebug(s"Ator $entityId registrado com política de tempo: $timePolicy")
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
    currentTick = event.tick
    currentTimeManager = event.actorRef
    try actSpontaneous(event)
    catch
      case e: Exception =>
        e.printStackTrace()
        onFinishSpontaneous()
    save(event)
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

  /** Handles AdvanceToTick event for TimeStepped simulation paradigm
    */
  private def handleAdvanceToTick(event: AdvanceToTick): Unit =
    if (timePolicy == TimePolicyEnum.TimeSteppedSimulation) {
      currentTick = event.targetTick
      try
        actAdvanceToTick(event)
      catch {
        case e: Exception =>
          logError(s"Erro durante AdvanceToTick para tick ${event.targetTick}: ${e.getMessage}", e)
      } finally
        // Sempre confirmar completamento do tick
        currentTimeManager ! TickCompleted(event.targetTick, getEntityId)
    } else {
      logWarn(s"Recebido AdvanceToTick mas política de tempo é $timePolicy, não TimeStepped")
    }

  /** This method is called when the actor receives an AdvanceToTick event in TimeStepped mode.
    * Override this method to implement time-stepped behavior.
    * @param event
    *   The AdvanceToTick event
    */
  protected def actAdvanceToTick(event: AdvanceToTick): Unit =
    // Default implementation - subclasses should override
    logDebug(s"AdvanceToTick para tick ${event.targetTick} - implementação padrão")

  /** This method is called when the actor receives an interaction event. It should be overridden by
    * the actor to handle the interaction event.
    * @param event
    *   The interaction event
    */
  protected def actInteractWith(event: ActorInteractionEvent): Unit = ()
  
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
        id = IdUtil.format(getEntityId),
        resourceId = IdUtil.format(properties.resourceId),
        classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
        actorRef = getPath
      ),
      scheduleTick = scheduleTick.map(_.toString),
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
              id = IdUtil.format(getEntityId),
              resourceId = IdUtil.format(properties.resourceId),
              classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
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

  protected def getDependency(entityId: String): Dependency =
    dependencies(IdUtil.format(entityId))

  /** Gets the time manager actor reference.
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = timeManager

  /** Gets the time policy for this actor.
    * @return
    *   The time policy
    */
  protected def getTimePolicy: TimePolicyEnum.TimePolicyEnum = timePolicy

  /** Sets the time policy for this actor (should be used carefully).
    * @param policy
    *   The new time policy
    */
  protected def setTimePolicy(policy: TimePolicyEnum.TimePolicyEnum): Unit = {
    timePolicy = policy
    logInfo(s"Política de tempo alterada para: $policy")
  }
  
  protected def toIdentify: Identify =
    Identify(
      id = getEntityId,
      resourceId = IdUtil.format(properties.resourceId),
      classType = StringUtil.getModelClassNameWithoutPackage(getClass.getName),
      actorRef = self.path.name
    )

  override def getStatistics: Map[String, Any] = Map(
    "entityId" -> entityId,
    "shardId" -> shardId,
    "classType" -> getClass.getName,
    "currentTick" -> currentTick,
    "lamportClock" -> getLamportClock,
    "state" -> (if (state != null) state.toString else "null"),
    "dependencies" -> dependencies.keys.mkString(","),
    "reporters" -> (if (reporters != null) reporters.keys.mkString(",") else "null"),
    "timePolicy" -> timePolicy.toString
  )
}
