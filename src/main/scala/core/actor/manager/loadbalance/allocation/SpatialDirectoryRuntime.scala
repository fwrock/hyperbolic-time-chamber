package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ Actor, ActorRef, Address, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, PoisonPill, Props, Terminated }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings }
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.control.NonFatal

private[allocation] case class LocalPublishSpatial(requestId: String, entries: Vector[SpatialAssignment])
private[allocation] case class ObserveSpatialDirectory(controller: ActorRef)

class SpatialDirectoryReplica(authority: ActorRef, address: Address) extends Actor {
  private val incarnation = java.util.UUID.randomUUID().toString
  private val registrationId = java.util.UUID.randomUUID().toString
  private var owner: Option[ActorRef] = None
  private var epoch: Option[String] = None
  private var ready = false
  private var failure: Option[String] = None
  private val observers = mutable.Set.empty[ActorRef]
  private val waiting = mutable.Queue.empty[(LocalPublishSpatial, ActorRef)]

  override def preStart(): Unit = authority ! RegisterSpatialReplica(registrationId, address.toString, incarnation, self)
  private def fail(reason: String): Unit = if (failure.isEmpty) {
    failure = Some(reason)
    ready = false
    waiting.dequeueAll(_ => true).foreach { case (e, replyTo) => replyTo ! SpatialDirectoryRejected(e.requestId, reason) }
    observers.foreach(_ ! SpatialDirectoryFailed(epoch.getOrElse(""), reason))
  }
  private def publish(e: LocalPublishSpatial, replyTo: ActorRef): Unit =
    authority.tell(PublishSpatialAssignments(e.requestId, epoch.get, address.toString, incarnation, e.entries), replyTo)

  override def receive: Receive = {
    case ObserveSpatialDirectory(ref) =>
      observers += ref
      failure.foreach(reason => ref ! SpatialDirectoryFailed(epoch.getOrElse(""), reason))
    case GetSpatialDirectoryStatus => sender() ! SpatialDirectoryStatus(epoch.getOrElse(""),
      SpatialShardIdRegistry.directoryRevision, ready, SpatialShardIdRegistry.publishedSize, failure)
    case e: InstallSpatialAssignments if failure.isEmpty =>
      try {
        require(owner.forall(_ == sender()) && epoch.forall(_ == e.epoch), "spatial directory authority changed")
        require(epoch.nonEmpty || (e.replace && e.requestId == registrationId), "unsolicited spatial directory installation")
        SpatialShardIdRegistry.installDirectory(e.epoch, e.revision, e.entries, e.replace)
        if (owner.isEmpty) context.watch(sender())
        owner = Some(sender())
        epoch = Some(e.epoch)
        sender() ! SpatialAssignmentsInstalled(e.requestId, e.epoch, e.revision, address.toString, incarnation)
      } catch { case NonFatal(error) =>
        sender() ! SpatialInstallFailed(e.requestId, e.epoch, address.toString, incarnation, error.getMessage)
        fail(error.getMessage)
      }
    case e: SpatialReplicaRegistered if e.requestId == registrationId && epoch.contains(e.epoch) && owner.contains(sender()) =>
      ready = failure.isEmpty
      if (ready) waiting.dequeueAll(_ => true).foreach { case (request, replyTo) => publish(request, replyTo) }
    case e: LocalPublishSpatial =>
      if (failure.nonEmpty) sender() ! SpatialDirectoryRejected(e.requestId, failure.get)
      else if (ready) publish(e, sender())
      else waiting.enqueue(e -> sender())
    case e: SpatialDirectoryRejected if e.requestId == registrationId => fail(e.reason)
    case e: SpatialDirectoryFailed if owner.contains(sender()) && epoch.contains(e.epoch) => fail(e.reason)
    case Terminated(ref) if owner.contains(ref) => fail("spatial directory authority lost; coordinated recovery is required")
    case _ => ()
  }
}

class SpatialDirectoryRuntime(system: ExtendedActorSystem) extends Extension {
  val enabled: Boolean = system.settings.config.getBoolean("htc.load-balance-manager.enabled")
  private lazy val proxy: ActorRef = {
    val name = "spatial-directory"
    system.actorOf(ClusterSingletonManager.props(Props(classOf[SpatialDirectoryAuthority]), PoisonPill,
      ClusterSingletonManagerSettings(system)), name)
    system.actorOf(ClusterSingletonProxy.props(s"/user/$name", ClusterSingletonProxySettings(system)), s"$name-proxy")
  }
  private lazy val replica = system.actorOf(Props(classOf[SpatialDirectoryReplica], proxy,
    Cluster(system).selfAddress), "spatial-directory-replica")
  def start(): Unit = if (enabled) { val initialized = replica; () }
  def observe(controller: ActorRef): Unit = { require(enabled); replica ! ObserveSpatialDirectory(controller) }
  def publish(requestId: String, entries: Vector[SpatialAssignment]): Future[SpatialAssignmentsPublished] = {
    require(enabled, "spatial directory is disabled")
    implicit val timeout: Timeout = Timeout(20.seconds)
    implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
    (replica ? LocalPublishSpatial(requestId, entries)).map {
      case e: SpatialAssignmentsPublished if e.requestId == requestId &&
          SpatialShardIdRegistry.confirmsPublication(e.epoch, e.revision, entries) => e
      case e: SpatialDirectoryRejected => throw new IllegalStateException(e.reason)
      case _ => throw new IllegalStateException("uncorrelated spatial publication reply")
    }
  }
  def status(): Future[SpatialDirectoryStatus] = {
    require(enabled, "spatial directory is disabled")
    implicit val timeout: Timeout = Timeout(5.seconds)
    (replica ? GetSpatialDirectoryStatus).mapTo[SpatialDirectoryStatus]
  }
}

object SpatialDirectoryRuntime extends ExtensionId[SpatialDirectoryRuntime] with ExtensionIdProvider {
  override def lookup: ExtensionId[? <: Extension] = SpatialDirectoryRuntime
  override def createExtension(system: ExtendedActorSystem): SpatialDirectoryRuntime = new SpatialDirectoryRuntime(system)
}
