package org.interscity.htc
package model.mobility.entity.event.data

import model.mobility.entity.event.data.vehicle.RequestLinkAccessData

/** The Node request a vehicle asks its Link to make at the exit tick, in the vehicle's name. */
final case class LinkAccessRequest(nodeId: Long, nodeClassType: String, request: RequestLinkAccessData)
