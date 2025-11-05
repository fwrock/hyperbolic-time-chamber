package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.{ CreationTypeEnum, ReportTypeEnum, TimeManagerTypeEnum }

import org.apache.pekko.actor.ActorRef
import org.htc.protobuf.core.entity.actor.Dependency
import org.interscity.htc.core.enumeration.CreationTypeEnum.LoadBalancedDistributed

import scala.collection.mutable

case class Properties(
  entityId: String = null,
  var resourceId: String = null,
  timeManagers: mutable.Map[String, ActorRef] = null, // Suporta múltiplos time managers
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  data: Any = null,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency](),
  actorType: CreationTypeEnum = LoadBalancedDistributed,
  defaultTimeManagerType: String = TimeManagerTypeEnum.DISCRETE_EVENT // Tipo padrão de time manager
) {
  /** Gets the time manager by type.
    * @param managerType The type of time manager (e.g., "discrete-event", "time-stepped")
    * @return The time manager ActorRef, or None if not found
    */
  def getTimeManager(managerType: String): Option[ActorRef] = {
    if (timeManagers != null) {
      timeManagers.get(managerType)
    } else {
      None
    }
  }

  /** Gets the default time manager.
    * Priority order:
    * 1. Time manager of defaultTimeManagerType from timeManagers map
    * 2. "discrete-event" time manager from timeManagers map (fallback)
    * 
    * @return The default time manager ActorRef, or null if none configured
    */
  def getDefaultTimeManager: ActorRef = {
    if (timeManagers != null) {
      // Try to get the configured default type first
      timeManagers.get(defaultTimeManagerType)
        .orElse(timeManagers.get(TimeManagerTypeEnum.DISCRETE_EVENT)) // Fallback to discrete-event
        .orNull // Return null if none found
    } else {
      null
    }
  }

  /** Gets all available time manager types.
    * @return Set of available time manager type names
    */
  def getAvailableTimeManagerTypes: Set[String] = {
    if (timeManagers != null) {
      timeManagers.keySet.toSet
    } else {
      Set.empty
    }
  }
}
