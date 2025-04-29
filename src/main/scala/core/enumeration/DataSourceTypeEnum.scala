package org.interscity.htc
package core.enumeration

import core.actor.manager.load.strategy.{ JsonLoadData, LoadDataStrategy }

enum DataSourceTypeEnum(val clazz: Class[? <: LoadDataStrategy]) {
  case json extends DataSourceTypeEnum(classOf[JsonLoadData])
}
