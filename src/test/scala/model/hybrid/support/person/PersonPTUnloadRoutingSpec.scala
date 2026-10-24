package org.interscity.htc
package model.hybrid.support.person

import core.entity.event.ActorInteractionEvent
import core.enumeration.CreationTypeEnum.LoadBalancedDistributed
import model.hybrid.entity.state.plan.ConcreteMode
import model.hybrid.entity.event.data.bus.BusUnloadPassengerData
import model.hybrid.entity.event.data.subway.SubwayUnloadPassengerData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class PersonPTUnloadRoutingSpec extends AnyFlatSpec with Matchers {
  for (mode <- Seq(ConcreteMode.Bus, ConcreteMode.Subway); arrival <- Seq(false, true)) {
    "PT unload routing" should s"answer $mode at arrival=$arrival using an explicit creation type" in {
      val sent = mutable.ArrayBuffer.empty[(Long, String, Any, String, Any)]
      val handler = new PersonPTTripHandler("1", (_, _) => (),
        (id, model, data, event, creation) => sent += ((id, model, data, event, creation)), _ => ())
      val request = ActorInteractionEvent(10L, 0L, 2L, "", "", s"hybrid.actor.$mode", data = "request", resourceId = "")
      handler.handlePTUnloadRequest(request, if (arrival) 4L else 3L, mode, 4L) shouldBe arrival
      sent.size shouldBe 1
      sent.head._1 shouldBe 2L
      sent.head._2 shouldBe request.actorClassType
      sent.head._5 shouldBe LoadBalancedDistributed
      sent.head._3 shouldBe (if (mode == ConcreteMode.Bus) BusUnloadPassengerData(arrival) else SubwayUnloadPassengerData(arrival))
    }
  }
}
