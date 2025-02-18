package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef }
import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, ScheduleEvent, SpontaneousEvent }
import core.types.CoreTypes.Tick

import core.entity.state.BaseState
import core.entity.event.control.execution.{ AcknowledgeTickEvent, DestructEvent, RegisterActorEvent }
import core.entity.control.LamportClock
import core.util.JsonUtil

import org.apache.pekko.cluster.sharding.ShardRegion

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import org.interscity.htc.core.entity.event.data.BaseEventData

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
  protected val actorId: String,
  private val timeManager: ActorRef = null,
  private val data: String,
  protected val dependencies: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef]()
)(implicit m: Manifest[T])
    extends Actor
    with ActorLogging {

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0
  protected var state: T = uninitialized
  private var currentTimeManager: ActorRef = uninitialized

  /** Initializes the actor. This method is called before the actor starts processing messages. It
    * registers the actor with the time manager and calls the onStart method.
    */
  override def preStart(): Unit = {
    super.preStart()
    if (timeManager != null && !timeManager.equals(self)) {
      log.info(s"Registering actor with time manager at tick $startTick")
      timeManager ! RegisterActorEvent(startTick = startTick, actorRef = self)
    }
    if (data != null) {
      state = JsonUtil.fromJson[T](data)
      startTick = state.getStartTick
    }
    onStart()
  }

  /** Starts the actor. This method is called on start the actor before starts processing messages.
    * If you want to perform any action before the actor starts processing messages, you should
    * override this method.
    */
  protected def onStart(): Unit = {}

  protected def handleEvent: Receive = {
    case _ => log.info("Event not handled")
  }

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
  protected def sendMessageTo[D <: BaseEventData](
    actorId: String,
    actorRef: ActorRef,
    data: D,
    eventType: String = "default"
  ): Unit = {
    lamportClock.increment()
    logEvent(s"Sending message to ${actorRef.path.name} with Lamport clock ${getLamportClock}")
    actorRef ! EntityEnvelopeEvent[D](
      actorId,
      ActorInteractionEvent(
        tick = currentTick,
        lamportTick = getLamportClock,
        actorRefId = getActorId,
        actorRef = self,
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
  protected def actSpontaneous(event: SpontaneousEvent): Unit = {}

  /** Handles the interaction with another actor. This method is called when the actor receives an
    * interaction event. It updates the Lamport clock and calls the actInteractWith method.
    * @param event
    *   The interaction event
    */
  private def handleInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
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
  def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {}

  /** Logs an event. This method is called when the actor wants to log an event.
    * @param eventInfo
    *   The information of the event
    */
  protected def logEvent(eventInfo: String): Unit = {
    val logMessage = s"$actorId - $eventInfo"
    log.info(logMessage)
  }

  override def receive: Receive = {
    case event: SpontaneousEvent         => handleSpontaneous(event)
    case event: ActorInteractionEvent[_] => handleInteractWith(event)
    case event: DestructEvent            => destruct(event)
    case event                           => handleEvent(event)
  }

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
    self ! DestructEvent(currentTick, currentTimeManager)

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
    scheduleTick: Option[Tick] = None
  ): Unit =
    timeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      scheduleEvent = scheduleTick.map(
        tick => ScheduleEvent(tick = tick, actorRef = self)
      ),
      timeManager = currentTimeManager
    )

  protected def selfSpontaneous(): Unit =
    self ! SpontaneousEvent(currentTick, currentTimeManager)

  protected def scheduleEvent(tick: Tick): Unit =
    timeManager ! ScheduleEvent(tick = tick, actorRef = self)

  /** Gets the time manager actor reference.
    * @return
    *   The time manager actor reference
    */
  protected def getTimeManager: ActorRef = timeManager

  /** Gets the actor id.
    * @return
    *   The actor id
    */
  def getActorId: String = actorId
}

/** The companion object of the BaseActor class. It provides the idExtractor and shardResolver for
  * the shard region. The idExtractor is used to extract the entity id from the message. The
  * shardResolver is used to extract the shard id from the message.
  */
object BaseActor {
  val idExtractor: ShardRegion.ExtractEntityId = {
    case EntityEnvelopeEvent(entityId, payload) => (entityId, payload)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case EntityEnvelopeEvent(entityId, _) => (entityId.hashCode % 100).toString
  }
}
