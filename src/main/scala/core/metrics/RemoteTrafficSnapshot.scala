package org.interscity.htc
package core.metrics

/** Physical remote-envelope totals for one JVM at final metric capture, before teardown. */
final case class RemoteTrafficSnapshot(
  direction: String,
  category: String,
  messages: Long,
  bytes: Long
)
