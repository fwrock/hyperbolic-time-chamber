package org.interscity.htc
package core.runtime.conservative

import core.entity.event.{ActorInteractionEvent, SpontaneousEvent}
import core.entity.event.control.report.ReportEvent
import core.runtime.EventOrder
import core.runtime.accounting.{JoinRuntime, LogicalProcessId}
import model.hybrid.entity.event.data.link.LinkInfoData
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConservativeProtocolSerializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ConservativeProtocolSerializationSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  "the conservative protocol" should "preserve channels, phases, model data and actor references on its remoting binding" in {
    val owner = TestProbe().ref
    val order = EventOrder(10L, 2L, "input")
    val event = ActorInteractionEvent(10L, 1L, 2L, "example.Link", "", "example.Link",
      data = LinkInfoData(200.0, 600.0, 2, 40.0, 1), resourceId = "run", order = Some(order),
      sourceEpoch = "epoch", sourceIncarnation = "incarnation")
    val messages: Vector[ConservativeCommand] = Vector(
      ConservativeMembership("epoch", Vector(owner)),
      RegisterConservativeProcess("epoch", owner, JoinRuntime(Identify(id = 2L, classType = "example.Link"),
        owner, Some(order), 0L, "incarnation")),
      ConservativeTransfer("epoch", 1L, LogicalProcessId("example.Link", 2L), event),
      ConservativeTransferAccepted("epoch", 1L),
      ConservativeDelivery("epoch", 2L, event),
      ConservativeDelivery("epoch", 3L, SpontaneousEvent(10L, owner, generation = 2L, order = Some(order))),
      ConservativeInputAccepted("epoch", 2L, Some(order)),
      CollectConservativeReady(1L), ConservativeReady(1L, Some(order), 1),
      ExecuteConservativeBatch(1L, 10L, 2L), ConservativeBatchProcessed(1L, None, 0, terminal = true),
      ConservativeBatchComplete(1L),
      PublishConservativeReport(owner, ReportEvent("2", 10L, 1L, data = "state", label = "value")),
      StopConservativeRuntime(1L, truncated = false), RetireConservativeProcess(1L, truncated = false),
      ConservativeProcessRetired(1L), ConservativeRuntimeStopped(1L, Set(owner)))
    val codec = SerializationExtension(system)
    messages.foreach { message =>
      val serializer = codec.findSerializerFor(message)
      serializer.getClass.getName should include ("Kryo")
      codec.deserialize(codec.serialize(message).get, serializer.identifier, "").get shouldBe message
    }
  }
}
