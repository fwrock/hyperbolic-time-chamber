package org.interscity.htc
package core.entity.event.control.load

import core.entity.event.BaseEvent

case class InitializeEntityAckEvent(
  entityId: String
) extends BaseEvent
