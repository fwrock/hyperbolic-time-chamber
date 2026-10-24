package org.interscity.htc
package model.hybrid.entity.state

import core.util.JsonUtil
import org.interscity.htc.model.hybrid.entity.state.model.*
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class TransitStationCheckpointSpec extends AnyFlatSpec with Matchers {
  "a bus station checkpoint" should "retain populated route keys, queued vehicle identities and node mappings" in {
    val state = BusStationState(0L, "station", 1L, 2L, mutable.LinkedHashMap(11L -> 1L, 12L -> 2L), 20L,
      mutable.Queue(BusInformation(21L, "line", 40, 2, 12.0)),
      goingRoute = Some(mutable.Map(SubRoutePair(11L, 12L) -> mutable.Queue((Identify(id = 5L), Identify(id = 2L))))),
      returningRoute = Some(mutable.Map(SubRoutePair(12L, 11L) -> mutable.Queue((Identify(id = 6L), Identify(id = 1L))))))
    val restored = JsonUtil.convertValue[BusStationState](JsonUtil.toJson(state))
    restored.busStops.keys.toList.sorted shouldBe List(11L, 12L)
    restored.busStops(11L) shouldBe 1L
    restored.buses.dequeue().actorId shouldBe 21L
    restored.goingRoute.get(SubRoutePair(11L, 12L)).dequeue()._1.id shouldBe 5L
    restored.returningRoute.get(SubRoutePair(12L, 11L)).dequeue()._2.id shouldBe 1L
  }
  "a subway station checkpoint" should "retain populated line clocks, vehicle queues and route records" in {
    val state = SubwayStationState(0L, "station", 3L, true, true,
      mutable.Map("line" -> SubwayLineInformation(20L, 40L)),
      mutable.Map("line" -> mutable.Queue(SubwayInformation("line", 21L, 100, 2, 20.0, 20L))),
      mutable.Map("line" -> mutable.Queue(SubwayRouteEntry(SubwayStationNode(18L, 1L), 19L))),
      mutable.Map("line" -> mutable.Seq(Identify(id = 22L))))
    val restored = JsonUtil.convertValue[SubwayStationState](JsonUtil.toJson(state))
    restored.lines("line").nextTick += 20L
    restored.lines("line").nextTick shouldBe 60L
    restored.subways("line").dequeue().actorId shouldBe 21L
    restored.linesRoute("line").dequeue().stationNode.nodeId shouldBe 1L
    restored.people("line").head.id shouldBe 22L
  }
}
