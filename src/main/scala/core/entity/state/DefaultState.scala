package org.interscity.htc
package core.entity.state

import core.types.CoreTypes.Tick

case class DefaultState(startTick: Tick) extends BaseState(startTick = startTick)
