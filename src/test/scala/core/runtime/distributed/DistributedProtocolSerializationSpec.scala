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
    val origin = RuntimeBirthOrigin(id, order.key, order.tick, order.microstep)
    val birth = RuntimeBirthData("{\"startTick\":20}",
      Map("node" -> core.entity.actor.ShardActorId(7L, "example.Node")), "run",
      Map("time-warp" -> ref), Map(core.enumeration.ReportTypeEnum.parquet -> ref))
    val messages = Vector[AnyRef](origin, birth, RuntimeBirthAccepted(id, 2L),
      RequestRuntimeBirth("epoch", id, origin), AuthorizeRuntimeBirth("epoch", id, origin),
      RuntimeBirthInitialized(id, ref), StopRuntimeBirthInitializer,
      JoinRuntime(Identify(id = 1L, classType = id.model), ref, None, order.tick, "child", Some(origin)), RuntimeMembership("epoch", Vector(ref)),
      RegisterLocalProcess("epoch", JoinRuntime(Identify(id = 1L, classType = id.model), ref, Some(order), 0L, "instance")),
      LocalProcessRegistered(id), StartLocalRuntime(0L, 100L, inputThrough = 10L),
      BeginInputEnrollment(1L), InputEnrollmentOpened(1L), CompleteInputEnrollment(1L, 20L), InputEnrollmentCompleted(1L),
      LoadOptimisticInputWindow("epoch", 1L, 0L, 20L), OptimisticInputWindowOpened("epoch", 1L), OptimisticInputWindowReady("epoch", 1L, 20L, false, 5L), OptimisticTransfer("epoch", 1L, id, event),
      OptimisticTransferAccepted("epoch", 1L), LocalRuntimeCommitted(1L, Set(ref)),
      RetireLocalProcesses(1L, false), LocalProcessesRetired(1L), RuntimeJoined("epoch", ref, ref, true), SetRuntimeHorizon(100L),
      SetOptimisticExecutionLimits(10L, 60L, core.runtime.OptimisticExecutionLimits()),
      RuntimeSnapshot(1L, Some(10L), 1, 0, 1, Some(RuntimeRetention(2L, 5L, 3L, 4L, 1L, 2L, 3L))))
    val codec = SerializationExtension(system)
    messages.foreach { message =>
      val serializer = codec.findSerializerFor(message)
      serializer.getClass.getName should include ("Kryo")
      codec.deserialize(codec.serialize(message).get, serializer.identifier, "").get shouldBe message
    }
  }
  it should "preserve inert birth initialization through the sharded envelope codec" in {
    val ref = TestProbe().ref
    val origin = RuntimeBirthOrigin(LogicalProcessId("example.Parent", 1L), "birth", 10L)
    val initialize = core.entity.event.control.load.InitializeEvent("2", ref,
      core.entity.event.data.InitializeData("{\"startTick\":20}", "run", ref,
        scala.collection.mutable.Map(core.enumeration.ReportTypeEnum.parquet -> ref),
        timeManagers = scala.collection.mutable.Map("time-warp" -> ref),
        runtimeCoordinator = Some(ref), runtimeBirth = Some(origin)))
    val envelope = core.entity.event.EntityEnvelopeEvent("2", initialize)
    val codec = SerializationExtension(system)
    val serializer = codec.findSerializerFor(envelope)
    val manifest = serializer.asInstanceOf[org.apache.pekko.serialization.SerializerWithStringManifest].manifest(envelope)
    codec.deserialize(codec.serialize(envelope).get, serializer.identifier, manifest).get shouldBe envelope
  }
}
