# 🐛 Troubleshooting Guide

Comprehensive troubleshooting guide for the Hyperbolic Time Chamber simulation framework. This guide covers common issues, diagnostic procedures, and solutions for various problems you might encounter.

---

## 🎯 **Quick Diagnosis**

### **⚡ System Health Check**
```bash
# Run comprehensive system diagnosis
./diagnose.sh

# Quick health check
./diagnose.sh --quick

# Specific component check
./diagnose.sh docker      # Docker setup
./diagnose.sh cassandra   # Database connectivity  
./diagnose.sh java        # Java environment
./diagnose.sh network     # Network connectivity
```

### **🔍 Common Symptoms**
| Symptom | Likely Cause | Quick Fix |
|---------|--------------|-----------|
| Simulation won't start | Missing dependencies | `./diagnose.sh` then follow recommendations |
| "Connection refused" | Services not running | `docker compose up -d` |
| OutOfMemoryError | Insufficient memory | Reduce simulation size or increase heap |
| Slow performance | Resource constraints | Check system resources and optimize |
| No output generated | Configuration error | Validate configuration files |

---

## 🐳 **Docker Issues**

### **❌ Docker Won't Start**

#### **Problem**: Docker daemon not running
```bash
# Symptoms
docker: Cannot connect to the Docker daemon at unix:///var/run/docker.sock

# Solution
sudo systemctl start docker
sudo systemctl enable docker
```

#### **Problem**: Permission denied
```bash
# Symptoms
Got permission denied while trying to connect to the Docker daemon socket

# Solution
sudo usermod -aG docker $USER
# Log out and log back in, then:
docker --version
```

#### **Problem**: Docker Compose command not found
```bash
# Symptoms
bash: docker-compose: command not found

# Solution (Ubuntu/Debian)
sudo apt update
sudo apt install docker-compose-plugin

# Or use docker compose (without hyphen)
docker compose --version
```

### **🐳 Container Issues**

#### **Problem**: Containers fail to start
```bash
# Check container status
docker compose ps

# Check logs
docker compose logs cassandra
docker compose logs node1

# Common solutions
docker compose down
docker system prune -f
docker compose up -d
```

#### **Problem**: Port conflicts
```bash
# Symptoms
Error starting userland proxy: listen tcp4 0.0.0.0:9042: bind: address already in use

# Find process using port
sudo netstat -tulpn | grep :9042
sudo lsof -i :9042

# Kill conflicting process
sudo kill -9 <PID>

# Or change port in docker-compose.yml
ports:
  - "9043:9042"  # Use different external port
```

#### **Problem**: Out of disk space
```bash
# Check disk usage
df -h
docker system df

# Clean up Docker resources
docker system prune -f
docker volume prune -f
docker image prune -f

# Remove unused containers and images
docker container prune -f
docker rmi $(docker images -q --filter dangling=true)
```

---

## ☕ **Java/SBT Issues**

### **❌ Java Version Problems**

#### **Problem**: Wrong Java version
```bash
# Check current version
java -version
javac -version

# Install correct version (Ubuntu/Debian)
sudo apt update
sudo apt install openjdk-11-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
echo 'export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64' >> ~/.bashrc

# Switch between versions (if multiple installed)
sudo update-alternatives --config java
```

#### **Problem**: JAVA_HOME not set
```bash
# Symptoms
Error: JAVA_HOME is not defined correctly

# Solution
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin

# Make permanent
echo 'export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64' >> ~/.bashrc
echo 'export PATH=$PATH:$JAVA_HOME/bin' >> ~/.bashrc
source ~/.bashrc
```

### **🔨 SBT Problems**

#### **Problem**: SBT won't start
```bash
# Check SBT installation
sbt --version

# Install SBT (Ubuntu/Debian)
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt update
sudo apt install sbt
```

#### **Problem**: SBT compilation errors
```bash
# Clean and recompile
sbt clean
sbt compile

# Update dependencies
sbt update

# Increase memory for SBT
export SBT_OPTS="-Xmx4G -XX:+UseG1GC"

# Clear ivy cache if dependency issues
rm -rf ~/.ivy2/cache
sbt clean update compile
```

#### **Problem**: OutOfMemoryError during build
```bash
# Increase JVM heap size
export SBT_OPTS="-Xmx8G -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Or create .sbtopts file
echo "-J-Xmx8G" > .sbtopts
echo "-J-XX:+UseG1GC" >> .sbtopts

# For Docker builds
docker compose build --build-arg SBT_OPTS="-Xmx8G"
```

---

## 🗄️ **Database Issues**

### **❌ Cassandra Problems**

#### **Problem**: Cassandra won't start
```bash
# Check Cassandra logs
docker logs htc-cassandra-db

# Common issues and solutions:

# 1. Insufficient memory
# Edit docker-compose.yml:
environment:
  - MAX_HEAP_SIZE=1G
  - HEAP_NEWSIZE=200M

# 2. Port already in use
# Check what's using port 9042
sudo netstat -tulpn | grep :9042

# 3. Data corruption
docker compose down
docker volume rm htc_cassandra_data
docker compose up -d cassandra
```

#### **Problem**: Connection timeouts
```bash
# Symptoms
com.datastax.driver.core.exceptions.NoHostAvailableException

# Solutions:
# 1. Wait for Cassandra to fully start
docker logs htc-cassandra-db | grep "Listening for thrift clients"

# 2. Increase timeout in application.conf
cassandra {
  connection-timeout = 30 seconds
  read-timeout = 60 seconds
}

# 3. Check network connectivity
docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES;"
```

#### **Problem**: WriteTimeoutException
```bash
# Symptoms
WriteTimeoutException: Cassandra timeout during SIMPLE write query

# Solutions:
# 1. Reduce write load
# In simulation config, reduce batch sizes:
"batchSize": 100  # Instead of 1000

# 2. Increase Cassandra timeouts
# In cassandra.yaml:
write_request_timeout_in_ms: 10000
range_request_timeout_in_ms: 15000

# 3. Optimize system resources
echo 'vm.swappiness=1' | sudo tee -a /etc/sysctl.conf
echo 'vm.max_map_count=1048575' | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

### **📊 Redis Issues**

#### **Problem**: Redis connection refused
```bash
# Check Redis status
docker logs htc-redis

# Test connection
docker exec htc-redis redis-cli ping

# Restart Redis if needed
docker compose restart redis
```

#### **Problem**: Redis memory issues
```bash
# Check Redis memory usage
docker exec htc-redis redis-cli INFO memory

# Configure maxmemory policy
docker exec htc-redis redis-cli CONFIG SET maxmemory-policy allkeys-lru
docker exec htc-redis redis-cli CONFIG SET maxmemory 1gb
```

---

## 🚀 **Performance Issues**

### **⚡ Slow Simulation Performance**

#### **Problem**: High CPU usage
```bash
# Monitor CPU usage
top -p $(pgrep java)
htop

# Solutions:
# 1. Reduce simulation complexity
# 2. Use faster time steps
# 3. Optimize actor creation strategy

# In simulation config:
{
  "timeStep": 5,  # Larger time steps
  "creationType": "PoolDistributed"  # More efficient for many actors
}
```

#### **Problem**: High memory usage
```bash
# Monitor memory
free -h
docker stats

# Solutions:
# 1. Increase JVM heap size
export JAVA_OPTS="-Xms4g -Xmx8g -XX:+UseG1GC"

# 2. Optimize garbage collection
export JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions"

# 3. Reduce actor memory footprint
# Use more efficient data structures
# Reduce state complexity
```

#### **Problem**: Slow database writes
```bash
# Monitor database performance
docker exec htc-cassandra-db nodetool tpstats

# Solutions:
# 1. Increase write batch size
"reporting": {
  "cassandra": {
    "batchSize": 5000  # Larger batches
  }
}

# 2. Use async writes
"reporting": {
  "async": true,
  "bufferSize": 10000
}

# 3. Optimize Cassandra settings
# In cassandra.yaml:
concurrent_writes: 64
memtable_heap_space_in_mb: 2048
```

### **🔧 System Resource Optimization**

#### **Memory Optimization**
```bash
# Increase system limits
sudo sysctl -w vm.max_map_count=1048575
sudo sysctl -w vm.swappiness=1
sudo sysctl -w fs.file-max=65536

# Make permanent
echo 'vm.max_map_count=1048575' | sudo tee -a /etc/sysctl.conf
echo 'vm.swappiness=1' | sudo tee -a /etc/sysctl.conf
echo 'fs.file-max=65536' | sudo tee -a /etc/sysctl.conf

# Restart to apply
sudo reboot
```

#### **Network Optimization**
```bash
# Increase network buffers
sudo sysctl -w net.core.rmem_max=134217728
sudo sysctl -w net.core.wmem_max=134217728
sudo sysctl -w net.ipv4.tcp_rmem="4096 16384 134217728"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 134217728"
```

---

## 📊 **Configuration Issues**

### **❌ Invalid Configuration**

#### **Problem**: JSON parsing errors
```bash
# Symptoms
com.fasterxml.jackson.core.JsonParseException: Unexpected character

# Validate JSON syntax
cat config/simulation.json | jq .

# Common fixes:
# 1. Remove trailing commas
# 2. Quote all string values
# 3. Use proper JSON syntax

# Use JSON validator
./scripts/validate_config.sh config/simulation.json
```

#### **Problem**: Missing required fields
```bash
# Symptoms
Configuration validation failed: Missing required field 'duration'

# Check required fields
./scripts/validate_config.sh --verbose config/simulation.json

# Generate template with all required fields
./scripts/generate_config_template.sh > config/complete_template.json
```

#### **Problem**: Invalid actor configuration
```bash
# Symptoms
Unknown actor type: com.example.InvalidActor

# Check available actor types
grep -r "classType" examples/*/config/

# Verify actor class exists
find . -name "*.scala" | xargs grep "class.*Actor"
```

---

## 🔌 **Network Connectivity Issues**

### **❌ Service Discovery Problems**

#### **Problem**: Services can't find each other
```bash
# Check Docker network
docker network ls
docker network inspect htc-network

# Test connectivity between containers
docker exec node1 ping cassandra
docker exec node1 nslookup cassandra

# Solutions:
# 1. Restart Docker network
docker compose down
docker compose up -d

# 2. Use explicit service names in configuration
cassandra {
  contact-points = ["htc-cassandra-db:9042"]  # Use container name
}
```

#### **Problem**: External connectivity issues
```bash
# Test internet connectivity
docker exec node1 ping 8.8.8.8
docker exec node1 curl -I https://google.com

# Check DNS resolution
docker exec node1 nslookup google.com

# Configure Docker DNS if needed
# In docker-compose.yml:
services:
  node1:
    dns:
      - 8.8.8.8
      - 8.8.4.4
```

---

## 📈 **Monitoring and Debugging**

### **🔍 Debugging Tools**

#### **Application Logs**
```bash
# View application logs
docker compose logs -f node1

# Filter logs by level
docker compose logs node1 | grep ERROR
docker compose logs node1 | grep WARN

# Save logs for analysis
docker compose logs --no-color > simulation_logs.txt
```

#### **JVM Debugging**
```bash
# Enable JMX monitoring
export JAVA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"

# Enable heap dumps on OOM
export JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof"

# Enable GC logging
export JAVA_OPTS="-XX:+PrintGC -XX:+PrintGCDetails \
  -XX:+PrintGCTimeStamps -Xloggc:/app/logs/gc.log"
```

#### **Performance Profiling**
```bash
# Use built-in profiler
docker exec node1 jstack $(pgrep java)  # Thread dump
docker exec node1 jmap -histo $(pgrep java)  # Memory histogram

# Monitor real-time performance
docker exec node1 jstat -gc $(pgrep java) 5s  # GC stats every 5 seconds
```

### **📊 Health Monitoring**

#### **System Health Scripts**
```bash
# Monitor system resources
./scripts/monitor_resources.sh

# Check service health
./scripts/health_check.sh

# Performance monitoring
./scripts/performance_monitor.sh --duration 300  # Monitor for 5 minutes
```

---

## 🆘 **Emergency Recovery**

### **🚨 Complete System Reset**

#### **Nuclear Option**: Reset Everything
```bash
# Stop all services
docker compose down

# Remove all data
docker volume prune -f
docker system prune -f

# Clean workspace
rm -rf logs/* target/* scripts/output/*

# Restart fresh
docker compose up -d
./diagnose.sh
```

#### **Backup and Restore**
```bash
# Create system backup
./scripts/backup_system.sh

# Restore from backup
./scripts/restore_system.sh backup_20250127.tar.gz

# Restore specific components
./scripts/restore_cassandra.sh cassandra_backup.tar.gz
```

---

## 📞 **Getting Help**

### **🔍 Self-Diagnosis Checklist**
Before seeking help, try these steps:

1. [ ] Run `./diagnose.sh` and follow recommendations
2. [ ] Check recent log files for error messages
3. [ ] Verify system meets minimum requirements
4. [ ] Try with a minimal configuration
5. [ ] Search existing issues on GitHub
6. [ ] Check documentation for similar problems

### **🆘 Reporting Issues**

#### **Information to Include**
```bash
# Generate diagnostic report
./scripts/generate_diagnostic_report.sh

# This creates: diagnostic_report_TIMESTAMP.tar.gz containing:
# - System information
# - Configuration files
# - Recent log files
# - Error messages
# - Performance metrics
```

#### **Issue Template**
```markdown
## Problem Description
Brief description of the issue

## Environment
- OS: Ubuntu 22.04
- Java Version: 11.0.19
- Docker Version: 24.0.2
- HTC Version: 1.5.0

## Steps to Reproduce
1. Step 1
2. Step 2
3. Step 3

## Expected Behavior
What should happen

## Actual Behavior
What actually happens

## Error Messages
```
Copy error messages here
```

## Additional Context
Any other relevant information
```

### **📞 Support Channels**
- **GitHub Issues**: For bugs and feature requests
- **Discord Community**: For real-time help and discussions
- **Stack Overflow**: Tag questions with `hyperbolic-time-chamber`
- **Documentation**: Check the complete documentation first

---

**🎯 This troubleshooting guide covers the most common issues you'll encounter with HTC. Most problems can be resolved quickly with the right diagnostic approach. Remember: when in doubt, start with `./diagnose.sh`!**