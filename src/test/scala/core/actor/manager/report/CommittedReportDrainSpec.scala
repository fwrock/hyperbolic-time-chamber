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
  private implicit val system: ActorSystem = ActorSystem("CommittedReportDrainSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Sink(writes: TestProbe, completion: Future[Unit]) extends ReportData(startRealTime = null) {
    override protected def onReport(event: ReportEvent): Unit = writes.ref ! event
    override protected def flushCommittedReports(): Future[Unit] = completion
  }
  "committed sink drain" should "acknowledge storage completion instead of only delivery into a buffer" in {
    val writes = TestProbe()
    val coordinator = TestProbe()
    val persisted = Promise[Unit]()
    val sink = system.actorOf(Props(new Sink(writes, persisted.future)))
    val report = ReportEvent(entityId = "1", tick = 10L, lamportTick = 0L, data = "committed", label = "sample")
    coordinator.send(sink, CommittedReportDelivery("run", 1L, report, coordinator.ref))
    writes.expectMsg(report)
    coordinator.expectMsg(DeliveryAccepted("run", 1L))
    coordinator.send(sink, FlushRuntimeReports(2L, coordinator.ref))
    coordinator.expectNoMessage(100.millis)
    persisted.success(())
    coordinator.expectMsg(RuntimeReportsFlushed(2L))
  }
  it should "return a failure instead of claiming a successful flush" in {
    val coordinator = TestProbe()
    val persisted = Promise[Unit]()
    val sink = system.actorOf(Props(new Sink(TestProbe(), persisted.future)))
    coordinator.send(sink, FlushRuntimeReports(3L, coordinator.ref))
    persisted.failure(new IllegalStateException("storage failed"))
    coordinator.expectMsgType[RuntimeReportsFlushed].error.get should include ("storage failed")
  }
}
