#!/bin/bash
# Setup script for AMD EPYC 7453 dual-socket (112 threads, 1TB RAM)
# Optimizes system for maximum throughput

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}   Hyperbolic Time Chamber - AMD EPYC Setup                 ${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""

# Check if running as root
if [[ $EUID -ne 0 ]]; then
   echo -e "${RED}This script must be run as root (sudo)${NC}"
   exit 1
fi

# 1. Detect NUMA configuration
echo -e "${YELLOW}[1/10] Detecting NUMA configuration...${NC}"
if command -v numactl &> /dev/null; then
    NUMA_NODES=$(numactl --hardware | grep "available" | awk '{print $2}')
    echo -e "${GREEN}✓ NUMA nodes detected: $NUMA_NODES${NC}"
    numactl --hardware
else
    echo -e "${RED}✗ numactl not found. Installing...${NC}"
    apt-get update && apt-get install -y numactl
fi

# 2. Enable Transparent Huge Pages
echo ""
echo -e "${YELLOW}[2/10] Enabling Transparent Huge Pages...${NC}"
echo always > /sys/kernel/mm/transparent_hugepage/enabled
echo always > /sys/kernel/mm/transparent_hugepage/defrag
echo -e "${GREEN}✓ THP enabled${NC}"

# 3. Configure Huge Pages
echo ""
echo -e "${YELLOW}[3/10] Configuring Huge Pages (250,000 x 2MB = 500GB)...${NC}"
echo 250000 > /proc/sys/vm/nr_hugepages
ACTUAL_HP=$(cat /proc/sys/vm/nr_hugepages)
echo -e "${GREEN}✓ Huge pages allocated: $ACTUAL_HP${NC}"

# Persist across reboots
if ! grep -q "vm.nr_hugepages" /etc/sysctl.conf; then
    echo "vm.nr_hugepages=250000" >> /etc/sysctl.conf
fi

# 4. Increase file descriptors
echo ""
echo -e "${YELLOW}[4/10] Increasing file descriptor limits...${NC}"
cat >> /etc/security/limits.conf << 'EOF'
*    soft nofile 1048576
*    hard nofile 1048576
root soft nofile 1048576
root hard nofile 1048576
EOF
echo -e "${GREEN}✓ File descriptor limits increased to 1048576${NC}"

# 5. Optimize network stack
echo ""
echo -e "${YELLOW}[5/10] Optimizing network stack...${NC}"
cat >> /etc/sysctl.conf << 'EOF'
# Network optimizations for high throughput
net.core.rmem_max = 268435456
net.core.wmem_max = 268435456
net.core.rmem_default = 67108864
net.core.wmem_default = 67108864
net.ipv4.tcp_rmem = 4096 87380 134217728
net.ipv4.tcp_wmem = 4096 65536 134217728
net.ipv4.tcp_mem = 134217728 134217728 268435456
net.core.netdev_max_backlog = 300000
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
EOF
sysctl -p > /dev/null 2>&1
echo -e "${GREEN}✓ Network stack optimized${NC}"

# 6. Disable NUMA balancing (for pinned workloads)
echo ""
echo -e "${YELLOW}[6/10] Disabling automatic NUMA balancing...${NC}"
echo 0 > /proc/sys/kernel/numa_balancing
if ! grep -q "kernel.numa_balancing" /etc/sysctl.conf; then
    echo "kernel.numa_balancing=0" >> /etc/sysctl.conf
fi
echo -e "${GREEN}✓ NUMA auto-balancing disabled${NC}"

# 7. Set CPU governor to performance
echo ""
echo -e "${YELLOW}[7/10] Setting CPU governor to performance...${NC}"
if command -v cpupower &> /dev/null; then
    cpupower frequency-set -g performance > /dev/null 2>&1
    echo -e "${GREEN}✓ CPU governor set to performance${NC}"
else
    echo -e "${YELLOW}⚠ cpupower not found, skipping...${NC}"
fi

# 8. Disable swap (for maximum performance)
echo ""
echo -e "${YELLOW}[8/10] Disabling swap...${NC}"
swapoff -a
sed -i '/ swap / s/^/#/' /etc/fstab
echo -e "${GREEN}✓ Swap disabled${NC}"

# 9. Install required packages
echo ""
echo -e "${YELLOW}[9/10] Installing required packages...${NC}"
apt-get update > /dev/null 2>&1
apt-get install -y \
    docker.io \
    docker-compose \
    sysstat \
    iotop \
    htop \
    iftop \
    jq \
    linux-tools-common \
    linux-tools-generic \
    numactl \
    hwloc \
    > /dev/null 2>&1
echo -e "${GREEN}✓ Packages installed${NC}"

# 10. Display system info
echo ""
echo -e "${YELLOW}[10/10] System information:${NC}"
echo ""
echo -e "${BLUE}CPU Info:${NC}"
lscpu | grep -E "Model name|CPU\(s\)|Thread|Core|Socket|NUMA"
echo ""
echo -e "${BLUE}Memory Info:${NC}"
free -h
echo ""
echo -e "${BLUE}NUMA Nodes:${NC}"
numactl --hardware | head -20
echo ""
echo -e "${BLUE}Huge Pages:${NC}"
grep -i huge /proc/meminfo
echo ""

# Create monitoring script
cat > /usr/local/bin/htc-monitor << 'MONITOR_EOF'
#!/bin/bash
# HTC Monitoring Script

echo "═══════════════════════════════════════════════════════════"
echo "   Hyperbolic Time Chamber - System Monitor"
echo "═══════════════════════════════════════════════════════════"
echo ""

echo "CPU Usage (per NUMA node):"
numastat -c htc_seed_epyc htc_worker_epyc 2>/dev/null || echo "Containers not running"

echo ""
echo "Memory Usage:"
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" 2>/dev/null || echo "Docker not running"

echo ""
echo "Container CPUs:"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}" 2>/dev/null || echo "Docker not running"

echo ""
echo "Network I/O:"
docker stats --no-stream --format "table {{.Name}}\t{{.NetIO}}" 2>/dev/null || echo "Docker not running"

echo ""
echo "Cluster Status:"
curl -s http://localhost:8558/cluster/members | jq -r '.members[] | "\(.node) - \(.status)"' 2>/dev/null || echo "Cluster not responding"

echo ""
echo "═══════════════════════════════════════════════════════════"
MONITOR_EOF

chmod +x /usr/local/bin/htc-monitor

echo ""
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}✓ Setup complete!${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Reboot the system (recommended):"
echo "   sudo reboot"
echo ""
echo "2. Or reload sysctl without reboot:"
echo "   sudo sysctl -p"
echo ""
echo "3. Verify huge pages after reboot:"
echo "   cat /proc/meminfo | grep Huge"
echo ""
echo "4. Start the simulation:"
echo "   docker-compose -f docker-compose-epyc.yml up -d"
echo ""
echo "5. Monitor the system:"
echo "   htc-monitor"
echo ""
echo -e "${BLUE}System Capacity Estimate:${NC}"
echo "- Heap available: ~900 GB (2x 450GB containers)"
echo "- Threads: 112 (56 cores x 2 HT)"
echo "- Expected throughput: 500K-1M events/sec"
echo "- Max actors in memory: 50-100M"
echo "- Viable simulation size: 10-30M vehicles"
echo ""
