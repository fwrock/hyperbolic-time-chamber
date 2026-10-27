package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Address, Extension, ExtensionId, ExtensionIdProvider, ExtendedActorSystem, PoisonPill, Props }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.sharding.ShardCoordinator
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import scala.collection.{ immutable, mutable }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.*
import java.util.concurrent.atomic.AtomicLong

private[allocation] case class LocalAllocationRegion(typeName: String, region: ActorRef)
private[allocation] case class LocalAllocate(requestId: String, typeName: String, sequence: Long,
  requester: ActorRef, shardId: String, regions: Vector[AllocationRegion])
private[allocation] case class LocalRebalance(requestId: String, typeName: String, sequence: Long,
  regions: Vector[AllocationRegion], inProgress: Set[String])

class AllocationNodeClient(authority: ActorRef, address: Address) extends Actor with ActorLogging {
  private val incarnation = java.util.UUID.randomUUID().toString
  private var epoch: Option[String] = None
  private var failed: Option[String] = None
  private val regions = mutable.Map.empty[String, (ActorRef, String, Boolean)]
  private val bindings = mutable.Map.empty[String, ActorRef]

  private def pin(value: String): Boolean = {
    if (epoch.exists(_ != value)) {
      failed = Some("allocation authority epoch changed; coordinated recovery is required")
      false
    } else { epoch = Some(value); true }
  }
  private def ready(name: String): Boolean = failed.isEmpty && epoch.nonEmpty && regions.get(name).exists(_._3)

  override def receive: Receive = {
    case GetAllocationClientStatus => sender() ! AllocationClientStatus(epoch.getOrElse(""), incarnation,
      address.toString, regions.collect { case (name, (_, _, true)) => name }.toSet, failed)
    case LocalAllocationRegion(name, region) =>
      regions.get(name) match {
        case Some((existing, _, _)) if existing == region => ()
        case Some(_) => failed = Some("local allocation region changed")
        case None =>
          val id = java.util.UUID.randomUUID().toString
          regions(name) = (region, id, false)
          authority ! RegisterAllocationRegion(id, epoch.getOrElse(""), name, address.toString, incarnation, region)
      }
    case e: AllocationRegionRegistered =>
      regions.get(e.typeName).filter(_._2 == e.requestId).foreach { registration =>
        if (pin(e.epoch)) regions(e.typeName) = (registration._1, registration._2, true)
      }
    case e: BindAllocationController =>
      if (failed.nonEmpty) sender() ! AllocationRejected(e.requestId, epoch.getOrElse(""), failed.get)
      else {
        bindings(e.requestId) = sender()
        authority ! e.copy(epoch = epoch.getOrElse(""))
      }
    case e: AllocationControllerBound =>
      bindings.remove(e.requestId).foreach { replyTo =>
        if (pin(e.epoch)) replyTo ! e
        else replyTo ! AllocationRejected(e.requestId, e.epoch, failed.get)
      }
    case e: AllocationRejected =>
      bindings.remove(e.requestId) match {
        case Some(replyTo) => replyTo ! e
        case None if regions.values.exists(_._2 == e.requestId) =>
          failed = Some(e.reason)
          log.error("Allocation region registration failed: {}", e.reason)
        case _ => ()
      }
    case e: LocalAllocate =>
      if (!ready(e.typeName)) sender() ! AllocationRejected(e.requestId, epoch.getOrElse(""),
        failed.getOrElse("allocation region registration is not acknowledged"))
      else authority.tell(AllocateShardRequest(e.requestId, epoch.get, e.typeName, address.toString,
        incarnation, e.sequence, e.requester, e.shardId, e.regions), sender())
    case e: LocalRebalance =>
      if (!ready(e.typeName)) sender() ! AllocationRejected(e.requestId, epoch.getOrElse(""),
        failed.getOrElse("allocation region registration is not acknowledged"))
      else authority.tell(RebalanceShardsRequest(e.requestId, epoch.get, e.typeName, address.toString,
        incarnation, e.sequence, e.regions, e.inProgress), sender())
  }
}

class RemoteShardAllocator(client: ActorRef, typeName: String, address: Address)(implicit system: ActorSystem)
    extends ShardCoordinator.ShardAllocationStrategy {
  private val sequence = new AtomicLong(0L)
  private implicit val timeout: Timeout = Timeout(10.seconds)
  private implicit val executionContext: ExecutionContext = system.dispatcher
  private def rows(allocations: Map[ActorRef, immutable.IndexedSeq[String]]): Vector[AllocationRegion] =
    allocations.iterator.map { case (ref, shards) =>
      AllocationRegion(ref, if (ref.path.address.hasLocalScope) address.toString else ref.path.address.toString, shards.toVector)
    }.toVector.sortBy(_.address)
  override def allocateShard(requester: ActorRef, shardId: String,
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[String]]): Future[ActorRef] = {
    val id = java.util.UUID.randomUUID().toString
    (client ? LocalAllocate(id, typeName, sequence.incrementAndGet(), requester, shardId, rows(currentShardAllocations))).map {
      case AllocateShardReply(`id`, _, region) => region
      case e: AllocationRejected => throw new IllegalStateException(e.reason)
      case _ => throw new IllegalStateException("uncorrelated allocation reply")
    }
  }
  override def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[String]],
    rebalanceInProgress: Set[String]): Future[Set[String]] = {
    val id = java.util.UUID.randomUUID().toString
    (client ? LocalRebalance(id, typeName, sequence.incrementAndGet(), rows(currentShardAllocations), rebalanceInProgress)).map {
      case RebalanceShardsReply(`id`, _, shards) => shards
      case e: AllocationRejected => throw new IllegalStateException(e.reason)
      case _ => throw new IllegalStateException("uncorrelated rebalance reply")
    }
  }
}

class AllocationRuntime(system: ExtendedActorSystem) extends Extension {
  val enabled: Boolean = system.settings.config.getBoolean("htc.load-balance-manager.enabled")
  private val name = "allocation-authority"
  private lazy val proxy: ActorRef = {
    system.actorOf(ClusterSingletonManager.props(AllocationAuthority.props(), PoisonPill,
      ClusterSingletonManagerSettings(system)), name)
    system.actorOf(ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system)), s"$name-proxy")
  }
  private lazy val client = system.actorOf(Props(classOf[AllocationNodeClient], proxy,
    Cluster(system).selfAddress), "allocation-node-client")
  private val adapters = mutable.Map.empty[String, RemoteShardAllocator]
  def start(): Unit = if (enabled) {
    SpatialDirectoryRuntime(system).start()
    val initialized = client
    ()
  }
  def strategy(typeName: String): RemoteShardAllocator = synchronized {
    require(enabled, "custom allocation is disabled")
    adapters.getOrElseUpdate(typeName, new RemoteShardAllocator(client, typeName, Cluster(system).selfAddress)(system))
  }
  def register(typeName: String, region: ActorRef): Unit = {
    require(enabled, "custom allocation is disabled")
    client ! LocalAllocationRegion(typeName, region)
  }
  def bind(request: BindAllocationController): Future[AllocationControllerBound] = {
    require(enabled, "custom allocation is disabled")
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val ec: ExecutionContext = system.dispatcher
    (client ? request).map {
      case e: AllocationControllerBound if e.requestId == request.requestId && e.controllerId == request.controllerId => e
      case e: AllocationRejected => throw new IllegalStateException(e.reason)
      case _ => throw new IllegalStateException("uncorrelated controller binding reply")
    }
  }
  def request(message: AllocationWire): Future[Any] = {
    require(enabled, "custom allocation is disabled")
    implicit val timeout: Timeout = Timeout(10.seconds)
    proxy ? message
  }
  def status(): Future[AllocationClientStatus] = {
    require(enabled, "custom allocation is disabled")
    implicit val timeout: Timeout = Timeout(5.seconds)
    (client ? GetAllocationClientStatus).mapTo[AllocationClientStatus]
  }
}

object AllocationRuntime extends ExtensionId[AllocationRuntime] with ExtensionIdProvider {
  override def lookup: ExtensionId[? <: Extension] = AllocationRuntime
  override def createExtension(system: ExtendedActorSystem): AllocationRuntime = new AllocationRuntime(system)
}
