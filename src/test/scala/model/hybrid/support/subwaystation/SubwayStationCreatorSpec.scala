package org.interscity.htc
package model.hybrid.support.subwaystation

import core.entity.actor.ShardActorId
import model.hybrid.entity.state.SubwayStationState
import model.hybrid.entity.state.model.{ SubwayInformation, SubwayLineInformation, SubwayRouteEntry, SubwayStationNode }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Covers the Time-Warp dynamic-actor-spawn fix's prepare/spawn split (see
  * `docs/KNOWN_GAPS.md`'s dynamic-actor-spawn entry): `prepareSubway` must never touch the
  * irrevocable `spawnDynamicActorFn`, `spawnPreparedSubway` must call it with exactly the JSON
  * `prepareSubway` produced, `prepareSubway` must still fail the same way as the old
  * `createSubway` did on an empty route, and `prepareSubwaysFrom` must dequeue+prepare without
  * ever spawning.
  */
class SubwayStationCreatorSpec extends AnyFlatSpec with Matchers {

  private def subwayInfo(actorId: Long = 42L, line: String = "line-1"): SubwayInformation =
    SubwayInformation(
      line = line,
      actorId = actorId,
      capacity = 200,
      numberOfPorts = 4,
      velocity = 20.0,
      stopTime = 30L
    )

  private def stateWithRoute(line: String = "line-1"): SubwayStationState =
    SubwayStationState(
      startTick = 0L,
      name = "station-1",
      nodeId = 1L,
      terminal = false,
      garage = true,
      lines = mutable.Map(line -> SubwayLineInformation(interval = 100L)),
      subways = mutable.Map(line -> mutable.Queue(subwayInfo(line = line))),
      linesRoute = mutable.Map(
        line -> mutable.Queue(
          SubwayRouteEntry(stationNode = SubwayStationNode(stationId = 1L, nodeId = 10L), railLinkId = 1000L),
          SubwayRouteEntry(stationNode = SubwayStationNode(stationId = 2L, nodeId = 20L), railLinkId = 1001L)
        )
      )
    )

  private def stateWithoutRoute(line: String = "line-1"): SubwayStationState =
    SubwayStationState(
      startTick = 0L,
      name = "station-1",
      nodeId = 1L,
      terminal = false,
      garage = true,
      lines = mutable.Map(line -> SubwayLineInformation(interval = 100L)),
      subways = mutable.Map(line -> mutable.Queue(subwayInfo(line = line))),
      linesRoute = mutable.Map.empty
    )

  private def newCreator(
    state:      SubwayStationState,
    spawnCalls: mutable.ListBuffer[(String, Long, String)],
    deps:       mutable.Map[String, ShardActorId] = mutable.Map.empty,
    tick:       Long = 100L
  ): SubwayStationCreator =
    new SubwayStationCreator(
      getStateFn          = () => state,
      entityIdFn          = () => "station-1",
      currentTickFn       = () => tick,
      reportFn            = (_, _) => (),
      spawnDynamicActorFn = (ct, eid, sd) => spawnCalls += ((ct, eid, sd)),
      addDependencyFn     = (id, ref) => deps(id) = ref,
      logWarnFn           = _ => (),
      logDebugFn          = _ => (),
      logErrorFn          = _ => ()
    )

  "prepareSubway" should "never call the spawn lambda" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    val json = creator.prepareSubway(subwayInfo(), "line-1", startTick = 200L)

    json should not be empty
    spawnCalls shouldBe empty
  }

  it should "throw IllegalStateException when no route is available, exactly as the old createSubway did" in {
    val state      = stateWithoutRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    an[IllegalStateException] should be thrownBy creator.prepareSubway(subwayInfo(), "line-1", startTick = 200L)
    spawnCalls shouldBe empty
  }

  "spawnPreparedSubway" should "call the spawn lambda with exactly the JSON prepareSubway produced" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)
    val subway     = subwayInfo(actorId = 7L)

    val json = creator.prepareSubway(subway, "line-1", startTick = 200L)
    creator.spawnPreparedSubway(subway, json)

    spawnCalls should have size 1
    val (classType, entityId, stateData) = spawnCalls.head
    classType shouldBe "hybrid.actor.Subway"
    entityId shouldBe 7L
    stateData shouldBe json
  }

  "prepareSubwaysFrom" should "dequeue and prepare a subway per line without ever spawning" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    val prepared = creator.prepareSubwaysFrom(state.lines)

    prepared should have size 1
    prepared.head.subway.actorId shouldBe 42L
    prepared.head.stateJson should not be empty
    spawnCalls shouldBe empty
    state.subways("line-1") shouldBe empty
  }

  it should "re-enqueue the subway and leave it unprepared when the route is missing" in {
    val state      = stateWithoutRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    val prepared = creator.prepareSubwaysFrom(state.lines)

    prepared shouldBe empty
    spawnCalls shouldBe empty
    state.subways("line-1") should have size 1
  }

  "createSubway (via createSubwayFrom)" should "still spawn immediately (non-Time-Warp path), unchanged" in {
    val state      = stateWithRoute()
    val spawnCalls = mutable.ListBuffer.empty[(String, Long, String)]
    val creator    = newCreator(state, spawnCalls)

    val spawned = creator.createSubwayFrom(state.lines)

    spawned shouldBe 1
    spawnCalls should have size 1
    spawnCalls.head._2 shouldBe 42L
  }
}
