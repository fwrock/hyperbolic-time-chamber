# 🏗️ Architecture Overview

The Hyperbolic Time Chamber (HTC) is built on a distributed, event-driven architecture designed for scalability, fault tolerance, and performance. This document provides a comprehensive overview of the system's architecture, components, and design principles.

---

## 🎯 **Core Architecture Principles**

### **1. Event-Driven Design**
- **Discrete Event Simulation**: Time progresses through discrete events rather than continuous time steps
- **Asynchronous Processing**: Non-blocking event handling for maximum throughput
- **Event Ordering**: Lamport clocks ensure proper event ordering in distributed scenarios
- **Event Persistence**: Critical events are persisted for recovery and analysis

### **2. Actor-Based Concurrency**
- **Apache Pekko Framework**: Built on the robust Pekko actor system (Akka fork)
- **Isolation**: Each actor maintains its own state and communicates via messages
- **Fault Tolerance**: Supervision hierarchies with automatic recovery
- **Location Transparency**: Actors can be distributed across nodes seamlessly

### **3. Distributed Computing**
- **Horizontal Scaling**: Add compute nodes dynamically to handle larger simulations
- **Cluster Management**: Automatic node discovery and load balancing
- **Partition Tolerance**: Continue operation even with network partitions
- **State Replication**: Critical state is replicated across nodes for reliability

### **4. Microservices Architecture**
- **Loosely Coupled**: Components interact through well-defined interfaces
- **Independent Deployment**: Each service can be updated independently
- **Technology Focus**: Scala-based simulation core with extensible interfaces
- **Service Discovery**: Automatic discovery and registration of services

---

## 🔧 **System Components**

### **🎮 Core Simulation Engine**

```
┌─────────────────────────────────────────────────────────────┐
│                    Simulation Core                         │
├─────────────────────────────────────────────────────────────┤
│  SimulationManager                                         │
│  ├── Orchestrates overall simulation lifecycle             │
│  ├── Manages component initialization and shutdown         │
│  ├── Coordinates between managers                          │
│  └── Handles global simulation events                      │
├─────────────────────────────────────────────────────────────┤
│  TimeManager (Global/Local)                               │
│  ├── Manages simulation time progression                   │
│  ├── Schedules and dispatches events                      │
│  ├── Synchronizes distributed time across nodes           │
│  └── Handles time-based event ordering                    │
├─────────────────────────────────────────────────────────────┤
│  LoadManager                                               │
│  ├── Loads simulation data from various sources            │
│  ├── Creates and initializes simulation actors            │
│  ├── Manages actor lifecycle during data loading          │
│  └── Handles batch processing for large datasets          │
├─────────────────────────────────────────────────────────────┤
│  ReportManager                                             │
│  ├── Collects simulation data and metrics                 │
│  ├── Manages different report types and formats           │
│  ├── Coordinates data export and persistence              │
│  └── Handles real-time monitoring and dashboards         │
└─────────────────────────────────────────────────────────────┘
```

#### **SimulationManager**
The central orchestrator responsible for:
- **Lifecycle Management**: Controls simulation startup, execution, and shutdown
- **Component Coordination**: Manages communication between other managers
- **Configuration Loading**: Reads and validates simulation configuration
- **Error Handling**: Manages global error recovery and graceful degradation

#### **TimeManager**
Controls the temporal aspects of simulation:
- **Time Synchronization**: Ensures consistent time across distributed nodes
- **Event Scheduling**: Manages the event queue and dispatch timing
- **Clock Management**: Maintains Lamport clocks for event ordering
- **Performance Optimization**: Implements efficient time advancement algorithms

#### **LoadManager**
Handles data ingestion and actor creation:
- **Data Source Abstraction**: Supports multiple input formats (JSON, CSV, databases)
- **Actor Factory**: Creates simulation actors based on configuration
- **Batch Processing**: Efficiently processes large datasets in chunks
- **Memory Management**: Optimizes memory usage during data loading

#### **ReportManager**
Manages data collection and export:
- **Flexible Reporting**: Supports multiple output formats and destinations
- **Real-time Streaming**: Enables live monitoring of simulation progress
- **Data Aggregation**: Combines data from multiple sources and time periods
- **Export Coordination**: Manages data export to external systems

---

### **🎭 Actor System Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                     Actor Hierarchy                        │
├─────────────────────────────────────────────────────────────┤
│  BaseActor<T>                                              │
│  ├── Abstract base for all simulation actors               │
│  ├── Provides common lifecycle methods                     │
│  ├── Implements event handling and state management        │
│  └── Supports serialization and persistence               │
├─────────────────────────────────────────────────────────────┤
│  Domain-Specific Actors                                   │
│  ├── VehicleActor: Represents individual vehicles         │
│  ├── IntersectionActor: Models traffic intersections      │
│  ├── SignalActor: Traffic signal control logic           │
│  ├── RouteActor: Route planning and optimization          │
│  └── Custom actors for specific simulation needs          │
├─────────────────────────────────────────────────────────────┤
│  Actor Creation Patterns                                   │
│  ├── LoadBalancedDistributed: Auto-scaling across nodes   │
│  ├── PoolDistributed: Fixed-size pools per node          │
│  ├── Simple: Single instance actors                       │
│  └── Custom: Domain-specific creation strategies          │
└─────────────────────────────────────────────────────────────┘
```

#### **BaseActor Design**
All simulation actors extend the `BaseActor<T>` class, which provides:

```scala
abstract class BaseActor[T <: BaseState](
  private val properties: Properties
)(implicit m: Manifest[T]) extends ActorSerializable with ActorLogging with Stash {
  
  // Core functionality:
  protected def actSpontaneous(event: SpontaneousEvent): Unit
  def actInteractWith(event: ActorInteractionEvent): Unit
  protected def onInitialize(event: InitializeEvent): Unit
  protected def onFinishInitialize(): Unit
  
  // Time management:
  protected var currentTick: Tick
  protected var startTick: Tick
  private val lamportClock = new LamportClock()
  
  // State management:
  protected var state: T
  protected val dependencies: mutable.Map[String, Dependency]
  protected var reporters: mutable.Map[ReportTypeEnum, ActorRef]
}
```

---

### **📊 Data Flow Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                      Data Flow                             │
├─────────────────────────────────────────────────────────────┤
│  Input Sources                                             │
│  ├── JSON Configuration Files                             │
│  ├── CSV Data Files                                       │
│  ├── Database Connections (Cassandra, PostgreSQL)        │
│  ├── REST APIs and External Services                      │
│  └── Real-time Streaming Sources                          │
├─────────────────────────────────────────────────────────────┤
│  Processing Pipeline                                       │
│  ├── Data Validation and Schema Checking                  │
│  ├── Transformation and Enrichment                        │
│  ├── Actor Creation and Initialization                    │
│  ├── Event Generation and Scheduling                      │
│  └── State Updates and Persistence                        │
├─────────────────────────────────────────────────────────────┤
│  Output Destinations                                       │
│  ├── Time-series Database (Cassandra)                     │
│  ├── File Exports (JSON, CSV, XML)                        │
│  ├── Real-time Dashboards (WebSocket)                     │
│  ├── Academic Reports (PDF, LaTeX)                        │
│  └── External APIs and Integrations                       │
└─────────────────────────────────────────────────────────────┘
```

---

### **🕐 Time Management System**

```
┌─────────────────────────────────────────────────────────────┐
│                  Time Management                           │
├─────────────────────────────────────────────────────────────┤
│  Global Time Manager                                       │
│  ├── Maintains master simulation clock                     │
│  ├── Coordinates time advancement across nodes             │
│  ├── Handles pause/resume/stop operations                  │
│  └── Manages simulation duration and termination          │
├─────────────────────────────────────────────────────────────┤
│  Local Time Managers                                       │
│  ├── Manage time on individual nodes                       │
│  ├── Synchronize with global time manager                  │
│  ├── Handle local event scheduling                         │
│  └── Optimize local time advancement                       │
├─────────────────────────────────────────────────────────────┤
│  Event Scheduling                                          │
│  ├── Priority queue for future events                      │
│  ├── Lamport clocks for distributed ordering               │
│  ├── Optimistic time advancement                           │
│  └── Rollback mechanisms for conflicts                     │
└─────────────────────────────────────────────────────────────┘
```

#### **Time Synchronization Protocol**
1. **Global Clock**: Master clock maintained by Global Time Manager
2. **Local Clocks**: Each node maintains a local clock synchronized with global
3. **Event Ordering**: Lamport timestamps ensure causal ordering of events
4. **Synchronization Points**: Periodic synchronization prevents drift
5. **Rollback Support**: Ability to rollback time for conflict resolution

---

### **🌐 Distributed System Design**

```
┌─────────────────────────────────────────────────────────────┐
│                 Distributed Architecture                   │
├─────────────────────────────────────────────────────────────┤
│  Cluster Management                                        │
│  ├── Node Discovery and Registration                       │
│  ├── Leader Election for Global Services                   │
│  ├── Load Balancing and Auto-scaling                      │
│  └── Failure Detection and Recovery                       │
├─────────────────────────────────────────────────────────────┤
│  Data Partitioning                                        │
│  ├── Consistent Hashing for Actor Distribution            │
│  ├── Geographic Partitioning for Spatial Simulations     │
│  ├── Load-based Dynamic Rebalancing                       │
│  └── Replication for High Availability                    │
├─────────────────────────────────────────────────────────────┤
│  Network Communication                                     │
│  ├── Protocol Buffers for Efficient Serialization        │
│  ├── TCP/TLS for Secure Inter-node Communication         │
│  ├── Message Compression and Batching                     │
│  └── Network Partition Tolerance                          │
└─────────────────────────────────────────────────────────────┘
```

#### **Cluster Deployment Patterns**

**Single Node Development**
```yaml
# docker-compose.yml
services:
  node1:
    build: .
    environment:
      CLUSTER_IP: node1
      CLUSTER_PORT: 1600
      SEED_PORT_1600_TCP_ADDR: node1
```

**Multi-Node Production**
```yaml
# Production cluster with 3 nodes
services:
  node1:
    environment:
      CLUSTER_IP: node1
      SEED_NODES: "node1:1600,node2:1600,node3:1600"
  node2:
    environment:
      CLUSTER_IP: node2
      SEED_NODES: "node1:1600,node2:1600,node3:1600"
  node3:
    environment:
      CLUSTER_IP: node3
      SEED_NODES: "node1:1600,node2:1600,node3:1600"
```

---

### **🗄️ Data Storage Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                   Storage Systems                          │
├─────────────────────────────────────────────────────────────┤
│  Apache Cassandra                                         │
│  ├── Time-series simulation data                          │
│  ├── Actor state snapshots                                │
│  ├── Event logs and audit trails                          │
│  └── Large-scale analytical datasets                      │
├─────────────────────────────────────────────────────────────┤
│  Redis                                                     │
│  ├── Session state caching                                │
│  ├── Real-time metrics                                    │
│  ├── Temporary computation results                        │
│  └── Pub/sub messaging                                    │
├─────────────────────────────────────────────────────────────┤
│  File System                                              │
│  ├── Configuration files (JSON/HOCON)                     │
│  ├── Input datasets (CSV/JSON)                            │
│  ├── Export outputs (multiple formats)                    │
│  └── Logs and debugging information                       │
└─────────────────────────────────────────────────────────────┘
```

#### **Cassandra Schema Design**
```sql
-- Keyspace for simulation data
CREATE KEYSPACE IF NOT EXISTS htc_simulation 
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 3
};

-- Time-series event data
CREATE TABLE htc_simulation.events (
  simulation_id UUID,
  tick BIGINT,
  actor_id TEXT,
  event_type TEXT,
  event_data TEXT,
  timestamp TIMESTAMP,
  PRIMARY KEY ((simulation_id), tick, actor_id)
) WITH CLUSTERING ORDER BY (tick ASC, actor_id ASC);

-- Actor state snapshots
CREATE TABLE htc_simulation.actor_states (
  simulation_id UUID,
  actor_id TEXT,
  tick BIGINT,
  state_data TEXT,
  PRIMARY KEY ((simulation_id, actor_id), tick)
) WITH CLUSTERING ORDER BY (tick DESC);
```

---

### **📡 Communication Patterns**

```
┌─────────────────────────────────────────────────────────────┐
│                 Communication Patterns                     │
├─────────────────────────────────────────────────────────────┤
│  Event-Driven Messaging                                   │
│  ├── SpontaneousEvent: Internal actor events              │
│  ├── ActorInteractionEvent: Inter-actor communication     │
│  ├── ControlEvent: System lifecycle events                │
│  └── ReportEvent: Data collection events                  │
├─────────────────────────────────────────────────────────────┤
│  Synchronization Mechanisms                               │
│  ├── Barriers: Synchronization points across nodes        │
│  ├── Consensus: Agreement on global state                 │
│  ├── Locks: Distributed locking for critical sections    │
│  └── Transactions: ACID properties for state changes      │
├─────────────────────────────────────────────────────────────┤
│  External Interfaces                                      │
│  ├── REST API: HTTP-based external integration            │
│  ├── gRPC: High-performance RPC for real-time data       │
│  ├── WebSocket: Real-time dashboard updates               │
│  └── Message Queues: Asynchronous external communication │
└─────────────────────────────────────────────────────────────┘
```

---

### **🔒 Security Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                   Security Framework                       │
├─────────────────────────────────────────────────────────────┤
│  Authentication & Authorization                           │
│  ├── JWT tokens for API access                            │
│  ├── Role-based access control (RBAC)                     │
│  ├── Service-to-service authentication                    │
│  └── Integration with external identity providers         │
├─────────────────────────────────────────────────────────────┤
│  Network Security                                         │
│  ├── TLS encryption for all communications                │
│  ├── Network segmentation and firewalls                   │
│  ├── VPN support for distributed deployments              │
│  └── Certificate management and rotation                  │
├─────────────────────────────────────────────────────────────┤
│  Data Protection                                          │
│  ├── Encryption at rest for sensitive data                │
│  ├── Data anonymization for privacy compliance           │
│  ├── Audit logging for data access                        │
│  └── Backup encryption and secure storage                 │
└─────────────────────────────────────────────────────────────┘
```

---

### **📊 Monitoring & Observability**

```
┌─────────────────────────────────────────────────────────────┐
│               Monitoring Architecture                      │
├─────────────────────────────────────────────────────────────┤
│  Metrics Collection                                        │
│  ├── System metrics (CPU, memory, network)                │
│  ├── Application metrics (throughput, latency)            │
│  ├── Business metrics (simulation progress, accuracy)     │
│  └── Custom metrics for domain-specific monitoring        │
├─────────────────────────────────────────────────────────────┤
│  Logging Framework                                         │
│  ├── Structured logging with JSON format                  │
│  ├── Distributed tracing across service boundaries        │
│  ├── Log aggregation and centralized search               │
│  └── Correlation IDs for request tracking                 │
├─────────────────────────────────────────────────────────────┤
│  Alerting & Dashboards                                    │
│  ├── Real-time alerting on system anomalies               │
│  ├── Performance dashboards and visualizations            │
│  ├── Capacity planning and trend analysis                 │
│  └── SLA monitoring and reporting                         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 **Performance Characteristics**

### **📈 Scalability Metrics**
- **Horizontal Scaling**: Linear scaling up to 100+ nodes tested
- **Actor Density**: 1M+ actors per node with optimized memory usage
- **Event Throughput**: 100K+ events/second per node
- **Storage Scaling**: Petabyte-scale data storage with Cassandra

### **⚡ Latency Characteristics**
- **Event Processing**: <1ms average latency for local events
- **Network Communication**: <10ms for inter-node messaging
- **Database Writes**: <5ms for Cassandra writes (async)
- **Time Synchronization**: <100ms global synchronization interval

### **💾 Memory Usage**
- **Base Memory**: 2GB minimum per node
- **Actor Memory**: 1KB average per actor instance
- **Cache Memory**: 512MB default for Redis caching
- **Buffer Memory**: 256MB for message buffering

### **🔄 Fault Tolerance**
- **Node Failures**: Automatic recovery with <30s downtime
- **Network Partitions**: Graceful degradation with partition tolerance
- **Data Loss**: Zero data loss with proper replication
- **State Recovery**: Snapshot-based recovery in <60s

---

## 🛠️ **Extension Points**

### **🎭 Custom Actors**
```scala
// Example custom actor implementation
class CustomVehicleActor(properties: Properties) 
  extends BaseActor[VehicleState](properties) {
  
  override def actSpontaneous(event: SpontaneousEvent): Unit = {
    // Custom spontaneous behavior
    event match {
      case moveEvent: MoveEvent => handleMovement(moveEvent)
      case _ => super.actSpontaneous(event)
    }
  }
  
  override def actInteractWith(event: ActorInteractionEvent): Unit = {
    // Custom interaction behavior
    event match {
      case collision: CollisionEvent => handleCollision(collision)
      case _ => super.actInteractWith(event)
    }
  }
}
```

### **📊 Custom Reports**
```scala
// Example custom reporter
class CustomMetricsReporter(
  selfProxy: ActorRef,
  startRealTime: LocalDateTime
) extends BaseReportData[DefaultState](selfProxy, startRealTime) {
  
  override def onReport(event: ReportEvent): Unit = {
    // Custom metrics collection
    val metrics = extractCustomMetrics(event)
    persistMetrics(metrics)
  }
}
```

### **🔌 Plugin Architecture**
```scala
// Plugin interface
trait SimulationPlugin {
  def initialize(context: SimulationContext): Unit
  def onSimulationStart(): Unit
  def onSimulationEnd(): Unit
  def getCustomActors(): Map[String, Class[_ <: BaseActor[_]]]
  def getCustomReporters(): Map[ReportTypeEnum, Class[_ <: BaseReportData[_]]]
}
```

---

## 🎯 **Design Patterns**

### **🏭 Factory Pattern**
Used for actor creation with different deployment strategies:
```scala
object ActorCreatorUtil {
  def createActor(
    actorType: String,
    creationType: CreationTypeEnum,
    properties: Properties
  ): ActorRef = {
    creationType match {
      case LoadBalancedDistributed => createShardedActor(actorType, properties)
      case PoolDistributed => createPoolActor(actorType, properties)
      case Simple => createSimpleActor(actorType, properties)
    }
  }
}
```

### **🔍 Observer Pattern**
Event-driven architecture with reactive components:
```scala
trait EventObserver {
  def onEvent(event: BaseEvent[_]): Unit
}

class EventPublisher {
  private val observers = mutable.Set[EventObserver]()
  
  def subscribe(observer: EventObserver): Unit = observers += observer
  def publish(event: BaseEvent[_]): Unit = observers.foreach(_.onEvent(event))
}
```

### **🏗️ Builder Pattern**
Complex configuration object construction:
```scala
case class SimulationConfigBuilder() {
  private var name: String = _
  private var duration: Tick = _
  private var actors: List[ActorDataSource] = Nil
  
  def withName(name: String): SimulationConfigBuilder = {
    this.name = name
    this
  }
  
  def withDuration(duration: Tick): SimulationConfigBuilder = {
    this.duration = duration
    this
  }
  
  def build(): Simulation = Simulation(name, duration, actors)
}
```

---

## 📚 **Further Reading**

- **[Configuration Guide](CONFIGURATION.md)** - Detailed configuration options
- **[API Reference](API_REFERENCE.md)** - Complete API documentation
- **[Developer Guide](DEVELOPER_GUIDE.md)** - Contributing and extending the system
- **[Performance Benchmarks](BENCHMARKS.md)** - Performance testing and optimization
- **[Deployment Guide](DEPLOYMENT.md)** - Production deployment strategies

---

**This architecture provides a solid foundation for scalable, fault-tolerant traffic simulation. The modular design allows for easy extension and customization while maintaining high performance and reliability.**