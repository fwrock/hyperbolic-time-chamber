#!/bin/bash

# Kafka Topics Setup for HTC
# This script creates all necessary Kafka topics for the Hyperbolic Time Chamber

echo "🚀 Setting up Kafka topics for HTC..."

# Kafka broker address
KAFKA_BROKER="localhost:9092"

# Function to create topic if it doesn't exist
create_topic() {
    local topic_name=$1
    local partitions=$2
    local replication_factor=$3
    local description=$4
    
    echo "📝 Creating topic: $topic_name (partitions: $partitions)"
    
    docker exec htc-kafka kafka-topics \
        --bootstrap-server $KAFKA_BROKER \
        --create \
        --if-not-exists \
        --topic $topic_name \
        --partitions $partitions \
        --replication-factor $replication_factor \
        --config compression.type=snappy \
        --config cleanup.policy=delete \
        --config retention.ms=86400000
    
    if [ $? -eq 0 ]; then
        echo "✅ Topic $topic_name created successfully"
    else
        echo "❌ Failed to create topic $topic_name"
    fi
}

# Wait for Kafka to be ready
echo "⏳ Waiting for Kafka to be ready..."
for i in {1..30}; do
    if docker exec htc-kafka kafka-broker-api-versions --bootstrap-server $KAFKA_BROKER > /dev/null 2>&1; then
        echo "✅ Kafka is ready!"
        break
    else
        echo "🔄 Waiting for Kafka... ($i/30)"
        sleep 2
    fi
done

# Create topics for different HTC modules

echo ""
echo "🎯 Creating HTC Core Topics..."

# Core event streaming
create_topic "htc.events" 12 1 "HTC simulation events"
create_topic "htc.events.avro" 12 1 "HTC simulation events (Avro format)"

# Reporting and metrics
create_topic "htc.reports" 6 1 "HTC simulation reports" 
create_topic "htc.reports.avro" 6 1 "HTC simulation reports (Avro format)"
create_topic "htc.custom-reports" 3 1 "HTC custom reports"

# Command and control
create_topic "htc.commands" 3 1 "HTC simulation commands"
create_topic "htc.status" 3 1 "HTC simulation status"

# State synchronization
create_topic "htc.state-sync" 6 1 "HTC state synchronization"
create_topic "htc.state-sync.avro" 6 1 "HTC state sync (Avro format)"

echo ""
echo "🚗 Creating Vehicle Data Topics..."

# Vehicle streaming (high volume)
create_topic "htc.vehicles.positions" 24 1 "Vehicle position updates"
create_topic "htc.vehicles.positions.avro" 24 1 "Vehicle positions (Avro - high performance)"
create_topic "htc.vehicles.movements" 12 1 "Vehicle movement events"
create_topic "htc.vehicles.speed-alerts" 6 1 "Vehicle speed alerts"

echo ""
echo "📊 Creating Traffic Analysis Topics..."

# Traffic analysis
create_topic "htc.traffic.reports" 12 1 "Traffic analysis reports"
create_topic "htc.traffic.reports.avro" 12 1 "Traffic reports (Avro format)"
create_topic "htc.traffic.congestion" 6 1 "Traffic congestion events"
create_topic "htc.traffic.incidents" 3 1 "Traffic incidents"

echo ""
echo "🔬 Creating Hybrid Micro Simulation Topics..."

# Hybrid micro simulation (ultra high volume - Avro only)
create_topic "htc.micro.updates" 48 1 "Microscopic vehicle updates"
create_topic "htc.micro.updates.avro" 48 1 "Micro updates (Avro - ultra high performance)"
create_topic "htc.micro.lane-changes" 12 1 "Lane change events"
create_topic "htc.micro.following-updates" 24 1 "Car-following updates"
create_topic "htc.micro.intersections" 6 1 "Intersection micro events"

echo ""
echo "🌐 Creating Integration Topics..."

# External integration
create_topic "htc.integration.requests" 6 1 "External integration requests"
create_topic "htc.integration.responses" 6 1 "External integration responses"
create_topic "htc.route-requests" 6 1 "Dynamic route requests"
create_topic "htc.route-responses" 6 1 "Dynamic route responses"

echo ""
echo "🔄 Creating Request-Response Topics..."

# Request-response patterns
create_topic "htc.rpc.route-calculation" 6 1 "Route calculation requests"
create_topic "htc.rpc.traffic-optimization" 3 1 "Traffic optimization requests"
create_topic "htc.rpc.simulation-control" 3 1 "Simulation control requests"

echo ""
echo "📈 Creating Monitoring Topics..."

# Monitoring and observability
create_topic "htc.monitoring.metrics" 6 1 "Performance metrics"
create_topic "htc.monitoring.logs" 3 1 "Application logs"
create_topic "htc.monitoring.health" 3 1 "Health check data"

echo ""
echo "🧪 Creating Test Topics..."

# Test topics for development
create_topic "htc.test.json-performance" 3 1 "JSON performance testing"
create_topic "htc.test.avro-performance" 3 1 "Avro performance testing"
create_topic "htc.test.consumer-groups" 3 1 "Consumer group testing"

echo ""
echo "📋 Listing all HTC topics..."
docker exec htc-kafka kafka-topics --bootstrap-server $KAFKA_BROKER --list | grep htc

echo ""
echo "✅ Kafka topic setup completed!"
echo ""
echo "🎯 Quick Access URLs:"
echo "   Kafka UI: http://localhost:8080"
echo "   Schema Registry: http://localhost:8081"
echo ""
echo "🔧 Useful commands:"
echo "   List topics: docker exec htc-kafka kafka-topics --bootstrap-server localhost:9092 --list"
echo "   Topic details: docker exec htc-kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic htc.events"
echo "   Consume messages: docker exec htc-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic htc.events --from-beginning"
echo ""
echo "📊 Performance optimized topics (with Avro):"
echo "   htc.vehicles.positions.avro (vehicle tracking)"
echo "   htc.traffic.reports.avro (traffic analysis)"  
echo "   htc.micro.updates.avro (micro simulation)"
echo ""