#!/bin/bash
# 🚀 Quick Deploy para 1M Atores - EPYC Configuration

set -e

echo "═══════════════════════════════════════════════════════"
echo "  Hyperbolic Time Chamber - EPYC Deployment Script"
echo "  Target: 1 Million Actors"
echo "═══════════════════════════════════════════════════════"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print status
print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

# Check if running as root
if [ "$EUID" -eq 0 ]; then 
   print_error "Do not run as root (Docker commands don't need sudo)"
   exit 1
fi

echo "Step 1/8: Checking system requirements..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check CPU cores
CORES=$(nproc)
if [ "$CORES" -lt 56 ]; then
    print_warning "Found $CORES cores (expected 112 threads)"
else
    print_status "CPU cores: $CORES"
fi

# Check RAM
RAM_GB=$(free -g | awk '/^Mem:/{print $2}')
if [ "$RAM_GB" -lt 900 ]; then
    print_warning "Found ${RAM_GB}GB RAM (expected 1TB)"
else
    print_status "RAM: ${RAM_GB}GB"
fi

# Check huge pages
HUGE_PAGES=$(cat /proc/meminfo | grep HugePages_Total | awk '{print $2}')
if [ "$HUGE_PAGES" -lt 100000 ]; then
    print_warning "Huge pages: $HUGE_PAGES (recommended: 250000)"
    echo "  Run: sudo ./setup-epyc.sh"
else
    print_status "Huge pages: $HUGE_PAGES"
fi

# Check file descriptors
FD_LIMIT=$(ulimit -n)
if [ "$FD_LIMIT" -lt 1000000 ]; then
    print_warning "File descriptors: $FD_LIMIT (recommended: 1048576)"
    echo "  Add to /etc/security/limits.conf:"
    echo "    * soft nofile 1048576"
    echo "    * hard nofile 1048576"
else
    print_status "File descriptors: $FD_LIMIT"
fi

echo ""
echo "Step 2/8: Checking Docker..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if ! command -v docker &> /dev/null; then
    print_error "Docker not found. Please install Docker first."
    exit 1
fi

print_status "Docker version: $(docker --version | cut -d' ' -f3 | cut -d',' -f1)"

# Check if docker-compose exists
if ! command -v docker-compose &> /dev/null; then
    print_warning "docker-compose not found, using 'docker compose' instead"
    DOCKER_COMPOSE="docker compose"
else
    DOCKER_COMPOSE="docker-compose"
    print_status "Docker Compose version: $($DOCKER_COMPOSE --version | cut -d' ' -f3 | cut -d',' -f1)"
fi

echo ""
echo "Step 3/8: Stopping existing containers..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

$DOCKER_COMPOSE -f docker-compose-epyc.yml down 2>/dev/null || true
print_status "Containers stopped"

echo ""
echo "Step 4/8: Cleaning old build artifacts..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

sbt clean > /dev/null 2>&1 || print_warning "SBT clean failed (might be ok)"
print_status "Build artifacts cleaned"

echo ""
echo "Step 5/8: Compiling project (this may take 2-5 minutes)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if sbt compile; then
    print_status "Compilation successful"
else
    print_error "Compilation failed"
    exit 1
fi

echo ""
echo "Step 6/8: Creating assembly JAR..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if sbt assembly; then
    JAR_SIZE=$(du -h target/scala-3.3.5/*.jar 2>/dev/null | cut -f1 | head -1)
    print_status "Assembly created: $JAR_SIZE"
else
    print_error "Assembly creation failed"
    exit 1
fi

echo ""
echo "Step 7/8: Building Docker images..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if docker build -t hyperbolic-time-chamber:latest .; then
    print_status "Docker image built"
else
    print_error "Docker build failed"
    exit 1
fi

echo ""
echo "Step 8/8: Starting EPYC configuration..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Set environment variables for 1M actors
export HTC_TIME_MANAGER_INSTANCES=8704
export HTC_TIME_MANAGER_PER_NODE=4352
export HTC_TIME_MANAGER_VERBOSE_LOGGING=false
export HTC_REPORT_JSON_INSTANCES=512
export HTC_REPORT_JSON_PER_NODE=256

print_status "Environment variables set:"
echo "  - TIME_MANAGER_INSTANCES: 8704"
echo "  - VERBOSE_LOGGING: false (optimized for 1M+)"
echo ""

if $DOCKER_COMPOSE -f docker-compose-epyc.yml up -d; then
    print_status "Containers started"
else
    print_error "Failed to start containers"
    exit 1
fi

echo ""
echo "Waiting for cluster to form (30 seconds)..."
sleep 10
echo -n "  "; for i in {1..20}; do echo -n "▓"; sleep 1; done; echo ""

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  ✅ Deployment Complete!"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "Cluster Status:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Check cluster members
CLUSTER_STATUS=$(curl -s http://localhost:8558/cluster/members 2>/dev/null)
if [ $? -eq 0 ]; then
    MEMBERS=$(echo $CLUSTER_STATUS | jq -r '.members | length' 2>/dev/null || echo "Unknown")
    if [ "$MEMBERS" = "2" ]; then
        print_status "Cluster healthy: $MEMBERS members"
        echo ""
        echo $CLUSTER_STATUS | jq -r '.members[] | "  - \(.node) [\(.status)]"' 2>/dev/null || echo "  Could not parse members"
    else
        print_warning "Cluster forming: $MEMBERS members (expected 2)"
        echo "  Wait a few more seconds and check: curl http://localhost:8558/cluster/members | jq"
    fi
else
    print_warning "Could not connect to cluster management endpoint"
    echo "  Check logs: docker logs htc_seed_epyc"
fi

echo ""
echo "Container Resources:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" | grep htc || print_warning "Containers not running yet"

echo ""
echo "Next Steps:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Check logs:"
echo "   docker logs -f htc_seed_epyc"
echo "   docker logs -f htc_worker_epyc"
echo ""
echo "2. Monitor cluster:"
echo "   curl http://localhost:8558/cluster/members | jq"
echo ""
echo "3. Watch resources:"
echo "   watch -n 1 'docker stats --no-stream'"
echo ""
echo "4. Check NUMA affinity:"
echo "   docker exec htc_seed_epyc taskset -cp 1"
echo "   docker exec htc_worker_epyc taskset -cp 1"
echo ""
echo "5. Start simulation:"
echo "   # Place your scenario JSON in the configured path"
echo "   # Set HTC_SIMULATION_CONFIG_FILE environment variable"
echo ""
echo "Configuration:"
echo "  - Docs: docs/SCALABILITY_1M_ACTORS.md"
echo "  - EPYC: docs/EPYC_CONFIGURATION.md"
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Ready for 1 Million Actors! 🚀"
echo "═══════════════════════════════════════════════════════"
