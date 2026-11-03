package org.interscity.htc
package core.partial

import com.fasterxml.jackson.databind.ObjectMapper

import java.io.File
import scala.jdk.CollectionConverters.*

/** Reads the selected units of a SimEDaPE selection manifest (`htc.simedape.selection.v1`). Only
  * what the filter needs; the rest of the manifest is for humans and for SimEDaPE.
  */
object PartialManifest {
  val Schema = "htc.simedape.selection.v1"

  /** @param units selected unit ids (engine actor ids)
    * @param nonNumeric unit ids that are not a number and therefore cannot match any actor
    */
  final case class Selection(units: Set[Long], nonNumeric: List[String], scenarioId: Option[String])

  def read(path: String): Selection = {
    val root = new ObjectMapper().readTree(new File(path))
    val schema = Option(root.get("schema")).map(_.asText()).getOrElse("")
    require(schema == Schema, s"$path: schema must be $Schema, found '$schema'")
    val raw = Option(root.get("selected")).map(_.elements().asScala.toList).getOrElse(Nil)
    val units = raw.map(e => Option(e.get("unit")).map(_.asText()).getOrElse(""))
    val (numeric, other) = units.partition(_.toLongOption.isDefined)
    Selection(numeric.map(_.toLong).toSet, other, Option(root.get("scenario_id")).map(_.asText()))
  }
}
