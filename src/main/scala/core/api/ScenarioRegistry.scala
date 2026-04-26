package org.interscity.htc
package core.api

import org.interscity.htc.core.entity.configuration.Simulation
import org.interscity.htc.core.util.JsonUtil
import org.slf4j.LoggerFactory

import java.io.File
import scala.io.Source
import scala.util.Try

/** Optional metadata file placed alongside simulation.json.
  * Any field is optional — the file may contain just a subset.
  *
  * Example metadata.json:
  * {{{
  * {
  *   "description": "Toulouse 1% sample — morning peak",
  *   "version": "2.1",
  *   "author": "HTC Team",
  *   "tags": ["toulouse", "urban", "1pct"],
  *   "notes": "Requires at least 4 worker nodes"
  * }
  * }}}
  */
case class ScenarioMeta(
  description: Option[String] = None,
  version: Option[String] = None,
  author: Option[String] = None,
  tags: List[String] = List.empty,
  notes: Option[String] = None
)

/** Lightweight summary of a scenario — used in the list endpoint. */
case class ScenarioSummary(
  name: String,
  hasMetadata: Boolean,
  meta: Option[ScenarioMeta],
  simulationName: Option[String],
  simulationDescription: Option[String],
  duration: Option[Long],
  timeUnit: Option[String],
  startTick: Option[Long],
  endTick: Option[Long]
)

/** Full scenario detail — used in the single-scenario endpoint. */
case class ScenarioDetail(
  name: String,
  hasMetadata: Boolean,
  meta: Option[ScenarioMeta],
  simulation: Simulation
)

/** Scans a configured directory for available simulation scenarios.
  *
  * Expected directory layout:
  * {{{
  *   <scenarios-dir>/
  *     scenario_a/
  *       simulation.json      ← required
  *       metadata.json        ← optional
  *     scenario_b/
  *       simulation.json
  * }}}
  *
  * The directory is configured via (highest priority first):
  *   1. `HTC_SCENARIOS_DIR` env var
  *   2. `htc.api.scenarios-dir` in application.conf
  *   3. Default: `/app/simulations`
  */
object ScenarioRegistry {

  private val logger = LoggerFactory.getLogger(getClass)

  @volatile private var scenariosDir: String = "/app/simulations"

  def configure(dir: String): Unit = {
    scenariosDir = dir
    logger.info(s"ScenarioRegistry: scenarios directory set to '$dir'")
  }

  def directory: String = scenariosDir
  
  def listScenarios(): List[ScenarioSummary] = {
    val dir = new File(scenariosDir)
    if (!dir.exists() || !dir.isDirectory) {
      logger.warn(s"ScenarioRegistry: directory '$scenariosDir' does not exist or is not a directory")
      return List.empty
    }

    Option(dir.listFiles())
      .getOrElse(Array.empty[File])
      .filter(f => f.isDirectory && new File(f, "simulation.json").exists())
      .sortBy(_.getName)
      .map { scenarioDir =>
        val meta = loadMeta(new File(scenarioDir, "metadata.json"))
        val sim  = loadSimulation(new File(scenarioDir, "simulation.json"))
        ScenarioSummary(
          name                = scenarioDir.getName,
          hasMetadata         = new File(scenarioDir, "metadata.json").exists(),
          meta                = meta,
          simulationName      = sim.map(_.name),
          simulationDescription = sim.map(_.description),
          duration            = sim.map(_.duration),
          timeUnit            = sim.map(_.timeUnit),
          startTick           = sim.map(_.startTick),
          endTick             = sim.flatMap(_.endTick)
        )
      }
      .toList
  }
  
  def getScenario(name: String): Either[String, ScenarioDetail] = {
    val scenarioDir = new File(scenariosDir, name)
    if (!scenarioDir.exists() || !scenarioDir.isDirectory)
      return Left(s"Scenario '$name' not found in '$scenariosDir'")

    val simFile = new File(scenarioDir, "simulation.json")
    if (!simFile.exists())
      return Left(s"Scenario '$name' has no simulation.json")

    loadSimulation(simFile) match {
      case None      => Left(s"Failed to parse simulation.json for scenario '$name'")
      case Some(sim) =>
        val meta = loadMeta(new File(scenarioDir, "metadata.json"))
        Right(ScenarioDetail(
          name        = name,
          hasMetadata = new File(scenarioDir, "metadata.json").exists(),
          meta        = meta,
          simulation  = sim
        ))
    }
  }
  
  def simulationFilePath(name: String): Option[String] = {
    val f = new File(new File(scenariosDir, name), "simulation.json")
    if (f.exists()) Some(f.getAbsolutePath) else None
  }
  
  private def loadSimulation(file: File): Option[Simulation] =
    Try {
      val content = Source.fromFile(file).mkString
      JsonUtil.fromJson[Simulation](content)
    }.recover { case e =>
      logger.warn(s"ScenarioRegistry: failed to parse ${file.getAbsolutePath}: ${e.getMessage}")
      throw e
    }.toOption

  private def loadMeta(file: File): Option[ScenarioMeta] = {
    if (!file.exists()) return None
    Try {
      val content = Source.fromFile(file).mkString
      JsonUtil.fromJson[ScenarioMeta](content)
    }.recover { case e =>
      logger.warn(s"ScenarioRegistry: failed to parse ${file.getAbsolutePath}: ${e.getMessage}")
      throw e
    }.toOption
  }
}
