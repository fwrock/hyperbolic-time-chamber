package org.interscity.htc.core.cache

import org.interscity.htc.core.entity.configuration.CalculationCacheConfig
import java.io.{ DataInputStream, DataOutputStream }
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
      """{"directory":"/tmp/cache","entries":["routing.alt.vehicle"],"refresh":true}"""
    )
    config shouldBe CalculationCacheConfig("/tmp/cache", List("routing.alt.vehicle"), true)
    intercept[IllegalArgumentException](CalculationCacheConfig(entries = List("../escape")))
    intercept[IllegalArgumentException](CalculationCacheConfig(entries = List("x", "x")))
  }

  "calculation cache" should "reuse immutable values, invalidate inputs/schema and honor selection and refresh" in {
    val dir = Files.createTempDirectory("htc-cache-test")
    val config = CalculationCacheConfig(dir.toString, List("test.result"))
    var calls = 0
    def load(
      c: Option[CalculationCacheConfig] = Some(config),
      input: Int = 1,
      version: Int = 1
    ): String =
      CalculationCache.getOrCompute(c, "test.result", version, key(input), codec) {
        calls += 1; s"result-$calls"
      }
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
    try
      stream.forEach(
        p => Files.delete(p)
      )
    finally stream.close()
    Files.delete(dir)
  }

  it should "rebuild a truncated gzip file and tolerate an unwritable cache directory" in {
    val dir = Files.createTempDirectory("htc-cache-corrupt")
    val config = Some(CalculationCacheConfig(dir.toString, List("test.result")))
    def load(value: String): String =
      CalculationCache.getOrCompute(config, "test.result", 1, key(1), codec)(value)
    load("original") shouldBe "original"
    val stream = Files.list(dir)
    val path =
      try stream.findFirst().get()
      finally stream.close()
    val bytes = Files.readAllBytes(path)
    Files.write(path, bytes.take(bytes.length - 5))
    load("rebuilt") shouldBe "rebuilt"
    load("ignored") shouldBe "rebuilt"
    val blocked = Files.createTempFile("htc-cache-blocked", ".file")
    CalculationCache.getOrCompute(
      Some(CalculationCacheConfig(blocked.toString, List("test.result"))),
      "test.result",
      1,
      key(1),
      codec
    )("computed") shouldBe "computed"
    Files.delete(blocked); Files.delete(path); Files.delete(dir)
  }

  it should "preserve a previous entry and remove staging files when encoding a refresh fails" in {
    withDirectory {
      dir =>
        val config = CalculationCacheConfig(dir.toString, List("test.result"))
        val fingerprint = key(1)
        CalculationCache.getOrCompute(Some(config), "test.result", 1, fingerprint, codec)(
          "original"
        )
        val failingCodec = new CalculationCodec[String] {
          def read(in: DataInputStream): String = codec.read(in)
          def write(value: String, out: DataOutputStream): Unit = {
            out.writeUTF("partial")
            throw new java.io.IOException("encoding failed")
          }
        }

        CalculationCache.getOrCompute(
          Some(config.copy(refresh = true)),
          "test.result",
          1,
          fingerprint,
          failingCodec
        )("replacement") shouldBe "replacement"
        CalculationCache.getOrCompute(
          Some(config),
          "test.result",
          1,
          fingerprint,
          codec
        )(fail("the previous entry must survive")) shouldBe "original"
        scala.util.Using.resource(Files.list(dir)) {
          entries =>
            entries.count() shouldBe 1L
        }
    }
  }

  it should "propagate calculation failures without publishing a cache file" in {
    withDirectory {
      dir =>
        val config = Some(CalculationCacheConfig(dir.toString, List("test.result")))
        val error = new IllegalStateException("calculation failed")

        val thrown = intercept[IllegalStateException] {
          CalculationCache.getOrCompute(config, "test.result", 1, key(1), codec)(throw error)
        }

        thrown should be theSameInstanceAs error
        scala.util.Using.resource(Files.list(dir)) {
          entries =>
            entries.count() shouldBe 0L
        }
    }
  }

  it should "reject a mismatched envelope and a payload with trailing data" in {
    withDirectory {
      dir =>
        val config = Some(CalculationCacheConfig(dir.toString, List("test.result")))
        val fingerprint = key(1)
        val path = dir.resolve(s"test.result-v1-$fingerprint.htcc.gz")
        for ((header, trailing) <- List(("WRONG", false), ("HTC-CALC", true))) {
          scala.util.Using.resource(
            new DataOutputStream(
              new java.util.zip.GZIPOutputStream(Files.newOutputStream(path))
            )
          ) {
            out =>
              out.writeUTF(header)
              out.writeInt(1)
              out.writeUTF("test.result")
              out.writeUTF(fingerprint)
              codec.write("invalid", out)
              if (trailing) out.writeByte(1)
          }

          CalculationCache.getOrCompute(
            config,
            "test.result",
            1,
            fingerprint,
            codec
          )("rebuilt") shouldBe "rebuilt"
          CalculationCache.getOrCompute(
            config,
            "test.result",
            1,
            fingerprint,
            codec
          )(fail("the rebuilt entry must be reused")) shouldBe "rebuilt"
        }
    }
  }

  private def withDirectory(test: java.nio.file.Path => Unit): Unit = {
    val dir = Files.createTempDirectory("htc-cache-contract")
    try test(dir)
    finally {
      scala.util.Using.resource(Files.list(dir)) {
        entries =>
          entries.forEach(
            path => Files.delete(path)
          )
      }
      Files.delete(dir)
    }
  }

}
