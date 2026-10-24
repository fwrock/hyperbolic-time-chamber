package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.SubwayState
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{ Moving, Ready, Stopped }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

/** Regression coverage for the item-2 finding in the Time Warp `AwaitedReply` follow-up
  * (`docs/TIME_WARP_DESIGN.md` §10): `Movable.leavingLink`/`Movable.enterLink` register an awaited
  * reply (`Movable.leaveLinkAwaitKey`/`enterLinkAwaitKey`) for every `Movable` subclass, `Subway`
  * included, but `Subway.actHandleReceiveLeaveLinkInfo` (`Subway.scala`) had no consume-side use of
  * it at all -- it gated on a bare `state.status == Moving` check instead. Under Time Warp, a
  * rollback that restores `state.status` away from `Moving` (from before this reply's originating
  * `LeaveLinkData` was sent) makes a genuine, un-retractable reply look like a no-op: the branch
  * silently does nothing, `onFinishSpontaneous` is never called, and since `Movable.leavingLink`
  * already disengaged (`onFinishSpontaneous(Some(currentTick + 1))` only fires once this handler
  * runs), the actor never gets rescheduled -- the same orphaned-actor shape as
  * `SimulationBaseActorAwaitedReplySpec`, just with `Subway`'s inline handler instead of a
  * `*SignalHandler` class.
  *
  * `onFinishSpontaneous`/`scheduleEvent` are overridden here (both are non-final on
  * `SimulationBaseActor`) purely to record calls -- this avoids standing up real cluster-sharding
  * message routing (`SimulationBaseActorAwaitedReplySpec`'s heavier real-mailbox harness) since the
  * behavior under test lives entirely in `actHandleReceiveLeaveLinkInfo`'s own gating logic, not in
  * message delivery.
  */
class SubwayAwaitedReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SubwayAwaitedReplySpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestSubway(properties: Properties) extends Subway(properties) {
    val finishCalls: mutable.ArrayBuffer[Option[Tick]] = mutable.ArrayBuffer.empty
    val scheduleCalls: mutable.ArrayBuffer[Tick] = mutable.ArrayBuffer.empty

    override protected def onFinishSpontaneous(scheduleTick: Option[Tick] = None, destruct: Boolean = false): Unit =
      finishCalls += scheduleTick
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean = false): Unit = scheduleCalls += tick
    override protected def report(data: Any, label: String = null): Unit = ()

    def testSetState(s: SubwayState): Unit = {
      state = s
      startTick = s.startTick
    }
    def testRegisterAwaitedReply(key: String, kind: String): Unit = registerAwaitedReply(key, kind)
    def testLeaveLinkInfo(event: ActorInteractionEvent, data: LinkInfoData): Unit =
      actHandleReceiveLeaveLinkInfo(event, data)
    def testStatus: MovableStatusEnumAlias = state.status
  }

  private type MovableStatusEnumAlias = model.hybrid.entity.state.enumeration.MovableStatusEnum

  private def freshState(status: MovableStatusEnumAlias): SubwayState = {
    val s = SubwayState(
      startTick = 0L,
      capacity = 200,
      numberOfPorts = 4,
      velocity = 20.0,
      stopTime = 30L,
      origin = 1001L,
      destination = 1002L,
      line = "line-1"
    )
    s.status = status
    s
  }

  private var nextEntitySuffix = 0

  private def newTestSubway(timeManagerType: String, entityId: String = "subway"): TestSubway = {
    nextEntitySuffix += 1
    val properties = Properties(
      entityId = entityId,
      resourceId = "res-1",
      timeManagers = mutable.Map.empty,
      defaultTimeManagerType = timeManagerType
    )
    TestActorRef(new TestSubway(properties), s"$entityId-$nextEntitySuffix").underlyingActor
  }

  private def leaveLinkEvent(linkId: Long, tick: Tick = 5L, seq: Long = 0L): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = tick,
      lamportTick = tick,
      actorRefId = linkId,
      shardRefId = "hybrid.actor.RailLink",
      actorPathRef = s"/user/rail-$linkId",
      actorClassType = "hybrid.actor.RailLink",
      eventType = "RECEIVE_LEAVE_LINK_INFO",
      data = LinkInfoData(linkLength = 100.0, linkFreeSpeed = 10.0),
      resourceId = "res-1",
      seq = seq
    )

  "a Time Warp Subway" should "still process a genuine leave-link reply after a rollback restores state.status away from Moving" in {
    val subway = newTestSubway(TimeManagerTypeEnum.TIME_WARP)
    subway.testSetState(freshState(Moving))

    val linkId = 42L
    // Mirrors Movable.leavingLink's real registration for this exchange.
    subway.testRegisterAwaitedReply(Movable.leaveLinkAwaitKey(linkId), "LeaveLinkData")

    // The rollback: restores state.status to a pre-request value (here, already-Ready) without
    // retracting the genuinely in-flight reply -- exactly what a status-only gate cannot tell apart
    // from staleness.
    subway.testSetState(freshState(Ready))

    subway.testLeaveLinkInfo(leaveLinkEvent(linkId), LinkInfoData(linkLength = 100.0, linkFreeSpeed = 10.0))

    // Proof the actor is NOT orphaned: it reschedules itself via onFinishSpontaneous, which only
    // this handler can do once Movable.leavingLink has already disengaged.
    subway.finishCalls should not be empty
    subway.finishCalls.last shouldBe defined
    subway.testStatus shouldBe Ready
  }

  it should "still drop a genuinely stale leave-link reply (no matching outstanding request)" in {
    val subway = newTestSubway(TimeManagerTypeEnum.TIME_WARP)
    subway.testSetState(freshState(Moving))

    // No registerAwaitedReply call for this link -- nothing outstanding for it.
    subway.testLeaveLinkInfo(leaveLinkEvent(linkId = 99L), LinkInfoData(linkLength = 50.0, linkFreeSpeed = 10.0))

    subway.finishCalls shouldBe empty
    subway.testStatus shouldBe Moving // unchanged -- the reply was never accepted
  }

  "a conservative-mode Subway" should "resolve a registered leave request after its station dwell" in {
    val subway = newTestSubway(TimeManagerTypeEnum.DISCRETE_EVENT)
    subway.testSetState(freshState(Stopped))

    subway.testRegisterAwaitedReply(Movable.leaveLinkAwaitKey(7L), "LeaveLinkData")
    subway.testLeaveLinkInfo(leaveLinkEvent(linkId = 7L), LinkInfoData(linkLength = 80.0, linkFreeSpeed = 10.0))

    subway.finishCalls should not be empty
    subway.testStatus shouldBe Ready
  }

  it should "drop an unregistered reply without changing state" in {
    val subway = newTestSubway(TimeManagerTypeEnum.DISCRETE_EVENT)
    subway.testSetState(freshState(Ready))

    subway.testLeaveLinkInfo(leaveLinkEvent(linkId = 7L), LinkInfoData(linkLength = 80.0, linkFreeSpeed = 10.0))

    subway.finishCalls shouldBe empty
    subway.testStatus shouldBe Ready
  }
}
