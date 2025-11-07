package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ ActorLogging, ActorNotFound, ActorRef, ActorSelection, Stash }
import core.entity.event.{ EntityEnvelopeEvent }
import core.entity.state.BaseState
import core.util.{ IdUtil, JsonUtil }

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.{ ClusterSharding, ShardRegion }
import org.apache.pekko.persistence.{ SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer }
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.entity.event.control.load.InitializeEvent

import java.util.UUID
import scala.compiletime.uninitialized
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

/** Generic base actor class that provides the basic structure for all actors in the system.
  * This class contains only generic actor functionality. For simulation-specific actors,
  * use SimulationBaseActor instead.
  * 
  * @param properties The properties containing actor configuration
  * @tparam T The state type of the actor
  */
abstract class BaseActor[T <: BaseState](
  private val properties: Properties
)(implicit m: Manifest[T])
    extends ActorSerializable
    with ActorLogging
    with Stash {

  protected val config = ConfigFactory.load()
  private var isInitialized: Boolean = false
  private val snapShotInterval = 1000

  protected var entityId: String =
    if (properties != null) properties.entityId 
    else {
      // 🎲 Usar UUID determinístico se RandomSeedManager estiver disponível
      try {
        core.actor.manager.RandomSeedManager.deterministicUUID()
      } catch {
        case _: Exception => UUID.randomUUID().toString // Fallback
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
    }

  /** Starts the actor. This method is called when the actor starts before processing messages.
    * Override this method to perform any initialization.
    */
  protected def onStart(): Unit = ()

  /** Handles events that are not handled by default receive.
    * Override this method to handle custom events.
    */
  protected def handleEvent: Receive = {
    case event => logInfo(s"Event not handled $event")
  }

  /** Handles initialization of the actor.
    * Override this method to perform custom initialization logic.
    * @param event The initialization event
    */
  protected def onInitialize(event: InitializeEvent): Unit = {
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
    case event: InitializeEvent               => onInitialize(event)
    case event: ShardRegion.StartEntity       => handleStartEntity(event)
    case SaveSnapshotSuccess(metadata)        =>
    case SaveSnapshotFailure(metadata, cause) =>
    case event                                => handleEvent(event)
  }

  protected def save(event: Any): Unit =
    persist(event) {
      e =>
        context.system.eventStream.publish(e)
        if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
          saveSnapshot(state)
    }

  def receiveCommand: Receive = receive

  def receiveRecover: Receive = {
    case snapshot: SnapshotOffer =>
      state = snapshot.snapshot.asInstanceOf[T]
      logInfo(s"Recovered state: $state")
    case _: org.apache.pekko.persistence.RecoveryCompleted =>
      // Recovery completed - this is normal Pekko Persistence lifecycle
      ()
    case event => 
      // During recovery, just log - don't process events meant for normal operation
      logDebug(s"Ignoring event during recovery: ${event.getClass.getSimpleName}")
  }

  private def handleEnvelopeEvent(entityEnvelopeEvent: EntityEnvelopeEvent): Unit =
    entityEnvelopeEvent.event match {
      case event: InitializeEvent         => onInitialize(event)
      case event: DestructEvent           => destruct(event)
      case event: ShardRegion.StartEntity => handleStartEntity(event)
      case event                          => handleEvent(event)
    }

  private def handleStartEntity(event: ShardRegion.StartEntity): Unit =
    entityId = event.entityId

  /** Handles the destruction event. This method is called when the actor receives a destruction
    * event. It calls the onDestruct method.
    * @param event The destruction event
    */
  private def destruct(event: DestructEvent): Unit = {
    onDestruct(event)
    context.stop(self)
  }

  /** Called when the actor is finished. This method is called when the actor finishes processing
    * messages.
    */
  protected def selfDestruct(): Unit = {
    context.stop(self)
  }

  /** Called when the actor is being destroyed.
    * Override this method to perform cleanup actions.
    * @param event The destruction event
    */
  protected def onDestruct(event: DestructEvent): Unit = {}

  /** Gets an actor selection by pool entity id.
    * @param entityId The entity id
    * @return The actor selection
    */
  protected def getActorPoolRef(entityId: String): ActorSelection =
    context.system.actorSelection(s"/user/${IdUtil.format(entityId)}")

  /** Gets an actor reference from a path.
    * @param path The actor path
    * @return The actor reference
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
    * @return The actor id
    */
  protected def getEntityId: String = entityId

  /** Gets the actor path as a string.
    * @return The actor path
    */
  protected def getPath: String = self.path.toString

  /** Gets the actor reference of the shard region for the current actor.
    * @return The actor reference of the shard region
    */
  protected def getSelfShard: ActorRef =
    ClusterSharding(context.system).shardRegion(getShardId)

  /** Gets the shard name for the current actor.
    * @return The shard name
    */
  protected def getShardId: String = getClass.getName

  /** Gets the actor reference of the shard region for a given class name.
    * @param className The class name of the shard region
    * @return The actor reference of the shard region
    */
  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className)
}
