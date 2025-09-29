#!/bin/bash
# Script para validar determinismo da simulação de referência
# Executa a mesma simulação múltiplas vezes e compara resultados

echo "🔬 VALIDAÇÃO DE DETERMINISMO - SIMULAÇÃO DE REFERÊNCIA"
echo "======================================================"

# Configurações
BASE_DIR="/home/dean/PhD/hyperbolic-time-chamber"
SCRIPTS_DIR="$BASE_DIR/scripts"
OUTPUT_DIR="$SCRIPTS_DIR/reference_determinism_validation"
SIMULATION_DIR="$BASE_DIR/simulations/input/example_simulation"

# Criar diretório de saída
mkdir -p "$OUTPUT_DIR"

cd "$BASE_DIR"

echo ""
echo "📋 CONFIGURAÇÃO DO TESTE:"
echo "  • Simulação: simulation.json (referência)"
echo "  • Número de execuções: 3"
echo "  • Diretório de saída: $OUTPUT_DIR"
echo ""

# Verificar se simulation.json existe
if [ ! -f "$SIMULATION_DIR/simulation.json" ]; then
    echo "❌ Erro: simulation.json não encontrado em $SIMULATION_DIR"
    exit 1
fi

echo "📄 Configuração da simulação:"
cat "$SIMULATION_DIR/simulation.json" | head -10
echo ""

# Array para armazenar IDs das simulações
SIMULATION_IDS=()

echo "🚀 EXECUTANDO SIMULAÇÕES IDÊNTICAS DA REFERÊNCIA..."
echo "=================================================="

for i in {1..3}; do
    echo ""
    echo "📊 Execução $i/3 - $(date)"
    echo "--------------------------------"
    
    # Definir ID único para esta execução
    RUN_ID="reference_test_run_${i}"
    SIMULATION_IDS+=("$RUN_ID")
    
    echo "🆔 ID da simulação: $RUN_ID"
    
    # Configurar variável de ambiente para ID da simulação
    export HTC_SIMULATION_ID="$RUN_ID"
    
    # Garantir que está usando a simulação de referência
    export SIMULATION_FILE="$SIMULATION_DIR/simulation.json"
    
    echo "📁 Arquivo de simulação: $SIMULATION_FILE"
    echo "🏃 Iniciando simulação de referência..."
    
    # Executar simulação (capturar logs)
    if timeout 300s ./build-and-run.sh > "$OUTPUT_DIR/reference_simulation_${i}_log.txt" 2>&1; then
        echo "✅ Simulação $i concluída com sucesso"
    else
        EXIT_CODE=$?
        if [ $EXIT_CODE -eq 124 ]; then
            echo "⏰ Simulação $i - timeout após 5 minutos"
        else
            echo "❌ Simulação $i falhou (código: $EXIT_CODE)"
        fi
        echo "📄 Log salvo em: $OUTPUT_DIR/reference_simulation_${i}_log.txt"
    fi
    
    # Esperar um pouco entre execuções para evitar interferência
    if [ $i -lt 3 ]; then
        echo "⏳ Aguardando 15 segundos antes da próxima execução..."
        sleep 15
    fi
done

echo ""
echo "📊 VERIFICAÇÃO DE DADOS GERADOS"
echo "==============================="

cd "$SCRIPTS_DIR"

echo ""
echo "🔍 Verificando dados no Cassandra..."

DATA_FOUND=0
for sim_id in "${SIMULATION_IDS[@]}"; do
    echo -n "  • $sim_id: "
    
    # Contar registros no Cassandra
    count=$(docker exec -it $(docker ps -q --filter "name=cassandra") cqlsh -e "USE htc_reports; SELECT COUNT(*) FROM simulation_reports WHERE simulation_id = '$sim_id' ALLOW FILTERING;" 2>/dev/null | grep -o '[0-9]\+' | tail -1)
    
    if [ -n "$count" ] && [ "$count" -gt 0 ]; then
        echo "$count registros ✅"
        DATA_FOUND=$((DATA_FOUND + 1))
    else
        echo "Sem dados ❌"
    fi
done

if [ $DATA_FOUND -lt 2 ]; then
    echo ""
    echo "❌ ERRO: Não há dados suficientes para análise"
    echo "📋 Verificar logs de execução:"
    for i in {1..3}; do
        echo "  📄 Execução $i: $OUTPUT_DIR/reference_simulation_${i}_log.txt"
    done
    exit 1
fi

echo ""
echo "🧮 EXECUTANDO ANÁLISE DE DETERMINISMO DA REFERÊNCIA"
echo "=================================================="

# Executar análise de reprodutibilidade com todas as simulações
echo "📊 Analisando determinismo entre ${#SIMULATION_IDS[@]} execuções da simulação de referência..."

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

echo ""
echo "🔬 ANÁLISE DETALHADA DE DETERMINISMO"
echo "==================================="

# Executar validador de determinismo específico
echo "📊 Executando análise estatística de determinismo..."

/home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python determinism_validator.py \
    "${SIMULATION_IDS[@]}" > "$OUTPUT_DIR/determinism_validation.txt" 2>&1

if [ $? -eq 0 ]; then
    echo "✅ Validação de determinismo concluída"
else
    echo "❌ Erro na validação de determinismo"
fi

echo ""
echo "🔍 COMPARAÇÕES DETALHADAS ENTRE PARES"
echo "====================================="

# Fazer comparação detalhada entre pares de simulações
comparison_count=0
for i in $(seq 1 $((${#SIMULATION_IDS[@]} - 1))); do
    for j in $(seq $((i + 1)) ${#SIMULATION_IDS[@]}); do
        sim1="${SIMULATION_IDS[$((i-1))]}"
        sim2="${SIMULATION_IDS[$((j-1))]}"
        
        echo ""
        echo "🔍 Comparação detalhada: $sim1 vs $sim2..."
        
        /home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python detailed_comparison.py \
            "$sim1" "$sim2" > "$OUTPUT_DIR/detailed_comparison_${i}_vs_${j}.txt" 2>&1
        
        if [ $? -eq 0 ]; then
            echo "✅ Comparação salva em: $OUTPUT_DIR/detailed_comparison_${i}_vs_${j}.txt"
            comparison_count=$((comparison_count + 1))
        else
            echo "❌ Erro na comparação detalhada"
        fi
    done
done

echo ""
echo "📈 RESUMO DOS RESULTADOS DA SIMULAÇÃO DE REFERÊNCIA"
echo "=================================================="

# Extrair e apresentar métricas principais
if [ -f "$OUTPUT_DIR/reproducibility_analysis/reproducibility/reproducibility_report.json" ]; then
    echo ""
    echo "🎯 MÉTRICAS DE DETERMINISMO DA SIMULAÇÃO DE REFERÊNCIA:"
    
    python3 -c "
import json
import sys

try:
    with open('$OUTPUT_DIR/reproducibility_analysis/reproducibility/reproducibility_report.json', 'r') as f:
        data = json.load(f)
    
    print(f'📊 Execuções da referência analisadas: {len(data[\"execution_summary\"][\"execution_ids\"])}')
    print(f'📈 Variação em número de eventos: CV = {data[\"data_consistency\"][\"event_count_cv\"]:.6f}')
    print(f'📈 Variação em número de veículos: CV = {data[\"data_consistency\"][\"vehicle_count_cv\"]:.6f}')
    
    if 'cross_execution_variability' in data:
        cv_speed = data['cross_execution_variability'].get('calculated_speed', {}).get('cv', 'N/A')
        cv_time = data['cross_execution_variability'].get('travel_time', {}).get('cv', 'N/A')
        print(f'📈 Variabilidade Speed: CV = {cv_speed}')
        print(f'📈 Variabilidade Time: CV = {cv_time}')
    
    print('')
    print('🎯 INTERPRETAÇÃO PARA SIMULAÇÃO DE REFERÊNCIA:')
    print('  • CV < 0.001: Simulação de referência ALTAMENTE DETERMINÍSTICA ✅')
    print('  • CV < 0.01:  Simulação de referência DETERMINÍSTICA ✅')
    print('  • CV < 0.05:  Simulação de referência PARCIALMENTE DETERMINÍSTICA ⚠️')
    print('  • CV > 0.10:  Simulação de referência NÃO DETERMINÍSTICA ❌')
    
    # Verificar similarity scores
    if 'similarity_analysis' in data:
        similarities = data['similarity_analysis']['pairwise_similarities']
        if similarities:
            avg_sim = sum(sim['similarity_score'] for sim in similarities) / len(similarities)
            print(f'📊 Similaridade média entre execuções: {avg_sim:.3f}')
            print('  • > 0.95: Execuções da referência são QUASE IDÊNTICAS ✅')
            print('  • > 0.80: Execuções da referência são SIMILARES ✅')
            print('  • > 0.50: Execuções da referência são MODERADAMENTE SIMILARES ⚠️')
            print('  • < 0.50: Execuções da referência são MUITO DIFERENTES ❌')

except Exception as e:
    print(f'❌ Erro ao processar relatório: {e}')
    sys.exit(1)
"
else
    echo "❌ Relatório de reprodutibilidade não encontrado"
fi

# Mostrar score de determinismo se disponível
if [ -f "$OUTPUT_DIR/determinism_validation.txt" ]; then
    echo ""
    echo "🎯 SCORE DE DETERMINISMO DA SIMULAÇÃO DE REFERÊNCIA:"
    grep -E "(Score de determinismo|Conclusão)" "$OUTPUT_DIR/determinism_validation.txt" 2>/dev/null || echo "Verificar arquivo: $OUTPUT_DIR/determinism_validation.txt"
fi

echo ""
echo "📁 ARQUIVOS GERADOS PARA ANÁLISE:"
echo "================================="
find "$OUTPUT_DIR" -name "*.txt" -o -name "*.json" -o -name "*.png" | sort | while read file; do
    size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file")
    echo "  📄 $(basename "$file") - $size bytes"
done

echo ""
echo "🎯 CONCLUSÃO DA VALIDAÇÃO DA SIMULAÇÃO DE REFERÊNCIA"
echo "=================================================="
echo "✅ Validação da simulação de referência concluída!"
echo "📁 Todos os resultados salvos em: $OUTPUT_DIR"
echo ""
echo "🔍 PRÓXIMOS PASSOS:"
echo "  1. Analise o relatório: $OUTPUT_DIR/reproducibility_analysis/"
echo "  2. Verifique comparações: $OUTPUT_DIR/detailed_comparison_*.txt"
echo "  3. Examine logs de simulação: $OUTPUT_DIR/reference_simulation_*_log.txt"
echo ""
echo "🎯 CRITÉRIO DE SUCESSO:"
echo "  • Se CV < 0.001 e Similaridade > 0.95: SIMULAÇÃO DE REFERÊNCIA É DETERMINÍSTICA ✅"
echo "  • Se CV > 0.01 ou Similaridade < 0.80: SIMULAÇÃO DE REFERÊNCIA NÃO É DETERMINÍSTICA ❌"
echo ""
echo "📊 Baseado nos resultados, podemos concluir se o problema está na:"
echo "  • Simulação de referência (não determinística)"
echo "  • Configuração de execução"  
echo "  • Infraestrutura de comparação"