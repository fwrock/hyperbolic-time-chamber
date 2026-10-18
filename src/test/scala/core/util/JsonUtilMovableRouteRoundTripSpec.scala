package org.interscity.htc
package core.util

import model.hybrid.entity.state.CarState
import model.hybrid.entity.state.enumeration.ActorTypeEnum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JsonUtilMovableRouteRoundTripSpec extends AnyFlatSpec with Matchers {

  "JsonUtil" should "round-trip movableBestRoute/movableCurrentPath through CarState" in {
    val original = CarState(
      startTick = 0L,
      origin = 1L,
      destination = 2L,
      actorType = ActorTypeEnum.Car,
      size = 4.5
    )
    original.updateBestRoute(Some(scala.collection.mutable.Queue((1L, 2L), (3L, 4L))))
    original.updateCurrentPath(Some((1L, 2L)))
    original.updateCurrentNode(1L)

    val json = JsonUtil.toJson(original)
    println(s"SERIALIZED: $json")

    val restored = JsonUtil.fromJson[CarState](json)

    withClue("movableBestRoute lost across JSON round-trip: ") {
      restored.getBestRoute shouldBe Some(scala.collection.mutable.Queue((1L, 2L), (3L, 4L)))
    }
    withClue("movableCurrentPath lost across JSON round-trip: ") {
      restored.getCurrentPath shouldBe Some((1L, 2L))
    }
    withClue("movableCurrentNode lost across JSON round-trip: ") {
      restored.getCurrentNode shouldBe 1L
    }
  }
}
