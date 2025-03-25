package org.interscity.htc
package core.entity.event

import org.interscity.htc.core.entity.event.data.BaseEventData

case class EntityEnvelopeEvent(entityId: String, event: AnyRef)
