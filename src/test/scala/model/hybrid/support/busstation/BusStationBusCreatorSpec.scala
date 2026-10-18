package org.interscity.htc
package model.hybrid.support.busstation

import model.hybrid.entity.state.BusStationState
import model.hybrid.entity.state.model.{ BusInformation, SubRoutePair }

import org.htc.protobuf.core.entity.actor.Identify

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Covers the Time-Warp dynamic-actor-spawn fix's prepare/spawn split (see
  * `docs/KNOWN_GAPS.md`'s dynamic-actor-spawn entry): `prepareBus` must never touch the
  * irrevocable `spawnDynamicActorFn`, `spawnPrepared` must call it with exactly the JSON
  * `prepareBus` produced, and `prepareBus` must still fail the same way as the old `createBus`
  * did on an empty route.
  */
class BusStationBusCreatorSpec extends AnyFlatSpec with Matchers {

  private def newState(interval: Long = 10L): BusStationState =
    BusStationState(
      startTick = 0L,
      name = "station-1",
      origin = 1L,
      destination = 2L,
      busStops = mutable.LinkedHashMap(1L -> 100L, 2L -> 200L),
      interval = interval,
      buses = mutable.Queue.empty
    )

  private def bus(actorId: Long = 42L): BusInformation =
    BusInformation(
      actorId = actorId,
      label = "line-1",
      capacity = 40,
      numberOfPorts = 2,
      size = 12.0
    )

  private def newCreator(
    state:               BusStationState,
    spawnCalls:          mutable.ListBuffer[(String, Long, String)],
    tick:                Long = 100L
  ): BusStationBusCreator = {
    val routeCalculator = new BusStationRouteCalculator(
      getStateFn      = () => state,
      entityIdFn      = () => "station-1",
      currentTickFn   = () => tick,
      getBlockingEcFn = () => scala.concurrent.ExecutionContext.global,
      logInfoFn       = _ => (),
      logWarnFn       = _ => (),
      logDebugFn      = _ => ()
    )
    new BusStationBusCreator(
      getStateFn          = () => state,
      entityIdFn          = () => "station-1",
      currentTickFn       = () => tick,
      reportFn            = (_, _) => (),
      spawnDynamicActorFn = (ct, eid, sd) => spawnCalls += ((ct, eid, sd)),
      routeCalculator     = routeCalculator,
      logInfoFn           = _ => (),
      logWarnFn           = _ => (),
      logDebugFn          = _ => ()
    )
  }

  private def stateWithRoute(): BusStationState = {
    val state = newState()
    state.copy(
      goingRoute = Some(
        mutable.Map(SubRoutePair(1L, 2L) -> mutable.Queue((Identify(id = 100L), Identify(id = 200L))))
      ),
      returningRoute = Some(mutable.Map.empty)
    )
  }

  "prepareBus" should "never call the spawn lambda" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    val json = creator.prepareBus(bus())

    json should not be empty
    spawnCalls shouldBe empty
  }

  it should "throw IllegalStateException when no route is available, exactly as the old createBus did" in {
    val state      = newState()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    an[IllegalStateException] should be thrownBy creator.prepareBus(bus())
    spawnCalls shouldBe empty
  }

  "spawnPrepared" should "call the spawn lambda with exactly the JSON prepareBus produced" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)
    val theBus     = bus(actorId = 7L)

    val json = creator.prepareBus(theBus)
    creator.spawnPrepared(theBus, json)

    spawnCalls should have size 1
    val (classType, entityId, stateData) = spawnCalls.head
    classType shouldBe "hybrid.actor.Bus"
    entityId shouldBe 7L
    stateData shouldBe json
  }

  "createBus" should "still spawn immediately (non-Time-Warp path), unchanged" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    creator.createBus(bus(actorId = 9L))

    spawnCalls should have size 1
    spawnCalls.head._2 shouldBe 9L
  }
}
