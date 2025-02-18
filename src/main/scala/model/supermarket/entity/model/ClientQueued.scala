package org.interscity.htc
package model.supermarket.entity.model

import core.entity.actor.Identify

case class ClientQueued(
  client: Identify,
  amountThings: Int
)
