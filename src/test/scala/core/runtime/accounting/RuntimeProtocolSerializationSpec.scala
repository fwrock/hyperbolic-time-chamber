package org.interscity.htc
package core.runtime.accounting

import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.runtime.EventOrder
import model.hybrid.entity.event.data.link.LinkInfoData
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.{ SerializationExtension, SerializerWithStringManifest }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeProtocolSerializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("RuntimeProtocolSerializationSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  "the accounted protocol" should "preserve model payloads, logical order and actor references on its actual wire binding" in {
    val codec = SerializationExtension(system)
    val owner = TestProbe().ref
    val interaction = ActorInteractionEvent(10L, 1L, 2L, "example.Link", "", "example.Link",
      data = LinkInfoData(100.0, 10.0, 3, 13.9, 2), resourceId = "run", seq = 99L,
      order = Some(EventOrder(10L, 2L, "logical")), sourceEpoch = "epoch", sourceIncarnation = "incarnation")
    val messages: Seq[AnyRef] = Seq(RuntimeDelivery("epoch", 1L, interaction),
      RuntimeDelivery("epoch", 2L, SpontaneousEvent(10L, owner, generation = 1L,
        scheduleRevision = 2L, order = interaction.order)),
      DrainRuntimeReports(1L, owner, Set(owner)),
      RegisterOwnedProcess(org.htc.protobuf.core.entity.actor.Identify(id = 123L, classType = "example.Link"),
        owner, interaction.order, Some(org.htc.protobuf.core.entity.actor.Identify(id = 2L, classType = "example.Link"))))
    messages.foreach { message =>
      val serializer = codec.findSerializerFor(message)
      serializer.getClass.getName should include ("Kryo")
      codec.deserialize(codec.serialize(message).get, serializer.identifier, "").get shouldBe message
    }
  }
}
