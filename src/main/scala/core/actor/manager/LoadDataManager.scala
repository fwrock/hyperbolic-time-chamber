package org.interscity.htc
package core.actor.manager

import core.entity.event.control.load.{ FinishCreationEvent, FinishLoadDataEvent, LoadDataEvent, LoadDataSourceEvent, StartCreationEvent }
import core.enumeration.DataSourceType

import org.apache.pekko.actor.{ ActorRef, Props }
import core.entity.event.control.execution.DestructEvent
import core.actor.manager.load.CreatorLoadData
import core.actor.manager.load.strategy.LoadDataStrategy
import core.entity.state.DefaultState
import core.util.ActorCreatorUtil
import core.util.ActorCreatorUtil.createActor

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
          actorDataSource.dataSource.sourceType.clazz,
          poolTimeManager
        )
        loaders.put(loader, false)
        loader ! LoadDataSourceEvent(
          managerRef = self,
          creatorRef = creatorRef,
          actorDataSource = actorDataSource
        )
    }
  }

  private def loadDataStrategy(dataSourceType: DataSourceType): LoadDataStrategy =
    dataSourceType.clazz.getDeclaredConstructor().newInstance()

  private def handleFinishLoadData(event: FinishLoadDataEvent): Unit = {
    loaders(event.actorRef) = true

    event.actorRef ! DestructEvent(actorRef = self)

    if (isAllDataLoaded) {
      creatorRef ! StartCreationEvent(actorRef = self)
    }
  }

  private def handleFinishCreation(event: FinishCreationEvent): Unit = {
    creatorRef ! DestructEvent(actorRef = self)
    simulationManager ! event
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
