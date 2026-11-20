package org.interscity.htc
package core.actor.manager.report

import com.typesafe.config.Config
import core.enumeration.ReportTypeEnum
import org.apache.pekko.actor.{ ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.pattern.ask
import org.apache.pekko.routing.{ ActorRefRoutee, GetRoutees, Routees }
import org.apache.pekko.util.Timeout

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.util.{ Success, Try }

/** Per-JVM view of the report sinks that live on this JVM.
  *
  * Each enabled report strategy is one cluster router pool (round-robin) with routees spread over
  * the nodes, so with N nodes about (N-1)/N of the report traffic crosses the network. With
  * `htc.report-manager.placement = node-local` every actor instead sends to a routee of the same
  * strategy that runs in its own JVM, chosen stably by entity id. This only changes the destination:
  * the sink actors, their acknowledgements, flush and drain handshakes are the existing ones.
  *
  * Resolution is lazy and never blocks: until the routees of a router have settled, or when this
  * JVM hosts none of them, the router itself is returned and routing is unchanged.
  */
final class NodeLocalReporters(system: ExtendedActorSystem) extends Extension {
  private val settled = new ConcurrentHashMap[ActorRef, Vector[ActorRef]]()
  private val inFlight = ConcurrentHashMap.newKeySet[ActorRef]()
  private val lastAttemptNanos = new ConcurrentHashMap[ActorRef, java.lang.Long]()
  private val selfAddress = system.provider.getDefaultAddress

  private val RetryAfterNanos = 1.second.toNanos
  private implicit val timeout: Timeout = Timeout(5.seconds)

  /** True once the routees of `router` have been seen in full (even when none is local). */
  def isSettled(router: ActorRef): Boolean = settled.containsKey(router)

  /** Local sinks of `router` once its routees have settled (empty when none is local), `None` while
    * unresolved. Asking triggers the resolution, so callers cache only a `Some`.
    */
  def localSinks(router: ActorRef, expectedRoutees: => Int): Option[Vector[ActorRef]] =
    Option(settled.get(router)).orElse {
      request(router, expectedRoutees)
      None
    }

  /** The local routee for `key`, or `router` while unresolved or when this JVM hosts none. */
  def pick(router: ActorRef, expectedRoutees: => Int, key: String): ActorRef =
    localSinks(router, expectedRoutees) match {
      case Some(local) if local.nonEmpty => local(Math.floorMod(key.hashCode, local.size))
      case _                             => router
    }

  private def request(router: ActorRef, expectedRoutees: => Int): Unit = {
    val now = System.nanoTime()
    val last = lastAttemptNanos.get(router)
    if ((last == null || now - last >= RetryAfterNanos) && inFlight.add(router)) {
      lastAttemptNanos.put(router, now)
      implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
      (router ? GetRoutees).onComplete { result =>
        inFlight.remove(router)
        result match {
          case Success(Routees(routees)) =>
            Try(expectedRoutees).foreach { expected =>
              settle(router, routees.collect { case ActorRefRoutee(ref) => ref }.toVector, expected, isLocal)
            }
          case _ => ()
        }
      }
    }
  }

  private def isLocal(ref: ActorRef): Boolean =
    ref.path.address.hasLocalScope || ref.path.address == selfAddress

  /** Record the local routees once all expected routees exist; earlier snapshots are discarded. */
  private[report] def settle(
    router: ActorRef,
    routees: Vector[ActorRef],
    expected: Int,
    local: ActorRef => Boolean
  ): Unit =
    if (routees.nonEmpty && routees.size >= expected) {
      val mine = routees.filter(local).sortBy(_.path.toString)
      if (settled.put(router, mine) == null)
        system.log.info(
          s"[REPORT-PLACEMENT] node-local: router=${router.path.name} local sinks=${mine.size} of ${routees.size}"
        )
    }
}

object NodeLocalReporters extends ExtensionId[NodeLocalReporters] with ExtensionIdProvider {
  override def lookup: ExtensionId[? <: Extension] = NodeLocalReporters

  override def createExtension(system: ExtendedActorSystem): NodeLocalReporters =
    new NodeLocalReporters(system)

  /** Only the router pools created by the ReportManager can be asked for routees. */
  def isReportPool(ref: ActorRef): Boolean =
    ref != null && ref.path.name.startsWith(core.util.ManagerConstantsUtil.POOL_REPORT_DATA_ACTOR_NAME_PREFIX)

  val PlacementKey = "htc.report-manager.placement"
  val NodeLocal    = "node-local"
  val ClusterPool  = "cluster-pool"

  /** Routees the cluster pool of `reportType` holds once complete: the configured total, capped by
    * what the Up members can host.
    */
  def expectedRoutees(config: Config, reportType: ReportTypeEnum, system: ExtendedActorSystem): Int = {
    val total   = config.getInt(s"htc.report-manager.$reportType.number-of-instances")
    val perNode = config.getInt(s"htc.report-manager.$reportType.number-of-instances-per-node")
    val members = Try(Cluster(system).state.members.count(_.status == MemberStatus.Up)).getOrElse(1)
    Math.max(1, Math.min(total, perNode * Math.max(1, members)))
  }
}
