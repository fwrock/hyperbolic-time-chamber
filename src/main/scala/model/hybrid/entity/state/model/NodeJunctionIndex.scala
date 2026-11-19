package org.interscity.htc
package model.hybrid.entity.state.model

/** Lookups over a Node's immutable junction tables, built once per state instance. Each accessor
  * returns what the equivalent `filter`/`find` over the original list returned, in list order.
  */
final class NodeJunctionIndex(
  movements: List[JunctionMovement],
  bindings: List[MovementSignalBinding],
  conflicts: List[JunctionConflict]
) {
  private val pairKey = (in: Long, out: Long) => (in, out)
  private val movementsByPair = movements.groupBy(m => pairKey(m.incomingLinkId, m.outgoingLinkId))
  private val movementsByOutgoing = movements.groupBy(_.outgoingLinkId)
  private val movementByConnectorMap = movements.reverseIterator.map(m => m.connectorLinkId -> m).toMap
  private val bindingsByPair = bindings.groupBy(b => pairKey(b.incomingLinkId, b.outgoingLinkId))
  private val bindingsByOrigin = bindings.groupBy(_.origin)
  private val conflictsByConnector: Map[Long, List[JunctionConflict]] =
    conflicts.flatMap(c => List(c.connectorA, c.connectorB).distinct.map(_ -> c)).groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }
  private val conflictsByPriority = conflicts.flatMap(c => c.priorityConnector.map(_ -> c)).groupBy(_._1)
    .map { case (k, v) => k -> v.map(_._2) }

  val conflictConnectors: List[Long] = conflicts.flatMap(r => List(r.connectorA, r.connectorB)).distinct.sorted

  def movementsFor(incoming: Long, outgoing: Long): List[JunctionMovement] =
    movementsByPair.getOrElse(pairKey(incoming, outgoing), Nil)
  def movementsInto(outgoing: Long): List[JunctionMovement] = movementsByOutgoing.getOrElse(outgoing, Nil)
  def movementOf(connector: Long): Option[JunctionMovement] = movementByConnectorMap.get(connector)
  def bindingsFor(incoming: Long, outgoing: Long): List[MovementSignalBinding] =
    bindingsByPair.getOrElse(pairKey(incoming, outgoing), Nil)
  def bindingsOfOrigin(origin: Long): List[MovementSignalBinding] = bindingsByOrigin.getOrElse(origin, Nil)
  def conflictsOf(connector: Long): List[JunctionConflict] = conflictsByConnector.getOrElse(connector, Nil)
  def conflictsWithPriority(connector: Long): List[JunctionConflict] = conflictsByPriority.getOrElse(connector, Nil)
}
