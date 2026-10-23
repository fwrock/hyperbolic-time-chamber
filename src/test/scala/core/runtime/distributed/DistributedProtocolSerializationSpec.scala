package org.interscity.htc
package core.runtime.distributed

import core.entity.event.ActorInteractionEvent
import core.runtime.EventOrder
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.serialization.SerializationExtension
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DistributedProtocolSerializationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("DistributedProtocolSerializationSpec",
    ConfigFactory.parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  "distributed optimistic commands" should "preserve membership, LP identity, anti-message identity and actual sink refs through Kryo" in {
    val ref = TestProbe().ref
    val id = LogicalProcessId("example.Model", 1L)
    val order = EventOrder(10L, 2L, "cause")
    val event = ActorInteractionEvent(10L, 1L, 1L, id.model, "", id.model, data = "input", resourceId = "run",
      seq = 2L, isAntiMessage = true, order = Some(order), sourceEpoch = "epoch", sourceIncarnation = "instance")
    val messages = Vector[AnyRef](RuntimeMembership("epoch", Vector(ref)),
      RegisterLocalProcess("epoch", JoinRuntime(Identify(id = 1L, classType = id.model), ref, Some(order), 0L, "instance")),
      LocalProcessRegistered(id), StartLocalRuntime(0L, 100L), OptimisticTransfer("epoch", 1L, id, event),
      OptimisticTransferAccepted("epoch", 1L), LocalRuntimeCommitted(1L, Set(ref)),
      RetireLocalProcesses(1L, false), LocalProcessesRetired(1L), RuntimeJoined("epoch", ref, ref, true), SetRuntimeHorizon(100L))
    val codec = SerializationExtension(system)
    messages.foreach { message =>
      val serializer = codec.findSerializerFor(message)
      serializer.getClass.getName should include ("Kryo")
      codec.deserialize(codec.serialize(message).get, serializer.identifier, "").get shouldBe message
    }
  }
}
