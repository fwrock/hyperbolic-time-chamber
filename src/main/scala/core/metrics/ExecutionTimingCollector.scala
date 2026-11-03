package org.interscity.htc
package core.metrics

import org.interscity.htc.core.metrics.core.{ExecutionTimingMetrics, TimingSnapshot}
import org.apache.pekko.actor.{Actor, ActorRef, ExtendedActorSystem, Extension, ExtensionId, Props}

sealed trait ExecutionTimingMessage extends Serializable

object ExecutionTimingCollector extends ExtensionId[ExecutionTimingCollectorExtension] {
  val Name = "execution-timing-collector"
  final case class Capture(replyTo: ActorRef) extends ExecutionTimingMessage
  final case class Captured(processId: String, node: Int, rows: Vector[TimingSnapshot]) extends ExecutionTimingMessage
  private val processId = java.util.UUID.randomUUID().toString
  def snapshot: Captured = Captured(processId, org.interscity.htc.core.cluster.NodeIdentity.selfStamp, ExecutionTimingMetrics.timers.snapshots)
  override def createExtension(system: ExtendedActorSystem): ExecutionTimingCollectorExtension =
    new ExecutionTimingCollectorExtension(system)
}
final class ExecutionTimingCollectorExtension(system: ExtendedActorSystem) extends Extension {
  val ref: ActorRef = system.actorOf(Props(classOf[ExecutionTimingCollectorActor]), ExecutionTimingCollector.Name)
}
final class ExecutionTimingCollectorActor extends Actor {
  def receive: Receive = {
    case ExecutionTimingCollector.Capture(replyTo) => replyTo ! ExecutionTimingCollector.snapshot
  }
}
