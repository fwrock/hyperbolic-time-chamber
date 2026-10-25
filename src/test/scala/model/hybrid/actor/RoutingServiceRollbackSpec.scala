package org.interscity.htc
package model.hybrid.actor

import com.typesafe.config.ConfigFactory
import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import model.hybrid.entity.event.data.routing.*
import model.hybrid.entity.state.RoutingServiceState
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

class RoutingServiceRollbackSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("RoutingServiceRollbackSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off\nhtc.time-warp.checkpoint-interval=50")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class TestRoutingService(properties: Properties) extends RoutingService(properties) {
    override protected def sendMessageTo(entityId: Long, shardId: String, data: AnyRef,
      eventType: String, actorType: CreationTypeEnum): Unit =
      super.sendMessageTo(entityId, shardId, data, eventType, CreationTypeEnum.PoolDistributed)
    def initialize(): Unit = {
      state = RoutingServiceState()
      startTick = 0L
      rollbackHandler.initialize(0L)
    }
    def costs = state.costs
    def recovered: Boolean = !recoveryRunning
  }

  private def event(tick: Long, source: Long, seq: Long, data: AnyRef): ActorInteractionEvent =
    ActorInteractionEvent(tick = tick, lamportTick = tick, actorRefId = source,
      shardRefId = source.toString, actorPathRef = s"/user/$source", actorClassType = "test.Peer",
      eventType = data.getClass.getSimpleName, data = data, resourceId = "routing-test", seq = seq,
      order = Some(EventOrder(tick, 0L, s"$source:$seq")))

  "RoutingService rollback" should "restore cost versions, retract replies and reprocess surviving queries" in {
    val tm = TestProbe()
    val peer = TestProbe()
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => peer.ref.forward(msg) } }), "7001")
    val properties = Properties(entityId = "7002", resourceId = "routing-test",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> tm.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.TIME_WARP, reporters = mutable.Map.empty)
    val actor = TestActorRef(new TestRoutingService(properties), "7002")
    peer.awaitAssert(actor.underlyingActor.recovered shouldBe true, 3.seconds, 20.millis)
    actor.underlyingActor.initialize()
    val first = event(1L, 7001L, 1L, LinkCostChangedData(7001L, 100.0, 1L))
    val changed = event(3L, 7001L, 2L, LinkCostChangedData(7001L, 900.0, 3L))
    actor ! first
    actor ! changed
    actor ! event(4L, 7001L, 3L, RouteQueryData(1L, 1L, 10))
    val original = peer.expectMsgType[ActorInteractionEvent]
    original.data shouldBe RouteReplyData(3L, Some(RouteResult(0.0, Nil)))
    actor.underlyingActor.costs(7001L).weight shouldBe 900.0

    actor ! changed.copy(isAntiMessage = true)
    val anti = peer.expectMsgType[ActorInteractionEvent]
    anti.isAntiMessage shouldBe true
    anti.messageId shouldBe original.messageId
    val corrected = peer.expectMsgType[ActorInteractionEvent]
    corrected.isAntiMessage shouldBe false
    corrected.data shouldBe original.data
    actor.underlyingActor.costs(7001L).weight shouldBe 100.0
    actor.underlyingActor.costs(7001L).order shouldBe first.order.get
    peer.expectNoMessage(100.millis)
  }
}
