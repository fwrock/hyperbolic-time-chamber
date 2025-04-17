package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.event.control.report.RegisterReportersEvent
import org.interscity.htc.core.entity.state.DefaultState
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.{ POOL_REPORT_DATA_ACTOR_NAME_PREFIX, REPORT_MANAGER_ACTOR_NAME }

import scala.collection.mutable

class ReportManager(
  timeManager: ActorRef,
  simulationManager: ActorRef
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "report-manager"
    ) {

  private var selfProxy: ActorRef = null
  private val reporters = mutable.Map[ReportTypeEnum, ActorRef]()

  override def onStart(): Unit = {
    super.onStart()
    createReporters()
    simulationManager ! RegisterReportersEvent(
      reporters = reporters
    )
  }

  private def createReporters(): Unit = {
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
    context.actorOf(
      ClusterRouterPool(
        local = RoundRobinPool(0),
        settings = ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(Props(reportType.clazz, getSelfProxy)),
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

}
