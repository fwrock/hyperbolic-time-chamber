# 🔧 Developer Guide

A comprehensive guide for developers who want to contribute to, extend, or build upon the Hyperbolic Time Chamber simulation framework. This guide covers development setup, architecture patterns, testing strategies, and contribution guidelines.

---

## 🎯 **Development Overview**

### **🏗️ Development Philosophy**
- **Actor-First Design**: Everything is an actor with clear responsibilities
- **Event-Driven Architecture**: Loose coupling through message passing
- **Immutable State**: Functional programming principles for reliability
- **Test-Driven Development**: Comprehensive testing at all levels
- **Documentation-First**: Code is self-documenting with extensive comments

### **🛠️ Technology Stack**
- **Language**: Scala 3.3.5 with modern functional programming features
- **Actor System**: Apache Pekko (Akka fork) for distributed computing
- **Build Tool**: SBT with multi-module project structure
- **Testing**: ScalaTest with property-based testing
- **Serialization**: Protocol Buffers and Jackson for efficiency
- **Storage**: Cassandra for time-series data, Redis for caching
- **Containerization**: Docker with optimized multi-stage builds

---

## 🔧 **Development Environment Setup**

### **📋 Prerequisites**

```bash
```bash
# Required tools
java --version    # JDK 21+ (OpenJDK recommended)
sbt --version     # SBT 1.9.0+
docker --version  # Docker 20.10+
git --version     # Git 2.30+

# IDE recommendations
# - IntelliJ IDEA with Scala plugin
# - VS Code with Metals extension
# - Vim/Neovim with coc-metals
```
```

### **🏗️ Project Setup**

```bash
# Clone repository with submodules
git clone --recursive https://github.com/your-repo/hyperbolic-time-chamber.git
cd hyperbolic-time-chamber

# Setup development environment
./dev-setup.sh

# Build and run (recommended approach)
./build-and-run.sh

# Or verify setup manually
sbt test
docker compose up -d cassandra redis  # Optional: prefer JSON for performance
./diagnose.sh
```

### **📁 Project Structure**

```
hyperbolic-time-chamber/
├── build.sbt                          # Main build configuration
├── project/                           # SBT project configuration
│   ├── build.properties              # SBT version
│   ├── plugins.sbt                   # SBT plugins
│   └── Dependencies.scala            # Dependency management
├── src/
│   ├── main/
│   │   ├── scala/                     # Main source code
│   │   │   ├── core/                  # Core simulation engine
│   │   │   │   ├── actor/             # Actor implementations
│   │   │   │   ├── entity/            # Domain entities
│   │   │   │   ├── util/              # Utility classes
│   │   │   │   └── HyperbolicTimeChamber.scala
│   │   │   ├── model/                 # Domain models
│   │   │   │   ├── mobility/          # Transportation models
│   │   │   │   └── infrastructure/    # Infrastructure models
│   │   │   └── system/                # System integration
│   │   ├── protobuf/                  # Protocol buffer definitions
│   │   │   ├── core/                  # Core system messages
│   │   │   ├── model/                 # Domain-specific messages
│   │   │   └── system/                # System messages
│   │   └── resources/                 # Configuration files
│   │       ├── application.conf       # Main configuration
│   │       ├── application-dev.conf   # Development overrides
│   │       └── logback.xml           # Logging configuration
│   └── test/
│       ├── scala/                     # Test source code
│       │   ├── unit/                  # Unit tests
│       │   ├── integration/           # Integration tests
│       │   └── performance/           # Performance tests
│       └── resources/                 # Test resources
├── docs/                              # Documentation
├── scripts/                           # Development and deployment scripts
├── examples/                          # Example scenarios
└── docker/                           # Docker configurations
```

---

## 🚀 **Development Workflow**

### **🔧 Build and Run**

The easiest way to build and run the application:

```bash
# Complete build, assembly, and Docker image creation
./build-and-run.sh

# This script will:
# 1. Clean the project
# 2. Generate the assembly JAR
# 3. Create Docker image
# 4. Start the simulation
```

### **⚡ Performance Tips**

- **Prefer JSON over Cassandra**: Use pre-configured JSON files instead of Cassandra database connections for better simulation performance
- **Cassandra Performance**: Database connections can slow down simulations significantly
- **GPS Dependencies**: GPS actors are optional and can be omitted for better performance

### **🔄 Manual Development Commands**

```bash
# Compile only
sbt compile

# Run tests
sbt test

# Clean and compile
sbt clean compile

# Create assembly JAR
sbt assembly

# Run specific test
sbt "testOnly *VehicleActorSpec"
```

---

## 🏗️ **Architecture Patterns**

### **🎭 Actor Design Patterns**

#### **State Management Pattern**
```scala
// Immutable state with copy semantics
case class VehicleState(
  position: Position,
  speed: Double,
  route: Route,
  lastUpdate: Tick
) extends BaseState {
  
  // State transitions through pure functions
  def updatePosition(newPosition: Position, tick: Tick): VehicleState =
    copy(position = newPosition, lastUpdate = tick)
    
  def updateSpeed(newSpeed: Double, tick: Tick): VehicleState =
    copy(speed = newSpeed, lastUpdate = tick)
}

class VehicleActor(properties: Properties) extends BaseActor[VehicleState](properties) {
  
  // State updates through immutable copies
  private def updateState(updater: VehicleState => VehicleState): Unit = {
    val newState = updater(getState)
    setState(newState)
    save(createStateUpdateEvent(newState))
  }
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    event.actionType match {
      case "move" => 
        updateState(_.updatePosition(calculateNewPosition(), getCurrentTick))
      case "accelerate" =>
        updateState(_.updateSpeed(calculateNewSpeed(), getCurrentTick))
    }
  }
}
```

#### **Event Sourcing Pattern**
```scala
// Events as immutable facts
sealed trait VehicleEvent extends BaseEvent[BaseEventData]

case class VehicleMovedEvent(
  vehicleId: String,
  oldPosition: Position,
  newPosition: Position,
  speed: Double,
  tick: Tick
) extends VehicleEvent

case class VehicleAcceleratedEvent(
  vehicleId: String,
  oldSpeed: Double,
  newSpeed: Double,
  acceleration: Double,
  tick: Tick
) extends VehicleEvent

// Event replay for state reconstruction
class VehicleActor(properties: Properties) extends BaseActor[VehicleState](properties) {
  
  def applyEvent(event: VehicleEvent): VehicleState = {
    val currentState = getState
    event match {
      case VehicleMovedEvent(_, _, newPosition, speed, tick) =>
        currentState.copy(position = newPosition, speed = speed, lastUpdate = tick)
      case VehicleAcceleratedEvent(_, _, newSpeed, _, tick) =>
        currentState.copy(speed = newSpeed, lastUpdate = tick)
    }
  }
  
  // Persist event and apply state change
  private def persistAndApply(event: VehicleEvent): Unit = {
    persistEvent(event)  // Store for replay
    setState(applyEvent(event))  // Apply to current state
  }
}
```

#### **Supervision Strategy Pattern**
```scala
class VehicleManagerActor extends BaseActor[ManagerState] {
  
  // Define supervision strategy for child actors
  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 3,
    withinTimeRange = 1.minute
  ) {
    case _: VehicleException => Restart  // Restart individual vehicle
    case _: NetworkException => Resume   // Continue with cached data
    case _: ConfigException => Stop      // Stop invalid configuration
    case _ => Escalate                   // Escalate unknown errors
  }
  
  override def onInitialize(event: InitializeEvent): Unit = {
    // Create supervised child actors
    val vehicleActors = createVehicleActors()
    vehicleActors.foreach(context.watch)  // Monitor for termination
  }
  
  // Handle child actor termination
  override def handleTerminated(terminated: Terminated): Unit = {
    logWarn(s"Vehicle actor terminated: ${terminated.actor}")
    // Implement recovery strategy (restart, replace, etc.)
    replaceTerminatedActor(terminated.actor)
  }
}
```

### **🔄 Communication Patterns**

#### **Request-Response Pattern**
```scala
// Request message with response promise
case class RouteCalculationRequest(
  origin: Position,
  destination: Position,
  constraints: RouteConstraints,
  replyTo: ActorRef
) extends BaseEvent[BaseEventData]

case class RouteCalculationResponse(
  requestId: String,
  route: Option[Route],
  calculationTime: Long,
  error: Option[String]
) extends BaseEvent[BaseEventData]

// Async request handling
class RouteCalculatorActor extends BaseActor[CalculatorState] {
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event match {
      case req: RouteCalculationRequest =>
        calculateRouteAsync(req).onComplete {
          case Success(route) =>
            req.replyTo ! RouteCalculationResponse(
              requestId = req.id,
              route = Some(route),
              calculationTime = System.currentTimeMillis() - req.timestamp
            )
          case Failure(error) =>
            req.replyTo ! RouteCalculationResponse(
              requestId = req.id,
              route = None,
              error = Some(error.getMessage)
            )
        }
    }
  }
}
```

#### **Publish-Subscribe Pattern**
```scala
// Event bus for decoupled communication
class TrafficEventBus extends EventBus[TrafficEvent, ActorRef, String] {
  
  override def mapSize: Int = 128
  
  override def classify(event: TrafficEvent): String = event.eventType
  
  override def publish(event: TrafficEvent, subscriber: ActorRef): Unit =
    subscriber ! event
    
  override def compareSubscribers(a: ActorRef, b: ActorRef): Int =
    a.compareTo(b)
}

// Publisher
class TrafficSensorActor extends BaseActor[SensorState] {
  
  private val eventBus = TrafficEventBus.getInstance()
  
  private def publishTrafficEvent(event: TrafficEvent): Unit = {
    eventBus.publish(event)
    logInfo(s"Published event: ${event.eventType}")
  }
}

// Subscriber
class TrafficAnalyzerActor extends BaseActor[AnalyzerState] {
  
  override def onInitialize(event: InitializeEvent): Unit = {
    val eventBus = TrafficEventBus.getInstance()
    eventBus.subscribe(self, "vehicle_detected")
    eventBus.subscribe(self, "congestion_detected")
  }
}
```

---

## 🧪 **Testing Strategy**

### **🔬 Unit Testing**

#### **Actor Testing with TestKit**
```scala
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class VehicleActorSpec extends TestKit(ActorSystem("VehicleActorSpec"))
  with ImplicitSender
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "VehicleActor" must {
    
    "initialize with correct state" in {
      val vehicleActor = system.actorOf(VehicleActor.props(testProperties))
      
      vehicleActor ! GetStateRequest()
      
      val state = expectMsgType[VehicleState]
      state.position should equal(Position(0, 0))
      state.speed should equal(0.0)
    }
    
    "update position on move event" in {
      val vehicleActor = system.actorOf(VehicleActor.props(testProperties))
      val probe = TestProbe()
      
      vehicleActor ! SpontaneousEvent(
        tick = 100,
        actionType = "move",
        data = MoveEventData(Position(10, 10))
      )
      
      vehicleActor ! GetStateRequest()
      val state = expectMsgType[VehicleState]
      state.position should equal(Position(10, 10))
    }
    
    "handle interaction with traffic signal" in {
      val vehicleActor = system.actorOf(VehicleActor.props(testProperties))
      val signalProbe = TestProbe()
      
      vehicleActor ! ActorInteractionEvent(
        sourceActor = signalProbe.ref.path.toString,
        targetActor = vehicleActor.path.toString,
        interactionType = "signal_changed",
        data = SignalStateData(SignalPhase.RED)
      )
      
      vehicleActor ! GetStateRequest()
      val state = expectMsgType[VehicleState]
      state.speed should equal(0.0)  // Should stop for red signal
    }
  }
}
```

#### **Property-Based Testing**
```scala
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class VehiclePhysicsSpec extends AnyFlatSpec with ScalaCheckPropertyChecks {
  
  // Generate test data
  val positionGen = for {
    x <- Gen.choose(-1000.0, 1000.0)
    y <- Gen.choose(-1000.0, 1000.0)
  } yield Position(x, y)
  
  val speedGen = Gen.choose(0.0, 100.0)
  val accelerationGen = Gen.choose(-10.0, 10.0)
  
  "Vehicle physics" should "maintain conservation of energy" in {
    forAll(speedGen, accelerationGen, Gen.choose(1.0, 10.0)) { 
      (initialSpeed, acceleration, timeStep) =>
        
        val finalSpeed = VehiclePhysics.calculateSpeed(
          initialSpeed, acceleration, timeStep
        )
        
        // Speed should not be negative
        finalSpeed should be >= 0.0
        
        // Speed change should follow physics
        val expectedSpeedChange = acceleration * timeStep
        val actualSpeedChange = finalSpeed - initialSpeed
        
        if (initialSpeed + expectedSpeedChange >= 0) {
          actualSpeedChange should equal(expectedSpeedChange +- 0.001)
        }
    }
  }
}
```

### **🔗 Integration Testing**

#### **Multi-Actor Integration**
```scala
class IntersectionSimulationSpec extends TestKit(ActorSystem("IntersectionTest"))
  with AnyWordSpecLike
  with Matchers {
  
  "Intersection simulation" must {
    
    "coordinate traffic signals and vehicles" in {
      // Create test scenario
      val intersection = system.actorOf(IntersectionActor.props(testProperties))
      val signal = system.actorOf(TrafficSignalActor.props(testProperties))
      val vehicles = (1 to 10).map { i =>
        system.actorOf(VehicleActor.props(testProperties.copy(entityId = s"vehicle_$i")))
      }
      
      // Setup initial state
      intersection ! InitializeEvent(intersectionConfig)
      signal ! InitializeEvent(signalConfig)
      vehicles.foreach(_ ! InitializeEvent(vehicleConfig))
      
      // Run simulation for specific duration
      val timeManager = TestProbe()
      intersection ! StartSimulationTimeEvent(startTick = 0, timeManager.ref)
      
      // Verify behavior
      timeManager.expectMsgType[SimulationCompleteEvent](30.seconds)
      
      // Check final state
      intersection ! GetMetricsRequest()
      val metrics = expectMsgType[IntersectionMetrics]
      metrics.totalVehiclesPassed should be > 0
      metrics.averageDelay should be < 60.0  // seconds
    }
  }
}
```

#### **Database Integration Testing**
```scala
class CassandraIntegrationSpec extends AnyFlatSpec with Matchers {
  
  private var cassandraContainer: CassandraContainer = _
  private var session: Session = _
  
  override def beforeAll(): Unit = {
    cassandraContainer = new CassandraContainer("cassandra:latest")
    cassandraContainer.start()
    
    session = CassandraConnector.connect(
      contactPoints = Seq(cassandraContainer.getContactPoint),
      keyspace = "test_htc"
    )
    
    // Initialize test schema
    CassandraSchemaManager.createKeyspace(session)
    CassandraSchemaManager.createTables(session)
  }
  
  override def afterAll(): Unit = {
    session.close()
    cassandraContainer.stop()
  }
  
  "ReportManager" should "persist simulation data to Cassandra" in {
    val reportManager = system.actorOf(ReportManager.props(testConfig))
    
    val testEvent = ReportEvent(
      tick = 100,
      reportType = ReportTypeEnum.VEHICLE_TRAJECTORY,
      data = VehicleTrajectoryData("vehicle_001", Position(10, 20), 30.0),
      lamportTick = 100
    )
    
    reportManager ! testEvent
    
    // Verify data persistence
    eventually {
      val result = session.execute(
        "SELECT * FROM vehicle_trajectories WHERE vehicle_id = 'vehicle_001'"
      )
      result.asScala should have size 1
    }
  }
}
```

### **⚡ Performance Testing**

#### **Load Testing**
```scala
class PerformanceSpec extends AnyFlatSpec with Matchers {
  
  "Simulation" should "handle large number of actors efficiently" in {
    val config = createLargeSimulationConfig(
      vehicleCount = 100000,
      intersectionCount = 1000,
      duration = 3600
    )
    
    val startTime = System.nanoTime()
    val startMemory = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
    
    val simulation = new SimulationRunner(config)
    val result = simulation.run()
    
    val endTime = System.nanoTime()
    val endMemory = Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory
    
    val executionTime = (endTime - startTime) / 1e9  // seconds
    val memoryUsed = (endMemory - startMemory) / (1024 * 1024)  // MB
    
    // Performance assertions
    executionTime should be < 300.0  // Should complete in under 5 minutes
    memoryUsed should be < 8192  // Should use less than 8GB
    result.eventsProcessed should be > 1000000  // Should process significant events
    result.throughput should be > 1000  // Events per second
  }
}
```

#### **Memory Profiling**
```scala
class MemoryProfileSpec extends AnyFlatSpec with Matchers {
  
  "Actor system" should "maintain stable memory usage" in {
    val config = createRepeatedSimulationConfig(iterations = 10)
    val memoryUsages = mutable.ArrayBuffer[Long]()
    
    (1 to 10).foreach { iteration =>
      System.gc()  // Force garbage collection
      Thread.sleep(1000)  // Allow GC to complete
      
      val beforeMemory = getUsedMemory()
      
      val simulation = new SimulationRunner(config)
      simulation.run()
      
      val afterMemory = getUsedMemory()
      memoryUsages += afterMemory - beforeMemory
      
      logInfo(s"Iteration $iteration memory usage: ${afterMemory - beforeMemory} bytes")
    }
    
    // Check for memory leaks
    val averageEarly = memoryUsages.take(3).sum / 3
    val averageLate = memoryUsages.takeRight(3).sum / 3
    val memoryGrowth = (averageLate - averageEarly) / averageEarly.toDouble
    
    memoryGrowth should be < 0.1  // Less than 10% growth indicates no major leaks
  }
}
```

---

## 🔧 **Development Tools**

### **🛠️ Build Configuration**

#### **Multi-Module SBT Setup**
```scala
// build.sbt
ThisBuild / version := "1.5.0"
ThisBuild / scalaVersion := "3.3.5"
ThisBuild / organization := "org.interscity.htc"

// Common settings
lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen"
  ),
  javacOptions ++= Seq(
    "-source", "11",
    "-target", "11"
  )
)

// Core module
lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "htc-core",
    libraryDependencies ++= Dependencies.coreDependencies
  )

// Traffic models module
lazy val traffic = (project in file("modules/traffic"))
  .settings(commonSettings)
  .settings(
    name := "htc-traffic",
    libraryDependencies ++= Dependencies.trafficDependencies
  )
  .dependsOn(core % "compile->compile;test->test")

// Infrastructure models module
lazy val infrastructure = (project in file("modules/infrastructure"))
  .settings(commonSettings)
  .settings(
    name := "htc-infrastructure",
    libraryDependencies ++= Dependencies.infrastructureDependencies
  )
  .dependsOn(core % "compile->compile;test->test")

// Main application
lazy val app = (project in file("."))
  .settings(commonSettings)
  .settings(
    name := "hyperbolic-time-chamber",
    libraryDependencies ++= Dependencies.appDependencies
  )
  .dependsOn(core, traffic, infrastructure)
  .aggregate(core, traffic, infrastructure)
```

#### **Dependency Management**
```scala
// project/Dependencies.scala
import sbt._

object Dependencies {
  
  // Versions
  val pekkoVersion = "1.1.5"
  val pekkoHttpVersion = "1.2.0"
  val jacksonVersion = "2.19.2"
  val cassandraVersion = "1.1.0"
  val scalaTestVersion = "3.2.18"
  
  // Core dependencies
  val coreDependencies = Seq(
    "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.5.18"
  )
  
  // Test dependencies
  val testDependencies = Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.scalatestplus" %% "scalacheck-1-17" % "3.2.18.0" % Test,
    "org.testcontainers" % "cassandra" % "1.19.6" % Test
  )
}
```

### **🔍 Code Quality Tools**

#### **ScalaFmt Configuration**
```hocon
# .scalafmt.conf
version = "3.8.2"
runner.dialect = scala3

maxColumn = 100
align.preset = most
align.multiline = false

rewrite.rules = [
  AvoidInfix,
  RedundantBraces,
  RedundantParens,
  SortModifiers,
  PreferCurlyFors
]

rewrite.neverInfix.excludeFilters = [
  until
  to
  by
  eq
  ne
  "should.*"
  "contain.*"
  "must.*"
  in
  ignore
  be
  taggedAs
  thrownBy
  synchronized
  have
  when
  size
  only
  noneOf
  oneElementOf
  noElementsOf
  atLeastOneElementOf
  atMostOneElementOf
  allElementsOf
  inOrderElementsOf
]
```

#### **Scalafix Configuration**
```hocon
# .scalafix.conf
rules = [
  OrganizeImports,
  DisableSyntax,
  NoValInForComprehension,
  ProcedureSyntax,
  RemoveUnused
]

OrganizeImports {
  groupedImports = Merge
  groups = [
    "re:javax?\\."
    "scala."
    "org.apache.pekko."
    "*"
    "org.interscity.htc."
  ]
}

DisableSyntax.noVars = true
DisableSyntax.noThrows = true
DisableSyntax.noNulls = true
DisableSyntax.noReturns = true
```

### **📊 Monitoring and Debugging**

#### **Application Metrics**
```scala
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

class SimulationMetrics(registry: MeterRegistry) {
  
  private val actorCounter = registry.counter("htc.actors.total")
  private val eventCounter = registry.counter("htc.events.processed")
  private val eventTimer = registry.timer("htc.events.processing.time")
  private val memoryGauge = registry.gauge("htc.memory.used", this, _.getUsedMemory)
  
  def incrementActorCount(): Unit = actorCounter.increment()
  def incrementEventCount(): Unit = eventCounter.increment()
  def recordEventProcessingTime(timeNanos: Long): Unit = 
    eventTimer.record(timeNanos, TimeUnit.NANOSECONDS)
  
  private def getUsedMemory: Double = {
    val runtime = Runtime.getRuntime
    (runtime.totalMemory - runtime.freeMemory).toDouble
  }
}

// Integration with Pekko
class MetricsExtension(system: ActorSystem) extends Extension {
  private val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  val metrics = new SimulationMetrics(registry)
  
  // Expose metrics endpoint
  val metricsRoute = path("metrics") {
    get {
      complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, registry.scrape()))
    }
  }
}
```

#### **Distributed Tracing**
```scala
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer

class TracedActor extends BaseActor[ActorState] {
  
  private val tracer: Tracer = OpenTelemetry.globalTracer("htc-simulation")
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    val span = tracer.spanBuilder(s"spontaneous-${event.actionType}")
      .setAttribute("actor.id", entityId)
      .setAttribute("actor.type", getClass.getSimpleName)
      .setAttribute("event.tick", event.tick)
      .startSpan()
    
    try {
      super.actSpontaneous(event)
      span.setStatus(StatusCode.OK)
    } catch {
      case ex: Exception =>
        span.setStatus(StatusCode.ERROR, ex.getMessage)
        span.recordException(ex)
        throw ex
    } finally {
      span.end()
    }
  }
}
```

---

## 🔌 **Extension Development**

### **🎭 Custom Actor Development**

#### **Actor Template**
```scala
package com.yourcompany.htc.actors

import org.interscity.htc.core.actor.BaseActor
import org.interscity.htc.core.entity.state.BaseState
import org.interscity.htc.core.entity.event.{SpontaneousEvent, ActorInteractionEvent}

// Custom state definition
case class CustomActorState(
  customProperty: String,
  lastUpdate: Tick,
  metadata: Map[String, Any] = Map.empty
) extends BaseState {
  override def getStartTick: Tick = lastUpdate
}

// Custom actor implementation
class CustomActor(properties: Properties) 
  extends BaseActor[CustomActorState](properties) {
  
  override def onInitialize(event: InitializeEvent): Unit = {
    val initialState = CustomActorState(
      customProperty = loadFromConfig("custom.property"),
      lastUpdate = getCurrentTick
    )
    setState(initialState)
    
    // Schedule initial event
    scheduleEvent(getCurrentTick + 1, SpontaneousEvent(
      tick = getCurrentTick + 1,
      actionType = "custom_action",
      data = EmptyEventData()
    ))
  }
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    event.actionType match {
      case "custom_action" => handleCustomAction(event)
      case "periodic_task" => handlePeriodicTask(event)
      case _ => logWarn(s"Unknown spontaneous action: ${event.actionType}")
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    event.interactionType match {
      case "custom_interaction" => handleCustomInteraction(event)
      case "data_request" => handleDataRequest(event)
      case _ => logWarn(s"Unknown interaction: ${event.interactionType}")
    }
  }
  
  private def handleCustomAction(event: SpontaneousEvent): Unit = {
    val currentState = getState
    
    // Custom business logic
    val newProperty = performCustomLogic(currentState.customProperty)
    
    // Update state
    setState(currentState.copy(
      customProperty = newProperty,
      lastUpdate = getCurrentTick
    ))
    
    // Schedule next action
    scheduleEvent(getCurrentTick + 10, SpontaneousEvent(
      tick = getCurrentTick + 10,
      actionType = "custom_action",
      data = EmptyEventData()
    ))
  }
  
  private def handleCustomInteraction(event: ActorInteractionEvent): Unit = {
    // Handle interaction with other actors
    event.data match {
      case customData: CustomInteractionData =>
        processInteractionData(customData)
        respondToInteraction(event.sourceActor, createResponse())
      case _ =>
        logError(s"Unexpected interaction data: ${event.data}")
    }
  }
  
  private def performCustomLogic(input: String): String = {
    // Implement your custom business logic here
    s"processed_$input"
  }
}

// Factory for custom actor
object CustomActor {
  def props(properties: Properties): Props = 
    Props(classOf[CustomActor], properties)
}
```

#### **Actor Registration**
```scala
// Register custom actor type
class CustomActorFactory extends ActorFactory {
  override def supportedActorTypes: Set[String] = Set(
    "com.yourcompany.htc.actors.CustomActor"
  )
  
  override def createActor(actorType: String, properties: Properties): Props = {
    actorType match {
      case "com.yourcompany.htc.actors.CustomActor" => 
        CustomActor.props(properties)
      case _ => 
        throw new IllegalArgumentException(s"Unsupported actor type: $actorType")
    }
  }
}

// Register in application.conf
htc {
  actors {
    factories += "com.yourcompany.htc.actors.CustomActorFactory"
  }
}
```

### **📊 Custom Reporter Development**

```scala
package com.yourcompany.htc.reporters

import org.interscity.htc.core.actor.manager.BaseReportData
import org.interscity.htc.core.entity.event.control.report.ReportEvent
import org.interscity.htc.core.enumeration.ReportTypeEnum

class CustomReporter(
  selfProxy: ActorRef,
  startRealTime: LocalDateTime
) extends BaseReportData[DefaultState](selfProxy, startRealTime) {
  
  private val customMetrics = mutable.Map[String, Any]()
  private val dataBuffer = mutable.ArrayBuffer[CustomDataPoint]()
  
  override def onReport(event: ReportEvent): Unit = {
    event.reportType match {
      case ReportTypeEnum.CUSTOM =>
        processCustomReport(event)
      case _ =>
        logWarn(s"Unexpected report type: ${event.reportType}")
    }
  }
  
  private def processCustomReport(event: ReportEvent): Unit = {
    // Extract custom metrics from event
    val dataPoint = extractCustomDataPoint(event)
    dataBuffer += dataPoint
    
    // Update running metrics
    updateCustomMetrics(dataPoint)
    
    // Flush buffer if needed
    if (dataBuffer.size >= 1000) {
      flushDataBuffer()
    }
  }
  
  private def extractCustomDataPoint(event: ReportEvent): CustomDataPoint = {
    CustomDataPoint(
      timestamp = event.tick,
      actorId = event.data.actorId,
      metrics = event.metrics
    )
  }
  
  private def updateCustomMetrics(dataPoint: CustomDataPoint): Unit = {
    // Update aggregated metrics
    customMetrics("total_events") = customMetrics.getOrElse("total_events", 0L).asInstanceOf[Long] + 1
    customMetrics("last_update") = System.currentTimeMillis()
  }
  
  private def flushDataBuffer(): Unit = {
    // Export data to external system
    exportToDatabase(dataBuffer.toSeq)
    dataBuffer.clear()
  }
  
  override def exportData(format: OutputFormat, destination: String): Unit = {
    format match {
      case OutputFormat.JSON =>
        exportAsJson(destination)
      case OutputFormat.CSV =>
        exportAsCsv(destination)
      case _ =>
        throw new UnsupportedOperationException(s"Format $format not supported")
    }
  }
}

case class CustomDataPoint(
  timestamp: Tick,
  actorId: String,
  metrics: Map[String, Any]
)
```

### **🔌 Plugin System**

```scala
package com.yourcompany.htc.plugins

import org.interscity.htc.core.plugin.SimulationPlugin
import org.interscity.htc.core.plugin.SimulationContext

class TrafficOptimizationPlugin extends SimulationPlugin {
  
  override def name: String = "traffic-optimization"
  override def version: String = "1.0.0"
  override def description: String = "AI-based traffic optimization plugin"
  override def dependencies: Set[String] = Set("core", "traffic")
  
  private var optimizationActor: Option[ActorRef] = None
  
  override def initialize(context: SimulationContext): Unit = {
    logInfo("Initializing Traffic Optimization Plugin")
    
    // Create optimization actor
    optimizationActor = Some(context.actorSystem.actorOf(
      TrafficOptimizationActor.props(context.configuration),
      "traffic-optimizer"
    ))
    
    // Register event listeners
    context.eventBus.subscribe(optimizationActor.get, "traffic_congestion")
    context.eventBus.subscribe(optimizationActor.get, "signal_timing")
  }
  
  override def onSimulationStart(): Unit = {
    logInfo("Traffic optimization plugin activated")
    optimizationActor.foreach(_ ! StartOptimizationEvent())
  }
  
  override def onSimulationEnd(): Unit = {
    logInfo("Traffic optimization plugin completing")
    optimizationActor.foreach(_ ! StopOptimizationEvent())
  }
  
  override def shutdown(): Unit = {
    optimizationActor.foreach(context.stop)
    optimizationActor = None
  }
  
  override def getCustomActors(): Map[String, Class[_ <: BaseActor[_]]] = Map(
    "com.yourcompany.htc.actors.TrafficOptimizerActor" -> classOf[TrafficOptimizationActor]
  )
  
  override def getCustomReporters(): Map[ReportTypeEnum, Class[_ <: BaseReportData[_]]] = Map(
    ReportTypeEnum.OPTIMIZATION -> classOf[OptimizationReporter]
  )
  
  override def getConfigSchema(): ConfigSchema = {
    ConfigSchema(
      fields = Map(
        "algorithm" -> FieldDefinition(FieldType.STRING, "Optimization algorithm"),
        "learningRate" -> FieldDefinition(FieldType.DOUBLE, "Learning rate for AI"),
        "updateInterval" -> FieldDefinition(FieldType.INTEGER, "Update interval in seconds")
      ),
      required = Set("algorithm")
    )
  }
}
```

---

## 🚀 **Contribution Guidelines**

### **📋 Contribution Process**

1. **Fork and Clone**
   ```bash
   git fork https://github.com/original-repo/hyperbolic-time-chamber.git
   git clone https://github.com/your-username/hyperbolic-time-chamber.git
   cd hyperbolic-time-chamber
   git remote add upstream https://github.com/original-repo/hyperbolic-time-chamber.git
   ```

2. **Create Feature Branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Development Workflow**
   ```bash
   # Make changes
   sbt test                    # Run tests
   sbt scalafmtAll            # Format code
   sbt "scalafix --rules OrganizeImports"  # Fix imports
   
   # Commit changes
   git add .
   git commit -m "feat: add new traffic optimization algorithm"
   ```

4. **Pull Request**
   ```bash
   git push origin feature/your-feature-name
   # Create PR on GitHub
   ```

### **📝 Code Standards**

#### **Naming Conventions**
```scala
// Classes: PascalCase
class VehicleActor
class TrafficSignalController

// Methods and variables: camelCase
def calculateRoute()
val maxSpeed: Double

// Constants: SCREAMING_SNAKE_CASE
val MAX_SIMULATION_DURATION: Tick = 86400

// Packages: lowercase with dots
package org.interscity.htc.traffic.actors
```

#### **Documentation Standards**
```scala
/**
 * Represents a vehicle in the traffic simulation.
 *
 * This actor models individual vehicle behavior including movement,
 * route following, and interaction with traffic infrastructure.
 *
 * @param properties Actor configuration and dependencies
 * @param vehicleType Type of vehicle (car, truck, bus, etc.)
 * @param initialRoute Initial route assignment
 * 
 * @author Your Name
 * @since 1.5.0
 */
class VehicleActor(
  properties: Properties,
  vehicleType: VehicleType,
  initialRoute: Route
) extends BaseActor[VehicleState](properties) {
  
  /**
   * Calculates the next position based on current speed and direction.
   *
   * @param currentPosition Current vehicle position
   * @param speed Current speed in m/s
   * @param timeStep Time step in seconds
   * @return New position after time step
   */
  def calculateNextPosition(
    currentPosition: Position,
    speed: Double,
    timeStep: Double
  ): Position = {
    // Implementation
  }
}
```

### **🧪 Testing Requirements**

#### **Test Coverage**
- **Unit Tests**: All public methods must have unit tests
- **Integration Tests**: Critical workflows must have integration tests
- **Performance Tests**: Performance-critical components need benchmarks
- **Documentation Tests**: All examples in documentation must be tested

#### **Test Naming**
```scala
class VehicleActorSpec extends AnyWordSpec {
  
  "VehicleActor" when {
    "initialized with valid configuration" should {
      "set initial state correctly" in {
        // Test implementation
      }
      
      "schedule first movement event" in {
        // Test implementation
      }
    }
    
    "receiving movement event" should {
      "update position according to speed" in {
        // Test implementation
      }
      
      "respect maximum speed limits" in {
        // Test implementation
      }
    }
    
    "interacting with traffic signals" should {
      "stop on red signal" in {
        // Test implementation
      }
      
      "proceed on green signal" in {
        // Test implementation
      }
    }
  }
}
```

### **🔍 Code Review Process**

#### **Review Checklist**
- [ ] Code follows established patterns and conventions
- [ ] All tests pass and coverage is adequate
- [ ] Documentation is complete and accurate
- [ ] Performance implications are considered
- [ ] Breaking changes are properly documented
- [ ] Security implications are reviewed
- [ ] Error handling is comprehensive

#### **Review Guidelines**
- **Be Constructive**: Focus on code improvement, not criticism
- **Be Specific**: Provide specific suggestions, not vague feedback
- **Consider Alternatives**: Suggest alternative approaches when appropriate
- **Check Scalability**: Consider how changes affect large-scale simulations
- **Verify Documentation**: Ensure all public APIs are documented

---

## 📚 **Development Resources**

### **📖 Learning Materials**
- **Scala Documentation**: Official Scala 3 documentation
- **Pekko Documentation**: Apache Pekko actor system guide
- **Functional Programming**: "Functional Programming in Scala" by Chiusano & Bjarnason
- **Actor Model**: "Reactive Design Patterns" by Kuhn, Hanafee & Allen

### **🛠️ Development Tools**
- **IDE Setup**: IntelliJ IDEA with Scala plugin configuration
- **SBT Plugins**: Useful SBT plugins for development
- **Docker**: Container setup for development environment
- **Monitoring**: Development monitoring and debugging tools

### **🔗 Useful Links**
- **Project Repository**: Main repository and issue tracker
- **Continuous Integration**: CI/CD pipeline and build status
- **Documentation Site**: Complete documentation website
- **Community Forum**: Developer discussions and Q&A

---

**🎯 This developer guide provides everything you need to contribute effectively to the Hyperbolic Time Chamber project. Whether you're fixing bugs, adding features, or extending the system, these guidelines will help you build high-quality, maintainable code.**

Ready to contribute? Start with the [issues labeled "good first issue"](https://github.com/your-repo/hyperbolic-time-chamber/labels/good%20first%20issue) and join our developer community!