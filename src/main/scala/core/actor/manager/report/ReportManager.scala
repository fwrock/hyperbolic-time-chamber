package org.interscity.htc
package core.actor.manager.report

import core.actor.manager.BaseManager
import core.runtime.accounting.*
import core.metrics.ExecutionTimingCollector
import core.entity.event.control.report.ReportEvent
import org.apache.pekko.Done
import org.apache.pekko.actor.{CoordinatedShutdown, RootActorPath}
import org.apache.pekko.cluster.{Cluster, MemberStatus}
import org.apache.pekko.cluster.ClusterEvent.{CurrentClusterState, MemberRemoved, UnreachableMember}
import org.apache.pekko.routing.{GetRoutees, Routees, ActorRefRoutee}
import scala.concurrent.Promise
import core.entity.event.control.report.RegisterReportersEvent
import core.entity.state.DefaultState
import core.enumeration.ReportTypeEnum
import core.util.ManagerConstantsUtil
import core.util.ManagerConstantsUtil.{ POOL_REPORT_DATA_ACTOR_NAME_PREFIX, REPORT_MANAGER_ACTOR_NAME }

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.{ DestructEvent, StopSimulationEvent }

import java.time.LocalDateTime
import scala.collection.mutable

class ReportManager(
  timeManager: ActorRef,
  simulationManager: ActorRef,
  startRealTime: LocalDateTime,
  scenarioId: String = "sim",
  simulationId: String = java.util.UUID.randomUUID().toString
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "report-manager"
    ) {

  private val reportRun = ReportRunIdentity(scenarioId, simulationId)

  private var selfProxy: ActorRef = null
  private var drain: Option[(Long, ActorRef, Set[ActorRef], Vector[String])] = None
  private val reporters = mutable.Map[ReportTypeEnum, ActorRef]()

  private val shutdown = Promise[Done]()
  private var stopping = false
  private var pendingNodes = Set.empty[String]
  private var captured = Vector.empty[ExecutionTimingCollector.Captured]
  private var pendingRouters = Set.empty[ActorRef]
  private var reportSinks = Set.empty[ActorRef]
  private var timingSinks = Vector.empty[ActorRef]
  private var timingDeliveries = Set.empty[Long]
  private var timingFlushes = Set.empty[ActorRef]
  private val timingEpoch = java.util.UUID.randomUUID().toString
  private val TimingFlushRound = -1L

  private def beginStop(): Unit = if (!stopping) {
    stopping = true
    if (reporters.isEmpty) { finishStop(); return }
    captured = Vector(ExecutionTimingCollector.snapshot)
    val cluster = scala.util.Try(Cluster(context.system)).toOption
    val remoteMembers = cluster.toVector.flatMap(c => c.state.members.filter(m =>
      m.status == MemberStatus.Up && m.address != c.selfAddress))
    pendingNodes = remoteMembers.map(_.address.toString).toSet
    cluster.foreach(_.subscribe(self, classOf[MemberRemoved], classOf[UnreachableMember]))
    remoteMembers.foreach { member =>
      context.actorSelection(RootActorPath(member.address) / "user" / ExecutionTimingCollector.Name) !
        ExecutionTimingCollector.Capture(self)
    }
    collectReportSinks()
  }

  private def collectReportSinks(): Unit = if (pendingNodes.isEmpty) {
    pendingRouters = reporters.values.toSet
    if (pendingRouters.isEmpty) finishStop()
    else pendingRouters.foreach(_ ! GetRoutees)
  }

  private def publishTimingReports(): Unit = {
    val reports = captured.flatMap(ReportManager.timingReports)
    var token = 0L
    timingSinks.foreach { sink =>
      reports.foreach { report =>
        token += 1
        timingDeliveries += token
        sink ! CommittedReportDelivery(timingEpoch, token, report, self)
      }
    }
    if (timingDeliveries.isEmpty) flushTimingReports()
  }

  private def flushTimingReports(): Unit = {
    timingFlushes = reportSinks
    if (timingFlushes.isEmpty) finishStop()
    else timingFlushes.foreach(_ ! FlushRuntimeReports(TimingFlushRound, self))
  }

  private def finishStop(): Unit = {
    logInfo("Received StopSimulationEvent. Flushing/stopping reporters")
    reportSinks.foreach(_ ! DestructEvent(actorRef = getPath))
    core.api.SimulationController.markCompleted()
    shutdown.trySuccess(Done)
    selfDestruct()
  }

  private def failStop(reason: String): Unit = {
    logError(s"[TIMING-REPORT-FAILED] $reason")
    core.api.SimulationController.markFailed()
    shutdown.tryFailure(new IllegalStateException(reason))
    selfDestruct()
  }

  override def postStop(): Unit = {
    shutdown.tryFailure(new IllegalStateException("ReportManager stopped before acknowledged flush"))
    super.postStop()
  }

  override def onStart(): Unit = {
    super.onStart()
    ExecutionTimingCollector(context.system)
    CoordinatedShutdown(context.system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "htc-report-flush") { () =>
      if (!shutdown.isCompleted) self ! StopSimulationEvent()
      shutdown.future
    }
    logInfo(s"Report execution id=${reportRun.simulationId}, scenario=${reportRun.scenarioId}")
    createReporters()
    simulationManager ! RegisterReportersEvent(
      reporters = reporters
    )
  }

  private def createReporters(): Unit = {
    val reportingEnabled =
      try config.getBoolean("htc.report-manager.enabled")
      catch { case _: Exception => true }

    if (!reportingEnabled) {
      logInfo("Reporting is disabled (htc.report-manager.enabled = false). No reporters created.")
      return
    }

    val enabledStrategies = Some(
      config.getStringList("htc.report-manager.enabled-strategies").toArray.toList.map(_.toString)
    ).getOrElse(List("csv"))

    enabledStrategies.foreach {
      reportType =>
        val reportTypeEnum = ReportTypeEnum.valueOf(reportType)
        reporters.put(reportTypeEnum, createReportData(reportTypeEnum))
    }
  }

  protected def createReportData(reportType: ReportTypeEnum): ActorRef = {
    val totalInstances =
      Some(config.getInt(s"htc.report-manager.$reportType.number-of-instances")).getOrElse(8)
    val maxInstancesPerNode = Some(
      config.getInt(s"htc.report-manager.$reportType.number-of-instances-per-node")
    ).getOrElse(8)
    val reporterProps = Props(reportType.clazz, getSelfProxy, startRealTime, reportRun.simulationId, reportRun.scenarioId)
      .withDispatcher("pekko.actor.io-dispatcher")

    context.actorOf(
      ClusterRouterPool(
        local = RoundRobinPool(0),
        settings = ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(reporterProps),
      name = s"$POOL_REPORT_DATA_ACTOR_NAME_PREFIX-$reportType"
    )
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(REPORT_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }

  override def onDestruct(event: DestructEvent): Unit = {
    super.onDestruct(event)
    reporters.foreach {
      case (_, actorRef) =>
        actorRef ! event
    }
  }

  override def handleEvent: Receive = {
    // subscribe() sends this snapshot before membership events. It must not restart the
    // manager and lose the in-flight final-flush state on a real cluster provider.
    case _: CurrentClusterState => ()
    case _: StopSimulationEvent => beginStop()
    case snapshot: ExecutionTimingCollector.Captured if stopping && pendingNodes.contains(sender().path.address.toString) =>
      pendingNodes -= sender().path.address.toString
      captured :+= snapshot
      collectReportSinks()
    case Routees(routes) if stopping && pendingRouters.contains(sender()) =>
      val sinks = routes.collect { case ActorRefRoutee(ref) => ref }.toVector
      if (sinks.isEmpty) failStop("enabled reporter pool has no report sinks")
      else {
        sinks.foreach(context.watch)
        reportSinks ++= sinks
        timingSinks :+= sinks.head
        pendingRouters -= sender()
        if (pendingRouters.isEmpty) publishTimingReports()
      }
    case DeliveryAccepted(epoch, token) if stopping && epoch == timingEpoch =>
      if (timingDeliveries.contains(token)) {
        timingDeliveries -= token
        if (timingDeliveries.isEmpty) flushTimingReports()
      }
    case RuntimeReportsFlushed(round, error) if stopping && round == TimingFlushRound && timingFlushes.contains(sender()) =>
      error match {
        case Some(reason) => failStop(reason)
        case None =>
          timingFlushes -= sender()
          if (timingFlushes.isEmpty) finishStop()
      }
    case RuntimeFailure(reason) if stopping => failStop(reason)
    case MemberRemoved(member, _) if stopping && pendingNodes.contains(member.address.toString) =>
      failStop(s"node removed during timing collection: ${member.address}")
    case UnreachableMember(member) if stopping && pendingNodes.contains(member.address.toString) =>
      failStop(s"node unreachable during timing collection: ${member.address}")
    case DrainRuntimeReports(round, replyTo, targets) =>
      require(drain.isEmpty, "overlapping report drains")
      if (targets.isEmpty) replyTo ! RuntimeReportsFlushed(round)
      else {
        drain = Some((round, replyTo, targets, Vector.empty))
        targets.foreach { target => context.watch(target); target ! FlushRuntimeReports(round, self) }
      }
    case RuntimeReportsFlushed(round, error) =>
      drain.foreach { case (expected, replyTo, pending, errors) =>
        if (round == expected && pending.contains(sender())) {
          val remaining = pending - sender()
          val failures = errors ++ error
          if (remaining.isEmpty) {
            drain = None
            replyTo ! RuntimeReportsFlushed(round, if (failures.isEmpty) None else Some(failures.mkString("; ")))
          } else drain = Some((round, replyTo, remaining, failures))
        }
      }
    case org.apache.pekko.actor.Terminated(ref) if stopping && reportSinks.contains(ref) =>
      failStop(s"report sink lost during final timing flush: $ref")
    case org.apache.pekko.actor.Terminated(ref) =>
      drain.foreach { case (round, replyTo, pending, _) =>
        if (pending.contains(ref)) { drain = None; replyTo ! RuntimeReportsFlushed(round, Some(s"report sink lost: $ref")) }
      }
    case _ => () // Late/duplicate guarded acknowledgements do not belong to an active drain.

  }

}

object ReportManager {
  private[report] def timingReports(snapshot: ExecutionTimingCollector.Captured): Vector[ReportEvent] =
    snapshot.rows.map { row => ReportEvent(
      entityId = s"execution-timing:${snapshot.processId}:${row.operation}", tick = 0L, lamportTick = 0L,
      data = row.reportData ++ Map("process_id" -> snapshot.processId, "snapshot_timestamp_ms" -> System.currentTimeMillis()),
      label = "execution_timing_summary", node = snapshot.node, timestamp = System.nanoTime(),
      fromNode = 0, messageType = "", fromType = ""
    ) }

  def props(
    simulationManager: ActorRef,
    timeManager: ActorRef,
    startRealTime: LocalDateTime,
    scenarioId: String = "sim",
    executionId: Option[String] = None
  ): Props = {
    // Keep the identity in Props so actor restarts retain the same execution.
    val run = ReportRunIdentity.create(scenarioId, executionId)
    Props(classOf[ReportManager], timeManager, simulationManager, startRealTime, run.scenarioId, run.simulationId)
  }
}
