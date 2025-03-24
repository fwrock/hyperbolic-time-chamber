package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class InitializeEntityAckEvent(
  entityId: String
) extends BaseEvent[DefaultBaseEventData]
