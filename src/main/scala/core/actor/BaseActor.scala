package org.interscity.htc
package core.actor

import org.apache.pekko.actor.{ActorLogging, ActorNotFound, ActorRef, ActorSelection, Stash}
import core.entity.event.{ActorInteractionEvent, EntityEnvelopeEvent, FinishEvent, SpontaneousEvent}
import core.types.Tick
import core.entity.state.BaseState
import core.entity.control.LamportClock
import core.util.{IdUtil, JsonUtil, StringUtil}

import com.typesafe.config.ConfigFactory
import org.apache.pekko.cluster.sharding.{ClusterSharding, ShardRegion}
import org.apache.pekko.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import org.apache.pekko.util.Timeout
import org.htc.protobuf.core.entity.actor.{Dependency, Identify}
import org.htc.protobuf.core.entity.event.communication.ScheduleEvent
import org.htc.protobuf.core.entity.event.control.execution.{DestructEvent, RegisterActorEvent}
import org.htc.protobuf.core.entity.event.control.load.{InitializeEntityAckEvent, StartEntityAckEvent}
import org.interscity.htc.core.entity.actor.properties.{BaseProperties, Properties}
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.ReportTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum
import org.interscity.htc.core.enumeration.CreationTypeEnum.{LoadBalancedDistributed, PoolDistributed}
import org.interscity.htc.core.enumeration.TimePolicyEnum
import org.interscity.htc.core.entity.event.control.simulation.{AdvanceToTick, TickCompleted}

import java.util.UUID
import scala.Long.MinValue
import scala.collection.mutable
import scala.compiletime.uninitialized
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Base actor class that provides the basic structure for the actors in the system. All actors
  * should extend this class.
  * @param actorId
  *   The id of the actor
  * @param timeManager
  *   The actor reference of the time manager
  * @tparam T
  *   The state of the actor
  */
abstract class BaseActor[T <: BaseState](
  private val properties: BaseProperties
)(implicit m: Manifest[T])
    extends ActorSerializable
    with ActorLogging
    with Stash {

  protected val config = ConfigFactory.load()
  private val snapShotInterval = 1000
  protected var entityId: String =
    if (properties != null) properties.getEntityId
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

  override def preStart(): Unit = {
    super.preStart()
    onStart()
  }
  
  /** Starts the actor. This method is called on start the actor before starts processing messages.
   * If you want to perform any action before the actor starts processing messages, you should
   * override this method.
   */
  protected def onStart(): Unit = ()
  
  protected def handleEvent: Receive = {
    case event => logInfo(s"Event not handled $event")
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
    case _ => receive
  }

  private def handleEnvelopeEvent(entityEnvelopeEvent: EntityEnvelopeEvent): Unit =
    entityEnvelopeEvent.event match {
      case event: DestructEvent           => destruct(event)
      case event: ShardRegion.StartEntity => handleStartEntity(event)
      case event                          => handleEvent(event)
    }

  private def handleStartEntity(event: ShardRegion.StartEntity): Unit =
    entityId = event.entityId

  /** Handles the destruction event. This method is called when the actor receives a destruction
    * event. It calls the destruct method.
    * @param event
    *   The destruction event
    */
  private def destruct(event: DestructEvent): Unit = {
    onDestruct(event)
    context.stop(self)
  }

  /** Called when the actor is finished. This method is called when the actor finishes processing
    * messages. It calls the onDestruct method.
    */
  protected def selfDestruct(): Unit =
    context.stop(self)

  /** Finishes the actor. This method is called when the actor finishes processing messages. It
    * calls the onDestruct method.
    */
  protected def onDestruct(event: DestructEvent): Unit = {}

  protected def getActorPoolRef(entityId: String): ActorSelection =
    context.system.actorSelection(s"/user/${IdUtil.format(entityId)}")

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
    *
    * @param className
    *   The class name of the shard region
    * @return
    *   The actor reference of the shard region
    */
  protected def getShardRef(className: String): ActorRef =
    ClusterSharding(context.system).shardRegion(className)

  protected def getStatistics: Map[String, Any] = Map(
    "entityId" -> entityId,
    "shardId" -> shardId,
    "classType" -> getClass.getName,
    "state" -> (if (state != null) state.toString else "null"),
  )
}
