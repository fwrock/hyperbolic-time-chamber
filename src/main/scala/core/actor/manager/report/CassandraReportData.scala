package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.system.database.database.CreateEntityEvent
import org.interscity.htc.core.util.ManagerConstantsUtil
import org.interscity.htc.core.util.ManagerConstantsUtil.POOL_CASSANDRA_ENTITY_MANAGER_REPORT_DATA_ACTOR_NAME_PREFIX
import org.interscity.htc.system.database.cassandra.actor.CassandraEntityManager

import scala.collection.mutable
import scala.compiletime.uninitialized

class CassandraReportData(override val reportManager: ActorRef)
    extends ReportData(
      id = "cassandra-report-manager",
      reportManager = reportManager
    ) {

  private var driver: ActorRef = uninitialized

  private val databaseSource = Some(
    config.getString("htc.report-manager.cassandra.database-source")
  ).getOrElse("default")
  private val batchSize =
    Some(config.getInt("htc.report-manager.cassandra.batch-size")).getOrElse(1000)

  private val buffer = mutable.ListBuffer[ReportEvent]()

  override def onReport(event: ReportEvent): Unit = {
    buffer += event
    if (buffer.size >= batchSize) {
      flushBuffer()
    }
  }

  private def flushBuffer(): Unit = {
    buffer.foreach {
      report =>
        val fields = report.getClass.getDeclaredFields.map(_.getName)
        val values = report.productIterator.toList.map(_.toString)
        driver ! CreateEntityEvent(
          table = "report",
          columns = fields,
          values = values
        )
    }
    buffer.clear()
  }

  override def postStop(): Unit =
    if (buffer.nonEmpty) {
      flushBuffer()
    }

  private def getDriver: ActorRef =
    if (driver == null) {
      driver = createDriver()
      driver
    } else {
      driver
    }

  private def createDriver(): ActorRef = {
    val totalInstances = Some(
      config.getInt(s"htc.databases.cassandra.${databaseSource}.actor.number-of-instances")
    ).getOrElse(8)
    val maxInstancesPerNode = Some(
      config.getInt(s"htc.databases.cassandra.${databaseSource}.actor.number-of-instances-per-node")
    ).getOrElse(1)
    context.actorOf(
      ClusterRouterPool(
        local = RoundRobinPool(0),
        settings = ClusterRouterPoolSettings(
          totalInstances = totalInstances,
          maxInstancesPerNode = maxInstancesPerNode,
          allowLocalRoutees = true
        )
      ).props(CassandraEntityManager.props(databaseSource, null)),
      name = POOL_CASSANDRA_ENTITY_MANAGER_REPORT_DATA_ACTOR_NAME_PREFIX
    )
  }

}
