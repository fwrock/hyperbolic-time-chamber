package org.interscity.htc
package core.partial

/** Retains only the metadata needed to calculate partial selection after load transformations.
  *
  * Total counts every surviving record, including infrastructure and repeated ids. Classes keep the
  * last filterable class for each id; links retain the scan order and may contain duplicate edges.
  * Actor payloads are not retained.
  */
private[partial] final case class PartialActorIndex(
  total: Int,
  classesById: Map[Long, String],
  links: Vector[(Long, Long)]
)
