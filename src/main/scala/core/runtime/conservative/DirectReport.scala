package org.interscity.htc
package core.runtime.conservative

import core.entity.event.control.report.ReportEvent
import org.apache.pekko.actor.ActorRef

/** Sink delivery collected in a callback and applied by its scheduling owner. */
final case class DirectReport(target: ActorRef, event: ReportEvent)
