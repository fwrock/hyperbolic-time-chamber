package org.interscity.htc
package model.supermarket.util

import core.types.Tick

object CashierUtil {

  val breakTime: Tick = 25

  def serviceTime(amountThings: Int): Tick =
    amountThings * 30
}
