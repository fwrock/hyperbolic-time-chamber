#!/bin/bash
# Performance Benchmark Script
# Tests throughput and latency of the optimized Hyperbolic Time Chamber

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}   Hyperbolic Time Chamber - Performance Benchmark          ${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""

# Configuration
SEED_NODE="http://localhost:8558"
WORKER_NODES=("http://localhost:8559" "http://localhost:8560" "http://localhost:8561")
OUTPUT_DIR="./output/benchmark"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "$OUTPUT_DIR"

# Function to check cluster health
check_cluster_health() {
    echo -e "${YELLOW}[1/6] Checking cluster health...${NC}"
    
    for i in {1..30}; do
        MEMBERS=$(curl -s "$SEED_NODE/cluster/members" | jq -r '.members | length' 2>/dev/null || echo "0")
        if [ "$MEMBERS" -ge 4 ]; then
            echo -e "${GREEN}✓ Cluster healthy: $MEMBERS members${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
    done
    
    echo -e "${RED}✗ Cluster not healthy after 60s${NC}"
    return 1
}

# Function to monitor cluster state
monitor_cluster() {
    echo -e "${YELLOW}[2/6] Monitoring cluster state...${NC}"
    
    curl -s "$SEED_NODE/cluster/members" | jq . > "$OUTPUT_DIR/cluster_state_${TIMESTAMP}.json"
    
    echo -e "${GREEN}✓ Cluster state saved${NC}"
}

# Function to check JVM metrics
check_jvm_metrics() {
    echo -e "${YELLOW}[3/6] Checking JVM metrics...${NC}"
    
    for NODE in "${WORKER_NODES[@]}"; do
        PORT=${NODE##*:}
        CONTAINER=$(docker ps --format '{{.Names}}' | grep -E "worker.*${PORT: -1}$" | head -1)
        
        if [ -n "$CONTAINER" ]; then
            echo "  Checking $CONTAINER..."
            
            # Get heap usage
            HEAP_USED=$(docker exec "$CONTAINER" jcmd 1 GC.heap_info 2>/dev/null | grep -A 10 "G1 Heap" | grep "used" | awk '{print $3}' | head -1 || echo "N/A")
            HEAP_MAX=$(docker exec "$CONTAINER" jcmd 1 GC.heap_info 2>/dev/null | grep -A 10 "G1 Heap" | grep "capacity" | awk '{print $3}' | head -1 || echo "N/A")
            
            echo "    Heap: $HEAP_USED / $HEAP_MAX"
            
            # Thread count
            THREADS=$(docker exec "$CONTAINER" jcmd 1 Thread.print 2>/dev/null | grep -c "^\"" || echo "N/A")
            echo "    Threads: $THREADS"
        fi
    done
    
    echo -e "${GREEN}✓ JVM metrics collected${NC}"
}

# Function to monitor sharding distribution
check_sharding_distribution() {
    echo -e "${YELLOW}[4/6] Checking sharding distribution...${NC}"
    
    curl -s "$SEED_NODE/cluster/shards/mobility.actor.Car" 2>/dev/null | jq . > "$OUTPUT_DIR/shards_car_${TIMESTAMP}.json" || true
    curl -s "$SEED_NODE/cluster/shards/mobility.actor.Link" 2>/dev/null | jq . > "$OUTPUT_DIR/shards_link_${TIMESTAMP}.json" || true
    curl -s "$SEED_NODE/cluster/shards/mobility.actor.Node" 2>/dev/null | jq . > "$OUTPUT_DIR/shards_node_${TIMESTAMP}.json" || true
    
    echo -e "${GREEN}✓ Sharding distribution saved${NC}"
}

# Function to measure throughput
measure_throughput() {
    echo -e "${YELLOW}[5/6] Measuring throughput...${NC}"
    
    REPORT_DIR="./output/reports/json"
    
    if [ ! -d "$REPORT_DIR" ]; then
        echo -e "${RED}✗ Report directory not found${NC}"
        return 1
    fi
    
    # Get latest report files
    LATEST_REPORTS=$(find "$REPORT_DIR" -name "*.json" -type f -mmin -5 2>/dev/null | head -10)
    
    if [ -z "$LATEST_REPORTS" ]; then
        echo -e "${YELLOW}⚠ No recent reports found (looking for files modified in last 5 min)${NC}"
        return 0
    fi
    
    TOTAL_EVENTS=0
    for REPORT in $LATEST_REPORTS; do
        EVENTS=$(jq '. | length' "$REPORT" 2>/dev/null || echo "0")
        TOTAL_EVENTS=$((TOTAL_EVENTS + EVENTS))
    done
    
    echo "  Total events in last 5 min: $TOTAL_EVENTS"
    
    if [ $TOTAL_EVENTS -gt 0 ]; then
        THROUGHPUT=$((TOTAL_EVENTS / 300))  # Events per second
        echo -e "${GREEN}  Throughput: ~$THROUGHPUT events/sec${NC}"
    fi
    
    echo -e "${GREEN}✓ Throughput measured${NC}"
}

# Function to check system resources
check_system_resources() {
    echo -e "${YELLOW}[6/6] Checking system resources...${NC}"
    
    # CPU usage
    echo "  CPU Usage:"
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" | grep htc
    
    # Network stats
    echo ""
    echo "  Network Stats:"
    docker stats --no-stream --format "table {{.Name}}\t{{.NetIO}}" | grep htc
    
    # Disk I/O
    echo ""
    echo "  Disk I/O:"
    docker stats --no-stream --format "table {{.Name}}\t{{.BlockIO}}" | grep htc
    
    echo -e "${GREEN}✓ System resources checked${NC}"
}

# Function to generate benchmark report
generate_report() {
    echo ""
    echo -e "${YELLOW}Generating benchmark report...${NC}"
    
    cat > "$OUTPUT_DIR/benchmark_report_${TIMESTAMP}.txt" <<EOF
════════════════════════════════════════════════════════════
  Hyperbolic Time Chamber - Benchmark Report
  Timestamp: $TIMESTAMP
════════════════════════════════════════════════════════════

CLUSTER CONFIGURATION:
- Seed Node: $SEED_NODE
- Worker Nodes: ${#WORKER_NODES[@]}
- Total Nodes: $((${#WORKER_NODES[@]} + 1))

OPTIMIZATION SETTINGS:
- Persistence: DISABLED (in-memory journal only)
- Snapshots: DISABLED (interval = Int.MaxValue)
- Passivation: DISABLED (all actors in memory)
- Sharding State Store: DData
- Dispatcher Throughput: 500-1000
- Batch Size (Time Manager): 50,000

PERFORMANCE METRICS:
$(cat "$OUTPUT_DIR/cluster_state_${TIMESTAMP}.json" 2>/dev/null || echo "Cluster state not available")

RECOMMENDATIONS:
1. Monitor GC pauses - target < 200ms
2. Check mailbox sizes - should not grow unbounded
3. Verify shard distribution is balanced
4. Monitor network throughput between nodes
5. Check for dead letters (should be minimal)

NEXT STEPS:
- Run longer workloads (1-24 hours)
- Compare with baseline (non-optimized) configuration
- Profile with async-profiler for hotspots
- Tune JVM GC parameters if needed

════════════════════════════════════════════════════════════
EOF
    
    echo -e "${GREEN}✓ Report saved to: $OUTPUT_DIR/benchmark_report_${TIMESTAMP}.txt${NC}"
}

# Main execution
main() {
    echo ""
    
    if ! check_cluster_health; then
        echo -e "${RED}Benchmark aborted: Cluster not healthy${NC}"
        exit 1
    fi
    
    echo ""
    monitor_cluster
    
    echo ""
    check_jvm_metrics
    
    echo ""
    check_sharding_distribution
    
    echo ""
    measure_throughput
    
    echo ""
    check_system_resources
    
    echo ""
    generate_report
    
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}✓ Benchmark complete!${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo -e "Results saved to: ${YELLOW}$OUTPUT_DIR${NC}"
    echo ""
}

# Run benchmark
main "$@"
