package org.interscity.htc
package core.entity.event.control.load

import core.entity.actor.ActorSimulation

case class SendBatchToCreators(
                                actors: List[ActorSimulation]
                              )
