package org.interscity.htc
package model.hybrid.support.link

import model.hybrid.entity.event.data.MicroLeaveLinkData
import model.hybrid.entity.state.LinkState
import model.hybrid.micro.strategy.MicroVehicleUpdate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class LinkMicroSimulationHandlerSpec extends AnyFlatSpec with Matchers {
  "A MICRO leave report" should "measure elapsed time between entry and arrival rather than count both endpoint ticks" in {
    var state = LinkState(startTick = 0L, from = 1L, to = 2L, length = 100.0,
      lanes = 1, speedLimit = 50.0, capacity = 20.0, freeSpeed = 50.0)
    val sent = mutable.Buffer.empty[AnyRef]
    val reporter = new LinkMetricsReporter((_, _) => (), () => "3", () => 6L, 60, 10,
      () => state, _ => Some(2L), _ => 1.5, () => Nil, _ => ())
    val handler = new LinkMicroSimulationHandler(
      entityIdFn = () => "3", currentTickFn = () => 6L,
      sendMessageFn = (_, _, data, _) => { sent += data; () },
      getLinkStateFn = () => state, setLinkStateFn = s => state = s,
      getVehicleEntryTickFn = _ => Some(2L), removeVehicleEntryTickFn = _ => (),
      getVehicleWaitingSecondsFn = _ => 1.5, removeVehicleWaitingSecondsFn = _ => (),
      vehicleWaitingSeconds = mutable.Map.empty, isMicroScheduledFn = () => false,
      setMicroScheduledFn = _ => (), getSignalAtExitFn = () => None,
      metricsReporter = reporter, logDebugFn = _ => ())
    handler.sendMicroLeaveLinkToVehicle(MicroVehicleUpdate(9L, "cars", 9, 100.0, 10.0,
      0.0, 0, None, 100.0, 0.0, 10.0, reachedEnd = true))
    val data = sent.head.asInstanceOf[MicroLeaveLinkData]
    data.travelTime shouldBe 4.0
    data.averageSpeed shouldBe 25.0
    data.waitingTimeSeconds shouldBe 1.5
  }
}
