package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.SubwayState
import model.hybrid.entity.state.enumeration.MovableStatusEnum.{Ready, Stopped}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class SubwayLeaveReplySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SubwayLeaveReplySpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Train extends Subway(Properties(entityId = "1")) {
    val wakes = mutable.ArrayBuffer.empty[Option[Tick]]
    val reports = mutable.ArrayBuffer.empty[Any]
    def initialize(policy: String): Unit = {
      state = SubwayState(0L, 100, 2, 20.0, stopTime = 20L, origin = 1L, destination = 2L, line = "test")
      state.status = Stopped
      currentTimeManagerType = policy
      currentTick = 86L
      registerAwaitedReply(Movable.leaveLinkAwaitKey(19L), "LeaveLinkData")
    }
    def distance: Double = state.distance
    def status = state.status
    override protected def report(data: Any, label: String): Unit = reports += data
    override protected def onFinishSpontaneous(tick: Option[Tick], destruct: Boolean = false): Unit = wakes += tick
  }
  for (policy <- Seq(TimeManagerTypeEnum.DISCRETE_EVENT, TimeManagerTypeEnum.TIME_WARP)) {
    "a subway leave reply" should s"resolve the stopped train's registered request under $policy exactly once" in {
      val train = TestActorRef(new Train).underlyingActor
      train.initialize(policy)
      val event = ActorInteractionEvent(86L, 0L, 19L, "", "", "hybrid.actor.RailLink", data = "reply", resourceId = "")
      train.actHandleReceiveLeaveLinkInfo(event, LinkInfoData(linkLength = 1000.0, requestSeq = 1L))
      train.distance shouldBe 0.0
      train.wakes shouldBe empty
      train.reports shouldBe empty
      train.actHandleReceiveLeaveLinkInfo(event, LinkInfoData(linkLength = 1000.0, requestSeq = 0L))
      train.distance shouldBe 1000.0
      train.status shouldBe Ready
      train.wakes.toVector shouldBe Vector(Some(87L))
      train.actHandleReceiveLeaveLinkInfo(event, LinkInfoData(linkLength = 1000.0, requestSeq = 0L))
      train.distance shouldBe 1000.0
      train.wakes.size shouldBe 1
      train.reports.size shouldBe 1
    }
  }
}
