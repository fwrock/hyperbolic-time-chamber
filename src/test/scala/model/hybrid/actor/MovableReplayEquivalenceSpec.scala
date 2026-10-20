package org.interscity.htc
package model.hybrid.actor

import core.actor.manager.loadbalance.migration.MigrationSnapshot
import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
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

/** Replay equivalence (step 1 of the replay-correctness plan): after a straggler-driven rollback to
  * just before event `k+1`, the restored actor must be indistinguishable from a control actor that
  * was driven live through exactly the first `k` events and never rolled back.
  *
  * "Indistinguishable" means the whole `MigrationSnapshot` (the serialized `state` plus every
  * actor-local field the actor captures), ignoring only `entityId` (control and rolled-back are
  * different actors), `currentTick` and `lamportClock`. The clocks
  * legitimately differ: the rolled-back actor has additionally processed the straggler and the
  * undone events moved its clocks.
  *
  * Complements `MovableRouteRollbackReplaySpec`, which checks only the route fields at a single
  * rollback point with the default checkpoint interval. This spec sweeps every rollback point and
  * runs under three checkpoint intervals, so restoring from a non-initial checkpoint and replaying
  * across a checkpoint boundary are both exercised:
  *   - interval 1: a checkpoint after every event, no replay needed;
  *   - interval 2: checkpoints at events 2 and 4, replay of at most one event;
  *   - interval 50: only the initial checkpoint, every surviving event is replayed.
  */
private case class EquivState(
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

private case class EquivPingData()

abstract class MovableReplayEquivalenceBase(checkpointInterval: Int)
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      s"MovableReplayEquivalenceSpec-$checkpointInterval",
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

  /** Same minimal `Movable` as `MovableRouteRollbackReplaySpec`: each enter-link ack immediately
    * triggers `leavingLink()`, driving the real route-queue logic with no real `Link`/`CityMapUtil`.
    */
  private class TestMovable(properties: Properties, linkPeerId: Long) extends Movable[EquivState](properties) {

    override protected def resolveLink(linkId: Long): Option[(Long, String)] =
      Some((linkPeerId, linkPeerId.toString))

    override protected def sendMessageTo(
      entityId: Long,
      shardId: String,
      data: AnyRef,
      eventType: String,
      actorType: CreationTypeEnum
    ): Unit = super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)

    override protected def actHandleReceiveEnterLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit = {
      state.movableStatus = MovableStatusEnum.Ready
      leavingLink()
    }

    def testSetState(s: EquivState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testSnapshot: MigrationSnapshot = buildMigrationSnapshot()
  }

  private var nextId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextId += 1
    val id = nextId
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private val route = Seq((10L, 100L), (20L, 200L), (30L, 300L))

  /** One fresh actor plus the probes that stand in for its time manager and link. */
  private final case class Rig(actor: TestActorRef[TestMovable], tm: TestProbe, linkId: Long)

  private def newRig(): Rig = {
    val tm = TestProbe()
    val (_, linkId) = newPeer()
    nextId += 1
    val ownId = nextId
    val properties = Properties(
      entityId = ownId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    val ref = TestActorRef(new TestMovable(properties, linkId), ownId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor: PersistentActor recovery is async.
    Thread.sleep(1000)
    val state = EquivState()
    state.movableBestRoute = Some(mutable.Queue.from(route))
    state.movableStatus = MovableStatusEnum.Ready
    ref.underlyingActor.testSetState(state)
    ref.underlyingActor.testInitializeRollback()
    Rig(ref, tm, linkId)
  }

  private def ack(tick: Tick, linkId: Long, seq: Long): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = linkId,
      shardRefId = linkId.toString,
      actorPathRef = s"/user/$linkId",
      actorClassType = "test.Link",
      eventType = EventTypeEnum.ReceiveEnterLinkInfo.toString,
      data = LinkInfoData(),
      resourceId = "res-1",
      seq = seq
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

  /** The scripted live history, one entry per tick 1..5: enter, ack, enter, ack, enter. */
  private def liveEvent(rig: Rig, index: Int): Any = index match {
    case 1 => SpontaneousEvent(tick = 1L, actorRef = rig.tm.ref)
    case 2 => ack(tick = 2L, linkId = rig.linkId, seq = 1L)
    case 3 => SpontaneousEvent(tick = 3L, actorRef = rig.tm.ref)
    case 4 => ack(tick = 4L, linkId = rig.linkId, seq = 2L)
    case 5 => SpontaneousEvent(tick = 5L, actorRef = rig.tm.ref)
  }

  private val historyLength = 5

  private def driveLive(rig: Rig, upTo: Int): Unit =
    (1 to upTo).foreach(i => rig.actor ! liveEvent(rig, i))

  /** Compares everything the actor would carry across a migration, minus its entity id (each rig is
    * a different actor) and the two clocks.
    */
  private def fingerprint(rig: Rig): MigrationSnapshot =
    rig.actor.underlyingActor.testSnapshot.copy(entityId = "", currentTick = 0L, lamportClock = 0L)

  // A straggler at tick k+1 is only a straggler while k+1 < currentTick (5), so k ranges 0..3.
  (0 to 3).foreach {
    k =>
      s"[checkpoint interval $checkpointInterval] a rollback to just before event ${k + 1}" should
        s"restore exactly the snapshot of a control actor driven live through the first $k events" in {
          val control = newRig()
          driveLive(control, upTo = k)
          val expected = fingerprint(control)

          val rolledBack = newRig()
          driveLive(rolledBack, upTo = historyLength)
          rolledBack.actor ! straggler(tick = k + 1L)

          fingerprint(rolledBack) shouldBe expected
        }
  }
}

class MovableReplayEquivalenceInterval1Spec extends MovableReplayEquivalenceBase(checkpointInterval = 1)

class MovableReplayEquivalenceInterval2Spec extends MovableReplayEquivalenceBase(checkpointInterval = 2)

class MovableReplayEquivalenceInterval50Spec extends MovableReplayEquivalenceBase(checkpointInterval = 50)
