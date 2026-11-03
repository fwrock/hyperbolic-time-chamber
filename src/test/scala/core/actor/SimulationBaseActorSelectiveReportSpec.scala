package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.event.{ ActorInteractionEvent, SpontaneousEvent }
import core.entity.event.control.report.ReportEvent
import core.entity.state.BaseState
import core.enumeration.{ ReportTypeEnum, TimeManagerTypeEnum }
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{ TestActorRef, TestKit, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.duration.*

class SimulationBaseActorSelectiveReportSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("SelectiveReportSpec", ConfigFactory.parseString("""
    pekko.actor.provider = local
    pekko.actor.fail-mixed-versions = off
    htc.report-manager.default-enabled = false
    htc.report-manager.default-strategy = clickhouse
  """).withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private class TestActor(p: Properties) extends SimulationBaseActor[BaseState](p) {
    override protected val config = system.settings.config
    override protected def actSpontaneous(event: SpontaneousEvent): Unit = onFinishSpontaneous(None)
    override def actInteractWith(event: ActorInteractionEvent): Unit = ()
    def emit(): Unit = {
      // No default reporter is installed: this must neither emit nor attempt a fallback lookup.
      report("generic", "generic-report")
      reportToSpecificReporter(ReportTypeEnum.clickhouse, "flow", "vehicle_link_flow")
    }
  }

  "disabling default reports" should "retain targeted flow reports with no Parquet sink" in {
    val tm, sink = TestProbe()
    val p = Properties(entityId = "90000001", resourceId = "r",
      timeManagers = mutable.Map(TimeManagerTypeEnum.DISCRETE_EVENT -> tm.ref),
      defaultTimeManagerType = TimeManagerTypeEnum.DISCRETE_EVENT,
      reporters = mutable.Map(ReportTypeEnum.clickhouse -> sink.ref))
    val actor = TestActorRef(new TestActor(p), "90000001")
    actor.underlyingActor.emit()
    sink.expectMsgType[ReportEvent](3.seconds).data shouldBe "flow"
    sink.expectNoMessage(150.millis)
  }
}
