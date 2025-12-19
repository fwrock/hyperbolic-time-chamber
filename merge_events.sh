#!/bin/bash
# merge_events.sh - Merge and sort JSONL event files from multiple JsonReportData actors

set -e

INPUT_DIR="${1:-output/reports/json}"
OUTPUT_FILE="${2:-merged_events.jsonl}"

if [[ ! -d "$INPUT_DIR" ]]; then
  echo "❌ Error: Directory $INPUT_DIR does not exist"
  exit 1
fi

echo "🔍 Finding JSONL files in $INPUT_DIR..."
JSONL_FILES=$(find "$INPUT_DIR" -name "*_events.jsonl" -type f 2>/dev/null || true)

if [[ -z "$JSONL_FILES" ]]; then
  echo "❌ Error: No *_events.jsonl files found in $INPUT_DIR"
  exit 1
fi

FILE_COUNT=$(echo "$JSONL_FILES" | wc -l)
echo "📂 Found $FILE_COUNT JSONL file(s)"

echo "🔀 Merging and sorting by tick..."

# Merge all JSONL files, sort by tick, and write as JSONL (one JSON per line)
cat $JSONL_FILES | \
  jq -c -s 'sort_by(.tick) | .[]' > "$OUTPUT_FILE"

TOTAL_EVENTS=$(wc -l < "$OUTPUT_FILE")
echo "✅ Merged $TOTAL_EVENTS events to $OUTPUT_FILE"

# Generate summary
echo ""
echo "=== Event Type Distribution ==="
jq -r '.event_type' "$OUTPUT_FILE" | sort | uniq -c | sort -rn

# Tick range
echo ""
echo "=== Tick Range ==="
MIN_TICK=$(jq -r '.tick' "$OUTPUT_FILE" | sort -n | head -1)
MAX_TICK=$(jq -r '.tick' "$OUTPUT_FILE" | sort -n | tail -1)
echo "Min tick: $MIN_TICK"
echo "Max tick: $MAX_TICK"
echo "Duration: $((MAX_TICK - MIN_TICK)) ticks"

# File size
FILE_SIZE=$(du -h "$OUTPUT_FILE" | awk '{print $1}')
echo ""
echo "📊 Output file size: $FILE_SIZE"

echo ""
echo "🎉 Done! Use: jq '.[] | select(.event_type == \"journey_started\")' $OUTPUT_FILE"
