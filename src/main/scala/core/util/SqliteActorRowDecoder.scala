package org.interscity.htc
package core.util

import core.entity.actor.ActorSimulation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.sql.ResultSet

/** Reconstructs the actor JSON shape before binding a SQLite row through Jackson.
  *
  * Binding a complete ActorSimulation preserves the concrete containers used for Any-typed content
  * in JSON sources. Binding data_content separately or manually assembling the actor can produce
  * different runtime types even when the values appear equivalent.
  */
private[util] object SqliteActorRowDecoder {
  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

  /** Decodes the current row without advancing or closing its caller-owned cursor.
    *
    * SQL and JSON errors propagate. Scalar strings are escaped; content, roles and relationships
    * remain JSON fragments so Jackson applies the same nested-field mapping as a JSON actor source.
    */
  def decode(row: ResultSet): ActorSimulation = {
    val poolJson = poolConfigurationJson(row)
    val relationshipsJson = nullableJson(row.getString("relationships"))
    val actorJson =
      s"""{"id":${jsonString(row.getString("id"))},""" +
        s""""name":${jsonString(row.getString("name"))},""" +
        s""""typeActor":${jsonString(row.getString("type_actor"))},""" +
        s""""creationType":${jsonString(row.getString("creation_type"))},""" +
        s""""poolConfiguration":$poolJson,""" +
        s""""data":{"dataType":${jsonString(row.getString("data_type"))},"content":${row.getString(
            "data_content"
          )}},""" +
        s""""relationships":$relationshipsJson}"""

    mapper.readValue(actorJson, classOf[ActorSimulation])
  }

  /** Uses the total-instance column as the existing marker for an absent pool configuration. */
  private def poolConfigurationJson(row: ResultSet): String = {
    val roles = row.getString("pool_use_roles")
    if (row.getObject("pool_total_instances") == null) "null"
    else
      s"""{"roundRobinPool":${row.getInt("pool_round_robin_pool")},""" +
        s""""totalInstances":${row.getInt("pool_total_instances")},""" +
        s""""maxInstancesPerNode":${row.getInt("pool_max_instances_per_node")},""" +
        s""""allowLocalRoutes":${row.getInt("pool_allow_local_routes") != 0},""" +
        s""""useRoles":${nullableJson(roles)}}"""
  }

  /** Escapes scalar text through the same mapper that binds the reconstructed record. */
  private def jsonString(value: String): String = mapper.writeValueAsString(value)

  /** Preserves non-null JSON fragments verbatim and maps SQL NULL to JSON null. */
  private def nullableJson(value: String): String = if (value == null) "null" else value
}
