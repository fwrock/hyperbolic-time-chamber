package org.interscity.htc
package core.util

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeModeSpec extends AnyFlatSpec with Matchers {
  "RuntimeMode" should "default to cluster with the shipped application.conf" in {
    RuntimeMode.from(ConfigFactory.load()) shouldBe RuntimeMode.Cluster
  }

  it should "read single-node and tolerate case and whitespace" in {
    RuntimeMode.from(ConfigFactory.parseString("htc.runtime.mode = \" Single-Node \"")) shouldBe RuntimeMode.SingleNode
  }

  it should "default to cluster when the key is absent" in {
    RuntimeMode.from(ConfigFactory.empty()) shouldBe RuntimeMode.Cluster
  }

  it should "reject unknown values" in {
    an[IllegalArgumentException] should be thrownBy
      RuntimeMode.from(ConfigFactory.parseString("htc.runtime.mode = local"))
  }
}
