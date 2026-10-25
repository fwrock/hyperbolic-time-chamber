package org.interscity.htc
package core.actor

import core.entity.actor.properties.Properties
import core.entity.state.BaseState
import core.enumeration.CreationTypeEnum
import core.types.Tick

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.{ ActorSystem, PoisonPill, Props }
import org.apache.pekko.cluster.sharding.ShardRegion
import org.apache.pekko.testkit.TestProbe
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration._

private case class DestructTestState(startTick: Tick = 0L) extends BaseState(startTick = startTick)

/** Regression coverage for the ghost-resurrection bug: `BaseActor.destruct()` (triggered by a
  * `DestructEvent` from a time manager, e.g. `LocalTimeManagerBase.completeDestruct`) used to call
  * a bare `context.stop(self)`, which never informs Pekko cluster sharding. For a
  * `LoadBalancedDistributed` entity, that left the shard region unaware the entity died, so the
  * next message addressed to the same entityId (a stale reply, a late anti-message under Time
  * Warp) caused the shard region to silently spin up a brand-new "ghost" instance with
  * uninitialized state -- confirmed live via
  * `htc_events_when_state_is_null_total{actor_type="Car",event_type="spontaneous"}`.
  *
  * `destruct()` must now route through `selfDestruct()`, whose existing passivation logic already
  * distinguishes `LoadBalancedDistributed` (must passivate through the parent shard region) from
  * other actor types (plain stop is fine -- no shard region depends on the entity id).
  *
  * Actors here are created via [[TestProbe.childActorOf]] rather than `TestActorRef`: `TestActorRef`
  * defaults to Pekko's `CallingThreadDispatcher`, which only pumps an actor's mailbox synchronously
  * inside a `!` call made by the same thread. `SimulationBaseActor` is a `PersistentActor`, and
  * recovery is inherently asynchronous (journal I/O completes on a real dispatcher thread) -- when
  * recovery finishes there and Pekko Persistence tries to un-stash the `DestructEvent` this spec sent
  * earlier, `CallingThreadDispatcher` can leave it queued forever with nothing left to "pump" it
  * (confirmed directly: recovery completed and `RecoveryCompleted` was observed, but the stashed
  * command was never delivered even after 90s). `childActorOf` runs the actor on the system's real
  * dispatcher like production code does, which drains the stash the moment recovery completes.
  */
class BaseActorDestructPassivationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var _system: ActorSystem = uninitialized
  private implicit def system: ActorSystem = _system

  override def beforeAll(): Unit =
    _system = ActorSystem(
      "BaseActorDestructPassivationSpec",
      ConfigFactory
        .parseString("pekko.actor.provider = local\npekko.actor.fail-mixed-versions = off")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = {
    _system.terminate()
    ()
  }

  private class TestDestructActor(properties: Properties) extends SimulationBaseActor[DestructTestState](properties) {
    override protected def actSpontaneous(event: core.entity.event.SpontaneousEvent): Unit =
      onFinishSpontaneous(None)

    override def actInteractWith(event: core.entity.event.ActorInteractionEvent): Unit = ()
  }

  "BaseActor.destruct" should "passivate through the parent, not bare-stop, for a LoadBalancedDistributed entity" in {
    val parentProbe = TestProbe("shard-region-parent")
    val props = Props(
      new TestDestructActor(
        Properties(entityId = "entity-1", actorType = CreationTypeEnum.LoadBalancedDistributed)
      )
    )
    val actorRef = parentProbe.childActorOf(props, "sharded-entity")
    val watcher = TestProbe("watcher")
    watcher.watch(actorRef)

    actorRef ! DestructEvent(actorRef = "")

    // Mirrors real cluster sharding protocol: the entity's parent here is a stand-in for the real
    // Shard, which -- upon receiving Passivate(stopMessage) -- sends stopMessage back to the entity
    // itself to actually stop it. A plain TestProbe doesn't do that on its own, so without this the
    // entity would sit here forever having correctly asked to passivate but never being told to stop.
    parentProbe.expectMsg(5.seconds, ShardRegion.Passivate(PoisonPill))
    actorRef ! PoisonPill
    watcher.expectTerminated(actorRef, 5.seconds)
  }

  it should "stop directly, without involving the parent, for a PoolDistributed entity" in {
    val parentProbe = TestProbe("plain-parent")
    val props = Props(
      new TestDestructActor(
        Properties(entityId = "entity-2", actorType = CreationTypeEnum.PoolDistributed)
      )
    )
    val actorRef = parentProbe.childActorOf(props, "pooled-entity")
    val watcher = TestProbe("watcher-2")
    watcher.watch(actorRef)

    actorRef ! DestructEvent(actorRef = "")

    watcher.expectTerminated(actorRef, 5.seconds)
    parentProbe.expectNoMessage()
  }
}
