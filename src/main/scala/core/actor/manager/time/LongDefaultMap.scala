package org.interscity.htc
package core.actor.manager.time

import scala.collection.mutable

/** Replaces `mutable.Map[Long, Long].withDefaultValue(d)` on the dispatch path: that map boxes every
  * key and routes each absent lookup through a default `Function1`. Keys here stay primitive.
  */
final class LongDefaultMap(default: Long) {
  private val values = mutable.LongMap.empty[Long]

  def apply(key: Long): Long = values.getOrElse(key, default)

  def update(key: Long, value: Long): Unit = values.update(key, value)

  def remove(key: Long): Unit = values.remove(key)

  def size: Int = values.size
}
