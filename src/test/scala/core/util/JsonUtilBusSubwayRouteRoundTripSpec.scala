package org.interscity.htc
package core.util

import model.hybrid.entity.state.{ BusState, SubwayState }
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Round-trips `BusState`/`SubwayState` through the same `JsonUtil.toJson`/`fromJsonClassName`
  * mechanism `BaseActor.buildMigrationSnapshot`/`applyMigrationSnapshot` use, mirroring
  * `JsonUtilMovableRouteRoundTripSpec` (which already confirmed the generic `MovableState`
  * mechanism is sound for `CarState`). Exists to test the two remaining hypotheses from
  * `docs/TIME_WARP_ARCHITECTURE_AUDIT.md` §2.4/§4 for the still-open Bus/Subway route-state-after-
  * rollback gap: that something specific to `BusState`/`SubwayState` (not `MovableState` itself)
  * corrupts the snapshot on the same round-trip that loses `movableCurrentPath`.
  */
class JsonUtilBusSubwayRouteRoundTripSpec extends AnyFlatSpec with Matchers {

  "JsonUtil" should "round-trip movableBestRoute/movableCurrentPath through BusState" in {
    val original = BusState(
      startTick = 0L,
      label = "line-42",
      capacity = 50,
      busStops = Map(10L -> 100L, 20L -> 200L),
      numberOfPorts = 2,
      people = mutable.Map(1L -> Identify(id = 1L, resourceId = "r1", classType = "Person", actorRef = "ref1", actorType = "Person")),
      origin = 1L,
      destination = 2L,
      size = 12.0
    )
    original.updateBestRoute(Some(mutable.Queue((1L, 2L), (3L, 4L))))
    original.updateCurrentPath(Some((1L, 2L)))
    original.updateCurrentNode(1L)

    val json = JsonUtil.toJson(original)
    println(s"BUS SERIALIZED: $json")

    val restored = JsonUtil.fromJson[BusState](json)

    withClue("movableBestRoute lost across JSON round-trip: ") {
      restored.getBestRoute shouldBe Some(mutable.Queue((1L, 2L), (3L, 4L)))
    }
    withClue("movableCurrentPath lost across JSON round-trip: ") {
      restored.getCurrentPath shouldBe Some((1L, 2L))
    }
    withClue("movableCurrentNode lost across JSON round-trip: ") {
      restored.getCurrentNode shouldBe 1L
    }
    withClue("busStops keys corrupted (Map[Long,Long] key-type bug) across JSON round-trip: ") {
      restored.busStops shouldBe Map(10L -> 100L, 20L -> 200L)
    }
    withClue("people keys corrupted (Map[Long,Identify] key-type bug) across JSON round-trip: ") {
      restored.people.keySet shouldBe Set(1L)
    }
  }

  "JsonUtil" should "round-trip movableBestRoute/movableCurrentPath through SubwayState" in {
    val original = SubwayState(
      startTick = 0L,
      capacity = 200,
      numberOfPorts = 4,
      velocity = 15.0,
      stopTime = 30L,
      subwayStations = mutable.Map(10L -> 100L, 20L -> 200L),
      origin = 1L,
      destination = 2L,
      passengers = mutable.Map(1L -> Identify(id = 1L, resourceId = "r1", classType = "Person", actorRef = "ref1", actorType = "Person")),
      line = "L1"
    )
    original.updateBestRoute(Some(mutable.Queue((1L, 2L), (3L, 4L))))
    original.updateCurrentPath(Some((1L, 2L)))
    original.updateCurrentNode(1L)

    val json = JsonUtil.toJson(original)
    println(s"SUBWAY SERIALIZED: $json")

    val restored = JsonUtil.fromJson[SubwayState](json)

    withClue("movableBestRoute lost across JSON round-trip: ") {
      restored.getBestRoute shouldBe Some(mutable.Queue((1L, 2L), (3L, 4L)))
    }
    withClue("movableCurrentPath lost across JSON round-trip: ") {
      restored.getCurrentPath shouldBe Some((1L, 2L))
    }
    withClue("movableCurrentNode lost across JSON round-trip: ") {
      restored.getCurrentNode shouldBe 1L
    }
    withClue("subwayStations keys corrupted (Map[Long,Long] key-type bug) across JSON round-trip: ") {
      restored.subwayStations shouldBe mutable.Map(10L -> 100L, 20L -> 200L)
    }
    withClue("passengers keys corrupted (Map[Long,Identify] key-type bug) across JSON round-trip: ") {
      restored.passengers.keySet shouldBe Set(1L)
    }
  }
}
