package org.interscity.htc
package core.entity.event.control.load

/** A scenario failed a pre-flight check. Model-neutral: each model's validator reports through it. */
final case class ScenarioLoadError(message: String)
