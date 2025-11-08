package org.interscity.htc
package model.hybrid.entity.event.data.subway

import core.entity.event.data.BaseEventData

import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

case class SubwayLoadPassengerData(
  people: mutable.Seq[Identify]
) extends BaseEventData
