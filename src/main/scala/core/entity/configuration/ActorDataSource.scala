package org.interscity.htc
package core.entity.configuration

import core.enumeration.{ CreationTypeEnum, EntityLifecycleEnum, LoadingStrategyEnum }

case class ActorDataSource(
  id: String,
  classType: String,
  creationType: CreationTypeEnum,
  dataSource: DataSource,
  loadingStrategy: LoadingStrategyEnum = LoadingStrategyEnum.EAGER,
  entityLifecycle: EntityLifecycleEnum = EntityLifecycleEnum.STATIC
)
