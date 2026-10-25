package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
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

/** Regression coverage for the rollback-orphaned-reply bug fixed by `SimulationBaseActor`'s new
  * `registerAwaitedReply`/`isAwaitingReply`/`consumeAwaitedReply` primitive (design Problem 1a):
  * a rollback can restore `state.status` (a wait-marker) to its pre-request value while a genuine,
  * un-retractable reply is still in flight; a stale-guard that only consults `state.status` then
  * drops that reply, and since the request was sent via a "disengage-then-scheduleEvent" pattern
  * (`onFinishSpontaneous(None)` fires at send time; only the reply handler's `scheduleEvent` call
  * ever reschedules the actor), the actor is never rescheduled again.
  *
  * Also covers the follow-up `pruneInvalidatedAwaitedReplies` fix: once a rollback's anti-message
  * cascade actually retracts the send an `outstandingAwaitedReplies` entry was waiting on, that
  * entry is dropped rather than left to (incorrectly) validate a reply for a request that never
  * causally happened -- `resyncScheduleAfterRollback`'s forced re-dispatch is what re-establishes a
  * genuine wait afterward, exactly like a fresh `registerAwaitedReply` superseding a stale one.
  *
  * Same harness style as `SimulationBaseActorStragglerTriggerSpec` (real mailbox traffic, a
  * `/user/{name}` forwarder standing in for a `TestProbe` peer, a one-time wait for
  * `PersistentActor` recovery).
  */
private case class WaitState(startTick: Tick = 0L, status: String = "Idle", replies: Int = 0)
    extends BaseState(startTick = startTick)

private case class ReqData()
private case class ReplyData()
private case class TriggerData()

class SimulationBaseActorAwaitedReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorAwaitedReplySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Mirrors CarSignalHandler's requestSignalState/handleLinkAccess shape: a spontaneous dispatch
    * sends one request, registers the awaited reply, and disengages (`onFinishSpontaneous(None)`)
    * without ever scheduling itself again -- only the reply handler's `scheduleEvent` call can ever
    * bring this actor back to life.
    */
  private class WaitMarkerActor(properties: Properties, peerEntityId: Long) extends SimulationBaseActor[WaitState](properties) {

    private def isTimeWarpMode: Boolean = getCurrentTimeManagerType == TimeManagerTypeEnum.TIME_WARP

    override def actSpontaneous(event: SpontaneousEvent): Unit = {
      state = state.copy(status = "Waiting")
      // registerAwaitedReply must run AFTER sendMessageTo: it captures this actor's
      // messageSendSeq as the request's identity token, which must equal the seq sendMessageTo
      // just embedded on the wire for THIS send (see Movable.enterLink's identical comment).
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId.toString,
        data = ReqData(),
        eventType = "req",
        actorType = CreationTypeEnum.PoolDistributed
      )
      registerAwaitedReply("req", "Reply")
      onFinishSpontaneous(None)
    }

    override def actInteractWith(event: ActorInteractionEvent): Unit =
      event.data match {
        case _: ReplyData =>
          val isStale =
            if (isTimeWarpMode) !isAwaitingReply("req", "Reply", event.seq)
            else state.status != "Waiting"
          if (!isStale) {
            consumeAwaitedReply("req")
            state = state.copy(status = "Done", replies = state.replies + 1)
            scheduleEvent(currentTick)
          }
        case _ => () // TriggerData: fire-and-forget, doesn't reschedule on its own
      }

    def testSetState(s: WaitState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testStatus: String = state.status
    def testReplies: Int = state.replies
    def testHasReplyObligation(key: String): Boolean = awaitedReplySendSeq(key).nonEmpty
    def testIsAwaitingReply(key: String, kind: String, sendSeq: Long): Boolean = isAwaitingReply(key, kind, sendSeq)
  }

  private var nextPeerId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextPeerId += 1
    val id = nextPeerId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private def newActor(peerEntityId: Long, timeManagerType: String): TestActorRef[WaitMarkerActor] = {
    nextPeerId += 1
    val ownEntityId = nextPeerId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map.empty,
      defaultTimeManagerType = timeManagerType
    )
    val ref = TestActorRef(new WaitMarkerActor(properties, peerEntityId), ownEntityId.toString)
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(WaitState(startTick = 0L, status = "Idle", replies = 0))
    if (timeManagerType == TimeManagerTypeEnum.TIME_WARP) ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def spontaneous(tick: Tick, tmRef: org.apache.pekko.actor.ActorRef): SpontaneousEvent =
    SpontaneousEvent(tick, tmRef)

  private def interaction(tick: Tick, senderId: Long, data: AnyRef, eventType: String, seq: Long = 0L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Sender",
      actorPathRef = s"/user/sender-$senderId",
      actorClassType = "test.Sender",
      eventType = eventType,
      data = data,
      resourceId = "res-1",
      seq = seq
    )

  "a Time Warp actor" should "resend its request (rather than accept a stale reply for the retracted one) after a rollback prunes the awaited-reply entry" in {
    val (peerProbe, peerName) = newPeer()
    val tmProbe = TestProbe()
    val actor = newActor(peerName, TimeManagerTypeEnum.TIME_WARP)

    // Live dispatch at tick=5: sends the request, registers the awaited reply (sendSeq=1), disengages.
    actor ! spontaneous(5L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent]).data shouldBe ReqData()
    tmProbe.expectMsgClass(3.seconds, classOf[FinishEvent]) // the tick=5 dispatch's own disengage
    actor.underlyingActor.testStatus shouldBe "Waiting"

    // A genuinely causally-earlier interaction arrives afterward -- this actor rolls back to
    // before it, restoring state (including status) all the way to its pre-tick-5 checkpoint. The
    // request sent during the now-undone tick=5 event is itself retracted, so
    // SimulationBaseActor.pruneInvalidatedAwaitedReplies drops the "req" entry: the request it
    // answers never causally happened, and any reply for it is now genuinely stale, not merely
    // stale-by-state.status the way the pre-pruning-fix version of this test treated it.
    actor ! interaction(3L, senderId = 999L, data = TriggerData(), eventType = "trigger")

    // The rollback's own anti-message cascade retracts the tick=5 "req" send -- drained here so it
    // doesn't get mistaken for the fresh dispatch's own "req" send below.
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    // The replacement clears every obsolete registration and carries the restored schedule atomically.
    val resync = tmProbe.expectMsgType[core.entity.event.control.execution.ReplaceScheduleEvent](3.seconds)
    // The restored checkpoint is genesis (startTick=0) -- nothing survived the rollback to replay
    // forward from, so 0 is this actor's genuine post-rollback tick, not the interaction's tick=3.
    resync.next.map(_.tick) shouldBe Some(0L)
    actor.underlyingActor.testStatus shouldBe "Idle" // the wait-marker really was wiped by the rollback

    // The stale reply for the now-retracted original request (seq=1L, the pruned entry's identity)
    // arrives and must be rejected: isAwaitingReply("req", "Reply", 1L) is now false.
    actor ! interaction(5L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 1L)
    tmProbe.expectNoMessage(1.second)
    actor.underlyingActor.testReplies shouldBe 0
    actor.underlyingActor.testStatus shouldBe "Idle"

    // The real time manager would honor the replacement by dispatching a fresh
    // SpontaneousEvent at this actor's genuine post-rollback tick -- simulated directly here, since
    // tmProbe is a bare probe with no scheduling logic of its own. The fresh dispatch resends "req"
    // with a new identity (sendSeq=2).
    actor ! spontaneous(0L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent]).data shouldBe ReqData()
    tmProbe.expectMsgClass(3.seconds, classOf[FinishEvent]) // the fresh dispatch's own disengage
    actor.underlyingActor.testStatus shouldBe "Waiting"

    // The genuine reply for the fresh (second) request, tagged with its own identity (seq=2L), is
    // accepted and reschedules the actor -- proof the pruning fix doesn't orphan it permanently.
    actor ! interaction(0L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 2L)
    val resched = tmProbe.fishForMessage(3.seconds) { case _: ScheduleEvent => true; case _ => false }
    resched.asInstanceOf[ScheduleEvent].tick shouldBe 0L
    actor.underlyingActor.testStatus shouldBe "Done"
    actor.underlyingActor.testReplies shouldBe 1
  }

  it should "still drop a genuinely stale reply (superseded by a fresh request for the same key)" in {
    val (peerProbe, peerName) = newPeer()
    val tmProbe = TestProbe()
    val actor = newActor(peerName, TimeManagerTypeEnum.TIME_WARP)

    actor ! spontaneous(5L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    // A second, fresh dispatch for the same key supersedes the first registration.
    actor ! spontaneous(6L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    // Consume the second (current) registration via a real reply, leaving nothing outstanding.
    // seq=2L: this actor's second Time-Warp send, matching the second registerAwaitedReply's
    // sendSeq (the first registration's sendSeq=1L was superseded/overwritten).
    actor ! interaction(6L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 2L)
    tmProbe.fishForMessage(3.seconds) { case _: ScheduleEvent => true; case _ => false }
    actor.underlyingActor.testReplies shouldBe 1

    // A duplicate/late reply arriving after the key has already been consumed must still be
    // dropped as stale -- no second reschedule, no second increment.
    actor ! interaction(6L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 2L)
    tmProbe.expectNoMessage(1.second)
    actor.underlyingActor.testReplies shouldBe 1
  }

  it should "reject a reply tagged with a superseded request's identity even though nothing has consumed the current registration yet, and accept one tagged with the current request's identity" in {
    val (peerProbe, peerName) = newPeer()
    val tmProbe = TestProbe()
    val actor = newActor(peerName, TimeManagerTypeEnum.TIME_WARP)

    // Live dispatch at tick=5: sends the request, registers "req" with sendSeq=1. Each dispatch's
    // own onFinishSpontaneous(None) disengage sends tmProbe a FinishEvent -- drained explicitly
    // here (unlike the fishForMessage-based tests above, which skip past it implicitly) since the
    // stale-rejection check below uses expectNoMessage, which fails on ANY queued message.
    actor ! spontaneous(5L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    tmProbe.expectMsgClass(3.seconds, classOf[FinishEvent])
    actor.underlyingActor.testStatus shouldBe "Waiting"

    // The exact failure chain from the live rollback-cascade livelock: a rollback later undoes
    // that send and resync forces an immediate re-dispatch, which re-sends the SAME kind of
    // request to the SAME key -- registerAwaitedReply for "req" is called again (sendSeq=2),
    // overwriting the first entry, while the first request's reply may still be in flight
    // (its anti-message and this resend can race; nothing guarantees the anti-message wins).
    actor ! spontaneous(6L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    tmProbe.expectMsgClass(3.seconds, classOf[FinishEvent])

    // The stale reply belonging to the FIRST (superseded) request instance arrives, correctly
    // tagged with its own original identity (seq=1L). Tagged with tick=6 (the actor's current
    // tick, not the tick=5 it was originally sent at) so this exercises identity staleness in
    // isolation from causal/Lamport staleness -- a real reply's wall-clock arrival order doesn't
    // imply anything about which request it identity-wise answers, and this fix's whole point is
    // that key+kind alone (both still matching) isn't enough. Under the pre-fix kind-only
    // isAwaitingReply check this matched "req"/"Reply" and was wrongly accepted, scheduling the
    // actor off stale data and orphaning the genuine second reply when it later arrived and found
    // the entry already consumed -- the rollback-cascade livelock. It must now be rejected as stale.
    actor ! interaction(6L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 1L)
    tmProbe.expectNoMessage(1.second)
    actor.underlyingActor.testReplies shouldBe 0
    actor.underlyingActor.testStatus shouldBe "Waiting"

    // The genuine reply for the CURRENT (second) request instance, tagged with its own identity
    // (seq=2L), must still be accepted and resolve/reschedule the actor -- the whole point of
    // registerAwaitedReply superseding rather than merging entries.
    actor ! interaction(6L, senderId = peerName, data = ReplyData(), eventType = "reply", seq = 2L)
    val resched = tmProbe.fishForMessage(3.seconds) { case _: ScheduleEvent => true; case _ => false }
    resched.asInstanceOf[ScheduleEvent].tick shouldBe 6L
    actor.underlyingActor.testStatus shouldBe "Done"
    actor.underlyingActor.testReplies shouldBe 1
  }

  "a conservative-mode actor" should "keep using the unchanged state.status-only staleness guard" in {
    val (peerProbe, peerName) = newPeer()
    val tmProbe = TestProbe()
    val actor = newActor(peerName, TimeManagerTypeEnum.DISCRETE_EVENT)

    actor ! spontaneous(5L, tmProbe.ref)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    tmProbe.expectMsgClass(3.seconds, classOf[FinishEvent]) // the disengage: onFinishSpontaneous(None)
    actor.underlyingActor.testStatus shouldBe "Waiting"

    // No rollback machinery exists in conservative mode; simulate a stale reply the direct way --
    // state.status no longer "Waiting" -- and confirm it's dropped exactly as before this change.
    actor.underlyingActor.testSetState(WaitState(startTick = 0L, status = "Idle", replies = 0))
    actor ! interaction(5L, senderId = peerName, data = ReplyData(), eventType = "reply")
    tmProbe.expectNoMessage(1.second)
    actor.underlyingActor.testReplies shouldBe 0
    actor.underlyingActor.testStatus shouldBe "Idle"

    // A genuine (status == "Waiting") reply is still accepted and reschedules as before.
    actor.underlyingActor.testSetState(WaitState(startTick = 0L, status = "Waiting", replies = 0))
    actor ! interaction(5L, senderId = peerName, data = ReplyData(), eventType = "reply")
    val resched = tmProbe.fishForMessage(3.seconds) { case _: ScheduleEvent => true; case _ => false }
    resched.asInstanceOf[ScheduleEvent].tick shouldBe 5L
    actor.underlyingActor.testReplies shouldBe 1
  }

  it should "mark its resolved dispatch as pending until the awaited reply has been consumed" in {
    val (peerProbe, peerName) = newPeer()
    val tmProbe = TestProbe()
    val actor = newActor(peerName, TimeManagerTypeEnum.DISCRETE_EVENT)

    actor ! spontaneous(5L, tmProbe.ref)
    peerProbe.expectMsgType[ActorInteractionEvent]
    val waiting = tmProbe.expectMsgType[FinishEvent]
    waiting.scheduleTick shouldBe None
    waiting.awaitingReply shouldBe true
    actor.underlyingActor.testHasReplyObligation("req") shouldBe true

    actor ! interaction(5L, peerName, ReplyData(), "reply")
    tmProbe.expectMsgType[ScheduleEvent]
    actor.underlyingActor.testReplies shouldBe 1
    actor.underlyingActor.testHasReplyObligation("req") shouldBe false
  }

  "SimulationBaseActor.pruneInvalidatedAwaitedReplies" should
    "drop an outstandingAwaitedReplies entry once the rollback it's caught up in retracts the send it was registered for" in {
      val (peerProbe, peerName) = newPeer()
      val tmProbe = TestProbe()
      val actor = newActor(peerName, TimeManagerTypeEnum.TIME_WARP)

      // Live dispatch at tick=5 registers "req" with sendSeq=1 (this actor's first Time-Warp send).
      actor ! spontaneous(5L, tmProbe.ref)
      peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
      actor.underlyingActor.testIsAwaitingReply("req", "Reply", 1L) shouldBe true

      // A causally-earlier interaction rolls this actor back to before tick=5, undoing that
      // dispatch -- and with it, the send registerAwaitedReply captured as "req"'s identity.
      actor ! interaction(3L, senderId = 999L, data = TriggerData(), eventType = "trigger")
      tmProbe.fishForMessage(3.seconds) { case _: core.entity.event.control.execution.ReplaceScheduleEvent => true; case _ => false }

      // The entry is gone, not merely superseded -- querying its own original identity now
      // returns false, proving pruneInvalidatedAwaitedReplies actually removed it rather than
      // just leaving resyncScheduleAfterRollback's forced re-dispatch to paper over a stale one.
      actor.underlyingActor.testIsAwaitingReply("req", "Reply", 1L) shouldBe false
    }
}
