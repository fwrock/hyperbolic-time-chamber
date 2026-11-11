package org.interscity.htc.core.cache

import org.interscity.htc.core.entity.configuration.CalculationCacheConfig
import java.io.{DataInputStream, DataOutputStream}
import java.nio.file.Files
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CalculationCacheSpec extends AnyFlatSpec with Matchers {
  private val codec = new CalculationCodec[String] {
    def read(in: DataInputStream): String = in.readUTF()
    def write(value: String, out: DataOutputStream): Unit = out.writeUTF(value)
  }
  private def key(value: Int): String = CalculationCache.fingerprint(_.writeInt(value))

  "scenario cache configuration" should "survive engine JSON decoding and reject unsafe identifiers" in {
    val config = org.interscity.htc.core.util.JsonUtil.fromJson[CalculationCacheConfig](
      """{"directory":"/tmp/cache","entries":["routing.alt.vehicle"],"refresh":true}""")
    config shouldBe CalculationCacheConfig("/tmp/cache", List("routing.alt.vehicle"), true)
    intercept[IllegalArgumentException](CalculationCacheConfig(entries = List("../escape")))
    intercept[IllegalArgumentException](CalculationCacheConfig(entries = List("x", "x")))
  }

  "calculation cache" should "reuse immutable values, invalidate inputs/schema and honor selection and refresh" in {
    val dir = Files.createTempDirectory("htc-cache-test")
    val config = CalculationCacheConfig(dir.toString, List("test.result"))
    var calls = 0
    def load(c: Option[CalculationCacheConfig] = Some(config), input: Int = 1, version: Int = 1): String =
      CalculationCache.getOrCompute(c, "test.result", version, key(input), codec) { calls += 1; s"result-$calls" }
    load() shouldBe "result-1"
    load() shouldBe "result-1"
    calls shouldBe 1
    load(input = 2) shouldBe "result-2"
    load(version = 2) shouldBe "result-3"
    load(Some(config.copy(entries = Nil))) shouldBe "result-4"
    load(None) shouldBe "result-5"
    load(Some(config.copy(refresh = true))) shouldBe "result-6"
    load() shouldBe "result-6"
    val stream = Files.list(dir)
    try stream.forEach(p => Files.delete(p)) finally stream.close()
    Files.delete(dir)
  }

  it should "rebuild a truncated gzip file and tolerate an unwritable cache directory" in {
    val dir = Files.createTempDirectory("htc-cache-corrupt")
    val config = Some(CalculationCacheConfig(dir.toString, List("test.result")))
    def load(value: String): String = CalculationCache.getOrCompute(config, "test.result", 1, key(1), codec)(value)
    load("original") shouldBe "original"
    val stream = Files.list(dir)
    val path = try stream.findFirst().get() finally stream.close()
    val bytes = Files.readAllBytes(path)
    Files.write(path, bytes.take(bytes.length - 5))
    load("rebuilt") shouldBe "rebuilt"
    load("ignored") shouldBe "rebuilt"
    val blocked = Files.createTempFile("htc-cache-blocked", ".file")
    CalculationCache.getOrCompute(Some(CalculationCacheConfig(blocked.toString, List("test.result"))),
      "test.result", 1, key(1), codec)("computed") shouldBe "computed"
    Files.delete(blocked); Files.delete(path); Files.delete(dir)
  }
}
