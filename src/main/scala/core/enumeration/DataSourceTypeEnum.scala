package org.interscity.htc
package core.enumeration

import core.actor.manager.load.strategy.{ CassandraLoadData, JsonLoadData, LoadDataStrategy, MongoLoadData, XmlLoadData }

enum DataSourceTypeEnum(val clazz: Class[? <: LoadDataStrategy]) {
  case json extends DataSourceTypeEnum(classOf[JsonLoadData])
  case xml extends DataSourceTypeEnum(classOf[XmlLoadData])
  case mongodb extends DataSourceTypeEnum(classOf[MongoLoadData])
  case cassandra extends DataSourceTypeEnum(classOf[CassandraLoadData])
}
