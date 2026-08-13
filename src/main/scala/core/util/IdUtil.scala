package org.interscity.htc
package core.util

object IdUtil {

  /** Renders a `Long` entity id at the Pekko actor-path / shard-routing boundary, where ids must be
    * strings. A `Long` is inherently path-safe, so this is a plain `toString`.
    */
  def format(id: Long): String = id.toString

  /** Legacy: sanitizes a string id (e.g. a data-source group id like `nodes_0`) for actor paths. */
  def format(id: String): String =
    if (id != null) id.replace(":", "_").replace(";", "_") else null
}
