package org.interscity.htc
package core.actor.manager.loadbalance.migration

import core.runtime.accounting.RuntimeBirthOrigin

case class SerializedMigrationInput(serializerId: Int, manifest: String, payload: String)
case class ConservativeMigrationCheckpoint(
  epoch: String,
  incarnation: String,
  requestId: String,
  ownerPath: String,
  coordinatorPath: String,
  sourcePath: String,
  inputs: Vector[SerializedMigrationInput],
  generation: Long,
  scheduleRevision: Long,
  spontaneousFinishSent: Boolean,
  effectOrdinal: Long,
  horizon: Long,
  retirementFence: Long,
  finalizedHorizon: Option[Long],
  birth: Option[RuntimeBirthOrigin]
)
