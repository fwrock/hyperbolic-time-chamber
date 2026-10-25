package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.control.execution.ReplaceScheduleEvent
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
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

/** Rollback replaces the complete logical schedule atomically with a newer transport revision.
  * It must retain real reply obligations and the schedule of the surviving state prefix.
  */
private case class ResyncState(startTick: Tick = 0L, counter: Int = 0) extends BaseState(startTick = startTick)

private case class ResyncTickData(value: Int)

class SimulationBaseActorRollbackScheduleResyncSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorRollbackScheduleResyncSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Every spontaneous dispatch mutates state, relays to a peer, and schedules itself 1 tick
    * ahead -- the same "mutate + send + reschedule" shape `SimulationBaseActorTimeWarpReplaySpec`'s
    * `TestReplayActor` uses. Interaction events are a plain no-op: this spec only needs them to
    * trigger the straggler branch of `handleInteractWith`.
    */
  private class ResyncActor(properties: Properties, peerEntityId: Long)
      extends SimulationBaseActor[ResyncState](properties) {

    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      state = state.copy(counter = state.counter + 1)
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId.toString,
        data = ResyncTickData(state.counter),
        eventType = "resync-tick",
        actorType = CreationTypeEnum.PoolDistributed
      )
      onFinishSpontaneous(Some(event.tick + 1))
    }

    def testSetState(s: ResyncState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
  }

  /** Same "mutate + send + reschedule" shape as [[ResyncActor]], but also registers an awaited
    * reply (key "keyA") when dispatched at `awaitAtTick` -- lets a test pin whether that
    * registration lands inside or outside a subsequent rollback's undone range.
    */
  private class ResyncActorWithAwait(properties: Properties, peerEntityId: Long, awaitAtTick: Tick)
      extends SimulationBaseActor[ResyncState](properties) {

    override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
      state = state.copy(counter = state.counter + 1)
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId.toString,
        data = ResyncTickData(state.counter),
        eventType = "resync-tick",
        actorType = CreationTypeEnum.PoolDistributed
      )
      if (event.tick == awaitAtTick) registerAwaitedReply("keyA", "KindA")
      onFinishSpontaneous(Some(event.tick + 1))
    }

    def testSetState(s: ResyncState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
  }

  private var nextPeerId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextPeerId += 1
    val id = nextPeerId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private def newActor(timeManagerProbe: TestProbe, peerEntityId: Long): TestActorRef[ResyncActor] = {
    nextPeerId += 1
    val ownEntityId = nextPeerId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    val ref = TestActorRef(new ResyncActor(properties, peerEntityId), ownEntityId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor: PersistentActor recovery is async even
    // under CallingThreadDispatcher; a short one-time wait lets it finish before real traffic.
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(ResyncState(startTick = 0L, counter = 0))
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def newAwaitActor(
    timeManagerProbe: TestProbe,
    peerEntityId: Long,
    awaitAtTick: Tick
  ): TestActorRef[ResyncActorWithAwait] = {
    nextPeerId += 1
    val ownEntityId = nextPeerId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    val ref = TestActorRef(new ResyncActorWithAwait(properties, peerEntityId, awaitAtTick), ownEntityId.toString)
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(ResyncState(startTick = 0L, counter = 0))
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def interaction(tick: Tick, senderId: Long, seq: Long): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Sender",
      actorPathRef = s"/user/sender-$senderId",
      actorClassType = "test.Sender",
      eventType = "ping",
      data = ResyncTickData(0),
      resourceId = "res-1",
      seq = seq,
      isAntiMessage = false
    )

  "a straggler-triggered rollback that undoes the actor's own live spontaneous dispatch" should
    "clear the stale time-manager registration and re-register at the actor's genuine post-rollback tick" in {
      val timeManagerProbe = TestProbe()
      val (peerProbe, peerName) = newPeer()
      val actor = newActor(timeManagerProbe, peerName)

      // Live dispatch at tick=5: counter 0 -> 1, and (critically) a real FinishEvent registering
      // this actor at tick=6 with the time manager -- the registration the rollback below must undo.
      actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("6")
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      // A causally-earlier interaction arrives after: tick=3 < currentTick=5, so
      // handleInteractWith's straggler branch rolls back to before tick=5, undoing that dispatch.
      actor ! interaction(tick = 3L, senderId = 101L, seq = 1L)

      val replacement = timeManagerProbe.expectMsgType[ReplaceScheduleEvent](3.seconds)
      replacement.next.map(_.tick) shouldBe Some(0L)
      replacement.revision should be > 1L
      replacement.completedGeneration should not be Long.MaxValue
      replacement.awaitingReply shouldBe false

      timeManagerProbe.expectNoMessage(1.second)
    }

  it should "be a no-op straggler (no resync at all) when nothing was actually rolled back" in {
    val timeManagerProbe = TestProbe()
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(timeManagerProbe, peerName)

    actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref)
    timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    // tick=5 is not < currentTick=5, so this is an ordinary (non-straggler) interaction -- no
    // rollback, hence no resync traffic to the time manager at all.
    actor ! interaction(tick = 5L, senderId = 101L, seq = 1L)

    timeManagerProbe.expectNoMessage(1.second)
  }

  "a rollback that leaves a genuine, unretracted awaited-reply entry outstanding" should
    "clear the stale time-manager registration but skip the forced re-dispatch" in {
      val timeManagerProbe = TestProbe()
      val (peerProbe, peerName) = newPeer()
      // Registers "keyA" during the tick=0 dispatch -- outside the tick>=3 range the rollback below undoes.
      val actor = newAwaitActor(timeManagerProbe, peerName, awaitAtTick = 0L)

      actor ! SpontaneousEvent(tick = 0L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("1")
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("6")
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      // Straggler rollback to before tick=5 undoes only the tick=5 dispatch -- "keyA", registered
      // at tick=0, survives pruning untouched.
      actor ! interaction(tick = 3L, senderId = 101L, seq = 1L)

      val replacement = timeManagerProbe.expectMsgType[ReplaceScheduleEvent](3.seconds)
      replacement.next shouldBe None
      replacement.awaitingReply shouldBe true

      timeManagerProbe.expectNoMessage(1.second)
    }

  "a rollback whose undone range includes the awaited-reply's own registering event" should
    "still force the re-dispatch exactly as before the pruning fix" in {
      val timeManagerProbe = TestProbe()
      val (peerProbe, peerName) = newPeer()
      // Registers "keyA" during the tick=5 dispatch -- inside the tick>=3 range the rollback below undoes.
      val actor = newAwaitActor(timeManagerProbe, peerName, awaitAtTick = 5L)

      actor ! SpontaneousEvent(tick = 0L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("1")
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe Some("6")
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      // Straggler rollback to before tick=5 undoes the tick=5 dispatch -- "keyA"'s own registering
      // send is retracted, so pruneInvalidatedAwaitedReplies drops it.
      actor ! interaction(tick = 3L, senderId = 101L, seq = 1L)

      val replacement = timeManagerProbe.expectMsgType[ReplaceScheduleEvent](3.seconds)
      replacement.next.map(_.tick) shouldBe Some(1L)
      replacement.awaitingReply shouldBe false

      timeManagerProbe.expectNoMessage(1.second)
    }
}
