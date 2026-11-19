package org.interscity.htc
package model.hybrid.support.node

import core.entity.event.ActorInteractionEvent
import core.util.JsonUtil
import model.hybrid.entity.event.data.link.LinkCapacityFreedData
import model.hybrid.entity.event.data.vehicle.RequestLinkAccessData
import model.hybrid.entity.event.node.LinkAccessData
import model.hybrid.entity.state.NodeState
import model.hybrid.entity.state.enumeration.LinkCapacityStateEnum
import model.hybrid.entity.state.model.{JunctionConflict, JunctionMovement}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

/** G04: crossing connectors exclude each other; priority and age order the waiters. */
class JunctionConflictSpec extends AnyFlatSpec with Matchers {
  // Movement 1->2 uses connector 20 (a yielding left turn); movement 3->4 uses connector 30 (priority through).
  private val movements = List(JunctionMovement(1, 2, 20), JunctionMovement(3, 4, 30), JunctionMovement(5, 6, 40))
  private def node(conflicts: List[JunctionConflict]) = NodeState(0, latitude = 0, longitude = 0, links = List(1, 2, 3, 4, 5, 6),
    junctionMovements = movements, conflicts = conflicts,
    availableCapacity = mutable.Map(20L -> 5, 30L -> 5, 40L -> 5))
  private val priority30 = List(JunctionConflict(20, 30, Some(30), "test-rule"))
  private val noPriority = List(JunctionConflict(20, 30, None, "test-rule"))

  private class Harness(var s: NodeState) {
    var tick = 1L
    val sent = mutable.Buffer.empty[(Long, LinkAccessData)]
    val handler = new NodeEventHandler(() => s, () => "9", () => tick, mutable.Map.empty, (_, _) => (),
      (id, _, d, _) => { sent += ((id, d.asInstanceOf[LinkAccessData])); () }, _ => None, _ => (), _ => ())
    private def event(id: Long, seq: Long) = ActorInteractionEvent(tick, tick, id, "hybrid.actor.Car", id.toString,
      "hybrid.actor.Car", data = "unused", resourceId = "test", seq = seq)
    def request(vehicle: Long, approach: Long, target: Long): Unit =
      handler.handleRequestLinkAccess(event(vehicle, vehicle), RequestLinkAccessData(target, signalAlreadyCleared = true,
        approachLinkId = approach))
    def free(connector: Long): Unit = handler.handleLinkCapacityFreed(LinkCapacityFreedData(connector, 1))
    def grants = sent.collect { case (v, a) if a.junctionLink.nonEmpty => (v, a.junctionLink.get.linkId) }.toList
    def waits = sent.collect { case (v, a) if a.capacityState == LinkCapacityStateEnum.Full => v }.toList
  }

  "Crossing connectors" should "exclude each other and release the waiter when the occupant leaves" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.grants shouldBe List((11L, 20L))
    h.request(12, 3, 4) // connector 20 is occupied: must wait even though it has priority
    h.waits shouldBe List(12L)
    h.free(20)
    h.grants shouldBe List((11L, 20L), (12L, 30L))
    h.free(20)
    h.grants should have size 2 // no duplicate grant
  }

  it should "let a waiting priority request overtake a later lower-priority one" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2) // occupies 20
    h.tick = 2; h.request(12, 3, 4) // priority, waits
    h.tick = 3; h.request(13, 1, 2) // arrives later; a higher-priority conflicting request is pending
    h.waits shouldBe List(12L, 13L)
    h.free(20)
    h.grants.map(_._1) shouldBe List(11L, 12L) // 12 first; 13 still blocked by 12's occupancy
    h.free(30)
    h.grants.map(_._1) shouldBe List(11L, 12L, 13L)
  }

  it should "serve equal-priority conflicts by request age, not by arrival at the connector" in {
    val h = new Harness(node(noPriority))
    h.request(11, 1, 2)
    h.tick = 2; h.request(12, 3, 4) // older waiter on 30
    h.tick = 3; h.request(13, 1, 2) // younger on the same connector as the occupant: still waits for 12
    h.waits shouldBe List(12L, 13L)
    h.free(20)
    h.grants.map(_._1) shouldBe List(11L, 12L)
  }

  it should "not affect connectors that have no conflict rule" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.request(14, 5, 6)
    h.grants.map(_._1) shouldBe List(11L, 14L)
  }

  it should "survive a JSON checkpoint with occupancy and waiters" in {
    val h = new Harness(node(priority30))
    h.request(11, 1, 2)
    h.request(12, 3, 4)
    val restored = JsonUtil.fromJson[NodeState](JsonUtil.toJson(h.s))
    restored.conflicts shouldBe priority30
    restored.occupiedConnectors(20L) shouldBe 1
    val h2 = new Harness(restored)
    h2.free(20)
    h2.grants.map(_._1) shouldBe List(12L)
  }

  it should "require a named source and known connectors" in {
    an[IllegalArgumentException] should be thrownBy JunctionConflict(20, 30, None, " ")
    an[IllegalArgumentException] should be thrownBy node(List(JunctionConflict(20, 99, None, "x")))
    an[IllegalArgumentException] should be thrownBy JunctionConflict(20, 30, Some(40), "x")
  }
}
