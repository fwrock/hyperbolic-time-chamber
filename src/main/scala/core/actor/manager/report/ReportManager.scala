package org.interscity.htc
package core.actor.manager.report

import core.actor.manager.BaseManager
import core.runtime.accounting.*
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
  startRealTime: LocalDateTime
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "report-manager"
    ) {

  private var selfProxy: ActorRef = null
  private var drain: Option[(Long, ActorRef, Set[ActorRef], Vector[String])] = None
  private val reporters = mutable.Map[ReportTypeEnum, ActorRef]()

  override def onStart(): Unit = {
    super.onStart()
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

  private def createReportData(reportType: ReportTypeEnum): ActorRef = {
    val totalInstances =
      Some(config.getInt(s"htc.report-manager.$reportType.number-of-instances")).getOrElse(8)
    val maxInstancesPerNode = Some(
      config.getInt(s"htc.report-manager.$reportType.number-of-instances-per-node")
    ).getOrElse(8)
    val reporterProps = Props(reportType.clazz, getSelfProxy, startRealTime)
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
    case org.apache.pekko.actor.Terminated(ref) =>
      drain.foreach { case (round, replyTo, pending, _) =>
        if (pending.contains(ref)) { drain = None; replyTo ! RuntimeReportsFlushed(round, Some(s"report sink lost: $ref")) }
      }
    case _: StopSimulationEvent =>
      logInfo("Received StopSimulationEvent. Flushing/stopping reporters")
      val destructEvent = DestructEvent(actorRef = getPath)
      reporters.foreach {
        case (_, actorRef) =>
          actorRef ! destructEvent
      }
      selfDestruct()
  }

}

object ReportManager {
  def props(
    simulationManager: ActorRef,
    timeManager: ActorRef,
    startRealTime: LocalDateTime
  ): Props =
    Props(classOf[ReportManager], timeManager, simulationManager, startRealTime)
}
