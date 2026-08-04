package org.interscity.htc
package model.hybrid.support.subwaystation

import core.entity.event.ActorInteractionEvent
import model.hybrid.entity.state.SubwayStationState
import model.hybrid.entity.state.enumeration.SubwayStationStateEnum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/** Regression test for a positional-argument bug found via `htc-scenario-qa`'s hybrid smoke-test
  * scenario (2026-07-23): `handleRegisterPassenger` used to build the queued passenger's
  * `Identify` with `Identify(event.actorRefId, event.actorClassType, event.actorPathRef)` —
  * three positional args against the real protobuf `Identify(id, resourceId, classType,
  * actorRef, actorType, ...)` signature. That silently routed `actorClassType` into `resourceId`
  * and `actorPathRef` into `classType`, so `SubwayPassengerHandler`'s later
  * `sendMessageFn(person.id, person.classType, ...)` targeted a nonexistent Pekko Cluster
  * Sharding shard type and threw `IllegalStateException`, permanently stalling every person who
  * boarded a Subway (see `handleLoadPeople`/`requestUnloadPeopleData` in
  * `model.hybrid.support.subway.SubwayPassengerHandler`, both of which key off `person.classType`
  * for every passenger in `state.passengers`). Locks in `event.toIdentity` (already used
  * correctly by `BusStop.handleRegisterPassenger`) as the fix.
  */
class SubwayPassengerManagerSpec extends AnyFlatSpec with Matchers {

  private def newState(): SubwayStationState =
    SubwayStationState(
      startTick = 0L,
      name = "station-1",
      nodeId = "n1",
      terminal = false,
      garage = false,
      lines = mutable.Map.empty,
      subways = mutable.Map.empty,
      linesRoute = mutable.Map.empty,
      status = SubwayStationStateEnum.Start
    )

  private def registerEvent(personId: String): ActorInteractionEvent =
    ActorInteractionEvent(
      tick = 300L,
      lamportTick = 300L,
      actorRefId = personId,
      shardRefId = "hybrid.actor.Person",
      actorPathRef = personId,
      actorClassType = "hybrid.actor.Person",
      data = "unused",
      resourceId = "res-1"
    )

  "handleRegisterPassenger" should "queue a passenger whose Identify.classType is the actor's real class type, not its path/id" in {
    val state = newState()
    var reported: Option[(Map[String, Any], String)] = None
    val manager = new SubwayPassengerManager(
      getStateFn = () => state,
      entityIdFn = () => "station-1",
      currentTickFn = () => 300L,
      reportFn = (data, label) => reported = Some((data, label)),
      sendMessageFn = (_, _, _) => ()
    )

    manager.handleRegisterPassenger(registerEvent("htcaid:person;grid_5"), line = "mini_subway_line_0")

    val queued = state.people("mini_subway_line_0")
    queued should have size 1
    queued.head.id shouldBe "htcaid:person;grid_5"
    queued.head.classType shouldBe "hybrid.actor.Person"
  }

  it should "append to an existing queue for the same line without dropping earlier passengers" in {
    val state = newState()
    val manager = new SubwayPassengerManager(
      getStateFn = () => state,
      entityIdFn = () => "station-1",
      currentTickFn = () => 300L,
      reportFn = (_, _) => (),
      sendMessageFn = (_, _, _) => ()
    )

    manager.handleRegisterPassenger(registerEvent("htcaid:person;grid_1"), line = "mini_subway_line_0")
    manager.handleRegisterPassenger(registerEvent("htcaid:person;grid_2"), line = "mini_subway_line_0")

    val queued = state.people("mini_subway_line_0")
    queued.map(_.id) shouldBe Seq("htcaid:person;grid_1", "htcaid:person;grid_2")
    all(queued.map(_.classType)) shouldBe "hybrid.actor.Person"
  }

  it should "keep separate lines' queues independent" in {
    val state = newState()
    val manager = new SubwayPassengerManager(
      getStateFn = () => state,
      entityIdFn = () => "station-1",
      currentTickFn = () => 300L,
      reportFn = (_, _) => (),
      sendMessageFn = (_, _, _) => ()
    )

    manager.handleRegisterPassenger(registerEvent("htcaid:person;grid_1"), line = "line-a")
    manager.handleRegisterPassenger(registerEvent("htcaid:person;grid_2"), line = "line-b")

    state.people("line-a").map(_.id) shouldBe Seq("htcaid:person;grid_1")
    state.people("line-b").map(_.id) shouldBe Seq("htcaid:person;grid_2")
  }
}
