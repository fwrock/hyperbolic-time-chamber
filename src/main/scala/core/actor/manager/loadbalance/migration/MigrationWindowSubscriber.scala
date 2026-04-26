package org.interscity.htc
package core.actor.manager.loadbalance.migration

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import core.entity.event.control.migration.{
  MigrationWindowAckEvent,
  MigrationWindowCloseEvent,
  MigrationWindowOpenEvent
}
import core.util.{ DistributedUtil, ManagerConstantsUtil }

/** Per-node actor that participates in the distributed migration window protocol.
  *
  * One instance is started by [[core.HyperbolicTimeChamber]] on every cluster node
  * (regular actor, NOT a singleton). It:
  *   1. Creates a cluster-singleton proxy for the SnapshotManager and registers it in
  *      [[MigrationStateStoreRegistry]] so that entities on this JVM can reach SM without
  *      a blocking lookup.
  *   2. Subscribes to the DistributedPubSub topic `"migration-window"`.
  *   3. When [[MigrationWindowOpenEvent]] arrives from the LBM:
  *        - Sets [[MigrationStateStoreRegistry.isMigrationActive]] = true
  *        - ACKs to event.lbmRef with phase = "open"
  *   4. When [[MigrationWindowCloseEvent]] arrives from the LBM:
  *        - Sets [[MigrationStateStoreRegistry.isMigrationActive]] = false
  *        - ACKs to event.lbmRef with phase = "close"
  *
  * === Why per-node (not singleton)? ===
  * The flag must be set on *every* JVM before Pekko hands off shards, so the subscriber
  * needs to run locally on each node and update the JVM-local AtomicBoolean directly.
  * A singleton would only run on one node and defeat the purpose.
  */
class MigrationWindowSubscriber extends Actor with ActorLogging {

  private val cluster   = Cluster(context.system)
  private val mediator  = DistributedPubSub(context.system).mediator
  private val nodeAddr  = cluster.selfAddress.toString

  override def preStart(): Unit = {
    val smProxy = DistributedUtil.createSingletonProxy(
      context.system,
      ManagerConstantsUtil.SNAPSHOT_MANAGER_ACTOR_NAME
    )
    MigrationStateStoreRegistry.registerSnapshotManager(smProxy)
    log.info("MigrationWindowSubscriber: SM proxy registered in MigrationStateStoreRegistry")

    mediator ! Subscribe(MigrationWindowSubscriber.TOPIC, self)
  }

  override def receive: Receive = {
    case SubscribeAck(Subscribe(MigrationWindowSubscriber.TOPIC, _, _)) =>
      log.info("MigrationWindowSubscriber: subscribed to topic '{}'", MigrationWindowSubscriber.TOPIC)

    case event: MigrationWindowOpenEvent =>
      log.info("MigrationWindowSubscriber [{}]: window OPEN for batch '{}'", nodeAddr, event.batchId)
      MigrationStateStoreRegistry.isMigrationActive.set(true)
      if (event.lbmRef != null) {
        event.lbmRef ! MigrationWindowAckEvent(
          batchId     = event.batchId,
          phase       = MigrationWindowSubscriber.PHASE_OPEN,
          nodeAddress = nodeAddr
        )
      }

    case event: MigrationWindowCloseEvent =>
      log.info("MigrationWindowSubscriber [{}]: window CLOSE for batch '{}'", nodeAddr, event.batchId)
      MigrationStateStoreRegistry.isMigrationActive.set(false)
      if (event.lbmRef != null) {
        event.lbmRef ! MigrationWindowAckEvent(
          batchId     = event.batchId,
          phase       = MigrationWindowSubscriber.PHASE_CLOSE,
          nodeAddress = nodeAddr
        )
      }
  }
}

object MigrationWindowSubscriber {
  val TOPIC: String      = "migration-window"
  val PHASE_OPEN: String  = "open"
  val PHASE_CLOSE: String = "close"

  def props(): Props = Props(new MigrationWindowSubscriber)

  /** Convenience factory to start one subscriber per node from HyperbolicTimeChamber. */
  def startOnNode(system: ActorSystem): ActorRef =
    system.actorOf(props(), "migration-window-subscriber")
}
