package org.interscity.htc
package core.actor.manager.loadbalance.allocation

import org.apache.pekko.actor.{ Actor, ActorRef, Terminated }
import org.apache.pekko.cluster.{ Cluster, MemberStatus }
import org.apache.pekko.cluster.ClusterEvent.{ MemberEvent, MemberRemoved, MemberUp, UnreachableMember }
import scala.collection.mutable
import scala.util.control.NonFatal

class SpatialDirectoryAuthority extends Actor {
  private val epoch = java.util.UUID.randomUUID().toString
  private case class Replica(ref: ActorRef, incarnation: String, registrationId: String, var ready: Boolean = false)
  private case class Publication(request: PublishSpatialAssignments, waiters: mutable.Set[ActorRef],
    revision: Long, outstanding: mutable.Set[String])
  private val replicas = mutable.Map.empty[String, Replica]
  private var entries = Map.empty[String, SpatialAssignment]
  private var revision = 0L
  private var members: Option[Set[String]] = None
  private var failure: Option[String] = None
  private var active: Option[Publication] = None
  private val queued = mutable.Queue.empty[(PublishSpatialAssignments, ActorRef)]
  private val receipts = mutable.Map.empty[String, (Vector[SpatialAssignment], Long)]
  private val cluster = Cluster(context.system)

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
  override def postStop(): Unit = cluster.unsubscribe(self)
  private def upMembers: Set[String] = cluster.state.members.filter(_.status == MemberStatus.Up).map(_.address.toString)

  private def fail(reason: String): Unit = if (failure.isEmpty) {
    failure = Some(reason)
    active.foreach(p => p.waiters.foreach(_ ! SpatialDirectoryRejected(p.request.requestId, reason)))
    queued.foreach { case (request, replyTo) => replyTo ! SpatialDirectoryRejected(request.requestId, reason) }
    queued.clear()
    active = None
    replicas.values.foreach(_.ref ! SpatialDirectoryFailed(epoch, reason))
  }

  private def drive(): Unit = if (failure.isEmpty && active.isEmpty && queued.nonEmpty) {
    val cohort = upMembers
    if (members.exists(_ != cohort)) fail("spatial directory membership changed; coordinated recovery is required")
    else if (cohort.nonEmpty && cohort.forall(a => replicas.get(a).exists(_.ready))) {
      val (request, replyTo) = queued.dequeue()
      try {
        receipts.get(request.requestId) match {
          case Some((rows, version)) =>
            require(rows == request.entries, "spatial request ID reused with different assignments")
            replyTo ! SpatialAssignmentsPublished(request.requestId, epoch, version)
            drive()
            return
          case None => ()
        }
        require(request.entries.map(_.entityId).distinct.size == request.entries.size, "duplicate spatial entity IDs")
        request.entries.foreach { row =>
          require(row.entityId.nonEmpty && row.shardId.nonEmpty && row.typeName.nonEmpty && row.x.isFinite && row.y.isFinite,
            "invalid spatial assignment")
          require(entries.get(row.entityId).forall(_ == row), s"spatial assignment changed for ${row.entityId}")
        }
        members = Some(cohort)
        val publication = Publication(request, mutable.Set(replyTo), revision + 1L, mutable.Set.from(cohort))
        active = Some(publication)
        cohort.foreach(a => replicas(a).ref ! InstallSpatialAssignments(request.requestId, epoch,
          publication.revision, request.entries, replace = false))
      } catch { case NonFatal(error) =>
        replyTo ! SpatialDirectoryRejected(request.requestId, error.getMessage)
        fail(error.getMessage)
      }
    }
  }

  override def receive: Receive = {
    case e: RegisterSpatialReplica =>
      try {
        require(failure.isEmpty, failure.getOrElse("spatial directory failed"))
        require(e.address.nonEmpty && e.incarnation.nonEmpty && e.replica == sender(), "invalid spatial replica")
        val actualAddress = if (sender().path.address.hasLocalScope) cluster.selfAddress.toString else sender().path.address.toString
        require(e.address == actualAddress, "spatial replica address differs from its actor address")
        require(members.forall(_.contains(e.address)), "spatial replica joined after membership was fixed")
        require(replicas.get(e.address).forall(r => r.ref == e.replica && r.incarnation == e.incarnation &&
          r.registrationId == e.requestId), "spatial replica incarnation changed")
        replicas.get(e.address) match {
          case Some(r) if r.ready => sender() ! SpatialReplicaRegistered(e.requestId, epoch, revision)
          case _ =>
            replicas(e.address) = Replica(e.replica, e.incarnation, e.requestId)
            context.watch(e.replica)
            sender() ! InstallSpatialAssignments(e.requestId, epoch, revision, entries.values.toVector.sortBy(_.entityId), replace = true)
        }
      } catch { case NonFatal(error) =>
        sender() ! SpatialDirectoryRejected(e.requestId, error.getMessage)
        fail(error.getMessage)
      }

    case e: SpatialAssignmentsInstalled if failure.isEmpty && e.epoch == epoch =>
      replicas.get(e.address).filter(r => r.ref == sender() && r.incarnation == e.incarnation).foreach { replica =>
        if (!replica.ready && replica.registrationId == e.requestId && e.revision == revision) {
          replica.ready = true
          sender() ! SpatialReplicaRegistered(e.requestId, epoch, revision)
          drive()
        } else active.filter(p => p.request.requestId == e.requestId && p.revision == e.revision).foreach { p =>
          if (p.outstanding.remove(e.address) && p.outstanding.isEmpty) {
            entries ++= p.request.entries.map(row => row.entityId -> row)
            revision = p.revision
            receipts(p.request.requestId) = p.request.entries -> revision
            p.waiters.foreach(_ ! SpatialAssignmentsPublished(p.request.requestId, epoch, revision))
            active = None
            drive()
          }
        }
      }

    case e: PublishSpatialAssignments =>
      try {
        require(failure.isEmpty, failure.getOrElse("spatial directory failed"))
        require(e.epoch == epoch && replicas.get(e.address).exists(r => r.ready && r.incarnation == e.incarnation),
          "spatial publisher is not registered in this epoch")
        receipts.get(e.requestId) match {
          case Some((rows, version)) =>
            require(rows == e.entries, "spatial request ID reused with different assignments")
            sender() ! SpatialAssignmentsPublished(e.requestId, epoch, version)
          case None if active.exists(_.request.requestId == e.requestId) =>
            require(active.get.request == e, "spatial request ID reused during publication")
            active.get.waiters += sender()
          case None => queued.enqueue(e -> sender()); drive()
        }
      } catch { case NonFatal(error) =>
        sender() ! SpatialDirectoryRejected(e.requestId, error.getMessage)
        fail(error.getMessage)
      }

    case e: SpatialInstallFailed if e.epoch == epoch && replicas.get(e.address).exists(r =>
        r.ref == sender() && r.incarnation == e.incarnation) =>
      if (active.exists(_.request.requestId == e.requestId) || replicas(e.address).registrationId == e.requestId)
        fail(s"spatial installation failed on ${e.address}: ${e.reason}")
    case Terminated(ref) if replicas.values.exists(_.ref == ref) => fail("spatial directory replica lost")
    case MemberUp(member) if members.exists(!_.contains(member.address.toString)) => fail("spatial directory member joined after publication")
    case MemberRemoved(member, _) if members.exists(_.contains(member.address.toString)) => fail("spatial directory member removed")
    case UnreachableMember(member) if members.exists(_.contains(member.address.toString)) => fail("spatial directory member unreachable")
    case _ => ()
  }
}
