package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, EventTypeEnum, MovableStatusEnum }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

/** Same replay-equivalence property as [[MovableReplayEquivalenceBase]], on a real `Car`: after a
  * straggler-driven rollback to just before event `k+1`, the restored `MigrationSnapshot` must equal
  * that of a control `Car` driven live through the first `k` events.
  *
  * What a `Car` adds over the bare `Movable`: its own actor-local link bookkeeping
  * (`currentLinkId`, `currentLinkLength`, `linkEntryTick`, `mesoExitTick`, ...), captured by
  * `captureCarMigrationFields`, and the `CarLinkHandler` logic that sets it, driven through the real
  * handlers.
  *
  * Finding (Gap P, "replay drops awaited replies"): when the restore point is a checkpoint taken
  * before a link reply and that reply survives the rollback, replaying it is discarded as stale. The
  * Car's `AwaitedReply` registry is rollback-immune, so the reply's entry was already consumed when
  * the reply was first handled live, and `CarLinkHandler.handleEnterLink` sees nothing awaited. The
  * restored Car stays `Waiting` with its link bookkeeping empty, for a reply that will never come
  * again. Fixed by making the awaited-reply registry part of the migration snapshot, so a restore
  * brings back the wait the reply needs, and by replay reusing the original send seq.
  *
  * Harness limits, same as `MovableRouteRollbackReplaySpec`: `Car` addresses links through
  * `LoadBalancedDistributed` sharding, which does not exist under `pekko.actor.provider = local`, so
  * `sendMessageTo` is forced to `PoolDistributed` addressing and `resolveLink` skips `CityMapUtil`.
  * Not covered: leaving a link. `requestSignalState` reads `CityMapUtil.nodesById`, a process-wide
  * singleton that loads a real city-map file and has no test hook, so the history stops before the
  * Car's exit tick. `signalWaitUntilTick`/`signalWaitNeedsReverify` and the leave-link handler are
  * therefore not exercised here.
  */
abstract class CarReplayEquivalenceBase(checkpointInterval: Int)
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      s"CarReplayEquivalenceSpec-$checkpointInterval",
      ConfigFactory
        .parseString(
          "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off\n" +
            s"htc.time-warp.checkpoint-interval = $checkpointInterval"
        )
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestCar(properties: Properties, linkPeerId: Long) extends Car(properties) {
    override protected def registerOnTimeManager(tick: Tick): Unit = ()

    override protected def resolveLink(linkId: Long): Option[(Long, String)] =
      Some((linkPeerId, linkPeerId.toString))

    override protected def sendMessageTo(
      entityId: Long,
      shardId: String,
      data: AnyRef,
      eventType: String,
      actorType: CreationTypeEnum
    ): Unit = super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)

    def testSetState(s: CarState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testSnapshot: MigrationSnapshot = buildMigrationSnapshot()
    def testStatus: MovableStatusEnum = state.status
    def testLinkFields: (Option[Long], Double, Option[Tick], Option[Tick]) =
      (currentLinkId, currentLinkLength, linkEntryTick, mesoExitTick)
  }

  private var nextId = 0L

  private def newPeer(): Long = {
    val probe = TestProbe()
    nextId += 1
    val id = nextId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    id
  }

  private val route = Seq((10L, 100L), (20L, 200L), (30L, 300L))

  private final case class Rig(actor: TestActorRef[TestCar], tm: TestProbe, linkId: Long)

  private def newRig(): Rig = {
    val tm = TestProbe()
    val linkId = newPeer()
    nextId += 1
    val ownId = nextId
    val properties = Properties(
      entityId = ownId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP,
      reporters = mutable.Map.empty // report() dereferences it; null would swallow the whole handler
    )
    val ref = TestActorRef(new TestCar(properties, linkId), ownId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor: PersistentActor recovery is async.
    Thread.sleep(1000)
    val state = CarState(startTick = 0L, origin = 1L, destination = 300L, actorType = ActorTypeEnum.Car, size = 4.5)
    state.bestRoute = Some(mutable.Queue.from(route))
    state.status = MovableStatusEnum.Ready
    ref.underlyingActor.testSetState(state)
    ref.underlyingActor.testInitializeRollback()
    Rig(ref, tm, linkId)
  }

  private def linkInfo(tick: Tick, linkId: Long, requestSeq: Long, eventType: EventTypeEnum): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = linkId,
      shardRefId = linkId.toString,
      actorPathRef = s"/user/$linkId",
      actorClassType = "test.Link",
      eventType = eventType.toString,
      data = LinkInfoData(
        linkLength = 100.0,
        linkCapacity = 20.0,
        linkNumberOfCars = 1,
        linkFreeSpeed = 50.0,
        linkLanes = 1,
        requestSeq = requestSeq
      ),
      resourceId = "res-1",
      seq = requestSeq
    )

  private def straggler(tick: Tick): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = 999L,
      shardRefId = "test.Straggler",
      actorPathRef = "/user/sender-999",
      actorClassType = "test.Straggler",
      eventType = "unrelated-ping",
      data = EquivPingData(),
      resourceId = "res-1",
      seq = tick
    )

  /** Scripted live history, four events. The link reply (event 2) is the one that matters: it sets
    * the Car's actor-local link bookkeeping, so rolling back to before it must reset those fields,
    * and rolling back to after it must keep them. The two later spontaneous events fire before the
    * Car's exit tick, so they only reschedule it and leave every field untouched.
    *
    *   1. t=1 Car enters segment 1 (send 1)
    *   2. t=2 link replies (`requestSeq` = the send it answers): Car goes Moving, sets
    *      `currentLinkId`, `currentLinkLength`, `linkEntryTick` and `mesoExitTick`
    *   3. t=3 spontaneous before the exit tick
    *   4. t=4 spontaneous before the exit tick
    */
  private val ticks = Vector(1L, 2L, 3L, 4L)

  private def liveEvent(rig: Rig, index: Int): Any = index match {
    case 1 => SpontaneousEvent(tick = ticks(0), actorRef = rig.tm.ref)
    case 2 => linkInfo(ticks(1), rig.linkId, requestSeq = 1L, EventTypeEnum.ReceiveEnterLinkInfo)
    case 3 => SpontaneousEvent(tick = ticks(2), actorRef = rig.tm.ref)
    case 4 => SpontaneousEvent(tick = ticks(3), actorRef = rig.tm.ref)
  }

  private val historyLength = 4

  private def driveLive(rig: Rig, upTo: Int): Unit =
    (1 to upTo).foreach(i => rig.actor ! liveEvent(rig, i))

  /** Every rig has its own link peer, so its id is replaced by a marker instead of compared. */

  /** The registry's keys embed the per-rig link id and its values a send seq, a monotonic identity
    * that is never rolled back, so a reprocessed send gets a fresh one the control never allocated.
    * What must match is which waits are registered, of what kind, and from which tick.
    */
  private def normalizedAwaits(awaited: Map[String, String]): Map[String, String] =
    awaited.map { case (key, v) => key.replaceAll(":\\d+$", ":link") -> v.substring(0, v.lastIndexOf('|')) }

  private def fingerprint(rig: Rig): MigrationSnapshot = {
    val snapshot = rig.actor.underlyingActor.testSnapshot
    // Separate fixture LPs have different root identities; preserve logical time while normalizing that identity.
    val snap = snapshot.copy(pendingSelf = snapshot.pendingSelf.map(_.copy(key = "fixture-self")))
    val normalizedState = core.util.JsonUtil.fromJson[CarState](snap.stateJson)
    normalizedState.journey.sumoDepartLane = normalizedState.journey.sumoDepartLane
      .map(_.replace(s"${rig.linkId}_", "link_"))
    normalizedState.journey.sumoArrivalLane = normalizedState.journey.sumoArrivalLane
      .map(_.replace(s"${rig.linkId}_", "link_"))
    snap.copy(
      stateJson = core.util.JsonUtil.toJson(normalizedState),
      entityId = "",
      currentTick = 0L,
      lamportClock = 0L,
      awaitedReplies = normalizedAwaits(snap.awaitedReplies),
      vehicleCurrentLinkId = if (snap.vehicleCurrentLinkId == rig.linkId) -1L else snap.vehicleCurrentLinkId
    )
  }

  "the scripted Car history" should "reach a non-trivial state, so the equivalence checks below mean something" in {
    val rig = newRig()
    driveLive(rig, upTo = 2)
    val (linkId, linkLength, entryTick, exitTick) = rig.actor.underlyingActor.testLinkFields

    linkId shouldBe defined
    linkLength shouldBe 100.0
    entryTick shouldBe Some(ticks(1))
    // The later spontaneous events must fall before the exit tick, or they would take the signal
    // path (which needs the real city map) instead of just rescheduling.
    exitTick.get should be > ticks(3)
  }

  /** Restoring to just before event 3 needs the link reply (event 2) replayed unless a checkpoint
    * already sits after it, which is the case for intervals 1 and 2.
    */
  private val replaysTheLinkReply = checkpointInterval > 2

  private def rollBackTo(k: Int): (Rig, Rig) = {
    // The rollback undoes events k+1..4 and processes the interactions among them again after the
    // straggler (the link reply); undone spontaneous events are redispatched by the time manager,
    // which does not exist here. The control applies the same replies live.
    val control = newRig()
    driveLive(control, upTo = k)
    ((k + 1) to historyLength).map(liveEvent(control, _)).collect { case e: ActorInteractionEvent => e }.foreach(control.actor ! _)

    val rolledBack = newRig()
    driveLive(rolledBack, upTo = historyLength)
    rolledBack.actor ! straggler(tick = ticks(k) - 1L)
    (control, rolledBack)
  }

  // The positive input follows the retained tick; the next event belongs to the undone suffix.
  (0 to 2).foreach {
    k =>
      s"[checkpoint interval $checkpointInterval] a Car rollback to just before event ${k + 1}" should
        s"restore exactly the snapshot of a control Car driven live through the first $k events" in {
          val (control, rolledBack) = rollBackTo(k)
          fingerprint(rolledBack) shouldBe fingerprint(control)
        }
  }

  if (replaysTheLinkReply) {
    s"[checkpoint interval $checkpointInterval] a Car whose link reply is replayed after a rollback" should
      "end up Moving with the same link bookkeeping as a live control (Gap P regression)" in {
        val (control, rolledBack) = rollBackTo(2)

        control.actor.underlyingActor.testStatus shouldBe MovableStatusEnum.Moving
        control.actor.underlyingActor.testLinkFields._1 shouldBe Some(control.linkId)

        rolledBack.actor.underlyingActor.testStatus shouldBe MovableStatusEnum.Moving
        rolledBack.actor.underlyingActor.testLinkFields._1 shouldBe Some(rolledBack.linkId)
      }
  }

  "a Car that discarded a duplicate link reply as stale live" should
    "discard it again when a rollback replays it, instead of re-applying it" in {
      def history(rig: Rig, upTo: Int): Unit = {
        val events = Seq[Any](
          SpontaneousEvent(tick = 1L, actorRef = rig.tm.ref),
          linkInfo(2L, rig.linkId, requestSeq = 1L, EventTypeEnum.ReceiveEnterLinkInfo),
          // same requestSeq: the first reply already consumed the awaited entry, so live it is stale
          linkInfo(3L, rig.linkId, requestSeq = 1L, EventTypeEnum.ReceiveEnterLinkInfo),
          SpontaneousEvent(tick = 4L, actorRef = rig.tm.ref),
          SpontaneousEvent(tick = 5L, actorRef = rig.tm.ref)
        )
        events.take(upTo).foreach(rig.actor ! _)
      }

      val control = newRig()
      history(control, upTo = 3)

      val rolledBack = newRig()
      history(rolledBack, upTo = 5)
      rolledBack.actor ! straggler(tick = 4L)

      control.actor.underlyingActor.testLinkFields._3 shouldBe Some(2L)
      rolledBack.actor.underlyingActor.testLinkFields._3 shouldBe Some(2L)
      fingerprint(rolledBack) shouldBe fingerprint(control)
    }
}

class CarReplayEquivalenceInterval1Spec extends CarReplayEquivalenceBase(checkpointInterval = 1)

class CarReplayEquivalenceInterval2Spec extends CarReplayEquivalenceBase(checkpointInterval = 2)

class CarReplayEquivalenceInterval50Spec extends CarReplayEquivalenceBase(checkpointInterval = 50)
