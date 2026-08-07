package org.interscity.htc
package core.actor.rollback

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.types.Tick

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class RollbackHistoryHandlerSpec extends AnyFlatSpec with Matchers {

  /** A minimal `BaseEvent` for tests — avoids needing a real `ActorRef`. */
  private case class TestEvent(tick: Tick, label: String)
      extends BaseEvent[DefaultBaseEventData](tick = tick)

  /** Fake "actor": a mutable counter plus a log of every event it ever applied, standing in for
    * real simulation state. `capture`/`restore` round-trip the counter through a fake
    * `MigrationSnapshot` (its JSON fields are repurposed to hold the counter as a string — no real
    * actor/JsonUtil involved, this is testing RollbackHistoryHandler in isolation per
    * docs/TIME_WARP_DESIGN.md's step-3 scoping).
    */
  private class FakeActor {
    var counter: Int = 0
    val applied: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

    def capture(): MigrationSnapshot = MigrationSnapshot(stateJson = counter.toString, stateClassName = "Int")
    def restore(snapshot: MigrationSnapshot): Unit = counter = snapshot.stateJson.toInt

    def apply(event: AnyRef): Unit =
      event match {
        case TestEvent(_, label) =>
          counter += 1
          applied += label
      }
  }

  private def newHandler(actor: FakeActor, checkpointInterval: Int = 3): RollbackHistoryHandler =
    new RollbackHistoryHandler(
      checkpointInterval = checkpointInterval,
      captureSnapshotFn = () => actor.capture(),
      restoreSnapshotFn = snap => actor.restore(snap),
      replayEventFn = logged => actor.apply(logged.event)
    )

  /** Simulates real usage: the actor processes the event first (mutating its own state), then
    * tells the handler about it. Returns the event, so callers can assert on it if needed.
    */
  private def record(handler: RollbackHistoryHandler, actor: FakeActor, tick: Long, seq: Long, label: String): TestEvent = {
    val event = TestEvent(tick = tick, label = label)
    actor.apply(event)
    handler.recordProcessedEvent(event, tick = tick, seq = seq)
    event
  }

  "initialize" should "establish a floor checkpoint before any event is recorded" in {
    val actor = new FakeActor
    val handler = newHandler(actor)
    handler.initialize(startTick = 0L)

    handler.checkpointCount shouldBe 1
    handler.logSize shouldBe 0
  }

  "recordProcessedEvent" should "take a full checkpoint only every checkpointInterval calls" in {
    val actor = new FakeActor
    val handler = newHandler(actor, checkpointInterval = 3)
    handler.initialize(startTick = 0L)

    for (i <- 1 to 5) record(handler, actor, tick = i.toLong, seq = i.toLong, label = s"e$i")

    handler.logSize shouldBe 5
    // initial floor (seq=0) + one taken at the 3rd recorded event (seq=3)
    handler.checkpointCount shouldBe 2
  }

  "rollbackTo" should "be a no-op when nothing is at or after the target tick" in {
    val actor = new FakeActor
    val handler = newHandler(actor)
    handler.initialize(startTick = 0L)
    record(handler, actor, tick = 1L, seq = 1L, label = "e1")

    val undone = handler.rollbackTo(targetTick = 5L)

    undone shouldBe empty
    actor.counter shouldBe 1 // untouched — no restore happened
  }

  it should "restore the nearest earlier checkpoint and replay forward to exactly the target tick" in {
    val actor = new FakeActor
    val handler = newHandler(actor, checkpointInterval = 100) // force a single floor checkpoint only
    handler.initialize(startTick = 0L)

    for (i <- 1 to 5) record(handler, actor, tick = i.toLong, seq = i.toLong, label = s"e$i")
    actor.counter shouldBe 5
    actor.applied.toList shouldBe List("e1", "e2", "e3", "e4", "e5")

    // A straggler arrives for tick 3: roll back to before it. e1/e2 survive (replayed from the
    // floor checkpoint), e3/e4/e5 are undone.
    val undone = handler.rollbackTo(targetTick = 3L)

    undone.map(_.tick) shouldBe Seq(3L, 4L, 5L)
    // e1/e2 were applied once during normal processing, then again during replay from the floor.
    actor.applied.toList shouldBe List("e1", "e2", "e3", "e4", "e5", "e1", "e2")
    actor.counter shouldBe 2 // floor(0) + replayed e1 + e2, e3/e4/e5's effects gone
    handler.logSize shouldBe 2
  }

  it should "leave the log/checkpoints consistent for a second rollback after the first" in {
    val actor = new FakeActor
    val handler = newHandler(actor, checkpointInterval = 100)
    handler.initialize(startTick = 0L)

    for (i <- 1 to 5) record(handler, actor, tick = i.toLong, seq = i.toLong, label = s"e$i")
    handler.rollbackTo(targetTick = 3L) // undoes e3/e4/e5, keeps e1/e2

    // Now record a fresh e3' (the corrected causal order, seq must keep increasing — never reuse
    // a seq that belonged to an undone event) and roll back again, this time to before e1 —
    // everything must unwind, including the just-replayed e1/e2.
    record(handler, actor, tick = 3L, seq = 6L, label = "e3-corrected")

    val undone = handler.rollbackTo(targetTick = 1L)

    undone.map(_.seq) shouldBe Seq(1L, 2L, 6L)
    actor.counter shouldBe 0 // back to the pristine floor, nothing replayed (target is the very first tick)
    handler.logSize shouldBe 0
    handler.checkpointCount shouldBe 1
  }

  "pruneBelow" should "discard checkpoints/log entries strictly before the retained floor" in {
    val actor = new FakeActor
    val handler = newHandler(actor, checkpointInterval = 2)
    handler.initialize(startTick = 0L)

    for (i <- 1 to 6) record(handler, actor, tick = i.toLong, seq = i.toLong, label = s"e$i")
    // Checkpoints at seq=0(initial),2,4,6
    handler.checkpointCount shouldBe 4

    handler.pruneBelow(gvt = 3L)

    // Floor becomes the checkpoint at seq=2 (tick=2, the latest one with tick <= 3); the initial
    // floor(seq=0) is gone, seq=4/6 checkpoints untouched.
    handler.checkpointCount shouldBe 3
    handler.logSize shouldBe 5 // events with seq 2,3,4,5,6 remain (seq >= floor's seq=2)
  }

  it should "never discard the floor needed for a rollback still targeting exactly gvt" in {
    val actor = new FakeActor
    val handler = newHandler(actor, checkpointInterval = 100)
    handler.initialize(startTick = 0L)

    for (i <- 1 to 5) record(handler, actor, tick = i.toLong, seq = i.toLong, label = s"e$i")

    handler.pruneBelow(gvt = 10L) // way past everything logged; only the initial floor qualifies

    handler.checkpointCount shouldBe 1
    noException should be thrownBy handler.rollbackTo(targetTick = 1L)
  }
}
