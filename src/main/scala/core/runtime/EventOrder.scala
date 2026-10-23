package org.interscity.htc
package core.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Logical order is independent of transport counters, which change on re-execution. */
final case class EventOrder(tick: Long, microstep: Long, key: String) extends Ordered[EventOrder] {
  require(microstep >= 0L, "negative causal microstep")
  require(key.nonEmpty, "empty logical event key")

  override def compare(that: EventOrder): Int =
    Ordering.Tuple3[Long, Long, String].compare((tick, microstep, key), (that.tick, that.microstep, that.key))

  def child(at: Long, ordinal: Long): EventOrder = {
    require(at >= tick, s"cannot schedule into the causal past: $at < $tick")
    require(ordinal >= 0L, "negative effect ordinal")
    EventOrder(at, if (at == tick) Math.addExact(microstep, 1L) else 0L,
      EventOrder.digest(s"child:$key:$at") + ":" + f"$ordinal%016x")
  }
}

object EventOrder {
  def root(tick: Long, namespace: String, entityId: Long): EventOrder =
    EventOrder(tick, 0L, digest(s"root:${namespace.length}:$namespace:$entityId:$tick"))

  private def digest(value: String): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8)))
}
