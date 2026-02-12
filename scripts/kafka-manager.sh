#!/bin/bash

# HTC Kafka Management Script
# Facilitates common operations with Kafka in the HTC environment

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_help() {
    echo -e "${BLUE}🚀 HTC Kafka Management Script${NC}"
    echo ""
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  start-dev      Start development environment (with separate network)"
    echo "  start-prod     Start production environment (host network)"
    echo "  stop           Stop all services"
    echo "  setup-topics   Create all Kafka topics"
    echo "  status         Show services status"
    echo "  logs [service] Show logs for service"
    echo "  shell          Open Kafka shell"
    echo "  ui             Open Kafka UI in browser"
    echo "  test-producer  Run producer performance test"
    echo "  test-consumer  Run consumer test"
    echo "  clean          Remove all data volumes"
    echo ""
    echo "Examples:"
    echo "  $0 start-dev           # Start dev environment"
    echo "  $0 logs kafka          # Show Kafka logs"
    echo "  $0 test-producer       # Test producer performance"
}

start_dev() {
    echo -e "${GREEN}🚀 Starting HTC development environment...${NC}"
    docker-compose -f docker-compose-dev.yml up -d
    
    echo -e "${YELLOW}⏳ Waiting for services to be ready...${NC}"
    sleep 10
    
    echo -e "${GREEN}✅ Services started!${NC}"
    echo -e "${BLUE}📊 Kafka UI: http://localhost:8080${NC}"
    echo -e "${BLUE}🔧 Schema Registry: http://localhost:8081${NC}"
    echo -e "${BLUE}📈 HTC Management: http://localhost:8558${NC}"
    
    read -p "Setup Kafka topics? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        setup_topics_dev
    fi
}

start_prod() {
    echo -e "${GREEN}🚀 Starting HTC production environment...${NC}"
    docker-compose up -d
    
    echo -e "${YELLOW}⏳ Waiting for services to be ready...${NC}"
    sleep 15
    
    echo -e "${GREEN}✅ Services started!${NC}"
    echo -e "${BLUE}📊 Kafka UI: http://localhost:8080${NC}"
    echo -e "${BLUE}🔧 Schema Registry: http://localhost:8081${NC}"
    echo -e "${BLUE}📈 HTC Management: http://localhost:8558${NC}"
    
    read -p "Setup Kafka topics? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        setup_topics_prod
    fi
}

stop_services() {
    echo -e "${YELLOW}🛑 Stopping all services...${NC}"
    docker-compose -f docker-compose-dev.yml down 2>/dev/null || true
    docker-compose down 2>/dev/null || true
    echo -e "${GREEN}✅ All services stopped!${NC}"
}

setup_topics_dev() {
    echo -e "${GREEN}📝 Setting up Kafka topics for development...${NC}"
    
    # Wait for Kafka to be ready
    echo -e "${YELLOW}⏳ Waiting for Kafka to be ready...${NC}"
    for i in {1..30}; do
        if docker exec htc-kafka-dev kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Kafka is ready!${NC}"
            break
        else
            echo -e "${YELLOW}🔄 Waiting for Kafka... ($i/30)${NC}"
            sleep 2
        fi
    done
    
    # Create topics
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.events --partitions 3 --replication-factor 1
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.reports --partitions 3 --replication-factor 1
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.reports.avro --partitions 3 --replication-factor 1
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.vehicles.positions.avro --partitions 6 --replication-factor 1
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.commands --partitions 1 --replication-factor 1
    docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists --topic htc.status --partitions 1 --replication-factor 1
    
    echo -e "${GREEN}✅ Development topics created!${NC}"
}

setup_topics_prod() {
    echo -e "${GREEN}📝 Running full topic setup script...${NC}"
    ./scripts/setup-kafka-topics.sh
}

show_status() {
    echo -e "${BLUE}📊 Services Status:${NC}"
    echo ""
    docker-compose -f docker-compose-dev.yml ps 2>/dev/null || docker-compose ps 2>/dev/null || echo "No services running"
    
    echo ""
    echo -e "${BLUE}🔍 Kafka Topics:${NC}"
    if docker exec htc-kafka-dev kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null; then
        echo ""
    elif docker exec htc-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null; then
        echo ""
    else
        echo "No Kafka topics found or Kafka not running"
    fi
}

show_logs() {
    local service=${1:-""}
    if [ -z "$service" ]; then
        echo -e "${YELLOW}Available services:${NC}"
        docker-compose -f docker-compose-dev.yml ps --services 2>/dev/null || docker-compose ps --services 2>/dev/null
        return
    fi
    
    echo -e "${BLUE}📋 Showing logs for $service...${NC}"
    docker-compose -f docker-compose-dev.yml logs -f "$service" 2>/dev/null || docker-compose logs -f "$service" 2>/dev/null
}

open_shell() {
    echo -e "${BLUE}🐚 Opening Kafka shell...${NC}"
    if docker exec -it htc-kafka-dev bash 2>/dev/null; then
        echo ""
    elif docker exec -it htc-kafka bash 2>/dev/null; then
        echo ""
    else
        echo -e "${RED}❌ Kafka container not running${NC}"
    fi
}

open_ui() {
    echo -e "${BLUE}🌐 Opening Kafka UI...${NC}"
    if command -v xdg-open > /dev/null; then
        xdg-open http://localhost:8080
    elif command -v open > /dev/null; then
        open http://localhost:8080
    else
        echo -e "${GREEN}📊 Kafka UI available at: http://localhost:8080${NC}"
    fi
}

test_producer() {
    echo -e "${GREEN}🧪 Running producer performance test...${NC}"
    
    # Check if we're in dev or prod
    if docker ps | grep -q htc-kafka-dev; then
        KAFKA_CONTAINER="htc-kafka-dev"
    else
        KAFKA_CONTAINER="htc-kafka"
    fi
    
    echo -e "${YELLOW}📤 Sending test messages to htc.test.performance...${NC}"
    docker exec $KAFKA_CONTAINER kafka-producer-perf-test \
        --topic htc.test.performance \
        --throughput 1000 \
        --record-size 1024 \
        --num-records 10000 \
        --producer-props bootstrap.servers=localhost:9092
}

test_consumer() {
    echo -e "${GREEN}🧪 Running consumer test...${NC}"
    
    # Check if we're in dev or prod
    if docker ps | grep -q htc-kafka-dev; then
        KAFKA_CONTAINER="htc-kafka-dev"
    else
        KAFKA_CONTAINER="htc-kafka"
    fi
    
    echo -e "${YELLOW}📥 Consuming messages from htc.events...${NC}"
    echo -e "${BLUE}Press Ctrl+C to stop${NC}"
    docker exec $KAFKA_CONTAINER kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic htc.events \
        --from-beginning \
        --max-messages 10
}

clean_data() {
    echo -e "${RED}⚠️  This will remove all data volumes!${NC}"
    read -p "Are you sure? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        stop_services
        echo -e "${YELLOW}🧹 Cleaning data volumes...${NC}"
        docker volume rm $(docker volume ls -q | grep -E "(kafka|zookeeper|redis).*dev") 2>/dev/null || true
        docker volume rm $(docker volume ls -q | grep -E "(kafka|zookeeper|redis)_data") 2>/dev/null || true
        echo -e "${GREEN}✅ Data volumes cleaned!${NC}"
    fi
}

# Main script logic
case "${1:-help}" in
    start-dev)
        start_dev
        ;;
    start-prod)
        start_prod
        ;;
    stop)
        stop_services
        ;;
    setup-topics)
        if docker ps | grep -q htc-kafka-dev; then
            setup_topics_dev
        else
            setup_topics_prod
        fi
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs "$2"
        ;;
    shell)
        open_shell
        ;;
    ui)
        open_ui
        ;;
    test-producer)
        test_producer
        ;;
    test-consumer)
        test_consumer
        ;;
    clean)
        clean_data
        ;;
    help|--help|-h)
        print_help
        ;;
    *)
        echo -e "${RED}❌ Unknown command: $1${NC}"
        echo ""
        print_help
        exit 1
        ;;
esac