package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt

/** Regression coverage for `docs/TIME_WARP_DESIGN.md` §10's straggler trigger, finally activated:
  * `SimulationBaseActor.handleInteractWith` now rolls back on a genuine causally-earlier
  * interaction and on receiving a real anti-message, cascading further anti-messages for whatever
  * a rollback undoes. Same harness style as `SimulationBaseActorTimeWarpReplaySpec` (real mailbox
  * traffic, a `/user/{name}` forwarder standing in for a `TestProbe` peer, a one-time wait for
  * `PersistentActor` recovery).
  */
/** Top-level (not nested in the Spec class) so Jackson can deserialize it via a plain no-arg-ish
  * constructor during `restoreSnapshotFn` — see `SimulationBaseActorTimeWarpReplaySpec`'s
  * `CounterState` for the full explanation of why a nested inner class breaks this.
  */
private case class RelayState(startTick: Tick = 0L, counter: Int = 0) extends BaseState(startTick = startTick)

private case class RelayData(value: Int)

class SimulationBaseActorStragglerTriggerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorStragglerTriggerSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  /** Every incoming interaction bumps the counter and relays it downstream to `peerEntityId` —
    * enough surface to prove both a rollback restores `counter` and that the resulting cascade
    * produces a real anti-message for the relay a rolled-back interaction made.
    */
  private class RelayActor(properties: Properties, peerEntityId: Long)
      extends SimulationBaseActor[RelayState](properties) {

    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      state = state.copy(counter = state.counter + 1)
      sendMessageTo(
        entityId = peerEntityId,
        shardId = peerEntityId.toString,
        data = RelayData(state.counter),
        eventType = "relay",
        actorType = CreationTypeEnum.PoolDistributed
      )
    }

    def testSetState(s: RelayState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testInitializeRollback(): Unit = rollbackHandler.initialize(startTick)
    def testCounter: Int = state.counter
    // LoadBalancedDistributed actors (the default actorType) have preStart() overwrite entityId
    // with self.path.name -- the Properties.entityId passed in is not what ends up on the wire.
    def testEntityId: Long = getEntityId.toLong
  }

  private var nextPeerId = 0L

  private def newPeer(): (TestProbe, Long) = {
    val probe = TestProbe()
    nextPeerId += 1
    val id = nextPeerId
    // The PoolDistributed router resolves entityId -> actor path via IdUtil.format(entityId), a
    // plain toString -- the forwarding actor must be registered under that exact numeric name for
    // sendMessageTo(entityId = id, ...) to find it, rather than Pekko's auto-generated probe name.
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (probe, id)
  }

  private def newActor(peerEntityId: Long): TestActorRef[RelayActor] = {
    nextPeerId += 1
    val ownEntityId = nextPeerId
    val properties = Properties(
      entityId = ownEntityId.toString,
      resourceId = "res-1",
      timeManagers = mutable.Map.empty,
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP
    )
    // LoadBalancedDistributed actors (the default actorType) have preStart() overwrite entityId
    // with self.path.name -- so the actor must be given an explicit numeric TestActorRef name
    // matching production's real convention (Pekko-assigned entityId is always a Long's string
    // form), not Pekko's default auto-generated ("$a"-style) name, which onFinishSpontaneous's
    // Identify(id = getEntityId.toLong, ...) can't parse.
    val ref = TestActorRef(new RelayActor(properties, peerEntityId), ownEntityId.toString)
    // See SimulationBaseActorTimeWarpReplaySpec's newActor for why: BaseActor is a
    // PersistentActor, recovery is asynchronous regardless of CallingThreadDispatcher, and a
    // command sent before it completes is stashed until it does.
    Thread.sleep(1000)
    ref.underlyingActor.testSetState(RelayState(startTick = 0L, counter = 0))
    ref.underlyingActor.testInitializeRollback()
    ref
  }

  private def interaction(tick: Tick, senderId: Long, seq: Long, isAntiMessage: Boolean = false): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = senderId,
      shardRefId = "test.Sender",
      actorPathRef = s"/user/sender-$senderId",
      actorClassType = "test.Sender",
      eventType = "ping",
      data = RelayData(0),
      resourceId = "res-1",
      seq = seq,
      isAntiMessage = isAntiMessage
    )

  "a genuinely causally-earlier interaction" should "roll the actor back, send a real anti-message for the undone relay, then process itself in order" in {
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(peerName)

    // tick=5 arrives first and is processed live: counter -> 1, a real relay sent downstream.
    actor ! interaction(tick = 5L, senderId = 101L, seq = 1L)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent]).data shouldBe RelayData(1)
    actor.underlyingActor.testCounter shouldBe 1

    // A straggler for tick=3 arrives after -- causally earlier than what this actor already
    // processed. It must roll back (undoing tick=5's relay), anti-message it, THEN process
    // itself normally.
    actor ! interaction(tick = 3L, senderId = 102L, seq = 1L)

    val antiMessage = peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    antiMessage.isAntiMessage shouldBe true
    antiMessage.tick shouldBe 5L
    antiMessage.seq shouldBe 1L
    antiMessage.actorRefId shouldBe actor.underlyingActor.testEntityId // the anti-message's sender is THIS actor -- it's retracting its own prior relay

    val replayedRelay = peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    replayedRelay.isAntiMessage shouldBe false
    replayedRelay.data shouldBe RelayData(1) // tick=3's own processing, counter back to 1 from the restored floor(0)

    actor.underlyingActor.testCounter shouldBe 1
  }

  "receiving a real anti-message" should "roll back to before the original interaction and cascade a further anti-message for its own relay" in {
    val (peerProbe, peerName) = newPeer()
    val actor = newActor(peerName)

    actor ! interaction(tick = 5L, senderId = 101L, seq = 7L)
    peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent]).data shouldBe RelayData(1)
    actor.underlyingActor.testCounter shouldBe 1

    // sender-a itself rolled back and is retracting the tick=5/seq=7 send it made to this actor.
    actor ! interaction(tick = 5L, senderId = 101L, seq = 7L, isAntiMessage = true)

    val cascadedAntiMessage = peerProbe.expectMsgClass(3.seconds, classOf[ActorInteractionEvent])
    cascadedAntiMessage.isAntiMessage shouldBe true
    cascadedAntiMessage.tick shouldBe 5L
    cascadedAntiMessage.actorRefId shouldBe actor.underlyingActor.testEntityId

    actor.underlyingActor.testCounter shouldBe 0 // undone entirely, nothing replayed past it
  }

  it should "be a quiet no-op when the referenced original can no longer be found" in {
    val (_, peerName) = newPeer()
    val actor = newActor(peerName)

    noException should be thrownBy {
      actor ! interaction(tick = 5L, senderId = 999L, seq = 99L, isAntiMessage = true)
      Thread.sleep(200)
    }
    actor.underlyingActor.testCounter shouldBe 0
  }
}
