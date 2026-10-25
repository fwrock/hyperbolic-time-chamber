package org.interscity.htc
package core.runtime.accounting

import core.entity.event.ActorInteractionEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeIdentitySpec extends AnyFlatSpec with Matchers {
  "accounted message identity" should "separate models, runs and incarnations sharing numeric counters" in {
    val original = ActorInteractionEvent(10L, 0L, 1L, "example.A", "", "example.A", data = "value",
      resourceId = "test", seq = 1L, sourceEpoch = "run", sourceIncarnation = "instance")
    original.messageId should not be original.copy(actorClassType = "example.B").messageId
    original.messageId should not be original.copy(sourceEpoch = "another-run").messageId
    original.messageId should not be original.copy(sourceIncarnation = "restarted").messageId
    original.messageId shouldBe original.copy(isAntiMessage = true).messageId
  }
}
