package org.interscity.htc
package core.entity.configuration

import core.enumeration.DataSourceTypeEnum

case class DataSource(
                       sourceType: DataSourceTypeEnum,
                       info: Map[String, Any]
)
