package org.interscity.htc
package core.actor.manager.time

import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Random

/** Replays random operation sequences against the new indexed structures and against the naive
  * structures they replaced (hash set scanned by id; hash map/set scanned with `.min`), checking
  * they agree after every step. Pure logic, no Pekko.
  */
class RunningEventsAndScheduleSpec extends AnyFlatSpec with Matchers {

  private def ident(id: Long) = Identify(id = id, classType = "example.Car", actorType = "LoadBalancedDistributed")

  "RunningEvents" should "behave like the old Set[Identify] scanned by id" in {
    for (seed <- 1 to 50) {
      val rnd = new Random(seed)
      val fast = new RunningEvents
      val naive = mutable.Set.empty[Identify]
      for (_ <- 1 to 2000) {
        val id = rnd.nextInt(60).toLong
        if (rnd.nextBoolean()) {
          if (!naive.exists(_.id == id)) naive.add(ident(id))
          fast.add(ident(id))
        } else {
          val wasNaive = naive.exists(_.id == id)
          naive.filterInPlace(_.id != id)
          fast.removeId(id) shouldBe wasNaive
        }
        fast.map(_.id).toSet shouldBe naive.map(_.id).toSet
        fast.size shouldBe naive.size
        fast.isEmpty shouldBe naive.isEmpty
        fast.containsId(id) shouldBe naive.exists(_.id == id)
      }
    }
  }

  "ordered tick schedule" should "return the same next tick as the old scan-all-keys version" in {
    for (seed <- 1 to 50) {
      val rnd = new Random(seed)
      val tree = mutable.TreeMap.empty[Long, mutable.Set[Identify]]
      val treeFinish = mutable.TreeSet.empty[Long]
      val hash = mutable.Map.empty[Long, mutable.Set[Identify]]
      val hashFinish = mutable.Set.empty[Long]
      def fastNext: Option[Long] = (tree.headOption.map(_._1), treeFinish.headOption) match {
        case (Some(a), Some(b)) => Some(math.min(a, b))
        case (a, b)             => a.orElse(b)
      }
      def naiveNext: Option[Long] = {
        val all = hash.keys ++ hashFinish
        if (all.nonEmpty) Some(all.min) else None
      }
      for (_ <- 1 to 2000) {
        val tick = rnd.nextInt(80).toLong
        rnd.nextInt(4) match {
          case 0 =>
            tree.getOrElseUpdate(tick, mutable.Set.empty).add(ident(rnd.nextInt(30)))
            hash.getOrElseUpdate(tick, mutable.Set.empty).add(ident(rnd.nextInt(30)))
          case 1 => treeFinish.add(tick); hashFinish.add(tick)
          case 2 => tree.remove(tick); hash.remove(tick); treeFinish.remove(tick); hashFinish.remove(tick)
          case _ =>
            // processTick's "earliest bucket at or before tick"
            tree.headOption.map(_._1).filter(_ <= tick) shouldBe hash.keys.filter(_ <= tick).minOption
        }
        fastNext shouldBe naiveNext
      }
    }
  }
}
