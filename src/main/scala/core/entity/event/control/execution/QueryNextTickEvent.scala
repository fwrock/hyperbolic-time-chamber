package org.interscity.htc
package core.entity.event.control.execution

import core.entity.event.BaseEvent

import org.interscity.htc.core.entity.event.data.DefaultBaseEventData

/** Sent by GlobalTimeManager to all LocalTimeManagers during the grace period when no scheduled
  * actors are detected. Each LTM responds with a LocalTimeReportEvent reflecting its current
  * schedule state. This allows any in-flight ScheduleEvents (e.g. from Car.handleStartTrip
  * arriving just after LTM reported idle) to be counted before simulation termination.
  */
case class QueryNextTickEvent() extends BaseEvent[DefaultBaseEventData]
