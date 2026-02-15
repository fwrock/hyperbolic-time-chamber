#!/bin/bash

# Kafka Topic Initialization Script for HTC Simulation
# This script ensures all required Kafka topics exist before starting the simulation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "🏗️  HTC Kafka Topic Initialization"
echo "=================================="

# Configuration
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
ENVIRONMENT="${HTC_KAFKA_ENV_PREFIX:-dev}"

echo "📍 Kafka Bootstrap Servers: $KAFKA_BOOTSTRAP_SERVERS"
echo "🏷️  Environment: $ENVIRONMENT"

# Function to check if Kafka is running
check_kafka() {
    echo "🔍 Checking Kafka connectivity..."
    
    timeout 10 bash -c "echo > /dev/tcp/localhost/9092" 2>/dev/null || {
        echo "❌ Cannot connect to Kafka at localhost:9092"
        echo "💡 Make sure Kafka is running: docker-compose up kafka"
        exit 1
    }
    
    echo "✅ Kafka is accessible"
}

# Function to wait for Schema Registry
check_schema_registry() {
    echo "🔍 Checking Schema Registry connectivity..."
    
    if curl -f -s "http://localhost:8081/subjects" >/dev/null 2>&1; then
        echo "✅ Schema Registry is accessible"
    else
        echo "⚠️  Schema Registry not accessible (this is optional for topic creation)"
    fi
}

# Function to create topics using kafka-topics.sh
create_topics_with_cli() {
    echo "🏗️  Creating topics using Kafka CLI..."
    
    # Define topics with their configurations
    declare -A topics=(
        ["$ENVIRONMENT.htc.model.hybrid.routing.dynamic-costs.v1"]="--partitions 12 --replication-factor 1 --config retention.ms=300000 --config cleanup.policy=delete"
        ["$ENVIRONMENT.htc.routing.requests.v1"]="--partitions 8 --replication-factor 1 --config retention.ms=3600000"
        ["$ENVIRONMENT.htc.routing.responses.v1"]="--partitions 8 --replication-factor 1 --config retention.ms=3600000"
        ["$ENVIRONMENT.htc.mobility.vehicle-updates.v1"]="--partitions 8 --replication-factor 1 --config retention.ms=3600000"
        ["$ENVIRONMENT.htc.system.performance-metrics.v1"]="--partitions 4 --replication-factor 1 --config retention.ms=86400000"
    )
    
    for topic in "${!topics[@]}"; do
        echo "📍 Creating topic: $topic"
        
        # Check if topic already exists
        if docker exec htc-kafka /bin/kafka-topics --bootstrap-server localhost:9092 --list | grep -q "^$topic$"; then
            echo "   ✅ Topic already exists: $topic"
        else
            # Create the topic
            docker exec htc-kafka /bin/kafka-topics \
                --bootstrap-server localhost:9092 \
                --create \
                --topic "$topic" \
                ${topics[$topic]} || {
                echo "   ❌ Failed to create topic: $topic"
                continue
            }
            echo "   ✅ Created topic: $topic"
        fi
    done
}

# Function to use Scala application for topic creation
create_topics_with_scala() {
    echo "🏗️  Creating topics using HTC Topic Manager..."
    
    cd "$PROJECT_ROOT"
    
    # Compile and run the topic manager
    sbt "runMain org.interscity.htc.core.kafka.KafkaTopicManagerApp init" || {
        echo "❌ Failed to create topics with Scala application"
        echo "💡 Falling back to CLI method..."
        create_topics_with_cli
    }
}

# Function to list created topics
list_topics() {
    echo ""
    echo "📋 Verifying created topics:"
    echo "=========================="
    
    docker exec htc-kafka /bin/kafka-topics \
        --bootstrap-server localhost:9092 \
        --list | grep "^$ENVIRONMENT\.htc\." | while read topic; do
        echo "✅ $topic"
        
        # Show topic details
        docker exec htc-kafka /bin/kafka-topics \
            --bootstrap-server localhost:9092 \
            --describe \
            --topic "$topic" | grep -E "Topic:|PartitionCount:|ReplicationFactor:" | head -1
    done
}

# Main execution
main() {
    check_kafka
    check_schema_registry
    
    echo ""
    if command -v sbt >/dev/null 2>&1; then
        create_topics_with_scala
    else
        echo "⚠️  SBT not found, using CLI method for topic creation"
        create_topics_with_cli
    fi
    
    list_topics
    
    echo ""
    echo "🎉 Kafka topic initialization completed!"
    echo ""
    echo "💡 Next steps:"
    echo "   1. Start HTC simulation: ./build-and-run.sh"
    echo "   2. Monitor topics: http://localhost:8080 (Kafka UI)"
    echo "   3. View Schema Registry: http://localhost:8081"
}

# Parse command line arguments
case "${1:-init}" in
    "init")
        main
        ;;
    "list")
        check_kafka
        list_topics
        ;;
    "clean")
        echo "🧹 Cleaning up HTC topics..."
        docker exec htc-kafka /bin/kafka-topics \
            --bootstrap-server localhost:9092 \
            --list | grep "^$ENVIRONMENT\.htc\." | while read topic; do
            echo "🗑️  Deleting topic: $topic"
            docker exec htc-kafka /bin/kafka-topics \
                --bootstrap-server localhost:9092 \
                --delete \
                --topic "$topic"
        done
        echo "✅ Cleanup completed"
        ;;
    *)
        echo "Usage: $0 [init|list|clean]"
        echo "  init  - Create all HTC topics (default)"
        echo "  list  - List existing HTC topics"
        echo "  clean - Delete all HTC topics"
        exit 1
        ;;
esac