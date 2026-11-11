package org.interscity.htc
package model.hybrid.actor

import core.entity.actor.properties.Properties
import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.event.data.bus.RegisterPassengerData
import model.hybrid.entity.state.BusStopState
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.compiletime.uninitialized

class BusStopPassengerReportingSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private var systemInstance: ActorSystem = uninitialized
  private implicit def system: ActorSystem = systemInstance

  override def beforeAll(): Unit = {
    systemInstance = ActorSystem("BusStopPassengerReportingSpec", ConfigFactory.parseString(
      "pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off").withFallback(ConfigFactory.load()))
  }
  override def afterAll(): Unit = { systemInstance.terminate(); () }

  private class ObservedStop extends BusStop(Properties(entityId = "800", resourceId = "test",
    timeManagers = mutable.Map.empty)) {
    val reports = mutable.Buffer.empty[(Map[String, Any], String)]
    override protected def report(data: Any, label: String = null): Unit =
      reports += ((data.asInstanceOf[Map[String, Any]], label))
    def prepare(): Unit = { state = BusStopState(nodeId = 1L, label = "stop") }
  }

  "BusStop passenger reporting" should "report the first arrival and each subsequent queue size" in {
    val stop = TestActorRef(new ObservedStop, "observed-stop").underlyingActor
    stop.prepare()
    for (person <- List(1L, 2L)) stop.actInteractWith(ActorInteractionEvent(
      tick = 10L, lamportTick = person, actorRefId = person, shardRefId = "hybrid.actor.Person",
      actorPathRef = person.toString, actorClassType = "hybrid.actor.Person",
      data = RegisterPassengerData("line"), resourceId = "test"))
    stop.reports.map(_._2) shouldBe Seq("bus_stop_passenger_arrived", "bus_stop_passenger_arrived")
    stop.reports.map(_._1("passengers_waiting")) shouldBe Seq(1, 2)
    all(stop.reports.map(_._1("route_label"))) shouldBe "line"
  }
}
