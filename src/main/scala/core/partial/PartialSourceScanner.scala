package org.interscity.htc
package core.partial

import core.actor.manager.load.ActorLoadFilter
import core.entity.actor.ActorSimulation
import core.entity.configuration.ActorDataSource
import core.enumeration.DataSourceTypeEnum
import core.modifications.ScenarioModifications
import core.util.{ JsonStreamingUtil, SqliteActorSimulationUtil }

import java.io.{ BufferedInputStream, FileInputStream }
import scala.collection.mutable
import scala.util.Using

/** Scans the actor universe the loaders will create, after modifications and class filtering.
  *
  * Sources are consumed in input order. Only filterable ids/classes and composition links survive
  * the scan; payloads and source handles are released before selection is calculated.
  */
private[partial] object PartialSourceScanner {

  /** Counts transformed records and indexes filterable composition links; source failures
    * propagate.
    */
  def scan(
    sources: List[ActorDataSource],
    isFilterable: String => Boolean
  ): PartialActorIndex = {
    val classes = mutable.HashMap.empty[Long, String]
    val links = mutable.ArrayBuffer.empty[(Long, Long)]
    var total = 0
    sources.foreach {
      source =>
        foreachActor(source) {
          actor =>
            total += 1
            if (isFilterable(actor.typeActor)) {
              classes(actor.id) = actor.typeActor
              links ++= PartialClosure.linksOf(actor)
            }
        }
    }
    PartialActorIndex(total, classes.toMap, links.toVector)
  }

  /** Keeps stream ownership scoped even if JSON parser construction fails before returning a
    * handle.
    */
  private def foreachActor(source: ActorDataSource)(consume: ActorSimulation => Unit): Unit = {
    val path = source.dataSource.info("path").asInstanceOf[String]
    source.dataSource.sourceType match {
      case DataSourceTypeEnum.json =>
        Using.resource(new BufferedInputStream(new FileInputStream(path))) {
          stream =>
            val (parser, actors) = JsonStreamingUtil.createParser(stream)
            Using.resource(parser)(
              _ => foreachTransformed(actors)(consume)
            )
        }
      case DataSourceTypeEnum.sqlite =>
        val (connection, actors) = SqliteActorSimulationUtil.openIterator(path)
        Using.resource(connection)(
          _ => foreachTransformed(actors)(consume)
        )
    }
  }

  /** Applies edits/removals before class filtering and dependency checks, matching loader order. */
  private def foreachTransformed(actors: Iterator[ActorSimulation])(
    consume: ActorSimulation => Unit
  ): Unit =
    actors
      .flatMap(ScenarioModifications.transform)
      .flatMap(
        actor => ActorLoadFilter.transform(actor)
      )
      .foreach(consume)
}
