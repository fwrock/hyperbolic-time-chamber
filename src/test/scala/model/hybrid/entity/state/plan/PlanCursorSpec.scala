package org.interscity.htc
package model.hybrid.entity.state.plan

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PlanCursorSpec extends AnyFlatSpec with Matchers {

  private val home = Activity("home", nodeId = "n1", endTime = AtTick(100))
  private val work = Activity("work", nodeId = "n2", endTime = AtTick(200))
  private val walkToStop = WalkLeg(originNodeId = "n1", destinationNodeId = "n3")
  private val transit = TransitLeg(
    mode = ConcreteMode.Bus,
    line = "L42",
    boardingStop = StopRef(actorId = "stop-1", actorClassType = "BusStop", nodeId = "n3"),
    alightingStop = StopRef(actorId = "stop-2", actorClassType = "BusStop", nodeId = "n4")
  )
  private val walkFromStop = WalkLeg(originNodeId = "n4", destinationNodeId = "n2")
  private val pendingDecision = PendingDecision(
    ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk, ConcreteMode.Bus), strategyId = "default")
  )

  private def emptyCursor: PlanCursor = PlanCursor(executed = Nil, remaining = RemainingQueue(Nil))

  "advance" should "report ScheduleComplete when the queue is empty" in {
    val result = PlanCursor.advance(emptyCursor)

    result shouldBe ScheduleComplete(emptyCursor)
  }

  it should "move an Activity at the head into executed and report Advanced" in {
    val cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(List(home, work)))

    val result = PlanCursor.advance(cursor)

    result shouldBe a[Advanced]
    val Advanced(next) = result.asInstanceOf[Advanced]
    next.executed shouldBe List(home)
    next.remaining.dequeue.map(_._1) shouldBe Some(work)
  }

  it should "move an AtomicLeg at the head into executed and report Advanced" in {
    val cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(List(walkToStop, work)))

    val result = PlanCursor.advance(cursor)

    result shouldBe Advanced(PlanCursor(executed = List(walkToStop), remaining = RemainingQueue(List(work))))
  }

  it should "surface a PendingDecision at the head as PendingResolutionRequired, already dequeued" in {
    val cursor = PlanCursor(executed = List(home), remaining = RemainingQueue(List(pendingDecision, work)))

    val result = PlanCursor.advance(cursor)

    result shouldBe a[PendingResolutionRequired]
    val PendingResolutionRequired(surfaced, cursorWithoutHead) = result.asInstanceOf[PendingResolutionRequired]
    surfaced shouldBe pendingDecision
    cursorWithoutHead.executed shouldBe List(home)
    cursorWithoutHead.remaining.dequeue.map(_._1) shouldBe Some(work)
  }

  "expandPending" should "splice the resolved legs in exactly where the PendingDecision was" in {
    val cursor = PlanCursor(executed = List(home), remaining = RemainingQueue(List(pendingDecision, work)))
    val PendingResolutionRequired(_, cursorWithoutHead) =
      PlanCursor.advance(cursor).asInstanceOf[PendingResolutionRequired]

    val expanded = PlanCursor.expandPending(
      PendingResolutionRequired(pendingDecision, cursorWithoutHead),
      resolved = List(walkToStop, transit, walkFromStop)
    )

    expanded.executed shouldBe List(home)
    drain(expanded.remaining) shouldBe List(walkToStop, transit, walkFromStop, work)
  }

  "expandReplan" should "drop only the contiguous leg run at the head, leaving the trailing Activity untouched" in {
    val cursor = PlanCursor(
      executed = Nil,
      remaining = RemainingQueue(List(walkToStop, transit, walkFromStop, work))
    )
    val replacementWalk = WalkLeg(originNodeId = "n1", destinationNodeId = "n2")

    val replanned = PlanCursor.expandReplan(cursor, newLegs = List(replacementWalk))

    drain(replanned.remaining) shouldBe List(replacementWalk, work)
  }

  it should "not touch anything when the head is already an Activity (empty leg run)" in {
    val cursor = PlanCursor(executed = Nil, remaining = RemainingQueue(List(work)))

    val replanned = PlanCursor.expandReplan(cursor, newLegs = List(walkToStop))

    drain(replanned.remaining) shouldBe List(walkToStop, work)
  }

  private def drain(queue: RemainingQueue): List[PlanElement] =
    queue.dequeue match {
      case None            => Nil
      case Some((h, rest)) => h :: drain(rest)
    }
}
