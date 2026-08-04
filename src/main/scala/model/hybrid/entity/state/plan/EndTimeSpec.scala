package org.interscity.htc
package model.hybrid.entity.state.plan

import core.types.Tick

import com.fasterxml.jackson.annotation.{ JsonSubTypes, JsonTypeInfo }

/** How an [[Activity]] decides when it ends.
  *
  * Deliberately minimal: carries no lateness/tolerance policy itself. Turning a `spec` plus the
  * tick a person actually arrived at the activity into the tick they actually depart on is
  * [[LatenessPolicy]]'s job, kept separate so this type stays a pure description of intent.
  */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[AtTick], name = "AtTick"),
    new JsonSubTypes.Type(value = classOf[Duration], name = "Duration")
  )
)
sealed trait EndTimeSpec

/** Ends at an absolute simulation tick. */
final case class AtTick(tick: Tick) extends EndTimeSpec

/** Ends after a fixed number of ticks have elapsed since the activity started. */
final case class Duration(ticks: Tick) extends EndTimeSpec
