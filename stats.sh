#!/bin/bash
while true; do
  docker stats node_1 --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}" >> stats_log_1M_1.txt
  sleep 10
done
