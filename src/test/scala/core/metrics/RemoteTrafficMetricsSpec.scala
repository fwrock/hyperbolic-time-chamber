package org.interscity.htc
package core.metrics

import org.interscity.htc.core.entity.event.{ ActorInteractionEvent, EntityEnvelopeEvent, SpontaneousEvent }
import org.interscity.htc.core.runtime.accounting.{ LogicalProcessId, RouteControl }
import org.interscity.htc.core.runtime.conservative.*
import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.ByteBuffer

class RemoteTrafficMetricsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private val system = ActorSystem(
    "RemoteTrafficMetricsSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  "remote traffic counters" should "retain direction and serialized size without double-counting envelopes" in {
    val counters = new RemoteTrafficMetrics(new CollectorRegistry())
    counters.snapshot shouldBe empty
    counters.record("sent", "scheduling", 100)
    counters.record("sent", "scheduling", 150)
    counters.record("received", "model", 80)
    counters.snapshot should contain(RemoteTrafficSnapshot("sent", "scheduling", 2, 250))
    counters.snapshot should contain(RemoteTrafficSnapshot("received", "model", 1, 80))
    intercept[IllegalArgumentException](counters.record("sent", "other", -1))
  }

  it should "classify wrapped direct interactions separately from wake-ups and runtime effects" in {
    val ref = system.deadLetters
    val lp = LogicalProcessId("example.Car", 1L)
    val root = DirectWork(DirectWorkId(lp, "incarnation", -1), None, lp, 10)
    val event =
      ActorInteractionEvent(10L, 0L, 1L, "example.Car", "1", "example.Car", data = "payload", resourceId = "test")
    val delivery = DirectExecute("run", root, ref, event)
    RemoteTrafficInstrument.categoryOf(EntityEnvelopeEvent("1", delivery)) shouldBe "model"
    RemoteTrafficInstrument.categoryOf(
      delivery.copy(event = SpontaneousEvent(10, ref))
    ) shouldBe "scheduling"
    RemoteTrafficInstrument.categoryOf(RouteControl("run", ref, event, 10)) shouldBe "scheduling"
    RemoteTrafficInstrument.categoryOf(CollectConservativeReady(1)) shouldBe "coordination"
    RemoteTrafficInstrument.categoryOf("unknown") shouldBe "other"
  }

  it should "write no metadata into the transport buffer" in {
    val instrument = new RemoteTrafficInstrument
    val buffer = ByteBuffer.allocate(32)
    buffer.position(7)
    instrument.remoteWriteMetadata(null, "payload", null, buffer)
    buffer.position() shouldBe 7
    instrument.serializationTimingEnabled shouldBe false
  }
}
