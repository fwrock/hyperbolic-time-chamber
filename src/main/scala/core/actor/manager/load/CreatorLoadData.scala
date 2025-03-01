package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.ActorRef
import core.entity.actor.{ ActorSimulation, Dependency, Identify, Initialization }
import core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, InitializeEntityAckEvent, InitializeEvent, RequestInitializeEvent, StartCreationEvent }
import core.util.{ ActorCreatorUtil, JsonUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.{ createActor, createPoolActor, createShardRegion, createShardedActor, createSingletonActor }

import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.execution.RegisterActorEvent
import org.interscity.htc.core.util.JsonUtil.convertValue

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
  private val initializeData = mutable.Map[String, Initialization]()

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
    case event: ShardRegion.StartEntityAck =>
      handleInitialize(event)
    case event: InitializeEntityAckEvent => handleFinishInitialization(event)
  }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    if (initializeData.isEmpty && actors.isEmpty) {
      logEvent("Finish creation")
      loadDataManager ! FinishCreationEvent(actorRef = self)
    }
  }

  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit = {
    initializeData.get(event.entityId) match
      case Some(data) =>
        getShardRef(data.classType) ! EntityEnvelopeEvent(
          entityId = event.entityId,
          event = InitializeEvent(
            id = data.id,
            actorRef = self,
            data = data.toInitializeData
          )
        )
        initializeData.remove(event.entityId)
      case None =>
        logEvent(s"Data not found ${event.entityId}")
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logEvent("Start creation")
    actors.distinctBy(_.id).foreach {
      actor =>

        initializeData(actor.id) = Initialization(
          id = actor.id,
          classType = actor.typeActor,
          data = actor.data.content,
          dependencies = mutable.Map[String, Dependency]() ++= actor.dependencies
        )

        val shardRegion = createShardRegion(
          system = context.system,
          actorClassName = actor.typeActor,
          entityId = actor.id,
          timeManager = timeManager,
          creatorManager = self
        )

        shardRegion ! ShardRegion.StartEntity(actor.id)

        timeManager ! RegisterActorEvent(
          startTick = convertValue[DefaultState](actor.data.content).getStartTick,
          actorRef = shardRegion,
          identify = Identify(
            actor.id,
            actor.typeActor,
            shardRegion
          )
        )
    }
    actors.clear()
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit =
    event.actors.foreach {
      actor => actors += actor
    }
}
