package org.interscity.htc
package core.actor.manager.load

import core.actor.manager.BaseManager
import core.entity.event.control.load.{
  NeedsPostLoadRegistrationEvent,
  PostLoadRegistrationAckEvent,
  PostLoadRegistrationDoneEvent,
  PostLoadRegistrationEvent,
  StartPostLoadRegistrationPhaseEvent
}
import core.entity.state.DefaultState
import core.util.ManagerConstantsUtil.POST_LOAD_REGISTRATION_MANAGER_ACTOR_NAME

import org.apache.pekko.actor.{ActorRef, Cancellable, Props}
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.util.StringUtil

import scala.collection.mutable
import core.runtime.accounting.RuntimeFailure
import scala.concurrent.duration.*

/** Dedicated manager for post-load registration orchestration.
  *
  * Lifecycle:
  *   1. '''Accumulation phase''' (during EAGER loading): receives NeedsPostLoadRegistrationEvent
  *      from creators for each actor that opts in.
  *   2. '''Execution phase''' (triggered by SimulationManager via StartPostLoadRegistrationPhaseEvent):
  *      sends a bounded window of registration requests and waits for delivery ACKs.
  *   3. '''Completion''': once all ACKs are received, sends
  *      PostLoadRegistrationDoneEvent to SimulationManager.
  */
class PostLoadRegistrationManager(
  val simulationManager: ActorRef
) extends BaseManager[DefaultState](
      actorId = POST_LOAD_REGISTRATION_MANAGER_ACTOR_NAME,
      timeManager = null
    ) {

  private val pendingRegistrations: mutable.Map[String, String] = mutable.Map.empty
  private val pendingAcks: mutable.Set[String] = mutable.Set.empty
  private var triggered: Boolean = false
  private var failed = false
  private var retryTask: Cancellable = _
  private var retryCount: Int = 0
  private var totalRegistrations = 0
  private var queued = Iterator.empty[String]
  private val maxInFlight = scala.util.Try(
    context.system.settings.config.getInt("htc.post-load-registration.max-in-flight")
  ).getOrElse(256)
  require(maxInFlight > 0, "post-load registration max-in-flight must be positive")
  private implicit def executionContext: scala.concurrent.ExecutionContext = context.dispatcher

  protected def dispatchRegistration(entityId: String, classType: String): Unit = {
    val shardRef = ClusterSharding(context.system).shardRegion(StringUtil.getModelClassName(classType))
    shardRef ! EntityEnvelopeEvent(entityId, PostLoadRegistrationEvent(coordinatorRef = self))
  }

  private def fail(reason: String): Unit = if (!failed) {
    failed = true
    logError(reason)
    simulationManager ! RuntimeFailure(reason)
    selfDestruct()
  }

  private val RETRY_INTERVAL: FiniteDuration = {
    val secs = scala.util.Try(
      context.system.settings.config.getInt("htc.post-load-registration.retry-interval-seconds")
    ).getOrElse(10)
    secs.seconds
  }

  private val MAX_RETRIES: Int =
    scala.util.Try(
      context.system.settings.config.getInt("htc.post-load-registration.max-retries")
    ).getOrElse(120)

  private case object RetryPostLoadRegistration

  override def handleEvent: Receive = {
    case _ if failed                          => ()
    case event: NeedsPostLoadRegistrationEvent => handleNeedsRegistration(event)
    case StartPostLoadRegistrationPhaseEvent   => handleTrigger()
    case event: PostLoadRegistrationAckEvent   => handleAck(event)
    case RetryPostLoadRegistration             => handleRetry()
    case RuntimeFailure(reason)               => fail(reason)
    case _: StopSimulationEvent                => selfDestruct()
  }

  private def handleNeedsRegistration(event: NeedsPostLoadRegistrationEvent): Unit =
    if (!triggered) {
      pendingRegistrations.put(event.entityId, event.classType)
    } else {
      logWarn(
        s"Received NeedsPostLoadRegistrationEvent after trigger — ignoring (${event.entityId})"
      )
    }

  private def handleTrigger(): Unit = {
    if (triggered) {
      logWarn(
        s"Received duplicate StartPostLoadRegistrationPhaseEvent — ignoring. " +
          s"Already triggered with ${pendingRegistrations.size} entities, ${pendingAcks.size} still pending."
      )
      return
    }
    triggered = true
    totalRegistrations = pendingRegistrations.size
    logInfo(
      s"Post-load registration phase triggered. ${pendingRegistrations.size} entities to register."
    )
    if (pendingRegistrations.isEmpty) {
      finish()
    } else {
      queued = pendingRegistrations.keysIterator.toVector.iterator
      fillWindow()
      retryTask = context.system.scheduler.scheduleWithFixedDelay(
        RETRY_INTERVAL,
        RETRY_INTERVAL,
        self,
        RetryPostLoadRegistration
      )
    }
  }

  private def sendRegistration(entityId: String): Unit = {
    try dispatchRegistration(entityId, pendingRegistrations(entityId))
    catch {
      case scala.util.control.NonFatal(error) =>
        fail(s"Cannot register $entityId: ${error.getMessage}")
    }
  }

  private def fillWindow(): Unit = {
    while (!failed && pendingAcks.size < maxInFlight && queued.hasNext) {
      val entityId = queued.next()
      pendingAcks.add(entityId)
      sendRegistration(entityId)
    }
    if (!failed && pendingRegistrations.isEmpty) finish()
  }

  private def handleAck(event: PostLoadRegistrationAckEvent): Unit = {
    if (pendingAcks.remove(event.entityId)) {
      pendingRegistrations.remove(event.entityId)
      retryCount = 0
      fillWindow()
    }
  }

  private def handleRetry(): Unit = {
    retryCount += 1
    val elapsedSec = retryCount * RETRY_INTERVAL.toSeconds
    val byClass = pendingAcks
      .groupBy(id => pendingRegistrations.getOrElse(id, "unknown"))
      .map { case (cls, ids) => s"${StringUtil.getModelClassName(cls)}=${ids.size}" }
      .mkString(", ")
    val sample = pendingAcks.take(5).mkString(", ")
    val more   = if (pendingAcks.size > 5) s" (+${pendingAcks.size - 5} more)" else ""

    if (retryCount >= MAX_RETRIES) {
      logWarn(
        s"Max retries ($MAX_RETRIES) reached after ${elapsedSec}s. " +
          s"${pendingAcks.size} actors did not ACK. By class: [$byClass]. " +
          s"Sample: [${pendingAcks.take(20).mkString(", ")}${if (pendingAcks.size > 20) "..." else ""}]. " +
          s"Startup failed; the registration barrier remains closed."
      )
      fail("Post-load registration retry limit exceeded with unacknowledged actors")
    } else {
      logWarn(
        s"Retry $retryCount/$MAX_RETRIES (${elapsedSec}s elapsed). " +
          s"${pendingAcks.size} actors still pending. By class: [$byClass]. " +
          s"Sample: [$sample$more]"
      )
      pendingAcks.toVector.foreach(sendRegistration)
    }
  }

  private def finish(): Unit = {
    require(pendingRegistrations.isEmpty && pendingAcks.isEmpty, "registration barrier is not closed")
    if (retryTask != null) retryTask.cancel()
    logInfo(
      s"Post-load registration complete " +
        s"(${totalRegistrations} total, ${pendingRegistrations.size} still pending). " +
        s"Notifying SimulationManager."
    )
    simulationManager ! PostLoadRegistrationDoneEvent(actorRef = self)
    selfDestruct()
  }
  override def postStop(): Unit = {
    if (retryTask != null) retryTask.cancel()
    super.postStop()
  }
}

object PostLoadRegistrationManager {
  def props(simulationManager: ActorRef): Props =
    Props(classOf[PostLoadRegistrationManager], simulationManager)
}


