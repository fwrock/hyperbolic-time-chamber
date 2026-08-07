package org.interscity.htc
package model.hybrid.decision

import model.hybrid.entity.state.ArrivalLogistics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/** Coverage for `TravelTimeLogitEngine.sampleLogit` — the multinomial-logit sampling that replaces
  * `TravelTimeEngine`'s deterministic `maxByOption` (see `TravelTimeLogitEngine`'s class doc for
  * the real-utility-theory rationale). Pure given an injected `Random`, so fully testable without
  * any `CityMapUtil`/`TransitMapUtil` singleton involvement.
  */
class TravelTimeLogitEngineSpec extends AnyFlatSpec with Matchers {

  private def logistics(mode: String): ArrivalLogistics = ArrivalLogistics(mode = mode)

  "sampleLogit" should "return None for an empty candidate list" in {
    TravelTimeLogitEngine.sampleLogit(Nil, new Random(1L)) shouldBe None
  }

  it should "always return the sole candidate, without touching the RNG, when there is only one" in {
    val car = logistics("car")

    TravelTimeLogitEngine.sampleLogit(List((car, 5.0)), new Random(1L)) shouldBe Some(car)
  }

  it should "be reproducible: the same seed produces the same sequence of picks" in {
    val candidates = List((logistics("car"), 1.0), (logistics("walk"), 1.0), (logistics("bicycle"), 0.5))

    val firstRun  = (1 to 20).map(_ => TravelTimeLogitEngine.sampleLogit(candidates, new Random(42L)))
    val secondRun = (1 to 20).map(_ => TravelTimeLogitEngine.sampleLogit(candidates, new Random(42L)))

    firstRun shouldBe secondRun
  }

  it should "split roughly evenly between two equal-score candidates over many draws" in {
    val car  = logistics("car")
    val walk = logistics("walk")
    val rng  = new Random(7L)

    val draws = (1 to 2000).flatMap(_ => TravelTimeLogitEngine.sampleLogit(List((car, 2.0), (walk, 2.0)), rng))
    val carCount = draws.count(_ == car)

    carCount should (be >= 800 and be <= 1200)
  }

  it should "favor the higher-scoring candidate without making the lower one impossible" in {
    val best  = logistics("car")
    val worst = logistics("walk")
    val rng   = new Random(13L)

    val draws = (1 to 1000).flatMap(_ => TravelTimeLogitEngine.sampleLogit(List((best, 3.0), (worst, 0.0)), rng))

    draws.count(_ == best) should be > (draws.size * 9 / 10) // clear utility gap -> strongly favored
    draws.count(_ == worst) should be > 0 // but never literally impossible, unlike argmax
  }

  it should "converge toward argmax as scale increases (sharper distribution)" in {
    val best  = logistics("car")
    val worst = logistics("walk")
    val rng   = new Random(21L)

    val draws = (1 to 200).flatMap(_ => TravelTimeLogitEngine.sampleLogit(List((best, 1.1), (worst, 1.0)), rng, scale = 50.0))

    draws.count(_ == best) should be > (draws.size * 19 / 20)
  }

  it should "flatten toward uniform as scale decreases, even with an otherwise-clear winner" in {
    val best  = logistics("car")
    val worst = logistics("walk")
    val rng   = new Random(99L)

    val draws = (1 to 2000).flatMap(_ => TravelTimeLogitEngine.sampleLogit(List((best, 10.0), (worst, 0.0)), rng, scale = 0.001))
    val bestCount = draws.count(_ == best)

    // Near-zero scale washes out the utility gap -> both candidates drawn roughly equally.
    bestCount should (be >= 800 and be <= 1200)
  }
}
