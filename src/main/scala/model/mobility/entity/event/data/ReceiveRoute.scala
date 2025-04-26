package org.interscity.htc
package model.mobility.entity.event.data

import org.htc.protobuf.core.entity.actor.Identify

import scala.collection.mutable

case class ReceiveRoute(
  cost: Double = Double.MaxValue,
  path: Option[mutable.Queue[(Identify, Identify)]] = None
)
