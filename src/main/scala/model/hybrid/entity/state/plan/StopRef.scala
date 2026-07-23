package org.interscity.htc
package model.hybrid.entity.state.plan

/** Reference to a transit stop actor a [[TransitLeg]] boards at or alights from.
  *
  * Holds only entity/shard-style identifiers (id, class type, node id) — never an `ActorRef` —
  * consistent with the project rule that simulation state must not carry live actor references.
  */
final case class StopRef(
  actorId: String,
  actorClassType: String,
  nodeId: String
)
