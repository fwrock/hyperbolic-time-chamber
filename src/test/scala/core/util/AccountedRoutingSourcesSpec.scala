package org.interscity.htc
package core.util

import core.entity.configuration.{ ActorDataSource, DataSource, Simulation }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum, EntityLifecycleEnum, LoadingStrategyEnum }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.LocalDateTime

class AccountedRoutingSourcesSpec extends AnyFlatSpec with Matchers {
  private def withSource(ids: Seq[Long])(check: ActorDataSource => Unit): Unit = {
    val path = Files.createTempFile("htc-routing-source-", ".json")
    val records = ids.map(id => s"""{"id":$id,"typeActor":"hybrid.actor.RoutingService","data":{"dataType":"model.hybrid.entity.state.RoutingServiceState","content":{}}}""")
    Files.writeString(path, records.mkString("[", ",", "]"))
    try check(ActorDataSource("routing", "hybrid.actor.RoutingService",
      CreationTypeEnum.LoadBalancedDistributed, DataSource(DataSourceTypeEnum.json, Map("path" -> path.toString))))
    finally Files.deleteIfExists(path)
  }

  private def simulation(sources: ActorDataSource*): Simulation =
    Simulation("routing", "routing", startTick = 0L, startRealTime = LocalDateTime.MIN,
      timeUnit = "second", timeStep = 1L, duration = 100L, actorsDataSources = sources.toList)

  "accounted routing startup" should "accept exactly the configured eager static workers" in {
    withSource(Seq(7L, 8L)) { source =>
      SimulationUtil.validateAccountedRoutingSources(simulation(source), Vector(7L, 8L))
    }
  }

  it should "reject duplicates within a file and across sources before actors initialize" in {
    withSource(Seq(7L, 7L)) { source =>
      intercept[IllegalArgumentException] {
        SimulationUtil.validateAccountedRoutingSources(simulation(source), Vector(7L))
      }.getMessage should include ("must be unique")
    }
    withSource(Seq(7L)) { source =>
      intercept[IllegalArgumentException] {
        SimulationUtil.validateAccountedRoutingSources(simulation(source, source.copy(id = "other")), Vector(7L))
      }.getMessage should include ("must be unique")
    }
  }

  it should "reject absent, missing and extra workers" in {
    intercept[IllegalArgumentException] {
      SimulationUtil.validateAccountedRoutingSources(simulation(), Vector(7L))
    }
    withSource(Seq(7L)) { source =>
      Seq(Vector(7L, 8L), Vector(8L)).foreach { expected =>
        intercept[IllegalArgumentException] {
          SimulationUtil.validateAccountedRoutingSources(simulation(source), expected)
        }.getMessage should include ("must exactly match")
      }
    }
  }

  it should "reject progressive or ephemeral routing workers" in {
    withSource(Seq(7L)) { source =>
      LoadingStrategyEnum.values.filterNot(_ == LoadingStrategyEnum.EAGER).foreach { strategy =>
        intercept[IllegalArgumentException] {
          SimulationUtil.validateAccountedRoutingSources(simulation(source.copy(loadingStrategy = strategy)), Vector(7L))
        }.getMessage should include ("EAGER")
      }
      EntityLifecycleEnum.values.filterNot(_ == EntityLifecycleEnum.STATIC).foreach { lifecycle =>
        intercept[IllegalArgumentException] {
          SimulationUtil.validateAccountedRoutingSources(simulation(source.copy(entityLifecycle = lifecycle)), Vector(7L))
        }.getMessage should include ("STATIC")
      }
    }
  }

  it should "reject workers that cannot receive sharded routing queries" in {
    withSource(Seq(7L)) { source =>
      intercept[IllegalArgumentException] {
        SimulationUtil.validateAccountedRoutingSources(
          simulation(source.copy(creationType = CreationTypeEnum.PoolDistributed)), Vector(7L))
      }.getMessage should include ("LoadBalancedDistributed")
    }
  }
}
