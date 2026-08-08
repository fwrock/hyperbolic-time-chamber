package org.interscity.htc
package core.actor.rollback

import core.entity.event.BaseEvent
import core.entity.event.data.DefaultBaseEventData
import core.types.Tick

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AntiMessageCascadeSpec extends AnyFlatSpec with Matchers {

  private case class TestEvent(tick: Tick) extends BaseEvent[DefaultBaseEventData](tick = tick)

  private def sentMessage(senderId: String, tick: Tick, seq: Long, receiverId: String): SentMessage =
    SentMessage(
      messageId = MessageId(senderId = senderId, tick = tick, seq = seq),
      receiverId = receiverId,
      receiverShardId = "shard-1",
      receiverActorType = "LoadBalancedDistributed"
    )

  private def loggedEvent(tick: Tick, seq: Long, sentMessages: Seq[SentMessage] = Seq.empty): LoggedEvent =
    LoggedEvent(tick = tick, seq = seq, event = TestEvent(tick), sentMessages = sentMessages)

  "messagesToRetract" should "be empty when nothing was undone" in {
    AntiMessageCascade.messagesToRetract(Seq.empty) shouldBe empty
  }

  it should "be empty when the undone events sent nothing" in {
    val undone = Seq(loggedEvent(tick = 3L, seq = 1L), loggedEvent(tick = 4L, seq = 2L))

    AntiMessageCascade.messagesToRetract(undone) shouldBe empty
  }

  it should "return every sent message across every undone event, unconditionally" in {
    val m1 = sentMessage("car-1", tick = 3L, seq = 1L, receiverId = "link-9")
    val m2 = sentMessage("car-1", tick = 3L, seq = 2L, receiverId = "node-5")
    val m3 = sentMessage("car-1", tick = 4L, seq = 3L, receiverId = "link-9")

    val undone = Seq(
      loggedEvent(tick = 3L, seq = 1L, sentMessages = Seq(m1, m2)),
      loggedEvent(tick = 4L, seq = 2L, sentMessages = Seq(m3))
    )

    // Aggressive cancellation: every send from every undone event comes back, no filtering.
    AntiMessageCascade.messagesToRetract(undone) should contain theSameElementsAs Seq(m1, m2, m3)
  }

  it should "not deduplicate repeated sends to the same receiver -- each is a distinct message identity" in {
    val toSameReceiverTwice = sentMessage("car-1", tick = 3L, seq = 1L, receiverId = "link-9")
    val toSameReceiverAgain = sentMessage("car-1", tick = 3L, seq = 2L, receiverId = "link-9")

    val undone = Seq(loggedEvent(tick = 3L, seq = 1L, sentMessages = Seq(toSameReceiverTwice, toSameReceiverAgain)))

    AntiMessageCascade.messagesToRetract(undone) shouldBe Seq(toSameReceiverTwice, toSameReceiverAgain)
  }
}
