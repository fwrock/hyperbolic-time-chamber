package org.interscity.htc
package model.hybrid.support.motorcycle

import core.types.Tick
import model.hybrid.entity.event.data.{ MicroEnterLinkData, MicroLeaveLinkData }
import model.hybrid.entity.state.enumeration.{ ActorTypeEnum, SimulationModeEnum }
import model.hybrid.entity.state.{ DriverAttributes, MotorcycleState }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for docs/KNOWN_GAPS.md's dead-fallback-velocity finding: same bug class as
  * CarMicroHandler's (see CarMicroHandlerSpec), but worse for Motorcycle -- it didn't even check
  * `state.microState` at all, unconditionally starting every micro-link entry at a flat
  * `speedLimit * 0.9`, discarding both a fresh motorcycle's true rest state and a chained
  * micro-link's carried-over exit velocity alike. Fixed to read
  * `journeyReporter.sumoArrivalSpeed` instead.
  */
class MotorcycleMicroHandlerSpec extends AnyFlatSpec with Matchers {

  private def newMotorcycleState(): MotorcycleState =
    MotorcycleState(
      startTick = 0L,
      origin = "n_origin",
      destination = "n_dest",
      actorType = ActorTypeEnum.Motorcycle,
      size = 2.5
    )

  private def newHandler(reported: mutable.ArrayBuffer[Map[String, Any]]): (MotorcycleMicroHandler, MotorcycleJourneyReporter) = {
    var currentLinkId: Option[String] = None
    var linkEntryTick: Option[Tick] = None

    val journeyReporter = new MotorcycleJourneyReporter(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:motorcycle;moto_test",
      currentTickFn = () => 0L,
      tripOriginFn = () => Some("n_origin"),
      tripDestFn = () => Some("n_dest"),
      tripStartTickFn = () => Some(0L),
      driverAttrsFn = () => DriverAttributes()
    )

    val handler = new MotorcycleMicroHandler(
      reportFn = (data, label) => reported += data,
      entityIdFn = () => "htcaid:motorcycle;moto_test",
      currentTickFn = () => 0L,
      journeyReporter = journeyReporter,
      requestSignalStateFn = () => (),
      onFinishSpontaneousFn = _ => (),
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      isPersonCentricFn = () => true,
      finishJourneyFn = (_, _) => (),
      logDebugFn = _ => (),
      aggressivenessFn = () => 0.5,
      setCurrentLinkIdFn = v => currentLinkId = v,
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

  "handleMicroEnterLink" should "start a motorcycle's first-ever micro link at rest (0 m/s), not a flat fraction of speed limit" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newMotorcycleState()

    handler.handleMicroEnterLink(enterLinkData("link_main"), state)

    state.microState.map(_.velocity) shouldBe Some(0.0)
  }

  it should "carry over the motorcycle's actual exit velocity into the next chained micro link" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newMotorcycleState()

    handler.handleMicroEnterLink(enterLinkData("link_ab"), state)
    handler.handleMicroLeaveLink(
      MicroLeaveLinkData(
        linkId = "link_ab",
        finalPosition = 300.0,
        finalVelocity = 15.0,
        travelTime = 20.0,
        distanceTraveled = 300.0,
        averageSpeed = 15.0
      ),
      state
    )

    handler.handleMicroEnterLink(enterLinkData("link_bc"), state)

    state.microState.map(_.velocity) shouldBe Some(15.0)
  }

  it should "report the same initial_velocity it stores on the motorcycle's micro state" in {
    val reported = mutable.ArrayBuffer.empty[Map[String, Any]]
    val (handler, _) = newHandler(reported)
    val state = newMotorcycleState()

    handler.handleMicroEnterLink(enterLinkData("link_main"), state)

    val enterEvent = reported.find(_.get("event_type").contains("enter_micro_link"))
    enterEvent shouldBe defined
    enterEvent.get("initial_velocity") shouldBe 0.0
  }
}
