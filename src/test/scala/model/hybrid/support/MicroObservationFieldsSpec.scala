package org.interscity.htc
package model.hybrid.support

import core.api.SimulatorSettingsRegistry
import core.util.SimulationClock
import model.hybrid.entity.event.data.MicroUpdateData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MicroObservationFieldsSpec extends AnyFlatSpec with Matchers {
  private val update = MicroUpdateData(0, 12.0, 4.0, -2.0, 0, Some(7L), 3.5, 2.0, 4.0)

  "Microscopic observation fields" should "export kinematics and distinguish an actual leader from free space or a signal" in {
    val fields = MicroObservationFields.update(update, 4L, 1)
    fields("acceleration") shouldBe -2.0
    fields("leader_id") shouldBe "7"
    fields("gap") shouldBe 3.5
    fields("gap_valid") shouldBe true
    for (leader <- Seq(None, Some(-1L))) {
      val absent = MicroObservationFields.update(update.copy(leaderVehicle = leader), 4L, 1)
      Option(absent("leader_id")) shouldBe None
      Option(absent("gap")) shouldBe None
      absent("gap_valid") shouldBe false
    }
  }

  it should "use the installed physical clock and never stretch decimated samples across unobserved intervals" in {
    val previous = SimulatorSettingsRegistry.get(SimulationClock.Setting)
    SimulatorSettingsRegistry.set(SimulationClock.Setting, "0.1")
    try {
      val fields = MicroObservationFields.update(update, 13L, 5)
      fields("time_seconds").asInstanceOf[Double] shouldBe 1.3 +- 1e-9
      fields("sample_duration_seconds") shouldBe 0.1
      fields("report_every_updates") shouldBe 5
      MicroObservationFields.event(13L)("time_seconds").asInstanceOf[Double] shouldBe 1.3 +- 1e-9
    } finally {
      previous match {
        case Some(value) => SimulatorSettingsRegistry.set(SimulationClock.Setting, value)
        case None => SimulatorSettingsRegistry.clear(SimulationClock.Setting)
      }
    }
  }
}
