package org.interscity.htc
package model.hybrid.support

import core.actor.manager.load.{ActorLoadFilter, ScenarioPreflightValidator}
import core.entity.actor.{ActorDataSimulation, ActorSimulation}
import core.entity.configuration.{ActorDataSource, DataSource}
import core.enumeration.{CreationTypeEnum, DataSourceTypeEnum}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import model.hybrid.decision.ScenarioValidationContext
import java.nio.file.Files
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

class MobilityLoadTransformSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val excluded = Set("Bus", "BusStop", "BusStation", "Subway", "SubwayStation", "RailLink")
  private val walk = ActorLoadFilter.Settings(excludedClasses = excluded, transforms = List(
    new MobilityLoadTransform(ConfigFactory.parseString("htc.mobility.load-policy.transit-trip-policy=walk"))))
  private val reject = ActorLoadFilter.Settings(excludedClasses = excluded, transforms = List(
    new MobilityLoadTransform(ConfigFactory.empty())))
  private val home = """{"kind":"Activity","activityType":"home","nodeId":1,"endTime":{"kind":"AtTick","tick":10}}"""
  private val work = """{"kind":"Activity","activityType":"work","nodeId":2,"endTime":{"kind":"AtTick","tick":100}}"""
  private val transitChoice = """{"kind":"PendingDecision","decision":{"strategyId":"raptor","allowedModes":["Bus","Subway","Walk"]}}"""
  private def actor(plan: String, typ: String = "Person") = ActorSimulation(
    id = 7L, name = null, typeActor = s"hybrid.actor.$typ",
    data = ActorDataSimulation("model.hybrid.entity.state.PersonState",
      mapper.readValue(s"""{"originalPlan":[$plan],"ownedVehicles":{},"startTick":10}""", classOf[Object])))
  private def tree(a: ActorSimulation): JsonNode = mapper.valueToTree[JsonNode](a.data.content)
  private def source(typ: String, path: String = "unused") = ActorDataSource(
    typ, s"hybrid.actor.$typ", CreationTypeEnum.LoadBalancedDistributed,
    DataSource(DataSourceTypeEnum.json, Map("path" -> path)))

  override def afterEach(): Unit = ActorLoadFilter.clear()

  "default settings" should "leave the scenario and all transport actors unchanged" in {
    val a = actor(s"$home,$transitChoice,$work")
    ActorLoadFilter.transform(a, ActorLoadFilter.Settings()).get should be theSameInstanceAs a
    ActorLoadFilter.settings(ConfigFactory.empty()) shouldBe ActorLoadFilter.Settings()
    ActorLoadFilter.isExcluded("hybrid.actor.Bus", ActorLoadFilter.Settings()) shouldBe false
  }

  "the non-transit profile" should "exclude vehicles, their spawners and stations while retaining private mobility and roads" in {
    val transit = List("Bus", "BusStop", "BusStation", "Subway", "SubwayStation", "RailLink")
    val retained = List("Node", "Link", "TrafficSignal", "Person", "Car", "Bicycle")
    ActorLoadFilter.sources((transit ++ retained).map(source(_)), walk).map(_.classType) shouldBe retained.map("hybrid.actor." + _)
    transit.foreach { typ =>
      ActorLoadFilter.transform(actor("", typ), walk) shouldBe None
      ActorLoadFilter.isExcluded(s"org.interscity.htc.model.hybrid.actor.$typ", walk) shouldBe true
    }
  }

  it should "replace a pending transit trip with a physical walking leg without mutating the source" in {
    val original = actor(s"$home,$transitChoice,$work")
    val before = tree(original)
    val transformed = tree(ActorLoadFilter.transform(original, walk).get)
    val leg = transformed.path("originalPlan").get(1)
    leg.path("kind").asText() shouldBe "WalkLeg"
    leg.path("originNodeId").asLong() shouldBe 1L
    leg.path("destinationNodeId").asLong() shouldBe 2L
    transformed.path("originalPlan").get(0) shouldBe before.path("originalPlan").get(0)
    transformed.path("originalPlan").get(2) shouldBe before.path("originalPlan").get(2)
    tree(original) shouldBe before
  }

  it should "collapse access, transit and egress into one walk while preserving the next private-vehicle trip" in {
    val access = """{"kind":"WalkLeg","originNodeId":1,"destinationNodeId":10}"""
    val transit = """{"kind":"TransitLeg","mode":"Bus","line":"L"}"""
    val egress = """{"kind":"WalkLeg","originNodeId":20,"destinationNodeId":2}"""
    val car = """{"kind":"PrivateVehicleLeg","mode":"Car","vehicle":{"id":90,"classType":"hybrid.actor.Car"}}"""
    val bike = car.replace("Car", "Bicycle").replace("90", "91")
    val original = actor(s"$home,$access,$transit,$egress,$work,$car,$home,$bike,$work")
    val updated = tree(ActorLoadFilter.transform(original, walk).get).path("originalPlan")
    updated.size() shouldBe 7
    updated.get(1).path("destinationNodeId").asLong() shouldBe 2L
    updated.get(3) shouldBe mapper.readTree(car)
    updated.get(5) shouldBe mapper.readTree(bike)
  }

  it should "reject transit-dependent plans unless walking fallback was explicitly selected" in {
    val error = intercept[IllegalArgumentException] {
      ActorLoadFilter.transform(actor(s"$home,$transitChoice,$work"), reject)
    }
    error.getMessage should include("transit-trip-policy=walk")
  }

  it should "reject a mixed private-vehicle/transit trip rather than silently relocate its private vehicle" in {
    val car = """{"kind":"PrivateVehicleLeg","mode":"Car"}"""
    intercept[IllegalArgumentException] {
      ActorLoadFilter.transform(actor(s"$home,$car,$transitChoice,$work"), walk)
    }.getMessage should include("mixed private-vehicle/transit")
  }

  it should "reject a transit trip missing its destination activity" in {
    intercept[IllegalArgumentException] {
      ActorLoadFilter.transform(actor(s"$home,$transitChoice"), walk)
    }.getMessage should include("activities at both ends")
  }

  it should "preserve walking and private-only decisions" in {
    val privatePending = transitChoice.replace("[\"Bus\",\"Subway\",\"Walk\"]", "[\"Car\",\"Bicycle\",\"Walk\"]")
    val original = actor(s"$home,$privatePending,$work")
    ActorLoadFilter.transform(original, reject).get should be theSameInstanceAs original
  }

  "configuration" should "validate policies and reset the filter for a later unrestricted run" in {
    val config = ConfigFactory.parseString("""htc.load-filter { exclude-classes=[Bus], transforms=["org.interscity.htc.model.hybrid.support.MobilityLoadTransform"] }
      htc.mobility.load-policy.transit-trip-policy=walk""")
    ActorLoadFilter.configure(config).excludedClasses shouldBe Set("Bus")
    ActorLoadFilter(List(actor("", "Bus"))) shouldBe Nil
    ActorLoadFilter.configure(ConfigFactory.empty()) shouldBe ActorLoadFilter.Settings()
    ActorLoadFilter(List(actor("", "Bus"))).size shouldBe 1
    intercept[IllegalArgumentException] {
      new MobilityLoadTransform(ConfigFactory.parseString("htc.mobility.load-policy.transit-trip-policy=typo"))
    }
  }

  "preflight" should "validate the filtered plan even when its original transit engine is unavailable" in {
    ActorLoadFilter.configure(ConfigFactory.parseString("""htc.load-filter { exclude-classes=[Bus], transforms=["org.interscity.htc.model.hybrid.support.MobilityLoadTransform"] }
      htc.mobility.load-policy.transit-trip-policy=walk"""))
    val path = Files.createTempFile("non-transit-preflight", ".json")
    val input = s"""[{"id":7,"typeActor":"hybrid.actor.Person","data":{"dataType":"model.hybrid.entity.state.PersonState","content":${tree(actor(s"$home,$transitChoice,$work"))}}}]"""
    Files.writeString(path, input)
    try {
      given ExecutionContext = ExecutionContext.global
      Await.result(ScenarioPreflightValidator.validate(List(source("Person", path.toString)),
        ScenarioValidationContext(false, false)), 10.seconds) shouldBe Right(())
      Files.readString(path) shouldBe input
    } finally Files.deleteIfExists(path)
  }
}
