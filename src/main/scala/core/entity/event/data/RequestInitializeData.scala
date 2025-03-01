package org.interscity.htc
package core.entity.event.data

case class RequestInitializeData(
  id: String,
  classType: String
) extends BaseEventData
