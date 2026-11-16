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
  "queued admission" should "count continuation waits while keeping pre-departure delay out of trip waiting time" in {
    val reported=scala.collection.mutable.ArrayBuffer.empty[Map[String,Any]]
    val (handler,reporter)=newHandler(reported)
    val state=newCarState()
    handler.handleMicroEnterLink(enterLinkData(100L).copy(queuedWaitSeconds=3.0,initialVelocity=Some(0)),state)
    reporter.sumoWaitingTimeSeconds shouldBe 0.0
    handler.handleMicroEnterLink(enterLinkData(101L).copy(queuedWaitSeconds=2.5,initialVelocity=Some(0)),state)
    reporter.sumoWaitingTimeSeconds shouldBe 2.5
    state.microState.get.velocity shouldBe 0.0
    intercept[IllegalArgumentException](handler.handleMicroEnterLink(enterLinkData(102L).copy(queuedWaitSeconds = -1),state))
  }

  private def newCarState(): CarState =
    CarState(
      startTick = 0L,
      origin = 1L,
      destination = 2L,
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )

  private def newHandler(reported: scala.collection.mutable.ArrayBuffer[Map[String, Any]],
                         endTick: Long = 180L, runtimeOwnsHorizon: Boolean = false): (CarMicroHandler, CarJourneyReporter) = {
    var currentLinkId: Option[Long] = None
    var currentLinkLength: Double = 0.0
    var linkEntryTick: Option[Tick] = None

    val journeyState = model.hybrid.entity.state.CarJourneyState()
    val journeyReporter = new CarJourneyReporter(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => 0L,
      tripOriginFn = () => Some(1L),
      tripDestFn = () => Some(2L),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes(),
      journeyStateFn = () => journeyState
    )

    val handler = new CarMicroHandler(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:car;car_test",
      currentTickFn = () => 0L,
      simulationEndTickFn = () => endTick,
      extendSimulationFn = () => false,
      journeyReporter = journeyReporter,
      requestSignalStateFn = () => (),
      onFinishSpontaneousFn = _ => (),
      finishAndCleanupFn = (_, _) => reported += Map("event_type" -> "forced_cleanup"),
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      finishJourneyFn = (_, _) => (),
      logWarnFn = _ => (),
      logDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLinkId = v,
      setCurrentLinkLengthFn = v => currentLinkLength = v,
      setLinkEntryTickFn = v => linkEntryTick = v,
      getLinkEntryTickFn = () => linkEntryTick,
      getCurrentLinkIdFn = () => currentLinkId,
      runtimeOwnsHorizonFn = () => runtimeOwnsHorizon
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

  "handleMicroEnterLink" should "start a car's first-ever micro link at rest (0 m/s), matching SUMO's departSpeed=0 default" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    state.microState.map(_.velocity) shouldBe Some(0.0)
  }

  it should "carry over the car's actual exit velocity into the next chained micro link, not reset to a flat fraction of speed limit" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, journeyReporter) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData(101L), state)
    handler.handleMicroLeaveLink(
      model.hybrid.entity.event.data.MicroLeaveLinkData(
        linkId = 101L,
        finalPosition = 300.0,
        finalVelocity = 12.5,
        travelTime = 25.0,
        distanceTraveled = 300.0,
        averageSpeed = 12.0
      ),
      state
    )

    state.movableLinkExitReported shouldBe true
    core.util.JsonUtil.fromJson[CarState](core.util.JsonUtil.toJson(state)).movableLinkExitReported shouldBe true

    handler.handleMicroEnterLink(enterLinkData(102L), state)

    state.microState.map(_.velocity) shouldBe Some(12.5)
  }

  it should "reflect imported physical parameters and the actual departure offset in car state and reporting" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, reporter) = newHandler(reported)
    val state = newCarState().copy(microAcceleration = Some(1.5), microDeceleration = Some(3.7), size = 5.0,
      microMinGap = Some(3.5), microReactionTime = Some(1.7), microMaxSpeed = Some(7.0),
      microSpeedFactor = 1.2, microDesiredMaxSpeed = Some(5.0),microSigma=.5,microRandomSeed=9007199254740993L,
      microLc2013=model.hybrid.entity.state.model.Lc2013Parameters(strategic=2,keepRight=0))
    handler.handleMicroEnterLink(enterLinkData(100L).copy(initialPosition = 12.0), state)
    state.microState.get.maxAcceleration shouldBe 1.5
    state.microState.get.maxDeceleration shouldBe 3.7
    state.microState.get.vehicleLength shouldBe 5.0
    state.microState.get.minGap shouldBe 3.5
    state.microState.get.reactionTime shouldBe 1.7
    state.microState.get.desiredVelocity shouldBe 6.0
    val restored = core.util.JsonUtil.fromJson[CarState](core.util.JsonUtil.toJson(state))
    restored.microMinGap shouldBe Some(3.5)
    restored.microReactionTime shouldBe Some(1.7)
    restored.microMaxSpeed shouldBe Some(7.0)
    restored.microSpeedFactor shouldBe 1.2
    restored.microDesiredMaxSpeed shouldBe Some(5.0)
    restored.microSigma shouldBe .5
    restored.microRandomSeed shouldBe 9007199254740993L
    restored.microLc2013.strategic shouldBe 2.0
    restored.microLc2013.keepRight shouldBe 0.0
    reporter.sumoIdealTravelTimeSeconds shouldBe (300.0 - 12.0) / 6.0 +- 1e-9
    reporter.sumoDepartPos shouldBe 12.0
  }

  it should "report the same initial_velocity it stores on the car's micro state" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newCarState()

    handler.handleMicroEnterLink(enterLinkData(100L), state)

    val enterEvent = reported.find(_.get("event_type").contains("enter_micro_link"))
    enterEvent shouldBe defined
    enterEvent.get("initial_velocity") shouldBe 0.0
  }

  it should "execute an inclusive-horizon update while the accounted runtime owns finalization" in {
    val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported, endTick = 0L, runtimeOwnsHorizon = true)
    val state = newCarState()
    handler.handleMicroEnterLink(enterLinkData(100), state)
    handler.handleMicroUpdate(model.hybrid.entity.event.data.MicroUpdateData(
      0, 1.0, 5.0, 1.0, 0, None, 100.0, 10.0, 10.0), state)
    state.microState.get.positionInLink shouldBe 1.0
    reported.exists(_.get("event_type").contains("forced_cleanup")) shouldBe false
  }
}
