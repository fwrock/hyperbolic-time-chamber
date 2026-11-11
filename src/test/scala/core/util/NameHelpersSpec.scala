package org.interscity.htc
package core.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NameHelpersSpec extends AnyFlatSpec with Matchers {
  private val prefix = "org.interscity.htc.model."

  // The original, uncached definitions.
  private def oldModelName(n: String) = s"$prefix${n.replace(prefix, "")}"
  private def oldBare(n: String) = n.replace(prefix, "")
  private def oldFormat(id: String) = if (id != null) id.replace(":", "_").replace(";", "_") else null

  private val names = Seq("hybrid.actor.Car", s"${prefix}hybrid.actor.Link", "", s"x$prefix${prefix}y", "plain", "a:b;c")

  "StringUtil" should "return the same values as the uncached versions, cold and warm" in {
    for (_ <- 1 to 2; n <- names) {
      StringUtil.getModelClassName(n) shouldBe oldModelName(n)
      StringUtil.getModelClassNameWithoutPackage(n) shouldBe oldBare(n)
    }
  }

  "IdUtil.format(String)" should "match the original, including null and ids that need escaping" in {
    for (n <- names :+ "nodes_0" :+ "12345") IdUtil.format(n) shouldBe oldFormat(n)
    IdUtil.format(null: String) shouldBe null
  }
}
