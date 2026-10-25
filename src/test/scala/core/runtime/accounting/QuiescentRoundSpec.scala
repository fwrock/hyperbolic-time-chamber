package org.interscity.htc
package core.runtime.accounting

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QuiescentRoundSpec extends AnyFlatSpec with Matchers {
  "a quiescent round" should "reject stale idle observations and require every participant" in {
    val round = new QuiescentRound(2L, Set("lp", "manager"))
    round.acknowledgeFrozen("lp", 1L)
    round.allFrozen shouldBe false
    round.report("lp", RuntimeSnapshot(1L, None, 0, 0))
    round.report("manager", RuntimeSnapshot(2L, None, 0, 0))
    round.complete shouldBe false
    round.acknowledgeFrozen("lp", 2L)
    round.acknowledgeFrozen("manager", 2L)
    round.allFrozen shouldBe true
    round.report("lp", RuntimeSnapshot(2L, Some(10L), 1, 0))
    round.complete shouldBe true
    round.minimum shouldBe Some(10L)
    round.blocked shouldBe 1
  }
}
