package org.interscity.htc
package core.util

import core.entity.state.State

import org.htc.protobuf.core.entity.actor
import org.htc.protobuf.core.entity.actor.Relationship
import org.interscity.htc.core.entity.actor.Property

object StateUtil {

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

  def getPropertyValue[T](state: State, propertyName: String): Option[T] =
    state.properties.get(propertyName).flatMap {
      property =>
        property.value match {
          case value: T => Some(value)
          case _        => None
        }
    }

  def getRelationshipValue(state: State, relationshipName: String): Option[String] =
    state.relationships.get(relationshipName).map(_.id)

  def getProperty(state: State, propertyName: String): Option[Property] =
    state.properties.get(propertyName)

  def getRelationship(state: State, relationshipName: String): Option[Relationship] =
    state.relationships.get(relationshipName)

  def getPropertyValue[T](state: State, propertyName: String, defaultValue: T): T =
    getPropertyValue(state, propertyName).getOrElse(defaultValue)

  def getRelationshipValue(state: State, relationshipName: String, defaultValue: String): String =
    getRelationshipValue(state, relationshipName).getOrElse(defaultValue)

}
