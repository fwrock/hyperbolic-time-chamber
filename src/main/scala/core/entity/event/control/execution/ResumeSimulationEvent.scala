package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

case class ResumeSimulationEvent() extends BaseEvent(tick = -1, actorRef = null)
