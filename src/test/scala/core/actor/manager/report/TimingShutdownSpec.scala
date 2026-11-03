package org.interscity.htc
package core.actor.manager.report

import com.typesafe.config.ConfigFactory
import core.enumeration.ReportTypeEnum
import core.entity.event.control.report.{RegisterReportersEvent, ReportEvent}
import core.metrics.core.{ExecutionTimingMetrics, TimedOperation}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.routing.RoundRobinPool
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.StopSimulationEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*

class TimingShutdownSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("TimingShutdownSpec", ConfigFactory.parseString("""
    pekko.actor.provider = local
    pekko.actor.fail-mixed-versions = off
    htc.report-manager.enabled = true
    htc.report-manager.enabled-strategies = ["json"]
  """).withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Sink(events: ActorRef, persisted: Future[Unit]) extends ReportData(startRealTime = null) {
    override protected def onReport(event: ReportEvent): Unit = events ! event
    override protected def flushCommittedReports(): Future[Unit] = { events ! "flushing"; persisted }
  }
  private class Manager(observer: ActorRef, router: ActorRef) extends ReportManager(null, observer, null) {
    override protected def createReportData(kind: ReportTypeEnum): ActorRef = router
  }
  "Timing report shutdown" should "collect through a router and await persistence before completing" in {
    ExecutionTimingMetrics.timers.measure(TimedOperation.RouteWalking) { () }
    val events = TestProbe()
    val observer = TestProbe()
    val persisted = Promise[Unit]()
    val router = system.actorOf(RoundRobinPool(1).props(Props(new Sink(events.ref, persisted.future))))
    val manager = system.actorOf(Props(new Manager(observer.ref, router)))
    observer.expectMsgType[RegisterReportersEvent](5.seconds)
    observer.watch(manager)
    manager ! StopSimulationEvent()
    var reports = Vector.empty[ReportEvent]
    events.fishForMessage(5.seconds) {
      case event: ReportEvent => reports :+= event; false
      case "flushing" => true
    }
    // A real Cluster.subscribe sends its initial snapshot while the final flush is active.
    // Restarting here loses both the pending flush and the completion promise.
    manager ! org.apache.pekko.cluster.ClusterEvent.CurrentClusterState()
    manager ! org.apache.pekko.routing.Routees(Vector.empty)
    reports.exists(_.label == "execution_timing_summary") shouldBe true
    reports.exists(_.data.asInstanceOf[Map[String, Any]]("operation") == "route_walking") shouldBe true
    observer.expectNoMessage(100.millis)
    persisted.success(())
    observer.expectTerminated(manager, 5.seconds)
  }
}
