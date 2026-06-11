package org.interscity.htc
package model.hybrid.support.subwaystation

import org.interscity.htc.model.hybrid.entity.state.SubwayStationState
import org.interscity.htc.model.hybrid.entity.event.data.subway.{ SubwayLoadPassengerData, SubwayRequestPassengerData }
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.metrics.model.hybrid.SubwayStationMetrics
import scala.collection.mutable

class SubwayPassengerManager(
  getStateFn:      () => SubwayStationState,
  entityIdFn:      () => String,
  currentTickFn:   () => Tick,
  reportFn:        (Map[String, Any], String) => Unit,
  sendMessageFn:   (String, String, AnyRef) => Unit
) {

  private def state: SubwayStationState = getStateFn()

  def handleRegisterPassenger(event: ActorInteractionEvent, line: String): Unit = {
    val person = Identify(event.actorRefId, event.actorClassType, event.actorPathRef)
    state.people.get(line) match {
      case Some(people) =>
        state.people.put(line, people :+ person)
      case None =>
        state.people.put(line, mutable.Seq(person))
    }
    SubwayStationMetrics.passengersArrived.labels(line).inc()
    SubwayStationMetrics.passengersWaiting.labels(line).inc()
    reportFn(
      Map(
        "event_type"         -> "passenger_arrived_at_station",
        "station_id"         -> entityIdFn(),
        "person_id"          -> event.actorRefId,
        "line"               -> line,
        "passengers_waiting" -> state.people.get(line).map(_.size).getOrElse(0),
        "tick"               -> currentTickFn()
      ),
      "subway_station_passenger_arrived"
    )
  }

  def handleSubwayRequestPassenger(event: ActorInteractionEvent, data: SubwayRequestPassengerData): Unit =
    state.people.get(data.line) match {
      case Some(peopleQueue) =>
        val peopleToLoad = peopleQueue.take(data.availableSpace)
        state.people.put(data.line, peopleQueue.drop(data.availableSpace))
        sendLoadPeopleToSubway(peopleToLoad, event, data)
      case None =>
        sendLoadPeopleToSubway(mutable.Seq(), event, data)
    }

  private def sendLoadPeopleToSubway(
    peopleToLoad: mutable.Seq[Identify],
    event:        ActorInteractionEvent,
    data:         SubwayRequestPassengerData
  ): Unit = {
    if (peopleToLoad.nonEmpty) {
      SubwayStationMetrics.passengersBoarded.labels(data.line).inc(peopleToLoad.size)
      SubwayStationMetrics.passengersWaiting.labels(data.line).dec(peopleToLoad.size)
      reportFn(
        Map(
          "event_type"         -> "passengers_loaded_to_subway",
          "station_id"         -> entityIdFn(),
          "subway_id"          -> event.actorRefId,
          "line"               -> data.line,
          "passengers_loaded"  -> peopleToLoad.size,
          "passengers_waiting" -> state.people.get(data.line).map(_.size).getOrElse(0),
          "tick"               -> currentTickFn()
        ),
        "subway_station_passengers_loaded"
      )
    }
    sendMessageFn(
      event.actorRefId,
      event.actorClassType,
      SubwayLoadPassengerData(people = peopleToLoad)
    )
  }
}
