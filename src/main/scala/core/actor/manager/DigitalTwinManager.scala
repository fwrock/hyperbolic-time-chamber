package org.interscity.htc
package core.actor.manager

import core.actor.manager.digitaltwin.DigitalTwinHandler
import core.actor.manager.digitaltwin.DigitalTwinHandler.EventType
import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent }
import core.entity.state.DefaultState
import core.enumeration.CreationTypeEnum
import core.kafka.KafkaTopicNaming
import core.util.IdUtil
import org.interscity.htc.avro.bus.{ EntityType, EventKind, HtcReadyEvent, HtcReadyEventAvro }
import org.interscity.htc.system.broker.kafka.configuration.subscriber.SubscriberConfiguration

import org.apache.pekko.actor.{ ActorRef, Props, Status }
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.pattern.pipe
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Consumes HtcReadyEvent messages from the HTC-DL Bus (Kafka topic [[KafkaTopicNaming.Bus.ReadyEvents]])
  * and routes them to the corresponding twin actors in the simulation.
  *
  * Events are buffered in `pendingUpdates` and drained on a fixed interval so that twin updates
  * land between simulation ticks rather than interrupting one in progress.
  */
class DigitalTwinManager(timeManager: ActorRef)
    extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "digital-twin-manager"
    ) {

  private val pendingUpdates: mutable.Queue[HtcReadyEvent] = mutable.Queue.empty

  private lazy val handler: DigitalTwinHandler = new DigitalTwinHandler(
    entityIdFn    = () => entityId,
    currentTickFn = () => 0L,
    logFn         = (level, msg) =>
      level match {
        case "WARN"  => logWarn(msg)
        case "ERROR" => logError(msg)
        case _       => logInfo(msg)
      }
  )

  private implicit def ec: ExecutionContext =
    context.system.dispatchers.lookup("pekko.actor.io-dispatcher")

  override def onStart(): Unit = {
    startBusStream()
    scheduleDrainLoop()
  }

  override def handleEvent: Receive = {
    case IncomingBusEvent(event) => handleIncoming(event)
    case ProcessPendingUpdates   => drainQueue()
    case BusStreamFailed(cause)  => handleStreamFailure(cause)
    case Status.Failure(cause)   => handleStreamFailure(cause)
    case RestartBusStream        => startBusStream()
    case _: DestructEvent        => context.stop(self)
    case _                       =>
  }

  private def handleIncoming(event: HtcReadyEvent): Unit = {
    pendingUpdates.enqueue(event)
    if (pendingUpdates.size >= DigitalTwinManager.MaxQueueSizeBeforeEagerDrain)
      drainQueue()
  }

  private def drainQueue(): Unit = {
    var routed = 0
    while (pendingUpdates.nonEmpty) {
      val event = pendingUpdates.dequeue()
      routeEventToTwin(event)
      routed += 1
    }
    if (routed > 0)
      logInfo(s"DigitalTwinManager: drained $routed twin updates")
  }

  private def routeEventToTwin(event: HtcReadyEvent): Unit = {
    entityTypeClassName(event.entityType).foreach { className =>
      try {
        val region    = getShardRef(className)
        val formatted = IdUtil.format(event.entityId)
        val payload   = handler.buildUpdatePayload(event)
        val eventType = deriveEventType(event)
        region ! EntityEnvelopeEvent(
          entityId = formatted,
          event = ActorInteractionEvent(
            tick          = 0L,
            lamportTick   = 0L,
            actorRefId    = entityId,
            shardRefId    = entityId,
            actorPathRef  = self.path.name,
            actorClassType = getClass.getSimpleName,
            eventType     = eventType,
            data          = payload,
            actorType     = CreationTypeEnum.LoadBalancedDistributed.toString,
            resourceId    = ""
          )
        )
      } catch {
        case e: Exception =>
          logWarn(
            s"DigitalTwinManager: failed to route ${event.entityType}/${event.entityId}: ${e.getMessage}"
          )
      }
    }
  }

  private def entityTypeClassName(entityType: EntityType): Option[String] =
    entityType match {
      case EntityType.TRAFFIC_SIGNAL => Some("org.interscity.htc.model.hybrid.actor.TrafficSignal")
      case EntityType.LINK           => Some("org.interscity.htc.model.hybrid.actor.Link")
      case EntityType.CAR            => Some("org.interscity.htc.model.hybrid.actor.Car")
      case EntityType.BIKE           => Some("org.interscity.htc.model.hybrid.actor.Car")
      case EntityType.BUS            => Some("org.interscity.htc.model.hybrid.actor.Bus")
      case EntityType.SUBWAY         => Some("org.interscity.htc.model.hybrid.actor.Subway")
      case EntityType.PERSON         => Some("org.interscity.htc.model.hybrid.actor.Person")
      case EntityType.NODE           => Some("org.interscity.htc.model.hybrid.actor.Node")
    }

  private def deriveEventType(event: HtcReadyEvent): String =
    event.eventKind match {
      case EventKind.ANOMALY      => EventType.DtAnomaly
      case EventKind.COMMAND      => EventType.DtCommand
      case EventKind.STATE_UPDATE => EventType.DtStateUpdate
      case EventKind.TELEMETRY    => EventType.DtUpdate
    }

  private def startBusStream(): Unit = {
    // Pre-existing gap unrelated to this task: `runForeach` needs a Materializer resolvable via
    // an implicit ActorSystem/ClassicActorSystemProvider, which an Actor's `context.system` isn't
    // by default. Matches the `implicit val mat = Materializer(system)` convention already used in
    // core/api/ConfigApiServer.scala.
    implicit val mat: org.apache.pekko.stream.Materializer = org.apache.pekko.stream.Materializer(context.system)
    val consumerSettings = SubscriberConfiguration.consumerConfig(
      groupId = DigitalTwinManager.ConsumerGroupId,
      system  = context.system
    )
    val selfRef = self
    Consumer
      .plainSource(consumerSettings, Subscriptions.topics(KafkaTopicNaming.Bus.ReadyEvents))
      .map(record => HtcReadyEventAvro.deserialize(record.value()))
      .runForeach { result =>
        result.fold(
          err   => selfRef ! BusStreamFailed(err),
          event => selfRef ! IncomingBusEvent(event)
        )
      }
      .pipeTo(self)
  }

  private def handleStreamFailure(cause: Throwable): Unit = {
    logWarn(
      s"DigitalTwinManager: bus stream failed — ${cause.getMessage}. " +
        s"Restarting in ${DigitalTwinManager.StreamRestartDelay.toSeconds}s."
    )
    context.system.scheduler.scheduleOnce(
      delay    = DigitalTwinManager.StreamRestartDelay,
      receiver = self,
      message  = RestartBusStream
    )(ec)
  }

  private def scheduleDrainLoop(): Unit =
    context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = DigitalTwinManager.DrainInterval,
      delay        = DigitalTwinManager.DrainInterval,
      receiver     = self,
      message      = ProcessPendingUpdates
    )(ec)
}

// ── Internal protocol messages ───────────────────────────────────────────────

private[manager] final case class IncomingBusEvent(event: HtcReadyEvent)
private[manager] case object ProcessPendingUpdates
private[manager] final case class BusStreamFailed(cause: Throwable)
private[manager] case object RestartBusStream

object DigitalTwinManager {

  val ConsumerGroupId: String = "htc-digital-twin-manager"

  /** Interval between queue-drain passes. */
  val DrainInterval: FiniteDuration = 100.milliseconds

  /** Delay before restarting a failed Kafka stream. */
  val StreamRestartDelay: FiniteDuration = 5.seconds

  /** Trigger an eager drain when the queue exceeds this size to bound memory usage. */
  val MaxQueueSizeBeforeEagerDrain: Int = 500

  def props(timeManager: ActorRef): Props =
    Props(classOf[DigitalTwinManager], timeManager)
}
