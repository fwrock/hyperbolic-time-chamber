package org.interscity.htc
package core.runtime.accounting

import core.entity.event.ActorInteractionEvent
import core.runtime.EventOrder
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.actor.Identify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class AccountedRuntimeCoordinatorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("AccountedRuntimeCoordinatorSpec",
    ConfigFactory.parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  override def afterAll(): Unit = { system.terminate(); () }

  private def rig(reportManager: Option[org.apache.pekko.actor.ActorRef] = None): (TestProbe, TestProbe, TestProbe, org.apache.pekko.actor.ActorRef, String) = {
    val parent = TestProbe()
    val manager = TestProbe()
    val lp = TestProbe()
    val coordinator = parent.childActorOf(AccountedRuntimeCoordinator.props(1, 1000L, reportManager))
    manager.send(coordinator, JoinRuntimeManager(manager.ref))
    lp.send(coordinator, JoinRuntime(Identify(id = 1L, classType = "example.LP"), lp.ref, None))
    val joined = lp.expectMsgType[RuntimeJoined]
    val registration = manager.expectMsgType[RuntimeDelivery]
    manager.send(coordinator, DeliveryAccepted(joined.epoch, registration.token))
    parent.send(coordinator, StartRuntime(0L))
    val start = manager.expectMsgType[RuntimeDelivery]
    manager.send(coordinator, DeliveryAccepted(joined.epoch, start.token))
    (parent, manager, lp, coordinator, joined.epoch)
  }

  for (anti <- Seq(false, true)) {
    "an accounted cut" should s"wait for acceptance of an in-transit ${if (anti) "anti-message" else "positive"}" in {
      val (parent, manager, lp, coordinator, epoch) = rig()
      val event = ActorInteractionEvent(10L, 0L, 1L, "example.LP", "", "example.LP",
        data = "payload", resourceId = "test", seq = 1L, isAntiMessage = anti,
        order = Some(EventOrder(10L, 0L, "input")), sourceEpoch = epoch)
      lp.send(coordinator, RouteInteraction(epoch, LogicalProcessId("example.LP", 1L), event))
      val delivery = lp.expectMsgType[RuntimeDelivery]
      parent.send(coordinator, RequestRuntimeRound())
      val frozen = lp.expectMsgType[FreezeRuntime]
      manager.expectMsg(frozen)
      lp.send(coordinator, RuntimeFrozen(frozen.round))
      manager.send(coordinator, RuntimeFrozen(frozen.round))
      lp.expectNoMessage(100.millis)
      parent.expectNoMessage(100.millis)
      lp.send(coordinator, DeliveryAccepted(epoch, delivery.token))
      lp.expectMsg(CollectRuntimeSnapshot(frozen.round))
      manager.expectMsg(CollectRuntimeSnapshot(frozen.round))
      lp.send(coordinator, RuntimeSnapshot(frozen.round, Some(10L), 0, 0))
      manager.send(coordinator, RuntimeSnapshot(frozen.round, None, 0, 0))
      parent.expectMsg(RuntimeAdvanced(frozen.round, 10L))
      lp.expectMsg(CommitRuntime(frozen.round, 10L))
      manager.expectMsg(CommitRuntime(frozen.round, 10L))
      lp.send(coordinator, RuntimeCommitted(frozen.round, false))
      manager.send(coordinator, RuntimeCommitted(frozen.round, false))
      lp.expectMsg(ResumeRuntime(frozen.round))
      manager.expectMsg(ResumeRuntime(frozen.round))
      parent.expectNoMessage(100.millis)
    }
  }

  it should "require fresh round reports and all retirement acknowledgements before completion" in {
    val (parent, manager, lp, coordinator, _) = rig()
    parent.send(coordinator, RuntimeSnapshot(0L, None, 0, 0))
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round - 1L, None, 0, 0))
    manager.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectNoMessage(100.millis)
    lp.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, Long.MaxValue))
    lp.expectMsg(CommitRuntime(freeze.round, Long.MaxValue))
    manager.expectMsg(CommitRuntime(freeze.round, Long.MaxValue))
    lp.send(coordinator, RuntimeCommitted(freeze.round, true))
    manager.send(coordinator, RuntimeCommitted(freeze.round, false))
    lp.expectMsg(RetireRuntimeProcess(freeze.round, finalCut = true))
    manager.expectMsg(RuntimeStopManagers(freeze.round))
    manager.send(coordinator, RuntimeProcessRetired(freeze.round))
    parent.expectNoMessage(100.millis)
    lp.send(coordinator, RuntimeProcessRetired(freeze.round))
    parent.expectMsg(RuntimeFinished(Long.MaxValue, false))
  }
  it should "hold the cut while a declared creation is outstanding" in {
    val (parent, manager, lp, coordinator, _) = rig()
    lp.send(coordinator, BeginRuntimeCreation("new-lp", 10L))
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectNoMessage(100.millis)
    parent.expectNoMessage(100.millis)
    lp.send(coordinator, EndRuntimeCreation("new-lp"))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
  }

  it should "wait for accepted report delivery and the sink flush barrier before completion" in {
    val reportManager = TestProbe()
    val sink = TestProbe()
    val (parent, manager, lp, coordinator, epoch) = rig(Some(reportManager.ref))
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    manager.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, Long.MaxValue))
    lp.expectMsg(CommitRuntime(freeze.round, Long.MaxValue))
    manager.expectMsg(CommitRuntime(freeze.round, Long.MaxValue))
    val report = core.entity.event.control.report.ReportEvent("1", 10L, 0L, data = "committed", label = "test")
    lp.send(coordinator, PublishCommittedReport(sink.ref, report))
    lp.send(coordinator, RuntimeCommitted(freeze.round, true))
    manager.send(coordinator, RuntimeCommitted(freeze.round, false))
    val delivery = sink.expectMsgType[CommittedReportDelivery]
    reportManager.expectNoMessage(100.millis)
    sink.send(coordinator, DeliveryAccepted(epoch, delivery.token))
    lp.expectMsg(RetireRuntimeProcess(freeze.round, finalCut = true))
    manager.expectMsg(RuntimeStopManagers(freeze.round))
    val drain = reportManager.expectMsgType[DrainRuntimeReports]
    drain.targets shouldBe Set(sink.ref)
    lp.send(coordinator, RuntimeProcessRetired(freeze.round))
    manager.send(coordinator, RuntimeProcessRetired(freeze.round))
    parent.expectNoMessage(100.millis)
    reportManager.send(coordinator, RuntimeReportsFlushed(freeze.round))
    parent.expectMsg(RuntimeFinished(Long.MaxValue, false))
  }

  it should "pause only at an acknowledged cut and resume the same run" in {
    val (parent, manager, lp, coordinator, _) = rig()
    parent.send(coordinator, PauseRuntimeExecution())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round, Some(10L), 0, 0))
    manager.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, 10L))
    lp.expectMsg(CommitRuntime(freeze.round, 10L))
    manager.expectMsg(CommitRuntime(freeze.round, 10L))
    lp.send(coordinator, RuntimeCommitted(freeze.round, false))
    manager.send(coordinator, RuntimeCommitted(freeze.round, false))
    parent.expectMsg(RuntimeExecutionPaused(freeze.round))
    lp.expectNoMessage(100.millis)
    parent.send(coordinator, ResumeRuntimeExecution())
    lp.expectMsg(ResumeRuntime(freeze.round))
    manager.expectMsg(ResumeRuntime(freeze.round))
  }

  it should "distinguish a settled horizon from normal completion" in {
    val (parent, manager, lp, coordinator, epoch) = rig()
    val future = ActorInteractionEvent(1002L, 0L, 1L, "example.LP", "", "example.LP",
      data = "future", resourceId = "test", sourceEpoch = epoch)
    lp.send(coordinator, RouteInteraction(epoch, LogicalProcessId("example.LP", 1L), future))
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    manager.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, 1001L))
    lp.expectMsg(CommitRuntime(freeze.round, 1001L))
    manager.expectMsg(CommitRuntime(freeze.round, 1001L))
    lp.send(coordinator, RuntimeCommitted(freeze.round, false))
    manager.send(coordinator, RuntimeCommitted(freeze.round, false))
    lp.expectMsg(RetireRuntimeProcess(freeze.round, finalCut = true))
    manager.expectMsg(RuntimeStopManagers(freeze.round))
    lp.send(coordinator, RuntimeProcessRetired(freeze.round))
    manager.send(coordinator, RuntimeProcessRetired(freeze.round))
    parent.expectMsg(RuntimeFinished(1001L, true))
  }

  it should "retain a blocked obligation as live work and fail an unbounded one" in {
    val (parent, manager, lp, coordinator, _) = rig()
    parent.send(coordinator, RequestRuntimeRound())
    val freeze = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(freeze)
    lp.send(coordinator, RuntimeFrozen(freeze.round))
    manager.send(coordinator, RuntimeFrozen(freeze.round))
    lp.expectMsg(CollectRuntimeSnapshot(freeze.round))
    manager.expectMsg(CollectRuntimeSnapshot(freeze.round))
    lp.send(coordinator, RuntimeSnapshot(freeze.round, Some(10L), 1, 0))
    manager.send(coordinator, RuntimeSnapshot(freeze.round, None, 0, 0))
    parent.expectMsg(RuntimeAdvanced(freeze.round, 10L))
    lp.expectMsg(CommitRuntime(freeze.round, 10L))
    manager.expectMsg(CommitRuntime(freeze.round, 10L))
    lp.send(coordinator, RuntimeCommitted(freeze.round, false))
    manager.send(coordinator, RuntimeCommitted(freeze.round, false))
    lp.expectMsg(ResumeRuntime(freeze.round))
    manager.expectMsg(ResumeRuntime(freeze.round))
    parent.send(coordinator, RequestRuntimeRound())
    val next = lp.expectMsgType[FreezeRuntime]
    manager.expectMsg(next)
    lp.send(coordinator, RuntimeFrozen(next.round))
    manager.send(coordinator, RuntimeFrozen(next.round))
    lp.expectMsg(CollectRuntimeSnapshot(next.round))
    manager.expectMsg(CollectRuntimeSnapshot(next.round))
    lp.send(coordinator, RuntimeSnapshot(next.round, None, 1, 0))
    manager.send(coordinator, RuntimeSnapshot(next.round, None, 0, 0))
    parent.expectMsgType[RuntimeFailure].reason should include ("blocked obligation")
  }

  it should "keep failure terminal when late start or round requests arrive" in {
    val (parent, manager, lp, coordinator, _) = rig()
    parent.send(coordinator, RuntimeFailure("unsupported capability"))
    parent.expectMsg(RuntimeFailure("unsupported capability"))
    lp.expectMsgType[FreezeRuntime]
    manager.expectMsgType[FreezeRuntime]
    parent.send(coordinator, StartRuntime(0L))
    parent.send(coordinator, RequestRuntimeRound())
    parent.expectNoMessage(100.millis)
    lp.expectNoMessage(100.millis)
    manager.expectNoMessage(100.millis)
  }

}
