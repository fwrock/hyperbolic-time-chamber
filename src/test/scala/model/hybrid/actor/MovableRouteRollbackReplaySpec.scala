package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, FinishEvent, SpontaneousEvent }
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.MovableState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, EventTypeEnum, MovableStatusEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for the `Movable` route-queue reconstruction question raised while
  * re-auditing `docs/KNOWN_GAPS.md`'s "Time Warp: `Movable` Route State... Not Correctly
  * Reconstructed After a Rollback" entry: does `state.movableBestRoute`/`state.movableCurrentPath`
  * come back byte-for-byte correct after a straggler-driven rollback discards some already-live
  * traversal, or does it silently diverge from what a live run to the equivalent point would have?
  *
  * This is empirical, not a confirmation of either the original (now-refuted) hypothesis or its
  * refutation: the assertions compare the *rolled-back* actor's restored route state against a
  * *control* actor driven live, with no rollback, to the exact same logical point, and fail loudly
  * on any mismatch rather than assuming the answer.
  *
  * Exercises the real `Movable.enterLink`/`leavingLink`/`getNextPath` mutations (dequeueing
  * `movableBestRoute`, swapping `movableCurrentPath`) via the actual mailbox path
  * (`handleSpontaneous`/`handleInteractWith` -> `rollbackHandler.recordProcessedEvent` ->
  * `rollbackHandler.rollbackTo` -> `replayLoggedEvent`), the same harness style as
  * `SimulationBaseActorTimeWarpReplaySpec`/`SimulationBaseActorStragglerTriggerSpec`: real
  * `TestActorRef` mailbox traffic, a `/user/{id}` forwarder standing in for a `TestProbe` peer.
  *
  * Deliberately tests base `Movable` directly (a minimal concrete subclass), not `Car`: `Car`
  * hardcodes `CreationTypeEnum.LoadBalancedDistributed` on its link sends via the inherited
  * `enterLink`/`leavingLink`, which resolves through real `ClusterSharding` — unavailable under
  * this harness's `pekko.actor.provider = local`. The minimal subclass here overrides
  * `sendMessageTo` to force every send through `PoolDistributed` addressing instead (a `/user/{id}`
  * lookup, exactly what `SimulationBaseActorTimeWarpReplaySpec`'s peer forwarder already relies
  * on), and overrides `resolveLink` to skip the `CityMapUtil` road-graph dependency entirely — both
  * are pure test-harness plumbing, not changes to the route-queue logic under test.
  */
private case class UnrelatedPingData()

private case class RouteState(
  override val startTick: Tick = 0L,
  override val origin: Long = 1L,
  override val destination: Long = 300L,
  override val actorType: ActorTypeEnum = ActorTypeEnum.Car,
  override val size: Double = 1.0
) extends MovableState(
      startTick = startTick,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    )

class MovableRouteRollbackReplaySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "MovableRouteRollbackReplaySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Minimal concrete `Movable`: every link "enter" ack immediately triggers `leavingLink()`
    * (a zero-duration traversal), so each round trip through a single stand-in "link" peer drives
    * one full `enterLink -> leavingLink -> getNextPath` cycle of the real base-class logic under
    * test, without needing a real `Link` actor or `CityMapUtil` road graph.
    */
  private class TestMovable(properties: Properties, linkPeerId: Long) extends Movable[RouteState](properties) {

    override protected def resolveLink(linkId: Long): Option[(Long, String)] =
      Some((linkPeerId, linkPeerId.toString))

    // enterLink()/leavingLink() hardcode CreationTypeEnum.LoadBalancedDistributed, which resolves
    // via real ClusterSharding -- unavailable under this harness. Force every send through
    // PoolDistributed's /user/{id} addressing instead; this changes only the wire mechanism, not
    // any of the route-queue mutation logic this spec actually exercises.
    override protected def sendMessageTo(
      entityId: Long,
      shardId: String,
      data: AnyRef,
      eventType: String,
      actorType: CreationTypeEnum
    ): Unit = super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)

    override protected def actHandleReceiveEnterLinkInfo(
      event: ActorInteractionEvent,
      data: LinkInfoData
    ): Unit = {
      state.movableStatus = MovableStatusEnum.Ready
      leavingLink()
    }

    def testSetState(s: RouteState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testRoute: Option[mutable.Queue[(Long, Long)]] = if (state == null) None else state.movableBestRoute
    def testCurrentPath: Option[(Long, Long)] = if (state == null) None else state.movableCurrentPath
    def testStatus: MovableStatusEnum = if (state == null) null else state.movableStatus
  }

  private var nextPeerId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextPeerId += 1
    val id = nextPeerId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private def newActor(timeManagerProbe: TestProbe, linkPeerId: Long, route: Seq[(Long, Long)]): TestActorRef[TestMovable] = {
    nextPeerId += 1
    val ownEntityId = nextPeerId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    val ref = TestActorRef(new TestMovable(properties, linkPeerId), ownEntityId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor for why this one-time wait is needed:
    // BaseActor is a PersistentActor, recovery is asynchronous regardless of TestActorRef's
    // CallingThreadDispatcher, and a command sent before recovery completes is stashed until it does.
    Thread.sleep(1000)
    val state = RouteState(startTick = 0L, origin = 1L, destination = 300L)
    state.movableBestRoute = Some(mutable.Queue.from(route))
    state.movableStatus = MovableStatusEnum.Ready
    ref.underlyingActor.testSetState(state)
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def enterLinkAck(tick: Tick, linkPeerId: Long, seq: Long): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = linkPeerId,
      shardRefId = linkPeerId.toString,
      actorPathRef = s"/user/$linkPeerId",
      actorClassType = "test.Link",
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      data = LinkInfoData(),
      resourceId = "res-1",
      seq = seq
    )

  private def straggler(tick: Tick, senderId: Long, seq: Long): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Straggler",
      actorPathRef = s"/user/sender-$senderId",
      actorClassType = "test.Straggler",
      eventType = "unrelated-ping",
      // Deliberately not a LinkInfoData: Movable.actInteractWith only special-cases that payload
      // type (routing it to handleLinkInfo, which calls EventTypeEnum.valueOf on the eventType
      // string and throws for anything not a real enum case). Using an unrelated payload here
      // exercises the actual "not handled" fallback branch cleanly -- this straggler's only job is
      // to force the causally-earlier-tick rollback trigger, not to be a real link ack.
      data = UnrelatedPingData(),
      resourceId = "res-1",
      seq = seq
    )

  private val threeSegmentRoute = Seq((10L, 100L), (20L, 200L), (30L, 300L))

  "a straggler-driven rollback of a Movable mid third-segment entry" should
    "restore movableBestRoute/movableCurrentPath to exactly what a live run to the same point produced" in {
      val timeManagerProbe = TestProbe()
      val (linkProbe, linkPeerId) = newPeer()
      val actor = newActor(timeManagerProbe, linkPeerId, threeSegmentRoute)

      // t=1: enter segment 1 (10,100) -- dequeues it off movableBestRoute.
      actor ! SpontaneousEvent(tick = 1L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe None
      linkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      // ack at t=2: leaves segment 1, dequeues nothing further -- bestRoute now (20,200),(30,300).
      actor ! enterLinkAck(tick = 2L, linkPeerId = linkPeerId, seq = 1L)
      linkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds)

      // t=3: enter segment 2 (20,200) -- dequeues it; bestRoute now just (30,300).
      actor ! SpontaneousEvent(tick = 3L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds).scheduleTick shouldBe None
      linkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      // This is the point the control run below stops at: on link (20,200), one segment already
      // fully traversed, one dequeued-and-current, one still queued.
      val expectedRoute = actor.underlyingActor.testRoute.map(_.toSeq)
      val expectedPath = actor.underlyingActor.testCurrentPath
      val expectedStatus = actor.underlyingActor.testStatus
      expectedRoute shouldBe Some(Seq(30L -> 300L))
      expectedPath shouldBe Some(20L -> 200L)
      expectedStatus shouldBe MovableStatusEnum.Waiting

      // ack at t=4: leaves segment 2, dequeues nothing further -- bestRoute now just (30,300),
      // currentPath cleared. Then t=5 immediately starts entering segment 3, dequeueing the last
      // pair off bestRoute (now empty) and setting currentPath=(30,300).
      actor ! enterLinkAck(tick = 4L, linkPeerId = linkPeerId, seq = 2L)
      linkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds)

      actor ! SpontaneousEvent(tick = 5L, actorRef = timeManagerProbe.ref)
      timeManagerProbe.expectMsgType[FinishEvent](3.seconds)
      linkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

      actor.underlyingActor.testRoute.map(_.toSeq) shouldBe Some(Seq.empty)
      actor.underlyingActor.testCurrentPath shouldBe Some(30L -> 300L)

      // A genuinely causally-earlier interaction (tick=4, strictly less than currentTick=5) forces
      // a real straggler rollback: RollbackHistoryHandler.rollbackTo(4) undoes every logged event
      // at tick >= 4 -- both the t=4 ack and the t=5 entry -- restoring the checkpoint-plus-replay
      // state as of right after the t=3 dispatch above.
      actor ! straggler(tick = 4L, senderId = 999L, seq = 1L)

      // The undone sends get anti-messaged; drain them before asserting on final state.
      linkProbe.receiveWhile(3.seconds) {
        case m: ActorInteractionEvent => m
      }

      actor.underlyingActor.testRoute.map(_.toSeq) shouldBe expectedRoute
      actor.underlyingActor.testCurrentPath shouldBe expectedPath
      actor.underlyingActor.testStatus shouldBe expectedStatus
    }

  it should "match a control Movable driven live (no rollback) to the same logical point" in {
    val liveTimeManagerProbe = TestProbe()
    val (liveLinkProbe, liveLinkPeerId) = newPeer()
    val liveActor = newActor(liveTimeManagerProbe, liveLinkPeerId, threeSegmentRoute)

    liveActor ! SpontaneousEvent(tick = 1L, actorRef = liveTimeManagerProbe.ref)
    liveTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    liveLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    liveActor ! enterLinkAck(tick = 2L, linkPeerId = liveLinkPeerId, seq = 1L)
    liveLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    liveTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)

    liveActor ! SpontaneousEvent(tick = 3L, actorRef = liveTimeManagerProbe.ref)
    liveTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    liveLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    val rollbackTimeManagerProbe = TestProbe()
    val (rollbackLinkProbe, rollbackLinkPeerId) = newPeer()
    val rollbackActor = newActor(rollbackTimeManagerProbe, rollbackLinkPeerId, threeSegmentRoute)

    rollbackActor ! SpontaneousEvent(tick = 1L, actorRef = rollbackTimeManagerProbe.ref)
    rollbackTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    rollbackLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    rollbackActor ! enterLinkAck(tick = 2L, linkPeerId = rollbackLinkPeerId, seq = 1L)
    rollbackLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    rollbackTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)

    rollbackActor ! SpontaneousEvent(tick = 3L, actorRef = rollbackTimeManagerProbe.ref)
    rollbackTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    rollbackLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    rollbackActor ! enterLinkAck(tick = 4L, linkPeerId = rollbackLinkPeerId, seq = 2L)
    rollbackLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    rollbackTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)

    rollbackActor ! SpontaneousEvent(tick = 5L, actorRef = rollbackTimeManagerProbe.ref)
    rollbackTimeManagerProbe.expectMsgType[FinishEvent](3.seconds)
    rollbackLinkProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])

    rollbackActor ! straggler(tick = 4L, senderId = 999L, seq = 1L)
    rollbackLinkProbe.receiveWhile(3.seconds) {
      case m: ActorInteractionEvent => m
    }

    rollbackActor.underlyingActor.testRoute.map(_.toSeq) shouldBe liveActor.underlyingActor.testRoute.map(_.toSeq)
    rollbackActor.underlyingActor.testCurrentPath shouldBe liveActor.underlyingActor.testCurrentPath
    rollbackActor.underlyingActor.testStatus shouldBe liveActor.underlyingActor.testStatus
  }

  it should "be quiet no-op straggler handling when the actor never touched the route beforehand" in {
    val timeManagerProbe = TestProbe()
    val (linkProbe, linkPeerId) = newPeer()
    val actor = newActor(timeManagerProbe, linkPeerId, threeSegmentRoute)

    noException should be thrownBy {
      actor ! straggler(tick = -1L, senderId = 999L, seq = 1L)
      Thread.sleep(200)
    }
  }
}
