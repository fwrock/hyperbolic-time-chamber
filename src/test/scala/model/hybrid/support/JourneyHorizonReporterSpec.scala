package org.interscity.htc
package model.hybrid.support

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class JourneyHorizonReporterSpec extends AnyFlatSpec with Matchers {
  "a queued origin at the horizon" should "remain not departed without inventing duration or distance" in {
    val rows=mutable.Map.empty[String,Map[String,Any]]
    JourneyHorizonReporter.report("car","42",1,4,-1,1000,0,None,(data,label)=>rows(label)=data,.1,scheduledDeparture=Some(10))
    val trip=rows("sumo_tripinfo")
    trip("depart") shouldBe -1.0
    trip("arrival") shouldBe -1L
    trip("duration") shouldBe -1.0
    trip("routeLength") shouldBe 0.0
    trip("completion_reason") shouldBe "not_departed"
    trip("scheduledDepart") shouldBe 1.0
    trip("actualDepart") shouldBe -1.0
    trip("departDelay") shouldBe -1.0
  }
  "horizon summaries" should "censor arrival and retain only confirmed distance for every vehicle type" in {
    Seq("car", "bus", "subway", "bicycle", "motorcycle").foreach { kind =>
      val rows = mutable.Map.empty[String, Map[String, Any]]
      JourneyHorizonReporter.report(kind, "42", 1L, 4L, 995L, 1000L, 600.0, Some(7L),
        (data, label) => rows(label) = data)
      rows("journey_completed")("reached_destination") shouldBe false
      rows("journey_completed")("final_node").asInstanceOf[AnyRef] shouldBe null
      rows("journey_completed")("total_distance") shouldBe 600.0
      rows("sumo_tripinfo")("arrival") shouldBe -1L
      rows("sumo_tripinfo")("duration") shouldBe 5L
      rows("sumo_tripinfo")("routeLength") shouldBe 600.0
      rows.values.foreach { row =>
        row("horizon_truncated") shouldBe true
        row("tick") shouldBe 1000L
      }
    }
  }
}
