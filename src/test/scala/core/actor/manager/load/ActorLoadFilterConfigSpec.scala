package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.{ ActorDataSimulation, ActorSimulation, ShardActorId }
import core.entity.configuration.LoadFilterSimulation

import com.typesafe.config.{ ConfigException, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.InvocationTargetException

class ActorLoadFilterConfigSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val transformName = classOf[ConfiguredLoadTransform].getName
  private def actor: ActorSimulation = ActorSimulation(
    7L,
    "seed",
    "factory.Worker",
    data = ActorDataSimulation("custom.State", Map.empty)
  )

  override def beforeEach(): Unit = ActorLoadFilter.clear()
  override def afterEach(): Unit = ActorLoadFilter.clear()

  private def step(f: ActorSimulation => ActorSimulation): ActorLoadTransform =
    new ActorLoadTransform {
      override def transform(record: ActorSimulation): ActorSimulation = f(record)
    }

  "load filter configuration" should "construct each listed transform with the supplied Config and preserve duplicates" in {
    val config = ConfigFactory.parseString(s"""
      htc.load-filter.transforms = [" $transformName ", "$transformName"]
      htc.test.load-transform.prefix = "test:"
    """)
    val settings = ActorLoadFilter.settings(config)

    settings.transforms should have size 2
    (settings.transforms.head eq settings.transforms(1)) shouldBe false
    ActorLoadFilter.transform(actor, settings).get.name shouldBe "test:test:seed"
    ActorLoadFilter(actor :: Nil).head.name shouldBe "seed"
  }

  it should "ignore application transform names when an explicitly empty scenario block replaces them" in {
    val config = ConfigFactory.parseString("""
      htc.load-filter {
        include-classes = [Worker]
        exclude-classes = [Sensor]
        transforms = ["missing.Transform"]
      }
    """)
    ActorLoadFilter.settings(config, Some(LoadFilterSimulation())) shouldBe ActorLoadFilter
      .Settings()
  }

  it should "retain the published settings and reflective cause when a transform cannot be loaded" in {
    ActorLoadFilter.configure(ConfigFactory.parseString("htc.load-filter.exclude-classes=[Worker]"))
    val error = intercept[IllegalArgumentException] {
      ActorLoadFilter.configure(
        ConfigFactory.parseString("htc.load-filter.transforms=[\"missing.Transform\"]")
      )
    }

    error.getMessage shouldBe "cannot initialize load transform missing.Transform"
    error.getCause shouldBe a[ClassNotFoundException]
    ActorLoadFilter(actor :: Nil) shouldBe Nil
  }

  it should "preserve constructor failures inside the original InvocationTargetException" in {
    val config = ConfigFactory.parseString(s"""htc.load-filter.transforms=["$transformName"]""")
    val error = intercept[IllegalArgumentException](ActorLoadFilter.settings(config))

    error.getMessage shouldBe s"cannot initialize load transform $transformName"
    error.getCause shouldBe a[InvocationTargetException]
    error.getCause.getCause shouldBe a[ConfigException.Missing]
  }

  "the transformation pipeline" should "feed each step's output to the next without mutating the input record" in {
    val original = actor
    val first = step(_.copy(name = "stage-1"))
    val second = step(
      record => record.copy(name = s"${record.name}/stage-2")
    )
    val settings = ActorLoadFilter.Settings(transforms = List(first, second))

    ActorLoadFilter.transform(original, settings).get.name shouldBe "stage-1/stage-2"
    original.name shouldBe "seed"
  }

  it should "skip excluded records before invoking any model-owned transformation" in {
    val forbidden = step(
      _ => throw new IllegalStateException("excluded actors must not be transformed")
    )
    val settings =
      ActorLoadFilter.Settings(excludedClasses = Set("Worker"), transforms = List(forbidden))

    ActorLoadFilter.transform(actor, settings) shouldBe None
  }

  it should "reject an intermediate identity change before a later step can restore it" in {
    var restorationInvoked = false
    val change = step(_.copy(id = 999L))
    val restore = step {
      record =>
        restorationInvoked = true
        record.copy(id = 7L)
    }
    val settings = ActorLoadFilter.Settings(transforms = List(change, restore))

    intercept[IllegalArgumentException](
      ActorLoadFilter.transform(actor, settings)
    ).getMessage should include("changed actor identity/class")
    restorationInvoked shouldBe false
  }

  it should "sort and deduplicate excluded dependency classes while ignoring unnamed classes" in {
    val original = actor.copy(relationships =
      Map(
        "sensor" -> ShardActorId(1, "factory.Sensor"),
        "machine-1" -> ShardActorId(2, "factory.Machine"),
        "machine-2" -> ShardActorId(3, "factory.Machine"),
        "empty" -> ShardActorId(4, ""),
        "null" -> ShardActorId(5, null)
      )
    )
    val settings = ActorLoadFilter.Settings(excludedClasses = Set("Machine", "Sensor"))
    val error = intercept[IllegalArgumentException](ActorLoadFilter.transform(original, settings))

    error.getMessage shouldBe
      "requirement failed: actor 7 (factory.Worker) references an excluded class: factory.Machine, factory.Sensor"
  }
}
