package org.interscity.htc
package core.actor.manager

import core.actor.BaseActor
import core.entity.state.BaseState
import core.metrics.core.ManagerMetrics

import org.apache.pekko.actor.{ ActorRef, Props }
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.util.{ ActorCreatorUtil, DistributedUtil }

import scala.collection.mutable

abstract class BaseManager[T <: BaseState](
  actorId: String = null,
  timeManager: ActorRef = null
)(implicit m: Manifest[T])
    extends BaseActor[T](
      properties = Properties(
        entityId = actorId,
        resourceId = "",
        timeManagers =
          if (timeManager != null) mutable.Map("discrete-event" -> timeManager)
          else mutable.Map.empty,
        creatorManager = null,
        reporters = mutable.Map.empty
      )
    ) {

  // --- manager metrics (see core.metrics.core.ManagerMetrics) ---
  // Prometheus label for this manager; `kind` tells a cluster singleton (its path is <name>/singleton) from a per-node manager.
  private lazy val managerLabel: String = getClass.getSimpleName
  private lazy val managerKind: String = if (self.path.name == "singleton") "singleton" else "node"
  private lazy val managerMetricsEnabled: Boolean =
    scala.util.Try(context.system.settings.config.getBoolean("htc.node.manager-metrics")).getOrElse(true)
  private val childCounters = new java.util.HashMap[String, (io.prometheus.client.Counter.Child, io.prometheus.client.Histogram.Child)]()

  override def preStart(): Unit = {
    super.preStart()
    if (managerMetricsEnabled) ManagerMetrics.instances.labels(managerLabel, managerKind).inc()
  }

  override def postStop(): Unit = {
    if (managerMetricsEnabled) ManagerMetrics.instances.labels(managerLabel, managerKind).dec()
    super.postStop()
  }

  override def aroundReceive(receive: Receive, message: Any): Unit =
    if (!managerMetricsEnabled) super.aroundReceive(receive, message)
    else {
      val started = System.nanoTime()
      try super.aroundReceive(receive, message)
      finally {
        val label = ManagerMetrics.messageLabel(message)
        var children = childCounters.get(label)
        if (children == null) {
          children = (ManagerMetrics.messages.labels(managerLabel, label), ManagerMetrics.handlingSeconds.labels(managerLabel, label))
          childCounters.put(label, children)
        }
        children._1.inc()
        children._2.observe((System.nanoTime() - started) / 1e9)
      }
    }

  protected var reporters
    : mutable.Map[org.interscity.htc.core.enumeration.ReportTypeEnum, ActorRef] = null
  protected var timeManagerRef: ActorRef = timeManager

  protected def createSingletonManager(
    manager: Props,
    name: String,
    terminateMessage: Any
  ): ActorRef = ActorCreatorUtil.createSingletonManager(
    system = context.system,
    manager = manager,
    name = name,
    terminateMessage = terminateMessage
  )

  protected def createSingletonProxy(name: String): ActorRef =
    DistributedUtil.createSingletonProxy(context.system, name)
}
