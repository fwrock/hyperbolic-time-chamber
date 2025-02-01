package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef }
import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, ScheduleEvent, SpontaneousEvent }
import core.types.CoreTypes.Tick

import org.apache.pekko.event.{ Logging, LoggingAdapter }
import core.entity.state.BaseState
import core.entity.event.control.execution.{ AcknowledgeTickEvent, DestructEvent, RegisterActorEvent }
import core.entity.control.LamportClock
import core.util.JsonUtil

import org.apache.pekko.cluster.sharding.ShardRegion

import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import org.interscity.htc.core.entity.event.data.BaseEventData

abstract class BaseActor[T <: BaseState](
  protected val actorId: String = null,
  private val timeManager: ActorRef = null,
  private val data: String = null,
  protected val dependencies: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef]()
)(implicit m: Manifest[T])
    extends Actor
    with ActorLogging {

  protected var startTick: Tick = MinValue
  private val lamportClock = new LamportClock()
  protected var currentTick: Tick = 0
  protected var state: T = uninitialized

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

  protected def onStart(): Unit = {}

  protected def handleEvent: Receive = {
    case _ => log.info("Event not handled")
  }

  protected def sendMessageTo[D <: BaseEventData](
    entityId: String,
    actorRef: ActorRef,
    data: D,
    eventType: String = "default"
  ): Unit = {
    lamportClock.increment()
    logEvent(s"Sending message to ${actorRef.path.name} with Lamport clock ${getLamportClock}")
    actorRef ! EntityEnvelopeEvent[D](
      entityId,
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

  private def updateLamportClock(otherClock: Long): Unit =
    lamportClock.update(otherClock)

  private def getLamportClock: Long =
    lamportClock.getClock

  private def handleSpontaneous(event: SpontaneousEvent): Unit = {
    currentTick = event.tick
    actSpontaneous(event)
  }

  protected def sendAcknowledgeTick(): Unit =
    if (timeManager != null && timeManager != self) {
      timeManager ! AcknowledgeTickEvent(tick = currentTick, actorRef = self)
    }

  protected def actSpontaneous(event: SpontaneousEvent): Unit = {}

  private def handleInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {
    updateLamportClock(event.lamportTick)
    logEvent(
      s"Received interaction from ${sender().path.name} with Lamport clock ${getLamportClock}"
    )
    actInteractWith(event)
  }

  def actInteractWith[D <: BaseEventData](event: ActorInteractionEvent[D]): Unit = {}

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

  private def destruct(event: DestructEvent): Unit =
    context.stop(self)

  protected def onFinishSpontaneous(
    scheduleTick: Option[Tick] = None
  ): Unit =
    timeManager ! FinishEvent(
      end = currentTick,
      actorRef = self,
      scheduleEvent = scheduleTick.map(
        tick => ScheduleEvent(tick = tick, actorRef = self)
      )
    )

  protected def getTimeManager: ActorRef = timeManager

  def getActorId: String = actorId
}

object BaseActor {
  val idExtractor: ShardRegion.ExtractEntityId = {
    case EntityEnvelopeEvent(entityId, payload) => (entityId, payload)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case EntityEnvelopeEvent(entityId, _) => (entityId.hashCode % 100).toString
  }
}
