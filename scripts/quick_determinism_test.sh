#!/bin/bash
# Script rápido para validação de determinismo (versão compacta)

echo "🔬 TESTE RÁPIDO DE DETERMINISMO"
echo "==============================="

# Configurações
BASE_DIR="/home/dean/PhD/hyperbolic-time-chamber"
SCRIPTS_DIR="$BASE_DIR/scripts"
OUTPUT_DIR="$SCRIPTS_DIR/quick_determinism_test"

# Criar diretório de saída
mkdir -p "$OUTPUT_DIR"

cd "$BASE_DIR"

echo ""
echo "🚀 EXECUTANDO 2 SIMULAÇÕES IDÊNTICAS..."

# Simulação 1
echo "📊 Simulação 1/2..."
export HTC_SIMULATION_ID="quick_test_1"
timeout 180s ./build-and-run.sh > "$OUTPUT_DIR/sim1_log.txt" 2>&1 &
SIM1_PID=$!

# Aguardar um pouco
sleep 5

# Simulação 2
echo "📊 Simulação 2/2..."
export HTC_SIMULATION_ID="quick_test_2"
timeout 180s ./build-and-run.sh > "$OUTPUT_DIR/sim2_log.txt" 2>&1 &
SIM2_PID=$!

# Aguardar conclusão
echo "⏳ Aguardando conclusão das simulações..."
wait $SIM1_PID
wait $SIM2_PID

echo "✅ Simulações concluídas"

# Verificar dados
echo ""
echo "🔍 Verificando dados gerados..."

cd "$SCRIPTS_DIR"

count1=$(docker exec -it $(docker ps -q --filter "name=cassandra") cqlsh -e "USE htc_reports; SELECT COUNT(*) FROM simulation_reports WHERE simulation_id = 'quick_test_1' ALLOW FILTERING;" 2>/dev/null | grep -o '[0-9]\+' | tail -1 || echo "0")

count2=$(docker exec -it $(docker ps -q --filter "name=cassandra") cqlsh -e "USE htc_reports; SELECT COUNT(*) FROM simulation_reports WHERE simulation_id = 'quick_test_2' ALLOW FILTERING;" 2>/dev/null | grep -o '[0-9]\+' | tail -1 || echo "0")

echo "  • Simulação 1: $count1 registros"
echo "  • Simulação 2: $count2 registros"

if [ "$count1" -gt 0 ] && [ "$count2" -gt 0 ]; then
    echo ""
    echo "📊 EXECUTANDO ANÁLISE COMPARATIVA..."
    
    # Análise detalhada
    /home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python detailed_comparison.py quick_test_1 quick_test_2 > "$OUTPUT_DIR/comparison_result.txt" 2>&1
    
    # Análise estatística
    /home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python determinism_validator.py quick_test_1 quick_test_2 > "$OUTPUT_DIR/determinism_result.txt" 2>&1
    
    echo "✅ Análise concluída!"
    echo ""
    echo "📄 RESULTADOS:"
    echo "=============="
    
    # Mostrar resumo da comparação
    echo ""
    echo "🔍 COMPARAÇÃO DETALHADA:"
    if [ -f "$OUTPUT_DIR/comparison_result.txt" ]; then
        # Extrair métricas principais
        grep -E "(ESTATÍSTICAS BÁSICAS|ANÁLISE TEMPORAL|ANÁLISE DE VEÍCULOS|Ticks em comum|Veículos em comum)" "$OUTPUT_DIR/comparison_result.txt" || echo "Ver arquivo completo: $OUTPUT_DIR/comparison_result.txt"
    fi
    
    echo ""
    echo "🎯 SCORE DE DETERMINISMO:"
    if [ -f "$OUTPUT_DIR/determinism_result.txt" ]; then
        grep -E "(Score de determinismo|Conclusão)" "$OUTPUT_DIR/determinism_result.txt" || echo "Ver arquivo completo: $OUTPUT_DIR/determinism_result.txt"
    fi
    
else
    echo "❌ Não há dados suficientes para análise"
    echo "📄 Logs de simulação:"
    echo "  • Sim1: $OUTPUT_DIR/sim1_log.txt"
    echo "  • Sim2: $OUTPUT_DIR/sim2_log.txt"
fi

echo ""
echo "📁 Todos os resultados em: $OUTPUT_DIR"
echo "🎯 Para análise completa, execute: ./validate_determinism.sh"