#!/bin/bash
# Quick scenario generation examples

cd "$(dirname "$0")"

echo "=============================================="
echo "🚀 Hyperbolic Time Chamber"
echo "   Scenario Generation Examples"
echo "=============================================="
echo ""

# Check Python dependencies
echo "📋 Checking dependencies..."
if ! python3 -c "import yaml" 2>/dev/null; then
    echo "⚠️  PyYAML not found. Installing..."
    pip install pyyaml
fi
echo "✅ Dependencies OK"
echo ""

# Function to run and show result
run_example() {
    local name=$1
    local cmd=$2
    
    echo "=============================================="
    echo "Example: $name"
    echo "=============================================="
    echo "Command: $cmd"
    echo ""
    eval $cmd
    echo ""
}

# Parse command line arguments
case "${1:-help}" in
    1|quick)
        run_example "Quick Test Scenario" \
            "python3 generate_hybrid_scenario.py --quick-test"
        ;;
    
    2|small)
        run_example "Small Grid Scenario" \
            "python3 generate_hybrid_scenario.py --config example_configs/small_grid.yaml"
        ;;
    
    3|large)
        run_example "Large Grid Scenario" \
            "python3 generate_hybrid_scenario.py --config example_configs/large_grid.yaml"
        ;;
    
    4|random)
        run_example "Random Network Scenario" \
            "python3 generate_hybrid_scenario.py --config example_configs/random_network.yaml"
        ;;
    
    5|micro)
        run_example "MICRO Intensive Scenario" \
            "python3 generate_hybrid_scenario.py --config example_configs/micro_intensive.yaml"
        ;;
    
    6|meso)
        run_example "MESO Baseline Scenario" \
            "python3 generate_hybrid_scenario.py --config example_configs/meso_baseline.yaml"
        ;;
    
    7|custom)
        run_example "Custom CLI Scenario" \
            "python3 generate_hybrid_scenario.py --name 'Custom Test' --nodes 16 --vehicles 100 --output ../simulations/input/custom_test"
        ;;
    
    8|interactive)
        echo "=============================================="
        echo "Interactive Mode"
        echo "=============================================="
        python3 generate_hybrid_scenario.py --interactive
        ;;
    
    all)
        echo "🎯 Running all examples..."
        echo ""
        
        for i in 1 2 3 4 5 6 7; do
            $0 $i
            sleep 1
        done
        
        echo "✅ All examples completed!"
        ;;
    
    clean)
        echo "🧹 Cleaning generated scenarios..."
        rm -rf ../simulations/input/test_scenario
        rm -rf ../simulations/input/small_grid_scenario
        rm -rf ../simulations/input/large_grid_scenario
        rm -rf ../simulations/input/random_network_scenario
        rm -rf ../simulations/input/micro_intensive_scenario
        rm -rf ../simulations/input/meso_baseline_scenario
        rm -rf ../simulations/input/custom_test
        echo "✅ Cleaned!"
        ;;
    
    help|*)
        cat << 'EOF'
📖 Scenario Generation Examples

Usage: ./run_examples.sh [example_number|name]

Available Examples:
  1 | quick       Quick test scenario (9 nodes, 20 vehicles, 10 min)
  2 | small       Small grid (9 nodes, 50 vehicles, 30 min)
  3 | large       Large grid (25 nodes, 500 vehicles, 2 hours)
  4 | random      Random network (20 nodes, 150 vehicles, 1 hour)
  5 | micro       MICRO intensive (9 nodes, 30 vehicles, 90% MICRO)
  6 | meso        MESO baseline (25 nodes, 1000 vehicles, 0% MICRO)
  7 | custom      Custom CLI example
  8 | interactive Interactive configuration mode
  
  all             Run all examples (except interactive)
  clean           Remove all generated scenarios
  help            Show this help

Examples:
  ./run_examples.sh quick        # Generate quick test
  ./run_examples.sh 2            # Generate small grid
  ./run_examples.sh interactive  # Interactive mode
  ./run_examples.sh all          # Generate all scenarios
  ./run_examples.sh clean        # Clean up

After Generation:
  1. Check the output directory
  2. Read SCENARIO_REPORT.md
  3. Set HTC_SIMULATION_DATA_PATH
  4. Run the simulation:
     
     export HTC_SIMULATION_DATA_PATH=/path/to/scenario
     cd ..
     ./build-and-run.sh

For more info, see scripts/README.md
EOF
        ;;
esac
