package org.interscity.htc
package model.supermarket.entity.event.data

import core.entity.event.data.BaseEventData

case class NewClientServiceData(
  amountThings: Int
) extends BaseEventData
