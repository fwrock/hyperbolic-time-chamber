package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.{ActorDataSimulation, ActorSimulation, ShardActorId}
import core.entity.configuration.{ActorDataSource, DataSource}
import core.enumeration.{CreationTypeEnum, DataSourceTypeEnum}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ActorLoadFilterSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  override def afterEach(): Unit = ActorLoadFilter.clear()
  private def actor(typ: String) = ActorSimulation(7L, null, typ,
    data = ActorDataSimulation("custom.State", Map("value" -> 42)))
  private def source(typ: String) = ActorDataSource(typ, typ, CreationTypeEnum.LoadBalancedDistributed,
    DataSource(DataSourceTypeEnum.json, Map("path" -> "unchanged.json")))

  "the generic filter" should "leave every model untouched by default" in {
    val original = actor("factory.Worker")
    ActorLoadFilter.transform(original).get should be theSameInstanceAs original
    ActorLoadFilter.settings(ConfigFactory.empty()) shouldBe ActorLoadFilter.Settings()
  }

  it should "exclude arbitrary domain classes using short, relative or qualified names" in {
    val settings = ActorLoadFilter.Settings(excludedClasses = Set("Worker", "custom.energy.Sensor"))
    ActorLoadFilter.transform(actor("factory.Worker"), settings) shouldBe None
    ActorLoadFilter.transform(actor("org.example.custom.energy.Sensor"), settings) shouldBe None
    ActorLoadFilter.transform(actor("factory.WorkerSupervisor"), settings).nonEmpty shouldBe true
    ActorLoadFilter.sources(List(source("factory.Worker"), source("factory.Machine")), settings)
      .map(_.classType) shouldBe List("factory.Machine")
  }

  it should "apply an allow-list with exclusions taking precedence" in {
    val settings = ActorLoadFilter.Settings(includedClasses = Set("Worker", "Sensor"), excludedClasses = Set("Worker"))
    ActorLoadFilter.transform(actor("factory.Worker"), settings) shouldBe None
    ActorLoadFilter.transform(actor("factory.Machine"), settings) shouldBe None
    ActorLoadFilter.transform(actor("custom.Sensor"), settings).nonEmpty shouldBe true
  }

  it should "reject a retained record that explicitly depends on an excluded class" in {
    val original = actor("factory.Worker").copy(relationships = Map("machine" -> ShardActorId(9L, "factory.Machine")))
    intercept[IllegalArgumentException] {
      ActorLoadFilter.transform(original, ActorLoadFilter.Settings(excludedClasses = Set("Machine")))
    }.getMessage should include("references an excluded class")
  }

  it should "run model-owned transforms before checking retained dependencies" in {
    val original = actor("factory.Worker").copy(relationships = Map("machine" -> ShardActorId(9L, "factory.Machine")))
    val transform = new ActorLoadTransform {
      override def transform(record: ActorSimulation): ActorSimulation = record.copy(relationships = Map.empty)
    }
    ActorLoadFilter.transform(original, ActorLoadFilter.Settings(excludedClasses = Set("Machine"), transforms = List(transform)))
      .get.relationships shouldBe empty
    original.relationships should have size 1
  }

  it should "prevent transforms from changing identity or actor class" in {
    val transform = new ActorLoadTransform {
      override def transform(record: ActorSimulation): ActorSimulation = record.copy(id = 999L)
    }
    intercept[IllegalArgumentException] {
      ActorLoadFilter.transform(actor("factory.Worker"), ActorLoadFilter.Settings(transforms = List(transform)))
    }.getMessage should include("changed actor identity/class")
  }

  "settings" should "accept HOCON lists and environment-style comma-separated strings" in {
    ActorLoadFilter.settings(ConfigFactory.parseString("htc.load-filter.exclude-classes=[Worker, Sensor]"))
      .excludedClasses shouldBe Set("Worker", "Sensor")
    ActorLoadFilter.settings(ConfigFactory.parseString("htc.load-filter.include-classes=\"Worker, Sensor\""))
      .includedClasses shouldBe Set("Worker", "Sensor")
  }

  it should "reset between a filtered and unrestricted load" in {
    ActorLoadFilter.configure(ConfigFactory.parseString("htc.load-filter.exclude-classes=[Worker]"))
    ActorLoadFilter(List(actor("factory.Worker"))) shouldBe empty
    ActorLoadFilter.configure(ConfigFactory.empty())
    ActorLoadFilter(List(actor("factory.Worker"))) should have size 1
  }
}
