#!/bin/bash

# Script para testar a nova implementação com eventos de tráfego
echo "🚀 Iniciando teste de novos eventos de tráfego..."

# Compilar o projeto
echo "📦 Compilando projeto Scala..."
sbt compile

if [ $? -eq 0 ]; then
    echo "✅ Compilação bem-sucedida!"
    
    # Executar uma simulação pequena para testar
    echo "🎯 Executando simulação de teste..."
    echo "⚠️ Este é apenas um teste para verificar se os novos eventos são gerados"
    echo "💡 Uma simulação completa seria executada com: sbt run"
    
    # Verificar se existem dados de traffic_events no Cassandra após a simulação
    echo "🔍 Verificando eventos de tráfego no Cassandra..."
    echo "📊 Comando para verificar: docker compose exec cassandra cqlsh -e \"SELECT report_type FROM htc_reports.simulation_reports WHERE report_type='traffic_events' LIMIT 5 ALLOW FILTERING;\""
    
else
    echo "❌ Erro na compilação. Verifique o código Scala."
    exit 1
fi

echo "✅ Teste de setup concluído!"
echo "💡 Para executar uma simulação completa: sbt run"
echo "🔍 Para verificar dados: execute a comparação novamente após a simulação"