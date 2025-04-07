package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ ActorRef, Props }
import core.actor.manager.load.CreatorLoadData
import core.entity.state.DefaultState
import core.util.{ ActorCreatorUtil, ManagerConstantsUtil }
import core.util.ActorCreatorUtil.createActor

import org.apache.pekko.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.htc.protobuf.core.entity.event.control.load.StartCreationEvent
import org.interscity.htc.core.entity.event.control.load.{ FinishCreationEvent, FinishLoadDataEvent, LoadDataEvent, LoadDataSourceEvent }
import org.interscity.htc.core.util.ManagerConstantsUtil.{ LOAD_MANAGER_ACTOR_NAME, POOL_CREATOR_LOAD_DATA_ACTOR_NAME }

import scala.collection.mutable
import scala.compiletime.uninitialized

class LoadDataManager(
  val timeManager: ActorRef,
  val poolTimeManager: ActorRef,
  val simulationManager: ActorRef
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "load-data-manager",
    ) {

  private var loadDataTotalAmount = 0L
  private var currentLoadDataAmount = 0L
  private var dataSourceAmount: Int = Int.MaxValue
  private var creatorRef: ActorRef = uninitialized
  private val loaders: mutable.Map[ActorRef, Boolean] = mutable.Map[ActorRef, Boolean]()
  private var selfProxy: ActorRef = null
  private val creators = mutable.Map[ActorRef, Boolean]()

  override def handleEvent: Receive = {
    case event: LoadDataEvent       => loadData(event)
    case event: FinishLoadDataEvent => handleFinishLoadData(event)
    case event: FinishCreationEvent => handleFinishCreation(event)
  }

  private def loadData(event: LoadDataEvent): Unit = {
    dataSourceAmount = event.actorsDataSources.size
    logEvent(s"Starting Load data, dataSourceAmount = $dataSourceAmount")
    creatorRef = createCreatorLoadData(dataSourceAmount)
    event.actorsDataSources.foreach {
      actorDataSource =>
        logEvent(
          s"Load data source ${actorDataSource.dataSource} of type ${actorDataSource.classType}"
        )
        val loader = createActor(
          context.system,
          actorDataSource.dataSource.sourceType.clazz,
          poolTimeManager
        )
        loaders.put(loader, false)
        loader ! LoadDataSourceEvent(
          managerRef = getSelfProxy,
          creatorRef = creatorRef,
          actorDataSource = actorDataSource
        )
    }
  }

  private def createCreatorLoadData(amountDataSources: Int): ActorRef =
    context.actorOf(
      ClusterRouterPool(
        RoundRobinPool(1),
        ClusterRouterPoolSettings(
          totalInstances = amountDataSources * 2,
          maxInstancesPerNode = amountDataSources,
          allowLocalRoutees = true
        )
      ).props(CreatorLoadData.props(getSelfProxy, poolTimeManager)),
      name = POOL_CREATOR_LOAD_DATA_ACTOR_NAME
    )

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    val actorRef = event.actorRef

    loadDataTotalAmount += event.amount
    if (event.creators.nonEmpty) {
      event.creators.foreach {
        creator => creators.put(creator, false)
      }
    }
    loaders(actorRef) = true

    logEvent(s"loaders = $loaders")

    actorRef ! DestructEvent(actorRef = getPath)
//
//    logEvent(s"all loaders = ${loaders.values
//        .forall(_ == true)}, dataSourceAmount = $dataSourceAmount, loaders.size = ${loaders.size}")
    if (isAllDataLoaded) {
      creators.keys.foreach {
        creator =>
          creator ! StartCreationEvent(actorRef = getPath)
      }
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
//    logEvent(s"loadDataTotalAmount=$loadDataTotalAmount, amount=${event.amount}, currentLoadDataAmount=$currentLoadDataAmount")
    creators.get(event.actorRef) match
      case Some(flag) =>
        if (!flag) {
          currentLoadDataAmount += event.amount
          creators(event.actorRef) = true
        }
      case None =>
        logEvent(s"Creator not found ${event.actorRef}")
//    logEvent(s"loadDataTotalAmount=$loadDataTotalAmount, amount=${event.amount}, currentLoadDataAmount=$currentLoadDataAmount")
    if (loadDataTotalAmount == currentLoadDataAmount) {
      logEvent("Finish creation")
      creatorRef ! DestructEvent(actorRef = getPath)
      simulationManager ! FinishLoadDataEvent(
        actorRef = selfProxy,
        amount = loadDataTotalAmount,
        creators = mutable.Set()
      )
    }
  }

  private def isAllDataLoaded: Boolean =
    loaders.values.forall(_ == true) && dataSourceAmount == loaders.size

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = createSingletonProxy(LOAD_MANAGER_ACTOR_NAME)
      selfProxy
    } else {
      selfProxy
    }
}

object LoadDataManager {
  def props(
    timeManager: ActorRef,
    poolTimeManager: ActorRef,
    simulationManager: ActorRef
  ): Props =
    Props(
      classOf[LoadDataManager],
      timeManager,
      poolTimeManager,
      simulationManager
    )
}
