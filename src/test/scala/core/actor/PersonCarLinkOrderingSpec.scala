package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import core.entity.state.BaseState
import core.enumeration.{ CreationTypeEnum, TimeManagerTypeEnum }
import core.runtime.EventOrder
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ Actor, ActorSystem, Props }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

private case class OrderingState(startTick: Long = 0L, value: Int = 1, entries: List[String] = Nil)
  extends BaseState(startTick = startTick)

/** Minimal Person -> Car -> Link reply protocol adapters; real mobility outcomes are tested separately.
  * Noncommuting updates make mailbox-order execution observable, and the reply crosses two LPs.
  */
class PersonCarLinkOrderingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("PersonCarLinkOrderingSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private class ProtocolActor(properties: Properties, role: String, downstream: Long)
    extends SimulationBaseActor[OrderingState](properties) {
    def ready: Boolean = recoveryFinished
    def init(): Unit = { state = OrderingState(); rollbackHandler.initialize(0L) }
    def value: Int = state.value
    def entries: List[String] = state.entries
    override def actInteractWith(event: ActorInteractionEvent): Unit = {
      val value = if (role == "Link") event.eventType match {
        case "a" => state.value + 3
        case "b" => state.value * 2
        case _ => state.value
      } else event.data.toString.toInt
      state = state.copy(value = value, entries = state.entries :+ event.eventType)
      if ((role == "Link" && event.eventType == "query") || role == "Car") {
        sendMessageTo(downstream, data = value.toString, eventType = "completion",
          actorType = CreationTypeEnum.PoolDistributed)
      }
    }
  }

  private var nextId = 100L
  private def actor(role: String, mode: String, downstream: Long): TestActorRef[ProtocolActor] = {
    nextId += 1
    val ref = TestActorRef(new ProtocolActor(Properties(entityId = nextId.toString, resourceId = "ordering",
      actorType = CreationTypeEnum.PoolDistributed, timeManagers = mutable.Map(mode -> TestProbe().ref)), role, downstream))
    TestProbe().awaitAssert(ref.underlyingActor.ready shouldBe true, 5.seconds, 10.millis)
    ref.underlyingActor.init()
    ref
  }
  private def destination(): (Long, TestProbe) = {
    nextId += 1
    val id = nextId
    val probe = TestProbe()
    system.actorOf(Props(new Actor { def receive: Receive = { case msg => probe.ref.forward(msg) } }), id.toString)
    (id, probe)
  }
  private def input(name: String, delta: Long): ActorInteractionEvent =
    ActorInteractionEvent(10L, 0L, 99L, "test", "", "test", name, "0", resourceId = "ordering",
      seq = name.head.toLong, order = Some(EventOrder(10L, delta, name)))

  private def run(mode: String, replacementBeforeAnti: Boolean): Unit = {
    val (personId, toPerson) = destination()
    val (carId, toCar) = destination()
    val person = actor("Person", mode, 0L)
    val car = actor("Car", mode, personId)
    val link = actor("Link", mode, carId)
    if (mode == TimeManagerTypeEnum.DISCRETE_EVENT) link ! input("a", 0L)
    link ! input("b", 0L)
    link ! input("query", 1L)
    val original = toCar.expectMsgType[ActorInteractionEvent]
    car ! original
    val originalCompletion = toPerson.expectMsgType[ActorInteractionEvent]
    person ! originalCompletion
    if (mode == TimeManagerTypeEnum.TIME_WARP) {
      person.underlyingActor.value shouldBe 2
      link ! input("a", 0L)
      val cancelled = toCar.expectMsgType[ActorInteractionEvent]
      cancelled.isAntiMessage shouldBe true
      val replacement = toCar.expectMsgType[ActorInteractionEvent]
      replacement.order shouldBe original.order
      replacement.seq should not be original.seq
      if (replacementBeforeAnti) {
        car ! replacement
        person ! toPerson.expectMsgType[ActorInteractionEvent]
        car ! cancelled
        person ! toPerson.expectMsgType[ActorInteractionEvent]
        person ! toPerson.expectMsgType[ActorInteractionEvent]
        person ! toPerson.expectMsgType[ActorInteractionEvent]
      } else {
        car ! cancelled
        person ! toPerson.expectMsgType[ActorInteractionEvent]
        car ! replacement
        person ! toPerson.expectMsgType[ActorInteractionEvent]
      }
    }
    link.underlyingActor.entries shouldBe List("a", "b", "query")
    car.underlyingActor.entries shouldBe List("completion")
    person.underlyingActor.entries shouldBe List("completion")
    person.underlyingActor.value shouldBe 8
  }

  "the controlled Person-Car-Link protocol" should "complete under conservative ordered delivery" in {
    run(TimeManagerTypeEnum.DISCRETE_EVENT, false)
  }
  it should "correct all three LPs after same-tick reordering and a late cancellation" in {
    run(TimeManagerTypeEnum.TIME_WARP, false)
  }
  it should "preserve corrected state when replacement overtakes cancellation" in {
    run(TimeManagerTypeEnum.TIME_WARP, true)
  }
}
