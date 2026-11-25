package org.interscity.htc
package model.mobility.actor

import com.typesafe.config.ConfigFactory
import core.entity.actor.properties.Properties
import core.enumeration.TimeManagerTypeEnum
import core.util.JsonUtil
import model.mobility.entity.state.CarState
import model.mobility.entity.state.enumeration.ActorTypeEnum
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializers }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

class CarMicroDepartureSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory
    .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
    .withFallback(ConfigFactory.load())
  private implicit val system: ActorSystem = ActorSystem("CarMicroDepartureSpec", config)
  private val peer = ActorSystem("CarMicroDepartureColdPeer", config)
  override def afterAll(): Unit = { system.terminate(); peer.terminate(); () }

  private class InspectCar(properties: Properties) extends Car(properties) {
    def prepare(value: CarState): Unit = state = value
    def entry: (Double, Option[Int]) = (microEntryVelocity, microEntryLane)
    def recovered: Boolean = recoveryFinished
  }

  private def carState: CarState = CarState(
    0L,
    origin = 1L,
    destination = 2L,
    actorType = ActorTypeEnum.Car,
    size = 4.5,
    microDepartSpeed = Some(8.5),
    microDepartLane = Some(2)
  )

  "Observed MICRO departure" should "supply the requested speed and lane only before first admission" in {
    val tm = TestProbe()
    val car = TestActorRef(
      new InspectCar(
        Properties(
          entityId = "981",
          reporters = mutable.Map.empty,
          timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref)
        )
      ),
      "981"
    )
    tm.awaitAssert(car.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    val value = carState
    car.underlyingActor.prepare(value)
    car.underlyingActor.entry shouldBe (8.5, Some(2))
    value.journey.sumoDepartTick = Some(10L)
    value.journey.sumoArrivalSpeed = 3.25
    value.journey.pendingRoadLane = Some(1)
    car.underlyingActor.entry shouldBe (3.25, Some(1))
    value.movableCurrentPathIsJunction = true
    car.underlyingActor.entry shouldBe (3.25, None)
  }

  it should "retain rest and ordinary lane selection for existing inputs" in {
    val tm = TestProbe()
    val car = TestActorRef(
      new InspectCar(
        Properties(
          entityId = "982",
          reporters = mutable.Map.empty,
          timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref)
        )
      ),
      "982"
    )
    tm.awaitAssert(car.underlyingActor.recovered shouldBe true, 5.seconds, 10.millis)
    car.underlyingActor.prepare(carState.copy(microDepartSpeed = None, microDepartLane = None))
    car.underlyingActor.entry shouldBe (0.0, None)
  }

  it should "preserve configured and admitted state through JSON and independent cold codecs" in {
    val original = carState
    original.journey.sumoDepartTick = Some(12L)
    original.journey.sumoArrivalSpeed = 4.0
    val json = JsonUtil.fromJson[CarState](JsonUtil.toJson(original))
    json.microDepartSpeed shouldBe Some(8.5)
    json.microDepartLane shouldBe Some(2)
    json.journey.sumoDepartTick shouldBe Some(12L)
    val from = SerializationExtension(system)
    val serializer = from.findSerializerFor(original)
    val restored = SerializationExtension(peer)
      .deserialize(
        from.serialize(original).get,
        serializer.identifier,
        Serializers.manifestFor(serializer, original)
      )
      .get
      .asInstanceOf[CarState]
    restored.microDepartSpeed shouldBe Some(8.5)
    restored.microDepartLane shouldBe Some(2)
    restored.journey.sumoArrivalSpeed shouldBe 4.0
    restored.journey.sumoDepartTick shouldBe Some(12L)
  }

  it should "decode integer-valued JSON inputs and reject invalid observed states" in {
    val value = JsonUtil.fromJson[CarState](
      """{"startTick":0,"origin":1,"destination":2,"actorType":"Car","size":4.5,"microDepartSpeed":7,"microDepartLane":2}"""
    )
    value.microDepartSpeed shouldBe Some(7.0)
    value.microDepartLane shouldBe Some(2)
    intercept[IllegalArgumentException](carState.copy(microDepartSpeed = Some(-1)))
    intercept[IllegalArgumentException](carState.copy(microDepartSpeed = Some(Double.NaN)))
    intercept[IllegalArgumentException](carState.copy(microDepartLane = Some(-1)))
  }
}
