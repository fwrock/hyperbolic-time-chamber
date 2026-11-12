package org.interscity.htc
package core.actor.manager.load

import scala.collection.immutable.TreeMap
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EmptyInputGapSpec extends AnyFlatSpec with Matchers {
  private val counts = TreeMap(20000L -> 3, 20005L -> 1)

  "emptyGapEnd" should "cover up to the tick before the next indexed actor" in {
    ProgressiveLoadDataManager.emptyGapEnd(counts, 0L, 1000L) shouldBe Some(19999L)
  }

  it should "not cover a range that contains an actor" in {
    ProgressiveLoadDataManager.emptyGapEnd(counts, 19990L, 20000L) shouldBe None
    ProgressiveLoadDataManager.emptyGapEnd(counts, 20000L, 20000L) shouldBe None
  }

  it should "cover nothing extra when the next actor is just past the requested range" in {
    ProgressiveLoadDataManager.emptyGapEnd(counts, 19000L, 19999L) shouldBe Some(19999L)
  }

  it should "ignore zero-count entries" in {
    ProgressiveLoadDataManager.emptyGapEnd(TreeMap(500L -> 0, 20000L -> 2), 0L, 100L) shouldBe Some(19999L)
  }

  it should "start from the first tick not yet loaded, not from earlier actors" in {
    ProgressiveLoadDataManager.emptyGapEnd(TreeMap(5L -> 4, 20000L -> 2), 6L, 100L) shouldBe Some(19999L)
  }

  it should "return None when the index has no actor at or after the start" in {
    ProgressiveLoadDataManager.emptyGapEnd(counts, 20006L, 21000L) shouldBe None
    ProgressiveLoadDataManager.emptyGapEnd(TreeMap.empty[Long, Int], 0L, 1000L) shouldBe None
  }
}
