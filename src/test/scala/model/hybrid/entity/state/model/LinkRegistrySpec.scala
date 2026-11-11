package org.interscity.htc
package model.hybrid.entity.state.model

import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import core.util.JsonUtil
import model.hybrid.entity.state.{ LinkState, RailLinkState }
import model.hybrid.entity.state.enumeration.ActorTypeEnum
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Random

class LinkRegistrySpec extends AnyFlatSpec with Matchers {

  private def reg(id: Long) = LinkRegister(id, "shard", ActorTypeEnum.Car, LoadBalancedDistributed, 4.5)

  "LinkRegistry" should "agree with the old Set[LinkRegister] scanned by actor id" in {
    for (seed <- 1 to 50) {
      val rnd = new Random(seed)
      val fast = new LinkRegistry
      val naive = mutable.Set.empty[LinkRegister]
      for (_ <- 1 to 2000) {
        val id = rnd.nextInt(60).toLong
        if (rnd.nextBoolean()) {
          // handlers only add after checking the actor is absent
          if (!naive.exists(_.actorId == id)) naive.add(reg(id))
          if (!fast.containsActor(id)) fast.add(reg(id))
        } else {
          val was = naive.exists(_.actorId == id)
          naive.filterInPlace(_.actorId != id)
          fast.removeActor(id) shouldBe was
        }
        fast.toSet shouldBe naive.toSet
        fast.size shouldBe naive.size
        fast.containsActor(id) shouldBe naive.exists(_.actorId == id)
      }
    }
  }

  it should "behave as a mutable.Set for += and -=" in {
    val r = new LinkRegistry
    r += reg(1)
    r += reg(2)
    r -= reg(1)
    r.toSet shouldBe Set(reg(2))
    r.contains(reg(2)) shouldBe true
  }

  "LinkState.registered" should "round-trip through JsonUtil as a plain JSON array" in {
    val state = LinkState(0L, from = 1L, to = 2L, length = 100, lanes = 1, speedLimit = 10, capacity = 5,
      freeSpeed = 10)
    state.registered += reg(7)
    state.registered += reg(9)
    val json = JsonUtil.toJson(state)
    json should include("\"registered\":[")
    val back = JsonUtil.fromJson[LinkState](json)
    back.registered shouldBe a[LinkRegistry]
    back.registered.toSet shouldBe Set(reg(7), reg(9))
    back.registered.containsActor(7L) shouldBe true
  }

  "RailLinkState.registered" should "round-trip through JsonUtil as a plain JSON array" in {
    val state = RailLinkState(0L, from = 1L, to = 2L, length = 100, lanes = 1, speedLimit = 10, capacity = 5,
      freeSpeed = 10)
    state.registered += reg(3)
    val json = JsonUtil.toJson(state)
    json should include("\"registered\":[")
    val back = JsonUtil.fromJson[RailLinkState](json)
    back.registered.toSet shouldBe Set(reg(3))
    back.registered.removeActor(3L) shouldBe true
    back.registered.isEmpty shouldBe true
  }
}
