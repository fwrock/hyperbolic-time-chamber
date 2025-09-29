# Comparação de Simuladores com Análise de Reprodutibilidade

## 🎯 Visão Geral

O script `compare_simulators.py` foi atualizado para incluir análise de reprodutibilidade automática quando múltiplas execuções são fornecidas. Isso permite uma avaliação científica completa da consistência dos simuladores.

## 🚀 Funcionalidades Principais

### 1. Comparação Tradicional (2 execuções)
- HTC vs Simulador de Referência
- Análise de similaridade básica
- Métricas de tráfego comparativas

### 2. Comparação com Reprodutibilidade (3+ execuções)
- **Análise baseada em TICK** (tempo lógico de simulação)
- Múltiplas execuções do HTC e/ou referência
- Gráficos específicos para comparação científica
- Métricas de determinismo e consistência

## 📊 Gráficos de Reprodutibilidade Específicos para Comparação

### 1. **Análise Temporal (baseada em TICK)**
- **Padrões temporais**: Fluxo horário por execução
- **Horários de pico**: Consistência dos picos de tráfego
- **Duração da simulação**: Consistência do tempo total (ticks)
- **Taxa de eventos por tick**: Indicador de determinismo

### 2. **Métricas de Determinismo**
- **Coeficiente de Variação (CV)** para duração
- **CV para taxa de eventos**: Indica reprodutibilidade
- **Intervalos de tick**: Consistência de início/fim
- **Score de reprodutibilidade**: Métrica combinada (0-1)

### 3. **Comparação HTC vs Referência**
- **Gráficos diferenciados**: Cores/símbolos para cada tipo
- **Análise de convergência**: Comparação de variabilidade
- **Métricas de validação**: Confiabilidade científica

### 4. **Dashboard Científico**
- **Layout acadêmico**: Adequado para artigos
- **Métricas consolidadas**: Visão geral da reprodutibilidade
- **Interpretação automática**: Classificação da qualidade

## 🔧 Como Usar

### Comparação Simples
```bash
python compare_simulators.py events.xml --htc-cassandra
```

### Comparação com Reprodutibilidade (múltiplas execuções HTC)
```bash
python compare_simulators.py events.xml --htc-cassandra \
  --additional-htc-sims sim_id_2 sim_id_3 sim_id_4
```

### Comparação com múltiplas referências
```bash
python compare_simulators.py events_1.xml --htc-cassandra \
  --additional-ref-files events_2.xml events_3.xml
```

### Comparação completa (múltiplas de ambos)
```bash
python compare_simulators.py events_1.xml --htc-cassandra \
  --additional-htc-sims sim_id_2 sim_id_3 \
  --additional-ref-files events_2.xml events_3.xml
```

## 📁 Estrutura de Saída

```
output/comparison/
├── comparison/                    # Comparação tradicional HTC vs Ref
│   ├── simulator_comparison_report.json
│   ├── comparison_summary.md
│   └── visualizations/
├── reproducibility_analysis/      # 🆕 Análise de reprodutibilidade
│   ├── temporal_reproducibility.png/pdf     # TICK-based temporal analysis
│   ├── basic_metrics_comparison.png/pdf     # Métricas básicas
│   ├── similarity_scores.png/pdf            # Scores de similaridade
│   ├── reproducibility_dashboard.png/pdf    # Dashboard científico
│   ├── reproducibility_report.json          # Relatório detalhado
│   └── individual_plots/                    # Gráficos individuais
├── general_metrics/               # Métricas HTC
└── reference_metrics/             # Métricas Referência
```

## 🕒 Importância do TICK vs TIMESTAMP

### Por que TICK é crucial?

1. **TICK = Tempo lógico da simulação**
   - Determinístico para mesmas entradas
   - Independente da infraestrutura de execução
   - Permite comparação científica válida

2. **TIMESTAMP = Tempo real de processamento**
   - Varia entre execuções (hardware, carga do sistema)
   - Não reflete o comportamento da simulação
   - Gera falsos negativos na reprodutibilidade

### Análise baseada em TICK

O sistema prioriza automaticamente a coluna `tick` quando disponível:

```python
# Detecção automática
time_col = 'tick' if any('tick' in df.columns for df in datasets) else 'timestamp'

# Análise temporal baseada em tick
if time_col == 'tick':
    df_copy['simulation_hour'] = (df_copy[time_col] // 3600).astype(int)
    # Análise de duração da simulação
    duration = df_copy[time_col].max() - df_copy[time_col].min()
```

## 📊 Interpretação das Métricas

### Coeficiente de Variação (CV)
- **CV < 0.001**: Determinística (ideal)
- **CV < 0.01**: Excelente reprodutibilidade
- **CV < 0.05**: Boa reprodutibilidade
- **CV < 0.1**: Moderada
- **CV ≥ 0.1**: Baixa (investigar)

### Score de Reprodutibilidade (0-1)
- **≥ 0.9**: Excelente
- **≥ 0.8**: Boa
- **≥ 0.6**: Moderada
- **< 0.6**: Baixa (requer investigação)

### Métricas de Tick
- **Duração consistente**: Todas as execuções devem ter mesma duração
- **Taxa de eventos consistente**: Events/tick deve ser estável
- **Intervalos consistentes**: Início/fim devem ser idênticos

## 🎯 Benefícios para Pesquisa

1. **Validação científica**: Reprodutibilidade baseada em tempo lógico
2. **Comparação confiável**: HTC vs referência com base temporal correta
3. **Detecção de problemas**: Identificação automática de inconsistências
4. **Documentação automática**: Relatórios adequados para artigos
5. **Visualizações acadêmicas**: Gráficos prontos para publicação

## 🔍 Casos de Uso

### Para Desenvolvimento
- Validar determinismo do simulador
- Comparar versões do algoritmo
- Verificar impacto de mudanças

### Para Pesquisa
- Validar modelos contra referência
- Demonstrar reprodutibilidade científica
- Gerar evidências para artigos

### Para Otimização
- Identificar fontes de variabilidade
- Comparar configurações
- Avaliar melhorias de performance

## 🚀 Próximos Passos

1. **Execute comparações com múltiplas execuções**
2. **Analise os gráficos de reprodutibilidade gerados**
3. **Verifique métricas baseadas em TICK**
4. **Use os relatórios para documentação científica**
5. **Identifique e corrija problemas de determinismo**

---

*Este sistema garante que a comparação entre simuladores seja feita com base científica sólida, focando no que realmente importa para a reprodutibilidade: o comportamento temporal lógico da simulação.*