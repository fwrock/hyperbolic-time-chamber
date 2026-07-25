package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import core.actor.trace.ActorTrace
import org.apache.pekko.actor.ActorRef
import org.interscity.htc.core.metrics.model.hybrid.BusMetrics
import org.interscity.htc.model.hybrid.entity.event.data.bus.{
  BusLoadPassengerData,
  BusRequestPassengerData,
  BusRequestUnloadPassengerData,
  BusUnloadPassengerData
}
import org.interscity.htc.model.hybrid.entity.event.data.person.PassengerBoardedVehicleData
import org.interscity.htc.model.hybrid.entity.state.BusState
import org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.{
  WaitingLoadPassenger,
  WaitingUnloadPassenger
}
import org.interscity.htc.model.hybrid.util.{BusUtil, CityMapUtil}
import org.interscity.htc.model.hybrid.util.BusUtil.loadPersonTime

/** Handles all bus-stop passenger operations for Bus actors.
  *
  * Responsibilities:
  *   - handleBusLoadPeople / handleUnloadPassenger: passenger boarding/alighting
  *   - checkBusStopAtPosition: MICRO mode bus-stop detection
  *   - findNextBusStop / findBusStopAtNode: route helpers
  *   - requestUnloadPeopleData / requestLoadPassenger: stop interaction protocol
  *   - getCurrentLinkLength: link length lookup
  */
class BusStopHandler(
  private val reportFn: (Map[String, Any], String) => Unit,
  private val entityIdFn: () => String,
  private val currentTickFn: () => Tick,
  private val journeyReporter: BusJourneyReporter,
  private val sendMessageFn: (String, String, AnyRef, String) => Unit,
  private val onFinishSpontaneousFn: Option[Tick] => Unit,
  private val deferFinishSpontaneousFn: () => Unit,
  private val enterLinkFn: () => Unit,
  private val selfRefFn: () => ActorRef,
  private val getCurrentStopNodeFn: () => Option[String],
  private val setCurrentStopNodeFn: Option[String] => Unit,
  private val logDebugFn: String => Unit,
  private val getCurrentLinkIdFn: () => Option[String],
  private val busStopProbeLogEvery: Int
) {

  private var busStopProbeLogCount: Long = 0L
  private var expectedUnloadResponses: Int = 0

  def handleBusLoadPeople(data: BusLoadPassengerData, state: BusState): Unit = {
    // Stale-reply guard, mirroring the pattern already used elsewhere in this codebase
    // (BusSignalHandler.handleSignalState's `state.status != WaitingSignalState` check;
    // Car/Motorcycle/Bicycle's `status == Parked || Finished` checks in their own
    // handleLeaveLink). If the bus has already moved on from this WaitingLoadPassenger cycle
    // by the time the stop's reply arrives (e.g. the reply was queued behind other messages),
    // re-setting status back to WaitingLoadPassenger and rescheduling here would make
    // Bus.actSpontaneous's `case WaitingLoadPassenger => enterLink()` fire a second, spurious
    // time for a link already entered — confirmed via instrumentation as the dominant
    // remaining source of duplicate enter_link events. Passengers still board either way (the
    // stop already committed to handing them over) — only the status/reschedule side effect is
    // skipped for a stale reply. See docs/KNOWN_GAPS.md.
    val isStaleReply = state.status != WaitingLoadPassenger
    if (data.people.nonEmpty) {
      val tick         = currentTickFn()
      val entityId     = entityIdFn()
      val nextTickTime = tick + loadPersonTime(
        numberOfPorts      = state.numberOfPorts,
        numberOfPassengers = data.people.size
      )
      journeyReporter.sumoStopTimeSeconds += math.max(0L, nextTickTime - tick).toDouble

      for (person <- data.people) state.people.put(person.id, person)

      for (person <- data.people)
        sendMessageFn(
          person.id,
          person.classType,
          PassengerBoardedVehicleData(vehicleId = entityIdFn(), vehicleClassType = "hybrid.actor.Bus"),
          "PassengerBoardedVehicle"
        )

      BusMetrics.passengersBoarded.labels(state.label).inc(data.people.size)
      BusMetrics.activePassengers.inc(data.people.size)
      ActorTrace.trace(entityId, tick, "bus_passengers_loaded", // #actor-trace
        s"loaded=${data.people.size} total=${state.people.size} occupancy=${state.occupancyPercentage}% label=${state.label}") // #actor-trace

      reportFn(
        Map(
          "event_type"        -> "bus_load_passengers",
          "bus_id"            -> entityId,
          "passengers_loaded" -> data.people.size,
          "total_passengers"  -> state.people.size,
          "occupancy"         -> state.occupancyPercentage,
          "tick"              -> tick
        ),
        "bus_load_passengers"
      )

      if (!isStaleReply) {
        state.status = WaitingLoadPassenger
        onFinishSpontaneousFn(Some(nextTickTime))
      }
    } else if (!isStaleReply) {
      state.status = WaitingLoadPassenger
      onFinishSpontaneousFn(Some(currentTickFn() + 1))
    }
  }

  def handleUnloadPassenger(data: BusUnloadPassengerData, personId: String, state: BusState): Unit = {
    if (expectedUnloadResponses == 0) return  // no active unload round; ignore spurious/late response
    state.countUnloadReceived += 1

    if (data.isArrival) {
      state.people.remove(personId)
      state.countUnloadPassenger += 1
    }

    if (state.countUnloadReceived >= expectedUnloadResponses) {
      val unloadedCount = state.countUnloadPassenger
      state.countUnloadReceived  = 0
      state.countUnloadPassenger = 0
      expectedUnloadResponses    = 0

      if (unloadedCount > 0) {
        val tick         = currentTickFn()
        val entityId     = entityIdFn()
        val nextTickTime = tick + BusUtil.unloadPersonTime(
          numberOfPassengers = unloadedCount,
          numberOfPorts      = state.numberOfPorts
        )
        journeyReporter.sumoStopTimeSeconds += math.max(0L, nextTickTime - tick).toDouble

        BusMetrics.passengersAlighted.labels(state.label).inc(unloadedCount)
        BusMetrics.activePassengers.dec(unloadedCount)
        ActorTrace.trace(entityId, tick, "bus_passengers_unloaded", // #actor-trace
          s"unloaded=$unloadedCount remaining=${state.people.size} label=${state.label}") // #actor-trace

        reportFn(
          Map(
            "event_type"           -> "bus_unload_passengers",
            "bus_id"               -> entityId,
            "passengers_unloaded"  -> unloadedCount,
            "remaining_passengers" -> state.people.size,
            "tick"                 -> tick
          ),
          "bus_unload_passengers"
        )

        state.status = WaitingUnloadPassenger
        onFinishSpontaneousFn(Some(nextTickTime))
      } else {
        state.status = WaitingUnloadPassenger
        onFinishSpontaneousFn(Some(currentTickFn() + 1))
      }
    }
  }

  def checkBusStopAtPosition(position: Double, state: BusState): Unit =
    state.microState.foreach { micro =>
      micro.nextBusStop.foreach { stopId =>
        busStopProbeLogCount += 1
        if (busStopProbeLogCount % busStopProbeLogEvery == 0L) {
          logDebugFn(s"Bus stop probe[$busStopProbeLogCount]: position=$position, nextStop=$stopId")
          ActorTrace.trace(entityIdFn(), currentTickFn(), "bus_stop_probe", // #actor-trace
            s"position=$position nextStop=$stopId label=${state.label}") // #actor-trace
        }
      }
    }

  def findNextBusStop(state: BusState): Option[String] =
    state.busStops.headOption.map(_._1)

  def findBusStopAtNode(nodeId: String, state: BusState): Option[String] =
    state.busStops.find { case (_, stopNodeId) => stopNodeId == nodeId }.map(_._1)

  def requestUnloadPeopleData(state: BusState): Unit = {
    if (state.people.isEmpty) {
      requestLoadPassenger(state)
      return
    }

    state.status = WaitingUnloadPassenger
    expectedUnloadResponses    = state.people.size
    state.countUnloadReceived  = 0
    state.countUnloadPassenger = 0

    val nodeId = getCurrentStopNodeFn().getOrElse("unknown")
    state.people.foreach { case (_, person) =>
      sendMessageFn(
        person.id,
        person.classType,
        BusRequestUnloadPassengerData(nodeId = nodeId, nodeRef = selfRefFn()),
        "RequestUnloadPassenger"
      )
    }
    // Consistency-critical: handleUnloadPassenger resolves the spontaneous event (via
    // onFinishSpontaneousFn) once every passenger has replied. Polling again unconditionally at
    // tick+1 here raced with that — if not all passengers replied within one tick, the poll
    // fired case WaitingUnloadPassenger => requestLoadPassenger() prematurely, before unload
    // actually finished, sending a duplicate RequestPassenger to the stop. See
    // docs/KNOWN_GAPS.md's non-reproducibility investigation for the analogous enter_link bug
    // this pattern caused via requestLoadPassenger below.
    deferFinishSpontaneousFn()
  }

  def requestLoadPassenger(state: BusState): Unit = {
    val nodeId = getCurrentStopNodeFn().getOrElse("unknown")
    findBusStopAtNode(nodeId, state) match {
      case Some(busStopId) =>
        state.status = WaitingLoadPassenger
        sendMessageFn(
          busStopId,
          "hybrid.actor.BusStop",
          BusRequestPassengerData(
            label          = state.label,
            availableSpace = state.capacity - state.people.size
          ),
          "RequestPassenger"
        )
        // Consistency-critical: handleBusLoadPeople (triggered by the stop's
        // BusLoadPassengerData reply) is what actually resolves this spontaneous event, via
        // onFinishSpontaneousFn. Polling again unconditionally at tick+1 (as before) raced with that
        // reply: BusState.status and BusState.movableStatus are the *same* field
        // (BusState.status delegates to movableStatus), so a premature tick+1 wake-up here saw
        // status still == WaitingLoadPassenger (indistinguishable from "boarding just
        // finished") and Bus.actSpontaneous's `case WaitingLoadPassenger => enterLink()`
        // treated it as "ready to leave", sending a duplicate EnterLinkData for a link it was
        // (or was about to be) already registered on. Confirmed via direct instrumentation —
        // see docs/KNOWN_GAPS.md.
        deferFinishSpontaneousFn()
      case None =>
        setCurrentStopNodeFn(None)
        state.status = org.interscity.htc.model.hybrid.entity.state.enumeration.MovableStatusEnum.Ready
        enterLinkFn()
    }
  }

  def getCurrentLinkLength: Double =
    getCurrentLinkIdFn().flatMap { linkId =>
      CityMapUtil.edgeLabelsById.get(linkId).map(_.length)
    }.getOrElse(1000.0)
}
