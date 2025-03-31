package org.interscity.htc
package model.supermarket.entity.model

import org.htc.protobuf.core.entity.actor.Identify

case class ClientQueued(
                         client: Identify,
                         amountThings: Int
)
