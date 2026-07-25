package org.interscity.htc
package core.enumeration

import core.actor.manager.load.strategy.{
  JsonLoadData,
  LoadDataStrategy,
  ProgressiveJsonLoadData,
  ProgressiveSqliteLoadData,
  SqliteLoadData
}

/** Maps a `DataSource.sourceType` to the pair of `LoadDataStrategy` implementations that back it —
  * one for EAGER loading (`eagerClazz`), one for PROGRESSIVE, tick-windowed loading
  * (`progressiveClazz`). Both `LoadDataManager` (EAGER) and `ProgressiveLoadDataManager`
  * (PROGRESSIVE) resolve the strategy class through this enum, so the managers themselves stay
  * source-format-agnostic.
  */
enum DataSourceTypeEnum(
  val eagerClazz: Class[? <: LoadDataStrategy],
  val progressiveClazz: Class[? <: LoadDataStrategy]
) {
  case json extends DataSourceTypeEnum(classOf[JsonLoadData], classOf[ProgressiveJsonLoadData])
  case sqlite extends DataSourceTypeEnum(classOf[SqliteLoadData], classOf[ProgressiveSqliteLoadData])
}
