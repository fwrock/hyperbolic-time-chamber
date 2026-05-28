package org.interscity.htc
package core.actor.manager.warmup

import core.entity.event.control.warmup.{ StartWarmUpWorkersEvent, WarmUpWorkerDoneEvent }

import org.apache.pekko.actor.{ Actor, ActorLogging, Props }

/** Pool worker actor — one instance per cluster node (maxInstancesPerNode = 1).
  *
  * On receiving [[StartWarmUpWorkersEvent]], invokes each target method via reflection
  * in the calling thread (actor dispatcher). Because this runs on the io-dispatcher
  * (configured in WarmUpManager), blocking for ~150s is safe and expected.
  *
  * Reports completion via [[WarmUpWorkerDoneEvent]] back to the sender (WarmUpManager).
  */
class WarmUpWorker extends Actor with ActorLogging {

  override def receive: Receive = {
    case StartWarmUpWorkersEvent(targets) =>
      val address = self.path.address.toString
      log.info(s"[WarmUpWorker] Starting warm-up on $address (${targets.size} target(s))")
      val t0 = System.currentTimeMillis()
      targets.foreach(invokeTarget)
      val elapsed = System.currentTimeMillis() - t0
      log.info(s"[WarmUpWorker] Warm-up complete on $address in ${elapsed}ms")
      sender() ! WarmUpWorkerDoneEvent(address)
  }

  /** Invokes a warm-up target via reflection.
    * Format: "fully.qualified.ObjectName#methodName" (methodName defaults to "warmUp").
    * Scala objects compile to a class with suffix "$" and a MODULE$ singleton field.
    */
  private def invokeTarget(target: String): Unit = {
    val parts      = target.split("#", 2)
    val className  = parts(0).trim + "$"
    val methodName = if (parts.length > 1) parts(1).trim else "warmUp"
    try {
      val clazz    = Class.forName(className)
      val instance = clazz.getField("MODULE$").get(null)
      clazz.getMethod(methodName).invoke(instance)
      log.info(s"[WarmUpWorker] '$target' OK")
    } catch {
      case e: Exception =>
        log.error(e, s"[WarmUpWorker] Failed to invoke '$target': ${e.getMessage}")
    }
  }
}

object WarmUpWorker {
  def props: Props = Props[WarmUpWorker]().withDispatcher("pekko.actor.io-dispatcher")
}
