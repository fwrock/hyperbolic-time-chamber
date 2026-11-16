package org.interscity.htc.core.entity.configuration

/** Selects deterministic calculations eligible for process-local disk caching.
  *
  * Construction rejects blank directories, unsafe identifiers and duplicate selections. An empty
  * selection disables all entries. Directory existence and write access are checked during cache
  * I/O.
  *
  * @param directory
  *   cache directory, resolved relative to the working directory when not absolute
  * @param entries
  *   safe calculation identifiers eligible for reuse and storage
  * @param refresh
  *   bypass existing entries and attempt to replace them with freshly computed values
  */
case class CalculationCacheConfig(
  directory: String = ".htc-cache",
  entries: List[String] = Nil,
  refresh: Boolean = false
) {
  require(
    directory != null && directory.trim.nonEmpty,
    "calculationCache.directory must not be empty"
  )
  require(
    entries != null && entries.forall(
      id => id != null && id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")
    ),
    "calculationCache.entries must contain safe calculation identifiers"
  )
  require(entries.distinct.size == entries.size, "calculationCache.entries must be unique")
}
