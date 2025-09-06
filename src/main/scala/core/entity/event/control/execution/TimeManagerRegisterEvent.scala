package org.interscity.htc
package core.entity.event.control.execution

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.enumeration.LocalTimeManagerTypeEnum

case class TimeManagerRegisterEvent(
  actorRef: ActorRef,
  localTimeManagerType: LocalTimeManagerTypeEnum
)
