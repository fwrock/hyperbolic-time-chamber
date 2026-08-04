package org.interscity.htc
package model.hybrid.entity.state.plan

import model.hybrid.entity.state.DriverAttributes
import org.htc.protobuf.core.entity.actor.Identify

import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }

/** One step of a person's daily plan.
  *
  * `PlanElement` is the umbrella type stored in [[RemainingQueue]]. It is deliberately split into
  * [[ExecutedElement]] (anything ready to run) and [[PendingDecision]] (a choice not yet resolved)
  * so that the type system itself — not a runtime check — prevents an unresolved decision from
  * ever being treated as executable. See [[ExecutedElement]] for why that split exists.
  *
  * Note: Scala 3 requires every direct subtype of a sealed trait to live in the same source file
  * as the trait itself, which is why the whole `PlanElement`/`ExecutedElement`/`AtomicLeg`
  * hierarchy — including their concrete leaves — is declared in this one file rather than split
  * one-type-per-file as elsewhere in this package.
  *
  * The `@JsonTypeInfo`/`@JsonSubTypes` pair is what lets the native scenario JSON format
  * (`persons_*.json`) discriminate between the five leaf shapes below via a `"kind"` property —
  * required because `PersonState.originalPlan`/`cursor` are deserialized generically by
  * `core.util.JsonUtil.convertValue`/`core.actor.BaseActor.onInitialize`, which has no per-actor
  * custom decoder to hang this on.
  */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[Activity], name = "Activity"),
    new JsonSubTypes.Type(value = classOf[WalkLeg], name = "WalkLeg"),
    new JsonSubTypes.Type(value = classOf[PrivateVehicleLeg], name = "PrivateVehicleLeg"),
    new JsonSubTypes.Type(value = classOf[TransitLeg], name = "TransitLeg"),
    new JsonSubTypes.Type(value = classOf[PendingDecision], name = "PendingDecision")
  )
)
sealed trait PlanElement

/** Anything that is already executable — never includes [[PendingDecision]].
  *
  * This exclusion is the central guarantee of the plan-element design: it makes it a compile
  * error to place a pending, unresolved mode-choice decision into a `List[ExecutedElement]`
  * (e.g. `PlanCursor.executed`), rather than relying on a runtime check that could be forgotten.
  */
sealed trait ExecutedElement extends PlanElement

/** A stay at a location for a purpose (home, work, shopping, ...).
  *
  * @param activityType free-form activity purpose label
  * @param nodeId       node id where the activity takes place
  * @param endTime      when the activity ends (see [[EndTimeSpec]])
  */
final case class Activity(
  activityType: String,
  nodeId: String,
  endTime: EndTimeSpec
) extends ExecutedElement

/** A single, already-resolved leg of travel using one concrete mode.
  *
  * `AtomicLeg`s are the unit [[RemainingQueue.dropCurrentLegRun]] operates on: a contiguous run
  * of them (with no intervening [[Activity]]) represents one trip's travel, possibly split across
  * an access walk / transit ride / egress walk.
  */
sealed trait AtomicLeg extends ExecutedElement {
  def mode: ConcreteMode
}

/** A walking leg. `precomputedRoute`, when present, is a compact list of (linkId, nodeId) pairs
  * so the walk doesn't need to re-run routing at execution time.
  */
final case class WalkLeg(
  originNodeId: String,
  destinationNodeId: String,
  precomputedRoute: Option[List[(String, String)]] = None
) extends AtomicLeg {
  val mode: ConcreteMode = ConcreteMode.Walk
}

/** A leg driven/ridden in a privately owned vehicle (car, bicycle, motorcycle).
  *
  * `vehicle` identifies the vehicle actor by id/shard/class only — never an `ActorRef` — so this
  * type stays safe to persist as simulation state.
  */
final case class PrivateVehicleLeg(
  mode: ConcreteMode,
  vehicle: Identify,
  driverAttributes: DriverAttributes = DriverAttributes()
) extends AtomicLeg

/** A leg ridden aboard a scheduled transit service (bus, subway). */
final case class TransitLeg(
  mode: ConcreteMode,
  line: String,
  boardingStop: StopRef,
  alightingStop: StopRef
) extends AtomicLeg

/** A mode-choice decision that has not yet been resolved into concrete [[AtomicLeg]]s.
  *
  * Deliberately does NOT extend [[ExecutedElement]] — see that trait's doc for why this
  * exclusion is load-bearing rather than incidental.
  */
final case class PendingDecision(decision: ModeDecisionRequest) extends PlanElement
