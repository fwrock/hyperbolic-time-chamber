package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.entity.event.control.report.ReportEvent
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** W0 of the Time Warp correctness plan: pins down, with tests, the rollback gaps found by reading
  * `SimulationBaseActor`/`RollbackHistoryHandler`, before any production code changes.
  *
  * Two kinds of test per gap:
  *   - "currently ..." tests are characterization tests: they assert today's (incorrect) behavior and
  *     pass. When the gap is fixed they will fail on purpose, and must be updated to the corrected
  *     expectation.
  *   - tests wrapped in `pendingUntilFixed` assert the correct behavior. They are reported as pending
  *     while the gap exists, and fail once the gap is fixed (telling you to drop the wrapper).
  *
  * Gaps covered:
  *   - A: an interaction undone by a rollback is never reprocessed after the straggler.
  *   - B: replaying a surviving event re-sends its messages (B1) and re-buffers its reports (B2),
  *     although the originals were already delivered and are never retracted.
  *   - R: after a rollback the actor is rescheduled at the tick of its last surviving event, which
  *     was already processed.
  *
  * Same real-mailbox TestActorRef harness as `SimulationBaseActorRollbackScheduleResyncSpec`.
  */
private case class GapsState(startTick: Tick = 0L, entries: List[String] = Nil)
    extends BaseState(startTick = startTick)

private case class GapsPayload(value: Int)

class SimulationBaseActorTimeWarpGapsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorTimeWarpGapsSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Records every event it handles into `state.entries` (so rollback and replay restore it like any
    * real state), sends one message to a peer per spontaneous dispatch, reports once per spontaneous
    * dispatch, and reschedules itself one tick ahead.
    */
  private class GapsActor(properties: Properties, peerEntityId: Long)
      extends SimulationBaseActor[GapsState](properties) {

    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      state = state.copy(entries = state.entries :+ s"S@${event.tick}")
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId.toString,
        data = GapsPayload(event.tick.toInt),
        eventType = "gaps-tick",
        actorType = CreationTypeEnum.PoolDistributed
      )
      report(data = event.tick, label = "gaps-report")
      onFinishSpontaneous(Some(event.tick + 1))
    }

    override def actInteractWith(event: ActorInteractionEvent): Unit =
      state = state.copy(entries = state.entries :+ s"I@${event.tick}:${event.actorRefId}")

    def testSetState(s: GapsState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testEntries: List[String] = state.entries
  }

  private var nextId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextId += 1
    val id = nextId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private def newActor(
    timeManagerProbe: TestProbe,
    reporterProbe: TestProbe,
    peerEntityId: Long
  ): TestActorRef[GapsActor] = {
    nextId += 1
    val ownEntityId = nextId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP,
      reporters = mutable.Map(ReportTypeEnum.parquet -> reporterProbe.ref)
    )
    val ref = TestActorRef(new GapsActor(properties, peerEntityId), ownEntityId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor: PersistentActor recovery is async even
    // under CallingThreadDispatcher; one short wait lets it finish before real traffic.
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(GapsState(startTick = 0L))
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def interaction(tick: Tick, senderId: Long, seq: Long = 1L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Sender",
      actorPathRef = s"/user/sender-$senderId",
      actorClassType = "test.Sender",
      eventType = "ping",
      data = GapsPayload(0),
      resourceId = "res-1",
      seq = seq,
      isAntiMessage = false
    )

  /** Two live spontaneous dispatches (ticks 1 and 3), then a straggler interaction at tick 2: tick 3
    * is undone, tick 1 survives and gets replayed. Everything produced up to the straggler is
    * drained from the probes, so tests only see what the rollback itself causes.
    */
  private def runScenarioWithSurvivingEvent(
    actor: TestActorRef[GapsActor],
    tm: TestProbe,
    peer: TestProbe
  ): Unit = {
    actor ! SpontaneousEvent(tick = 1L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent](3.seconds)
    peer.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    actor ! SpontaneousEvent(tick = 3L, actorRef = tm.ref)
    tm.expectMsgType[FinishEvent](3.seconds)
    peer.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    actor ! interaction(tick = 2L, senderId = 901L)
  }

  // ---------------------------------------------------------------------------------------------
  // Harness sanity: without a straggler the handlers run in arrival order. Guards the pending tests
  // below against being "pending" only because the harness itself is broken.
  // ---------------------------------------------------------------------------------------------

  "the harness" should "record events in arrival order when there is no straggler" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (_, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    actor ! interaction(tick = 100L, senderId = 201L)
    actor ! interaction(tick = 103L, senderId = 202L)

    actor.underlyingActor.testEntries shouldBe List("I@100:201", "I@103:202")
  }

  // ---------------------------------------------------------------------------------------------
  // Gap A: undone interactions are not reprocessed
  // ---------------------------------------------------------------------------------------------

  "a straggler rollback" should "currently lose the interaction it undid (Gap A, characterization)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (_, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    actor ! interaction(tick = 100L, senderId = 201L)
    actor ! interaction(tick = 103L, senderId = 202L)
    actor ! interaction(tick = 101L, senderId = 203L) // straggler: 101 < currentTick 103

    // I@103:202 was undone by the rollback to 101 and nothing ever reprocesses it.
    actor.underlyingActor.testEntries shouldBe List("I@100:201", "I@101:203")
  }

  it should "reprocess the interaction it undid, after the straggler, in tick order (Gap A, desired)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (_, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    actor ! interaction(tick = 100L, senderId = 201L)
    actor ! interaction(tick = 103L, senderId = 202L)
    actor ! interaction(tick = 101L, senderId = 203L)

    pendingUntilFixed {
      actor.underlyingActor.testEntries shouldBe List("I@100:201", "I@101:203", "I@103:202")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Gap B1: replay re-sends messages that were already delivered
  // ---------------------------------------------------------------------------------------------

  it should "currently re-send, during replay, the message of a surviving event (Gap B1, characterization)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    runScenarioWithSurvivingEvent(actor, tm, peer)

    val received = peer.receiveWhile(2.seconds) { case e: ActorInteractionEvent => e }
    // One anti-message for the undone tick-3 send, plus one duplicate of the tick-1 send that the
    // replay re-issued although the receiver already processed the original.
    received.count(_.isAntiMessage) shouldBe 1
    received.count(!_.isAntiMessage) shouldBe 1
  }

  it should "not re-send the messages of surviving events during replay (Gap B1, desired)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    runScenarioWithSurvivingEvent(actor, tm, peer)

    val received = peer.receiveWhile(2.seconds) { case e: ActorInteractionEvent => e }
    pendingUntilFixed {
      received.filterNot(_.isAntiMessage) shouldBe empty
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Gap B2: replay re-buffers reports of surviving events
  // ---------------------------------------------------------------------------------------------

  private def flushedReportTicks(
    actor: TestActorRef[GapsActor],
    tm: TestProbe,
    peer: TestProbe,
    reporter: TestProbe
  ): Seq[Any] = {
    runScenarioWithSurvivingEvent(actor, tm, peer)
    // A later dispatch carries a GVT covering everything, which flushes whatever is buffered.
    actor ! SpontaneousEvent(tick = 4L, actorRef = tm.ref, gvt = Some(Long.MaxValue))
    reporter.receiveWhile(2.seconds) { case r: ReportEvent => r.data }
  }

  it should "currently flush the report of a surviving event twice (Gap B2, characterization)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    val flushed = flushedReportTicks(actor, tm, peer, reporter)

    flushed.count(_ == 1L) shouldBe 2 // original buffered report + the one replay added again
    flushed.count(_ == 3L) shouldBe 0 // tick 3 was undone and rightly discarded
  }

  it should "flush the report of a surviving event exactly once (Gap B2, desired)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    val flushed = flushedReportTicks(actor, tm, peer, reporter)

    pendingUntilFixed {
      flushed.count(_ == 1L) shouldBe 1
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Gap R: resync reschedules an already-processed tick
  // ---------------------------------------------------------------------------------------------

  private def resyncTickAfterRollback(
    actor: TestActorRef[GapsActor],
    tm: TestProbe,
    peer: TestProbe
  ): Tick = {
    runScenarioWithSurvivingEvent(actor, tm, peer)
    val clearing = tm.expectMsgType[FinishEvent](3.seconds)
    clearing.scheduleTick shouldBe None
    tm.expectMsgType[ScheduleEvent](3.seconds).tick
  }

  "the post-rollback resync" should "currently reschedule the actor at the tick of its last surviving event (Gap R, characterization)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    // Tick 1 survived and is already reflected in state; the LTM is asked to dispatch tick 1 again.
    resyncTickAfterRollback(actor, tm, peer) shouldBe 1L
  }

  it should "reschedule the actor strictly after the last surviving event (Gap R, desired)" in {
    val tm = TestProbe()
    val reporter = TestProbe()
    val (peer, peerId) = newPeer()
    val actor = newActor(tm, reporter, peerId)

    val rescheduledAt = resyncTickAfterRollback(actor, tm, peer)

    pendingUntilFixed {
      rescheduledAt should be > 1L
    }
  }
}
