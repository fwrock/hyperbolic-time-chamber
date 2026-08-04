package org.interscity.htc
package model.hybrid.util

import model.hybrid.entity.state.model.{ RouteStop, TransitRoute }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression coverage for `TransitRouteUtil.reachableStopIdsAfter` — the directional-feasibility
  * filter added to fix a bug found via `htc-scenario-qa`'s hybrid smoke-test scenario (2026-07-23):
  * `TravelTimeModeChoiceStrategy.bestAlightingStop`/`ModeChoiceUtil.bestAlightingStop` used to rank
  * every stop sharing a `line` label purely by haversine distance to the destination, with no
  * notion of the line's actual direction of travel, so they could offer a PT trip no scheduled
  * vehicle ever fulfills — the rider boards, and is never picked up, with no exception thrown.
  *
  * Deliberately pure (no `TransitRouteUtil` singleton/file access) so these behaviors are testable
  * without the eager-singleton/no-fork problem documented on `RaptorMultiModalEngineSpec` and
  * `ArrivalLogisticsTranslation.resolveTransitLeg`.
  */
class TransitRouteUtilSpec extends AnyFlatSpec with Matchers {

  private def route(stopIds: String*): TransitRoute =
    TransitRoute(
      lineId = "line-a",
      stopType = "bus",
      headwaySeconds = 600,
      stops = stopIds.map(id => RouteStop(stopId = id, travelTimeFromPrevSeconds = 60)).toList
    )

  "reachableStopIdsAfter" should "return every stop strictly after the boarding stop, in route order" in {
    val r = route("a", "b", "c", "d")

    TransitRouteUtil.reachableStopIdsAfter(r, "b") shouldBe Some(Set("c", "d"))
  }

  it should "return an empty set (not None) when the boarding stop is the route's last stop" in {
    val r = route("a", "b", "c")

    TransitRouteUtil.reachableStopIdsAfter(r, "c") shouldBe Some(Set.empty)
  }

  it should "never include the boarding stop itself, even if a line loops back through it" in {
    val r = route("a", "b", "c", "a")

    TransitRouteUtil.reachableStopIdsAfter(r, "a") shouldBe Some(Set("b", "c", "a"))
  }

  it should "return None when the boarding stop isn't on the route at all — direction unverifiable" in {
    val r = route("a", "b", "c")

    TransitRouteUtil.reachableStopIdsAfter(r, "nonexistent-stop") shouldBe None
  }

  it should "distinguish 'boarding at the terminal' (Some(empty)) from 'not on this route' (None) — callers must not conflate them" in {
    val r = route("a", "b")

    val atTerminal = TransitRouteUtil.reachableStopIdsAfter(r, "b")
    val notOnRoute = TransitRouteUtil.reachableStopIdsAfter(r, "z")

    atTerminal shouldBe Some(Set.empty)
    notOnRoute shouldBe None
    atTerminal should not be notOnRoute
  }
}
