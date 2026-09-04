package org.interscity.htc
package core.actor.manager.time.gvt

import org.apache.pekko.actor.ActorRef
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MarginBasedGVTEstimationSpec extends AnyFlatSpec with Matchers {

  private def report(lvt: Long, isIdle: Boolean = false): LocalVirtualTimeReport =
    LocalVirtualTimeReport(source = ActorRef.noSender, lvt = lvt, isIdle = isIdle)

  "estimate" should "return Long.MinValue when there are no reports yet" in {
    new MarginBasedGVTEstimation(margin = 10L).estimate(Seq.empty) shouldBe Long.MinValue
  }

  it should "return the minimum reported LVT minus the margin" in {
    val strategy = new MarginBasedGVTEstimation(margin = 5L)

    strategy.estimate(Seq(report(100L), report(50L), report(75L))) shouldBe 45L
  }

  it should "allow a zero margin (exact minimum LVT)" in {
    val strategy = new MarginBasedGVTEstimation(margin = 0L)

    strategy.estimate(Seq(report(30L), report(20L))) shouldBe 20L
  }

  it should "reject a negative margin at construction" in {
    an[IllegalArgumentException] should be thrownBy new MarginBasedGVTEstimation(margin = -1L)
  }

  it should "never let a single very-behind LTM be masked by others being far ahead" in {
    val strategy = new MarginBasedGVTEstimation(margin = 2L)

    // One straggling LTM (lvt=1) must dominate the estimate even with two others far ahead.
    strategy.estimate(Seq(report(1L), report(1000L), report(2000L))) shouldBe -1L
  }
}
