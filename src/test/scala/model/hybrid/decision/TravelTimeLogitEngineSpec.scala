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

    // Each call re-seeds fresh (mirrors a fresh RNG per decision) — same seed, same single draw.
    firstRun shouldBe secondRun
  }

  it should "split roughly evenly between two equal-score candidates over many draws" in {
    val car  = logistics("car")
    val walk = logistics("walk")
    val rng  = new Random(7L)

    val draws = (1 to 2000).flatMap(_ => TravelTimeLogitEngine.sampleLogit(List((car, 2.0), (walk, 2.0)), rng))
    val carCount = draws.count(_ == car)

    // Binomial(n=2000, p=0.5): expected 1000, std dev ~22.4 — a 400-count band is generously wide
    // to avoid flakiness while still catching a sampler that's obviously biased or broken.
    carCount should (be >= 800 and be <= 1200)
  }

  it should "favor the higher-scoring candidate without making the lower one impossible" in {
    val best  = logistics("car")
    val worst = logistics("walk")
    val rng   = new Random(13L)

    // gap = 3.0 -> P(worst) = exp(-3)/(1+exp(-3)) ~= 4.7%, ~47 expected hits in 1000 draws: strongly
    // favors `best` while keeping P(worst) far enough from zero that this isn't a coin-flip-against
    // a near-impossible event (a gap of 10.0 here gives P(worst) ~= 4.5e-5, ~0.045 expected hits —
    // "count > 0" on that is itself a near coin flip and made this assertion flaky).
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
