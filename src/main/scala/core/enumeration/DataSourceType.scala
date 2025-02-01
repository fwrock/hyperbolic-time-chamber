package org.interscity.htc
package core.enumeration

import core.actor.manager.load.strategy.{ CassandraLoadData, JsonLoadData, LoadDataStrategy, MongoLoadData, XmlLoadData }

enum DataSourceType(val clazz: Class[? <: LoadDataStrategy]) {
  case json extends DataSourceType(classOf[JsonLoadData])
  case xml extends DataSourceType(classOf[XmlLoadData])
  case mongodb extends DataSourceType(classOf[MongoLoadData])
  case cassandra extends DataSourceType(classOf[CassandraLoadData])
}
