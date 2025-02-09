package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.actor.ActorSimulation
import core.entity.event.control.load.{ CreateActorsEvent, StartCreationEvent }
import core.exception.{ CyclicDependencyException, NotFoundDependencyReferenceException }
import core.util.ActorCreatorUtil
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.{ createActor, createShardedActor }

import java.util.UUID
import scala.collection.mutable

class CreatorLoadData(
  loadDataManager: ActorRef,
  timeManager: ActorRef
) extends BaseActor[DefaultState](
      timeManager = timeManager,
      actorId = "creator-load-data",
      data = null,
      dependencies = mutable.Map.empty
    ) {

  private val actors: mutable.ListBuffer[ActorSimulation] = mutable.ListBuffer()

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logEvent("Start creation")
    val actorsMap = actors
      .map(
        actor => retrieveActorIdentifier(actor) -> actor
      )
      .toMap
    val dependencyGraph = mutable.Map[String, List[String]]().withDefaultValue(List.empty)

    actors.foreach {
      actor =>
        actor.dependencies.foreach {
          case (dep, _) =>
            dependencyGraph(dep) = dependencyGraph(dep) :+ actor.name
        }
    }

    val sortedActors = topologicalSort(
      actors
        .map(
          actor => retrieveActorIdentifier(actor)
        )
        .toList,
      dependencyGraph
    )

    val actorRefs = mutable.Map[String, ActorRef]()

    sortedActors.foreach {
      name =>
        val actor: ActorSimulation = actorsMap(name)

        val dependencies = actor.dependencies.map {
          case (label, refName) =>
            label -> actorRefs.getOrElse(
              refName,
              throw new NotFoundDependencyReferenceException(s"The reference $refName not found")
            )
        }

        val actorRef = createShardedActor(
          system = context.system,
          actorClassName = actor.typeActor,
          entityId = actor.id,
          getTimeManager,
          actor.data.content,
          dependencies
        )

        actorRefs(actor.name) = actorRef
    }

    actorRefs.toMap
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit =
    event.actors.foreach {
      actor => actors += actor
    }

  private def topologicalSort(
    nodes: List[String],
    dependencyGraph: mutable.Map[String, List[String]]
  ): List[String] = {
    val visited = mutable.Set[String]()
    val inPath = mutable.Set[String]()
    val sorted = mutable.ListBuffer[String]()

    def visit(node: String): Boolean = {
      if (inPath.contains(node)) {
        throw new CyclicDependencyException(s"Cycle detected with actor $node")
      }
      if (!visited.contains(node)) {
        visited.add(node)
        inPath.add(node)

        dependencyGraph(node).foreach {
          dep =>
            if (!visit(dep)) {
              return false
            }
        }

        inPath.remove(node)
        sorted.prepend(node)
      }
      true
    }

    nodes.foreach {
      node =>
        if (!visit(node))
          throw new CyclicDependencyException(
            s"Cycle detected in the dependency graph for actor $node"
          )
    }

    sorted.toList
  }

  private def retrieveActorIdentifier(actor: ActorSimulation): String =
    if (actor.id != null) actor.id else actor.name
}
