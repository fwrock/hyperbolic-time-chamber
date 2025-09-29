#!/bin/bash
# Script para demonstrar a diferença entre análise baseada em timestamp vs tick

echo "🎯 DEMONSTRAÇÃO: TIMESTAMP vs TICK para Reprodutibilidade"
echo "============================================================"

cd /home/dean/PhD/hyperbolic-time-chamber/scripts

echo ""
echo "📊 1. Executando análise com prioridade em TICK (correto para simulação)..."
echo "   - Tick = tempo de simulação lógico"
echo "   - Reprodutibilidade baseada no que realmente importa"
echo ""

/home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python reproducibility_analysis.py \
  --cassandra-sims cenario_1000_viagens_1 cenario_1000_viagens_2 cenario_1000_viagens_3 \
  --output-dir output/tick_based_analysis

echo ""
echo "📈 2. Verificando os resultados..."
if [ -f "output/tick_based_analysis/reproducibility/reproducibility_report.json" ]; then
    echo "✅ Análise baseada em TICK executada com sucesso!"
    
    # Extrair métricas principais do relatório
    echo ""
    echo "🎯 MÉTRICAS DE REPRODUTIBILIDADE (baseadas em TICK):"
    python3 -c "
import json
with open('output/tick_based_analysis/reproducibility/reproducibility_report.json', 'r') as f:
    data = json.load(f)
    print(f'  • Variação em número de eventos: CV = {data[\"data_consistency\"][\"event_count_cv\"]:.4f}')
    print(f'  • Variação em número de veículos: CV = {data[\"data_consistency\"][\"vehicle_count_cv\"]:.4f}')
    print(f'  • Variabilidade Calculated Speed: CV = {data[\"cross_execution_variability\"][\"calculated_speed\"][\"cv\"]:.4f}')
    print(f'  • Variabilidade Travel Time: CV = {data[\"cross_execution_variability\"][\"travel_time\"][\"cv\"]:.4f}')
    print('')
    print('🎯 INTERPRETAÇÃO:')
    print('  - CV < 0.01: Excelente reprodutibilidade')
    print('  - CV < 0.05: Boa reprodutibilidade') 
    print('  - CV > 0.10: Problemas de reprodutibilidade')
"
else
    echo "❌ Erro na análise baseada em TICK"
fi

echo ""
echo "🔍 3. VANTAGENS da abordagem baseada em TICK:"
echo "   ✅ Elimina variações causadas por diferenças de execução"
echo "   ✅ Foca no tempo lógico da simulação (o que realmente importa)"
echo "   ✅ Permite comparação científica válida entre execuções"
echo "   ✅ Independente de infraestrutura e performance da máquina"
echo ""

echo "📚 4. EXPLICAÇÃO TÉCNICA:"
echo "   • TIMESTAMP = Momento real de processamento (varia entre execuções)"
echo "   • TICK = Tempo lógico da simulação (determinístico para mesmas entradas)"
echo "   • Para reprodutibilidade científica: TICK é a medida correta"
echo ""

echo "🎉 Demonstração concluída!"
echo "📁 Resultados salvos em: output/tick_based_analysis/"