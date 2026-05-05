package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ ActorLogging, ActorNotFound, ActorRef, ActorSelection, Stash }
import core.entity.event.EntityEnvelopeEvent
import core.entity.state.BaseState
import core.actor.manager.loadbalance.migration.{ MigrationSnapshot, MigrationStateStoreRegistry }
import core.util.{ IdUtil, JsonUtil }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.persistence.{ SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer }
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.PostLoadRegistrationEvent
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.entity.event.control.loadbalance.PrepareForMigrationEvent
import core.entity.event.control.migration.SaveMigrationSnapshotEvent
import org.interscity.htc.core.metrics.core.ActorMetrics

import java.util.UUID
import scala.compiletime.uninitialized
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

/** Generic base actor class that provides the basic structure for all actors in the system. This
  * class contains only generic actor functionality. For simulation-specific actors, use
  * SimulationBaseActor instead.
  *
  * @param properties
  *   The properties containing actor configuration
  * @tparam T
  *   The state type of the actor
  */
abstract class BaseActor[T <: BaseState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends ActorSerializable
    with ActorLogging
    with Stash {

  protected val config = ConfigFactory.load()
  private var isInitialized: Boolean = false
  private val snapShotInterval = 10000000

  protected var entityId: String =
    if (properties != null) properties.entityId
    else {
      try
        core.actor.manager.RandomSeedManager.deterministicUUID()
      catch {
        case _: Exception => UUID.randomUUID().toString
      }
    }
  protected var shardId: String = getShardId
  protected var state: T = uninitialized

  override def persistenceId: String = s"${getClass.getName}-${self.path.name}"

  /** Initializes the actor. This method is called before the actor starts processing messages.
    */
  override def preStart(): Unit = {
    super.preStart()
    onStart()
  }

  protected def onFinishInitialize(): Unit =
    if (!isInitialized) {
      isInitialized = true
      ActorMetrics.actorsInitialized.labels(getClass.getSimpleName).inc()
    }

  /** Starts the actor. This method is called when the actor starts before processing messages.
    * Override this method to perform any initialization.
    */
  protected def onStart(): Unit = ()

  /** Called when the actor is restored from a migration snapshot (moved to another node). Distinct
    * from [[onStart]] (first creation). Override to handle migration-specific re-initialization
    * (e.g., re-registering with external actors that were not saved). Default: calls [[onStart]]
    * for backward compatibility.
    */
  protected def onMigrationRestore(): Unit = onStart()

  /** Handles events that are not handled by default receive. Override this method to handle custom
    * events.
    */
  protected def handleEvent: Receive = {
    case event => logWarn(s"Event not handled $event")
  }

  /** Handles initialization of the actor. Override this method to perform custom initialization
    * logic.
    * @param event
    *   The initialization event
    */
  protected def onInitialize(event: InitializeEvent): Unit =
    if (!isInitialized) {
      entityId = event.id
      state = JsonUtil.convertValue[T](event.data.data)
      if (state != null) {
        onFinishInitialize()
      } else {
        onFinishInitialize()
        context.stop(self)
      }
    }

  /** Logs an event. This method is called when the actor wants to log an event.
    * @param eventInfo
    *   The information of the event
    */
  protected def logInfo(eventInfo: String): Unit =
    log.info(s"$entityId: $eventInfo")

  protected def logDebug(eventInfo: String): Unit =
    log.debug(s"$entityId: $eventInfo")

  protected def logWarn(eventInfo: String): Unit =
    log.warning(s"$entityId: $eventInfo")

  protected def logError(eventInfo: String, throwable: Throwable): Unit =
    log.error(throwable, s"$entityId: $eventInfo")

  protected def logError(eventInfo: String): Unit =
    log.error(s"$entityId: $eventInfo")

  override def receive: Receive = {
    case event: DestructEvent                 => destruct(event)
    case event: EntityEnvelopeEvent           => handleEnvelopeEvent(event)
    case event: PostLoadRegistrationEvent     => onPostLoadRegistration(event)
    case event: InitializeEvent               => onInitialize(event)
    case event: PrepareForMigrationEvent      => handlePrepareForMigration(event)
    case event: ShardRegion.StartEntity       => handleStartEntity(event)
    case SaveSnapshotSuccess(metadata)        =>
    case SaveSnapshotFailure(metadata, cause) =>
    case event                                => handleEvent(event)
  }

  private def save(event: Any): Unit = ()
//    persist(event) {
//      e =>
//        context.system.eventStream.publish(e)
//        if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
//          saveSnapshot(state)
//    }

  def receiveCommand: Receive = receive

  def receiveRecover: Receive = {
    case snapshot: SnapshotOffer =>
      state = snapshot.snapshot.asInstanceOf[T]
    case _ => receive
  }

  private def handleEnvelopeEvent(entityEnvelopeEvent: EntityEnvelopeEvent): Unit =
    entityEnvelopeEvent.event match {
      case event: InitializeEvent           => onInitialize(event)
      case event: DestructEvent             => destruct(event)
      case event: PrepareForMigrationEvent  => handlePrepareForMigration(event)
      case event: ShardRegion.StartEntity   => handleStartEntity(event)
      case event: PostLoadRegistrationEvent => onPostLoadRegistration(event)
      case event                            => handleEvent(event)
    }

  /** Called after ALL eager sources have finished loading. Override in actors that need to register
    * with other actors (e.g. BusStop → Node) to guarantee the target is already fully initialized
    * before the registration message is sent. The actor MUST eventually call event.coordinatorRef !
    * PostLoadRegistrationAckEvent(entityId). SimulationBaseActor provides a default implementation
    * that calls handlePostLoadRegistration() and then automatically sends the ACK.
    */
  protected def onPostLoadRegistration(event: PostLoadRegistrationEvent): Unit = {}

  private def handleStartEntity(event: ShardRegion.StartEntity): Unit =
    entityId = event.entityId

  /** Handles the destruction event. This method is called when the actor receives a destruction
    * event. It calls the onDestruct method.
    * @param event
    *   The destruction event
    */
  private def destruct(event: DestructEvent): Unit = {
    ActorMetrics.actorsDestroyed.labels(getClass.getSimpleName).inc()
    onDestruct(event)
    context.stop(self)
  }

  /** Called when the actor is finished. This method is called when the actor finishes processing
    * messages.
    */
  protected def selfDestruct(): Unit =
    context.stop(self)

  /** Called when the actor is being destroyed. Override this method to perform cleanup actions.
    * @param event
    *   The destruction event
    */
  protected def onDestruct(event: DestructEvent): Unit = {}

  /** Handles the PrepareForMigrationEvent by building a snapshot and sending it to the
    * SnapshotManager (cluster singleton), so it is accessible from all nodes.
    *
    * The event now carries a batchId and lbmRef — these are forwarded in the snapshot save event so
    * the SM can track which migration batch this entity belongs to.
    *
    * @param event
    *   The prepare-for-migration event
    */
  private def handlePrepareForMigration(event: PrepareForMigrationEvent): Unit = {
    if (state == null) {
      logWarn(s"PrepareForMigrationEvent for $entityId but state is null — skipping snapshot")
      return
    }
    try {
      val snapshot = buildMigrationSnapshot()
      MigrationStateStoreRegistry.getSnapshotManager match {
        case Some(smRef) =>
          smRef ! SaveMigrationSnapshotEvent(
            entityId = IdUtil.format(entityId),
            batchId = event.batchId,
            snapshot = snapshot
          )
          logDebug(s"Migration snapshot sent to SM for '$entityId' (batch=${event.batchId})")
        case None =>
          logWarn(
            s"PrepareForMigrationEvent for '$entityId' but SnapshotManager proxy is not registered — " +
              s"snapshot will be lost. Entity may not restore correctly after migration."
          )
      }
    } catch {
      case e: Exception =>
        logError(s"Failed to build migration snapshot for '$entityId': ${e.getMessage}", e)
    }
  }

  /** Serializes the current actor state to the [[MigrationStateStoreRegistry]] store.
    *
    * Only saves if:
    *   1. A migration state store is registered (LBM is active) 2. The actor has been initialized
    *      (state is not null)
    *
    * Subclasses (e.g., [[SimulationBaseActor]]) can override [[buildMigrationSnapshot]] to include
    * additional fields beyond the base state.
    */
  protected def saveMigrationState(): Unit =
    MigrationStateStoreRegistry.get.foreach {
      store =>
        if (state != null) {
          try {
            val snapshot = buildMigrationSnapshot()
            val snapshotJson = JsonUtil.toJson(snapshot)
            val snapshotBytes = snapshotJson.getBytes("UTF-8")
            store.saveState(entityId, snapshotBytes, snapshot.stateClassName)
          } catch {
            case e: Exception =>
              logError(s"Failed to save migration state for $entityId: ${e.getMessage}", e)
          }
        }
    }

  /** Builds a [[MigrationSnapshot]] containing all data to preserve across migration.
    *
    * By default, includes only the actor's typed state. Subclasses override to include additional
    * transient fields (e.g., SimulationBaseActor adds currentTick, lamportClock, dependencies).
    *
    * @return
    *   A [[MigrationSnapshot]] with the serialized state and metadata
    */
  protected def buildMigrationSnapshot(): MigrationSnapshot =
    MigrationSnapshot(
      stateJson = JsonUtil.toJson(state),
      stateClassName = state.getClass.getName,
      entityId = entityId
    )

  /** Attempts to restore actor state from the migration state store.
    *
    * Called during actor initialization. If migration state is found, it means this actor was
    * migrated from another node and should restore its previous state instead of initializing from
    * scratch.
    *
    * @return
    *   true if migration state was successfully restored, false if not found or failed
    */
  protected def restoreMigrationState(): Boolean =
    MigrationStateStoreRegistry.get match {
      case Some(store) =>
        store.loadAndRemoveState(entityId) match {
          case Some((snapshotBytes, _)) =>
            try {
              val snapshotJson = new String(snapshotBytes, "UTF-8")
              val snapshot = JsonUtil.fromJson[MigrationSnapshot](snapshotJson)
              applyMigrationSnapshot(snapshot)
              logInfo(s"Migration state restored for entity $entityId")
              true
            } catch {
              case e: Exception =>
                logError(s"Failed to restore migration state for $entityId: ${e.getMessage}", e)
                false
            }
          case None => false
        }
      case None => false
    }

  /** Applies a restored [[MigrationSnapshot]] to the actor.
    *
    * Subclasses override this to restore additional fields beyond the base state (e.g.,
    * SimulationBaseActor restores currentTick, lamportClock, dependencies).
    *
    * @param snapshot
    *   The deserialized migration snapshot
    */
  protected def applyMigrationSnapshot(snapshot: MigrationSnapshot): Unit =
    state = JsonUtil.fromJsonClassName[T](snapshot.stateJson, snapshot.stateClassName)

  /** Gets an actor selection by pool entity id.
    * @param entityId
    *   The entity id
    * @return
    *   The actor selection
    */
  protected def getActorPoolRef(entityId: String): ActorSelection =
    context.system.actorSelection(s"/user/${IdUtil.format(entityId)}")

  /** Gets an actor reference from a path.
    * @param path
    *   The actor path
    * @return
    *   The actor reference
    */
  protected def getActorRef(path: String): ActorRef =
    Await.result(getActorRefFromPath(path), Duration.Inf)

  private def getActorRefFromPath(path: String): Future[ActorRef] = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    implicit val timeout: Timeout = Timeout(3, TimeUnit.SECONDS)
    context.system
      .actorSelection(path)
      .resolveOne()
      .recover {
        case _: ActorNotFound =>
          log.warning(s"Actor not found: $path")
          null
      }
  }

  /** Gets the actor id.
    * @return
    *   The actor id
    */
  protected def getEntityId: String = entityId

  /** Gets the actor path as a string.
    * @return
    *   The actor path
    */
  protected def getPath: String = self.path.toString

  /** Gets the actor reference of the shard region for the current actor.
    * @return
    *   The actor reference of the shard region
    */
  protected def getSelfShard: ActorRef =
    ClusterSharding(context.system).shardRegion(getShardId)

  /** Gets the shard name for the current actor.
    * @return
    *   The shard name
    */
  protected def getShardId: String = getClass.getName

  /** Gets the actor reference of the shard region for a given class name.
    * @param className
    *   The class name of the shard region
    * @return
    *   The actor reference of the shard region
    */
  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className)
}
