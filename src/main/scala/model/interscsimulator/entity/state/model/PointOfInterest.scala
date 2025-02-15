package org.interscity.htc
package model.interscsimulator.entity.state.model

import model.interscsimulator.entity.state.enumeration.PointOfInterestTypeEnum

case class PointOfInterest(
  id: String,
  label: String,
  `type`: PointOfInterestTypeEnum,
  location: Location
)
