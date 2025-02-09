package org.interscity.htc
package model.interscsimulator.entity.state

import core.entity.state.BaseState

import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.types.CoreTypes.Tick
import org.interscity.htc.model.interscsimulator.entity.state.model.RoutePathItem

import scala.collection.mutable

case class SubwayState(
                        startTick: Tick = 0,
                        capacity: Int,
                        numberOfPorts: Int,
                        velocity: Double,
                        stopTime: Tick,
                        passengers: mutable.Map[String, ActorRef] = mutable.Map.empty,
                        var bestRoute: Option[mutable.Queue[(RoutePathItem, RoutePathItem)]] = None,
                        var bestCost: Double = Double.MaxValue,
                        line: String
) extends BaseState(startTick = startTick)
