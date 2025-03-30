package org.interscity.htc
package core.actor.manager

import core.enumeration.DataSourceTypeEnum

import org.apache.pekko.actor.{ActorRef, Props}
import core.actor.manager.load.CreatorLoadData
import core.actor.manager.load.strategy.LoadDataStrategy
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil
import core.util.ActorCreatorUtil.createActor

import org.htc.protobuf.core.entity.event.control.execution.DestructEvent
import org.htc.protobuf.core.entity.event.control.load.{FinishCreationEvent, FinishLoadDataEvent, LoadDataEvent, LoadDataSourceEvent, StartCreationEvent}

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

  private var loadDataAmount: Int = Int.MaxValue
  private var creatorRef: ActorRef = uninitialized
  private val loaders: mutable.Map[ActorRef, Boolean] = mutable.Map[ActorRef, Boolean]()

  override def handleEvent: Receive = {
    case event: LoadDataEvent       => loadData(event)
    case event: FinishLoadDataEvent => handleFinishLoadData(event)
    case event: FinishCreationEvent => handleFinishCreation(event)
  }

  private def loadData(event: LoadDataEvent): Unit = {
    logEvent("Load data")
    loadDataAmount = event.actorsDataSources.size
    creatorRef = context.actorOf(
      Props(new CreatorLoadData(loadDataManager = self, timeManager = poolTimeManager))
    )
    event.actorsDataSources.foreach {
      actorDataSource =>
        logEvent(s"Load data source ${actorDataSource}")
        val loader = createActor(
          context.system,
          DataSourceTypeEnum.valueOf(actorDataSource.dataSource.get.sourceType.name).clazz,
          poolTimeManager
        )
        loaders.put(loader, false)
        loader ! LoadDataSourceEvent(
          managerRef = getPath,
          creatorRef = creatorRef.path.toString,
          actorDataSource = Some(actorDataSource)
        )
    }
  }

  private def loadDataStrategy(dataSourceType: DataSourceTypeEnum): LoadDataStrategy =
    dataSourceType.clazz.getDeclaredConstructor().newInstance()

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    logEvent(s"Load data maanager actorRef = ${event.actorRef}")
    val actorRef = getActorRef(event.actorRef)

    loaders(actorRef) = true

    actorRef! DestructEvent(actorRef = getPath)

    if (isAllDataLoaded) {
      creatorRef ! StartCreationEvent(actorRef = getPath)
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    creatorRef ! DestructEvent(actorRef = getPath)
    simulationManager ! FinishLoadDataEvent(actorRef = getPath)
  }

  private def isAllDataLoaded: Boolean =
    loaders.values.forall(_ == true) && loadDataAmount == loaders.size
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
