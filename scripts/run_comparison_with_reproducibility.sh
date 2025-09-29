#!/bin/bash
# Script de exemplo para executar comparação com análise de reprodutibilidade

echo "🚀 COMPARAÇÃO DE SIMULADORES COM ANÁLISE DE REPRODUTIBILIDADE"
echo "=============================================================="

cd /home/dean/PhD/hyperbolic-time-chamber/scripts

echo ""
echo "📊 1. Comparação simples HTC vs Referência (sem reprodutibilidade):"
echo "   - Apenas 2 execuções: HTC principal + Referência principal"
echo ""

# Exemplo de comparação simples
# python compare_simulators.py events_reference.xml --htc-cassandra

echo ""
echo "🔄 2. Comparação com análise de reprodutibilidade - múltiplas execuções HTC:"
echo "   - 1 HTC principal + 1 Referência + N execuções HTC adicionais"
echo ""

# Exemplo com múltiplas simulações HTC do Cassandra
python compare_simulators.py \
  /path/to/reference_events.xml \
  --htc-cassandra \
  --additional-htc-sims cenario_1000_viagens_2 cenario_1000_viagens_3 \
  --output output/comparison_with_reproducibility_htc

echo ""
echo "🔄 3. Comparação com análise de reprodutibilidade - múltiplas referências:"
echo "   - 1 HTC principal + 1 Referência principal + N arquivos XML adicionais"
echo ""

# Exemplo com múltiplos arquivos de referência
# python compare_simulators.py \
#   events_reference_1.xml \
#   --htc-cassandra \
#   --additional-ref-files events_reference_2.xml events_reference_3.xml \
#   --output output/comparison_with_reproducibility_ref

echo ""
echo "🔄 4. Comparação completa com análise de reprodutibilidade:"
echo "   - Múltiplas execuções de ambos os simuladores"
echo ""

# Exemplo com múltiplas execuções de ambos
# python compare_simulators.py \
#   events_reference_1.xml \
#   --htc-cassandra \
#   --additional-htc-sims cenario_1000_viagens_2 cenario_1000_viagens_3 \
#   --additional-ref-files events_reference_2.xml events_reference_3.xml \
#   --output output/comparison_full_reproducibility

echo ""
echo "📈 5. Análise pura de reprodutibilidade (redirecionamento):"
echo "   - Usando o script dedicado de reprodutibilidade"
echo ""

# Exemplo de redirecionamento para análise pura
# python compare_simulators.py \
#   --reproducibility \
#   --cassandra-sims cenario_1000_viagens_1 cenario_1000_viagens_2 cenario_1000_viagens_3

echo ""
echo "🎯 INTERPRETAÇÃO DOS RESULTADOS:"
echo ""
echo "📊 Métricas de Reprodutibilidade (baseadas em TICK):"
echo "   • CV < 0.001: Determinística (ideal para simulações científicas)"
echo "   • CV < 0.01:  Excelente reprodutibilidade"
echo "   • CV < 0.05:  Boa reprodutibilidade"
echo "   • CV < 0.1:   Reprodutibilidade moderada"
echo "   • CV >= 0.1:  Baixa reprodutibilidade (investigar)"
echo ""
echo "🎯 Score de Reprodutibilidade:"
echo "   • Score >= 0.9: Excelente"
echo "   • Score >= 0.8: Boa"
echo "   • Score >= 0.6: Moderada"
echo "   • Score < 0.6:  Baixa (requer investigação)"
echo ""
echo "📁 Arquivos Gerados:"
echo "   • comparison/ - Comparação tradicional HTC vs Referência"
echo "   • reproducibility_analysis/ - Análise de reprodutibilidade (TICK-based)"
echo "     ├── temporal_reproducibility.png/pdf - Padrões temporais"
echo "     ├── basic_metrics_comparison.png/pdf - Métricas básicas"
echo "     ├── similarity_scores.png/pdf - Scores de similaridade"
echo "     ├── reproducibility_dashboard.png/pdf - Dashboard consolidado"
echo "     └── reproducibility_report.json - Relatório completo"
echo ""
echo "🔬 FOCO EM TICK:"
echo "   • TICK = Tempo lógico da simulação (determinístico)"
echo "   • TIMESTAMP = Tempo real de processamento (varia entre execuções)"
echo "   • Para pesquisa científica: TICK é a medida correta!"
echo ""
echo "✅ Script de exemplo criado! Execute os comandos conforme necessário."