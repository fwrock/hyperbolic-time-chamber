package org.interscity.htc
package core.partial

import core.entity.actor.{ ActorDataSimulation, ActorSimulation, ShardActorId }
import core.entity.configuration.{ ActorDataSource, DataSource }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum }

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }

class PartialFilterSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  override def afterEach(): Unit = PartialFilter.reset()

  private def write(dir: Path, name: String, json: String): String = {
    val f = dir.resolve(name); Files.writeString(f, json); f.toString
  }
  private def source(id: String, path: String) =
    ActorDataSource(id, "x", CreationTypeEnum.LoadBalancedDistributed, DataSource(DataSourceTypeEnum.json, Map("path" -> path)))
  private def actor(id: Long, typ: String) =
    ActorSimulation(id = id, name = null, typeActor = typ, data = ActorDataSimulation("d", Map.empty), relationships = null)

  // persons 1,2 ; cars 11,12 (of 1), 21 (of 2) declared on the car with part_of ; nodes 90,91 are not filterable
  private def scenario(dir: Path) = {
    val persons = write(dir, "persons.json",
      """[{"id":1,"typeActor":"hybrid.actor.Person","data":{"dataType":"d","content":{}}},
        |{"id":2,"typeActor":"hybrid.actor.Person","data":{"dataType":"d","content":{}}},
        |{"id":3,"typeActor":"hybrid.actor.Person","data":{"dataType":"d","content":{}}}]""".stripMargin)
    val cars = write(dir, "cars.json",
      """[{"id":11,"typeActor":"hybrid.actor.Car","data":{"dataType":"d","content":{}},"dependencies":{"owner":{"id":1,"classType":"P","relation":"part_of"},"from_node":{"id":90,"classType":"N"}}},
        |{"id":12,"typeActor":"hybrid.actor.Car","data":{"dataType":"d","content":{}},"dependencies":{"owner":{"id":1,"classType":"P","relation":"part_of"}}},
        |{"id":21,"typeActor":"hybrid.actor.Car","data":{"dataType":"d","content":{}},"dependencies":{"owner":{"id":2,"classType":"P","relation":"part_of"}}}]""".stripMargin)
    val nodes = write(dir, "nodes.json", """[{"id":90,"typeActor":"hybrid.actor.Node","data":{"dataType":"d","content":{}}},{"id":91,"typeActor":"hybrid.actor.Node","data":{"dataType":"d","content":{}}}]""")
    List(source("p", persons), source("c", cars), source("n", nodes))
  }
  private def manifest(dir: Path, units: String*) =
    write(dir, "selection.json", s"""{"schema":"htc.simedape.selection.v1","scenario_id":"s","selected":[${units.map(u => s"""{"unit":"$u"}""").mkString(",")}]}""")
  private def settings(sel: String, classes: Set[String] = Set("Person", "Car")) = PartialFilter.Settings(sel, classes, "")

  "disabled" should "leave every actor untouched" in {
    PartialFilter.enabled shouldBe false
    val all = List(actor(1, "hybrid.actor.Person"), actor(90, "hybrid.actor.Node"))
    PartialFilter.filter(all) shouldBe all
    PartialFilter.settings(ConfigFactory.parseString("htc.partial.selection-file = \"\"")).enabled shouldBe false
    PartialFilter.settings(ConfigFactory.empty()).enabled shouldBe false
  }

  "a selected person" should "keep their cars and drop the other persons, cars and none of the infrastructure" in {
    val dir = Files.createTempDirectory("partial")
    val s = settings(manifest(dir, "1"))
    val summary = PartialFilter.prepare(scenario(dir), s).toOption.get
    summary.kept shouldBe 3                                         // person 1 + cars 11, 12
    summary.removed shouldBe 3                                      // persons 2, 3 + car 21
    val all = List(1L, 2L, 3L).map(actor(_, "hybrid.actor.Person")) ++ List(11L, 12L, 21L).map(actor(_, "hybrid.actor.Car")) ++ List(90L, 91L).map(actor(_, "hybrid.actor.Node"))
    PartialFilter.filter(all).map(_.id).toSet shouldBe Set(1L, 11L, 12L, 90L, 91L)
    PartialFilter.enabled shouldBe true
    PartialFilter.writeReport(s, summary).map(p => Files.readString(Path.of(p))).exists(_.contains("htc.partial.report.v1")) shouldBe true
  }

  "buses, bus stops, subway and stations" should "never be removed when their classes are not listed as filterable" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "1"), Set("Person", "Car", "Bicycle", "Motorcycle"))).isRight shouldBe true
    val transit = List("Bus", "BusStop", "BusStation", "Subway", "SubwayStation", "RailLink", "TrafficSignal").zipWithIndex
      .map { case (c, i) => actor(500L + i, s"hybrid.actor.$c") }
    PartialFilter.filter(transit ++ List(2L, 3L).map(actor(_, "hybrid.actor.Person"))).map(_.id) shouldBe transit.map(_.id)
  }

  "a selected car" should "keep its owner" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "21"))).isRight shouldBe true
    val all = List(1L, 2L, 3L).map(actor(_, "hybrid.actor.Person")) ++ List(11L, 12L, 21L).map(actor(_, "hybrid.actor.Car"))
    PartialFilter.filter(all).map(_.id).toSet shouldBe Set(2L, 21L)
  }

  "a person without cars" should "be removed when not selected and kept when selected" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "3"))).isRight shouldBe true
    PartialFilter.filter(List(1L, 2L, 3L).map(actor(_, "hybrid.actor.Person"))).map(_.id) shouldBe List(3L)
  }

  "the filterable classes" should "accept a simple name or the full class name, and be required" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "1"), Set("hybrid.actor.Person", "hybrid.actor.Car"))).isRight shouldBe true
    PartialFilter.reset()
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "1"), Set.empty)).left.toOption.get should include("filterable-classes")
    PartialFilter.enabled shouldBe false
  }

  "inconsistent relations" should "fail the load and leave the filter off" in {
    val dir = Files.createTempDirectory("partial")
    val bad = write(dir, "bad.json", """[{"id":50,"typeActor":"hybrid.actor.Car","data":{"dataType":"d","content":{}},"dependencies":{"owner":{"id":999,"classType":"P","relation":"part_of"}}}]""")
    val r = PartialFilter.prepare(scenario(dir) :+ source("bad", bad), settings(manifest(dir, "1")))
    r.isLeft shouldBe true
    PartialFilter.enabled shouldBe false
  }

  "a missing or foreign manifest" should "fail the load" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(dir.resolve("none.json").toString)).isLeft shouldBe true
  }

  "a reset filter" should "behave as disabled again" in {
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "1")))
    PartialFilter.reset()
    PartialFilter.filter(List(actor(1, "hybrid.actor.Person"))).size shouldBe 1       // reset = disabled again
  }

  "the settings" should "read a list or a comma-separated environment-style string" in {
    PartialFilter.settings(ConfigFactory.parseString("""htc.partial { selection-file = "/s.json", filterable-classes = [Person, Car] }""")).filterableClasses shouldBe Set("Person", "Car")
    PartialFilter.settings(ConfigFactory.parseString("""htc.partial { selection-file = "/s.json", filterable-classes = "Person, Car" }""")).filterableClasses shouldBe Set("Person", "Car")
  }

  "the simulation.json partial block" should "be parsed, give the settings, and be cleared for a later full load" in {
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    val p = mapper.readValue("""{"selectionFile":"/s.json","filterableClasses":["Person","Car"]}""", classOf[core.entity.configuration.PartialSimulation])
    val st = PartialFilter.settings(p)
    st.enabled shouldBe true
    st.filterableClasses shouldBe Set("Person", "Car")
    val dir = Files.createTempDirectory("partial")
    PartialFilter.prepare(scenario(dir), settings(manifest(dir, "1"))).isRight shouldBe true
    PartialFilter.enabled shouldBe true
    PartialFilter.clear()                                  // what LoadDataManager does when the next load has no partial block
    PartialFilter.enabled shouldBe false
    PartialFilter.filter(List(actor(2, "hybrid.actor.Person"))).size shouldBe 1
  }
}
