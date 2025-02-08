package org.interscity.htc
package model.interscsimulator.entity.state.model

case class BusInformation(
                           actorId: String,
                           label: String = null,
                           capacity: Int,
                           size: Double
)
