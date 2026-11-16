package org.interscity.htc
package core.partial

import core.actor.manager.load.ActorLoadFilter
import core.entity.actor.{ ActorDataSimulation, ActorSimulation }
import core.entity.configuration.{ ActorDataSource, DataSource }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum }
import core.modifications.ScenarioModifications
import core.util.SqliteActorSourceFixture

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.util.Using

class PartialFilterPreparationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val mapper = new ObjectMapper()

  override def beforeEach(): Unit = clearFilters()
  override def afterEach(): Unit = clearFilters()

  private def clearFilters(): Unit = {
    PartialFilter.clear()
    ActorLoadFilter.clear()
    ScenarioModifications.clear()
  }

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("htc-partial-preparation")
    try test(dir)
    finally
      Using.resource(Files.walk(dir)) {
        paths =>
          paths
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .forEach(
              path => Files.delete(path)
            )
      }
  }

  private def write(dir: Path, name: String, text: String): Path = {
    val path = dir.resolve(name)
    Files.writeString(path, text)
    path
  }

  private def record(id: Long, kind: String, relationships: String = "null"): String =
    s"""{"id":$id,"typeActor":"$kind","data":{"dataType":"d","content":{}},"relationships":$relationships}"""

  private def source(
    path: Path,
    kind: DataSourceTypeEnum = DataSourceTypeEnum.json
  ): ActorDataSource =
    ActorDataSource(
      path.getFileName.toString,
      "Car",
      CreationTypeEnum.LoadBalancedDistributed,
      DataSource(kind, Map("path" -> path.toString))
    )

  private def selection(dir: Path, units: List[String]): PartialFilter.Settings = {
    val entries = units
      .map(
        unit => s"""{"unit":"$unit"}"""
      )
      .mkString(",")
    val path = write(
      dir,
      "selection.json",
      s"""{"schema":"htc.simedape.selection.v1","selected":[$entries]}"""
    )
    PartialFilter.Settings(path.toString, Set("Person", "Car"), "")
  }

  private def loadedActor(id: Long, kind: String = "Person"): ActorSimulation =
    ActorSimulation(id, null, kind, data = ActorDataSimulation("d", Map.empty))

  "partial preparation" should "apply modifications before load-filter checks and composition selection" in {
    withDirectory {
      dir =>
        val car = record(
          11,
          "Car",
          """{"owner":{"id":1,"classType":"Person","relation":"part_of"},"position":{"id":90,"classType":"Node"}}"""
        )
        val actors = List(record(1, "Person"), record(2, "Person"), car, record(90, "Node"))
        val sources = List(source(write(dir, "actors.json", actors.mkString("[", ",", "]"))))
        val modifications = write(
          dir,
          "modifications.json",
          """{"schema":"htc.modifications.v1","operations":[{"op":"edit","ids":[11],"set":{"dependencies.owner.id":2,"dependencies.position":{"id":2,"classType":"Person"}}}]}"""
        )
        ScenarioModifications
          .prepare(sources, ScenarioModifications.Settings(List(modifications.toString)))
          .isRight shouldBe true
        ActorLoadFilter.configure(
          ConfigFactory.parseString("htc.load-filter.exclude-classes=[Node]")
        )

        val summary = PartialFilter.prepare(sources, selection(dir, List("2"))).toOption.get
        summary.total shouldBe 3
        summary.filterable shouldBe 3
        summary.kept shouldBe 2
        summary.byReason shouldBe Map("Selected" -> 1, "PartOfSelectedWhole" -> 1)
        PartialFilter
          .filter(List(loadedActor(1), loadedActor(2), loadedActor(11, "Car")))
          .map(_.id) shouldBe List(2L, 11L)
    }
  }

  it should "retain added filterable actors and their required owners even when absent from the manifest" in {
    withDirectory {
      dir =>
        val owner = """{"owner":{"id":1,"classType":"Person","relation":"part_of"}}"""
        val base = List(record(1, "Person"), record(2, "Person"), record(11, "Car", owner))
        val sources = List(source(write(dir, "actors.json", base.mkString("[", ",", "]"))))
        val added =
          record(12, "Car", """{"owner":{"id":2,"classType":"Person","relation":"part_of"}}""")
        val modifications = write(
          dir,
          "modifications.json",
          s"""{"schema":"htc.modifications.v1","operations":[{"op":"add","actor":$added}]}"""
        )
        val extra = ScenarioModifications
          .prepare(sources, ScenarioModifications.Settings(List(modifications.toString)))
          .toOption
          .get
          ._2

        val summary =
          PartialFilter.prepare(sources ++ extra, selection(dir, List("1"))).toOption.get
        summary.total shouldBe 4
        summary.selected shouldBe 1
        summary.kept shouldBe 4
        summary.byReason shouldBe Map(
          "Selected" -> 2,
          "PartOfSelectedWhole" -> 1,
          "WholeOfKeptPart" -> 1
        )
    }
  }

  it should "scan mixed JSON and SQLite sources using the same class-selection rules" in {
    SqliteActorSourceFixture.withDatabase {
      connection =>
        SqliteActorSourceFixture.insertActor(connection, id = 2, seq = 1)
    } {
      database =>
        withDirectory {
          dir =>
            val json = source(write(dir, "actors.json", s"[${record(1, "example.Model")}]"))
            val settings = selection(dir, List("2")).copy(filterableClasses = Set("Model"))
            val summary = PartialFilter
              .prepare(List(json, source(database, DataSourceTypeEnum.sqlite)), settings)
              .toOption
              .get
            summary.total shouldBe 2
            summary.filterable shouldBe 2
            summary.kept shouldBe 1
            summary.byClass shouldBe Map("example.Model" -> Map("Selected" -> 1))
            PartialFilter
              .filter(List(loadedActor(1, "example.Model"), loadedActor(2, "example.Model")))
              .map(_.id) shouldBe List(2L)
        }
    }
  }

  it should "preserve per-class reason counts and cap report diagnostics without changing selection totals" in {
    withDirectory {
      dir =>
        val owner = """{"owner":{"id":1,"classType":"Person","relation":"part_of"}}"""
        val records = List(record(1, "Person"), record(11, "Car", owner), record(12, "Car", owner))
        val sources = List(source(write(dir, "actors.json", records.mkString("[", ",", "]"))))
        val missing = (101 to 205).reverse.map(_.toString).toList
        val unmatched = (0 until 105)
          .map(
            index => s"bad-$index"
          )
          .toList
        val settings = selection(dir, "1" :: (missing ++ unmatched))
        val summary = PartialFilter.prepare(sources, settings).toOption.get
        summary.selected shouldBe 106
        summary.selectedNotFound shouldBe (101L to 200L).toList
        summary.nonNumericUnits shouldBe unmatched.take(100)
        summary.byClass shouldBe Map(
          "Person" -> Map("Selected" -> 1),
          "Car" -> Map("PartOfSelectedWhole" -> 2)
        )
        val report = PartialFilter.writeReport(settings, summary).get
        report shouldBe dir.resolve("selection.partial-report.json").toString
        val tree = mapper.readTree(Path.of(report).toFile)
        tree.get("schema").asText() shouldBe "htc.partial.report.v1"
        tree.get("selected_units").asInt() shouldBe 106
        tree.get("kept_by_class").get("Car").get("PartOfSelectedWhole").asInt() shouldBe 2
        tree.get("selected_not_found").size() shouldBe 100
        tree.get("non_numeric_units").size() shouldBe 100
    }
  }

  it should "limit composition errors to ten messages and keep an unprepared filter disabled" in {
    withDirectory {
      dir =>
        val records = (1 to 12).map(
          id =>
            record(id, "Car", """{"owner":{"id":999,"classType":"Person","relation":"part_of"}}""")
        )
        val sources = List(source(write(dir, "actors.json", records.mkString("[", ",", "]"))))
        val error = PartialFilter.prepare(sources, selection(dir, List("1"))).left.toOption.get
        error should startWith("invalid composition relations: ")
        error should endWith(" (+2 more)")
        "which does not exist".r.findAllIn(error).size shouldBe 10
        PartialFilter.enabled shouldBe false
        PartialFilter.prepared shouldBe false
    }
  }

  it should "retain the active selection after preparation errors until explicitly cleared" in {
    withDirectory {
      dir =>
        val sources = List(source(write(dir, "actors.json", s"[${record(1, "Person")}]")))
        val settings = selection(dir, List("1"))
        PartialFilter.prepare(sources, settings).isRight shouldBe true
        PartialFilter
          .prepare(sources, settings.copy(selectionFile = dir.resolve("missing.json").toString))
          .isLeft shouldBe true
        val broken = source(write(dir, "broken.json", "["))
        intercept[Exception](PartialFilter.prepare(List(broken), settings))
        PartialFilter.enabled shouldBe true
        PartialFilter.prepared shouldBe true
        PartialFilter.filter(List(loadedActor(1), loadedActor(2))).map(_.id) shouldBe List(1L)
        PartialFilter.clear()
        PartialFilter.enabled shouldBe false
        PartialFilter.prepared shouldBe false
    }
  }

  it should "honor an explicit report path and leave selection active when report writing fails" in {
    withDirectory {
      dir =>
        val settings = selection(dir, List("1"))
        val sources = List(source(write(dir, "actors.json", s"[${record(1, "Person")}]")))
        val summary = PartialFilter.prepare(sources, settings).toOption.get
        val explicit = dir.resolve("custom.json").toString
        PartialFilter.writeReport(settings.copy(reportFile = explicit), summary) shouldBe Some(
          explicit
        )
        val failedReport = PartialFilter.writeReport(
          settings.copy(reportFile = dir.resolve("missing/report.json").toString),
          summary
        )
        failedReport shouldBe None
        PartialFilter.filter(List(loadedActor(1), loadedActor(2))).map(_.id) shouldBe List(1L)
    }
  }
}
