package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.enumeration.{CreationTypeEnum, TimeManagerTypeEnum}
import core.types.Tick
import model.hybrid.entity.event.data.subway.{SubwayLoadPassengerData, SubwayUnloadPassengerData}
import model.hybrid.entity.state.SubwayState
import model.hybrid.entity.state.enumeration.MovableStatusEnum.Moving
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class SubwayStopBarrierSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SubwayStopBarrierSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Train extends Subway(Properties(entityId = "1")) {
    val wakes = mutable.ArrayBuffer.empty[Tick]
    val replies = mutable.ArrayBuffer.empty[AnyRef]
    def prepare(policy: String, occupied: Boolean): Unit = {
      state = SubwayState(0L, 100, 2, 20.0, stopTime = 20L, origin = 1L, destination = 3L, line = "line",
        subwayStations = mutable.Map(17L -> 3L), passengers = if (occupied) mutable.Map(22L -> Identify(id = 22L, classType = "hybrid.actor.Person")) else mutable.Map.empty)
      state.status = Moving
      state.movableCurrentPath = Some((19L, 3L))
      currentTimeManagerType = policy
      currentTick = 100L
    }
    def waits: Set[String] = buildMigrationSnapshot().awaitedReplies.keySet
    def count: Int = state.passengers.size
    def distance: Double = state.distance
    def setLinkLength(length: Double): Unit = state.currentLinkLength = length
    def roundTripState(): Unit = state = core.util.JsonUtil.fromJson[SubwayState](core.util.JsonUtil.toJson(state))
    def completeLeave(): Unit = {
      registerAwaitedReply(Movable.leaveLinkAwaitKey(19L), "LeaveLinkData")
      actHandleReceiveLeaveLinkInfo(event(19L, "leave"),
        model.hybrid.entity.event.data.link.LinkInfoData(1000.0, 100.0, 0, 80.0, 1, requestSeq = 0L))
    }
    def expected: Int = expectedUnloadResponses
    override protected def report(data: Any, label: String): Unit = ()
    override protected def sendMessageTo(entityId: Long, shardId: String, data: AnyRef, eventType: String,
      actorType: CreationTypeEnum): Unit = replies += data
    override protected def onFinishSpontaneous(scheduleTick: Option[Tick], destruct: Boolean = false): Unit = ()
    override protected def scheduleEvent(tick: Tick, resyncAfterRollback: Boolean = false): Unit = wakes += tick
  }
  private def event(id: Long, data: AnyRef): ActorInteractionEvent =
    ActorInteractionEvent(100L, 0L, id, "", "", "peer", data = data, resourceId = "")
  for (policy <- Seq(TimeManagerTypeEnum.DISCRETE_EVENT, TimeManagerTypeEnum.TIME_WARP); loadFirst <- Seq(false, true)) {
    "a subway stop barrier" should s"wait for both replies under $policy (load first=$loadFirst)" in {
      val train = TestActorRef(new Train).underlyingActor
      train.prepare(policy, occupied = true)
      train.actSpontaneous(SpontaneousEvent(100L, null))
      train.expected shouldBe 1
      train.waits shouldBe Set("subway-stop:load", "subway-stop:unload")
      val load = event(17L, SubwayLoadPassengerData(mutable.Seq.empty))
      val unload = event(22L, SubwayUnloadPassengerData(true))
      train.actInteractWith(if (loadFirst) load else unload)
      train.wakes shouldBe empty
      train.waits.size shouldBe 1
      train.actInteractWith(if (loadFirst) unload else load)
      train.waits shouldBe empty
      train.expected shouldBe 0
      train.count shouldBe 0
      train.wakes.toVector shouldBe Vector(120L)
    }
  }
  it should "retain the station reply obligation even when the arriving train has no passengers" in {
    val train = TestActorRef(new Train).underlyingActor
    train.prepare(TimeManagerTypeEnum.TIME_WARP, occupied = false)
    train.actSpontaneous(SpontaneousEvent(100L, null))
    train.waits shouldBe Set("subway-stop:load")
    train.wakes shouldBe empty
    train.actInteractWith(event(17L, SubwayLoadPassengerData(mutable.Seq.empty)))
    train.waits shouldBe empty
    train.wakes.toVector shouldBe Vector(120L)
  }

  it should "count arrival before boarding and avoid counting the same rail link again at cleanup" in {
    val train = TestActorRef(new Train, "901").underlyingActor
    train.prepare(TimeManagerTypeEnum.TIME_WARP, occupied = false)
    train.setLinkLength(1000.0)
    train.actSpontaneous(SpontaneousEvent(100L, null))
    train.distance shouldBe 1000.0
    train.roundTripState()
    train.actInteractWith(event(17L, SubwayLoadPassengerData(
      mutable.Seq(Identify(id = 22L, classType = "hybrid.actor.Person")))))
    val boarding = train.replies.collectFirst {
      case d: model.hybrid.entity.event.data.person.PassengerBoardedVehicleData => d
    }.get
    boarding.vehicleDistance shouldBe 1000.0
    train.completeLeave()
    train.distance shouldBe 1000.0
  }
}
