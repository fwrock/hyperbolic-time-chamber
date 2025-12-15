#!/bin/bash
# Test script for lookahead optimization

set -e

echo "=================================="
echo "Lookahead Optimization Test Suite"
echo "=================================="
echo ""

# Configuration
SCENARIO_FILE=${1:-"scenario.json"}
DURATION=${2:-1000}

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "📋 Test Configuration:"
echo "  - Scenario: $SCENARIO_FILE"
echo "  - Duration: $DURATION ticks"
echo ""

# Test 1: Conservative (no lookahead)
echo -e "${YELLOW}Test 1: Conservative Execution (lookahead=1)${NC}"
export HTC_TIME_MANAGER_LOOKAHEAD=1
export HTC_SIMULATION_CONFIG_FILE=$SCENARIO_FILE

echo "Starting simulation..."
START_TIME=$(date +%s)

# Run simulation (adjust this command to match your actual run script)
# ./run.sh > /tmp/lookahead_test1.log 2>&1

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo -e "${GREEN}✓ Completed in ${ELAPSED}s${NC}"
echo ""

# Test 2: Lookahead window = 10
echo -e "${YELLOW}Test 2: Lookahead Optimization (lookahead=10)${NC}"
export HTC_TIME_MANAGER_LOOKAHEAD=10

echo "Starting simulation..."
START_TIME=$(date +%s)

# Run simulation
# ./run.sh > /tmp/lookahead_test2.log 2>&1

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo -e "${GREEN}✓ Completed in ${ELAPSED}s${NC}"
echo ""

# Test 3: Aggressive lookahead window = 50
echo -e "${YELLOW}Test 3: Aggressive Lookahead (lookahead=50)${NC}"
export HTC_TIME_MANAGER_LOOKAHEAD=50

echo "Starting simulation..."
START_TIME=$(date +%s)

# Run simulation
# ./run.sh > /tmp/lookahead_test3.log 2>&1

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo -e "${GREEN}✓ Completed in ${ELAPSED}s${NC}"
echo ""

# Analysis
echo "=================================="
echo "📊 Results Summary"
echo "=================================="
echo ""

echo "Check logs for lookahead statistics:"
echo "  grep 'lookahead:' /tmp/lookahead_test*.log"
echo ""

echo "Expected behavior:"
echo "  • Test 1: Baseline performance (synchronous barriers every tick)"
echo "  • Test 2: 1.5-2x speedup (reduced barrier frequency)"
echo "  • Test 3: 2-3x speedup (further reduced barriers)"
echo ""

echo "⚠️  Note: Uncomment simulation commands in this script to run actual tests"
echo "⚠️  Ensure scenario file exists and is properly configured"
echo ""

echo -e "${GREEN}✓ Test suite setup complete${NC}"
