package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, DistributedUtil, JsonUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.htc.protobuf.core.entity.actor.{ Dependency, Identify }
import org.htc.protobuf.core.entity.event.control.execution.RegisterActorEvent
import org.htc.protobuf.core.entity.event.control.load.{ FinishCreationEvent, InitializeEntityAckEvent, StartCreationEvent }
import org.interscity.htc.core.entity.actor.{ ActorSimulation, Initialization }
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, InitializeEvent }
import org.interscity.htc.core.entity.event.data.InitializeData
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
  private var selfProxy: ActorRef = null

  override def handleEvent: Receive = {
    case event: CreateActorsEvent  => handleCreateActors(event)
    case event: StartCreationEvent => handleStartCreation(event)
    case event: ShardRegion.StartEntityAck =>
      handleInitialize(event)
    case event: InitializeEntityAckEvent => handleFinishInitialization(event)
  }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit =
    if (initializeData.isEmpty && actors.isEmpty) {
      logEvent("Finish creation")
      loadDataManager ! FinishCreationEvent(actorRef = getPath)
    }

  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit =
    initializeData.get(event.entityId) match
      case Some(data) =>
        logEvent(s"Initialize ${event.entityId}")
        getShardRef(data.classType) ! EntityEnvelopeEvent(
          entityId = event.entityId,
          event = InitializeEvent(
            id = data.id,
            actorRef = getSelfProxy,
            data = InitializeData(
              data = data.data,
              timeManager = data.timeManager,
              creatorManager = data.creatorManager,
              dependencies = data.dependencies.map {
                case (label, dep) => dep.id -> dep
              }
            )
          )
        )
        initializeData.remove(event.entityId)
      case None =>
        logEvent(s"Data not found ${event.entityId}")

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    logEvent("Start creation")
    actors.distinctBy(_.id).foreach {
      actor =>

        initializeData(actor.id) = Initialization(
          id = actor.id,
          classType = actor.typeActor,
          data = actor.data.content,
          timeManager = timeManager,
          creatorManager = getSelfProxy,
          dependencies = mutable.Map[String, Dependency]() ++= actor.dependencies
        )

        val shardRegion = createShardRegion(
          system = context.system,
          actorClassName = actor.typeActor,
          entityId = actor.id,
          timeManager = timeManager,
          creatorManager = getSelfProxy
        )

        shardRegion ! ShardRegion.StartEntity(actor.id)

        timeManager ! RegisterActorEvent(
          startTick = convertValue[DefaultState](actor.data.content).getStartTick,
          actorRef = shardRegion.path.toString,
          identify = Some(
            Identify(
              actor.id,
              actor.typeActor,
              shardRegion.path.toString
            )
          )
        )
    }
    actors.clear()
  }

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy("creator-load-data", s"-${System.nanoTime()}")
      selfProxy
    } else {
      selfProxy
    }

  private def handleCreateActors(event: CreateActorsEvent): Unit =
    event.actors.foreach {
      actor => actors += actor
    }

  private def createSingletonProxy(name: String, suffix: String = ""): ActorRef =
    DistributedUtil.createSingletonProxy(context.system, name, suffix)
}

object CreatorLoadData {
  def props(
    loadDataManager: ActorRef,
    timeManager: ActorRef
  ): Props =
    Props(
      new CreatorLoadData(
        loadDataManager = loadDataManager,
        timeManager = timeManager
      )
    )
}
