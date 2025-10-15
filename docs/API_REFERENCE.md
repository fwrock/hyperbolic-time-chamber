# 📖 API Reference

Complete API documentation for the Hyperbolic Time Chamber simulation framework. This reference covers the core actor system, event types, configuration interfaces, and extension points.

---

## 🎯 **API Overview**

The HTC API is organized around these core concepts:

- **🎭 Actors**: Independent entities that encapsulate state and behavior
- **📨 Events**: Messages that trigger actor behavior and state changes
- **⏰ Time Management**: Discrete event scheduling and synchronization
- **📊 Reporting**: Data collection and analysis interfaces
- **🔌 Extensions**: Plugin and customization points

---

## 🎭 **Core Actor System**

### **🏗️ BaseActor<T>**

The foundation class for all simulation actors in the Hyperbolic Time Chamber framework.

```scala
abstract class BaseActor[T <: BaseState](
  private val properties: Properties
)(implicit m: Manifest[T]) 
  extends ActorSerializable with ActorLogging with Stash
```

#### **📋 Overview**

BaseActor provides the essential infrastructure for all simulation entities, including:
- **State Management**: Immutable state with persistence support
- **Event Handling**: Processing of spontaneous and interaction events
- **Time Management**: Integration with the simulation time system
- **Communication**: Message passing with other actors
- **Lifecycle Management**: Initialization, execution, and destruction phases

---

#### **🔄 Lifecycle Methods**

##### **Actor Initialization**
```scala
// Called during actor startup before message processing begins
protected def onStart(): Unit = ()

// Called during actor initialization with configuration data
protected def onInitialize(event: InitializeEvent): Unit = ()

// Called when actor is being destroyed/stopped
protected def onDestruct(event: DestructEvent): Unit = {}

// Manually stop the actor
protected def selfDestruct(): Unit
```

**Usage Example:**
```scala
class CarActor(properties: Properties) extends BaseActor[CarState](properties) {
  
  override def onInitialize(event: InitializeEvent): Unit = {
    // Set up initial state
    logInfo("Car actor initialized")
    // Register with traffic management systems
    registerWithTrafficSystem()
  }
  
  override def onDestruct(event: DestructEvent): Unit = {
    // Clean up resources
    logInfo("Car actor stopping")
    unregisterFromTrafficSystem()
  }
}
```

---

#### **📨 Event Handling Methods**

##### **Spontaneous Events**
```scala
// Handle internal/self-triggered events from the time manager
protected def actSpontaneous(event: SpontaneousEvent): Unit = ()

// Trigger a spontaneous event on yourself
protected def selfSpontaneous(): Unit

// Signal completion of spontaneous event processing
protected def onFinishSpontaneous(
  scheduleTick: Option[Tick] = None,
  destruct: Boolean = false
): Unit
```

**Usage Example:**
```scala
override def actSpontaneous(event: SpontaneousEvent): Unit = {
  state.status match {
    case "moving" =>
      updatePosition()
      // Schedule next movement in 1 time unit
      onFinishSpontaneous(scheduleTick = Some(getCurrentTick + 1))
    
    case "waiting" =>
      checkForGreenLight()
      onFinishSpontaneous(scheduleTick = Some(getCurrentTick + 5))
    
    case _ =>
      onFinishSpontaneous() // No rescheduling
  }
}
```

##### **Actor Interaction Events**
```scala
// Handle events from other actors
def actInteractWith(event: ActorInteractionEvent): Unit = ()

// Send message to another actor by entity ID
protected def sendMessageTo(
  entityId: String,
  shardId: String = null,
  data: AnyRef,
  eventType: String = "default",
  actorType: CreationTypeEnum = LoadBalancedDistributed
): Unit
```

**Usage Example:**
```scala
override def actInteractWith(event: ActorInteractionEvent): Unit = {
  event.data match {
    case trafficSignalData: TrafficSignalData =>
      if (trafficSignalData.isGreen) {
        startMoving()
      } else {
        stopAtIntersection()
      }
    
    case emergencyAlert: EmergencyAlert =>
      pullOver()
      
    case _ =>
      logWarn(s"Unknown interaction event: ${event.data}")
  }
}

// Example of sending a message
private def requestRouteCalculation(): Unit = {
  sendMessageTo(
    entityId = "route-calculator-1",
    data = RouteRequest(origin = currentPosition, destination = targetDestination),
    eventType = "route_request"
  )
}
```

---

#### **⏰ Time Management Methods**

```scala
// Get current simulation time
protected def getCurrentTick: Tick = currentTick

// Schedule an event at a specific future time
protected def scheduleEvent(tick: Tick): Unit

// Get Lamport clock for distributed time synchronization
private def getLamportClock: Long

// Update Lamport clock when receiving messages
private def updateLamportClock(otherClock: Long): Unit
```

**Usage Example:**
```scala
private def scheduleNextAction(): Unit = {
  val nextActionTime = getCurrentTick + calculateDelay()
  scheduleEvent(nextActionTime)
  logInfo(s"Next action scheduled for tick $nextActionTime")
}
```

---

#### **🗄️ State Management Methods**

```scala
// Access the current actor state (implicit through state variable)
protected var state: T

// State persistence and recovery (automatic via Pekko Persistence)
private def save(event: Any): Unit
def receiveRecover: Receive
```

**Usage Example:**
```scala
// State is accessed directly as a protected variable
private def updateCarPosition(newPosition: Position): Unit = {
  state = state.copy(
    position = newPosition,
    lastUpdate = getCurrentTick
  )
  // State automatically persisted through event sourcing
}
```

---

#### **📡 Communication and Messaging Methods**

```scala
// Get actor reference by path
protected def getActorRef(path: String): ActorRef

// Get actor pool reference for PoolDistributed actors
protected def getActorPoolRef(entityId: String): ActorSelection

// Get dependency actor information
protected def getDependency(entityId: String): Dependency

// Get shard region reference for distributed actors
protected def getShardRef(className: String): ActorRef
protected def getSelfShard: ActorRef
```

**Usage Example:**
```scala
private def communicateWithTrafficLight(): Unit = {
  val trafficLightDep = getDependency("traffic-light-1")
  sendMessageTo(
    entityId = trafficLightDep.id,
    data = VehicleApproachingData(vehicleId = getEntityId, speed = state.speed)
  )
}
```

---

#### **📊 Reporting and Logging Methods**

##### **Data Reporting**
```scala
// Report data with optional label
protected def report(data: Any, label: String = null): Unit

// Report with full event structure
protected def report(event: ReportEvent): Unit

// Simple data reporting
protected def report(data: Any): Unit
```

##### **Logging**
```scala
// Standard logging methods
protected def logInfo(eventInfo: String): Unit
protected def logDebug(eventInfo: String): Unit
protected def logWarn(eventInfo: String): Unit
protected def logError(eventInfo: String): Unit
protected def logError(eventInfo: String, throwable: Throwable): Unit
```

**Usage Example:**
```scala
private def reportVehicleMetrics(): Unit = {
  // Report position and speed data
  report(
    data = VehicleMetrics(
      position = state.position,
      speed = state.speed,
      fuel = state.fuelLevel
    ),
    label = "vehicle_metrics"
  )
}

private def handleError(operation: String, error: Throwable): Unit = {
  logError(s"Failed to $operation", error)
  // Report error for analysis
  report(ErrorReport(operation = operation, error = error.getMessage))
}
```

---

#### **🔧 Utility Methods**

```scala
// Actor identification
protected def getEntityId: String
protected def getPath: String
protected def getShardId: String

// Actor references and managers
protected def getTimeManager: ActorRef

// Create identity object for actor registration
protected def toIdentify: Identify
```

---

#### **⚙️ Properties Configuration**
```scala
case class Properties(
  entityId: String,                                    // Unique actor identifier
  resourceId: String,                                  // Resource/shard identifier
  creatorManager: ActorRef,                           // Creator manager reference
  timeManager: ActorRef,                              // Time manager reference
  reporters: mutable.Map[ReportTypeEnum, ActorRef],   // Report managers
  data: Option[Any],                                  // Initialization data
  actorType: CreationTypeEnum,                        // Creation strategy
  dependencies: mutable.Map[String, Dependency] = mutable.Map() // Actor dependencies
)
```

---

#### **📚 Complete Implementation Example**

Here's a comprehensive example showing how to implement a traffic light actor using BaseActor methods:

```scala
package org.interscity.htc.model.mobility.actor

import org.interscity.htc.core.actor.BaseActor
import org.interscity.htc.core.entity.event.{SpontaneousEvent, ActorInteractionEvent}
import org.interscity.htc.core.entity.event.control.load.InitializeEvent
import org.interscity.htc.core.entity.event.control.execution.DestructEvent

// State definition
case class TrafficLightState(
  currentPhase: String = "red",     // red, yellow, green
  phaseDuration: Int = 30,          // seconds in current phase
  phaseStartTick: Long = 0,         // when current phase started
  intersectionId: String = "",      // associated intersection
  override val startTick: Long = 0  // actor start time
) extends BaseState(startTick)

// Traffic light actor implementation
class TrafficLightActor(properties: Properties) 
  extends BaseActor[TrafficLightState](properties) {
  
  // 1. LIFECYCLE METHODS
  override def onInitialize(event: InitializeEvent): Unit = {
    logInfo(s"Traffic light ${getEntityId} initializing at intersection ${state.intersectionId}")
    
    // Register with intersection controller
    val intersectionDep = getDependency(state.intersectionId)
    sendMessageTo(
      entityId = intersectionDep.id,
      data = TrafficLightRegistrationData(lightId = getEntityId),
      eventType = "register_light"
    )
    
    // Schedule first phase change
    scheduleNextPhaseChange()
  }
  
  override def onDestruct(event: DestructEvent): Unit = {
    logInfo(s"Traffic light ${getEntityId} shutting down")
    
    // Notify intersection of shutdown
    val intersectionDep = getDependency(state.intersectionId)
    sendMessageTo(
      entityId = intersectionDep.id,
      data = TrafficLightShutdownData(lightId = getEntityId),
      eventType = "light_shutdown"
    )
  }
  
  // 2. EVENT HANDLING METHODS
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    state.currentPhase match {
      case "red" =>
        changePhase("green")
        reportPhaseChange()
        scheduleNextPhaseChange()
        
      case "green" =>
        changePhase("yellow")
        reportPhaseChange()
        // Yellow phase is shorter
        onFinishSpontaneous(scheduleTick = Some(getCurrentTick + 5))
        
      case "yellow" =>
        changePhase("red")
        reportPhaseChange()
        scheduleNextPhaseChange()
        
      case _ =>
        logError(s"Unknown phase: ${state.currentPhase}")
        onFinishSpontaneous()
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.data match {
      case emergencyRequest: EmergencyVehicleRequest =>
        handleEmergencyRequest(emergencyRequest)
        
      case phaseRequest: PhaseChangeRequest =>
        handlePhaseChangeRequest(phaseRequest)
        
      case statusQuery: StatusQuery =>
        respondWithStatus(event.actorRefId, statusQuery)
        
      case _ =>
        logWarn(s"Unknown interaction data: ${event.data}")
    }
  }
  
  // 3. BUSINESS LOGIC METHODS
  private def changePhase(newPhase: String): Unit = {
    val oldPhase = state.currentPhase
    state = state.copy(
      currentPhase = newPhase,
      phaseStartTick = getCurrentTick
    )
    
    logInfo(s"Phase changed from $oldPhase to $newPhase at tick ${getCurrentTick}")
    
    // Notify nearby vehicles
    notifyVehiclesOfPhaseChange(newPhase)
  }
  
  private def scheduleNextPhaseChange(): Unit = {
    val nextTick = getCurrentTick + state.phaseDuration
    onFinishSpontaneous(scheduleTick = Some(nextTick))
    logDebug(s"Next phase change scheduled for tick $nextTick")
  }
  
  private def handleEmergencyRequest(request: EmergencyVehicleRequest): Unit = {
    logInfo(s"Emergency vehicle request received: ${request.vehicleId}")
    
    if (state.currentPhase != "green") {
      // Override current phase for emergency vehicle
      changePhase("green")
      reportPhaseChange(isEmergencyOverride = true)
      
      // Schedule return to normal operation
      val emergencyDuration = 15 // seconds
      onFinishSpontaneous(scheduleTick = Some(getCurrentTick + emergencyDuration))
    }
  }
  
  private def notifyVehiclesOfPhaseChange(newPhase: String): Unit = {
    // Get all vehicles in range (from dependencies or spatial queries)
    val nearbyVehicles = findNearbyVehicles()
    
    nearbyVehicles.foreach { vehicleId =>
      sendMessageTo(
        entityId = vehicleId,
        data = TrafficSignalData(
          lightId = getEntityId,
          phase = newPhase,
          timeRemaining = state.phaseDuration
        ),
        eventType = "signal_update"
      )
    }
  }
  
  // 4. REPORTING AND MONITORING
  private def reportPhaseChange(isEmergencyOverride: Boolean = false): Unit = {
    report(
      data = TrafficLightPhaseReport(
        lightId = getEntityId,
        intersectionId = state.intersectionId,
        phase = state.currentPhase,
        phaseStartTick = state.phaseStartTick,
        isEmergencyOverride = isEmergencyOverride
      ),
      label = "phase_change"
    )
  }
  
  private def respondWithStatus(requesterId: String, query: StatusQuery): Unit = {
    val statusResponse = TrafficLightStatusResponse(
      lightId = getEntityId,
      currentPhase = state.currentPhase,
      phaseStartTick = state.phaseStartTick,
      nextPhaseChangeTick = state.phaseStartTick + state.phaseDuration,
      isOperational = true
    )
    
    sendMessageTo(
      entityId = requesterId,
      data = statusResponse,
      eventType = "status_response"
    )
  }
  
  // 5. UTILITY METHODS
  private def findNearbyVehicles(): List[String] = {
    // Implementation would query spatial index or use dependency relationships
    dependencies.values
      .filter(_.classType.contains("Car"))
      .map(_.id)
      .toList
  }
}

// Supporting data classes
case class TrafficLightRegistrationData(lightId: String)
case class TrafficLightShutdownData(lightId: String)
case class EmergencyVehicleRequest(vehicleId: String, priority: Int)
case class PhaseChangeRequest(requestedPhase: String, reason: String)
case class StatusQuery(requesterId: String)
case class TrafficSignalData(lightId: String, phase: String, timeRemaining: Int)
case class TrafficLightPhaseReport(
  lightId: String,
  intersectionId: String,
  phase: String,
  phaseStartTick: Long,
  isEmergencyOverride: Boolean
)
case class TrafficLightStatusResponse(
  lightId: String,
  currentPhase: String,
  phaseStartTick: Long,
  nextPhaseChangeTick: Long,
  isOperational: Boolean
)
```

This example demonstrates:
- **Lifecycle Management**: Proper initialization and cleanup
- **Event Processing**: Handling both spontaneous and interaction events
- **Time Management**: Scheduling future events and phase changes
- **State Management**: Immutable state updates
- **Communication**: Messaging with vehicles and intersection controllers
- **Reporting**: Data collection for analysis
- **Error Handling**: Logging and graceful error management

---

## 📨 **Event System**

### **🔄 Base Event Structure**

```scala
abstract class BaseEvent[D <: BaseEventData](
  lamportTick: Tick = 0,                    // Lamport clock timestamp
  data: D = null,                           // Event payload data
  tick: Tick = Long.MinValue,               // Simulation time tick
  actorRef: ActorRef = null,                // Sender actor reference
  actorRefId: String = null,                // Sender actor ID
  eventType: String = ""                    // Event type identifier
) extends Command
```

### **🎬 Event Categories**

#### **Spontaneous Events**
Events generated internally by actors:

```scala
case class SpontaneousEvent(
  override val lamportTick: Tick,
  override val data: BaseEventData,
  override val tick: Tick,
  eventId: String,                          // Unique event identifier
  actionType: String                        // Type of spontaneous action
) extends BaseEvent[BaseEventData](lamportTick, data, tick)
```

**Usage Example:**
```scala
// Generate vehicle movement event
val moveEvent = SpontaneousEvent(
  lamportTick = getCurrentLamportTick,
  data = VehicleMovementData(newPosition, speed),
  tick = getCurrentTick + 1,
  eventId = "move_001",
  actionType = "vehicle_movement"
)
self ! moveEvent
```

#### **Actor Interaction Events**
Events for communication between actors:

```scala
case class ActorInteractionEvent(
  override val lamportTick: Tick,
  override val data: BaseEventData,
  override val tick: Tick,
  sourceActor: String,                      // Source actor ID
  targetActor: String,                      // Target actor ID
  interactionType: String                   // Type of interaction
) extends BaseEvent[BaseEventData](lamportTick, data, tick)
```

**Usage Example:**
```scala
// Vehicle approaching intersection
val approachEvent = ActorInteractionEvent(
  lamportTick = getCurrentLamportTick,
  data = VehicleApproachData(vehicleId, estimatedArrival),
  tick = getCurrentTick,
  sourceActor = "vehicle_001",
  targetActor = "intersection_001", 
  interactionType = "vehicle_approach"
)
intersectionActor ! approachEvent
```

#### **Control Events**
System-level events for simulation control:

```scala
// Start simulation
case class StartSimulationTimeEvent(
  startTick: Tick,
  actorRef: ActorRef,
  data: Option[StartSimulationTimeData] = None
) extends BaseEvent[DefaultBaseEventData]()

// Register actor with time manager
case class RegisterActorEvent(
  startTick: Tick,
  actorId: String,
  identify: Option[Identify] = None
) extends BaseEvent[DefaultBaseEventData]()

// Schedule future event
case class ScheduleEvent(
  tick: Tick,
  actorRef: String,
  identify: Option[Identify] = None
) extends BaseEvent[DefaultBaseEventData]()
```

#### **Report Events**
Events for data collection and analysis:

```scala
case class ReportEvent(
  override val lamportTick: Tick,
  override val data: BaseEventData,
  override val tick: Tick,
  reportType: ReportTypeEnum,               // Type of report
  metrics: Map[String, Any],                // Collected metrics
  aggregationLevel: String                  // Temporal/spatial aggregation
) extends BaseEvent[BaseEventData](lamportTick, data, tick)
```

---

## ⏰ **Time Management API**

### **🕐 TimeManager**

Controls simulation time progression and event scheduling.

```scala
class TimeManager(
  val simulationDuration: Tick,
  val simulationManager: ActorRef,
  val parentManager: Option[ActorRef]
) extends BaseManager[DefaultState]
```

#### **Core Methods**

```scala
// Register actor for time management
def registerActor(event: RegisterActorEvent): Unit

// Schedule event for future execution
def scheduleEvent(event: ScheduleEvent): Unit

// Start simulation time progression
def startSimulation(event: StartSimulationTimeEvent): Unit

// Pause simulation
def pauseSimulation(): Unit

// Resume simulation
def resumeSimulation(): Unit

// Stop simulation
def stopSimulation(): Unit

// Get current simulation time
def getCurrentTick: Tick

// Check if simulation is running
def isRunning: Boolean
```

#### **Time Synchronization**

```scala
// Synchronize with global time
def syncWithGlobalTime(globalTick: Tick): Unit

// Report local time to global manager
def reportLocalTime(tick: Tick, hasScheduled: Boolean): Unit

// Handle time drift correction
def correctTimeDrift(drift: Tick): Unit
```

### **⏱️ Time Utilities**

```scala
object TimeUtil {
  // Convert time units
  def secondsToTicks(seconds: Double, timeStep: Double): Tick
  def ticksToSeconds(ticks: Tick, timeStep: Double): Double
  def minutesToTicks(minutes: Double, timeStep: Double): Tick
  
  // Time formatting
  def formatTick(tick: Tick, timeUnit: String): String
  def parseTimeString(timeStr: String, timeUnit: String): Tick
  
  // Time range operations
  def createTimeRange(start: Tick, end: Tick, step: Tick): Range
  def isTickInRange(tick: Tick, start: Tick, end: Tick): Boolean
}
```

---

## 📊 **Reporting API**

### **📈 Report Manager**

```scala
class ReportManager(
  timeManager: ActorRef,
  simulationManager: ActorRef,
  startRealTime: LocalDateTime
) extends BaseManager[DefaultState]
```

#### **Report Types**

```scala
enum ReportTypeEnum {
  case TRAFFIC_FLOW
  case VEHICLE_TRAJECTORY  
  case INTERSECTION_PERFORMANCE
  case NETWORK_STATISTICS
  case SYSTEM_PERFORMANCE
  case CUSTOM
}
```

#### **Report Configuration**

```scala
case class ReportConfig(
  reportType: ReportTypeEnum,
  enabled: Boolean = true,
  frequency: ReportFrequency,               // Collection frequency
  outputFormat: Set[OutputFormat],          // Output formats (JSON, CSV, etc.)
  filters: Map[String, Any] = Map.empty,    // Data filters
  aggregation: AggregationConfig            // Aggregation settings
)

enum ReportFrequency {
  case EVERY_TICK
  case EVERY_N_TICKS(n: Int)
  case PERIODIC(intervalSeconds: Int)
  case ON_EVENT(eventType: String)
  case SIMULATION_END
}

enum OutputFormat {
  case JSON, CSV, XML, PARQUET, AVRO
}
```

#### **Custom Reports**

```scala
abstract class BaseReportData[T <: BaseState](
  selfProxy: ActorRef,
  startRealTime: LocalDateTime
) extends BaseActor[T] {
  
  // Process report event
  def onReport(event: ReportEvent): Unit
  
  // Export collected data
  def exportData(format: OutputFormat, destination: String): Unit
  
  // Aggregate data over time window
  def aggregateData(timeWindow: TimeWindow): Map[String, Any]
}

// Example custom reporter
class CustomTrafficReporter(selfProxy: ActorRef, startRealTime: LocalDateTime)
  extends BaseReportData[DefaultState](selfProxy, startRealTime) {
  
  override def onReport(event: ReportEvent): Unit = {
    // Custom data processing logic
    val metrics = extractCustomMetrics(event)
    storeMetrics(metrics)
  }
}
```

### **📊 Data Collection API**

```scala
trait DataCollector {
  // Collect actor state data
  def collectActorState(actorId: String): Map[String, Any]
  
  // Collect event data
  def collectEventData(event: BaseEvent[_]): Map[String, Any]
  
  // Collect system metrics
  def collectSystemMetrics(): SystemMetrics
  
  // Collect network performance
  def collectNetworkMetrics(): NetworkMetrics
}

case class SystemMetrics(
  cpuUsage: Double,
  memoryUsage: Long,
  actorCount: Int,
  eventThroughput: Double,
  timestamp: LocalDateTime
)

case class NetworkMetrics(
  messagesSent: Long,
  messagesReceived: Long,
  bytesTransferred: Long,
  networkLatency: Double,
  timestamp: LocalDateTime
)
```

---

## 🚗 **Traffic-Specific APIs**

### **🚙 Vehicle Actor**

```scala
class VehicleActor(properties: Properties) 
  extends BaseActor[VehicleState](properties) {
  
  // Vehicle behavior methods
  def accelerate(acceleration: Double): Unit
  def decelerate(deceleration: Double): Unit
  def changeSpeed(newSpeed: Double): Unit
  def changeLane(targetLane: Int): Unit
  def followRoute(route: Route): Unit
  
  // Sensor and perception
  def perceiveEnvironment(): EnvironmentState
  def detectNearbyVehicles(range: Double): Set[VehicleInfo]
  def checkTrafficSignals(): SignalState
  
  // Decision making
  def makeDecision(environment: EnvironmentState): VehicleAction
  def planRoute(destination: Location): Route
  def evaluateGaps(targetLane: Int): LaneGap
}

case class VehicleState(
  position: Position,
  speed: Double,
  acceleration: Double,
  lane: Int,
  route: Route,
  destination: Location,
  vehicleType: VehicleType
) extends BaseState
```

### **🚦 Traffic Signal Actor**

```scala
class TrafficSignalActor(properties: Properties)
  extends BaseActor[SignalState](properties) {
  
  // Signal control methods
  def changePhase(newPhase: SignalPhase): Unit
  def extendGreen(extensionTime: Int): Unit
  def activateAllRed(): Unit
  def enableTransitPriority(transitVehicle: String): Unit
  
  // Timing management
  def updateTiming(newTiming: SignalTiming): Unit
  def getNextPhaseTime(): Tick
  def getRemainingGreenTime(): Int
  
  // Vehicle detection
  def detectApproachingVehicles(): Set[VehicleInfo]
  def countVehiclesInQueue(approach: String): Int
  def measureDelay(approach: String): Double
}

case class SignalState(
  currentPhase: SignalPhase,
  phaseStartTime: Tick,
  timing: SignalTiming,
  vehicleDetections: Map[String, Set[String]]
) extends BaseState

case class SignalTiming(
  cycleTime: Int,
  phases: List[PhaseConfig]
)

case class PhaseConfig(
  id: String,
  greenTime: Int,
  yellowTime: Int,
  allRedTime: Int,
  movements: Set[Movement]
)
```

### **🏗️ Infrastructure Actor**

```scala
class LinkActor(properties: Properties)
  extends BaseActor[LinkState](properties) {
  
  // Traffic flow methods
  def addVehicle(vehicle: VehicleInfo): Unit
  def removeVehicle(vehicleId: String): Unit
  def updateFlow(newFlow: Double): Unit
  def calculateDensity(): Double
  def calculateSpeed(): Double
  
  // Capacity management
  def getCurrentCapacity(): Double
  def setTemporaryCapacity(capacity: Double, duration: Int): Unit
  def addIncident(incident: IncidentInfo): Unit
  def removeIncident(incidentId: String): Unit
  
  // Performance metrics
  def getTravelTime(): Double
  def getDelay(): Double
  def getLevelOfService(): LevelOfService
}

case class LinkState(
  vehicles: Set[VehicleInfo],
  capacity: Double,
  freeFlowSpeed: Double,
  currentFlow: Double,
  incidents: Set[IncidentInfo]
) extends BaseState
```

---

## 🔌 **Extension APIs**

### **🎭 Custom Actor Development**

```scala
// Template for custom actor
abstract class CustomActor[T <: BaseState](properties: Properties)
  extends BaseActor[T](properties) {
  
  // Implement required methods
  override def onInitialize(event: InitializeEvent): Unit = {
    // Custom initialization logic
  }
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    event.actionType match {
      case "custom_action" => handleCustomAction(event)
      case _ => super.actSpontaneous(event)
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.interactionType match {
      case "custom_interaction" => handleCustomInteraction(event)
      case _ => super.actInteractWith(event)
    }
  }
  
  // Custom methods
  def handleCustomAction(event: SpontaneousEvent): Unit
  def handleCustomInteraction(event: ActorInteractionEvent): Unit
}

// Register custom actor
object CustomActorFactory extends ActorFactory {
  def createActor(actorType: String, properties: Properties): Props = {
    actorType match {
      case "com.yourcompany.CustomActor" => Props(classOf[CustomActor], properties)
      case _ => throw new IllegalArgumentException(s"Unknown actor type: $actorType")
    }
  }
}
```

### **📊 Plugin System**

```scala
trait SimulationPlugin {
  // Plugin metadata
  def name: String
  def version: String
  def description: String
  def dependencies: Set[String]
  
  // Lifecycle methods
  def initialize(context: SimulationContext): Unit
  def onSimulationStart(): Unit
  def onSimulationEnd(): Unit
  def shutdown(): Unit
  
  // Extension points
  def getCustomActors(): Map[String, Class[_ <: BaseActor[_]]]
  def getCustomReporters(): Map[ReportTypeEnum, Class[_ <: BaseReportData[_]]]
  def getCustomEvents(): Map[String, Class[_ <: BaseEvent[_]]]
  
  // Configuration
  def getConfigSchema(): ConfigSchema
  def validateConfig(config: Config): ValidationResult
}

// Plugin registration
class PluginManager {
  def registerPlugin(plugin: SimulationPlugin): Unit
  def unregisterPlugin(pluginName: String): Unit
  def getPlugin(name: String): Option[SimulationPlugin]
  def listPlugins(): Set[SimulationPlugin]
}
```

### **🔧 Configuration Extension**

```scala
trait ConfigurationProvider {
  def getConfigSchema(): ConfigSchema
  def loadConfiguration(source: String): Config
  def validateConfiguration(config: Config): ValidationResult
  def mergeConfigurations(configs: List[Config]): Config
}

case class ConfigSchema(
  fields: Map[String, FieldDefinition],
  required: Set[String],
  additionalProperties: Boolean = false
)

case class FieldDefinition(
  fieldType: FieldType,
  description: String,
  defaultValue: Option[Any] = None,
  constraints: Set[Constraint] = Set.empty
)

enum FieldType {
  case STRING, INTEGER, DOUBLE, BOOLEAN, ARRAY, OBJECT
}
```

---

## 🌐 **REST API**

### **🔗 Simulation Control Endpoints**

```scala
// Start simulation
POST /api/v1/simulations
Content-Type: application/json
{
  "configurationPath": "path/to/config.json",
  "name": "My Simulation",
  "description": "Test simulation"
}

// Get simulation status
GET /api/v1/simulations/{simulationId}

// Stop simulation
DELETE /api/v1/simulations/{simulationId}

// Pause/Resume simulation
PUT /api/v1/simulations/{simulationId}/control
{
  "action": "pause" | "resume"
}
```

### **📊 Data Access Endpoints**

```scala
// Get simulation results
GET /api/v1/simulations/{simulationId}/results
Query Parameters:
  - format: json|csv|xml
  - startTime: ISO timestamp
  - endTime: ISO timestamp
  - actorTypes: comma-separated list
  - aggregation: none|minute|hour

// Get real-time metrics
GET /api/v1/simulations/{simulationId}/metrics
WebSocket: /ws/simulations/{simulationId}/metrics

// Export data
POST /api/v1/simulations/{simulationId}/export
{
  "format": "csv",
  "destination": "file|database|api",
  "filters": {
    "actorTypes": ["vehicle"],
    "timeRange": ["2025-01-27T08:00:00", "2025-01-27T18:00:00"]
  }
}
```

### **⚙️ Configuration Endpoints**

```scala
// Get configuration schema
GET /api/v1/schema/simulation

// Validate configuration
POST /api/v1/validate/configuration
Content-Type: application/json
{
  "configuration": { /* simulation config */ }
}

// Get available actor types
GET /api/v1/actors/types

// Get actor configuration schema
GET /api/v1/actors/{actorType}/schema
```

---

## 🧪 **Testing APIs**

### **🔬 Unit Testing**

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import akka.testkit.{TestKit, TestProbe}

class VehicleActorSpec extends AnyFlatSpec with Matchers with TestKit {
  
  "VehicleActor" should "accelerate when receiving acceleration event" in {
    val vehicleActor = system.actorOf(VehicleActor.props(testProperties))
    val probe = TestProbe()
    
    // Send acceleration event
    vehicleActor ! SpontaneousEvent(
      tick = 100,
      actionType = "accelerate",
      data = AccelerationData(2.5)
    )
    
    // Verify state change
    vehicleActor ! GetStateEvent()
    val state = probe.expectMsgType[VehicleState]
    state.acceleration should be(2.5)
  }
}
```

### **🎯 Integration Testing**

```scala
class SimulationIntegrationSpec extends AnyFlatSpec with Matchers {
  
  "Complete simulation" should "run successfully with valid configuration" in {
    val config = SimulationConfig.fromFile("test-config.json")
    val simulation = new SimulationRunner(config)
    
    val result = simulation.run()
    
    result.status should be(SimulationStatus.COMPLETED)
    result.duration should be > 0L
    result.events.size should be > 0
  }
}
```

### **📈 Performance Testing**

```scala
class PerformanceSpec extends AnyFlatSpec with Matchers {
  
  "Large simulation" should "handle 100k actors efficiently" in {
    val config = createLargeSimulationConfig(actorCount = 100000)
    val simulation = new SimulationRunner(config)
    
    val startTime = System.currentTimeMillis()
    val result = simulation.run()
    val duration = System.currentTimeMillis() - startTime
    
    duration should be < 60000L // Should complete in under 60 seconds
    result.memoryUsage should be < (8L * 1024 * 1024 * 1024) // Under 8GB
  }
}
```

---

## 📚 **Code Examples**

### **🚗 Simple Vehicle Implementation**

```scala
class SimpleVehicle(properties: Properties) 
  extends BaseActor[VehicleState](properties) {
  
  override def onInitialize(event: InitializeEvent): Unit = {
    val initialState = VehicleState(
      position = Position(0, 0),
      speed = 0.0,
      acceleration = 0.0,
      lane = 1,
      route = loadRouteFromData(),
      destination = loadDestinationFromData(),
      vehicleType = VehicleType.CAR
    )
    setState(initialState)
    
    // Schedule first movement
    scheduleEvent(getCurrentTick + 1, SpontaneousEvent(
      tick = getCurrentTick + 1,
      actionType = "move",
      data = EmptyEventData()
    ))
  }
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    event.actionType match {
      case "move" => handleMovement()
      case "accelerate" => handleAcceleration(event)
      case "brake" => handleBraking(event)
      case _ => logWarn(s"Unknown action: ${event.actionType}")
    }
  }
  
  private def handleMovement(): Unit = {
    val currentState = getState
    val newPosition = calculateNewPosition(currentState)
    
    setState(currentState.copy(position = newPosition))
    
    // Report position to traffic analysis
    reportEvent(ReportEvent(
      tick = getCurrentTick,
      reportType = ReportTypeEnum.VEHICLE_TRAJECTORY,
      data = VehiclePositionData(entityId, newPosition),
      lamportTick = getCurrentLamportTick
    ))
    
    // Schedule next movement
    scheduleEvent(getCurrentTick + 1, SpontaneousEvent(
      tick = getCurrentTick + 1,
      actionType = "move",
      data = EmptyEventData()
    ))
  }
}
```

---

**📖 This API reference provides comprehensive documentation for developing with the Hyperbolic Time Chamber framework. Use these APIs to create custom actors, implement domain-specific behaviors, and extend the simulation capabilities.**

For practical examples and implementation patterns, see the [Developer Guide](DEVELOPER_GUIDE.md) and [examples directory](examples/).