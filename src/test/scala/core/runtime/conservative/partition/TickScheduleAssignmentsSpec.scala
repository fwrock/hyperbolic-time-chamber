package org.interscity.htc
package core.runtime.conservative.partition

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TickScheduleAssignmentsSpec extends AnyFlatSpec with Matchers {
  private def wake(id: Long, tick: Long): TickWake = TickWake(tick, Identify(id = id))

  "tick assignments" should "balance distinct wake-ups independently for each tick" in {
    val directory = new TickScheduleAssignments(3)
    def distribute(tick: Long, size: Int): Map[Int, Int] =
      (1L to size.toLong)
        .map(
          id => directory.admit(wake(id, tick))._1
        )
        .groupMapReduce(identity)(
          _ => 1
        )(_ + _)
    distribute(10, 900) shouldBe Map(0 -> 300, 1 -> 300, 2 -> 300)
    distribute(11, 600) shouldBe Map(0 -> 200, 1 -> 200, 2 -> 200)
  }

  it should "coalesce repeated actor/tick requests without advancing assignment" in {
    val directory = new TickScheduleAssignments(3)
    directory.admit(wake(1, 11)) shouldBe (0 -> true)
    directory.admit(wake(1, 11)) shouldBe (0 -> false)
    directory.admit(wake(2, 11)) shouldBe (1 -> true)
    directory.admit(wake(1, 12)) shouldBe (0 -> true)
  }

  it should "require storage acceptance and the assigned owner before a unique claim" in {
    val directory = new TickScheduleAssignments(2)
    val event = wake(1, 10)
    directory.admit(event)
    directory.hasPendingAcceptance shouldBe true
    intercept[IllegalArgumentException](directory.claim(event, 0))
    intercept[IllegalArgumentException](directory.accept(event, 1))
    directory.accept(event, 0)
    directory.accept(event, 0)
    directory.hasPendingAcceptance shouldBe false
    directory.claim(event, 0)
    intercept[IllegalArgumentException](directory.claim(event, 0))
    directory.admit(event) shouldBe (0 -> false)
    directory.earliest shouldBe empty
  }

  it should "retain independent future ticks and refuse to advance past unclaimed work" in {
    val directory = new TickScheduleAssignments(2)
    val earlier = wake(1, 11)
    val later = wake(1, 12)
    directory.admit(earlier)
    directory.admit(later)
    directory.earliest shouldBe Vector(earlier)
    intercept[IllegalArgumentException](directory.advance(12))
    directory.accept(earlier, 0)
    directory.claim(earlier, 0)
    directory.advance(12)
    directory.earliest shouldBe Vector(later)
  }
}
