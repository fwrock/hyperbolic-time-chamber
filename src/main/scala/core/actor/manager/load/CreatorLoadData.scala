package org.interscity.htc
package core.actor.manager.load

import core.actor.BaseActor

import org.apache.pekko.actor.{ ActorRef, Props }
import core.util.{ ActorCreatorUtil, DistributedUtil, IdUtil, StringUtil }
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil.createShardRegion

import org.apache.pekko.cluster.sharding.ShardRegion
import org.interscity.htc.core.entity.actor.ShardActorId
import org.htc.protobuf.core.entity.event.control.load.{ InitializeEntityAckEvent, StartCreationEvent }
import org.interscity.htc.core.actor.manager.loadbalance.allocation.{ ShardAllocatorRegistry, SpatialShardIdRegistry }
import org.interscity.htc.core.entity.actor.properties.{ CreatorProperties, Properties }
import org.interscity.htc.core.entity.actor.{ ActorSimulationCreation, Initialization }
import org.interscity.htc.core.entity.control.loadbalance.SpatialEntityData
import org.interscity.htc.core.entity.event.EntityEnvelopeEvent
import org.interscity.htc.core.entity.event.control.load.{ CreateActorsEvent, FinishCreationEvent, InitializeEvent, NeedsPostLoadRegistrationEvent, ProcessNextCreateChunk, RetryPendingAcks }
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

  // Fields needed for actor creation (not for simulation)
  private val timeManagers: mutable.Map[String, ActorRef] = creatorProperties.timeManagers
  private val creatorManager: ActorRef = creatorProperties.creatorManager
  private val reporters: mutable.Map[org.interscity.htc.core.enumeration.ReportTypeEnum, ActorRef] =
    creatorProperties.reporters

  private val initializeData = mutable.Map[String, mutable.Map[String, Initialization]]()
  private val initializedAcknowledges = mutable.Map[String, mutable.Seq[String]]()
  // Keeps Initialization data for entities that received StartEntityAck but not InitializeEntityAckEvent yet,
  // so we can resend the InitializeEvent if the entity crashed or stash-dropped it.
  private val pendingInitAck = mutable.Map[String, Initialization]()
  private var amountActors = 0

  private val actorsToCreate: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty
  private val actorsBatches: mutable.Map[String, String] = mutable.Map.empty
  private val batchesLoad: mutable.Map[String, ActorRef] = mutable.Map.empty
  private val batchesToCreate: mutable.Map[String, Seq[ActorSimulationCreation]] = mutable.Map.empty

  private val CREATE_CHUNK_SIZE = 1000
  private val DELAY_BETWEEN_CHUNKS = 100.milliseconds

  private var retryTask: org.apache.pekko.actor.Cancellable = _

  /** Lazy singleton proxy to LoadBalanceManager (null if load balancing disabled). */
  private var loadBalanceProxy: ActorRef = _

  /** Pending chunks awaiting BatchShardAssignmentResponse from LoadBalanceManager.
    * Key: batchId, Value: the chunk of actors waiting for spatial assignment.
    */
  private val pendingChunks: mutable.Map[String, List[ActorSimulationCreation]] = mutable.Map.empty

  /** Whether we've checked for the LoadBalanceManager proxy (avoid repeated lookups). */
  private var lbProxyChecked: Boolean = false

  override def onStart(): Unit = {
    super.onStart()
    retryTask = context.system.scheduler.scheduleWithFixedDelay(
      initialDelay = 5.seconds,
      delay = 5.seconds,
      receiver = self,
      message = RetryPendingAcks
    )
  }

  override def postStop(): Unit = {
    if (retryTask != null) retryTask.cancel()
    super.postStop()
  }

  override def handleEvent: Receive = {
    case event: CreateActorsEvent =>
      logDebug(s"Received CreateActorsEvent with ${event.actors.size} actors, batchId=${event.id}")
      handleCreateActors(event)
    case event: StartCreationEvent         => handleStartCreation(event)
    case event: ProcessNextCreateChunk     => handleProcessNextCreateChunk(event.batchId)
    case event: BatchShardAssignmentResponse    => handleBatchAssignmentResponse(event)
    case event: ShardRegion.StartEntityAck => handleInitialize(event)
    case event: InitializeEntityAckEvent       => handleFinishInitialization(event)
    case RetryPendingAcks                      => handleRetryPendingAcks()
    case event: NeedsPostLoadRegistrationEvent => handleNeedsPostLoadRegistration(event)

    case _ =>
  }

  private def handleRetryPendingAcks(): Unit = {
    var startEntityCount = 0
    var initEventCount = 0
    // Retry entities still waiting for StartEntityAck
    initializeData.foreach {
      case (_, entitiesMap) =>
        entitiesMap.foreach {
          case (entityId, initialization) =>
            try {
              val shardRegion = getShardRef(StringUtil.getModelClassName(initialization.classType))
              shardRegion ! ShardRegion.StartEntity(entityId)
              startEntityCount += 1
            } catch {
              case e: Exception =>
                logError(s"Watchdog: failed to retry StartEntity for $entityId: ${e.getMessage}", e)
            }
        }
    }
    // Retry entities that got StartEntityAck but never sent InitializeEntityAckEvent
    pendingInitAck.foreach {
      case (entityId, data) =>
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
            entityId = entityId,
            event = initializeEvent
          )
          initEventCount += 1
        } catch {
          case e: Exception =>
            logError(s"Watchdog: failed to retry InitializeEvent for $entityId: ${e.getMessage}", e)
        }
    }
    val total = startEntityCount + initEventCount
    if (total > 0) {
      val startClassBreakdown = initializeData.values.flatMap(_.values).groupBy(_.classType).map { case (k, v) => s"$k=${v.size}" }.mkString(", ")
      val initClassBreakdown  = pendingInitAck.values.groupBy(_.classType).map { case (k, v) => s"$k=${v.size}" }.mkString(", ")
      logWarn(s"Watchdog: retrying $total pending initializations ($startEntityCount awaiting StartEntityAck [$startClassBreakdown], $initEventCount awaiting InitializeEntityAck [$initClassBreakdown]).")
      if (initEventCount > 0) {
        val stuck = pendingInitAck.keys.take(10).mkString(", ")
        logWarn(s"Watchdog: stuck actor IDs (sample): $stuck")
      }
    }
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
    * Two-phase creation when load balancing is enabled:
    *   Phase 1: Extract spatial positions → send batch to LoadBalanceManager → wait
    *   Phase 2: On BatchShardAssignmentResponse → store mappings → create entities
    *
    * When load balancing is disabled, entities are created immediately with hash-based routing.
    */
  private def handleProcessNextCreateChunk(batchId: String): Unit = {

    val currentActors = actorsToCreate.getOrElse(batchId, List.empty)
    val chunk = currentActors.take(CREATE_CHUNK_SIZE)

    if (chunk.nonEmpty) {
      val lbProxy = getLoadBalanceProxy
      if (lbProxy.isDefined) {
        // Phase 1: Extract spatial data and send batch registration to LoadBalanceManager
        val spatialEntities = chunk.flatMap { actorCreation =>
          extractSpatialPosition(actorCreation).map { position =>
            SpatialEntityData(
              spatialEntityId = actorCreation.actor.id,
              position = position
            )
          }
        }

        if (spatialEntities.nonEmpty) {
          // Store chunk as pending — will be processed in handleBatchAssignmentResponse
          pendingChunks.put(batchId, chunk)
          lbProxy.get ! RegisterSpatialEntitiesBatchEvent(
            entities = spatialEntities,
            batchId = batchId
          )
          // Don't advance yet — wait for BatchShardAssignmentResponse
          return
        }
      }

      // No load balancing or no spatial data — create immediately with hash-based routing
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
    // Store all spatial shard assignments so extractShardId can use them
    SpatialShardIdRegistry.putAllShardIds(response.assignments)

    // Store entity positions for lookup by subsequent entities (links → node positions)
    SpatialShardIdRegistry.putAllPositions(response.positions)

    logDebug(
      s"Received spatial assignments for batch '${response.batchId}': " +
        s"${response.assignments.size} entities assigned"
    )

    // Retrieve and process the pending chunk
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

  /** Creates entities from a chunk — the actual creation loop extracted from handleProcessNextCreateChunk.
    * This is shared by both the immediate path (no LB) and the deferred path (after spatial assignment).
    */
  private def createEntitiesFromChunk(
    chunk: List[ActorSimulationCreation],
    batchId: String
  ): Unit = {
    chunk.foreach { actorCreation =>
      val initialization = Initialization(
        id = actorCreation.actor.id,
        resourceId = actorCreation.resourceId,
        classType = actorCreation.actor.typeActor,
        data = actorCreation.actor.data.content,
        timeManagers = timeManagers,
        creatorManager = self,
        reporters = reporters,
        relationships = {
        val base = if (actorCreation.actor.relationships != null) actorCreation.actor.relationships
                   else Map.empty[String, ShardActorId]
        mutable.Map[String, ShardActorId]() ++= base.map { case (label, rel) =>
          val bucket = if (rel.shardBucket.nonEmpty) rel.shardBucket
                       else SpatialShardIdRegistry.getShardId(rel.entityId).getOrElse("")
          label -> rel.copy(shardBucket = bucket)
        }
      }
      )

      addInitializeData(actorCreation.actor.id, batchId, initialization)
      addToInitializedAcknowledges(batchId, actorCreation.actor.id)

      // Track entity class name in spatial registry for migration routing
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
    * @return Some((longitude, latitude)) if spatial data is available, None otherwise
    */
  private def extractSpatialPosition(actorCreation: ActorSimulationCreation): Option[(Double, Double)] = {
    try {
      actorCreation.actor.data.content match {
        case contentMap: java.util.Map[?, ?] =>
          val map = contentMap.asInstanceOf[java.util.Map[String, Any]]

          // Direct coordinates (nodes and other entities with lat/long)
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

          // Links: look up source node position
          if (map.containsKey("from")) {
            val fromNodeId = map.get("from").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(fromNodeId)
            if (pos.isDefined) return pos
          }

          // Vehicles: look up origin node position
          if (map.containsKey("origin")) {
            val originNodeId = map.get("origin").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(originNodeId)
            if (pos.isDefined) return pos
          }

          // Fallback: currentNode
          if (map.containsKey("currentNode")) {
            val nodeId = map.get("currentNode").asInstanceOf[String]
            val pos = SpatialShardIdRegistry.getPosition(nodeId)
            if (pos.isDefined) return pos
          }

          None

        case _ => None
      }
    } catch {
      case _: Exception => None
    }
  }

  /** Lazily obtains a singleton proxy to LoadBalanceManager, if load balancing is enabled.
    *
    * Uses [[ShardAllocatorRegistry.isRegistered]] as the gate — if the allocator
    * hasn't been registered by LoadBalanceManager, spatial registration is skipped.
    */
  private def getLoadBalanceProxy: Option[ActorRef] = {
    if (lbProxyChecked) return Option(loadBalanceProxy)

    if (ShardAllocatorRegistry.isRegistered) {
      try {
        loadBalanceProxy = DistributedUtil.createSingletonProxy(
          context.system,
          LOAD_BALANCE_MANAGER_ACTOR_NAME
        )
        lbProxyChecked = true
        Some(loadBalanceProxy)
      } catch {
        case e: Exception =>
          logWarn(s"Failed to create LoadBalanceManager proxy: ${e.getMessage}. Using hash-based shard routing.")
          lbProxyChecked = true
          loadBalanceProxy = null
          None
      }
    } else {
      None // Load balancing not enabled
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
      if (bId.nonEmpty) initializeData.get(bId).flatMap(_.get(event.entityId)).map(_.classType).getOrElse("unknown")
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
        logWarn(s"StartEntityAck for ${event.entityId} (classType=$classTypeForLog) NOT FOUND in actorsBatches! Known batches: ${actorsBatches.size}")
    }
  }

  private def handleFinishInitialization(event: InitializeEntityAckEvent): Unit = {
    val init = pendingInitAck.remove(event.entityId)
    // If this entity's classType is in the config-level forced registration list, auto-register
    // it with the coordinator regardless of whether the actor itself sent NeedsPostLoadRegistrationEvent.
    // This allows including actors without modifying their input data files.
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
      // Late ACK after actorsBatches was cleaned — search all batches for ghost entries
      initializedAcknowledges.foreach {
        case (bId, acks) =>
          if (acks.contains(event.entityId)) {
            removeOfInitializedAcknowledges(bId, event.entityId)
            checkAndSendFinish(bId)
          }
      }
    }
  }

  /** Forwards NeedsPostLoadRegistrationEvent directly to PostLoadRegistrationCoordinator
    * so it can accumulate registrations during the loading phase (before being triggered).
    * The coordinator is the correct owner of this list, not LoadDataManager.
    */
  private def handleNeedsPostLoadRegistration(event: NeedsPostLoadRegistrationEvent): Unit = {
    if (creatorProperties.postLoadCoordinator != null) {
      logDebug(s"Forwarding NeedsPostLoadRegistration for ${event.entityId} (${event.classType}) to coordinator")
      creatorProperties.postLoadCoordinator ! event
    } else {
      logWarn(s"No postLoadCoordinator set — dropping NeedsPostLoadRegistration for ${event.entityId}")
    }
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
  def props(
    creatorProperties: CreatorProperties
  ): Props =
    Props(
      classOf[CreatorLoadData],
      creatorProperties
    )
}
