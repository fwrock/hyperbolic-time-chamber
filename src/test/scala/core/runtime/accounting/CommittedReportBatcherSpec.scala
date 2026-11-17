package org.interscity.htc
package core.runtime.accounting

import core.entity.event.control.report.ReportEvent
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class CommittedReportBatcherSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "CommittedReportBatcherSpec",
    ConfigFactory
      .parseString("pekko.actor.provider=local\npekko.actor.fail-mixed-versions=off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }

  "Committed report batches" should "register partial batches before dispatch and preserve per-target order across full and partial envelopes" in {
    val a = TestProbe().ref
    val b = TestProbe().ref
    var tokens = 0L
    val sent = mutable.ArrayBuffer.empty[(ActorRef, Long, Vector[ReportEvent])]
    val batcher = new CommittedReportBatcher(
      2,
      () => { tokens += 1; tokens },
      (target, token, events) => sent += ((target, token, events))
    )
    def event(i: Int): ReportEvent = ReportEvent("1", i.toLong, 0L, timestamp = 0L, data = i)
    batcher.add(a, event(1))
    tokens shouldBe 1L
    sent shouldBe empty
    batcher.add(b, event(2))
    batcher.add(a, event(3))
    sent.toVector shouldBe Vector((a, 1L, Vector(event(1), event(3))))
    batcher.add(a, event(4))
    tokens shouldBe 3L
    batcher.flush()
    sent.toVector shouldBe Vector(
      (a, 1L, Vector(event(1), event(3))),
      (b, 2L, Vector(event(2))),
      (a, 3L, Vector(event(4)))
    )
    batcher.nonEmpty shouldBe false
    batcher.flush()
    sent.size shouldBe 3
  }
}
