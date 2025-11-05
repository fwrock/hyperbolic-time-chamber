package org.interscity.htc
package model.example

import core.actor.SimulationBaseActor
import core.entity.event.SpontaneousEvent
import core.entity.state.BaseState
import org.interscity.htc.core.entity.actor.properties.Properties
import org.interscity.htc.core.enumeration.TimeManagerTypeEnum

/** Example actor demonstrating how to switch between time manager types
  * during simulation execution.
  * 
  * This actor starts with discrete-event time management and can switch
  * to time-stepped mode based on simulation conditions.
  */
class AdaptiveTimeManagerActor(
  properties: Properties
) extends SimulationBaseActor[ExampleState](properties) {

  private var eventCount = 0
  private val SWITCH_THRESHOLD = 100 // Switch after 100 events

  override protected def actSpontaneous(event: SpontaneousEvent): Unit = {
    eventCount += 1
    
    // Example: Switch to time-stepped mode after certain number of events
    if (eventCount == SWITCH_THRESHOLD && 
        getCurrentTimeManagerType == TimeManagerTypeEnum.DISCRETE_EVENT) {
      
      logInfo(s"Reached $SWITCH_THRESHOLD events, switching to time-stepped mode")
      
      if (switchTimeManager(TimeManagerTypeEnum.TIME_STEPPED)) {
        logInfo("Successfully switched to time-stepped time manager")
      } else {
        logWarn("Failed to switch time manager - time-stepped not available")
      }
    }
    
    // Example: Switch back to discrete-event under certain conditions
    if (eventCount > 200 && 
        getCurrentTimeManagerType == TimeManagerTypeEnum.TIME_STEPPED) {
      
      logInfo("Switching back to discrete-event mode")
      switchTimeManager(TimeManagerTypeEnum.DISCRETE_EVENT)
    }
    
    // Regular actor logic here
    performActorLogic()
    
    // Schedule next event
    onFinishSpontaneous(Some(currentTick + 1))
  }

  private def performActorLogic(): Unit = {
    // Actor-specific simulation logic
    logDebug(s"Processing event $eventCount at tick $currentTick using ${getCurrentTimeManagerType}")
  }

  override protected def onStart(): Unit = {
    super.onStart()
    logInfo(s"Actor started with time manager: ${getCurrentTimeManagerType}")
    logInfo(s"Available time manager types: ${properties.getAvailableTimeManagerTypes.mkString(", ")}")
  }
}

/** Example state for the adaptive time manager actor */
case class ExampleState(
  var counter: Int = 0
) extends BaseState {
  override def getStartTick: Long = 0
  override def isSetScheduleOnTimeManager: Boolean = true
  override def getReporterType: org.interscity.htc.core.enumeration.ReportTypeEnum = null
}

object AdaptiveTimeManagerExample {
  /** Example of how to create properties with multiple time managers */
  def createPropertiesWithMultipleTimeManagers(
    entityId: String,
    discreteEventTM: org.apache.pekko.actor.ActorRef,
    timeSteppedTM: org.apache.pekko.actor.ActorRef
  ): Properties = {
    import scala.collection.mutable
    
    Properties(
      entityId = entityId,
      timeManagers = mutable.Map(
        TimeManagerTypeEnum.DISCRETE_EVENT -> discreteEventTM,
        TimeManagerTypeEnum.TIME_STEPPED -> timeSteppedTM
      ),
      defaultTimeManagerType = TimeManagerTypeEnum.DISCRETE_EVENT,
      // ... other properties
    )
  }
  
  /** Example of backward compatible properties (uses discrete-event as default) */
  def createBackwardCompatibleProperties(
    entityId: String,
    timeManager: org.apache.pekko.actor.ActorRef
  ): Properties = {
    import scala.collection.mutable
    Properties(
      entityId = entityId,
      timeManagers = mutable.Map("discrete-event" -> timeManager), // Discrete-event as default
      creatorManager = null,
      reporters = mutable.Map.empty
    )
  }
}
