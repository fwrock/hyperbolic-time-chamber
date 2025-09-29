# Validação de Determinismo do Simulador HTC

## 🎯 Objetivo

Antes de implementar modificações para tornar o simulador determinístico, precisamos **validar se ele já é determinístico** ou identificar onde estão as fontes de não-determinismo.

## 🔬 Metodologia

### **1. Execução de Simulações Idênticas**
- Usar **exatamente as mesmas entradas**
- Executar **múltiplas vezes** (2-5 execuções)
- Comparar **todos os aspectos** dos resultados

### **2. Análise Multi-Dimensional**
- **Estatísticas básicas**: Contagem de eventos, veículos
- **Padrões temporais**: Sobreposição de ticks, sequência de eventos
- **Sequências de eventos**: Ordem, timing, tipos
- **Testes estatísticos**: ANOVA, Kruskal-Wallis

### **3. Score de Determinismo**
- **0.95-1.00**: Altamente determinístico ✅
- **0.80-0.94**: Determinístico ✅
- **0.60-0.79**: Parcialmente determinístico ⚠️
- **0.30-0.59**: Pouco determinístico ❌
- **0.00-0.29**: Não determinístico ❌

## 🛠️ Scripts Disponíveis

### **1. Teste Rápido (Recomendado para começar)**
```bash
./quick_determinism_test.sh
```
- **Tempo**: ~6-10 minutos
- **Simulações**: 2 execuções paralelas
- **Análise**: Comparação básica + score determinismo

### **2. Validação Completa**
```bash
./validate_determinism.sh
```
- **Tempo**: ~15-30 minutos
- **Simulações**: 3 execuções sequenciais
- **Análise**: Completa com visualizações

### **3. Análise Personalizada**
```bash
# Para simulações já executadas
python determinism_validator.py sim_id1 sim_id2 sim_id3

# Para comparação detalhada entre 2 simulações
python detailed_comparison.py sim_id1 sim_id2
```

## 📊 Interpretação dos Resultados

### **A. Estatísticas Básicas**
```
📈 ESTATÍSTICAS BÁSICAS:
  • quick_test_1: 131556 registros
  • quick_test_2: 131588 registros  ← Diferença pequena: OK
```

### **B. Análise Temporal**
```
⏰ ANÁLISE TEMPORAL:
  • Ticks em comum: 24
  • Ticks únicos em sim1: 968  ← Muitos ticks únicos: PROBLEMA
  • Ticks únicos em sim2: 970
```

### **C. Análise de Veículos**
```
🚗 ANÁLISE DE VEÍCULOS:
  • Veículos em comum: 382
  • Únicos em sim1: 214  ← Muitos veículos únicos: PROBLEMA
  • Únicos em sim2: 188
```

### **D. Score de Determinismo**
```
🎯 Score de determinismo: 0.125  ← BAIXO: Não determinístico
📝 Conclusão: NÃO DETERMINÍSTICO - Resultados variam substancialmente
```

## 🔍 Possíveis Cenários e Diagnósticos

### **Cenário 1: Altamente Determinístico (Score > 0.95)**
```
✅ RESULTADO ESPERADO SE DETERMINÍSTICO:
• Ticks em comum: >95% 
• Veículos em comum: >95%
• CV de métricas: <0.001
• Mesma sequência de eventos
```
**Conclusão**: Simulador já é determinístico!

### **Cenário 2: Não Determinístico (Score < 0.30)**
```
❌ RESULTADO ATUAL (PROVÁVEL):
• Ticks em comum: <10%
• Veículos únicos: >50%
• CV de métricas: >0.01
• Eventos em ordens diferentes
```
**Conclusão**: Necessário implementar random seed fixo.

### **Cenário 3: Parcialmente Determinístico (Score 0.60-0.79)**
```
⚠️ RESULTADO INTERMEDIÁRIO:
• Algumas métricas consistentes
• Outras com variação
• Possível problema específico
```
**Conclusão**: Investigar fontes específicas de variação.

## 🎯 Próximos Passos Baseados nos Resultados

### **Se Score > 0.95** (Já Determinístico)
1. ✅ **Simulador já é reprodutível**
2. 🔍 Investigar por que análise anterior mostrou baixa similaridade
3. 🛠️ Focar em melhorar métricas de similaridade

### **Se Score < 0.30** (Não Determinístico)
1. 🔧 **Implementar random seed fixo**
2. 📝 Adicionar campo `randomSeed` ao `Simulation.scala`
3. ⚙️ Aplicar seed em pontos críticos do código
4. 🔄 Re-executar validação

### **Se Score 0.30-0.95** (Parcialmente Determinístico)
1. 🔍 **Análise detalhada** das fontes de variação
2. 🎯 **Correções específicas** nos pontos identificados
3. 🔄 **Validação iterativa** após cada correção

## 📁 Estrutura de Arquivos Gerados

```
determinism_validation/
├── determinism_analysis.json      # Análise completa
├── determinism_analysis.png       # Visualizações
├── comparison_result.txt          # Comparação detalhada
├── simulation_*_log.txt          # Logs de execução
└── reproducibility_analysis/     # Análise de reprodutibilidade
```

## 🚀 Execução Recomendada

```bash
# 1. Teste rápido primeiro
cd /home/dean/PhD/hyperbolic-time-chamber/scripts
./quick_determinism_test.sh

# 2. Analise os resultados

# 3. Se necessário, execute validação completa
./validate_determinism.sh

# 4. Baseado nos resultados, proceda com correções
```

## 🎯 Critério de Sucesso

**OBJETIVO**: Atingir score de determinismo > 0.95

**MÉTRICA**: >95% de sobreposição em ticks e veículos entre execuções idênticas

**VALIDAÇÃO**: Reproduzir resultados científicos com variação CV < 0.001