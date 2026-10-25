package org.interscity.htc
package model.hybrid.entity.event.data.link

import core.entity.event.data.BaseEventData
import model.hybrid.entity.state.model.MicroBoundarySnapshot

case class SubscribeMicroBoundaryData() extends BaseEventData
case class MicroBoundaryUpdateData(snapshot: MicroBoundarySnapshot) extends BaseEventData
