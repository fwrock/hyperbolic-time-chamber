package org.interscity.htc
package core.actor.manager.load

import core.entity.configuration.{ ActorDataSource, DataSource }
import core.entity.event.control.load.{ EagerLoadDataReadyEvent, LoadDataEvent, ScenarioPreflightValidationFailedEvent }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum, LoadingStrategyEnum, ReportTypeEnum }
import core.util.JsonUtil

import org.interscity.htc.model.hybrid.entity.state.PersonState
import org.interscity.htc.model.hybrid.entity.state.plan.{ Activity, AtTick, ConcreteMode, ModeDecisionRequest, PendingDecision, PlanElement }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.collection.mutable
import scala.concurrent.duration.*

/** Proves the scenario-wide mode-decision-engine pre-flight check (see
  * `ScenarioPreflightValidator`) actually gates `LoadDataManager`'s real EAGER load path: an
  * invalid `strategyId` (or a `raptor` decision with no transit route data) must abort the whole
  * scenario load — `SimulationManager` gets `ScenarioPreflightValidationFailedEvent`, never
  * `EagerLoadDataReadyEvent` — before `LoadDataManager` creates `creatorRef`/`creatorPoolRef`, and
  * therefore before a single Person (or any other) actor is created.
  *
  * `pekko.actor.provider = local` avoids cluster sharding entirely — safe here specifically
  * because the abort path returns before `getSelfProxy`/`createCreatorLoadData` (both
  * cluster-dependent) are ever reached. A "valid scenario proceeds" test would need those and is
  * left to `ScenarioPreflightValidatorSpec` (validates the exact function `LoadDataManager` calls)
  * plus manual/cluster-enabled verification — see PR notes.
  */
class LoadDataManagerScenarioPreflightSpec
    extends TestKit(
      ActorSystem(
        "LoadDataManagerScenarioPreflightSpec",
        ConfigFactory
          .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
          .withFallback(ConfigFactory.load())
      )
    )
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  private var tempFiles: List[Path] = List.empty

  override def afterAll(): Unit = {
    tempFiles.foreach(p => Files.deleteIfExists(p))
    TestKit.shutdownActorSystem(system)
  }

  private def personPlan(strategyId: String): List[PlanElement] =
    List(
      Activity("home", "n1", AtTick(0L)),
      PendingDecision(
        ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = strategyId)
      )
    )

  private def writePersonSourceFile(strategyId: String): ActorDataSource = {
    val state = PersonState(originalPlan = personPlan(strategyId))
    val contentJson = JsonUtil.toJson(state)
    val fileContent =
      s"""[{"id":"person-1","typeActor":"hybrid.actor.Person","data":{"dataType":"model.hybrid.entity.state.PersonState","content":$contentJson}}]"""

    val file = Files.createTempFile("load-data-manager-preflight-spec", ".json")
    Files.writeString(file, fileContent)
    tempFiles = file :: tempFiles

    ActorDataSource(
      id = s"persons-$strategyId",
      classType = "hybrid.actor.Person",
      creationType = CreationTypeEnum.LoadBalancedDistributed,
      dataSource = DataSource(
        sourceType = DataSourceTypeEnum.json,
        info = Map("path" -> file.toAbsolutePath.toString)
      ),
      loadingStrategy = LoadingStrategyEnum.EAGER
    )
  }

  private def newLoadDataManager(simulationManager: TestProbe) =
    system.actorOf(
      LoadDataManager.props(
        timeSingletonManager = TestProbe().ref,
        poolTimeManager = TestProbe().ref,
        simulationManager = simulationManager.ref,
        poolReporters = mutable.Map.empty[ReportTypeEnum, org.apache.pekko.actor.ActorRef]
      )
    )

  "LoadDataManager" should "abort scenario load and never signal eager-load-ready when a Person source references an unregistered strategyId" in {
    val simulationManager = TestProbe()
    val loadDataManager = newLoadDataManager(simulationManager)

    val source = writePersonSourceFile(strategyId = "no-such-strategy")
    loadDataManager ! LoadDataEvent(actorRef = simulationManager.ref, actorsDataSources = List(source))

    val failure = simulationManager.expectMsgType[ScenarioPreflightValidationFailedEvent](10.seconds)
    failure.error.message should include("no-such-strategy")

    simulationManager.expectNoMessage(500.millis)
  }

  it should "abort scenario load when a Person source references 'raptor' with no transit route data configured" in {
    val simulationManager = TestProbe()
    val loadDataManager = newLoadDataManager(simulationManager)

    val source = writePersonSourceFile(strategyId = "raptor")
    loadDataManager ! LoadDataEvent(actorRef = simulationManager.ref, actorsDataSources = List(source))

    val failure = simulationManager.expectMsgType[ScenarioPreflightValidationFailedEvent](10.seconds)
    failure.error.message should include("raptor")

    simulationManager.expectNoMessage(500.millis)
  }

  it should "not run the pre-flight check at all when there are no Person sources (nothing to validate)" in {
    val simulationManager = TestProbe()
    val loadDataManager = newLoadDataManager(simulationManager)

    loadDataManager ! LoadDataEvent(actorRef = simulationManager.ref, actorsDataSources = List.empty)

    simulationManager.expectNoMessage(500.millis)
  }
}
