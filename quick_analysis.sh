#!/bin/bash

# Quick Traffic Analysis - Script Rápido
# Execute este script para análise rápida sem configuração manual

set -e

echo "🚀 Hyperbolic Time Chamber - Análise Rápida de Tráfego"
echo "======================================================"

# Ir para o diretório do projeto
cd "$(dirname "$0")"

# Executar configuração se necessário
if [ ! -d "scripts/venv" ]; then
    echo "📦 Primeira execução - configurando sistema..."
    ./run_traffic_analysis.sh setup
fi

# Executar análise
echo "📊 Executando análise de tráfego..."
./run_traffic_analysis.sh run cassandra 3000

echo ""
echo "✅ Análise concluída!"
echo "📋 Verifique os relatórios gerados no diretório scripts/output/"
echo "🌐 DataStax Studio disponível em: http://localhost:9091"
echo ""
echo "Para análise interativa, execute: ./run_traffic_analysis.sh jupyter"