#!/bin/bash
# Script para validar determinismo do simulador HTC
# Executa múltiplas simulações idênticas e compara resultados

echo "🔬 VALIDAÇÃO DE DETERMINISMO DO SIMULADOR HTC"
echo "=============================================="

# Configurações
SIMULATION_CONFIG="cenario_sao_paulo_2500"
NUM_RUNS=3
BASE_DIR="/home/dean/PhD/hyperbolic-time-chamber"
SCRIPTS_DIR="$BASE_DIR/scripts"
OUTPUT_DIR="$SCRIPTS_DIR/determinism_validation"

# Criar diretório de saída
mkdir -p "$OUTPUT_DIR"

cd "$BASE_DIR"

echo ""
echo "📋 CONFIGURAÇÃO DO TESTE:"
echo "  • Simulação: $SIMULATION_CONFIG"
echo "  • Número de execuções: $NUM_RUNS"
echo "  • Diretório de saída: $OUTPUT_DIR"
echo ""

# Array para armazenar IDs das simulações
SIMULATION_IDS=()

echo "🚀 EXECUTANDO SIMULAÇÕES IDÊNTICAS..."
echo "======================================"

for i in $(seq 1 $NUM_RUNS); do
    echo ""
    echo "📊 Execução $i/$NUM_RUNS - $(date)"
    echo "--------------------------------"
    
    # Definir ID único para esta execução
    RUN_ID="determinism_test_run_${i}"
    SIMULATION_IDS+=("$RUN_ID")
    
    echo "🆔 ID da simulação: $RUN_ID"
    
    # Configurar variável de ambiente para ID da simulação
    export HTC_SIMULATION_ID="$RUN_ID"
    
    echo "🏃 Iniciando simulação..."
    
    # Executar simulação (capturar logs)
    if timeout 300s ./build-and-run.sh > "$OUTPUT_DIR/simulation_${i}_log.txt" 2>&1; then
        echo "✅ Simulação $i concluída com sucesso"
    else
        echo "❌ Simulação $i falhou ou timeout"
        echo "📄 Log salvo em: $OUTPUT_DIR/simulation_${i}_log.txt"
    fi
    
    # Esperar um pouco entre execuções para evitar interferência
    echo "⏳ Aguardando 10 segundos antes da próxima execução..."
    sleep 10
done

echo ""
echo "📊 ANÁLISE DE DETERMINISMO"
echo "=========================="

cd "$SCRIPTS_DIR"

# Verificar se temos dados no Cassandra para todas as execuções
echo ""
echo "🔍 Verificando dados no Cassandra..."

for sim_id in "${SIMULATION_IDS[@]}"; do
    echo -n "  • $sim_id: "
    
    # Contar registros no Cassandra
    count=$(docker exec -it $(docker ps -q --filter "name=cassandra") cqlsh -e "USE htc_reports; SELECT COUNT(*) FROM simulation_reports WHERE simulation_id = '$sim_id' ALLOW FILTERING;" 2>/dev/null | grep -o '[0-9]\+' | tail -1)
    
    if [ -n "$count" ] && [ "$count" -gt 0 ]; then
        echo "$count registros ✅"
    else
        echo "Sem dados ❌"
    fi
done

echo ""
echo "🧮 EXECUTANDO ANÁLISE COMPARATIVA..."

# Executar análise de reprodutibilidade com todas as simulações
if [ ${#SIMULATION_IDS[@]} -ge 2 ]; then
    echo "📊 Analisando reprodutibilidade entre ${#SIMULATION_IDS[@]} execuções..."
    
    /home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python reproducibility_analysis.py \
        --cassandra-sims "${SIMULATION_IDS[@]}" \
        --output "$OUTPUT_DIR/reproducibility_analysis" \
        > "$OUTPUT_DIR/analysis_log.txt" 2>&1
    
    if [ $? -eq 0 ]; then
        echo "✅ Análise de reprodutibilidade concluída"
        echo "📄 Relatório salvo em: $OUTPUT_DIR/reproducibility_analysis/"
    else
        echo "❌ Erro na análise de reprodutibilidade"
        echo "📄 Log de erro: $OUTPUT_DIR/analysis_log.txt"
    fi
else
    echo "❌ Não há simulações suficientes para análise comparativa"
fi

echo ""
echo "🔬 ANÁLISE DETALHADA PAREADA"
echo "============================="

# Fazer comparação detalhada entre pares de simulações
for i in $(seq 1 $((${#SIMULATION_IDS[@]} - 1))); do
    for j in $(seq $((i + 1)) ${#SIMULATION_IDS[@]}); do
        sim1="${SIMULATION_IDS[$((i-1))]}"
        sim2="${SIMULATION_IDS[$((j-1))]}"
        
        echo ""
        echo "🔍 Comparando $sim1 vs $sim2..."
        
        /home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python detailed_comparison.py \
            "$sim1" "$sim2" > "$OUTPUT_DIR/detailed_${i}_vs_${j}.txt" 2>&1
        
        if [ $? -eq 0 ]; then
            echo "✅ Comparação detalhada salva em: $OUTPUT_DIR/detailed_${i}_vs_${j}.txt"
        else
            echo "❌ Erro na comparação detalhada"
        fi
    done
done

echo ""
echo "📈 RESUMO DOS RESULTADOS"
echo "========================"

# Tentar extrair métricas principais do relatório
if [ -f "$OUTPUT_DIR/reproducibility_analysis/reproducibility/reproducibility_report.json" ]; then
    echo ""
    echo "🎯 MÉTRICAS DE DETERMINISMO:"
    
    python3 -c "
import json
import sys

try:
    with open('$OUTPUT_DIR/reproducibility_analysis/reproducibility/reproducibility_report.json', 'r') as f:
        data = json.load(f)
    
    print(f'📊 Execuções analisadas: {len(data[\"execution_summary\"][\"execution_ids\"])}')
    print(f'📈 Variação em eventos: CV = {data[\"data_consistency\"][\"event_count_cv\"]:.6f}')
    print(f'📈 Variação em veículos: CV = {data[\"data_consistency\"][\"vehicle_count_cv\"]:.6f}')
    
    if 'cross_execution_variability' in data:
        cv_speed = data['cross_execution_variability'].get('calculated_speed', {}).get('cv', 'N/A')
        cv_time = data['cross_execution_variability'].get('travel_time', {}).get('cv', 'N/A')
        print(f'📈 Variabilidade Speed: CV = {cv_speed}')
        print(f'📈 Variabilidade Time: CV = {cv_time}')
    
    print('')
    print('🎯 INTERPRETAÇÃO:')
    print('  • CV < 0.001: Altamente determinístico')
    print('  • CV < 0.01:  Determinístico')
    print('  • CV < 0.05:  Parcialmente determinístico')
    print('  • CV > 0.10:  Não determinístico')
    
    # Verificar similarity scores
    if 'similarity_analysis' in data:
        similarities = data['similarity_analysis']['pairwise_similarities']
        if similarities:
            avg_sim = sum(sim['similarity_score'] for sim in similarities) / len(similarities)
            print(f'📊 Similaridade média: {avg_sim:.3f}')
            print('  • > 0.95: Execuções quase idênticas')
            print('  • > 0.80: Execuções similares')
            print('  • < 0.50: Execuções muito diferentes')

except Exception as e:
    print(f'❌ Erro ao processar relatório: {e}')
    sys.exit(1)
"
else
    echo "❌ Relatório de reprodutibilidade não encontrado"
fi

echo ""
echo "📁 ARQUIVOS GERADOS:"
echo "==================="
find "$OUTPUT_DIR" -name "*.txt" -o -name "*.json" -o -name "*.png" | sort | while read file; do
    echo "  📄 $(basename "$file") - $(stat -f%z "$file" 2>/dev/null || stat -c%s "$file") bytes"
done

echo ""
echo "🎯 CONCLUSÃO DO TESTE DE DETERMINISMO"
echo "======================================"
echo "✅ Teste concluído!"
echo "📁 Todos os resultados salvos em: $OUTPUT_DIR"
echo ""
echo "🔍 Para analisar os resultados:"
echo "  1. Verifique o relatório: $OUTPUT_DIR/reproducibility_analysis/"
echo "  2. Compare logs detalhados: $OUTPUT_DIR/detailed_*.txt"
echo "  3. Analise logs de simulação: $OUTPUT_DIR/simulation_*_log.txt"
echo ""
echo "📊 Se CV < 0.001: Simulador é determinístico ✅"
echo "📊 Se CV > 0.01:  Simulador é não-determinístico ❌"