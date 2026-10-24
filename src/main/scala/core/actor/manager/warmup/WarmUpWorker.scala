package org.interscity.htc
package core.actor.manager.warmup

import core.entity.event.control.warmup.{ StartWarmUpWorkersEvent, WarmUpWorkerDoneEvent, WarmUpWorkerReadyEvent }
import core.runtime.accounting.RuntimeFailure

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorRef, Props }
import org.apache.pekko.cluster.Cluster
import scala.util.control.NonFatal

/** Pool worker actor — one instance per cluster node (maxInstancesPerNode = 1).
  *
  * On receiving [[StartWarmUpWorkersEvent]], invokes each target method via reflection
  * in the calling thread (actor dispatcher). Because this runs on the io-dispatcher
  * (configured in WarmUpManager), blocking for ~150s is safe and expected.
  *
  * Reports completion via [[WarmUpWorkerDoneEvent]] back to the sender (WarmUpManager).
  */
class WarmUpWorker(manager: ActorRef) extends Actor with ActorLogging {
  protected def nodeAddress: String = Cluster(context.system).selfAddress.toString
  override def preStart(): Unit = manager ! WarmUpWorkerReadyEvent(nodeAddress)
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    manager ! RuntimeFailure(s"warm-up worker restarted: ${reason.getMessage}")
    super.preRestart(reason, message)
  }

  override def receive: Receive = {
    case StartWarmUpWorkersEvent(targets) if sender() == manager =>
      val address = nodeAddress
      log.info(s"[WarmUpWorker] Starting warm-up on $address (${targets.size} target(s))")
      val t0 = System.currentTimeMillis()
      try {
        targets.foreach(invokeTarget)
        val elapsed = System.currentTimeMillis() - t0
        log.info(s"[WarmUpWorker] Warm-up complete on $address in ${elapsed}ms")
        manager ! WarmUpWorkerDoneEvent(address)
      } catch {
        case NonFatal(error) =>
          val cause = Option(error.getCause).getOrElse(error)
          manager ! RuntimeFailure(s"target failed on $address: ${cause.getClass.getName}: ${cause.getMessage}")
      }
  }

  /** Invokes a warm-up target via reflection.
    * Format: "fully.qualified.ObjectName#methodName" (methodName defaults to "warmUp").
    * Scala objects compile to a class with suffix "$" and a MODULE$ singleton field.
    */
  private def invokeTarget(target: String): Unit = {
    val parts      = target.split("#", 2)
    val className  = parts(0).trim + "$"
    val methodName = if (parts.length > 1) parts(1).trim else "warmUp"
    val clazz    = Class.forName(className)
    val instance = clazz.getField("MODULE$").get(null)
    clazz.getMethod(methodName).invoke(instance)
    log.info(s"[WarmUpWorker] '$target' OK")
  }
}

object WarmUpWorker {
  def props(manager: ActorRef): Props =
    Props(classOf[WarmUpWorker], manager).withDispatcher("pekko.actor.io-dispatcher")
}
