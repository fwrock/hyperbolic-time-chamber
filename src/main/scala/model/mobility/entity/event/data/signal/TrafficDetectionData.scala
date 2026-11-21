package org.interscity.htc
package model.mobility.entity.event.data.signal

import core.entity.event.data.BaseEventData

final case class TrafficDetectionData(detectorId: Long, occupiedUntilSeconds: Double) extends BaseEventData
