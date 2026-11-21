package org.interscity.htc
package model.mobility.micro.model

import model.mobility.entity.state.model.VehicleInLane

object DriverRandomness {
  private def mix(value: Long): Long = {
    val a = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L
    val b = (a ^ (a >>> 27)) * 0x94d049bb133111ebL
    b ^ (b >>> 31)
  }

  // Logical identity and physical step survive replay, migration and lane/Link handoffs.
  def uniform(seed: Long, vehicleId: Long, physicalStep: Long): Double =
    ((mix(seed ^ mix(vehicleId) ^ mix(physicalStep ^ 0x9e3779b97f4a7c15L)) >>> 11).toDouble /
      (1L << 53).toDouble)

  def velocity(vehicle: VehicleInLane, candidate: Double, physicalStep: Long, deltaSeconds: Double): Double = {
    if (vehicle.sigma == 0.0) candidate
    else {
      val loss = vehicle.sigma * math.min(candidate,vehicle.maxAcceleration) * deltaSeconds *
        uniform(vehicle.randomSeed,vehicle.actorId,physicalStep)
      // Random dawdling must not weaken a stricter deterministic safety/speed cap.
      val floor = math.min(candidate,math.max(0.0,vehicle.velocity-vehicle.maxDeceleration*deltaSeconds))
      math.max(floor,candidate-loss)
    }
  }
}
