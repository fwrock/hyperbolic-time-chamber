package org.interscity.htc
package model.hybrid.support.car

import core.types.Tick
import model.hybrid.entity.event.data.MicroEnterLinkData
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, SimulationModeEnum }
import model.hybrid.entity.state.{ CarState, DriverAttributes }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression coverage for docs/KNOWN_GAPS.md's "sumo_validation scenario A car_1 anomaly"
  * finding: `handleMicroEnterLink` used to always fall back to a flat `speedLimit * 0.8` on
  * every micro-link entry (never actually reading `state.microState`, which is always None at
  * this point because `handleMicroLeaveLink` clears it first) -- silently discarding both a
  * fresh car's true rest state (SUMO departs at 0 m/s by default) and a chained micro-link's
  * carried-over exit velocity alike. Fixed to read `journeyReporter.sumoArrivalSpeed` instead,
  * which is 0.0 by construction for a never-yet-moved car and is updated to the real exit
  * velocity by `handleMicroLeaveLink` on every link exit.
  */
class CarMicroHandlerSpec extends AnyFlatSpec with Matchers {

  private def newCarState(): CarState =
    CarState(
      startTick = 0L,
      origin = "n_origin",
      destination = "n_dest",
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )

  private def newHandler(reported: scala.collection.mutable.ArrayBuffer[Map[String, Any]]): (CarMicroHandler, CarJourneyReporter) = {
    var currentLinkId: Option[String] = None
    var currentLinkLength: Double = 0.0
    var linkEntryTick: Option[Tick] = None

    val journeyReporter = new CarJourneyReporter(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => 0L,
      tripOriginFn = () => Some("n_origin"),
      tripDestFn = () => Some("n_dest"),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

    val handler = new CarMicroHandler(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => 0L,
      simulationEndTickFn = () => 180L,
      extendSimulationFn = () => false,
      journeyReporter = journeyReporter,
      requestSignalStateFn = () => (),
      onFinishSpontaneousFn = _ => (),
      finishAndCleanupFn = (_, _) => (),
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      finishJourneyFn = (_, _) => (),
      logWarnFn = _ => (),
      logDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLinkId = v,
      setCurrentLinkLengthFn = v => currentLinkLength = v,
      setLinkEntryTickFn = v => linkEntryTick = v,
      getLinkEntryTickFn = () => linkEntryTick,
      getCurrentLinkIdFn = () => currentLinkId
    )
    (handler, journeyReporter)
  }

  private def enterLinkData(linkId: String, speedLimitKmh: Double = 50.0): MicroEnterLinkData =
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

  "handleMicroEnterLink" should "start a car's first-ever micro link at rest (0 m/s), matching SUMO's departSpeed=0 default" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData("link_main"), state)

    state.microState.map(_.velocity) shouldBe Some(0.0)
  }

  it should "carry over the car's actual exit velocity into the next chained micro link, not reset to a flat fraction of speed limit" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, journeyReporter) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData("link_ab"), state)
    handler.handleMicroLeaveLink(
      model.hybrid.entity.event.data.MicroLeaveLinkData(
        linkId = "link_ab",
        finalPosition = 300.0,
        finalVelocity = 12.5,
        travelTime = 25.0,
        distanceTraveled = 300.0,
        averageSpeed = 12.0
      ),
      state
    )

    handler.handleMicroEnterLink(enterLinkData("link_bc"), state)

    state.microState.map(_.velocity) shouldBe Some(12.5)
  }

  it should "report the same initial_velocity it stores on the car's micro state" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData("link_main"), state)

    val enterEvent = reported.find(_.get("event_type").contains("enter_micro_link"))
    enterEvent shouldBe defined
    enterEvent.get("initial_velocity") shouldBe 0.0
  }
}
