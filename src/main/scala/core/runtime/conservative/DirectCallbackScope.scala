package org.interscity.htc
package core.runtime.conservative

import scala.collection.mutable

/** Ephemeral callback effects, never migration/model state. Completion is emitted on return. */
final class DirectCallbackScope(val delivery: DirectExecute) {
  val children: mutable.ArrayBuffer[DirectWork] = mutable.ArrayBuffer.empty
  val controls: mutable.ArrayBuffer[AnyRef] = mutable.ArrayBuffer.empty
  val reports: mutable.ArrayBuffer[DirectReport] = mutable.ArrayBuffer.empty

  /** Freeze the effects together so closing the parent cannot hide a child send. */
  def completion: DirectWorkCompletion = DirectWorkCompletion(delivery.work, children.toVector)
}
