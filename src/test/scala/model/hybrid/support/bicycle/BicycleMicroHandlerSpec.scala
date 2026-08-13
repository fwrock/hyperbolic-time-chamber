package org.interscity.htc
package model.hybrid.support.bicycle

import core.types.Tick
import model.hybrid.entity.event.data.{ MicroEnterLinkData, MicroLeaveLinkData }
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, SimulationModeEnum }
import model.hybrid.entity.state.{ BicycleState, DriverAttributes }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's dead-fallback-velocity finding: same bug class as
  * CarMicroHandler's (see CarMicroHandlerSpec), but worse for Bicycle -- it didn't even check
  * `state.microState` at all, unconditionally starting every micro-link entry at a flat 5.0 m/s,
  * discarding both a fresh bicycle's true rest state and a chained micro-link's carried-over exit
  * velocity alike. Fixed to read `journeyReporter.sumoArrivalSpeed` instead.
  */
class BicycleMicroHandlerSpec extends AnyFlatSpec with Matchers {

  private def newBicycleState(): BicycleState =
    BicycleState(
      startTick = 0L,
      origin = 1L,
      destination = 2L,
      actorType = ActorTypeEnum.Bicycle,
      size = 2.0
    )

  private def newHandler(reported: mutable.ArrayBuffer[Map[String, Any]]): (BicycleMicroHandler, BicycleJourneyReporter) = {
    var currentLinkId: Option[Long] = None
    var linkEntryTick: Option[Tick] = None

    val journeyReporter = new BicycleJourneyReporter(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:bicycle;bike_test",
      currentTickFn = () => 0L,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

    val handler = new BicycleMicroHandler(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:bicycle;bike_test",
      currentTickFn = () => 0L,
      journeyReporter = journeyReporter,
      requestSignalStateFn = () => (),
      onFinishSpontaneousFn = _ => (),
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      isPersonCentricFn = () => true,
      finishJourneyFn = (_, _) => (),
      logDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLinkId = v,
      setLinkEntryTickFn = v => linkEntryTick = v,
      getLinkEntryTickFn = () => linkEntryTick,
      getCurrentLinkIdFn = () => currentLinkId
    )
    (handler, journeyReporter)
  }

  private def enterLinkData(linkId: Long, speedLimitKmh: Double = 30.0): MicroEnterLinkData =
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

  "handleMicroEnterLink" should "start a bicycle's first-ever micro link at rest (0 m/s), not a flat 5.0 m/s" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBicycleState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    state.microState.map(_.velocity) shouldBe Some(0.0)
  }

  it should "carry over the bicycle's actual exit velocity into the next chained micro link" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBicycleState()

    handler.handleMicroEnterLink(enterLinkData(101L), state)
    handler.handleMicroLeaveLink(
      MicroLeaveLinkData(
        linkId = 101L,
        finalPosition = 300.0,
        finalVelocity = 4.2,
        travelTime = 60.0,
        distanceTraveled = 300.0,
        averageSpeed = 5.0
      ),
      state
    )

    handler.handleMicroEnterLink(enterLinkData(102L), state)

    state.microState.map(_.velocity) shouldBe Some(4.2)
  }

  it should "report the same initial_velocity it stores on the bicycle's micro state" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newBicycleState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    val enterEvent = reported.find(_.get("event_type").contains("enter_micro_link"))
    enterEvent shouldBe defined
    enterEvent.get("initial_velocity") shouldBe 0.0
  }
}
