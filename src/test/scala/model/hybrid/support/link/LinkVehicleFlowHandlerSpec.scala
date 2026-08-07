package org.interscity.htc
package model.hybrid.support.link

import core.entity.event.ActorInteractionEvent
import core.types.Tick
import model.hybrid.entity.event.data.{ EnterLinkData, LeaveLinkData }
import model.hybrid.entity.state.LinkState
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import model.hybrid.util.{ DynamicWeightCache, SpeedUtil }
import org.interscity.htc.core.enumeration.CreationTypeEnum

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression coverage for populating `LinkState.currentSpeed`/`congestionFactor` from real
  * occupancy instead of leaving them frozen at their 0.0/1.0 defaults forever (previously: set
  * once at construction, read by `DynamicLinkCost`/routing, never recomputed — see the
  * congestion-propagation design discussion this implements the first step of).
  *
  * `LinkMetricsReporter.publishDynamicCost` writes through `DynamicWeightCache`, a JVM-wide
  * singleton — but unlike `CityMapUtil` it defaults to the dependency-free in-memory cache
  * strategy (no file/network requirement) when no `htc.routing.cache-strategy` config is present,
  * so it's safe to exercise directly here. Each test uses a unique link ID and clears it
  * afterward to avoid leaking state into other specs sharing this JVM.
  */
class LinkVehicleFlowHandlerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var usedLinkIds: mutable.Set[String] = mutable.Set.empty

  override def afterEach(): Unit = {
    usedLinkIds.foreach(DynamicWeightCache.clearCost)
    usedLinkIds = mutable.Set.empty
  }

  private def newMesoState(linkId: String, length: Double = 300.0, capacity: Double = 20.0, freeSpeed: Double = 13.9, lanes: Int = 1): LinkState =
    LinkState.createMeso(
      startTick = 0L,
      from = "n1",
      to = "n2",
      length = length,
      lanes = lanes,
      speedLimit = freeSpeed,
      capacity = capacity,
      freeSpeed = freeSpeed
    )

  private case class Fixture(handler: LinkVehicleFlowHandler, getState: () => LinkState, linkId: String, setTick: Tick => Unit)

  private def newFixture(linkId: String, initialState: LinkState, costPublishInterval: Int = 0): Fixture = {
    usedLinkIds += linkId
    var state = initialState
    var tick: Tick = 0L
    val vehicleEntryTick = mutable.Map.empty[String, Tick]
    val vehicleWaiting    = mutable.Map.empty[String, Double]

    val metricsReporter = new LinkMetricsReporter(
      reportFn = (_, _) => (),
      entityIdFn = () => linkId,
      currentTickFn = () => tick,
      cacheTtl = 60,
      costPublishInterval = costPublishInterval,
      getLinkStateFn = () => state,
      getVehicleEntryTickFn = id => vehicleEntryTick.get(id),
      getVehicleWaitingSecondsFn = id => vehicleWaiting.getOrElse(id, 0.0),
      getRegisteredVehicleIdsFn = () => state.registered.iterator.map(_.actorId).toIterable,
      logWarnFn = _ => ()
    )

    val handler = new LinkVehicleFlowHandler(
      entityIdFn = () => linkId,
      currentTickFn = () => tick,
      sendMessageFn = (_, _, _, _) => (),
      scheduleEventFn = _ => (),
      getLinkStateFn = () => state,
      setLinkStateFn = s => state = s,
      putVehicleEntryTickFn = (id, t) => vehicleEntryTick.put(id, t),
      getVehicleEntryTickFn = id => vehicleEntryTick.get(id),
      removeVehicleEntryTickFn = id => vehicleEntryTick.remove(id),
      getOrUpdateVehicleWaitingFn = id => vehicleWaiting.getOrElseUpdate(id, 0.0),
      removeVehicleWaitingFn = id => vehicleWaiting.remove(id),
      getVehicleWaitingSecondsFn = id => vehicleWaiting.getOrElse(id, 0.0),
      isMicroScheduledFn = () => false,
      setMicroScheduledFn = _ => (),
      metricsReporter = metricsReporter,
      findVehicleLaneFn = _ => None,
      findLeastOccupiedLaneFn = () => 0,
      logDebugFn = _ => ()
    )

    Fixture(handler, () => state, linkId, t => tick = t)
  }

  private def event(carId: String, linkId: String): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 0L,
      lamportTick = 0L,
      actorRefId = carId,
      shardRefId = "hybrid.actor.Car",
      actorPathRef = carId,
      actorClassType = "hybrid.actor.Car",
      data = "unused",
      resourceId = "res-1"
    )

  private def enterData(carId: String, actorType: ActorTypeEnum = ActorTypeEnum.Car): EnterLinkData =
    EnterLinkData(
      actorType = actorType,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed,
      actorSize = 4.5
    )

  private def newMicroState(linkId: String, length: Double = 300.0, lanes: Int = 2, speedLimit: Double = 13.9, capacity: Double = 20.0): LinkState =
    LinkState.createMicro(
      startTick = 0L,
      from = "n1",
      to = "n2",
      length = length,
      lanes = lanes,
      speedLimit = speedLimit,
      capacity = capacity,
      freeSpeed = speedLimit
    )

  private def leaveData(carId: String): LeaveLinkData =
    LeaveLinkData(
      actorType = ActorTypeEnum.Car,
      actorSize = 4.5,
      actorCreationType = CreationTypeEnum.LoadBalancedDistributed
    )

  "handleEnterLinkMeso" should "start currentSpeed at freeSpeed and congestionFactor at 1.0 on an empty link" in {
    val linkId = "link_meso_empty"
    val f = newFixture(linkId, newMesoState(linkId))

    f.handler.handleEnterLinkMeso(event("car_1", linkId), enterData("car_1"))

    f.getState().currentSpeed shouldBe SpeedUtil.linkDensitySpeed(300.0, 20.0, 1L, 13.9)
    f.getState().congestionFactor shouldBe SpeedUtil.bprCongestionFactor(1.0, 20.0)
  }

  it should "lower currentSpeed and raise congestionFactor as more distinct vehicles register" in {
    val linkId = "link_meso_fill"
    val f = newFixture(linkId, newMesoState(linkId, capacity = 10.0))

    (1 to 9).foreach(i => f.handler.handleEnterLinkMeso(event(s"car_$i", linkId), enterData(s"car_$i")))
    val speedAt9 = f.getState().currentSpeed
    val congestionAt9 = f.getState().congestionFactor

    f.handler.handleEnterLinkMeso(event("car_10", linkId), enterData("car_10"))

    f.getState().currentSpeed should be < speedAt9
    f.getState().congestionFactor should be > congestionAt9
  }

  it should "not double-count or recompute for a duplicate EnterLinkData from the same vehicle" in {
    val linkId = "link_meso_dup"
    val f = newFixture(linkId, newMesoState(linkId))

    f.handler.handleEnterLinkMeso(event("car_1", linkId), enterData("car_1"))
    val speedAfterFirst = f.getState().currentSpeed

    f.handler.handleEnterLinkMeso(event("car_1", linkId), enterData("car_1"))

    f.getState().registered should have size 1
    f.getState().currentSpeed shouldBe speedAfterFirst
  }

  "handleLeaveLink" should "recompute back toward freeSpeed/1.0 as vehicles leave a MESO link" in {
    val linkId = "link_meso_leave"
    val f = newFixture(linkId, newMesoState(linkId, capacity = 10.0))
    (1 to 5).foreach(i => f.handler.handleEnterLinkMeso(event(s"car_$i", linkId), enterData(s"car_$i")))
    val speedAt5 = f.getState().currentSpeed

    (1 to 4).foreach(i => f.handler.handleLeaveLink(event(s"car_$i", linkId), leaveData(s"car_$i"), wasRegistered = true))

    f.getState().registered should have size 1
    f.getState().currentSpeed should be > speedAt5
    f.getState().congestionFactor shouldBe SpeedUtil.bprCongestionFactor(1.0, 10.0)
  }

  it should "leave currentSpeed/congestionFactor untouched on a MICRO-mode link (owned by the per-tick MICRO recompute instead)" in {
    val linkId = "link_micro_leave"
    val microState = LinkState.createMicro(
      startTick = 0L, from = "n1", to = "n2", length = 300.0, lanes = 1,
      speedLimit = 13.9, capacity = 20.0, freeSpeed = 13.9
    ).copy(currentSpeed = 7.0, congestionFactor = 1.42)
    val f = newFixture(linkId, microState)

    f.handler.handleLeaveLink(event("car_1", linkId), leaveData("car_1"), wasRegistered = false)

    f.getState().currentSpeed shouldBe 7.0
    f.getState().congestionFactor shouldBe 1.42
  }

  "recomputeAndPublishMesoDynamics (via handleEnterLinkMeso)" should "publish the recomputed cost to DynamicWeightCache when costPublishInterval is 0 (always publish)" in {
    val linkId = "link_meso_publish"
    val f = newFixture(linkId, newMesoState(linkId), costPublishInterval = 0)

    f.handler.handleEnterLinkMeso(event("car_1", linkId), enterData("car_1"))

    val published = DynamicWeightCache.getCost(linkId)
    published shouldBe defined
    published.get.currentSpeed shouldBe f.getState().currentSpeed
    published.get.congestionFactor shouldBe f.getState().congestionFactor
  }

  it should "respect costPublishInterval: skip the Kafka/cache publish for an enter before the interval elapses, then publish once it has" in {
    val linkId = "link_meso_rate_limit"
    val f = newFixture(linkId, newMesoState(linkId), costPublishInterval = 10)

    f.setTick(3L)
    f.handler.handleEnterLinkMeso(event("car_1", linkId), enterData("car_1"))

    f.getState().currentSpeed should not be 13.9
    DynamicWeightCache.getCost(linkId) shouldBe None

    f.setTick(10L)
    f.handler.handleEnterLinkMeso(event("car_2", linkId), enterData("car_2"))
    val published = DynamicWeightCache.getCost(linkId)
    published shouldBe defined
    published.get.currentSpeed shouldBe f.getState().currentSpeed
  }

  /** Regression coverage for docs/EVENTS_MESSAGES_ANALYSIS.md §7 recommendation 6:
    * `EnterLinkData.maxAcceleration`/`maxDeceleration` were removed from the wire; the Link now
    * derives them from `data.actorType` via `ActorTypeEnum.microMaxAcceleration`/
    * `microMaxDeceleration` instead. This locks in that the per-vehicle-type MICRO car-following
    * bounds are unchanged from what each vehicle actor used to send explicitly (formerly
    * `Movable`/`Bicycle`/`Bus`/`Motorcycle.microMaxAcceleration`/`microMaxDeceleration`
    * overrides).
    */
  "handleEnterLinkMicro" should "seed VehicleInLane.maxAcceleration/maxDeceleration from actorType, not a wire field" in {
    val cases = Seq(
      ActorTypeEnum.Car -> (2.6, 4.5),
      ActorTypeEnum.Bicycle -> (1.0, 3.0),
      ActorTypeEnum.Bus -> (1.2, 3.5),
      ActorTypeEnum.Motorcycle -> (3.5, 5.0)
    )

    cases.foreach {
      case (actorType, (expectedAcceleration, expectedDeceleration)) =>
        val linkId = s"link_micro_${actorType}"
        val f = newFixture(linkId, newMicroState(linkId))

        f.handler.handleEnterLinkMicro(event("veh_1", linkId), enterData("veh_1", actorType))

        val vehicle = f.getState().vehiclesByLane.values.flatten.find(_.actorId == "veh_1")
        vehicle shouldBe defined
        vehicle.get.maxAcceleration shouldBe expectedAcceleration
        vehicle.get.maxDeceleration shouldBe expectedDeceleration
    }
  }
}
