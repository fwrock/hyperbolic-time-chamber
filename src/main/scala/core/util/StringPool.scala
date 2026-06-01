package org.interscity.htc
package core.util

import java.util.concurrent.ConcurrentHashMap

/** Global string intern pool for deduplicating repeated string values loaded from JSON scenario
  * files or received via actor messages.
  *
  * Unlike `String.intern()`, this pool lives in the normal heap and is subject to ordinary GC
  * pressure — strings evicted from the pool are simply re-interned on next access.
  *
  * At São Paulo scale (millions of Person actors each holding activity nodeIds, mode strings,
  * and stop IDs), deduplication via this pool can recover several GB of heap that would otherwise
  * be held by identical String objects created independently during JSON deserialization.
  *
  * Intended for:
  *   - Fixed-vocabulary strings: activity types ("home", "work"), transport modes ("car", "walk"),
  *     strategy types ("utility")
  *   - Shared node/stop IDs: "htcaid:node;...", "htcaid:busstop;..." — many actors reference
  *     the same infrastructure nodes
  *
  * Thread-safe via `ConcurrentHashMap.computeIfAbsent`.
  */
object StringPool {
  private val pool = new ConcurrentHashMap[String, String](65536)

  /** Returns the canonical instance for `s`, interning it into the pool on first use. */
  def intern(s: String): String =
    if (s == null || s.isEmpty) s
    else pool.computeIfAbsent(s, identity)

  /** Convenience overload for `Option[String]` fields. */
  def internOpt(opt: Option[String]): Option[String] =
    if (opt.isEmpty) opt else opt.map(intern)

  /** Number of distinct strings currently held in the pool (for monitoring/logging). */
  def size: Int = pool.size()
}
