package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ActorInteractionEvent, FinishEvent, SpontaneousEvent}
import core.entity.event.control.report.ReportEvent
import core.enumeration.{CreationTypeEnum, TimeManagerTypeEnum}
import model.hybrid.entity.event.data.{EnterLinkData, LeaveLinkData}
import model.hybrid.entity.event.data.link.LinkInfoData
import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.enumeration.{ActorTypeEnum, EventTypeEnum, MovableStatusEnum}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

class CarMesoTraversalSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("CarMesoTraversalSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class TestCar(props: Properties) extends Car(props) {
    val reports = mutable.ArrayBuffer.empty[ReportEvent]
    var retireRequests = 0
    def ready: Boolean = recoveryFinished
    def distance: Double = state.distance
    def initialize(route: Seq[(Long, Long)]): Unit = {
      state = CarState(0L, origin = 1L, destination = route.last._2, actorType = ActorTypeEnum.Car, size = 4.5)
      state.bestRoute = Some(mutable.Queue.from(route))
      state.status = MovableStatusEnum.Ready
      rollbackHandler.initialize(0L)
    }
    override protected def resolveLink(linkId: Long): Option[(Long, String)] = Some(linkId -> "test.Link")
    override protected def getCurrentNode: Long = 0L
    override protected def report(event: ReportEvent): Unit = reports += event
    override protected def selfDestruct(): Unit = retireRequests += 1
    override protected def sendMessageTo(entityId: Long, shardId: String, data: AnyRef,
      eventType: String, actorType: CreationTypeEnum): Unit =
      super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)
  }

  private var nextId = 2000L
  private def peer(): (Long, TestProbe) = {
    nextId += 1
    val id = nextId
    val probe = TestProbe()
    system.actorOf(Props(new Actor { def receive: Receive = { case message => probe.ref.forward(message) } }), id.toString)
    id -> probe
  }

  for (policy <- Seq(TimeManagerTypeEnum.DISCRETE_EVENT, TimeManagerTypeEnum.TIME_WARP);
       linkCount <- Seq(1, 2)) {
    "MESO link completion" should s"wait for every leave reply under $policy across $linkCount links" in {
      val manager = TestProbe()
      val links = Vector.fill(linkCount)(peer())
      nextId += 1
      val car = TestActorRef(new TestCar(Properties(entityId = nextId.toString, resourceId = "meso",
        actorType = CreationTypeEnum.PoolDistributed, reporters = mutable.Map.empty,
        timeManagers = mutable.Map(policy -> manager.ref))))
      manager.awaitAssert(car.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
      car.underlyingActor.initialize(links.zipWithIndex.map { case ((id, _), index) => id -> (index + 2L) })

      var wake = 10L
      links.zipWithIndex.foreach { case ((linkId, link), index) =>
        car ! SpontaneousEvent(wake, manager.ref)
        val enter = link.expectMsgType[ActorInteractionEvent]
        enter.data shouldBe a[EnterLinkData]
        manager.receiveWhile(idle = 30.millis) { case _: FinishEvent => () }
        val enterReply = ActorInteractionEvent(wake, wake, linkId, "test.Link", "", "test.Link",
          EventTypeEnum.ReceiveEnterLinkInfo.toString,
          LinkInfoData(200.0, 600.0, 1, 40.0, 1, requestSeq = enter.seq), resourceId = "meso", seq = 1L)
        car ! enterReply
        val scheduled = manager.expectMsgType[FinishEvent]
        val exitTick = scheduled.scheduleTick.get.toLong
        exitTick should be > wake
        car ! SpontaneousEvent(exitTick, manager.ref)
        val leave = link.expectMsgType[ActorInteractionEvent]
        leave.data shouldBe a[LeaveLinkData]

        car.underlyingActor.reports.exists(_.label == "journey_completed") shouldBe false
        car.underlyingActor.retireRequests shouldBe 0
        car.underlyingActor.distance shouldBe index * 200.0
        val waiting = manager.expectMsgType[FinishEvent]
        waiting.scheduleTick shouldBe None
        waiting.awaitingReply shouldBe true
        link.expectNoMessage(50.millis)

        car ! enterReply.copy(tick = exitTick + 3L, lamportTick = exitTick + 3L, seq = 2L,
          eventType = EventTypeEnum.ReceiveLeaveLinkInfo.toString,
          data = LinkInfoData(200.0, 600.0, 0, 40.0, 1, requestSeq = leave.seq))
        car.underlyingActor.distance shouldBe (index + 1) * 200.0
        val completions = car.underlyingActor.reports.filter(_.label == "journey_completed")
        if (index == linkCount - 1) {
          completions.size shouldBe 1
          completions.head.data.asInstanceOf[Map[String, Any]]("total_distance") shouldBe linkCount * 200.0
          car.underlyingActor.retireRequests shouldBe 1
        } else {
          completions shouldBe empty
          wake = manager.expectMsgType[FinishEvent].scheduleTick.get.toLong
          wake shouldBe exitTick + 4L
        }
      }
    }
  }
}
