package org.interscity.htc
package core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class ReportJsonSpec extends AnyFlatSpec with Matchers {
  private def same(data: Any): Unit =
    JsonUtil.toReportJson(data) shouldBe JsonUtil.toJson(data)

  "toReportJson" should "match Jackson on a realistic link-summary row" in {
    same(Map(
      "event_type" -> "sumo_summary_step", "link_id" -> "12", "mode" -> "MICRO", "loaded" -> 1, "halting" -> 1,
      "duration" -> 1L, "meanSpeed" -> 0.0, "meanTravelTime" -> 3.3000000000000003, "arrived" -> 0L,
      "collisions" -> 0, "ended" -> 0, "inserted" -> 0, "discarded" -> 0, "meanWaitingTime" -> 3.3000000000000016))
  }

  it should "match Jackson on number edge cases, including non-finite doubles" in {
    same(Map("a" -> Double.NaN, "b" -> Double.PositiveInfinity, "c" -> Double.NegativeInfinity, "d" -> -0.0,
      "e" -> 1.0e-7, "f" -> 1.0e20, "g" -> Double.MaxValue, "h" -> Double.MinPositiveValue, "i" -> 100.0))
    same(Map("a" -> Long.MinValue, "b" -> Long.MaxValue, "c" -> Int.MinValue, "d" -> 0, "e" -> -1L))
    same(Map("f" -> 1.5f, "g" -> Float.NaN, "h" -> Float.NegativeInfinity, "i" -> 1.0e10f,
      "s" -> 7.toShort, "b" -> (-3).toByte))
  }

  it should "match Jackson on strings that need escaping" in {
    same(Map("q" -> "he said \"hi\"", "b" -> "back\\slash", "n" -> "line\nbreak\ttab\r", "c" -> "ctl\u0001\u001f",
      "u" -> "héllo — 日本語 😀", "s" -> "a/b", "e" -> "", "sep" -> "  ", "del" -> "\u007f"))
    same(Map("key with \"quotes\"" -> 1, "ключ" -> 2, "" -> 3, "tab\tkey" -> 4))
  }

  it should "match Jackson on null, boolean, empty and mixed rows" in {
    same(Map.empty[String, Any])
    same(Map("x" -> null, "t" -> true, "f" -> false, "s" -> "v"))
    same(Map("only" -> 1))
  }

  it should "fall back to Jackson, with identical output, for anything it does not handle" in {
    same(Map("nested" -> Map("a" -> 1), "list" -> List(1, 2, 3)))
    same(Map("opt" -> Some(4), "none" -> None))
    same(Map("big" -> BigDecimal("1.50"), "bi" -> BigInt(12)))
    same(Map(1 -> "int key"))
    same(List(1, 2))
    same("plain string")
    same(null)
    ReportJson.flatMapToJson(Map("nested" -> Map("a" -> 1))) shouldBe null
    ReportJson.flatMapToJson(Map("opt" -> Some(1))) shouldBe null
    ReportJson.flatMapToJson(Map(1 -> "k")) shouldBe null
    ReportJson.flatMapToJson(List(1)) shouldBe null
  }

  it should "actually take the fast path for flat primitive maps" in {
    ReportJson.flatMapToJson(Map("a" -> 1, "b" -> 2.5, "c" -> "x", "d" -> true, "e" -> null)) should not be null
  }

  it should "match Jackson on thousands of random flat maps, in every Scala map shape" in {
    val rnd = new Random(7)
    val alphabet = "abcXYZ019 _-\"\\\n\t/é日😀\u0001"
    def str(): String = Seq.fill(rnd.nextInt(12))(alphabet(rnd.nextInt(alphabet.length))).mkString
    def value(): Any = rnd.nextInt(10) match {
      case 0 => str()
      case 1 => rnd.nextLong()
      case 2 => rnd.nextInt()
      case 3 => rnd.nextDouble() * math.pow(10, rnd.nextInt(40) - 20)
      case 4 => rnd.nextBoolean()
      case 5 => null
      case 6 => rnd.nextFloat()
      case 7 => if (rnd.nextBoolean()) Double.NaN else Double.NegativeInfinity
      case 8 => rnd.nextInt(5).toShort
      case _ => 0.0
    }
    (1 to 4000).foreach { _ =>
      val entries = Seq.fill(rnd.nextInt(18))(str() -> value())
      same(entries.toMap)
      same(scala.collection.mutable.LinkedHashMap.from(entries))
      same(scala.collection.immutable.ListMap.from(entries))
      same(scala.collection.mutable.HashMap.from(entries))
    }
  }
}
