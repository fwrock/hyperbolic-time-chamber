package org.interscity.htc
package core.runtime.conservative

import core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, SpontaneousEvent }
import core.entity.event.control.report.ReportEvent
import core.runtime.EventOrder
import core.runtime.conservative.partition.*
import core.runtime.accounting.{ CommittedReportBatchDelivery, LogicalProcessId, RuntimeFailure, RuntimeJoined }
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, Serializers }
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DirectConservativeCodecSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val config = ConfigFactory
    .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
    .withFallback(ConfigFactory.load())
  private val writer = ActorSystem("DirectWriter", config)
  private val reader = ActorSystem("DirectReader", config)
  override def afterAll(): Unit = { writer.terminate(); reader.terminate(); () }

  "direct conservative messages" should "cross independent cold codecs including sharded payloads and batched effects" in {
    val receiver = LogicalProcessId("example.Link", 2L)
    val root = DirectWork(DirectWorkId(receiver, "incarnation", -1L), None, receiver, 10L)
    val child = DirectWork(DirectWorkId(receiver, "incarnation", 1L), Some(root.id), receiver, 10L)
    val owner = reader.deadLetters
    val event = ActorInteractionEvent(
      10L,
      2L,
      2L,
      "example.Link",
      "2",
      "example.Link",
      data = "notification",
      resourceId = "scenario",
      seq = 1L,
      sourceEpoch = "run",
      sourceIncarnation = "incarnation"
    )
    val delivery = DirectExecute("run", child, owner, event)
    val finish = FinishEvent(
      owner,
      Identify(id = 2L, classType = "example.Link"),
      10L,
      timeManager = owner,
      scheduleRevision = 1L,
      nextOrder = None
    )
    val receipt = DirectProcessed(
      "run",
      owner,
      DirectWorkCompletion(root, Vector(child)),
      Vector(finish),
      Vector(DirectReport(owner, ReportEvent("2", 10L, 2L, data = "output", label = "test"))),
      Some(EventOrder(20L, 0L, "wake")),
      1,
      false
    )
    val messages = Vector[AnyRef](
      DirectAccounted("run", DirectWorkCompletion(child, Vector.empty)),
      DirectExecute("run", root, owner, SpontaneousEvent(10L, owner)),
      DirectExecute("run", root, owner, DirectQueuedInputs(10L)),
      receipt,
      CommittedReportBatchDelivery(
        "run",
        8L,
        Vector(
          ReportEvent("2", 10L, 2L, timestamp = 0L, data = "one"),
          ReportEvent("2", 10L, 2L, timestamp = 0L, data = "two")
        ),
        owner
      ),
      RuntimeJoined("run", owner, owner, directConservative = true, additiveWakeups = true),
      PartitionOwners("run", receiver, owner),
      PartitionCohort("run", Vector(owner)),
      ConfigurePartition("run", Vector(owner)),
      StoreWake("run", TickWake(10L, Identify(id = 2L, classType = "example.Link"))),
      WakeStored("run", TickWake(10L, Identify(id = 2L))),
      DrainPartition("run", 1L, 10L),
      WakeClaimed("run", TickWake(10L, Identify(id = 2L))),
      PartitionDrained("run", 1L),
      StartPartitionRoot("run", owner, DirectExecute("run", root, owner,
        SpontaneousEvent(10L, owner)), Some(TickWake(10L, Identify(id = 2L)))),
      PartitionRootsSettled("run", Vector(root.id)),
      delivery,
      EntityEnvelopeEvent("2", delivery)
    )
    val from = SerializationExtension(writer)
    val to = SerializationExtension(reader)
    to.serialize(RuntimeFailure("different first-use history")).get
    messages.foreach {
      message =>
        val codec = from.findSerializerFor(message)
        to.deserialize(
          from.serialize(message).get,
          codec.identifier,
          Serializers.manifestFor(codec, message)
        ).get shouldBe message
    }
  }
}
