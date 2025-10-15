# ⚙️ Configuration Guide

This comprehensive guide covers all configuration options available in the Hyperbolic Time Chamber. Learn how to customize the simulator for your specific use cases, optimize performance, and configure advanced features.

---

## 📋 **Configuration Overview**

HTC uses a hierarchical configuration system with multiple layers:

1. **📄 Application Configuration** (`application.conf`) - Core system settings
2. **🎯 Simulation Configuration** (JSON) - Simulation-specific parameters
3. **🐳 Docker Configuration** (`docker-compose.yml`) - Infrastructure settings
4. **🗄️ Database Configuration** (Cassandra/Redis) - Storage settings
5. **🌍 Environment Variables** - Runtime overrides

**⚡ Performance Note:** For optimal simulation performance, prefer pre-configured JSON files over Cassandra database connections, which can slow down simulations.

---

## 🎯 **Simulation Configuration**

### **📝 Basic Structure**

```json
{
  "simulation": {
    "name": "Traffic Simulation",
    "description": "Urban traffic simulation example",
    "id": "sim_20250127_001",
    "startTick": 0,
    "startRealTime": "2025-01-27T08:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 3600,
    "randomSeed": 42,
    "actorsDataSources": [
      // Actor configuration array
    ]
  }
}
```

### **🔧 Core Parameters**

#### **Simulation Metadata**
```json
{
  "name": "Your Simulation Name",           // Display name
  "description": "Detailed description",   // Purpose/context
  "id": "unique_simulation_id"             // Optional unique identifier
}
```

#### **Time Configuration**
```json
{
  "startTick": 0,                          // Simulation start time (tick)
  "startRealTime": "2025-01-27T08:00:00.000", // Real-world time mapping
  "timeUnit": "seconds",                   // Time unit (seconds/minutes/hours)
  "timeStep": 1,                          // Time advancement per tick
  "duration": 3600                        // Total simulation duration (ticks)
}
```

**Time Unit Options:**
- `"seconds"` - Default, good for detailed analysis
- `"minutes"` - Faster simulation, less granular
- `"hours"` - High-level analysis, very fast
- `"milliseconds"` - Ultra-precise simulation (performance impact)

#### **Reproducibility**
```json
{
  "randomSeed": 42                        // Fixed seed for reproducible results
  // Omit randomSeed for timestamp-based random generation
}
```

---

## 🎭 **Actor Configuration**

### **📊 Actor Data Source Structure**
```json
{
  "actorsDataSources": [
    {
      "id": "vehicle-fleet-1",             // Unique identifier
      "classType": "mobility.actor.Car",   // Actor class (mobility model)
      "creationType": "LoadBalancedDistributed",   // Distribution strategy
      "poolConfiguration": {               // Pool-specific settings
        "minInstances": 10,
        "maxInstances": 100,
        "instancesPerNode": 25
      },
      "dataSource": {                      // Data source configuration
        "type": "json",                    // Source type
        "info": {
          "path": "data/vehicles.json",   // File path
          "batchSize": 1000               // Processing batch size
        }
      }
    }
  ]
}
```

### **🚀 Creation Types**

#### **LoadBalancedDistributed**
Automatically distributes actors across cluster nodes for optimal load balancing.

```json
{
  "creationType": "LoadBalancedDistributed",
  "shardingConfig": {
    "numberOfShards": 100,               // Number of shards (default: 100)
    "rebalanceInterval": "10 seconds"    // Rebalancing frequency
  }
}
```

**Use cases:**
- Large-scale simulations with many actors
- Dynamic load balancing requirements
- Geographic distribution of entities

#### **PoolDistributed**
Creates fixed-size pools of actors on each node.

```json
{
  "creationType": "PoolDistributed",
  "poolConfiguration": {
    "minInstances": 10,                  // Minimum pool size
    "maxInstances": 100,                 // Maximum pool size
    "instancesPerNode": 25,              // Instances per cluster node
    "resizeStrategy": "eager"            // Resizing strategy
  }
}
```

**Use cases:**
- Predictable workloads
- Resource-constrained environments
- Legacy system integration

#### **Simple**
Single instance actors for specialized use cases.

```json
{
  "creationType": "Simple",
  "singletonConfig": {
    "allowReplication": false,           // Allow replicas for HA
    "preferredNode": "node1"            // Preferred deployment node
  }
}
```

**Use cases:**
- Singleton managers (traffic controllers)
- Global coordinators
- External system interfaces

### **📂 Data Source Types**

#### **JSON File Source**
```json
{
  "dataSource": {
    "type": "json",
    "info": {
      "path": "data/vehicles.json",       // File path (relative or absolute)
      "encoding": "UTF-8",               // File encoding (default: UTF-8)
      "batchSize": 1000,                 // Processing batch size
      "validation": true                 // Enable schema validation
    }
  }
}
```

#### **CSV File Source**
```json
{
  "dataSource": {
    "type": "csv",
    "info": {
      "path": "data/traffic_data.csv",
      "separator": ",",                   // Field separator
      "header": true,                     // Has header row
      "dateFormat": "yyyy-MM-dd HH:mm:ss", // Date parsing format
      "batchSize": 5000
    }
  }
}
```

#### **Database Source**
```json
{
  "dataSource": {
    "type": "database",
    "info": {
      "connectionString": "cassandra://localhost:9042/htc",
      "query": "SELECT * FROM vehicle_data WHERE simulation_id = ?",
      "parameters": ["sim_001"],
      "fetchSize": 1000
    }
  }
}
```

#### **API Source**
```json
{
  "dataSource": {
    "type": "api",
    "info": {
      "endpoint": "https://api.example.com/vehicles",
      "method": "GET",
      "headers": {
        "Authorization": "Bearer ${API_TOKEN}",
        "Content-Type": "application/json"
      },
      "pagination": {
        "type": "offset",
        "pageSize": 100
      }
    }
  }
}
```

---

## 🎭 **Actor Types and Configuration**

### **🚗 Vehicle Actors**

```json
{
  "classType": "com.htc.traffic.VehicleActor",
  "actorConfig": {
    "behaviorModel": "IDM",               // Intelligent Driver Model
    "maxSpeed": 50.0,                    // km/h
    "acceleration": 2.5,                 // m/s²
    "deceleration": 4.0,                 // m/s²
    "minGap": 2.0,                      // meters
    "reactionTime": 1.0,                // seconds
    "laneChangeModel": "MOBIL",         // Lane changing algorithm
    "routingStrategy": "shortest_path"   // Routing algorithm
  }
}
```

**Vehicle Behavior Models:**
- `"IDM"` - Intelligent Driver Model (default)
- `"Krauss"` - SUMO's car-following model
- `"ACC"` - Adaptive Cruise Control
- `"Human"` - Human driver simulation
- `"AV"` - Autonomous vehicle behavior

### **🚦 Traffic Signal Actors**

```json
{
  "classType": "com.htc.traffic.TrafficSignalActor",
  "actorConfig": {
    "controlType": "fixed_time",         // Control strategy
    "cycleTime": 120,                   // Total cycle time (seconds)
    "phases": [
      {
        "id": "north_south",
        "duration": 45,                  // Green time (seconds)
        "yellowTime": 3,                // Yellow time (seconds)
        "movements": ["north", "south"]  // Allowed movements
      },
      {
        "id": "east_west", 
        "duration": 45,
        "yellowTime": 3,
        "movements": ["east", "west"]
      }
    ]
  }
}
```

**Control Types:**
- `"fixed_time"` - Pre-programmed timing
- `"actuated"` - Vehicle-responsive timing
- `"adaptive"` - AI-based optimization
- `"manual"` - External control interface

### **🏗️ Infrastructure Actors**

```json
{
  "classType": "com.htc.infrastructure.LinkActor",
  "actorConfig": {
    "capacity": 2000,                   // vehicles/hour
    "freeFlowSpeed": 60,               // km/h
    "length": 1000,                    // meters
    "numberOfLanes": 3,                // lane count
    "linkType": "highway",             // road classification
    "speedLimit": 80,                  // km/h
    "tollCost": 0.0                    // monetary cost
  }
}
```

---

## 📊 **Reporting Configuration**

### **🔧 Report Manager Settings**
```json
{
  "reporting": {
    "enabled": true,
    "outputDirectory": "simulation_output",
    "formats": ["json", "csv", "xml"],
    "realTimeUpdates": true,
    "aggregationInterval": 60,          // seconds
    "reports": [
      {
        "type": "traffic_flow",
        "enabled": true,
        "frequency": "every_tick",       // Collection frequency
        "fields": ["vehicle_id", "link_id", "speed", "position"],
        "filters": {
          "vehicleTypes": ["car", "truck"], // Filter criteria
          "timeRange": [0, 3600]
        }
      }
    ]
  }
}
```

### **📈 Report Types**

#### **Traffic Flow Reports**
```json
{
  "type": "traffic_flow",
  "config": {
    "includeTrajectories": true,
    "spatialResolution": 10,            // meters
    "temporalResolution": 1,            // seconds
    "outputFormat": "time_series"
  }
}
```

#### **Performance Reports**
```json
{
  "type": "performance",
  "config": {
    "systemMetrics": true,              // CPU, memory usage
    "actorMetrics": true,               // Actor statistics
    "networkMetrics": true,             // Network communication
    "samplingRate": 0.1                // 10% sampling
  }
}
```

#### **Custom Reports**
```json
{
  "type": "custom",
  "className": "com.yourcompany.CustomReporter",
  "config": {
    "customParam1": "value1",
    "customParam2": 123
  }
}
```

---

## 🐳 **Docker Configuration**

### **📝 Basic Docker Compose**
```yaml
# docker-compose.yml
version: '3.8'
services:
  
  cassandra:
    image: cassandra:latest
    container_name: htc-cassandra-db
    environment:
      - CASSANDRA_DC=datacenter1
      - CASSANDRA_RACK=rack1
      - CASSANDRA_CLUSTER_NAME=htc-cluster
      - MAX_HEAP_SIZE=2G                # JVM heap size
      - HEAP_NEWSIZE=400M              # Young generation size
    volumes:
      - cassandra_data:/var/lib/cassandra
      - ./cassandra-config:/etc/cassandra
    ports:
      - "9042:9042"
    networks:
      - htc-network

  redis:
    image: redis:latest
    container_name: htc-redis
    command: redis-server --maxmemory 1gb --maxmemory-policy allkeys-lru
    ports:
      - "6379:6379"
    networks:
      - htc-network

  node1:
    build: .
    container_name: htc-node1
    hostname: node1
    environment:
      - CLUSTER_IP=node1
      - CLUSTER_PORT=1600
      - JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC
      - SIMULATION_CONFIG_PATH=/app/config/simulation.json
    volumes:
      - ./data:/app/data
      - ./config:/app/config
      - ./logs:/app/logs
    networks:
      - htc-network
    depends_on:
      - cassandra
      - redis

networks:
  htc-network:
    driver: bridge

volumes:
  cassandra_data:
  redis_data:
```

### **🚀 Production Configuration**
```yaml
# docker-compose-prod.yml
version: '3.8'
services:
  
  cassandra:
    image: cassandra:latest
    deploy:
      replicas: 3
      resources:
        limits:
          cpus: '4'
          memory: 8G
        reservations:
          cpus: '2'
          memory: 4G
    environment:
      - CASSANDRA_SEEDS=cassandra-1,cassandra-2,cassandra-3
      - MAX_HEAP_SIZE=6G
      - HEAP_NEWSIZE=1200M
    networks:
      - htc-network

  node1:
    image: htc:latest
    deploy:
      replicas: 2
      resources:
        limits:
          cpus: '8'
          memory: 16G
    environment:
      - CLUSTER_SEEDS=node1:1600,node2:1600,node3:1600
      - JAVA_OPTS=-Xms8g -Xmx12g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
    networks:
      - htc-network
```

---

## 📄 **Application Configuration**

### **🔧 Core Settings (`application.conf`)**
```hocon
# Pekko Actor System Configuration
pekko {
  loglevel = "INFO"
  
  actor {
    provider = cluster
    
    serialization-bindings {
      "org.interscity.htc.core.entity.event.EntityEnvelopeEvent" = envelope
      "scalapb.GeneratedMessage" = proto
    }
    
    deployment {
      /simulation-manager {
        router = round-robin-pool
        nr-of-instances = 5
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 2
          allow-local-routees = off
        }
      }
    }
  }
  
  cluster {
    seed-nodes = [
      "pekko://hyperbolic-time-chamber@node1:1600",
      "pekko://hyperbolic-time-chamber@node2:1600"
    ]
    
    sharding {
      number-of-shards = 100
      guardian-name = sharding
      role = "simulation"
      
      passivate-idle-entity-after = 2 minutes
      remember-entities = on
    }
  }
  
  remote {
    artery {
      canonical.hostname = ${CLUSTER_IP}
      canonical.port = ${CLUSTER_PORT}
      
      advanced {
        maximum-frame-size = 1MB
        buffer-pool-size = 128
        maximum-large-frame-size = 8MB
      }
    }
  }
}

# Application-Specific Configuration
htc {
  simulation {
    default-timeout = 30 seconds
    max-actors-per-node = 100000
    snapshot-interval = 1000
    
    time-management {
      sync-interval = 100 milliseconds
      max-time-drift = 1 second
      batch-size = 1000
    }
  }
  
  database {
    cassandra {
      contact-points = ["cassandra:9042"]
      keyspace = "htc_simulation"
      replication-factor = 3
      consistency-level = "QUORUM"
      
      connection-pool {
        core-connections = 2
        max-connections = 8
        max-requests-per-connection = 1024
      }
    }
    
    redis {
      host = "redis"
      port = 6379
      database = 0
      timeout = 5 seconds
    }
  }
  
  reporting {
    buffer-size = 10000
    flush-interval = 5 seconds
    compression = true
    
    cassandra-reporter {
      batch-size = 1000
      retry-attempts = 3
    }
  }
}
```

### **🌍 Environment-Specific Overrides**

#### **Development (`application-dev.conf`)**
```hocon
include "application.conf"

pekko.loglevel = "DEBUG"

htc {
  simulation {
    max-actors-per-node = 1000      # Reduced for development
  }
  
  database {
    cassandra {
      keyspace = "htc_dev"
      replication-factor = 1        # Single node development
    }
  }
}
```

#### **Testing (`application-test.conf`)**
```hocon
include "application.conf"

htc {
  simulation {
    default-timeout = 5 seconds    # Faster timeouts for tests
  }
  
  database {
    cassandra {
      keyspace = "htc_test"
      contact-points = ["localhost:9042"]
    }
  }
}
```

#### **Production (`application-prod.conf`)**
```hocon
include "application.conf"

pekko {
  loglevel = "WARN"
  
  cluster {
    failure-detector {
      threshold = 12                # More tolerant for production
      acceptable-heartbeat-pause = 10 seconds
    }
  }
}

htc {
  simulation {
    max-actors-per-node = 1000000   # High-capacity production
  }
  
  database {
    cassandra {
      replication-factor = 3
      consistency-level = "QUORUM"
    }
  }
}
```

---

## 🗄️ **Database Configuration**

### **📊 Cassandra Settings**

#### **Cluster Configuration (`cassandra.yaml`)**
```yaml
# Basic Settings
cluster_name: 'HTC Cluster'
num_tokens: 256
allocate_tokens_for_keyspace: htc_simulation

# Memory Settings
memtable_allocation_type: heap_buffers
memtable_heap_space_in_mb: 2048
memtable_offheap_space_in_mb: 2048

# Compaction
compaction_throughput_mb_per_sec: 64
concurrent_compactors: 4

# Write Settings
commitlog_sync: periodic
commitlog_sync_period_in_ms: 10000
commitlog_segment_size_in_mb: 32

# Read Settings
file_cache_size_in_mb: 512
buffer_pool_use_heap_if_exhausted: true

# Network
native_transport_port: 9042
rpc_port: 9160
storage_port: 7000
ssl_storage_port: 7001

# Performance
concurrent_reads: 32
concurrent_writes: 32
concurrent_counter_writes: 32
```

#### **JVM Options (`jvm.options`)**
```bash
# Heap Settings
-Xms4G
-Xmx4G
-XX:+HeapDumpOnOutOfMemoryError

# GC Settings
-XX:+UseG1GC
-XX:+UnlockExperimentalVMOptions
-XX:+UseG1NewGC
-XX:G1NewSizePercent=20
-XX:+ResizeTLAB

# Performance
-XX:+PerfDisableSharedMem
-XX:+AlwaysPreTouch
-XX:-UseBiasedLocking

# Monitoring
-XX:+PrintGC
-XX:+PrintGCDetails
-XX:+PrintGCTimeStamps
```

### **💾 Redis Configuration**

#### **Redis Settings (`redis.conf`)**
```conf
# Memory
maxmemory 2gb
maxmemory-policy allkeys-lru

# Persistence
save 900 1
save 300 10
save 60 10000

# Network
bind 0.0.0.0
port 6379
tcp-keepalive 300

# Performance
tcp-backlog 511
timeout 0
databases 16

# Logging
loglevel notice
```

---

## 🌍 **Environment Variables**

### **🔧 System Variables**
```bash
# Java Configuration
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk
export JAVA_OPTS="-Xms4g -Xmx8g -XX:+UseG1GC"

# Pekko Cluster
export CLUSTER_IP=node1
export CLUSTER_PORT=1600
export SEED_NODES="node1:1600,node2:1600,node3:1600"

# Database
export CASSANDRA_HOSTS="cassandra1:9042,cassandra2:9042"
export REDIS_URL="redis://redis:6379/0"

# Simulation
export SIMULATION_CONFIG_PATH="/app/config/simulation.json"
export OUTPUT_DIRECTORY="/app/output"
export LOG_LEVEL="INFO"
```

### **🚀 Production Variables**
```bash
# Scaling
export MAX_ACTORS_PER_NODE=1000000
export CLUSTER_SHARDS=1000
export WORKER_THREADS=16

# Performance
export GC_TYPE="G1"
export HEAP_SIZE="12g"
export DIRECT_MEMORY="4g"

# Monitoring
export METRICS_ENABLED=true
export PROMETHEUS_PORT=9090
export JAEGER_ENDPOINT="http://jaeger:14268"

# Security
export TLS_ENABLED=true
export KEYSTORE_PATH="/app/certs/keystore.jks"
export TRUSTSTORE_PATH="/app/certs/truststore.jks"
```

---

## 📈 **Performance Tuning**

### **🚀 JVM Optimization**
```bash
# High-performance JVM settings
export JAVA_OPTS="
  -Xms8g -Xmx12g
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+UnlockExperimentalVMOptions
  -XX:+UseG1NewGC
  -XX:G1NewSizePercent=30
  -XX:G1MaxNewSizePercent=40
  -XX:+ParallelRefProcEnabled
  -XX:+AlwaysPreTouch
  -XX:+UseTransparentHugePages
"
```

### **🗄️ Database Optimization**
```yaml
# Cassandra production settings
memtable_heap_space_in_mb: 4096
memtable_offheap_space_in_mb: 4096
concurrent_reads: 64
concurrent_writes: 64
file_cache_size_in_mb: 2048
commitlog_segment_size_in_mb: 64
```

### **🌐 Network Optimization**
```conf
# Linux kernel network tuning
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 16384 134217728
net.ipv4.tcp_wmem = 4096 65536 134217728
net.core.netdev_max_backlog = 5000
```

---

## ✅ **Configuration Validation**

### **🔍 Validation Scripts**
```bash
# Validate simulation configuration
./scripts/validate_config.sh config/simulation.json

# Check system requirements
./diagnose.sh system

# Verify database connections
./scripts/check_connections.sh

# Performance baseline test
./scripts/performance_test.sh --duration 300
```

### **📊 Configuration Templates**
```bash
# Generate configuration templates
./scripts/generate_config.sh --type basic > config/basic_sim.json
./scripts/generate_config.sh --type advanced > config/advanced_sim.json
./scripts/generate_config.sh --type production > config/prod_sim.json
```

---

## 🔧 **Advanced Configuration**

### **🎛️ Custom Actor Configuration**
```json
{
  "customActors": {
    "com.yourcompany.CustomActor": {
      "defaultConfig": {
        "param1": "value1",
        "param2": 123
      },
      "creationStrategy": "singleton",
      "dependencies": ["database", "redis"]
    }
  }
}
```

### **🔌 Plugin Configuration**
```json
{
  "plugins": [
    {
      "name": "traffic-optimization",
      "className": "com.htc.plugins.TrafficOptimizationPlugin",
      "enabled": true,
      "config": {
        "algorithm": "genetic",
        "populationSize": 100
      }
    }
  ]
}
```

### **📊 Monitoring Integration**
```yaml
# Prometheus monitoring
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/etc/prometheus/console_libraries'
```

---

**🎯 This configuration guide provides you with complete control over your HTC simulation environment. Start with the basic configurations and gradually explore advanced options as your needs grow.**

For specific configuration examples, see the [examples directory](examples/) and the [Scenario Creation Guide](SCENARIO_CREATION.md).