package org.interscity.htc
package core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class LoadSimulationConfigSpec extends AnyFlatSpec with Matchers {
  private val flat =
    """{"name":"s","description":"d","startTick":0,"duration":10,"timeUnit":"seconds","timeStep":1,
      |"startRealTime":"2025-01-01T00:00:00.000","actorsDataSources":[],
      |"partial":{"selectionFile":"/s.json","filterableClasses":["Person","Car"]}}""".stripMargin

  private def load(json: String) = {
    val f = Files.createTempFile("simulation", ".json"); Files.writeString(f, json)
    try SimulationUtil.loadSimulationConfig(f.toString) finally Files.delete(f)
  }

  "a flat simulation.json" should "load through the file path (it used to come back null)" in {
    val c = load(flat)
    c should not be null
    c.name shouldBe "s"
    c.partial.map(_.filterableClasses) shouldBe Some(List("Person", "Car"))
  }

  "a wrapped simulation.json" should "still load" in {
    load(s"""{"simulation": $flat}""").name shouldBe "s"
  }

  "a config without the partial block" should "have no partial simulation" in {
    load(flat.replaceAll(""",\s*"partial":\{[^}]*\}""", "")).partial shouldBe None
  }
}
