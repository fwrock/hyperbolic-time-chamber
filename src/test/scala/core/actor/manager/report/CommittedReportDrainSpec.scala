package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.ReportEvent
import core.runtime.accounting.*
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorRef, ActorSystem, Props }
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.*

class CommittedReportDrainSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "CommittedReportDrainSpec",
    ConfigFactory
      .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
      .withFallback(ConfigFactory.load())
  )
  override def afterAll(): Unit = { system.terminate(); () }
  private class Sink(writes: TestProbe, completion: Future[Unit])
      extends ReportData(startRealTime = null) {
    override protected def onReport(event: ReportEvent): Unit = writes.ref ! event
    override protected def flushCommittedReports(): Future[Unit] = completion
  }
  "committed sink drain" should "acknowledge storage completion instead of only delivery into a buffer" in {
    val writes = TestProbe()
    val coordinator = TestProbe()
    val persisted = Promise[Unit]()
    val sink = system.actorOf(Props(new Sink(writes, persisted.future)))
    val report = ReportEvent(
      entityId = "1",
      tick = 10L,
      lamportTick = 0L,
      data = "committed",
      label = "sample"
    )
    coordinator.send(sink, CommittedReportDelivery("run", 1L, report, coordinator.ref))
    writes.expectMsg(report)
    coordinator.expectMsg(DeliveryAccepted("run", 1L))
    coordinator.send(sink, FlushRuntimeReports(2L, coordinator.ref))
    coordinator.expectNoMessage(100.millis)
    persisted.success(())
    coordinator.expectMsg(RuntimeReportsFlushed(2L))
  }
  it should "acknowledge one batch after accepting every event and still wait for the final flush" in {
    val coordinator = TestProbe()
    val writes = TestProbe()
    val persisted = Promise[Unit]()
    val sink = system.actorOf(Props(new Sink(writes, persisted.future)))
    val reports =
      Vector(ReportEvent("a", 1L, 0L, data = "first"), ReportEvent("b", 1L, 0L, data = "second"))
    coordinator.send(sink, CommittedReportBatchDelivery("run", 7L, reports, coordinator.ref))
    writes.expectMsg(reports(0))
    writes.expectMsg(reports(1))
    coordinator.expectMsg(DeliveryAccepted("run", 7L))
    coordinator.expectNoMessage(50.millis)
    coordinator.send(sink, FlushRuntimeReports(8L, coordinator.ref))
    coordinator.expectNoMessage(50.millis)
    persisted.success(())
    coordinator.expectMsg(RuntimeReportsFlushed(8L))
  }
  it should "fail a partially accepted batch without acknowledging it" in {
    val coordinator = TestProbe()
    val sink = system.actorOf(Props(new ReportData(startRealTime = null) {
      override def onReport(event: ReportEvent): Unit =
        if (event.entityId == "bad") throw new IllegalStateException("write failed")
    }))
    coordinator.send(
      sink,
      CommittedReportBatchDelivery(
        "run",
        9L,
        Vector(ReportEvent("ok", 1L, 0L, data = "ok"), ReportEvent("bad", 1L, 0L, data = "bad")),
        coordinator.ref
      )
    )
    coordinator.expectMsgType[RuntimeFailure].reason should include("write failed")
    coordinator.expectNoMessage(50.millis)
  }
  it should "return a failure instead of claiming a successful flush" in {
    val coordinator = TestProbe()
    val persisted = Promise[Unit]()
    val sink = system.actorOf(Props(new Sink(TestProbe(), persisted.future)))
    coordinator.send(sink, FlushRuntimeReports(3L, coordinator.ref))
    persisted.failure(new IllegalStateException("storage failed"))
    coordinator.expectMsgType[RuntimeReportsFlushed].error.get should include("storage failed")
  }
}
