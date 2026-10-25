package org.interscity.htc
package core.entity.event.control.rollback

import core.types.Tick

/** Transport identity never rewinds with model state. Accounted runs also scope it to
  * a model namespace, run epoch, and actor incarnation so numeric IDs may repeat across models.
  */
final case class MessageId(senderId: String, tick: Tick, seq: Long,
  senderType: String = "", epoch: String = "", incarnation: String = "")
