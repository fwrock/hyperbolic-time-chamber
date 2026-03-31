package org.interscity.htc
package core.entity.control.loadbalance

/** Average flow vector representing the mean direction and speed of entity movement within a shard.
  *
  * Used for predictive load balancing: if many entities are flowing toward another shard, we can
  * anticipate that shard's future load increase.
  *
  * @param dx
  *   Average displacement in X per second
  * @param dy
  *   Average displacement in Y per second
  * @param sampleCount
  *   Number of entities contributing to this vector
  */
case class FlowVector(
  dx: Double = 0.0,
  dy: Double = 0.0,
  sampleCount: Int = 0
) {

  /** Magnitude of the flow vector */
  def magnitude: Double = math.sqrt(dx * dx + dy * dy)

  /** Normalized direction vector (unit vector). Returns zero vector if magnitude is zero. */
  def direction: (Double, Double) = {
    val m = magnitude
    if (m < 1e-10) (0.0, 0.0)
    else (dx / m, dy / m)
  }

  /** Combine with another flow vector (weighted merge). */
  def merge(other: FlowVector): FlowVector = {
    val totalSamples = sampleCount + other.sampleCount
    if (totalSamples == 0) FlowVector.Zero
    else
      FlowVector(
        dx = (dx * sampleCount + other.dx * other.sampleCount) / totalSamples,
        dy = (dy * sampleCount + other.dy * other.sampleCount) / totalSamples,
        sampleCount = totalSamples
      )
  }
}

object FlowVector {
  val Zero: FlowVector = FlowVector(0.0, 0.0, 0)
}
