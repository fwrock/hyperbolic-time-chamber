package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.MovableState
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

private case class SupersededReplyTestState(
  override val startTick: Tick = 0L,
  override val origin: Long = 1L,
  override val destination: Long = 2L,
  override val actorType: ActorTypeEnum = ActorTypeEnum.Car,
  override val size: Double = 1.0
) extends MovableState(
      startTick = startTick,
      origin = origin,
      destination = destination,
      actorType = actorType,
      size = size
    )

/** Regression coverage for `Movable.isKnownSupersededReply` (docs/KNOWN_GAPS.md, Cause 3, found/
  * fixed 2026-09-11): before this hook existed, `SimulationBaseActor.handleInteractWith` treated
  * EVERY tick-behind `ReceiveEnterLinkInfo`/`ReceiveLeaveLinkInfo` reply as a genuine causality
  * violation requiring a full rollback + anti-message cascade -- even when it was a reply to a
  * request this actor had already superseded with a fresh one (e.g. after an earlier, unrelated
  * rollback), which the actor's own `isAwaitingReply` identity check could already recognize as
  * safe to just discard. A real live run showed this exact case dominating rollback volume.
  *
  * These tests drive `isKnownSupersededReply` directly against the real `Movable.enterLinkAwaitKey`/
  * `registerAwaitedReply`/`isAwaitingReply` machinery -- no mailbox traffic needed, since the
  * question is purely "does this hook classify the reply correctly," not the rollback mechanics
  * themselves (already covered by `SimulationBaseActorAwaitedReplySpec`/
  * `MovableRouteRollbackReplaySpec`).
  */
class MovableIsKnownSupersededReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "MovableIsKnownSupersededReplySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestMovable(properties: Properties) extends Movable[SupersededReplyTestState](properties) {
    def testSetState(s: SupersededReplyTestState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testRegisterEnterLinkAwait(linkId: Long): Unit =
      registerAwaitedReply(Movable.enterLinkAwaitKey(linkId), "EnterLinkData")
    def testIsKnownSupersededReply(event: ActorInteractionEvent): Boolean = isKnownSupersededReply(event)
    def testCurrentAwaitedSendSeq(key: String): Long = awaitedReplySendSeq(key).getOrElse(-1L)
    // messageSendSeq is private in SimulationBaseActor; drive registration indirectly by sending a
    // real (harmless, undelivered -- no peer exists) message first, so registerAwaitedReply captures
    // a real, distinct seq per call, mirroring production's send-then-register ordering.
    def testSendThenRegisterEnterLinkAwait(linkId: Long): Unit = {
      sendMessageTo(
        entityId = linkId,
        shardId = "",
        data = "probe",
        eventType = "EnterLink",
        actorType = CreationTypeEnum.PoolDistributed
      )
      testRegisterEnterLinkAwait(linkId)
    }
  }

  private var nextEntitySuffix = 0

  private def newTestMovable(): TestMovable = {
    nextEntitySuffix += 1
    val movable = TestActorRef(
      new TestMovable(
        Properties(
          entityId = "1",
          // Not LoadBalancedDistributed (the default): that makes preStart() overwrite entityId
          // with the actor's own Pekko path name, which sendMessageTo later parses as a Long.
          actorType = CreationTypeEnum.PoolDistributed,
          defaultTimeManagerType = "time-warp"
        )
      ),
      s"movable-superseded-test-$nextEntitySuffix"
    ).underlyingActor
    movable.testSetState(SupersededReplyTestState())
    movable
  }

  private def enterLinkInfoEvent(linkId: Long, requestSeq: Long, tick: Tick = 0L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = linkId,
      shardRefId = "",
      actorPathRef = "",
      actorClassType = "hybrid.actor.Link",
      eventType = "ReceiveEnterLinkInfo",
      data = LinkInfoData(requestSeq = requestSeq),
      resourceId = ""
    )

  "Movable.isKnownSupersededReply" should "treat a ReceiveEnterLinkInfo reply as superseded when nothing is currently registered for that link" in {
    val movable = newTestMovable()

    movable.testIsKnownSupersededReply(enterLinkInfoEvent(linkId = 42L, requestSeq = 1L)) shouldBe true
  }

  it should "NOT treat a ReceiveEnterLinkInfo reply as superseded when it matches the currently outstanding request's identity" in {
    val movable = newTestMovable()
    movable.testSendThenRegisterEnterLinkAwait(42L)
    val currentSeq = movable.testCurrentAwaitedSendSeq(Movable.enterLinkAwaitKey(42L))

    movable.testIsKnownSupersededReply(enterLinkInfoEvent(linkId = 42L, requestSeq = currentSeq)) shouldBe false
  }

  it should "treat a ReceiveEnterLinkInfo reply as superseded once a fresh request for the same link has been registered" in {
    val movable = newTestMovable()
    movable.testSendThenRegisterEnterLinkAwait(42L)
    val staleSeq = movable.testCurrentAwaitedSendSeq(Movable.enterLinkAwaitKey(42L))

    // A rollback would consume/overwrite the old registration and resend; simulate the resend here.
    movable.testSendThenRegisterEnterLinkAwait(42L)

    movable.testIsKnownSupersededReply(enterLinkInfoEvent(linkId = 42L, requestSeq = staleSeq)) shouldBe true
  }

  it should "not touch an interaction event whose data isn't a LinkInfoData reply at all" in {
    val movable = newTestMovable()

    val unrelated = ActorInteractionEvent(
      tick = 0L,
      lamportTick = 0L,
      actorRefId = 99L,
      shardRefId = "",
      actorPathRef = "",
      actorClassType = "test.Other",
      eventType = "SomethingElse",
      data = "not-link-info",
      resourceId = ""
    )

    movable.testIsKnownSupersededReply(unrelated) shouldBe false
  }
}
