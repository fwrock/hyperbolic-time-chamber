package org.interscity.htc
package model.supermarket.entity.state

import core.types.CoreTypes.Tick

import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum
import org.interscity.htc.model.supermarket.entity.enumeration.CashierStatusEnum.Free
import org.interscity.htc.model.supermarket.entity.model.ClientQueued

import scala.collection.mutable

case class CashierState(
  startTick: Tick = 0,
  queue: mutable.Queue[ClientQueued] = mutable.Queue[ClientQueued](),
  var status: CashierStatusEnum = Free,
  var clientInService: Option[Identify] = None
) extends BaseState(startTick = startTick)
