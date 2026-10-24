package org.interscity.htc
package model.hybrid.support.bus

import core.types.Tick
import model.hybrid.entity.event.data.{ MicroEnterLinkData, MicroLeaveLinkData }
import model.hybrid.entity.state.BusState
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, SimulationModeEnum }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's dead-fallback-velocity finding: same bug as
  * CarMicroHandler's (see CarMicroHandlerSpec), just for Bus. `handleMicroEnterLink` used to
  * always fall back to a flat `speedLimit * 0.7` on every micro-link entry (never actually
  * reading `state.microState`, which is always None at this point because `handleMicroLeaveLink`
  * clears it first) -- silently discarding both a fresh bus's true rest state and a chained
  * micro-link's carried-over exit velocity alike. Fixed to read `journeyReporter.sumoArrivalSpeed`
  * instead.
  */
class BusMicroHandlerSpec extends AnyFlatSpec with Matchers {

  private def newBusState(): BusState =
    BusState(
      startTick = 0L,
      label = "line_1",
      capacity = 40,
      busStops = Map.empty,
      numberOfPorts = 2,
      origin = 1L,
      destination = 2L,
      actorType = ActorTypeEnum.Bus,
      size = 12.0
    )

  private def newHandler(reported: mutable.ArrayBuffer[Map[String, Any]],
    signal: () => Unit = () => (), finish: Option[Tick] => Unit = _ => ()): (BusMicroHandler, BusJourneyReporter) = {
    var currentLinkId: Option[Long] = None
    var linkEntryTick: Option[Tick] = None

    val journey = org.interscity.htc.model.hybrid.entity.state.BusJourneyState()
    val journeyReporter = new BusJourneyReporter(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => 0L,
      journeyStateFn = () => journey
    )

    val handler = new BusMicroHandler(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:bus;bus_test",
      currentTickFn = () => 0L,
      journeyReporter = journeyReporter,
      onFinishSpontaneousFn = finish,
      requestSignalStateFn = signal,
      logDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLinkId = v,
      setLinkEntryTickFn = v => linkEntryTick = v,
      getLinkEntryTickFn = () => linkEntryTick,
      setCurrentStopNodeFn = _ => (),
      getCurrentLinkLengthFn = () => 0.0,
      findNextBusStopFn = () => None,
      checkBusStopAtPositionFn = _ => (),
      microUpdateLogEvery = 1000,
      getCurrentLinkIdFn = () => currentLinkId
    )
    (handler, journeyReporter)
  }

  private def enterLinkData(linkId: Long, speedLimitKmh: Double = 50.0): MicroEnterLinkData =
    MicroEnterLinkData(
      linkId = linkId,
      mode = SimulationModeEnum.MICRO,
      assignedLane = 0,
      linkLength = 300.0,
      speedLimit = speedLimitKmh,
      numberOfLanes = 1,
      microTimeStep = 0.1,
      ticksPerGlobalTick = 10
    )

  "handleMicroEnterLink" should "start a bus's first-ever micro link at rest (0 m/s), not a flat fraction of speed limit" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBusState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    state.microState.map(_.velocity) shouldBe Some(0.0)
  }

  it should "carry over the bus's actual exit velocity into the next chained micro link" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBusState()

    handler.handleMicroEnterLink(enterLinkData(101L), state)
    handler.handleMicroLeaveLink(
      MicroLeaveLinkData(
        linkId = 101L,
        finalPosition = 300.0,
        finalVelocity = 9.5,
        travelTime = 30.0,
        distanceTraveled = 300.0,
        averageSpeed = 10.0
      ),
      state
    )

    handler.handleMicroEnterLink(enterLinkData(102L), state)

    state.microState.map(_.velocity) shouldBe Some(9.5)
  }

  it should "report the same initial_velocity it stores on the bus's micro state" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBusState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    val enterEvent = reported.find(_.get("event_type").contains("enter_micro_link"))
    enterEvent shouldBe defined
    enterEvent.get("initial_velocity") shouldBe 0.0
  }

  "handleMicroLeaveLink" should "hand the completed path to signal cleanup once without directly rescheduling Ready" in {
    val reports = mutable.ArrayBuffer.empty[Map[String, Any]]
    val state = newBusState()
    state.currentPath = Some(101L -> 2L)
    var signals = 0
    var finishes = 0
    val (handler, _) = newHandler(reports, signal = () => {
      signals += 1
      state.currentPath shouldBe Some(101L -> 2L)
      state.movableLinkExitReported shouldBe true
      state.isMicroMode shouldBe false
    }, finish = _ => finishes += 1)
    handler.handleMicroEnterLink(enterLinkData(101L), state)
    finishes = 0
    val exit = MicroLeaveLinkData(linkId = 101L, finalPosition = 300.0,
      finalVelocity = 9.5, travelTime = 30.0, distanceTraveled = 300.0, averageSpeed = 10.0)
    handler.handleMicroLeaveLink(exit, state)
    handler.handleMicroLeaveLink(exit, state)
    signals shouldBe 1
    finishes shouldBe 0
    state.distance shouldBe 300.0
    reports.count(_.get("event_type").contains("leave_micro_link")) shouldBe 1
  }
}
