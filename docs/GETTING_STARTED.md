# 🚀 Getting Started

Welcome to the Hyperbolic Time Chamber! This guide will help you set up your first simulation environment and run your first traffic simulation. By the end of this guide, you'll have a running HTC system and understand the basic workflow.

---

## 📋 **Prerequisites**

Before starting, ensure your system meets the minimum requirements:

### **🖥️ System Requirements**
- **Operating System**: Linux (Ubuntu 20.04+), macOS (10.15+), or Windows 10+ with WSL2
- **CPU**: 4+ cores (8+ cores recommended for better performance)
- **RAM**: 8GB minimum (16GB+ recommended for larger simulations)
- **Storage**: 10GB+ free space for data, logs, and Docker images
- **Network**: Stable internet connection for downloading dependencies

### **💻 Required Software**

#### **Essential Dependencies**
```bash
# Java Development Kit 21+
java -version  # Should show 21+ (OpenJDK or Oracle)

# Scala Build Tool (SBT)
sbt --version  # Should show 1.9.0+

# Scala
scala --version  # Should show 3.3.5+

# Docker and Docker Compose
docker --version         # Should show 20.10+
docker-compose --version # Should show 2.0+
```

#### **Optional but Recommended**
```bash
# Git for version control
git --version

# curl for API testing
curl --version

# jq for JSON processing
jq --version
```

---

## 🛠️ **Installation**

### **Step 1: Clone the Repository**
```bash
# Clone the repository
git clone https://github.com/your-repo/hyperbolic-time-chamber.git
cd hyperbolic-time-chamber

# Check the project structure
ls -la
```

Expected output:
```
drwxr-xr-x  build-and-run.sh
drwxr-xr-x  build.sbt
drwxr-xr-x  docker-compose.yml
drwxr-xr-x  docs/
drwxr-xr-x  src/
drwxr-xr-x  cassandra-config/
drwxr-xr-x  cassandra-init/
...
```

### **Step 2: Environment Setup**

#### **☕ Java Environment Verification**
```bash
# Check Java version
java -version

# If Java 21+ is not installed (Ubuntu/Debian):
sudo apt update
sudo apt install openjdk-21-jdk

# Set JAVA_HOME (add to ~/.bashrc)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin
```

#### **🔨 SBT Installation**
```bash
# Ubuntu/Debian
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt update
sudo apt install sbt

# macOS with Homebrew
brew install sbt

# Verify installation
sbt --version
```

### **Step 3: Docker Setup**

#### **🐳 Docker Installation**
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install docker.io docker-compose-plugin
sudo systemctl start docker
sudo systemctl enable docker

# Add user to docker group (logout/login required)
sudo usermod -aG docker $USER

# Verify installation
docker --version
docker compose version
```

#### **🔍 System Diagnosis (Recommended)**
```bash
# Run comprehensive system check
./diagnose.sh

# Expected output includes:
# ✅ Docker installation and status
# ✅ System resources (CPU, RAM, disk)
# ✅ Java and SBT versions
# ✅ Network connectivity
# ✅ Available storage space
```

---

## 🏃‍♂️ **Quick Start**

### **Recommended: Build and Run Script**
```bash
# The easiest way to run the application
./build-and-run.sh

# This script will:
# 1. Clean the project
# 2. Generate the assembly
# 3. Create Docker image
# 4. Start the simulation
```

### **Alternative: Manual Step-by-Step**

#### **Step 1: Start Infrastructure Services (Optional)**
```bash
# Note: For better performance, prefer pre-configured JSON over Cassandra
# Cassandra connection can slow down the simulation

# If you need Cassandra and Redis:
docker compose up -d cassandra redis

# Wait for services to be ready
docker compose logs -f cassandra

# Look for: "Listening for thrift clients..."
# Press Ctrl+C when ready
```

#### **Step 2: Build and Run**
```bash
# Build the Scala application
sbt compile

# Or use the complete build script
./build-and-run.sh
```

---

## 🎯 **Your First Simulation**

### **1. Understanding the Mobility Model**

The HTC implements a specific mobility model with three main actor types:

#### **🚗 Car Actor**
Cars represent vehicles moving through the network:
```json
{
  "id": "htcaid:car;trip_1",
  "name": "Car Trip 1",
  "typeActor": "mobility.actor.Car",
  "data": {
    "dataType": "model.mobility.entity.state.CarState",
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "linkOrigin": "htcaid:link;2067",
      "scheduleOnTimeManager": true
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;60609822",
      "resourceId": "htcrid:node;5",
      "classType": "mobility.actor.Node",
      "actorType": "LoadBalancedDistributed"
    },
    "to_node": {
      "id": "htcaid:node;4922987596",
      "resourceId": "htcrid:node;4",
      "classType": "mobility.actor.Node",
      "actorType": "LoadBalancedDistributed"
    }
  }
}
```

#### **📍 Node Actor**
Nodes represent intersections or points in the network:
```json
{
  "id": "htcaid:node;1001568643",
  "name": "Node1001568643",
  "typeActor": "mobility.actor.Node",
  "data": {
    "dataType": "mobility.entity.state.NodeState",
    "content": {
      "startTick": 0,
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686",
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {}
}
```

#### **🛣️ Link Actor**
Links represent road segments connecting nodes:
```json
{
  "id": "htcaid:link;1",
  "name": "Link1",
  "typeActor": "mobility.actor.Link",
  "data": {
    "dataType": "model.mobility.entity.state.LinkState",
    "content": {
      "startTick": 0,
      "from_node": "htcaid:node;394923340",
      "to_node": "htcaid:node;2033271141",
      "length": 13.539593517363432,
      "lanes": 1,
      "freeSpeed": 4.166666666666667,
      "capacity": 600.0,
      "modes": ["car"],
      "linkType": "residential",
      "scheduleOnTimeManager": false
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;394923340",
      "resourceId": "htcrid:node;3",
      "classType": "mobility.actor.Node",
      "actorType": "LoadBalancedDistributed"
    },
    "to_node": {
      "id": "htcaid:node;2033271141",
      "resourceId": "htcrid:node;2",
      "classType": "mobility.actor.Node",
      "actorType": "LoadBalancedDistributed"
    }
  }
}
```

### **2. Map Structure**
The simulation uses a graph-based map structure:
```json
{
  "nodes": [
    {
      "id": "htcaid:node;1001568643",
      "classType": "mobility.actor.Node",
      "resourceId": "htcrid:node;1",
      "latitude": "-7347433.28816257",
      "longitude": "-2852981.6323715686"
    }
  ],
  "edges": [
    {
      "source_id": "htcaid:node;394923340",
      "target_id": "htcaid:node;2033271141",
      "weight": 13.539593517363432,
      "label": {
        "id": "htcaid:link;1",
        "resourceId": "htcrid:link;1",
        "classType": "mobility.actor.Link",
        "length": 13.539593517363432
      }
    }
  ],
  "directed": true
}
```

### **3. Creating Your First Simulation**

Create a simple configuration with your data files:
```bash
mkdir -p data/examples

# Use pre-configured JSON files for better performance
# (Avoid Cassandra connections as they can slow the simulation)
    "timeStep": 1,
    "duration": 3600,
    "randomSeed": 42,
    "actorsDataSources": [
      {
# Use pre-configured JSON files for better performance
# (Avoid Cassandra connections as they can slow the simulation)

# Run your simulation
./build-and-run.sh
```

### **4. Custom Mobility Models**

You can create your own mobility models beyond the default car/node/link structure. The framework is designed to be extensible, allowing you to implement custom actor types and behaviors specific to your research needs.

---

## 📊 **Understanding the Results**

### **📋 Data Storage Options**

#### **Recommended: JSON Files (Better Performance)**
For optimal simulation performance, use pre-configured JSON files instead of Cassandra:
```bash
# Your simulation data will be processed directly from JSON files
# This avoids database connection overhead that can slow simulations
```

#### **Optional: Cassandra Database**
If you need persistent storage, the simulation can store data in Cassandra:

**simulation_events** table:
```sql
SELECT * FROM simulation_events LIMIT 5;
```

**vehicle_states** table:
```sql
SELECT * FROM vehicle_states LIMIT 5;
```

### **📈 Monitoring Your Simulation**
```bash
# Monitor simulation progress
./build-and-run.sh

# Check logs for simulation status
docker compose logs -f

# Look for completion messages and performance metrics
```

---

## 🔧 **Configuration Options**

### **📝 Basic Configuration Template**
```json
{
  "simulation": {
    "name": "Your Simulation Name",
    "description": "Description of your simulation",
    "startTick": 0,
    "startRealTime": "2025-01-27T08:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 3600,
    "randomSeed": 42,
    "actorsDataSources": [
      // Actor configuration here
    ]
  }
}
```

### **🎭 Actor Types in Mobility Model**
- **mobility.actor.Car**: Individual vehicles with origin-destination routing
- **mobility.actor.Node**: Network intersections with GPS coordinates
- **mobility.actor.Link**: Road segments with capacity and speed limits
- **mobility.actor.GPS**: Optional GPS tracking actors
- **Custom actors**: Your own domain-specific mobility models

### **🏗️ Actor Creation Patterns**
- **LoadBalancedDistributed**: Automatically distributed across cluster nodes
- **PoolDistributed**: Fixed pools of actors per node
- **Simple**: Single instance actors

### **🔧 Performance Tips**
- Use pre-configured JSON files instead of Cassandra for faster simulations
- GPS dependencies are optional and can be omitted
- Consider your map size and actor count for optimal performance

---

## 🔍 **Verification & Testing**

### **✅ Health Checks**
```bash
# Check all services
docker compose ps

# Verify Cassandra
docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES;"

# Verify Redis
docker exec htc-redis redis-cli ping

# Check node connectivity
docker compose logs node1 | grep "Cluster"
```

### **🧪 Run Test Suite**
```bash
# Unit tests
sbt test

# Integration tests
sbt it:test

# Check test coverage
sbt clean coverage test coverageReport
```

---

## 🚨 **Troubleshooting**

### **Common Issues**

#### **🐳 Docker Issues**
```bash
# Problem: Docker daemon not running
sudo systemctl start docker

# Problem: Permission denied
sudo usermod -aG docker $USER
# Logout and login again

# Problem: Port conflicts
docker compose down
sudo netstat -tulpn | grep :9042  # Check if port is used
```

#### **☕ Java/SBT Issues**
```bash
# Problem: Wrong Java version
sudo update-alternatives --config java

# Problem: SBT compilation errors
sbt clean compile

# Problem: OutOfMemoryError
export SBT_OPTS="-Xmx4G -XX:+UseG1GC"
```

#### **🗄️ Database Issues**
```bash
# Problem: Cassandra won't start
docker compose logs cassandra

# Problem: Connection refused
./manage_cassandra.sh reset

# Problem: Data inconsistency
./manage_cassandra.sh clean

# Problem: Out of disk space
df -h
docker system prune -a
```

### **🔧 System Optimization**
```bash
# Increase system limits for large simulations
echo "vm.max_map_count=1048575" | sudo tee -a /etc/sysctl.conf
echo "fs.file-max=65536" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p

# Docker resource allocation
# Edit ~/.docker/daemon.json:
{
  "default-ulimits": {
    "memlock": {"hard": -1, "soft": -1},
    "nofile": {"hard": 65536, "soft": 65536}
  }
}
```

---

## 📚 **Next Steps**

### **🎓 Learning Path**
1. **📖 Read the [Architecture Overview](ARCHITECTURE.md)** to understand system design
2. **⚙️ Explore [Configuration Guide](CONFIGURATION.md)** for advanced settings  
3. **🎬 Learn [Scenario Creation](SCENARIO_CREATION.md)** to build complex simulations
4. **📊 Study [Traffic Analysis](TRAFFIC_ANALYSIS.md)** for result interpretation

### **🛠️ Development**
1. **🔍 Check [API Reference](API_REFERENCE.md)** for programmatic access
2. **👨‍💻 Read [Developer Guide](DEVELOPER_GUIDE.md)** to contribute or extend
3. **🚀 Review [Deployment Guide](DEPLOYMENT.md)** for production setups


---

## 🆘 **Getting Help**

### **📖 Documentation**
- Complete documentation in the `docs/` directory
- API documentation: `docs/API_REFERENCE.md`
- Configuration reference: `docs/CONFIGURATION.md`

### **💬 Community Support**
- **GitHub Issues**: Bug reports and feature requests
- **Discord**: Real-time community discussion
- **Stack Overflow**: Tag questions with `hyperbolic-time-chamber`
- **Academic Mailing List**: Research collaboration and questions

### **🔧 Professional Support**
- Commercial support available for enterprise deployments
- Custom development and consulting services
- Training workshops and certification programs

---

**🎉 Congratulations! You now have a working Hyperbolic Time Chamber simulation environment. Start exploring the system and building your own traffic scenarios!**

*Remember: Every expert was once a beginner. The HTC community is here to help you succeed in your urban mobility simulation journey.* 🚗⚡