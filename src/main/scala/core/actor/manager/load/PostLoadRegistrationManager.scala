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
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Dedicated manager for post-load registration orchestration.
  *
  * Lifecycle:
  *   1. '''Accumulation phase''' (during EAGER loading): receives NeedsPostLoadRegistrationEvent
  *      from creators for each actor that opts in.
  *   2. '''Execution phase''' (triggered by SimulationManager via StartPostLoadRegistrationPhaseEvent):
  *      fans out PostLoadRegistrationEvent to all accumulated actors and waits for ACKs.
  *   3. '''Completion''': once all ACKs received (or max retries exhausted), sends
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
  private var retryTask: Cancellable = _
  private var retryCount: Int = 0

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
    case event: NeedsPostLoadRegistrationEvent => handleNeedsRegistration(event)
    case StartPostLoadRegistrationPhaseEvent   => handleTrigger()
    case event: PostLoadRegistrationAckEvent   => handleAck(event)
    case RetryPostLoadRegistration             => handleRetry()
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
    logInfo(
      s"Post-load registration phase triggered. ${pendingRegistrations.size} entities to register."
    )
    if (pendingRegistrations.isEmpty) {
      finish()
    } else {
      pendingAcks ++= pendingRegistrations.keys
      sendRegistrationEvents()
      retryTask = context.system.scheduler.scheduleWithFixedDelay(
        RETRY_INTERVAL,
        RETRY_INTERVAL,
        self,
        RetryPostLoadRegistration
      )
    }
  }

  private def sendRegistrationEvents(): Unit = {
    val toRemove = mutable.Buffer[String]()
    pendingAcks.foreach { entityId =>
      pendingRegistrations.get(entityId) match {
        case Some(classType) =>
          try {
            val shardRef =
              ClusterSharding(context.system).shardRegion(StringUtil.getModelClassName(classType))
            shardRef ! EntityEnvelopeEvent(
              entityId = entityId,
              event = PostLoadRegistrationEvent(coordinatorRef = self)
            )
          } catch {
            case e: Exception =>
              logWarn(
                s"Failed to send PostLoadRegistrationEvent to $entityId ($classType): ${e.getMessage}. Skipping."
              )
              toRemove += entityId
          }
        case None =>
          logWarn(s"No classType found for $entityId — removing from pending.")
          toRemove += entityId
      }
    }
    toRemove.foreach(pendingAcks.remove)
    if (pendingAcks.isEmpty) finish()
  }

  private def handleAck(event: PostLoadRegistrationAckEvent): Unit = {
    pendingAcks.remove(event.entityId)
    if (pendingAcks.isEmpty) finish()
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
          s"Proceeding to avoid simulation deadlock."
      )
      finish()
    } else {
      logWarn(
        s"Retry $retryCount/$MAX_RETRIES (${elapsedSec}s elapsed). " +
          s"${pendingAcks.size} actors still pending. By class: [$byClass]. " +
          s"Sample: [$sample$more]"
      )
      sendRegistrationEvents()
    }
  }

  private def finish(): Unit = {
    if (retryTask != null) retryTask.cancel()
    logInfo(
      s"Post-load registration complete " +
        s"(${pendingRegistrations.size} total, ${pendingAcks.size} still pending). " +
        s"Notifying SimulationManager."
    )
    simulationManager ! PostLoadRegistrationDoneEvent(actorRef = self)
    selfDestruct()
  }
}

object PostLoadRegistrationManager {
  def props(simulationManager: ActorRef): Props =
    Props(classOf[PostLoadRegistrationManager], simulationManager)
}


