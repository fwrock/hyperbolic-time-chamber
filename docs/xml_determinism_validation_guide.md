# Guia: Validação de Determinismo de Simulação de Referência (events.xml)

## 🎯 Objetivo

Comparar arquivos `events.xml` de duas execuções idênticas da simulação de referência para determinar se ela é determinística.

## 📋 Pré-requisitos

- Simulação de referência configurada (MATSim, SUMO, etc.)
- Arquivos de configuração idênticos
- Capacidade de executar a simulação duas vezes

## 🛠️ Ferramentas Disponíveis

### 1. **Script Automático (Recomendado)**
```bash
./validate_reference_xml_determinism.sh
```

### 2. **Comparador Direto**
```bash
python compare_events_xml.py events1.xml events2.xml
```

## 🔧 Configuração para Sua Simulação

### **1. Editar o Script de Validação**

Abra `validate_reference_xml_determinism.sh` e substitua estas linhas:

```bash
# ANTES (exemplo)
echo "⚠️ SIMULANDO EXECUÇÃO (substitua pelo comando real)..."

# DEPOIS (seu comando real)
# Para MATSim:
java -Xmx8g -cp matsim.jar org.matsim.run.RunMatsim config.xml

# Para SUMO:
sumo -c simulation.sumocfg --xml-output "$OUTPUT_DIR/events1.xml"

# Para outro simulador:
seu_simulador --config config.xml --output "$OUTPUT_DIR/events1.xml"
```

### **2. Configurar Caminhos de Saída**

Certifique-se de que sua simulação salve os eventos XML nos locais corretos:

```bash
# Primeira execução
seu_simulador --output "$OUTPUT_DIR/events1.xml"

# Segunda execução  
seu_simulador --output "$OUTPUT_DIR/events2.xml"
```

## 📊 Exemplo de Uso Manual

Se você já tem dois arquivos `events.xml`:

```bash
cd /home/dean/PhD/hyperbolic-time-chamber/scripts

# Comparar diretamente
python compare_events_xml.py \
    /path/to/first/events.xml \
    /path/to/second/events.xml \
    --output results_determinism
```

## 📈 Interpretação dos Resultados

### **A. Score de Determinismo**

| Score | Interpretação | Ação |
|-------|---------------|------|
| **1.00** | PERFEITAMENTE DETERMINÍSTICO | ✅ Arquivos XML idênticos |
| **0.95-0.99** | ALTAMENTE DETERMINÍSTICO | ✅ Diferenças mínimas |
| **0.80-0.94** | DETERMINÍSTICO | ✅ Algumas pequenas variações |
| **0.60-0.79** | PARCIALMENTE DETERMINÍSTICO | ⚠️ Investigar diferenças |
| **< 0.60** | NÃO DETERMINÍSTICO | ❌ Implementar random seed |

### **B. Análise Detalhada**

```
📊 ESTATÍSTICAS BÁSICAS:
  • Eventos: 15234 vs 15234 ✅     <- Mesmo número de eventos
  • Pessoas: 1000 vs 1000 ✅       <- Mesmo número de pessoas

⏰ ANÁLISE TEMPORAL:
  • Tempos em comum: 8547          <- Eventos em mesmos tempos
  • Sobreposição temporal: 0.956   <- 95.6% de sobreposição

🔄 SEQUÊNCIA DE EVENTOS:
  • Correspondências exatas: 987   <- Eventos idênticos na sequência
  • Taxa de correspondência: 0.987 <- 98.7% igual

🎯 CORRESPONDÊNCIAS EXATAS:
  • Arquivos idênticos: SIM        <- Arquivos completamente iguais
  • Taxa de correspondência: 1.000 <- 100% igual
```

## 🎯 Cenários Típicos

### **Cenário 1: Perfeitamente Determinístico**
```
🎯 Score: 1.000
📝 Conclusão: PERFEITAMENTE DETERMINÍSTICO - Arquivos XML são IDÊNTICOS
🎉 SIMULAÇÃO DE REFERÊNCIA É PERFEITAMENTE DETERMINÍSTICA!
```
**Interpretação**: Simulação é determinística ✅

### **Cenário 2: Não Determinístico**
```
🎯 Score: 0.234
📝 Conclusão: NÃO DETERMINÍSTICO - Execuções produzem resultados muito diferentes
❌ SIMULAÇÃO DE REFERÊNCIA NÃO É DETERMINÍSTICA!
```
**Interpretação**: Implementar random seed ❌

### **Cenário 3: Quase Determinístico**
```
🎯 Score: 0.891
📝 Conclusão: DETERMINÍSTICO - Resultados consistentes com pequenas variações
✅ SIMULAÇÃO DE REFERÊNCIA É DETERMINÍSTICA!
```
**Interpretação**: Determinística com pequenas variações ✅

## 🔍 Principais Diferenças Analisadas

### **1. Timing dos Eventos**
- Eventos acontecem nos mesmos tempos?
- Sequência temporal é preservada?

### **2. Pessoas e Veículos**
- Mesmas pessoas participam?
- Mesmos IDs de veículos?

### **3. Sequência de Eventos**
- Primeiros N eventos são idênticos?
- Ordem de eventos é preservada?

### **4. Correspondência Exata**
- Arquivos são byte-a-byte idênticos?
- Hash dos eventos coincide?

## ⚙️ Configurações Comuns por Simulador

### **MATSim**
```xml
<!-- config.xml -->
<config>
    <global>
        <randomSeed>12345</randomSeed>
    </global>
</config>
```

### **SUMO**
```xml
<!-- simulation.sumocfg -->
<configuration>
    <random_number>
        <seed value="12345"/>
    </random_number>
</configuration>
```

### **Outros Simuladores**
Procure por configurações de:
- Random seed
- Random number generator
- Stochastic processes

## 🚀 Execução Rápida

```bash
# 1. Navegar para o diretório
cd /home/dean/PhD/hyperbolic-time-chamber/scripts

# 2. (Opcional) Editar o script para sua simulação
nano validate_reference_xml_determinism.sh

# 3. Executar validação
./validate_reference_xml_determinism.sh

# 4. Analisar resultados
cat reference_xml_determinism/xml_determinism_report.txt
```

## 📁 Estrutura de Saída

```
reference_xml_determinism/
├── events1.xml                      # Primeira execução
├── events2.xml                      # Segunda execução
├── xml_determinism_comparison.json  # Dados completos
├── xml_determinism_report.txt       # Relatório legível
└── comparison_log.txt               # Log de execução
```

## 🎯 Critério de Sucesso

**OBJETIVO**: Score ≥ 0.95 para considerar determinística

**IDEAL**: Score = 1.00 (arquivos idênticos)

**ACEITÁVEL**: Score ≥ 0.80 (pequenas variações toleráveis)

## 📋 Troubleshooting

### **Problema: Score baixo inesperado**
1. Verificar se configurações são idênticas
2. Verificar random seed
3. Verificar ordenação de eventos simultâneos

### **Problema: Arquivos XML não gerados**
1. Verificar comando da simulação
2. Verificar permissões de escrita
3. Verificar logs de erro

### **Problema: Parse XML falha**
1. Verificar formato do XML
2. Verificar encoding
3. Verificar estrutura de tags

Este guia permite validar se sua simulação de referência é determinística antes de comparar com o simulador HTC!