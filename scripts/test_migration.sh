#!/bin/bash

# Quick Test Migration Script
# Tests the migration script with a small sample

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo "========================================"
echo "🧪 Testing Migration Script"
echo "========================================"
echo ""

# Check if input directory exists
if [ ! -d "input/cenario_1000_viagens" ]; then
    echo "❌ Error: Input directory not found: input/cenario_1000_viagens"
    echo "Please ensure the source scenario exists."
    exit 1
fi

# Clean previous test output
if [ -d "output/test_migration" ]; then
    echo "🧹 Cleaning previous test output..."
    rm -rf output/test_migration
fi

echo "📋 Test Configuration:"
echo "  • Input:  input/cenario_1000_viagens"
echo "  • Output: output/test_migration"
echo "  • MICRO Ratio: 30%"
echo "  • Strategy: arterial"
echo "  • Convert Vehicles: Yes"
echo "  • Items per File: 3000"
echo ""

# Run migration
echo "🚀 Running migration..."
python3 migrate_to_hybrid.py \
    --input input/cenario_1000_viagens \
    --output output/test_migration \
    --micro-ratio 0.3 \
    --micro-strategy arterial \
    --convert-vehicles \
    --car-ratio 0.65 \
    --bus-ratio 0.15 \
    --bicycle-ratio 0.1 \
    --motorcycle-ratio 0.1 \
    --items-per-file 3000 \
    --signal-coverage 0.25

echo ""
echo "========================================"
echo "✅ Migration Test Complete!"
echo "========================================"
echo ""

# Show output structure
echo "📂 Output Structure:"
tree -L 2 output/test_migration || find output/test_migration -maxdepth 2 -type f -o -type d

echo ""
echo "📊 Quick Statistics:"
echo "  • Files generated:"
ls -1 output/test_migration/data/*.json | wc -l

echo ""
echo "📄 View migration report:"
echo "  cat output/test_migration/MIGRATION_REPORT.md"
echo ""
echo "🎯 Next steps:"
echo "  1. Review: output/test_migration/MIGRATION_REPORT.md"
echo "  2. Validate: python3 validate_scenario.py output/test_migration"
echo "  3. Stats: python3 scenario_stats.py output/test_migration"
echo ""
