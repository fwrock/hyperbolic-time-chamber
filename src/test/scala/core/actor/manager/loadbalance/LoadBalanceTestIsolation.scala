package org.interscity.htc
package core.actor.manager.loadbalance

import java.util.concurrent.Semaphore

private[htc] object LoadBalanceTestIsolation {
  // Both cluster suites exercise the same JVM-local production registries.
  private val gate = new Semaphore(1)
  def acquire(): Unit = gate.acquire()
  def release(): Unit = gate.release()
}
