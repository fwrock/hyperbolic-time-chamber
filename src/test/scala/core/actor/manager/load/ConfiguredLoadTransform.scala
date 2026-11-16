package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.ActorSimulation

import com.typesafe.config.Config

/** Exercises the public Config-constructor contract used by reflection-based load filtering. */
class ConfiguredLoadTransform(config: Config) extends ActorLoadTransform {
  private val prefix = config.getString("htc.test.load-transform.prefix")

  override def transform(actor: ActorSimulation): ActorSimulation =
    actor.copy(name = prefix + actor.name)
}
