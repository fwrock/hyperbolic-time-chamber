package org.interscity.htc
package model.mobility.support.bicycle

import core.types.Tick
import model.mobility.entity.event.data.{ MicroEnterLinkData, MicroLeaveLinkData }
import model.mobility.entity.state.enumeration.{ ActorTypeEnum, SimulationModeEnum }
import model.mobility.entity.state.{ BicycleState, DriverAttributes }

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

  private def newHandler(reported: mutable.ArrayBuffer[Map[String, Any]],
    signal: () => Unit = () => (), finish: Option[Tick] => Unit = _ => (),
    delegatedAccess: model.mobility.entity.event.node.LinkAccessData => Unit = _ => ()): (BicycleMicroHandler, BicycleJourneyReporter) = {
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
      requestSignalStateFn = signal,
      onFinishSpontaneousFn = finish,
      onFinishPrivateVehicleFn = _ => (),
      selfDestructFn = () => (),
      isPersonCentricFn = () => true,
      finishJourneyFn = (_, _) => (),
      logDebugFn = _ => (),
      setCurrentLinkIdFn = v => currentLinkId = v,
      setLinkEntryTickFn = v => linkEntryTick = v,
      getLinkEntryTickFn = () => linkEntryTick,
      getCurrentLinkIdFn = () => currentLinkId,
      onDelegatedAccessFn = delegatedAccess
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
  "delegated MICRO scheduling" should "leave timing with the Link and consume its fused boundary answer" in {
    for (granted <- Seq(false, true)) {
      val reported = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
      val finishes = scala.collection.mutable.ArrayBuffer.empty[Option[Tick]]
      val answers = scala.collection.mutable.ArrayBuffer.empty[model.mobility.entity.event.node.LinkAccessData]
      var requested = 0
      val (handler, _) = newHandler(reported, signal = () => requested += 1,
        finish = next => { finishes += next; () }, delegatedAccess = a => { answers += a; () })
      val state = newBicycleState()
      handler.handleMicroEnterLink(enterLinkData(101L).copy(delegatedScheduling = true), state)
      finishes.last shouldBe None
      state.movableMicroSchedulingDelegated shouldBe true
      val access = model.mobility.entity.event.node.LinkAccessData(
        model.mobility.entity.state.enumeration.TrafficSignalPhaseStateEnum.Green, 0L,
        capacityState = if (granted) model.mobility.entity.state.enumeration.LinkCapacityStateEnum.Available
          else model.mobility.entity.state.enumeration.LinkCapacityStateEnum.Full)
      handler.handleMicroLeaveLink(model.mobility.entity.event.data.MicroLeaveLinkData(
        101L, 300, 10, 30, 300, 10, access = Some(access), leaveDone = granted), state)
      requested shouldBe 0
      answers.toSeq shouldBe Seq(access)
      state.movableMicroLeaveHandledByLink shouldBe granted
      state.isMicroMode shouldBe false
      state.distance shouldBe 300.0
    }
  }

}
