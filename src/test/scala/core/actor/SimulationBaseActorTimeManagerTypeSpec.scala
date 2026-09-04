package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.state.BaseState
import core.enumeration.CreationTypeEnum
import core.enumeration.TimeManagerTypeEnum
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorSystem, ActorRef }
import org.apache.pekko.testkit.{ TestActorRef, TestProbe }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.compiletime.uninitialized

private case class TypeProbeState(startTick: Tick = 0L) extends BaseState(startTick = startTick)

/** `docs/TIME_WARP_DESIGN.md`'s step-4 log flagged `Properties.defaultTimeManagerType` as dead —
  * nothing ever set it, so `SimulationBaseActor` always resolved `currentTimeManagerType` to
  * `DISCRETE_EVENT` regardless of what a scenario actually wanted. This covers the fix: the
  * `PoolDistributed` construction path (`properties.data != null`, never routed through
  * `onInitialize`) must derive `currentTimeManagerType` from `timeManagers`' own key, not the
  * stale default.
  */
class SimulationBaseActorTimeManagerTypeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "SimulationBaseActorTimeManagerTypeSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TypeProbeActor(properties: Properties) extends SimulationBaseActor[TypeProbeState](properties) {
    def testCurrentTimeManagerType: String = getCurrentTimeManagerType
  }

  "a PoolDistributed actor constructed with properties.data set" should "derive currentTimeManagerType from timeManagers' own key, not the unset defaultTimeManagerType" in {
    val timeManagerProbe = TestProbe()
    val properties = Properties(
      entityId = "probe-1",
      resourceId = "res-1",
      timeManagers = mutable.Map(TimeManagerTypeEnum.TIME_WARP -> timeManagerProbe.ref),
      data = TypeProbeState(startTick = 0L),
      actorType = CreationTypeEnum.PoolDistributed
    )

    val actor = TestActorRef(new TypeProbeActor(properties))

    actor.underlyingActor.testCurrentTimeManagerType shouldBe TimeManagerTypeEnum.TIME_WARP
  }

  it should "still default to DISCRETE_EVENT when timeManagers is empty" in {
    val properties = Properties(
      entityId = "probe-2",
      resourceId = "res-1",
      timeManagers = mutable.Map.empty[String, ActorRef],
      data = TypeProbeState(startTick = 0L),
      actorType = CreationTypeEnum.PoolDistributed
    )

    val actor = TestActorRef(new TypeProbeActor(properties))

    actor.underlyingActor.testCurrentTimeManagerType shouldBe TimeManagerTypeEnum.DISCRETE_EVENT
  }
}
