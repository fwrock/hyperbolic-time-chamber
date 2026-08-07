package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.report.ReportEvent
import core.entity.state.BaseState
import core.enumeration.{ ReportTypeEnum, TimeManagerTypeEnum }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorSystem }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for `docs/TIME_WARP_DESIGN.md` §4 (irreversible side effects buffered until
  * GVT passes): `SimulationBaseActor.report()` must not forward to the reporter pipeline
  * immediately under Time Warp the way it always has under conservative mode -- it must hold the
  * `ReportEvent` back until a `SpontaneousEvent` carries a GVT estimate that covers its tick, and
  * must discard (never flush) anything covering a tick a later rollback undoes.
  *
  * Same TestActorRef/real-mailbox harness as `SimulationBaseActorTimeWarpReplaySpec`.
  */
private case class ReportBufferState(startTick: Tick = 0L) extends BaseState(startTick = startTick)

class SimulationBaseActorReportBufferSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorReportBufferSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Each `SpontaneousEvent` OR interaction reports its own tick as `data` -- report() is the only
    * thing under test. `actSpontaneous` is only used to piggyback a GVT and trigger a flush;
    * `actInteractWith` is what the rollback-discard test drives, since the real straggler trigger
    * (`docs/TIME_WARP_DESIGN.md` §10) only lives in `handleInteractWith`, not `handleSpontaneous`
    * (see `SimulationBaseActorStragglerTriggerSpec`, same harness pattern used here).
    */
  private class TestReportActor(properties: Properties) extends SimulationBaseActor[ReportBufferState](properties) {
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      report(data = event.tick, label = "test-report")
      onFinishSpontaneous(Some(event.tick + 1))
    }

    override def actInteractWith(event: ActorInteractionEvent): Unit =
      report(data = event.tick, label = "test-report")

    def testSetState(s: ReportBufferState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
  }

  private def interaction(tick: Tick, senderId: String, seq: Long): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Sender",
      actorPathRef = s"/user/$senderId",
      actorClassType = "test.Sender",
      eventType = "ping",
      data = "ping",
      resourceId = "res-1",
      seq = seq
    )

  private def newActor(timeManagerType: String, timeManagerProbe: TestProbe, reporterProbe: TestProbe): TestActorRef[TestReportActor] = {
    val properties = Properties(
      entityId = "report-buffer-actor-1",
      resourceId = "res-1",
      timeManagers = mutable.Map(timeManagerType -> timeManagerProbe.ref),
      defaultTimeManagerType = timeManagerType,
      reporters = mutable.Map(ReportTypeEnum.parquet -> reporterProbe.ref)
    )
    val ref = TestActorRef(new TestReportActor(properties))
    // See SimulationBaseActorTimeWarpReplaySpec's newActor doc: PersistentActor recovery is
    // asynchronous even under TestActorRef's CallingThreadDispatcher.
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(ReportBufferState(startTick = 0L))
    if (timeManagerType == TimeManagerTypeEnum.TIME_WARP) ref.underlyingActor.testInitializeRollback()
    ref
  }

  "report() under conservative mode" should "still send to the reporter immediately, unaffected by the buffering added for Time Warp" in {
    val timeManagerProbe = TestProbe()
    val reporterProbe = TestProbe()
    val actor = newActor(TimeManagerTypeEnum.DISCRETE_EVENT, timeManagerProbe, reporterProbe)

    actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)

    reporterProbe.expectMsgType[ReportEvent](3.seconds).data shouldBe 1L
  }

  "report() under Time Warp" should "buffer instead of sending immediately, then flush once a SpontaneousEvent carries a covering GVT" in {
    val timeManagerProbe = TestProbe()
    val reporterProbe = TestProbe()
    val actor = newActor(TimeManagerTypeEnum.TIME_WARP, timeManagerProbe, reporterProbe)

    actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)
    reporterProbe.expectNoMessage(500.millis) // buffered, not sent -- §4's whole point

    // A later dispatch carries a GVT that has now passed tick 1 -- the buffered report for tick 1
    // must flush as a side effect of this dispatch, even though this event's own tick is 2.
    actor ! SpontaneousEvent(tick = 2L, actorRef = timeManagerProbe.ref, gvt = Some(1L))

    reporterProbe.expectMsgType[ReportEvent](3.seconds).data shouldBe 1L // flushed tick-1 report
    reporterProbe.expectNoMessage(500.millis) // tick-2's own report is not yet covered by gvt=1
  }

  it should "discard a buffered report for a tick a real rollback undoes, never flushing it later" in {
    val timeManagerProbe = TestProbe()
    val reporterProbe = TestProbe()
    val actor = newActor(TimeManagerTypeEnum.TIME_WARP, timeManagerProbe, reporterProbe)

    // Two live interactions, processed in order: reports for tick 1 and tick 2 both buffered.
    actor ! interaction(tick = 1L, senderId = "sender-a", seq = 1L)
    actor ! interaction(tick = 2L, senderId = "sender-a", seq = 2L)
    reporterProbe.expectNoMessage(500.millis) // both still buffered, no GVT has covered either yet

    // A straggler for tick=1 arrives after -- causally earlier than what this actor already
    // processed (currentTick=2) -- driving the real handleInteractWith straggler trigger
    // (docs/TIME_WARP_DESIGN.md §10), which rolls back to before it via rollbackAndCascade. That
    // undoes both the original tick=1 and tick=2 processing (rollbackTo(1) undoes everything with
    // tick >= 1), discarding both buffered reports, before reprocessing this straggler for real.
    actor ! interaction(tick = 1L, senderId = "sender-b", seq = 1L)

    // Advance GVT far enough to cover everything. Only the straggler's own reprocessed tick=1
    // report should ever reach the reporter -- the original tick=1 and tick=2 reports were
    // discarded by the rollback, not merely delayed, so this must be exactly one message.
    actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref, gvt = Some(10L))

    reporterProbe.expectMsgType[ReportEvent](3.seconds).data shouldBe 1L
    reporterProbe.expectNoMessage(500.millis)
  }
}
