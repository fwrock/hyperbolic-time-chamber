package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.actor.{ActorSimulation, Identify}
import core.entity.event.control.load.{CreateActorsEvent, FinishCreationEvent, StartCreationEvent}
import core.exception.{CyclicDependencyException, NotFoundDependencyReferenceException}
import core.util.{ActorCreatorUtil, JsonUtil}
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.{createActor, createPoolActor, createShardedActor, createSingletonActor}

import org.interscity.htc.core.entity.event.control.execution.RegisterActorEvent
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{LoadBalancedDistributed, PoolDistributed, Simple, SingletonDistributed}

import scala.collection.mutable

class CreatorLoadData(
  loadDataManager: ActorRef,
  timeManager: ActorRef,
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
        actor => actor.id -> actor
      )
      .toMap
    val dependencyGraph = mutable.Map[String, List[String]]().withDefaultValue(List.empty)

    actors.foreach {
      actor =>
        actor.dependencies.foreach {
          case (_, dep) =>
            dependencyGraph(dep) = dependencyGraph(dep) :+ actor.id
        }
    }

    val sortedActors = topologicalSort(
      actors
        .map(
          actor => actor.id
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
        }.to(mutable.Map)

        val actorRef = newActor(actor = actor, dependencies = dependencies)

        timeManager ! RegisterActorEvent(
          startTick = JsonUtil.convertValue[DefaultState](actor.data.content).getStartTick,
          actorRef = actorRef,
          identify = Identify(
            actor.id,
            actorRef
          )
        )

        actorRefs(actor.id) = actorRef
    }

    actorRefs.toMap

    loadDataManager ! FinishCreationEvent(actorRef = self)
  }

  private def newActor(
    actor: ActorSimulation,
    dependencies: mutable.Map[String, ActorRef]
  ): ActorRef =
    actor.creationType match {
      case Simple =>
        logEvent(s"TimeManager pool created: $getTimeManager")
        createLoadBalanceDistributedActor(actor, dependencies)
      case SingletonDistributed =>
        createSingletonDistributedActor(actor, dependencies)
      case LoadBalancedDistributed =>
        createLoadBalanceDistributedActor(actor, dependencies)
      case PoolDistributed =>
        createPoolDistributedActor(actor, dependencies)
    }

  private def createSimpleActor(
    actor: ActorSimulation,
    dependencies: mutable.Map[String, ActorRef]
  ): ActorRef =
    createActor(
      system = context.system,
      actorClassName = actor.typeActor,
      actor.id,
      getTimeManager,
      actor.data.content.asInstanceOf[AnyRef],
      dependencies
    )

  private def createSingletonDistributedActor(
    actor: ActorSimulation,
    dependencies: mutable.Map[String, ActorRef]
  ): ActorRef =
    createSingletonActor(
      system = context.system,
      actorClassName = actor.typeActor,
      entityId = actor.id,
      getTimeManager,
      actor.data.content.asInstanceOf[AnyRef],
      dependencies
    )

  private def createLoadBalanceDistributedActor(
    actor: ActorSimulation,
    dependencies: mutable.Map[String, ActorRef]
  ): ActorRef =
    createShardedActor(
      system = context.system,
      actorClassName = actor.typeActor,
      entityId = actor.id,
      timeManager = getTimeManager,
      data = actor.data.content,
      dependencies = dependencies
    )

  private def createPoolDistributedActor(
    actor: ActorSimulation,
    dependencies: mutable.Map[String, ActorRef]
  ): ActorRef =
    createPoolActor(
      system = context.system,
      actorClassName = actor.typeActor,
      entityId = actor.id,
      poolConfiguration = actor.poolConfiguration,
      getTimeManager,
      actor.data.content.asInstanceOf[AnyRef],
      dependencies
    )

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
}
