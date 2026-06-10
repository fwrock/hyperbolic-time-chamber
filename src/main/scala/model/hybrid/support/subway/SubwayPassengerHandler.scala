package org.interscity.htc
package model.hybrid.support.subway

import org.interscity.htc.model.hybrid.entity.state.SubwayState
import org.interscity.htc.model.hybrid.entity.event.data.subway.{
  SubwayLoadPassengerData,
  SubwayRequestUnloadPassengerData,
  SubwayUnloadPassengerData
}
import org.htc.protobuf.core.entity.actor.Identify
import org.interscity.htc.core.entity.event.ActorInteractionEvent
import org.interscity.htc.core.types.Tick
import org.interscity.htc.core.metrics.model.hybrid.{ MovableMetrics, SubwayMetrics }
import org.apache.pekko.actor.ActorRef

class SubwayPassengerHandler(
  getStateFn:        () => SubwayState,
  entityIdFn:        () => String,
  currentTickFn:     () => Tick,
  getCurrentNodeFn:  () => String,
  selfRefFn:         () => ActorRef,
  reportFn:          (Map[String, Any], String) => Unit,
  sendMessageFn:     (String, String, AnyRef) => Unit,
  scheduleEventFn:   Tick => Unit,
  logInfoFn:         String => Unit,
  logWarnFn:         String => Unit
) {

  private def state: SubwayState = getStateFn()

  def requestLoadPassenger(stationOpt: Option[String]): Unit =
    stationOpt match {
      case Some(stationId) =>
        import org.interscity.htc.model.hybrid.util.SubwayUtil
        val availableSpace = math.min(
          x = state.capacity - state.passengers.size,
          y = SubwayUtil.numberOfPassengerToBoarding(
            numberOfPorts           = state.numberOfPorts,
            portsCapacity           = state.capacity,
            stopTime                = state.stopTime,
            boardingTimeByPassenger = state.boardingTimeByPassenger
          )
        )
        sendMessageFn(
          stationId,
          "hybrid.actor.SubwayStation",
          org.interscity.htc.model.hybrid.entity.event.data.subway.SubwayRequestPassengerData(
            line           = state.line,
            availableSpace = availableSpace
          )
        )
      case None =>
        state.nodeState.isLoaded = true
        onFinishNodeState()
    }

  def requestUnloadPeopleData(): Unit = {
    if (state.passengers.isEmpty) {
      logInfoFn(s"[SUBWAY] No passengers to unload, setting isUnloaded=true id=${entityIdFn()}")
      state.nodeState.isUnloaded = true
      onFinishNodeState()
      return
    }

    state.countUnloadReceived  = 0
    state.countUnloadPassenger = 0
    val nodeId                 = getCurrentNodeFn()
    logInfoFn(
      s"[SUBWAY] Requesting unload of ${state.passengers.size} passengers | " +
        s"id=${entityIdFn()} tick=${currentTickFn()}"
    )

    state.passengers.foreach { case (_, person) =>
      sendMessageFn(
        person.id,
        person.classType,
        SubwayRequestUnloadPassengerData(nodeId = nodeId, nodeRef = selfRefFn())
      )
    }
  }

  def handleLoadPeople(event: ActorInteractionEvent, data: SubwayLoadPassengerData): Unit = {
    state.nodeState.isLoaded = true
    for (person <- data.people)
      state.passengers.put(person.id, person)
    if (data.people.nonEmpty) {
      SubwayMetrics.passengersBoarded.labels(state.line).inc(data.people.size)
      SubwayMetrics.activePassengers.inc(data.people.size)
      reportFn(
        Map(
          "event_type"       -> "subway_load_passengers",
          "subway_id"        -> entityIdFn(),
          "line"             -> state.line,
          "passengers_loaded"-> data.people.size,
          "total_passengers" -> state.passengers.size,
          "capacity"         -> state.capacity,
          "tick"             -> currentTickFn()
        ),
        "subway_load_passengers"
      )
    }
    onFinishNodeState()
  }

  def handleUnloadPassenger(event: ActorInteractionEvent, data: SubwayUnloadPassengerData, expectedResponses: Int): Int = {
    state.countUnloadReceived += 1
    if (data.isArrival) {
      state.passengers.remove(event.actorRefId)
      state.countUnloadPassenger += 1
    }
    if (state.countUnloadReceived >= expectedResponses) {
      val unloadedCount          = state.countUnloadPassenger
      state.countUnloadReceived  = 0
      state.countUnloadPassenger = 0
      if (unloadedCount > 0) {
        SubwayMetrics.passengersAlighted.labels(state.line).inc(unloadedCount)
        SubwayMetrics.activePassengers.dec(unloadedCount)
        reportFn(
          Map(
            "event_type"           -> "subway_unload_passengers",
            "subway_id"            -> entityIdFn(),
            "line"                 -> state.line,
            "passengers_unloaded"  -> unloadedCount,
            "remaining_passengers" -> state.passengers.size,
            "tick"                 -> currentTickFn()
          ),
          "subway_unload_passengers"
        )
      }
      state.nodeState.isUnloaded = true
      onFinishNodeState()
      0 // reset expected count signal
    } else expectedResponses
  }

  private def onFinishNodeState(): Unit = {
    logInfoFn(
      s"[SUBWAY] onFinishNodeState: isLoaded=${state.nodeState.isLoaded} isUnloaded=${state.nodeState.isUnloaded} " +
        s"id=${entityIdFn()} tick=${currentTickFn()}"
    )
    if (state.nodeState.isLoaded && state.nodeState.isUnloaded) {
      state.nodeState.isLoaded   = false
      state.nodeState.isUnloaded = false
      logInfoFn(s"[SUBWAY] Scheduling depart in ${state.stopTime} ticks | id=${entityIdFn()} tick=${currentTickFn()}")
      scheduleEventFn(currentTickFn() + state.stopTime)
    }
  }
}
