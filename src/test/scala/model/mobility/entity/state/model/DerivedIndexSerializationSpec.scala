package org.interscity.htc
package model.mobility.entity.state.model

import core.util.JsonUtil
import model.mobility.entity.state.enumeration.SimulationModeEnum
import model.mobility.entity.state.{LinkState, NodeState}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Derived lookup indexes must neither be persisted nor lost on a JSON checkpoint round trip. */
class DerivedIndexSerializationSpec extends AnyFlatSpec with Matchers {
  "NodeState" should "round-trip through JSON after its junction index was used, without persisting the index" in {
    val node = NodeState(startTick = 0, latitude = 0, longitude = 0, links = List(1L, 2L, 3L),
      junctionMovements = List(JunctionMovement(1L, 2L, 100L), JunctionMovement(1L, 3L, 101L)),
      movementSignals = List(MovementSignalBinding(1L, 2L, 7L)),
      conflicts = List(JunctionConflict(100L, 101L, source = "test", priorityConnector = Some(100L))))
    node.junction.conflictsOf(100L).size shouldBe 1
    val json = JsonUtil.toJson(node)
    json should not include "\"junction\""
    val restored = JsonUtil.fromJsonClassName[NodeState](json, classOf[NodeState].getName)
    restored.conflicts shouldBe node.conflicts
    restored.junction.conflictsOf(101L).size shouldBe 1
    restored.junction.movementOf(101L).map(_.outgoingLinkId) shouldBe Some(3L)
    restored.junction.bindingsOfOrigin(7L).size shouldBe 1
  }

  "LinkState" should "keep in-place speed and congestion through a JSON checkpoint, without the lane index" in {
    val link = LinkState(0, from = 1, to = 2, length = 100, lanes = 2, speedLimit = 50, capacity = 100,
      freeSpeed = 50, simulationMode = SimulationModeEnum.MICRO, microTicksPerGlobalTick = 1,
      laneConnections = List(LaneConnection(9L, 1, 1))).initializeMicroLanes()
    link.laneConnectionIndex.legal(9L) shouldBe Vector(1)
    link.currentSpeed = 12.5
    link.congestionFactor = 1.75
    val json = JsonUtil.toJson(link)
    json should not include "laneConnectionIndex"
    val restored = JsonUtil.fromJsonClassName[LinkState](json, classOf[LinkState].getName)
    restored.currentSpeed shouldBe 12.5
    restored.congestionFactor shouldBe 1.75
    restored.laneConnectionIndex.legal(9L) shouldBe Vector(1)
    link.currentSpeed = 3.0
    restored.currentSpeed shouldBe 12.5
  }
}
