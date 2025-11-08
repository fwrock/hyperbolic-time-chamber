package org.interscity.htc
package model.hybrid.entity.state.model

import org.apache.pekko.actor.ActorRef

case class RoutePathItem(
  actorRef: ActorRef,
  actorId: String
)
