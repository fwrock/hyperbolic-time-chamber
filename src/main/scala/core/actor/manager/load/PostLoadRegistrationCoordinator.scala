package org.interscity.htc
package core.actor.manager.load

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import org.apache.pekko.cluster.sharding.ClusterSharding
import org.interscity.htc.core.actor.manager.BaseManager
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.util.StringUtil
import org.interscity.htc.core.entity.event.control.load.{
  NeedsPostLoadRegistrationEvent,
  PostLoadRegistrationAckEvent,
  PostLoadRegistrationDoneEvent,
  PostLoadRegistrationEvent,
  TriggerPostLoadRegistrationEvent
}
import org.interscity.htc.core.entity.state.DefaultState

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

/** Coordinator actor that orchestrates the post-load registration phase.
  *
  * Lifecycle:
  *   1. '''Accumulation phase''' (during EAGER loading): receives NeedsPostLoadRegistrationEvent
  *      directly from creators for each actor that opts in. Entities from the simulation config
  *      `postLoadRegistrationClasses` list are also forwarded here by the creator automatically.
  *   2. '''Execution phase''' (triggered by LoadDataManager after all EAGER loading completes):
  *      receives TriggerPostLoadRegistrationEvent, fans out PostLoadRegistrationEvent to all
  *      accumulated entities, and waits for their PostLoadRegistrationAckEvents.
  *   3. '''Completion''': once all ACKs are received (or max retries exhausted), sends
  *      PostLoadRegistrationDoneEvent to LoadDataManager and stops itself.
  *
  * The retry watchdog re-sends PostLoadRegistrationEvent every RETRY_INTERVAL to entities that
  * have not yet ACKed, similar to how CreatorLoadData retries InitializeEvent. After MAX_RETRIES
  * attempts the coordinator proceeds regardless, so a stuck entity never deadlocks the simulation.
  *
  * @param managerRef
  *   Reference to LoadDataManager; receives PostLoadRegistrationDoneEvent when phase completes.
  */
class PostLoadRegistrationCoordinator(
  private val managerRef: ActorRef
) extends BaseManager[DefaultState](
      actorId = "post-load-registration-coordinator"
    ) {

  private val pendingRegistrations: mutable.Map[String, String] = mutable.Map.empty

  private val pendingAcks: mutable.Set[String] = mutable.Set.empty

  private var triggered: Boolean = false

  private var retryTask: Cancellable = _
  private var retryCount: Int = 0

  private val RETRY_INTERVAL: FiniteDuration = 10.seconds
  private val MAX_RETRIES: Int = 12 // 120 s total before giving up

  private case object RetryPostLoadRegistration

  override def handleEvent: Receive = {
    case event: NeedsPostLoadRegistrationEvent => handleNeedsRegistration(event)
    case _: TriggerPostLoadRegistrationEvent   => handleTrigger()
    case event: PostLoadRegistrationAckEvent   => handleAck(event)
    case RetryPostLoadRegistration             => handleRetry()
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
        s"PostLoadRegistrationCoordinator: received duplicate TriggerPostLoadRegistrationEvent — ignoring. " +
          s"Already triggered with ${pendingRegistrations.size} entities, ${pendingAcks.size} still pending."
      )
      return
    }
    triggered = true
    logInfo(
      s"PostLoadRegistrationCoordinator: triggered with ${pendingRegistrations.size} entities to register"
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

  /** Fans out PostLoadRegistrationEvent to all entities still in pendingAcks. */
  private def sendRegistrationEvents(): Unit = {
    val toRemove = mutable.Buffer[String]()
    pendingAcks.foreach { entityId =>
      pendingRegistrations.get(entityId) match {
        case Some(classType) =>
          try {
            val shardRef = ClusterSharding(context.system).shardRegion(StringUtil.getModelClassName(classType))
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
    if (retryCount >= MAX_RETRIES) {
      logWarn(
        s"PostLoadRegistrationCoordinator: reached $MAX_RETRIES retries. " +
          s"${pendingAcks.size} entities did not ACK: " +
          s"${pendingAcks.take(20).mkString(", ")}${if (pendingAcks.size > 20) "..." else ""}. " +
          s"Proceeding to avoid simulation deadlock."
      )
      finish()
    } else {
      logWarn(
        s"PostLoadRegistrationCoordinator: retry $retryCount/$MAX_RETRIES. " +
          s"${pendingAcks.size} entities still pending: ${pendingAcks.take(10).mkString(", ")}"
      )
      sendRegistrationEvents()
    }
  }
  
  private def finish(): Unit = {
    if (retryTask != null) retryTask.cancel()
    logInfo(
      s"PostLoadRegistrationCoordinator: all registrations complete " +
        s"(${pendingRegistrations.size} total, ${pendingAcks.size} still pending). " +
        s"Notifying LoadDataManager."
    )
    managerRef ! PostLoadRegistrationDoneEvent(actorRef = self)
    selfDestruct()
  }
}

object PostLoadRegistrationCoordinator {
  def props(managerRef: ActorRef): Props =
    Props(classOf[PostLoadRegistrationCoordinator], managerRef)
}

