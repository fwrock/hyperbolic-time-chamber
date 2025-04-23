package org.interscity.htc
package core.entity.event.control.report

import core.enumeration.ReportTypeEnum

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

import scala.collection.mutable

case class RegisterReportersEvent(
  reporters: mutable.Map[ReportTypeEnum, ActorRef]
) extends BaseEvent[DefaultBaseEventData]
