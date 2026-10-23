package org.interscity.htc
package model.hybrid.entity.state

import core.util.JsonUtil
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class NodeCounterSnapshotSpec extends AnyFlatSpec with Matchers {
  "a Node checkpoint" should "restore numeric map values as Int before capacity and signal replay arithmetic" in {
    val original = NodeState(0L, latitude = 0.0, longitude = 0.0, links = List(7L),
      signalWaitingCounts = mutable.Map(7L -> 3), availableCapacity = mutable.Map(7L -> 12))
    val restored = JsonUtil.fromJson[NodeState](JsonUtil.toJson(original))
    restored.availableCapacity.updateWith(7L)(_.map(_ + 2))
    restored.signalWaitingCounts.updateWith(7L)(_.map(_ - 1))
    restored.availableCapacity(7L) shouldBe 14
    restored.signalWaitingCounts(7L) shouldBe 2
    original.availableCapacity(7L) shouldBe 12
    original.signalWaitingCounts(7L) shouldBe 3
  }
}
