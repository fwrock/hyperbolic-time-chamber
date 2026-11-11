package org.interscity.htc
package core.api

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files

class DispatcherRoutesSpec extends AnyFlatSpec with Matchers {
  "dispatcher configuration" should "read the resolved startup profile without exposing unrelated settings" in {
    val file = Files.createTempFile("htc-startup-profile", ".conf")
    try {
      Files.writeString(file, """include classpath("application.conf")
        pekko.actor.default-dispatcher.fork-join-executor.parallelism-max = 32
        pekko.actor.default-dispatcher.throughput = 75
        htc.time-manager.total-instances = 4
        private.password = "secret"
      """)
      val config = ConfigFactory.parseFile(file.toFile).withFallback(ConfigFactory.load()).resolve()
      val values = DispatcherRoutes.values(config)
      values("pekko.actor.default-dispatcher.fork-join-executor.parallelism-max") shouldBe "32"
      values("pekko.actor.default-dispatcher.throughput") shouldBe "75"
      values("pekko.actor.io-dispatcher.executor") shouldBe "\"virtual-thread-executor\""
      values.keys.forall(_.contains("dispatcher")) shouldBe true
      values.contains("private.password") shouldBe false
      config.getInt("htc.time-manager.total-instances") shouldBe 4
    } finally Files.deleteIfExists(file)
  }
}
