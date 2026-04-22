#!/bin/bash
set -e

PROJECT_NAME="hyperbolic-time-chamber"
SCALA_VERSION="3.3.5"
HTC_SCENARIO_NAME="${HTC_SCENARIO_NAME:-htc_scenario}"

echo "🚀 Step 1: Cleaning old build..."
sbt clean

echo "📦 Step 2: Compiling and generating JAR..."
sbt assembly

JAR_FILE="target/scala-${SCALA_VERSION}/${PROJECT_NAME}-1.24.3.jar"

if [[ ! -f "$JAR_FILE" ]]; then
    echo "❌ Error: JAR not found at $JAR_FILE"
    exit 1
fi

echo "🐳 Step 3: Building docker image..."
docker build -t ${PROJECT_NAME}:latest .

echo "📦 Step 4: Deploying with docker compose..."
echo "   • Scenario: ${HTC_SCENARIO_NAME}"
echo "   • Config:   /app/hyperbolic-time-chamber/simulations/input/${HTC_SCENARIO_NAME}/simulation.json"
echo "   • City map: /app/hyperbolic-time-chamber/simulations/input/${HTC_SCENARIO_NAME}/data/city_map.json"
docker compose up node1 --build

echo "✅ Application is running with docker compose!"
