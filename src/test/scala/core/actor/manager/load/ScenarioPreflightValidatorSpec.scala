package org.interscity.htc
package core.actor.manager.load

import core.entity.configuration.{ ActorDataSource, DataSource }
import core.enumeration.{ CreationTypeEnum, DataSourceTypeEnum, LoadingStrategyEnum }
import core.util.JsonUtil

import org.interscity.htc.model.hybrid.decision.ScenarioValidationContext
import org.interscity.htc.model.hybrid.entity.state.PersonState
import org.interscity.htc.model.hybrid.entity.state.plan.{ Activity, AtTick, ConcreteMode, ModeDecisionRequest, PendingDecision, PlanElement }

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*

/** Exercises the exact logic `LoadDataManager`/`ProgressiveLoadDataManager` call before creating
  * any actor: streaming Person source files, converting each entity's raw content to `PersonState`
  * the same way `BaseActor.onInitialize` does, and validating every distinct `strategyId` found via
  * `ScenarioLoadValidator.validateModeDecisionEngines`.
  */
class ScenarioPreflightValidatorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempFiles: List[Path] = List.empty

  override def afterEach(): Unit = {
    tempFiles.foreach(p => Files.deleteIfExists(p))
    tempFiles = List.empty
  }

  private val availableCtx = ScenarioValidationContext(
    transitRouteDataAvailable = true,
    transitMapDataAvailable = true
  )
  private val noTransitRoutesCtx = ScenarioValidationContext(
    transitRouteDataAvailable = false,
    transitMapDataAvailable = true
  )

  private def personPlan(strategyId: String): List[PlanElement] =
    List(
      Activity("home", "n1", AtTick(0L)),
      PendingDecision(
        ModeDecisionRequest(allowedModes = Set(ConcreteMode.Walk), strategyId = strategyId)
      )
    )

  /** Writes a minimal scenario file containing `personCount` Person entities, each with a
    * `PendingDecision` referencing `strategyId`. Mirrors the native scenario JSON shape:
    * a top-level array of `{ id, typeActor, data: { dataType, content } }` entries, where
    * `content` is the raw (Jackson-serialized) `PersonState`, exactly what
    * `core.util.JsonStreamingUtil` parses and `core.actor.BaseActor.onInitialize` later converts
    * via `JsonUtil.convertValue[PersonState]`.
    */
  private def writePersonSourceFile(strategyId: String, personCount: Int = 1): ActorDataSource = {
    val entries = (1 to personCount).map {
      i =>
        val state = PersonState(originalPlan = personPlan(strategyId))
        val contentJson = JsonUtil.toJson(state)
        s"""{"id":"person-$i","typeActor":"hybrid.actor.Person","data":{"dataType":"model.hybrid.entity.state.PersonState","content":$contentJson}}"""
    }
    val fileContent = entries.mkString("[", ",", "]")

    val file = Files.createTempFile("scenario-preflight-spec", ".json")
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

  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, 10.seconds)

  "isPersonSource" should "recognize a Person data source regardless of package-prefix form" in {
    val withPrefix = ActorDataSource(
      id = "p1",
      classType = "org.interscity.htc.model.hybrid.actor.Person",
      creationType = CreationTypeEnum.LoadBalancedDistributed,
      dataSource = DataSource(sourceType = DataSourceTypeEnum.json, info = Map.empty)
    )
    val withoutPrefix = withPrefix.copy(classType = "hybrid.actor.Person")

    ScenarioPreflightValidator.isPersonSource(withPrefix) shouldBe true
    ScenarioPreflightValidator.isPersonSource(withoutPrefix) shouldBe true
  }

  it should "reject a non-Person data source" in {
    val nodeSource = ActorDataSource(
      id = "n1",
      classType = "hybrid.actor.Node",
      creationType = CreationTypeEnum.LoadBalancedDistributed,
      dataSource = DataSource(sourceType = DataSourceTypeEnum.json, info = Map.empty)
    )

    ScenarioPreflightValidator.isPersonSource(nodeSource) shouldBe false
  }

  "validate" should "succeed for a scenario where every referenced strategyId is registered and available" in {
    val source = writePersonSourceFile(strategyId = "travel-time", personCount = 5)

    await(ScenarioPreflightValidator.validate(List(source), availableCtx)) shouldBe Right(())
  }

  it should "abort with the unknown-strategy error when a Person references an unregistered strategyId" in {
    val source = writePersonSourceFile(strategyId = "no-such-strategy")

    val result = await(ScenarioPreflightValidator.validate(List(source), availableCtx))

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("no-such-strategy")
  }

  it should "abort when 'raptor' is referenced but transit route data is unavailable" in {
    val source = writePersonSourceFile(strategyId = "raptor")

    val result = await(ScenarioPreflightValidator.validate(List(source), noTransitRoutesCtx))

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("raptor")
  }

  it should "succeed for an empty list of Person sources (nothing to validate)" in {
    await(ScenarioPreflightValidator.validate(Nil, noTransitRoutesCtx)) shouldBe Right(())
  }

  it should "short-circuit on the first bad strategyId without needing to read every source" in {
    val badSource = writePersonSourceFile(strategyId = "no-such-strategy")
    // A source whose file path doesn't exist — if validate tried to read it, the Future would
    // fail with an I/O exception instead of completing with a Left(ScenarioLoadError).
    val unreachableSource = badSource.copy(
      id = "unreachable",
      dataSource = badSource.dataSource.copy(info = Map("path" -> "/nonexistent/path/should-not-be-read.json"))
    )

    val result = await(ScenarioPreflightValidator.validate(List(badSource, unreachableSource), availableCtx))

    result.isLeft shouldBe true
  }
}
