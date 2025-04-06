#!/bin/bash
set -e

echo "🐳 📦 Step 1: Deploying with docker compose..."
docker compose up --build

echo "✅ Application is running with docker compose!"
