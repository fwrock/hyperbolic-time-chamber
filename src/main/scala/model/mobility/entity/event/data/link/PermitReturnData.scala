package org.interscity.htc
package model.mobility.entity.event.data.link

import core.entity.event.data.BaseEventData

/** What an approach Link had not spent of its quota for `targetLinkId` when the Node revoked it. */
case class PermitReturnData(targetLinkId: Long, remaining: Int) extends BaseEventData
