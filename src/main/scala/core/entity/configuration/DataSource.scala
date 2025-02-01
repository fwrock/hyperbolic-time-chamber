package org.interscity.htc
package core.entity.configuration

import core.enumeration.DataSourceType

case class DataSource(
  sourceType: DataSourceType,
  info: Map[String, Any]
)
