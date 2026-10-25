package org.interscity.htc
package core.serializer

import com.typesafe.config.ConfigFactory
import core.runtime.accounting.*
import core.runtime.conservative.ConservativeReady
import core.actor.manager.time.{AccountedOptimisticLocalTimeManager, ChannelAwareLocalTimeManager}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IndependentRuntimeCodecSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory.parseString(
    "pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load())
  private val writer = ActorSystem("RuntimeCodecWriter", config)
  private val reader = ActorSystem("RuntimeCodecReader", config)
  override def afterAll(): Unit = { writer.terminate(); reader.terminate(); () }

  "runtime serialization" should "decode on an independent cold codec with a different first-use history" in {
    val from = SerializationExtension(writer)
    val to = SerializationExtension(reader)
    to.serialize(RuntimeFailure("receiver-only history")).get
    val messages = Seq(
      RuntimeSnapshot(7L, Some(20L), 1, 2, retention = Some(RuntimeRetention(events = 3L))),
      RuntimeBirthOrigin(LogicalProcessId("example.Parent", 10L), "birth", 20L, 2L),
      ConservativeReady(7L, Some(core.runtime.EventOrder(20L, 2L, "key")), 1),
      PauseRuntimeExecution(), CommitRuntime(7L, 21L, Some(20L)),
      core.runtime.conservative.StopConservativeRuntime(7L, true, Some(20L)),
      core.runtime.conservative.RetireConservativeProcess(7L, true, Some(20L))
    )
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val bytes = from.serialize(original).get
      to.deserialize(bytes, serializer.identifier, "").get shouldBe original
    }
  }

  it should "serialize remote deployment Class arguments without Java serialization" in {
    val from = SerializationExtension(writer)
    val original = classOf[AccountedOptimisticLocalTimeManager]
    val serializer = from.findSerializerFor(original)
    serializer.getClass.getName should include ("Kryo")
    SerializationExtension(reader).deserialize(from.serialize(original).get, serializer.identifier, "").get shouldBe original
  }

  it should "preserve the warm-up ready handshake on an independent codec" in {
    val original = core.entity.event.control.warmup.WarmUpWorkerReadyEvent("pekko://node@127.0.0.1:1601")
    val from = SerializationExtension(writer)
    val serializer = from.findSerializerFor(original)
    val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
    SerializationExtension(reader).deserialize(from.serialize(original).get, serializer.identifier, manifest).get shouldBe original
  }

  it should "preserve migration preparation controls on independent codecs" in {
    import core.entity.event.control.migration.*
    val messages = Seq(
      MigrationBatchRegisteredEvent("batch"),
      MigrationSnapshotsReadyEvent("batch", Set("one", "two")),
      MigrationPreparationFailedEvent("batch", "one", "capture failed"),
      AbortMigrationPreparationEvent("batch"),
      MigrationHandoffStopEvent(),
      FinalMigrationSnapshotSavedEvent("one", "batch", 1L),
      SaveFinalMigrationSnapshotEvent("one", "batch",
        core.actor.manager.loadbalance.migration.MigrationSnapshot("{}", "example.State", entityId = "one"), 1L)
    )
    val from = SerializationExtension(writer)
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
      SerializationExtension(reader).deserialize(from.serialize(original).get,
        serializer.identifier, manifest).get shouldBe original
    }
  }

  "production worker Props" should "use class constructors rather than remotely serialized creator closures" in {
    val ref = writer.deadLetters
    val optimistic = AccountedOptimisticLocalTimeManager.props(100L, ref, ref)
    val conservative = ChannelAwareLocalTimeManager.props(100L, ref, ref)
    optimistic.actorClass() shouldBe classOf[AccountedOptimisticLocalTimeManager]
    conservative.actorClass() shouldBe classOf[ChannelAwareLocalTimeManager]
    Seq(optimistic, conservative).foreach(_.args shouldBe List(100L, ref, ref))
  }

  "migration restore serialization" should "preserve confirmation identities on independent codecs" in {
    import core.entity.event.control.migration.*
    val messages = Seq(
      MigrationRestoredAckEvent("one", "batch", "token", 3L),
      MigrationRestoreAcceptedEvent("one", "batch"),
      MigrationRestoreAlreadyAcceptedEvent("one", "batch", "token", 3L),
      MigrationRestoreFailedEvent("one", "batch", "token", 3L, "hook failed"),
      core.entity.event.control.loadbalance.MigrationCompleteEvent("shard", durationMs = 12L,
        batchId = "batch", attemptId = "attempt"),
      MigrationContextEvent(scala.collection.mutable.Map("discrete-event" -> reader.deadLetters),
        scala.collection.mutable.Map(core.enumeration.ReportTypeEnum.parquet -> reader.deadLetters),
        core.actor.manager.loadbalance.migration.MigrationSnapshot("{}", "example.State", entityId = "one"),
        "batch", reader.deadLetters, reader.deadLetters, "token", 3L)
    )
    val from = SerializationExtension(writer)
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
      SerializationExtension(reader).deserialize(from.serialize(original).get,
        serializer.identifier, manifest).get shouldBe original
    }
  }

  "distributed allocation codecs" should "preserve authority epochs, callback cohorts, registrations and intent identities" in {
    import core.actor.manager.loadbalance.allocation.*
    val ref = reader.deadLetters
    val rows = Vector(AllocationRegion(ref, "pekko://cluster@127.0.0.1:2551", Vector("shard")))
    val targets = Vector(AllocationTarget("Car", ref, ref))
    val messages: Seq[AllocationWire] = Seq(
      RegisterAllocationRegion("id", "epoch", "Car", "address", "incarnation", ref),
      AllocationRegionRegistered("id", "epoch", "Car"),
      BindAllocationController("id", "epoch", ref, "controller"), AllocationControllerBound("id", "epoch", "controller"),
      AllocateShardRequest("id", "epoch", "Car", "address", "incarnation", 2L, ref, "shard", rows),
      AllocateShardReply("id", "epoch", ref),
      RebalanceShardsRequest("id", "epoch", "Car", "address", "incarnation", 3L, rows, Set("other")),
      RebalanceShardsReply("id", "epoch", Set("shard")),
      ResolveAllocationPlacement("id", "epoch", "controller", "shard", "source", "target", Vector("Car")),
      AllocationPlacementResolved("id", "epoch", targets),
      InstallAllocationIntent("id", "epoch", "controller", "batch", "attempt", "shard", "source", "target", targets),
      AllocationIntentInstalled("id", "epoch", "batch", "attempt"),
      RetireAllocationIntent("id", "epoch", "controller", "batch", "attempt", "shard"),
      AllocationIntentRetired("id", "epoch", "batch", "attempt"), AllocationRejected("id", "epoch", "reason"),
      AllocationAuthorityFailure("controller", "reason"), GetAllocationClientStatus,
      AllocationClientStatus("epoch", "incarnation", "address", Set("Car"), None),
      GetAllocationAuthorityStatus("id", "epoch"),
      AllocationAuthorityStatus("id", "epoch", Map("Car" -> "owner"), Some("controller"), Map("Car" -> Set("address")), None,
        Map("Car" -> rows))
    )
    val from = SerializationExtension(writer)
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
      SerializationExtension(reader).deserialize(from.serialize(original).get, serializer.identifier, manifest).get shouldBe original
    }
  }
}
