package org.interscity.htc
package core.runtime

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import org.apache.pekko.actor.ActorRef
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EventOrderSpec extends AnyFlatSpec with Matchers {
  "EventOrder" should "advance zero-delay causality and reset the delta only at a later tick" in {
    val root = EventOrder.root(10L, "example.Model", 1L)
    val reply = root.child(10L, 1L).child(10L, 1L)
    reply.microstep shouldBe 2L
    reply should be > root
    reply.child(11L, 1L).microstep shouldBe 0L
    intercept[IllegalArgumentException](reply.child(9L, 1L))
    intercept[ArithmeticException](EventOrder(1L, Long.MaxValue, "x").child(1L, 1L))
  }

  it should "derive replay-stable keys from the cause and output ordinal" in {
    val root = EventOrder.root(10L, "example.Model", 1L)
    root.child(10L, 1L) shouldBe EventOrder.root(10L, "example.Model", 1L).child(10L, 1L)
    root.child(10L, 1L) should be < root.child(10L, 2L)
    root.key should not be EventOrder.root(10L, "example.Other", 1L).key
  }

  "OrderedInputQueue" should "order self-events and interactions in the same queue" in {
    val queue = new OrderedInputQueue
    val wake = SpontaneousEvent(10L, ActorRef.noSender, order = Some(EventOrder(10L, 0L, "a")))
    val reply = ActorInteractionEvent(10L, 0L, 1L, "test", "", "test", data = "reply",
      resourceId = "test", order = Some(EventOrder(10L, 1L, "b")))
    queue.enqueue(reply)
    queue.enqueue(wake)
    queue.minimumTick shouldBe Some(10L)
    queue.dequeue() shouldBe wake
    queue.dequeue() shouldBe reply
    queue.nonEmpty shouldBe false
  }

  it should "reject inconsistent transport and logical timestamps" in {
    intercept[IllegalArgumentException] {
      new OrderedInputQueue().enqueue(SpontaneousEvent(9L, ActorRef.noSender,
        order = Some(EventOrder(10L, 0L, "x"))))
    }
  }

  it should "reclaim obsolete dispatch entries behind earlier live work without changing queue order" in {
    val queue = new OrderedInputQueue
    val earlier = SpontaneousEvent(1L, ActorRef.noSender, generation = 1L)
    val obsolete = SpontaneousEvent(100L, ActorRef.noSender, generation = 2L)
    val later = SpontaneousEvent(200L, ActorRef.noSender, generation = 3L)
    Vector(later, obsolete, earlier).foreach(queue.enqueue)
    queue.discardDispatchesWhere(_.generation == 2L) shouldBe Vector(obsolete)
    queue.retainedEntryCount shouldBe 3
    queue.compactDiscarded()
    queue.retainedEntryCount shouldBe 2
    queue.queuedDispatchCount shouldBe 2
    queue.dequeue() shouldBe earlier
    queue.discardDispatchesWhere(_.generation == 3L)
    queue.headOption shouldBe None
    queue.compactDiscarded()
    queue.retainedEntryCount shouldBe 0
  }
}
