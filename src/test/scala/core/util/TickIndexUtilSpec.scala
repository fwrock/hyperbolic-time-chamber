package org.interscity.htc
package core.util

import core.entity.actor.{ ActorDataSimulation, ActorSimulation }

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class TickIndexUtilSpec extends AnyFlatSpec with Matchers {
  private def actor(id: Long, content: Any): ActorSimulation =
    ActorSimulation(id, s"actor-$id", "example.Model", data = ActorDataSimulation("state", content))

  private def timedActor(id: Long, tick: Long): ActorSimulation =
    actor(id, Map("startTick" -> tick))

  private def record(id: Long, content: String): String =
    s"""{"id":$id,"name":"actor-$id","typeActor":"example.Model","data":{"dataType":"state","content":$content}}"""

  private def withSource(records: List[String])(test: String => Unit): Unit = {
    val path = Files.createTempFile("htc-tick-index", ".json")
    try {
      Files.writeString(path, records.mkString("[", ",", "]"))
      test(path.toString)
    } finally Files.delete(path)
  }

  "buildLightIndex" should "count unsorted duplicate ticks and default missing or nonnumeric ticks to zero" in {
    withSource(
      List(
        record(1, """{"startTick":12}"""),
        record(2, """{"startTick":-4}"""),
        record(3, """{"startTick":12}"""),
        record(4, "{}"),
        record(5, """{"startTick":"12"}"""),
        record(6, "null")
      )
    ) {
      path =>
        TickIndexUtil.buildLightIndex(path) shouldBe TickIndexUtil.LightTickIndex(
          tickCounts = Map(12L -> 2, -4L -> 1, 0L -> 3),
          totalActors = 6,
          maxTick = 12L,
          minTick = -4L
        )
    }
  }

  it should "retain sentinel counts while excluding the sentinel from bounds" in {
    withSource(
      List(
        record(1, s"""{"startTick":${Long.MinValue}}"""),
        record(2, """{"startTick":-5}""")
      )
    ) {
      path =>
        val index = TickIndexUtil.buildLightIndex(path)
        index.tickCounts shouldBe Map(Long.MinValue -> 1, -5L -> 1)
        index.totalActors shouldBe 2
        index.minTick shouldBe -5L
        index.maxTick shouldBe 0L
    }
  }

  it should "use zero bounds for empty and sentinel-only sources" in {
    withSource(Nil) {
      path =>
        TickIndexUtil
          .buildLightIndex(path) shouldBe TickIndexUtil.LightTickIndex(Map.empty, 0, 0L, 0L)
    }
    withSource(List(record(1, s"""{"startTick":${Long.MinValue}}"""))) {
      path =>
        TickIndexUtil.buildLightIndex(path) shouldBe
          TickIndexUtil.LightTickIndex(Map(Long.MinValue -> 1), 1, 0L, 0L)
    }
  }

  it should "propagate invalid actor source failures" in {
    withSource(List(record(1, "{}"), "1")) {
      path =>
        intercept[IllegalArgumentException](TickIndexUtil.buildLightIndex(path))
    }
  }

  "readMatchingChunk" should "preserve order and inclusive boundaries across consecutive chunks" in {
    val actors = List(
      timedActor(1, 4),
      timedActor(2, 5),
      timedActor(3, 6),
      timedActor(4, 9),
      timedActor(5, 7)
    )
    val iter = actors.iterator
    TickIndexUtil.readMatchingChunk(iter, 5, 7, 2) shouldBe (actors.slice(1, 3), false)
    TickIndexUtil.readMatchingChunk(iter, 5, 7, 2) shouldBe (List(actors.last), true)
  }

  it should "observe exhaustion only after probing beyond an exactly full chunk" in {
    val expected = timedActor(1, 5)
    val iter = Iterator(expected)
    TickIndexUtil.readMatchingChunk(iter, 5, 5, 1) shouldBe (List(expected), false)
    TickIndexUtil.readMatchingChunk(iter, 5, 5, 1) shouldBe (Nil, true)
  }

  it should "leave the iterator untouched for nonpositive chunk limits" in {
    val expected = timedActor(1, 5)
    val iter = Iterator(expected)
    for (limit <- List(0, -1))
      TickIndexUtil.readMatchingChunk(iter, 0, 10, limit) shouldBe (Nil, false)
    iter.next() shouldBe expected
  }

  it should "decode numeric Scala and Jackson maps while defaulting unsupported or absent values to zero" in {
    val javaContent = new java.util.LinkedHashMap[String, Any]()
    javaContent.put("startTick", 7L)
    val absent = List(
      actor(1, Map.empty[String, Any]),
      actor(2, Map("startTick" -> "7")),
      actor(3, Map("startTick" -> null)),
      actor(4, null),
      actor(5, "unsupported"),
      actor(6, Map.empty[String, Any]).copy(data = null)
    )
    val numeric = List(actor(7, javaContent), timedActor(8, 7))
    TickIndexUtil.readMatchingChunk((absent ++ numeric).iterator, 0, 0, 100) shouldBe (absent, true)
    TickIndexUtil.readMatchingChunk(numeric.iterator, 7, 7, 100) shouldBe (numeric, true)
  }

  it should "consume an inverted range without returning records" in {
    TickIndexUtil.readMatchingChunk(Iterator(timedActor(1, 5)), 7, 5, 10) shouldBe (Nil, true)
  }

  "countActorsInRange" should "sum only inclusive bounds, including negative and sentinel ticks" in {
    val counts = Map(Long.MinValue -> 4, -2L -> 2, 0L -> 3, 5L -> 7, 6L -> 9)
    TickIndexUtil.countActorsInRange(counts, -2, 5) shouldBe 12
    TickIndexUtil.countActorsInRange(counts, Long.MinValue, Long.MinValue) shouldBe 4
    TickIndexUtil.countActorsInRange(counts, 5, -2) shouldBe 0
    TickIndexUtil.countActorsInRange(Map.empty, 0, 10) shouldBe 0
  }
}
