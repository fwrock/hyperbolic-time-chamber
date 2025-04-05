package org.interscity.htc
package model.supermarket.entity.state

import core.entity.state.BaseState
import core.types.Tick

import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum
import org.interscity.htc.model.supermarket.entity.enumeration.ClientStatusEnum.Start

case class ClientState(
  startTick: Tick = 0,
  cashierId: String,
  amountThings: Int = 5,
  var status: ClientStatusEnum = Start
) extends BaseState(startTick = startTick)
