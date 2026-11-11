package org.interscity.htc
package core.actor.manager.time

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Random

class WatermarkIndexSpec extends AnyFlatSpec with Matchers {

  "WatermarkIndex" should "match the old Map.withDefaultValue(-1) and values.min, including lowered values" in {
    for (seed <- 1 to 50) {
      val rnd = new Random(seed)
      val fast = new WatermarkIndex
      val naive = mutable.Map.empty[Long, Long].withDefaultValue(-1L)
      for (_ <- 1 to 2000) {
        val id = rnd.nextInt(40).toLong
        rnd.nextInt(3) match {
          case 0 => val t = rnd.nextInt(100).toLong; fast(id) = t; naive(id) = t // may lower (rollback resync)
          case 1 => fast.remove(id); naive.remove(id)
          case _ => ()
        }
        fast(id) shouldBe naive(id)
        fast.isEmpty shouldBe naive.isEmpty
        fast.min shouldBe (if (naive.isEmpty) None else Some(naive.values.min))
      }
    }
  }
}
