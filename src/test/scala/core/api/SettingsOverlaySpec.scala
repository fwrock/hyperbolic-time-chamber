package org.interscity.htc
package core.api

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SettingsOverlaySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private val R = SimulatorSettingsRegistry
  override def afterEach(): Unit = R.clearAll()

  "overlay" should "apply an override to any htc.* key, typed" in {
    val base = ConfigFactory.load()
    R.set("htc.timewarp-trace.enabled", "true")
    R.set("htc.warmup.max-workers-per-node", "7")
    R.set("htc.routing.ch.rebuild-scheduled-ticks", """[1,2]""")
    val c = R.overlay(base)
    c.getBoolean("htc.timewarp-trace.enabled") shouldBe true
    c.getInt("htc.warmup.max-workers-per-node") shouldBe 7
    c.getIntList("htc.routing.ch.rebuild-scheduled-ticks").toArray.toList shouldBe List(1, 2)
    R.clearAll()
    R.overlay(base) should be theSameInstanceAs base
  }

  "allKeys" should "cover every leaf under htc" in {
    R.allKeys should contain("htc.report-manager.parquet.compression")
    R.allKeys.count(_.startsWith("htc.")) should be > 100
  }

  "validate" should "reject wrong types, non-htc keys and values outside the option set" in {
    R.validate("htc.warmup.max-workers-per-node", "abc") should not be empty
    R.validate("htc.warmup.max-workers-per-node", "12") shouldBe empty
    R.validate("htc.node.message-origin", "maybe") should not be empty
    R.validate("pekko.loglevel", "DEBUG") should not be empty
    R.validate("htc.report-manager.parquet.compression", "lzo") should not be empty
    R.validate("htc.report-manager.parquet.compression", "zstd") shouldBe empty
    R.validate("htc.report-manager.enabled-strategies", "csv,bogus") should not be empty
  }

  "body parsing" should "accept flat, nested and array values" in {
    SettingsRoutes.parseBody("""{"htc":{"warmup":{"max-workers-per-node":3}},"htc.routing.ch.rebuild-scheduled-ticks":[3]}""") shouldBe
      Map("htc.warmup.max-workers-per-node" -> "3", "htc.routing.ch.rebuild-scheduled-ticks" -> "[3]")
  }
}
