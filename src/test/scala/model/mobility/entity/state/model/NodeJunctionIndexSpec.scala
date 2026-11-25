package org.interscity.htc
package model.mobility.entity.state.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

class NodeJunctionIndexSpec extends AnyFlatSpec with Matchers {
  "NodeJunctionIndex" should "match list scans over randomized junction tables" in {
    val rnd = new Random(5)
    (1 to 300).foreach { _ =>
      val connectors = rnd.shuffle((100L to 111L).toList).take(2 + rnd.nextInt(8))
      val movements = connectors.map(c => JunctionMovement(rnd.nextInt(4).toLong, 10L + rnd.nextInt(4), c))
      val bindings = List.fill(rnd.nextInt(10))(MovementSignalBinding(rnd.nextInt(4).toLong, 10L + rnd.nextInt(4), 200L + rnd.nextInt(3)))
      val pairs = connectors.combinations(2).toList
      val conflicts = rnd.shuffle(pairs).take(rnd.nextInt(pairs.size + 1)).map { pair => val a = pair.head; val b = pair(1);
        JunctionConflict(a, b, source = "test", priorityConnector = Option.when(rnd.nextBoolean())(if (rnd.nextBoolean()) a else b))
      }
      val index = new NodeJunctionIndex(movements, bindings, conflicts)
      index.conflictConnectors shouldBe conflicts.flatMap(r => List(r.connectorA, r.connectorB)).distinct.sorted
      (0L to 4L).foreach { in => (9L to 14L).foreach { out =>
        index.movementsFor(in, out) shouldBe movements.filter(m => m.incomingLinkId == in && m.outgoingLinkId == out)
        index.bindingsFor(in, out) shouldBe bindings.filter(b => b.incomingLinkId == in && b.outgoingLinkId == out)
      }}
      (9L to 14L).foreach(out => index.movementsInto(out) shouldBe movements.filter(_.outgoingLinkId == out))
      (198L to 203L).foreach(o => index.bindingsOfOrigin(o) shouldBe bindings.filter(_.origin == o))
      (99L to 112L).foreach { c =>
        index.movementOf(c) shouldBe movements.find(_.connectorLinkId == c)
        index.conflictsOf(c) shouldBe conflicts.filter(_.involves(c))
        index.conflictsWithPriority(c) shouldBe conflicts.filter(_.priorityConnector.contains(c))
      }
    }
  }
}
