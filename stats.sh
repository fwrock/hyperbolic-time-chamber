#!/bin/bash
while true; do
  docker stats interscsimulator --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}" >> stats_log_1_5m_intersc.txt
  sleep 10
done
