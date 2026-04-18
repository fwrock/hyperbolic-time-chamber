package org.interscity.htc
package core.entity.actor.properties

import core.enumeration.{ CreationTypeEnum, ReportTypeEnum }

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.CreationTypeEnum.Simple

import scala.collection.mutable

case class CreatorProperties(
  entityId: String = null,
  shardId: String = null,
  loadDataManager: ActorRef = null,
  timeManagers: mutable.Map[String, ActorRef] = null,
  creatorManager: ActorRef = null,
  reporters: mutable.Map[ReportTypeEnum, ActorRef] = null,
  data: Any = null,
  actorType: CreationTypeEnum = Simple,
  /** Reference to PostLoadRegistrationCoordinator. Creators forward NeedsPostLoadRegistrationEvent
    * directly to this coordinator (not via LoadDataManager) so it accumulates registrations
    * during the loading phase. May be null if no coordinator is active.
    */
  postLoadCoordinator: ActorRef = null,
  /** Set of short class names (e.g. "hybrid.actor.BusStop") that should auto-register for the
    * post-load registration phase, sourced from simulation.json postLoadRegistrationClasses.
    * Allows including classes without modifying their actor data files.
    */
  postLoadRegistrationClasses: Set[String] = Set.empty
)
