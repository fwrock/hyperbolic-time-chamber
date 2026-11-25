#!/bin/sh
set -eu
# Intentional word splitting permits the conventional JVM option list.
exec java ${JAVA_OPTS:--Xms256m -Xmx32g -XX:ActiveProcessorCount=28} \
  -XX:+UseCompactObjectHeaders \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  -Dconfig.resource="${HTC_CONFIG_RESOURCE:-beluga-multi.conf}" \
  -Dpekko.cluster.min-nr-of-members="${HTC_COHORT_SIZE:-4}" \
  -Dpekko.cluster.sharding.rebalance-interval=1d \
  -Dhtc.report-manager.enabled-strategies.0=parquet \
  -Dhtc.report-manager.enabled-strategies.1=kafka \
  -jar /app/engine.jar
