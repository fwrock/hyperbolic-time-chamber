package org.interscity.htc
package core.entity.event.control.execution

import core.types.CoreTypes.Tick

import org.interscity.htc.core.entity.event.BaseEvent
import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

case class UpdateGlobalTimeEvent(
  tick: Tick
) extends BaseEvent[DefaultBaseEventData]
