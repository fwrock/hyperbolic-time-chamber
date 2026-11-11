package org.interscity.htc.core.entity.configuration

case class CalculationCacheConfig(
  directory: String = ".htc-cache",
  entries: List[String] = Nil,
  refresh: Boolean = false
) {
  require(directory != null && directory.trim.nonEmpty, "calculationCache.directory must not be empty")
  require(entries != null && entries.forall(id => id != null && id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")),
    "calculationCache.entries must contain safe calculation identifiers")
  require(entries.distinct.size == entries.size, "calculationCache.entries must be unique")
}
