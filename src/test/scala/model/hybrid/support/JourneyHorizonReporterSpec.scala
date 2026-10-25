package org.interscity.htc
package model.hybrid.support

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable

class JourneyHorizonReporterSpec extends AnyFlatSpec with Matchers {
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
