package org.interscity.htc
package core.actor.manager.report

import core.entity.event.control.report.RegisterReportersEvent
import core.enumeration.ReportTypeEnum
import core.runtime.accounting.{FlushRuntimeReports, RuntimeReportsFlushed}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.cluster.{Cluster, MemberStatus}
import org.apache.pekko.routing.{GetRoutees, Routees}
import org.apache.pekko.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDateTime
import scala.concurrent.duration.*

class ParquetEmptyFlushSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("ParquetEmptyFlushSpec",
    ConfigFactory.parseString("""
      pekko.actor.provider = cluster
      pekko.actor.fail-mixed-versions = off
      pekko.remote.artery.canonical.hostname = "127.0.0.1"
      pekko.remote.artery.canonical.port = 0
      pekko.cluster.seed-nodes = []
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
      htc.report-manager.enabled-strategies = ["parquet"]
      htc.report-manager.parquet.number-of-instances = 1
      htc.report-manager.parquet.number-of-instances-per-node = 1
    """).withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }
  private class Manager(probe: ActorRef)
    extends ReportManager(probe, probe, LocalDateTime.of(2025, 1, 1, 0, 0)) {
    override protected val config = system.settings.config
  }

  "an empty Parquet reporter" should "acknowledge repeated flushes before any file has been opened" in {
    val probe = TestProbe()
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    probe.awaitAssert(cluster.selfMember.status shouldBe MemberStatus.Up, 10.seconds, 50.millis)
    val manager = system.actorOf(Props(new Manager(probe.ref)))
    val sinks = probe.expectMsgType[RegisterReportersEvent](5.seconds)
    val parquet = sinks.reporters(ReportTypeEnum.parquet)
    probe.awaitAssert({
      parquet.tell(GetRoutees, probe.ref)
      probe.expectMsgType[Routees].routees.size shouldBe 1
    }, 5.seconds, 50.millis)
    (1L to 2L).foreach { round =>
      parquet ! FlushRuntimeReports(round, probe.ref)
      probe.expectMsg(RuntimeReportsFlushed(round, None))
    }
    system.stop(manager)
  }
}
