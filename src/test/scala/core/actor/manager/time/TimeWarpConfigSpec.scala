package org.interscity.htc
package core.actor.manager.time

import core.api.SimulatorSettingsRegistry

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Guards against exactly the kind of bug `SimulationBaseActor.rollbackHandler`'s
  * `checkpointInterval` config read is otherwise immune from ever surfacing: since that read only
  * happens behind the `currentTimeManagerType == TimeManagerTypeEnum.TIME_WARP` gate (never true
  * today, `docs/TIME_WARP_DESIGN.md`'s step-5 log), a wrong config path there would silently never
  * throw in any test or run — this test resolves the actual path independently of that gate so a
  * typo doesn't sit undetected until someone finally flips Time Warp on.
  */
class TimeWarpConfigSpec extends AnyFlatSpec with Matchers {

  "htc.time-warp.checkpoint-interval" should "resolve from application.conf" in {
    SimulatorSettingsRegistry.getInt("htc.time-warp.checkpoint-interval", ConfigFactory.load()) shouldBe 50
  }

  "htc.time-warp.gvt-margin" should "resolve from application.conf" in {
    SimulatorSettingsRegistry.getInt("htc.time-warp.gvt-margin", ConfigFactory.load()) shouldBe 100
  }

  "htc.time-warp.plateau-rounds-required" should "resolve from application.conf" in {
    SimulatorSettingsRegistry.getInt("htc.time-warp.plateau-rounds-required", ConfigFactory.load()) shouldBe 3
  }
}
