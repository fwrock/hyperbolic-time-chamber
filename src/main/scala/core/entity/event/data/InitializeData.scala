package org.interscity.htc
package core.entity.event.data

import core.entity.actor.Dependency

import scala.collection.mutable

case class InitializeData(
  data: Any,
  dependencies: mutable.Map[String, Dependency] = mutable.Map[String, Dependency]()
) extends BaseEventData
