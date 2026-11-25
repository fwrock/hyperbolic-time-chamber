package org.interscity.htc
package model.mobility.entity.event.data.link

import core.entity.event.data.BaseEventData

/** The Node takes back the capacity quota it granted an approach Link for `targetLinkId`: the Link stops using it and
  * answers with a [[PermitReturnData]] carrying what it did not spend.
  */
case class PermitRevokeData(targetLinkId: Long) extends BaseEventData
