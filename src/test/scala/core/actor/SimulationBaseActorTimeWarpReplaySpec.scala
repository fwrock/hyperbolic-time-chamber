package org.interscity.htc
package core.actor

import core.actor.rollback.LoggedEvent
import core.entity.actor.properties.Properties
import core.entity.event.{ FinishEvent, SpontaneousEvent }
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorRef, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for the `isReplaying` fix documented in `docs/TIME_WARP_DESIGN.md`'s §10
  * follow-up log entry: `RollbackHistoryHandler.rollbackTo`'s replay phase must reconstruct state
  * by re-executing `actSpontaneous`/`actInteractWith` without repeating the protocol side effects
  * those methods trigger on a live dispatch (`onFinishSpontaneous`'s `FinishEvent`,
  * `scheduleEvent`'s `ScheduleEvent`) — both would corrupt the time manager's bookkeeping if
  * resent during a replay that isn't a real new dispatch cycle — while still letting
  * `sendMessageTo` genuinely resend (deliberate, per §10: replay's resends get fresh identities so
  * the *original* sends can be anti-messaged).
  *
  * Drives a minimal concrete `SimulationBaseActor` through its real mailbox (`actorRef ! ...`,
  * same pattern `OptimisticLocalTimeManagerSpec` uses for the LTM side) so `handleSpontaneous`'s
  * real bookkeeping — including the `isTimeWarp`-gated `recordProcessedEvent` call this step
  * added — runs exactly as it would in a live simulation. Only `rollbackHandler.rollbackTo`
  * itself is invoked directly (via a test-exposed wrapper), since nothing in the simulator calls
  * it yet — that activation is still future work, this spec only proves the mechanism it will
  * eventually trigger is correct.
  */

/** Top-level (not nested in the Spec class) so Jackson can deserialize it via a plain no-arg-ish
  * constructor during `restoreSnapshotFn` — a non-static inner class needs its enclosing
  * instance, which `JsonUtil.fromJsonClassName` has no way to supply, and fails with
  * `InvalidDefinitionException` the moment `rollbackTo` actually restores a checkpoint.
  */
private case class CounterState(startTick: Tick = 0L, counter: Int = 0) extends BaseState(startTick = startTick)

private case class CounterTickData(value: Int)

class SimulationBaseActorTimeWarpReplaySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorTimeWarpReplaySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Minimal concrete actor: each `SpontaneousEvent` increments its own counter, sends the new
    * value to a peer, and reschedules itself — the same "mutate state + send + reschedule" shape
    * every real `actSpontaneous` override has, just stripped of unrelated business logic.
    */
  private class TestReplayActor(properties: Properties, peerEntityId: String)
      extends SimulationBaseActor[CounterState](properties) {

    var spontaneousDispatches: Int = 0

    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      spontaneousDispatches += 1
      state = state.copy(counter = state.counter + 1)
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId,
        data = CounterTickData(state.counter),
        eventType = "counter-tick",
        actorType = CreationTypeEnum.PoolDistributed
      )
      onFinishSpontaneous(Some(event.tick + 1))
    }

    def testSetState(s: CounterState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testRollbackTo(targetTick: Tick): Seq[LoggedEvent] = rollbackHandler.rollbackTo(targetTick)
    def testCounter: Int = state.counter
  }

  /** `sendMessageTo`'s `PoolDistributed` path addresses its target by convention at
    * `/user/{entityId}` (`BaseActor.getActorPoolRef`) — but a `TestProbe`'s own actor lives under
    * `/system/` (created via `systemActorOf`), not `/user/`, so it can never be the direct
    * addressee. This creates a real `/user/{name}` actor that forwards everything to a
    * `TestProbe`, so the probe's assertion API can still be used while satisfying the real
    * addressing convention `sendMessageTo` depends on — the same convention every real
    * `PoolDistributed` actor (e.g. a `Car`) is reached by in production.
    */
  private def newPeer(): (TestProbe, String) = {
    val probe = TestProbe()
    val name = probe.ref.path.name
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), name)
    (probe, name)
  }

  private def newActor(timeManagerProbe: TestProbe, peerEntityId: String): TestActorRef[TestReplayActor] = {
    val properties = Properties(
      entityId = "replay-actor-1",
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    val ref = TestActorRef(new TestReplayActor(properties, peerEntityId))
    // BaseActor is a PersistentActor: recovery (reading this persistenceId's journal, even an
    // empty in-memory one) is asynchronous regardless of TestActorRef's CallingThreadDispatcher,
    // and any command sent before RecoveryCompleted is internally stashed by Pekko Persistence,
    // not dropped -- but if the async recovery round-trip races past even a multi-second
    // expectMsgType timeout, the stash never drains in time either. A short, one-time wait here
    // (not a retry loop -- this always runs exactly once per actor, win or lose) reliably lets
    // recovery finish before the real test traffic starts. Same category of known Pekko/Akka
    // PersistentActor-in-tests gotcha `PersonMigrationSnapshotSpec`/`PrivateVehicleMigrationSnapshotSpec`
    // sidestep entirely by calling protected methods directly instead of going through the mailbox
    // — not an option here, since this spec's whole point is exercising the real
    // `handleSpontaneous`/`recordProcessedEvent` mailbox path.
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(CounterState(startTick = 0L, counter = 0))
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  "a Time Warp actor's live dispatch" should "send a real FinishEvent to the time manager and a real message to its peer" in {
    val timeManagerProbe = TestProbe()
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(timeManagerProbe, peerName)

    actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)

    timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("2")
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])
    actor.underlyingActor.testCounter shouldBe 1
  }

  "rollbackTo" should "restore state via replay without resending FinishEvent/ScheduleEvent to the time manager" in {
    val timeManagerProbe = TestProbe()
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(timeManagerProbe, peerName)

    // Two live dispatches -- counter goes 0 -> 1 -> 2, each acknowledged for real.
    actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)
    timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])

    actor ! SpontaneousEvent(tick = 2L, actorRef = timeManagerProbe.ref)
    timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])

    actor.underlyingActor.testCounter shouldBe 2

    // Roll back to before tick 2 -- the checkpoint (initialize(), before anything happened) is
    // restored and the surviving tick-1 event is replayed to walk state forward to exactly that
    // point again.
    val undone = actor.underlyingActor.testRollbackTo(targetTick = 2L)

    undone.map(_.tick) shouldBe Seq(2L)
    actor.underlyingActor.testCounter shouldBe 1 // back to post-tick-1, not post-tick-2

    // The critical assertion: replaying tick 1's actSpontaneous must NOT have resent a
    // FinishEvent to the time manager or corrupted its bookkeeping -- nothing should have
    // arrived there as a side effect of the rollback itself.
    timeManagerProbe.expectNoMessage(3.seconds)

    // But sendMessageTo's resend during replay IS expected -- a second, fresh
    // ActorInteractionEvent for the peer, per docs/TIME_WARP_DESIGN.md §10.
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])
  }

  it should "leave the actor able to resume live dispatch normally after a rollback" in {
    val timeManagerProbe = TestProbe()
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(timeManagerProbe, peerName)

    actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)
    timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])

    actor ! SpontaneousEvent(tick = 2L, actorRef = timeManagerProbe.ref)
    timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])

    actor.underlyingActor.testRollbackTo(targetTick = 2L)
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent]) // the replay's resend

    // Corrected re-dispatch for tick 2 (the causally-correct one this time) -- a normal live
    // dispatch, not a replay, so it must behave exactly like any other live dispatch: real
    // FinishEvent, real send, counter advances for real.
    actor ! SpontaneousEvent(tick = 2L, actorRef = timeManagerProbe.ref)

    timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("3")
    peerProbe.expectMsgClass(3.seconds, classOf[core.entity.event.ActorInteractionEvent])
    actor.underlyingActor.testCounter shouldBe 2
  }
}
