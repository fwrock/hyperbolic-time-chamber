package org.interscity.htc
package core.actor.rollback

import core.entity.event.data.BaseEventData

/** Marker payload for an anti-message's `ActorInteractionEvent.data` — an anti-message carries no
  * real content, only the retraction signal already fully encoded in `messageId`/`isAntiMessage`.
  * Extends `BaseEventData` so it falls under the existing Kryo serialization binding
  * (`application.conf`'s `"org.interscity.htc.core.entity.event.data.BaseEventData" = kryo`) with
  * no new registration needed.
  */
case object AntiMessagePayload extends BaseEventData
