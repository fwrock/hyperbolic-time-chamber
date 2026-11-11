package org.interscity.htc
package core.actor.manager.report

import java.util.UUID

/** Generated once by the report manager, then passed to every local and remote sink. */
final case class ReportRunIdentity(scenarioId: String, simulationId: String)

object ReportRunIdentity {
  def create(scenarioId: String, executionId: Option[String] = None): ReportRunIdentity = {
    val scenario = Option(scenarioId).filter(_.nonEmpty).getOrElse("sim")
    // A scenario's seed and modeled start time must never determine execution identity.
    val prefix = scenario.replaceAll("[^a-zA-Z0-9_-]", "_").take(80)
    executionId.foreach(id => require(id.matches("[A-Za-z0-9_-]{1,128}"), "Invalid report execution ID"))
    ReportRunIdentity(scenario, executionId.getOrElse(s"${prefix}_${UUID.randomUUID()}"))
  }
}
