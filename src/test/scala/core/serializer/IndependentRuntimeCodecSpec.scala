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
      ResolveAllocationPlacement("live", "epoch", "controller", "shard", "source", "target", Vector("Car"), true),
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

  "spatial directory codecs" should "preserve epochs revisions typed assignments and replica identities" in {
    import core.actor.manager.loadbalance.allocation.*
    val row = SpatialAssignment("801", "dynamic-qt-shard-0-0", "example.Car", 1.5, 2.5)
    val messages: Seq[SpatialDirectoryWire] = Seq(row,
      RegisterSpatialReplica("id", "address", "incarnation", reader.deadLetters),
      InstallSpatialAssignments("id", "epoch", 2L, Vector(row), false),
      SpatialAssignmentsInstalled("id", "epoch", 2L, "address", "incarnation"),
      SpatialInstallFailed("id", "epoch", "address", "incarnation", "reason"),
      SpatialReplicaRegistered("id", "epoch", 2L),
      PublishSpatialAssignments("id", "epoch", "address", "incarnation", Vector(row)),
      SpatialAssignmentsPublished("id", "epoch", 2L), SpatialDirectoryRejected("id", "reason"),
      SpatialDirectoryFailed("epoch", "reason"), GetSpatialDirectoryStatus,
      SpatialDirectoryStatus("epoch", 2L, true, 1, None))
    val from = SerializationExtension(writer)
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
      SerializationExtension(reader).deserialize(from.serialize(original).get, serializer.identifier, manifest).get shouldBe original
    }
  }

  "conservative migration codecs" should "carry frozen cohorts and saved input on independent cold codecs" in {
    import core.runtime.conservative.*
    import core.actor.manager.loadbalance.migration.*
    import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
    val from = SerializationExtension(writer)
    val ref = reader.deadLetters
    val lp = LogicalProcessId("example.Car", 801L)
    val messages: Seq[AnyRef] = Seq(
      ConservativeMigrationDeclined("late-cut"),
      FreezeConservativeMigration("epoch", "cut", Map("shard" -> "node"), 1L),
      FreezeConservativeProcess("epoch", "cut"), ConservativeProcessFrozen("epoch", "cut"),
      ConservativeMigrationFrozen("epoch", "cut", Vector(ConservativeMigrationMember(lp, ref, ref))),
      RebindConservativeProcess("epoch", "cut", lp, "incarnation", "old"),
      ConservativeProcessRebound("epoch", "cut", ref, ref, "incarnation"),
      AuthorizeConservativeSourceStop("epoch", "cut"), ConservativeSourceStopAuthorized("epoch", "cut"),
      ResumeConservativeMigration("epoch", "cut"), EndConservativeProcessMigration("epoch", "cut"),
      ConservativeMigrationResumed("epoch", "cut"),
      core.entity.event.control.loadbalance.MigrationSafeEvent(2L, Some(Vector(ConservativeMigrationMember(lp, ref, ref))), "cut"),
      core.entity.event.control.loadbalance.RequestMigrationPauseEvent(Set("shard"), ref, Map("shard" -> "node"), "cut"),
      core.actor.manager.loadbalance.allocation.GetRegisteredAllocationRegions("id", "epoch", "controller"),
      core.actor.manager.loadbalance.allocation.RegisteredAllocationRegions("id", "epoch", Vector(
        core.actor.manager.loadbalance.allocation.RegisteredAllocationRegion("Car", ref, "address"))))
    messages.foreach { original =>
      val serializer = from.findSerializerFor(original)
      val manifest = org.apache.pekko.serialization.Serializers.manifestFor(serializer, original)
      SerializationExtension(reader).deserialize(from.serialize(original).get, serializer.identifier, manifest).get shouldBe original
    }
    val event = ActorInteractionEvent(10L, 2L, 12L, "example.Link", "path", "example.Link",
      data = model.hybrid.entity.event.data.link.LinkInfoData(200.0, 600.0, 2, 40.0, 1), resourceId = "run",
      order = Some(core.runtime.EventOrder(10L, 2L, "queued")), sourceEpoch = "epoch", sourceIncarnation = "incarnation")
    Seq[AnyRef](event, SpontaneousEvent(20L, ref, generation = 7L)).foreach { queued =>
      ConservativeMigrationCodec.decode(reader, ConservativeMigrationCodec.encode(writer, queued)) shouldBe queued
    }
    val checkpoint = ConservativeMigrationCheckpoint("epoch", "incarnation", "cut", "owner", "coordinator", "source",
      Vector(ConservativeMigrationCodec.encode(writer, event)), 7L, 3L, true, 2L, 100L, 0L, None, None)
    val snapshot = MigrationSnapshot("{}", "example.State", conservativeRuntime = Some(checkpoint))
    core.util.JsonUtil.convertValue[MigrationSnapshot](core.util.JsonUtil.toJson(snapshot)) shouldBe snapshot
  }
}
