package org.interscity.htc
package core.entity.event.control.load

import org.apache.pekko.actor.ActorRef
import core.entity.event.BaseEvent
import core.entity.configuration.ActorDataSource

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData
import org.interscity.htc.core.enumeration.ReportTypeEnum

import scala.collection.mutable

case class LoadDataSourceEvent(
  actorDataSource: ActorDataSource,
  managerRef: ActorRef,
  creatorRef: ActorRef
) extends BaseEvent[DefaultBaseEventData](actorRef = managerRef)
