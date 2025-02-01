package org.interscity.htc
package core.entity.state

import core.types.CoreTypes.Tick

/**
 * Default state.
 * This class can be used as a default state, without necessary to create a new class.
 * @param startTick the tick when the state started
 */
case class DefaultState(startTick: Tick) extends BaseState(startTick = startTick)
