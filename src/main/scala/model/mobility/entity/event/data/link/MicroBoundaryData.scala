package org.interscity.htc
package model.mobility.entity.event.data.link

import core.entity.event.data.BaseEventData
import model.mobility.entity.state.model.MicroBoundarySnapshot

case class SubscribeMicroBoundaryData() extends BaseEventData
case class MicroBoundaryUpdateData(snapshot: MicroBoundarySnapshot) extends BaseEventData
