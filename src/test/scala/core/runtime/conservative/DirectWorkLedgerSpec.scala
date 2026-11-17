package org.interscity.htc
package core.runtime.conservative

import core.runtime.accounting.LogicalProcessId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DirectWorkLedgerSpec extends AnyFlatSpec with Matchers {
  private val entityA = LogicalProcessId("Car", 1L)
  private val entityB = LogicalProcessId("Link", 2L)
  private val entityC = LogicalProcessId("Node", 3L)
  private val root = DirectWork(DirectWorkId(entityA, "entityA", -1L), None, entityA, 10L)
  private val ab = DirectWork(DirectWorkId(entityA, "entityA", 1L), Some(root.id), entityB, 10L)
  private val bc = DirectWork(DirectWorkId(entityB, "entityB", 1L), Some(ab.id), entityC, 10L)

  private def ledger(): DirectWorkLedger = {
    val result = new DirectWorkLedger
    result.begin(10L)
    result.enroll(root)
    result
  }

  "a direct work ledger" should "retain a delayed receiver after its sender finishes" in {
    val l = ledger()
    l.complete(DirectWorkCompletion(root, Vector(ab)))
    l.isClosed shouldBe false
    intercept[IllegalArgumentException](l.begin(11L))
    l.complete(DirectWorkCompletion(ab, Vector.empty))
    l.isClosed shouldBe true
    l.begin(11L)
  }

  it should "match a receiver completion that arrives before its declaration" in {
    val l = ledger()
    l.complete(DirectWorkCompletion(ab, Vector.empty))
    l.isClosed shouldBe false
    l.pendingCount shouldBe 1
    l.complete(DirectWorkCompletion(root, Vector(ab)))
    l.isClosed shouldBe true
  }

  it should "close a three-actor chain in every completion order" in {
    val receipts = Vector(
      DirectWorkCompletion(root, Vector(ab)),
      DirectWorkCompletion(ab, Vector(bc)),
      DirectWorkCompletion(bc, Vector.empty)
    )
    receipts.permutations.foreach {
      permutation =>
        val l = ledger()
        permutation.dropRight(1).foreach {
          receipt =>
            l.complete(receipt)
            l.isClosed shouldBe false
        }
        l.complete(permutation.last)
        l.isClosed shouldBe true
    }
  }

  it should "count multiple sends to the same receiver independently and ignore identical duplicates" in {
    val l = ledger()
    val second = ab.copy(id = ab.id.copy(sequence = 2L))
    val finish = DirectWorkCompletion(root, Vector(ab, second))
    l.complete(finish)
    l.complete(finish)
    val firstReceipt = DirectWorkCompletion(ab, Vector.empty)
    l.complete(firstReceipt)
    l.complete(firstReceipt)
    l.pendingCount shouldBe 1
    l.complete(DirectWorkCompletion(second, Vector.empty))
    l.isClosed shouldBe true
  }

  it should "reject conflicting early or completed receipts" in {
    val l = ledger()
    l.complete(DirectWorkCompletion(ab, Vector.empty))
    intercept[IllegalArgumentException](l.complete(DirectWorkCompletion(ab, Vector(bc))))
    l.complete(DirectWorkCompletion(root, Vector(ab)))
    intercept[IllegalArgumentException](l.complete(DirectWorkCompletion(ab, Vector(bc))))
  }

  it should "reject foreign children, undeclared roots and work from a different tick" in {
    intercept[IllegalArgumentException](
      ledger().complete(DirectWorkCompletion(root, Vector(ab.copy(parent = None))))
    )
    intercept[IllegalArgumentException](
      ledger().complete(
        DirectWorkCompletion(root.copy(id = root.id.copy(sequence = -2)), Vector.empty)
      )
    )
    intercept[IllegalArgumentException](
      ledger().complete(DirectWorkCompletion(ab.copy(tick = 11L), Vector.empty))
    )
    intercept[IllegalArgumentException](
      ledger().complete(DirectWorkCompletion(root, Vector(ab.copy(receiver = entityC, tick = 11L))))
    )
  }

  it should "keep another enrolled root open even after an unrelated chain finishes" in {
    val l = ledger()
    val other = root.copy(id = DirectWorkId(entityC, "entityC", -1), receiver = entityC)
    l.enroll(other)
    l.complete(DirectWorkCompletion(root, Vector.empty))
    l.isClosed shouldBe false
    l.complete(DirectWorkCompletion(other, Vector.empty))
    l.isClosed shouldBe true
  }

  it should "process a deep reversed chain without recursive stack growth" in {
    val l = ledger()
    val chain = (1L to 2000L).foldLeft(Vector(root)) {
      (chain, index) =>
        val previous = chain.last
        chain :+ DirectWork(
          DirectWorkId(entityA, "entityA", index),
          Some(previous.id),
          entityA,
          10L
        )
    }
    chain.indices.reverse.foreach {
      index =>
        l.complete(DirectWorkCompletion(chain(index), chain.lift(index + 1).toVector))
    }
    l.isClosed shouldBe true
  }
}
