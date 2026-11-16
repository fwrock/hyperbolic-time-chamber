package org.interscity.htc
package core.util

import core.entity.state.State

import org.htc.protobuf.core.entity.actor
import org.interscity.htc.core.entity.actor.{ Property, ShardActorId }

/** Adapts legacy property metadata and reads logical relationships without resolving actor refs. */
object StateUtil {

  /** Copies protobuf defaults into mutable runtime property values, with the last duplicate name
    * winning. Property schema and descriptive metadata are retained verbatim.
    */
  def convertProperties(propertiesList: Seq[actor.Property]): Map[String, Property] =
    propertiesList
      .map(
        property =>
          property.name -> Property(
            id = property.id,
            name = property.name,
            value = property.defaultValue,
            schema = property.schema,
            comment = property.comment,
            description = property.description,
            displayName = property.displayName,
            writeable = property.writeable
          )
      )
      .toMap

  /** Reads a value using the legacy erased generic cast.
    *
    * The caller must choose T from the property's schema; this method does not validate the runtime
    * value against T and can leave an incompatible value to fail when the caller uses it.
    */
  def getPropertyValue[T](state: State, propertyName: String): Option[T] =
    state.properties.get(propertyName).flatMap {
      property =>
        property.value match {
          case value: T => Some(value)
          case _        => None
        }
    }

  /** Returns the related entity's decimal identifier without class or shard routing metadata. */
  def getRelationshipValue(state: State, relationshipName: String): Option[String] =
    state.relationships.get(relationshipName).map(_.entityId.toString)

  /** Returns the stored property record, including its mutable value, without copying it. */
  def getProperty(state: State, propertyName: String): Option[Property] =
    state.properties.get(propertyName)

  /** Returns a logical routing assignment without looking up a physical actor reference. */
  def getRelationship(state: State, relationshipName: String): Option[ShardActorId] =
    state.relationships.get(relationshipName)

  /** Uses the fallback when lookup returns None, retaining the legacy generic-value contract. */
  def getPropertyValue[T](state: State, propertyName: String, defaultValue: T): T =
    getPropertyValue(state, propertyName).getOrElse(defaultValue)

  /** Uses the fallback only when the named relationship is absent. */
  def getRelationshipValue(state: State, relationshipName: String, defaultValue: String): String =
    getRelationshipValue(state, relationshipName).getOrElse(defaultValue)

}
