package org.interscity.htc
package core.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A run that ends on its own must be visible to REST clients: before markCompleted() the status stayed in
  * Loading/Running forever, so an orchestrator polling /simulation/status could not tell the run was over.
  * The controller is a global object, so the whole sequence lives in one test.
  */
class SimulationControllerStatusSpec extends AnyFlatSpec with Matchers {
  "SimulationController.markCompleted" should "end a running simulation, keep Failed, and be idempotent" in {
    SimulationController.initialize(null, apiEnabled = false) // auto-start flow: status is Running
    SimulationController.status shouldBe SimulationStatus.Running

    SimulationController.markCompleted()
    SimulationController.status shouldBe SimulationStatus.Stopped
    SimulationController.markCompleted()
    SimulationController.status shouldBe SimulationStatus.Stopped

    SimulationController.markFailed()
    SimulationController.markCompleted() // the end-of-run hook fires after a failure too
    SimulationController.status shouldBe SimulationStatus.Failed
  }
}
