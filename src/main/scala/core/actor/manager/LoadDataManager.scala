package org.interscity.htc
package core.actor.manager

import org.apache.pekko.actor.{ActorRef, Props}
import core.actor.manager.load.CreatorLoadData
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil
import core.util.ActorCreatorUtil.createActor

import org.apache.pekko.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import org.apache.pekko.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}
import org.apache.pekko.routing.RoundRobinPool
import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.htc.protobuf.core.entity.event.control.load.{FinishCreationEvent, FinishLoadDataEvent, StartCreationEvent}
import org.interscity.htc.core.entity.event.control.load.{LoadDataEvent, LoadDataSourceEvent}

import java.util.UUID
import scala.collection.mutable
import scala.compiletime.uninitialized

class LoadDataManager(
  val timeManager: ActorRef,
  val poolTimeManager: ActorRef,
  val simulationManager: ActorRef
) extends BaseManager[DefaultState](
      timeManager = timeManager,
      actorId = "load-data-manager",
      data = null,
      dependencies = mutable.Map.empty
    ) {

  private var loadDataTotalAmount = 0L
  private var dataSourceAmount: Int = Int.MaxValue
  private var creatorRef: ActorRef = uninitialized
  private val loaders: mutable.Map[ActorRef, Boolean] = mutable.Map[ActorRef, Boolean]()
  private var selfProxy: ActorRef = null
  private val creators = mutable.Set[String]()

  override def handleEvent: Receive = {
    case event: LoadDataEvent       => loadData(event)
    case event: FinishLoadDataEvent => handleFinishLoadData(event)
    case event: FinishCreationEvent => handleFinishCreation(event)
  }

  private def loadData(event: LoadDataEvent): Unit = {
    logEvent("Load data")
    dataSourceAmount = event.actorsDataSources.size
    creatorRef = createCreatorLoadData(dataSourceAmount)
    event.actorsDataSources.foreach {
      actorDataSource =>
        logEvent(s"Load data source ${actorDataSource.dataSource} of type ${actorDataSource.classType}")
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
      ).props(
        Props(
          new CreatorLoadData(
            loadDataManager = getSelfProxy,
            timeManager = poolTimeManager,
          )
        )
      ),
      "create-load-data-manager-router"
    )

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    logEvent(s"Load data manager actorRef = ${event.actorRef}")
    val actorRef = getActorRef(event.actorRef)

    loadDataTotalAmount += event.amount
    if (event.creators.nonEmpty) {
      creators ++= event.creators
    }
    loaders(actorRef) = true

    actorRef ! DestructEvent(actorRef = getPath)

    if (isAllDataLoaded) {
      creators.foreach {
        creator =>
          val creatorActorRef = getActorRef(creator)
          creatorActorRef ! StartCreationEvent(actorRef = getPath)
      }
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    logEvent(s"loadDataTotalAmount=${loadDataTotalAmount}, amount=${event.amount}")
    loadDataTotalAmount -= event.amount
    logEvent(s"loadDataTotalAmount=${loadDataTotalAmount}")
    if (loadDataTotalAmount <= 0) {
      logEvent("Finish creation")
      creatorRef ! DestructEvent(actorRef = getPath)
      simulationManager ! FinishLoadDataEvent(actorRef = getPath)
    }
  }

  private def isAllDataLoaded: Boolean =
    loaders.values.forall(_ == true) && dataSourceAmount == loaders.size

  private def getSelfProxy: ActorRef =
    if (selfProxy == null) {
      selfProxy = context.system.actorOf(
        ClusterSingletonProxy.props(
          singletonManagerPath = "/user/load-manager",
          settings = ClusterSingletonProxySettings(context.system)
        ),
        name = s"load-manager-proxy-${UUID.randomUUID().toString}"
      )
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
      new LoadDataManager(
        timeManager = timeManager,
        poolTimeManager = poolTimeManager,
        simulationManager = simulationManager
      )
    )
}
