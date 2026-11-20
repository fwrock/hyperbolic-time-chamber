package org.interscity.htc
package model.hybrid.util

/** Whether a scenario can ever route a pedestrian. Only `Person` walks (PersonWalkingTripHandler and
  * the travel-time mode choice), so a scenario without one never needs the undirected pedestrian
  * graph and its landmark index.
  */
object PedestrianDemand {
  def mayWalk(classTypes: Iterable[String]): Boolean =
    classTypes.exists(classType => classType != null && classType.split('.').last == "Person")
}
