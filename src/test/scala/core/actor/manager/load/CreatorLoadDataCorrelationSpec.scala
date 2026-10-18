package org.interscity.htc
package core.actor.manager.load

import core.entity.actor.properties.CreatorProperties
import core.entity.event.control.load.FinishCreationEvent

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestKit, TestProbe }
import org.htc.protobuf.core.entity.event.control.load.InitializeEntityAckEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration.*

/** Regression coverage for the `(batchId, entityId)`-keyed correlation-map fix in
  * [[CreatorLoadData]]: `actorsBatches`/`pendingInitAck` used to be `Map[String, ...]` keyed by
  * bare `entityId`, so a redelivered `StartEntity`/ack for an entity already registered under a
  * different in-flight batch would clobber that registration -- the original batch's
  * `InitializeEntityAckEvent` would then either resolve the WRONG batch or find nothing to
  * resolve, so that batch's `initializedAcknowledges` entry never emptied,
  * [[CreatorLoadData.checkAndSendFinish]] never fired for it, and the whole progressive-loading
  * window it belonged to never reported ready -- a total simulation freeze.
  *
  * Both maps are now keyed by entityId -> a FIFO `Queue` of in-flight `(batchId, ...)` pairs, so
  * two overlapping registrations for the same entity resolve in send order instead of clobbering
  * each other. This spec drives `pendingInitAck` (the map `handleFinishInitialization` actually
  * consumes) and `checkAndSendFinish` directly, without exercising `handleInitialize`'s
  * `getShardRef`/cluster-sharding call (out of scope here, and unavailable under the
  * `pekko.actor.provider = local` this suite uses -- consistent with
  * `LoadDataManagerScenarioPreflightSpec`'s note on the same constraint). `TestActorRef` is used
  * only to obtain a constructed `CreatorLoadData` instance whose `private[load]` members this spec
  * (same package) can call directly as plain method calls -- no messages are dispatched through
  * the actor's mailbox, so the actor's `PersistentActor` recovery timing is irrelevant here.
  */
class CreatorLoadDataCorrelationSpec
    extends TestKit(
      ActorSystem(
        "CreatorLoadDataCorrelationSpec",
        ConfigFactory
          .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
          .withFallback(ConfigFactory.load())
      )
    )
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private def newCreator(): CreatorLoadData =
    TestActorRef[CreatorLoadData](
      CreatorLoadData.props(CreatorProperties(entityId = "creator-1", shardId = "shard-1"))
    ).underlyingActor

  "CreatorLoadData's pendingInitAck" should "hold both in-flight registrations for the same entity, one per overlapping batch, instead of the second clobbering the first" in {
    val creator = newCreator()

    // Simulates what handleInitialize does on a successful StartEntityAck: append (batchId,
    // classType) onto this entity's queue. Two calls here stand in for the same entityId being
    // registered under two different in-flight batches -- e.g. a redelivered StartEntity for an
    // entity whose original registration (batch-A) hasn't been acked yet, while a second,
    // legitimately distinct batch (batch-B) also references it.
    creator.pendingInitAck.getOrElseUpdate("e1", mutable.Queue.empty) += (("batch-A", "hybrid.actor.Car"))
    creator.pendingInitAck.getOrElseUpdate("e1", mutable.Queue.empty) += (("batch-B", "hybrid.actor.Car"))

    // Pre-fix, a bare `Map[String, (String, String)]` would have left exactly one entry here (the
    // second `put` overwriting the first) -- losing batch-A's registration entirely.
    creator.pendingInitAck("e1") should contain theSameElementsInOrderAs Seq(
      ("batch-A", "hybrid.actor.Car"),
      ("batch-B", "hybrid.actor.Car")
    )
  }

  it should "resolve overlapping batches for a redelivered entity in FIFO order, letting both eventually finish, instead of one batch livelocking forever" in {
    val creator = newCreator()
    val probeA = TestProbe()
    val probeB = TestProbe()

    creator.batchesLoad.put("batch-A", probeA.ref)
    creator.batchesLoad.put("batch-B", probeB.ref)

    // Mirrors createEntitiesFromChunk + handleInitialize's bookkeeping for one entity referenced by
    // two overlapping batches.
    creator.addToInitializedAcknowledges("batch-A", "e1")
    creator.addToInitializedAcknowledges("batch-B", "e1")
    creator.pendingInitAck.getOrElseUpdate("e1", mutable.Queue.empty) += (("batch-A", "hybrid.actor.Car"))
    creator.pendingInitAck.getOrElseUpdate("e1", mutable.Queue.empty) += (("batch-B", "hybrid.actor.Car"))

    // First InitializeEntityAckEvent for "e1" -- the entity's own actor only ever knows its own
    // id, not which batch it belongs to, so both acks are indistinguishable messages carrying just
    // entityId. FIFO must resolve batch-A (the oldest registration) first.
    creator.handleFinishInitialization(InitializeEntityAckEvent(entityId = "e1"))

    probeA.expectMsgPF(2.seconds) {
      case FinishCreationEvent(_, "batch-A", _) => succeed
    }
    probeB.expectNoMessage(200.millis)

    // Second ack for the same entity resolves the remaining registration (batch-B). Without the
    // fix this would never happen: the earlier bare-entityId `put` would have already discarded
    // batch-A's tracking, so this second ack -- or the first one, depending on overwrite order --
    // would be the only one ever seen, and whichever batch lost its entry would sit with a
    // non-empty `initializedAcknowledges` forever, so `checkAndSendFinish` would never fire for it.
    creator.handleFinishInitialization(InitializeEntityAckEvent(entityId = "e1"))

    probeB.expectMsgPF(2.seconds) {
      case FinishCreationEvent(_, "batch-B", _) => succeed
    }

    creator.pendingInitAck.contains("e1") shouldBe false
    creator.initializedAcknowledges.get("batch-A").forall(_.isEmpty) shouldBe true
    creator.initializedAcknowledges.get("batch-B").forall(_.isEmpty) shouldBe true
  }
}
