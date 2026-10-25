package org.interscity.htc
package model.hybrid.support.person

import model.hybrid.entity.state.WalkingLegProgress
import model.hybrid.entity.state.plan.WalkLeg
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class PersonWalkingTripHandlerSpec extends AnyFlatSpec with Matchers {
  private val noop: String => Unit = _ => ()

  "walking measurement" should "sum every traversed link without consuming the route or using routing cost as distance" in {
    val route = mutable.Queue(10L -> 2L, 11L -> 3L, 10L -> 4L)
    val rows = mutable.ArrayBuffer.empty[Map[String, Any]]
    val handler = new PersonWalkingTripHandler("1", (data, _) => rows += data, noop, noop,
      id => Map(10L -> 100.0, 11L -> 250.0).get(id), (_, _) => Some(99999.0 -> route))
    handler.initiateWalkingTrip(WalkLeg(1L, 4L), 50L, noop) shouldBe Some(WalkingLegProgress(450.0, 372L))
    route.toList shouldBe List(10L -> 2L, 11L -> 3L, 10L -> 4L)
    rows.head("distance") shouldBe 450.0
    rows.head("arrival_tick") shouldBe 372L
  }

  it should "measure precomputed routes and allow a zero-length walk without running routing" in {
    val handler = new PersonWalkingTripHandler("1", (_, _) => (), noop, noop,
      _ => Some(140.0), (_, _) => fail("precomputed walk reran routing"))
    handler.initiateWalkingTrip(WalkLeg(1L, 2L, Some(List(10L -> 2L))), 10L, noop) shouldBe
      Some(WalkingLegProgress(140.0, 110L))
    handler.initiateWalkingTrip(WalkLeg(1L, 1L, Some(Nil)), 10L, noop) shouldBe
      Some(WalkingLegProgress(0.0, 10L))
  }

  it should "reject missing or invalid link lengths instead of emitting an understated successful route" in {
    Seq(None, Some(-1.0), Some(Double.NaN), Some(Double.PositiveInfinity)).foreach { length =>
      val rows = mutable.ArrayBuffer.empty[Map[String, Any]]
      val handler = new PersonWalkingTripHandler("1", (data, _) => rows += data, noop, noop,
        id => if (id == 10L) Some(140.0) else length)
      handler.initiateWalkingTrip(WalkLeg(1L, 3L, Some(List(10L -> 2L, 11L -> 3L))), 0L, noop) shouldBe None
      rows shouldBe empty
    }
  }

  it should "leave failed and unrepresentable routes without a scheduled arrival" in {
    val noRoute = new PersonWalkingTripHandler("1", (_, _) => (), noop, noop, _ => None, (_, _) => None)
    noRoute.initiateWalkingTrip(WalkLeg(1L, 2L), 0L, noop) shouldBe None
    val huge = new PersonWalkingTripHandler("1", (_, _) => (), noop, noop, _ => Some(Double.MaxValue))
    huge.initiateWalkingTrip(WalkLeg(1L, 2L, Some(List(10L -> 2L))), 0L, noop) shouldBe None
    val overflow = new PersonWalkingTripHandler("1", (_, _) => (), noop, noop, _ => Some(140.0))
    overflow.initiateWalkingTrip(WalkLeg(1L, 2L, Some(List(10L -> 2L))), Long.MaxValue - 1L, noop) shouldBe None
  }
}
