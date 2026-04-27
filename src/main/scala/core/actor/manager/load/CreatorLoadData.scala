package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Cancellable, Props }
import core.util.{ ActorCreatorUtil, DistributedUtil, IdUtil, StringUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartCreationEvent }
import org.interscity.htc.core.actor.manager.loadbalance.allocation.SpatialShardIdRegistry
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.control.loadbalance.SpatialEntityData
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, InitializeEvent, NeedsPostLoadRegistrationEvent, ProcessNextCreateChunk }
import org.interscity.htc.core.entity.event.control.loadbalance.{ BatchShardAssignmentResponse, RegisterSpatialEntitiesBatchEvent }
import org.interscity.htc.core.entity.event.data.InitializeData
import org.interscity.htc.core.util.ManagerConstantsUtil.LOAD_BALANCE_MANAGER_ACTOR_NAME

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class CreatorLoadData(
  private val creatorProperties: CreatorProperties
) extends BaseActor[DefaultState](
      properties = Properties(
        entityId = creatorProperties.entityId,
        resourceId = creatorProperties.shardId,
        data = creatorProperties.data
      )
    ) {

  private val timeManagers: mutable.Map[String, ActorRef] = creatorProperties.timeManagers
  private val creatorManager: ActorRef = creatorProperties.creatorManager
  private val reporters: mutable.Map[org.interscity.htc.core.enumeration.ReportTypeEnum, ActorRef] =
    creatorProperties.reporters

  private val initializeData = mutable.Map[String, mutable.Map[String, Initialization]]()
  private val initializedAcknowledges = mutable.Map[String, mutable.Seq[String]]()
  private val pendingInitAck = mutable.Map[String, Initialization]()
  private var amountActors = 0

  private val actorsToCreate: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty
  private val actorsBatches: mutable.Map[String, String] = mutable.Map.empty
  private val batchesLoad: mutable.Map[String, ActorRef] = mutable.Map.empty
  private val batchesToCreate: mutable.Map[String, Seq[ActorSimulationCreation]] = mutable.Map.empty

  private val CREATE_CHUNK_SIZE = 500
  private val DELAY_BETWEEN_CHUNKS = 100.milliseconds
  private val LB_CHUNK_TIMEOUT = 10.seconds

  /** Lazy singleton proxy to LoadBalanceManager (null if load balancing disabled). */
  private var loadBalanceProxy: ActorRef = _

  /** Pending chunks awaiting BatchShardAssignmentResponse from LoadBalanceManager. Key: batchId,
    * Value: the chunk of actors waiting for spatial assignment.
    */
  private val pendingChunks: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty

  /** Scheduled timeout cancellables for pending LBM chunks. Cancelled on response. */
  private val pendingChunkTimeouts: mutable.Map[String, Cancellable] = mutable.Map.empty

  /** Whether this instance has already attempted proxy initialisation (avoids repeat config reads).
    */
  private var lbProxyChecked: Boolean = false

  override def onStart(): Unit =
    super.onStart()

  override def postStop(): Unit =
    super.postStop()

  override def handleEvent: Receive = {
    case event: CreateActorsEvent =>
      logDebug(s"Received CreateActorsEvent with ${event.actors.size} actors, batchId=${event.id}")
      handleCreateActors(event)
    case event: StartCreationEvent             => handleStartCreation(event)
    case event: ProcessNextCreateChunk         => handleProcessNextCreateChunk(event.batchId)
    case event: BatchShardAssignmentResponse   => handleBatchAssignmentResponse(event)
    case event: CreatorLoadData.LbChunkTimeout => handleLbChunkTimeout(event)
    case event: ShardRegion.StartEntityAck     => handleInitialize(event)
    case event: InitializeEntityAckEvent       => handleFinishInitialization(event)
    case event: NeedsPostLoadRegistrationEvent => handleNeedsPostLoadRegistration(event)

    case _ =>
  }

  private def handleCreateActors(event: CreateActorsEvent): Unit = {
    batchesToCreate.put(event.id, event.actors)
    batchesLoad.put(event.id, event.actorRef)
    self ! StartCreationEvent(batchId = event.id)
  }

  private def handleStartCreation(event: StartCreationEvent): Unit = {
    actorsToCreate(event.batchId) = batchesToCreate
      .get(event.batchId)
      .map(_.distinctBy(_.actor.id))
      .getOrElse(Seq.empty)
      .toList

    amountActors += actorsToCreate(event.batchId).size

    if (actorsToCreate.nonEmpty) {
      self ! ProcessNextCreateChunk(batchId = event.batchId)
    } else {
      checkAndSendFinish(event.batchId)
    }
  }

  /** Process the next chunk of entities for a batch.
    *
    * Two-phase creation when load balancing is enabled: Phase 1: Extract spatial positions → send
    * batch to LoadBalanceManager → wait Phase 2: On BatchShardAssignmentResponse → store mappings →
    * create entities
    *
    * When load balancing is disabled, entities are created immediately with hash-based routing.
    */
  private def handleProcessNextCreateChunk(batchId: String): Unit = {

    val currentActors = actorsToCreate.getOrElse(batchId, List.empty)
    val chunk = currentActors.take(CREATE_CHUNK_SIZE)

    if (chunk.nonEmpty) {
      val lbProxy = getLoadBalanceProxy
      if (lbProxy.isDefined) {
        val spatialEntities = chunk.map {
          actorCreation =>
            val position = extractSpatialPosition(actorCreation).getOrElse((0.0, 0.0))
            SpatialEntityData(
              spatialEntityId = actorCreation.actor.id,
              lon = position._1,
              lat = position._2
            )
        }

        val withRealPos = spatialEntities.count {
          e => e.position != (0.0, 0.0)
        }

        pendingChunks.put(batchId, chunk)
        val timeout = context.system.scheduler.scheduleOnce(
          LB_CHUNK_TIMEOUT,
          self,
          CreatorLoadData.LbChunkTimeout(batchId)
        )
        pendingChunkTimeouts.put(batchId, timeout)
        lbProxy.get ! RegisterSpatialEntitiesBatchEvent(
          entities = spatialEntities,
          batchId = batchId
        )
        return
      }

      createEntitiesFromChunk(chunk, batchId)
      advanceToNextChunk(batchId, chunk.size)
    } else {
      checkAndSendFinish(batchId)
    }
  }

  /** Handles the batch shard assignment response from LoadBalanceManager.
    *
    * Phase 2 of two-phase creation: stores the spatial shard assignments in
    * [[SpatialShardIdRegistry]], then creates the entities using the assigned shard IDs.
    */
  private def handleBatchAssignmentResponse(response: BatchShardAssignmentResponse): Unit = {
    SpatialShardIdRegistry.putAllShardIds(response.assignments)

    SpatialShardIdRegistry.putAllPositions(response.positions)

    logDebug(
      s"Received spatial assignments for batch '${response.batchId}': " +
        s"${response.assignments.size} entities assigned"
    )

    pendingChunkTimeouts.remove(response.batchId).foreach(_.cancel())

    pendingChunks.remove(response.batchId) match {
      case Some(chunk) =>
        createEntitiesFromChunk(chunk, response.batchId)
        advanceToNextChunk(response.batchId, chunk.size)

      case None =>
        logWarn(
          s"Received BatchShardAssignmentResponse for batch '${response.batchId}' " +
            s"but no pending chunk found. Ignoring."
        )
    }
  }

  /** Creates entities from a chunk — the actual creation loop extracted from
    * handleProcessNextCreateChunk. This is shared by both the immediate path (no LB) and the
    * deferred path (after spatial assignment).
    */
  private def createEntitiesFromChunk(
    chunk: List[ActorSimulationCreation],
    batchId: String
  ): Unit =
    chunk.foreach {
      actorCreation =>
        val initialization = Initialization(
          id = actorCreation.actor.id,
          resourceId = actorCreation.resourceId,
          classType = actorCreation.actor.typeActor,
          data = actorCreation.actor.data.content,
          timeManagers = timeManagers,
          creatorManager = self,
          reporters = reporters,
          relationships = {
            val base =
              if (actorCreation.actor.relationships != null) actorCreation.actor.relationships
              else Map.empty[String, ShardActorId]
            mutable.Map[String, ShardActorId]() ++= base.map {
              case (label, rel) =>
                val bucket =
                  if (rel.shardBucket.nonEmpty) rel.shardBucket
                  else SpatialShardIdRegistry.getShardId(rel.entityId).getOrElse("")
                label -> rel.copy(shardBucket = bucket)
            }
          }
        )

        addInitializeData(actorCreation.actor.id, batchId, initialization)
        addToInitializedAcknowledges(batchId, actorCreation.actor.id)

        val fullClassName = StringUtil.getModelClassName(actorCreation.actor.typeActor)
        SpatialShardIdRegistry.putEntityClassName(actorCreation.actor.id, fullClassName)

        val shardRegion = createShardRegion(
          system = context.system,
          resourceId = actorCreation.resourceId,
          actorClassName = actorCreation.actor.typeActor,
          entityId = actorCreation.actor.id,
          timeManagers = timeManagers,
          creatorManager = self,
          reporters = reporters
        )

        shardRegion ! ShardRegion.StartEntity(actorCreation.actor.id)
    }

  /** Advances to the next chunk or finishes if no more actors remain. */
  private def advanceToNextChunk(batchId: String, chunkSize: Int): Unit = {
    actorsToCreate(batchId) = actorsToCreate(batchId).drop(chunkSize)

    if (actorsToCreate(batchId).nonEmpty) {
      context.system.scheduler.scheduleOnce(
        DELAY_BETWEEN_CHUNKS,
        self,
        ProcessNextCreateChunk(batchId = batchId)
      )
    }
  }

  /** Extracts spatial position from an entity's raw data content.
    *
    * Extraction strategy by entity type:
    *   - Nodes: Direct lat/long from content map (`latitude`, `longitude` keys)
    *   - Links: Look up `from` node position via [[SpatialShardIdRegistry]]
    *   - Cars/Buses/Vehicles: Look up `origin` or `currentNode` position via registry
    *   - Unknown: None (falls back to hash-based routing)
    *
    * @return
    *   Some((longitude, latitude)) if spatial data is available, None otherwise
    */
  private def extractSpatialPosition(
    actorCreation: ActorSimulationCreation
  ): Option[(Double, Double)] =
    try
      actorCreation.actor.data.content match {
        case contentMap: java.util.Map[?, ?] =>
          val map = contentMap.asInstanceOf[java.util.Map[String, Any]]

          if (map.containsKey("latitude") && map.containsKey("longitude")) {
            val lat = map.get("latitude") match {
              case n: Number => n.doubleValue()
              case _         => return None
            }
            val lon = map.get("longitude") match {
              case n: Number => n.doubleValue()
              case _         => return None
            }
            return Some((lon, lat))
          }

          if (map.containsKey("from")) {
            val fromNodeId = map.get("from").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(fromNodeId)
            if (pos.isDefined) return pos
          }

          if (map.containsKey("origin")) {
            val originNodeId = map.get("origin").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(originNodeId)
            if (pos.isDefined) return pos
          }

          if (map.containsKey("currentNode")) {
            val nodeId = map.get("currentNode").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(nodeId)
            if (pos.isDefined) return pos
          }

          if (map.containsKey("nodeId")) {
            val nodeId = map.get("nodeId").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(nodeId)
            if (pos.isDefined) return pos
          }

          None

        case _ => None
      }
    catch {
      case _: Exception => None
    }

  /** Lazily obtains a singleton proxy to LoadBalanceManager, if load balancing is enabled.
    *
    * Uses a JVM-level cache in the companion object so all [[CreatorLoadData]] instances on the
    * same node share one proxy actor, instead of each creating their own.
    *
    * Gate is config `htc.load-balance-manager.enabled` — valid on all cluster nodes, unlike
    * [[ShardAllocatorRegistry]] which is only populated on the singleton host node.
    */
  private def getLoadBalanceProxy: Option[ActorRef] = {
    if (lbProxyChecked) return Option(loadBalanceProxy)

    val cached = CreatorLoadData.globalLbProxy.get()
    if (cached != null) {
      loadBalanceProxy = cached
      lbProxyChecked = true
      return Some(loadBalanceProxy)
    }

    lbProxyChecked = true
    val lbEnabled =
      try config.getBoolean("htc.load-balance-manager.enabled")
      catch { case _: Exception => false }
    if (lbEnabled) {
      try {
        val proxy = DistributedUtil.createSingletonProxy(
          context.system,
          LOAD_BALANCE_MANAGER_ACTOR_NAME
        )
        // Race: only one proxy wins; stop the losers to avoid orphaned actors
        if (!CreatorLoadData.globalLbProxy.compareAndSet(null, proxy)) {
          context.stop(proxy)
        }
        loadBalanceProxy = CreatorLoadData.globalLbProxy.get()
        Some(loadBalanceProxy)
      } catch {
        case e: Exception =>
          logWarn(
            s"Failed to create LoadBalanceManager proxy: ${e.getMessage}. Using hash-based shard routing."
          )
          loadBalanceProxy = null
          None
      }
    } else {
      None // Load balancing not enabled
    }
  }

  /** Fallback handler: fires when a pending LBM chunk hasn't received a response within
    * [[LB_CHUNK_TIMEOUT]]. Proceeds with hash-based routing so entity creation isn't stalled.
    */
  private def handleLbChunkTimeout(event: CreatorLoadData.LbChunkTimeout): Unit = {
    pendingChunkTimeouts.remove(event.batchId)
    pendingChunks.remove(event.batchId) match {
      case Some(chunk) =>
        createEntitiesFromChunk(chunk, event.batchId)
        advanceToNextChunk(event.batchId, chunk.size)
      case None =>
      // Response already arrived just before the timeout fired — nothing to do
    }
  }

  private def addInitializeData(
    entityId: String,
    batchId: String,
    initialization: Initialization
  ): Unit =
    initializeData.get(batchId) match {
      case Some(data) => data.put(entityId, initialization)
      case None       => initializeData.put(batchId, mutable.Map(entityId -> initialization))
    }

  private def addToInitializedAcknowledges(batchId: String, entityId: String): Unit = {
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) => initializedAcknowledges.put(batchId, acknowledge :+ entityId)
      case None              => initializedAcknowledges.put(batchId, mutable.Seq(entityId))
    }
    actorsBatches.put(entityId, batchId)
  }

  private def removeOfInitializedAcknowledges(batchId: String, entityId: String): Unit =
    initializedAcknowledges.get(batchId) match {
      case Some(acknowledge) =>
        initializedAcknowledges.put(batchId, acknowledge.filter(_ != entityId))
      case None =>
    }

  private def handleInitialize(event: ShardRegion.StartEntityAck): Unit = {
    val classTypeForLog = {
      val bId = actorsBatches.getOrElse(event.entityId, "")
      if (bId.nonEmpty)
        initializeData.get(bId).flatMap(_.get(event.entityId)).map(_.classType).getOrElse("unknown")
      else if (pendingInitAck.contains(event.entityId)) pendingInitAck(event.entityId).classType
      else "not-found"
    }
    actorsBatches.get(event.entityId) match {
      case Some(batchId) =>
        initializeData.get(batchId).flatMap(_.get(event.entityId)) match {
          case Some(data) =>
            try {
              val initializeEvent = InitializeEvent(
                id = data.id,
                actorRef = self,
                data = InitializeData(
                  data = data.data,
                  resourceId = data.resourceId,
                  timeManagers = data.timeManagers,
                  creatorManager = data.creatorManager,
                  reporters = data.reporters,
                  relationships = data.relationships.map {
                    case (_, rel) => IdUtil.format(rel.entityId) -> rel
                  }
                )
              )

              getShardRef(StringUtil.getModelClassName(data.classType)) ! EntityEnvelopeEvent(
                entityId = event.entityId,
                event = initializeEvent
              )

              pendingInitAck.put(event.entityId, data)
            } catch {
              case e: Exception =>
                logError(
                  s"Failed to build InitializeEvent for ${event.entityId} (${data.classType}): ${e.getMessage}. Bypassing to unblock batch.",
                  e
                )
                // Bypass the stuck entity so the batch can complete
                removeOfInitializedAcknowledges(batchId, event.entityId)
            }
            initializeData(batchId).remove(event.entityId)
            if (initializeData(batchId).isEmpty) initializeData.remove(batchId)
            checkAndSendFinish(batchId)

          case None =>
        }
      case None =>
        logWarn(
          s"StartEntityAck for ${event.entityId} (classType=$classTypeForLog) NOT FOUND in actorsBatches! Known batches: ${actorsBatches.size}"
        )
    }
  }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    val init = pendingInitAck.remove(event.entityId)
    if (
      init.isDefined &&
      creatorProperties.postLoadCoordinator != null &&
      creatorProperties.postLoadRegistrationClasses.contains(init.get.classType)
    ) {
      creatorProperties.postLoadCoordinator ! NeedsPostLoadRegistrationEvent(
        entityId = event.entityId,
        classType = init.get.classType
      )
    }
    val batchId = actorsBatches.getOrElse(event.entityId, "")
    if (batchId.nonEmpty) {
      removeOfInitializedAcknowledges(batchId, event.entityId)
      checkAndSendFinish(batchId)
    } else {
      initializedAcknowledges.foreach {
        case (bId, acks) =>
          if (acks.contains(event.entityId)) {
            removeOfInitializedAcknowledges(bId, event.entityId)
            checkAndSendFinish(bId)
          }
      }
    }
  }

  /** Forwards NeedsPostLoadRegistrationEvent directly to PostLoadRegistrationCoordinator so it can
    * accumulate registrations during the loading phase (before being triggered). The coordinator is
    * the correct owner of this list, not LoadDataManager.
    */
  private def handleNeedsPostLoadRegistration(event: NeedsPostLoadRegistrationEvent): Unit =
    if (creatorProperties.postLoadCoordinator != null) {
      logDebug(
        s"Forwarding NeedsPostLoadRegistration for ${event.entityId} (${event.classType}) to coordinator"
      )
      creatorProperties.postLoadCoordinator ! event
    } else {
      logWarn(
        s"No postLoadCoordinator set — dropping NeedsPostLoadRegistration for ${event.entityId}"
      )
    }

  private def checkAndSendFinish(batchId: String): Unit = {
    val hasPendingCreation = actorsToCreate.get(batchId).exists(_.nonEmpty)
    val hasPendingAcks = initializedAcknowledges.get(batchId).exists(_.nonEmpty)
    val hasPendingInitData = initializeData.contains(batchId)

    if (!hasPendingCreation && !hasPendingAcks && !hasPendingInitData) {

      batchesLoad.get(batchId).foreach {
        ref =>
          ref ! FinishCreationEvent(
            actorRef = self,
            batchId = batchId,
            amount = amountActors
          )
      }

      batchesLoad.remove(batchId)
      batchesToCreate.remove(batchId)
      actorsToCreate.remove(batchId)

      actorsBatches.filterInPlace(
        (_, bId) => bId != batchId
      )
    }
  }
}

object CreatorLoadData {

  /** JVM-level cache: all [[CreatorLoadData]] instances on the same node share one proxy actor. */
  private[load] val globalLbProxy: java.util.concurrent.atomic.AtomicReference[ActorRef] =
    new java.util.concurrent.atomic.AtomicReference[ActorRef](null)

  /** Internal message: fires when an LBM chunk response hasn't arrived within the timeout. */
  final case class LbChunkTimeout(batchId: String)

  def props(
    creatorProperties: CreatorProperties
  ): Props =
    Props(
      classOf[CreatorLoadData],
      creatorProperties
    )
}
