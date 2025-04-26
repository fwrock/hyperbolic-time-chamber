package org.interscity.htc
package core.entity.configuration

import core.enumeration.CreationTypeEnum

case class ActorDataSource(
  id: String,
  classType: String,
  creationType: CreationTypeEnum,
  dataSource: DataSource
)
