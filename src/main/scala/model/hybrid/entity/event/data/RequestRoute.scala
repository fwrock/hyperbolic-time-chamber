package org.interscity.htc
package model.hybrid.entity.event.data

import core.entity.event.data.BaseEventData

case class RequestRoute(
  origin: String,
  destination: String
) extends BaseEventData
