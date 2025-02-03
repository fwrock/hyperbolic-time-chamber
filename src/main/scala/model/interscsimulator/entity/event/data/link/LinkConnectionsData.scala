package org.interscity.htc
package model.interscsimulator.entity.event.data.link

import core.entity.actor.Identify

import org.interscity.htc.core.entity.event.data.BaseEventData

case class LinkConnectionsData(
                                   to: Identify,
                                   from: Identify,
                                   ) extends BaseEventData
