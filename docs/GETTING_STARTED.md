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
# Java Development Kit 11+
java -version  # Should show 11+ (OpenJDK or Oracle)

# Scala Build Tool (SBT)
sbt --version  # Should show 1.9.0+

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

# If Java 11+ is not installed (Ubuntu/Debian):
sudo apt update
sudo apt install openjdk-11-jdk

# Set JAVA_HOME (add to ~/.bashrc)
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
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

### **Option 1: One-Click Start (Recommended)**
```bash
# Automated setup with optimal configuration
./htc-manager.sh

# This will:
# 1. Check system requirements
# 2. Start services with optimal settings
# 3. Initialize database schema
# 4. Show management menu
```

### **Option 2: Manual Step-by-Step**

#### **Step 1: Start Infrastructure Services**
```bash
# Start Cassandra and Redis
docker compose up -d cassandra redis

# Wait for services to be ready
docker compose logs -f cassandra

# Look for: "Listening for thrift clients..."
# Press Ctrl+C when ready
```

#### **Step 2: Initialize Database**
```bash
# Run database initialization
./manage_cassandra.sh start

# Verify database is ready
./check_cassandra_data.sh
```

#### **Step 3: Build and Run Simulation**
```bash
# Build the Scala application
sbt compile

# Run with Docker Compose
docker compose up -d

# Check logs
docker compose logs -f node1
```

---

## 🎯 **Your First Simulation**

### **1. Create a Simple Configuration**

Create a basic simulation configuration file:
```bash
mkdir -p data/examples
cat > data/examples/basic_simulation.json << 'EOF'
{
  "simulation": {
    "name": "Basic Traffic Simulation",
    "description": "A simple example simulation",
    "startTick": 0,
    "startRealTime": "2025-01-27T08:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 3600,
    "randomSeed": 42,
    "actorsDataSources": [
      {
        "id": "basic-vehicles",
        "classType": "com.htc.traffic.VehicleActor",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/examples/vehicles.json"
          }
        }
      }
    ]
  }
}
EOF
```

### **2. Create Sample Vehicle Data**
```bash
cat > data/examples/vehicles.json << 'EOF'
[
  {
    "id": "vehicle_001",
    "name": "Car 1",
    "typeActor": "com.htc.traffic.VehicleActor",
    "data": {
      "dataType": "vehicle",
      "content": {
        "startTime": 0,
        "route": ["link_1", "link_2", "link_3"],
        "vehicleType": "car",
        "maxSpeed": 50.0
      }
    }
  },
  {
    "id": "vehicle_002", 
    "name": "Car 2",
    "typeActor": "com.htc.traffic.VehicleActor",
    "data": {
      "dataType": "vehicle",
      "content": {
        "startTime": 30,
        "route": ["link_2", "link_3", "link_4"],
        "vehicleType": "car",
        "maxSpeed": 45.0
      }
    }
  }
]
EOF
```

### **3. Run the Simulation**
```bash
# Set simulation configuration
export SIMULATION_CONFIG_PATH=data/examples/basic_simulation.json

# Run simulation
docker compose up node1

# Monitor progress in another terminal
docker compose logs -f node1
```

### **4. Monitor Simulation**
```bash
# Wait for simulation to complete (look for "Simulation finished" in logs)

# Check database for simulation results
./manage_cassandra.sh status

# View Cassandra data
docker exec -it cassandra-container cqlsh
```

Expected Cassandra keyspace and tables:
```sql
USE htc_simulation;
DESCRIBE TABLES;

-- Expected tables:
-- simulation_events
-- vehicle_states  
-- link_flows
-- time_aggregated_data
```

---

## 📊 **Understanding the Results**

### **📋 Simulation Data Structure**
The simulation stores data in Cassandra with the following structure:

**simulation_events** table:
```sql
SELECT * FROM simulation_events LIMIT 5;
```

**vehicle_states** table:
```sql
SELECT * FROM vehicle_states LIMIT 5;
```

### **📈 Querying Results**
Access your simulation data directly through Cassandra:
```bash
# Enter Cassandra shell
docker exec -it cassandra-container cqlsh

# Use simulation keyspace
USE htc_simulation;

# Count total events
SELECT COUNT(*) FROM simulation_events;

# View recent vehicle states
SELECT * FROM vehicle_states 
WHERE timestamp > '2025-01-27 08:00:00' 
LIMIT 10;
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

### **🎭 Actor Types**
- **VehicleActor**: Individual vehicles with routing behavior
- **IntersectionActor**: Traffic intersections with signal control
- **SignalActor**: Traffic signal timing control
- **Custom actors**: Your own domain-specific actors

### **🚀 Creation Types**
- **LoadBalancedDistributed**: Automatically distributed across cluster nodes
- **PoolDistributed**: Fixed pools of actors per node
- **Simple**: Single instance actors

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

# Performance benchmarks
./scripts/run_benchmarks.sh
```

### **📊 Sample Data Generation**
```bash
# Generate larger sample datasets
./scripts/generate_sample_data.sh --vehicles 1000 --duration 7200

# Run comparison tests
./run_comparison.sh --sample
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

# Problem: Python version
python3 --version  # Must be 3.8+
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

### **🔬 Research**
1. **📝 See [Academic Usage](ACADEMIC_USAGE.md)** for research guidelines
2. **🆚 Explore [Simulator Comparison](SIMULATOR_COMPARISON.md)** for validation
3. **📈 Review [Benchmarks](BENCHMARKS.md)** for performance analysis

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