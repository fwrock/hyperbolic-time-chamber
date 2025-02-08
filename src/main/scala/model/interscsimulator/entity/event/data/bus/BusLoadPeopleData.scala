package org.interscity.htc
package model.interscsimulator.entity.event.data.bus

import org.interscity.htc.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.data.BaseEventData

import scala.collection.mutable

case class BusLoadPeopleData (
  people: mutable.Seq[Identify]
                             ) extends BaseEventData
