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
      PauseRuntimeExecution()
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

  "production worker Props" should "use class constructors rather than remotely serialized creator closures" in {
    val ref = writer.deadLetters
    val optimistic = AccountedOptimisticLocalTimeManager.props(100L, ref, ref)
    val conservative = ChannelAwareLocalTimeManager.props(100L, ref, ref)
    optimistic.actorClass() shouldBe classOf[AccountedOptimisticLocalTimeManager]
    conservative.actorClass() shouldBe classOf[ChannelAwareLocalTimeManager]
    Seq(optimistic, conservative).foreach(_.args shouldBe List(100L, ref, ref))
  }
}
