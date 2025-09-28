# Sistema de Comparação de Simuladores

Este sistema permite comparar os resultados do simulador **HTC (Hyperbolic Time Chamber)** com simuladores de referência (como MATSim ou SUMO) para validação e análise.

## 🎯 Objetivo

Validar a precisão do simulador HTC comparando seus resultados com simuladores estabelecidos que produzem eventos XML, permitindo:

- **Comparação estatística** de fluxos de tráfego
- **Análise de similaridade** entre padrões temporais
- **Visualizações comparativas** com gráficos e relatórios
- **Scoring automático** de similaridade

## 🏗️ Arquitetura

```
scripts/
├── compare_simulators.py          # Script principal de comparação
├── comparison/
│   ├── __init__.py               # Módulo de comparação
│   ├── reference_parser.py       # Parser para XML de referência
│   └── simulator_comparator.py   # Engine de comparação
├── output/
│   └── comparison/               # Resultados da comparação
│       ├── comparison_results.json
│       ├── comparison_report.md
│       └── visualizations/       # Gráficos e charts
run_comparison.sh                 # Script de execução facilitado
```

## 🚀 Como Usar

### 1. Preparação

Certifique-se de ter o ambiente configurado:
```bash
# Ativar ambiente virtual
source venv/bin/activate

# Verificar dependências
pip install -r requirements.txt
```

### 2. Executar Comparação

#### Usando dados do Cassandra:
```bash
./run_comparison.sh --cassandra reference_events.xml
```

#### Usando arquivo CSV:
```bash
./run_comparison.sh --csv data/htc_output.csv reference_events.xml
```

#### Com opções avançadas:
```bash
# Limite maior de dados do Cassandra
./run_comparison.sh --cassandra --limit 5000 reference_events.xml

# Especificar diretório de saída
./run_comparison.sh --cassandra --output /path/to/output reference_events.xml
```

### 3. Criar Arquivo de Exemplo

Para testar o sistema:
```bash
./run_comparison.sh --sample
```

Isso criará `sample_reference_events.xml` no diretório de saída.

## 📊 Métricas de Comparação

O sistema calcula múltiplas métricas de similaridade:

### 1. **Padrões Temporais**
- Distribuição de veículos ao longo do tempo
- Picos de tráfego
- Correlação temporal

### 2. **Uso de Links**
- Frequência de uso por link
- Distribuição espacial do tráfego
- Rotas mais utilizadas

### 3. **Tipos de Eventos**
- Distribuição de diferentes tipos de eventos
- Padrões de comportamento dos veículos

### 4. **Score de Similaridade**
- **Cosine Similarity**: Similaridade vetorial
- **Jensen-Shannon Divergence**: Diferença entre distribuições
- **Correlation**: Correlação estatística
- **Score Geral**: Média ponderada das métricas

## 📈 Interpretação dos Resultados

### Score de Similaridade:
- **0.9 - 1.0**: Excelente similaridade
- **0.8 - 0.9**: Boa similaridade
- **0.7 - 0.8**: Similaridade moderada
- **0.6 - 0.7**: Similaridade baixa
- **< 0.6**: Diferenças significativas

### Visualizações Geradas:

1. **Radar Chart**: Comparação multi-dimensional
2. **Bar Charts**: Distribuições por categoria
3. **Time Series**: Padrões temporais
4. **Correlation Plots**: Análise de correlação

## 📁 Outputs

Os resultados são salvos em `scripts/output/comparison/`:

### Arquivos Principais:
- `comparison_results.json`: Dados detalhados da comparação
- `comparison_report.md`: Relatório resumido em Markdown
- `visualizations/`: Gráficos e charts

### Estrutura do JSON:
```json
{
  "metadata": {
    "timestamp": "2024-01-15T10:30:00",
    "htc_records": 1000,
    "ref_records": 856
  },
  "temporal_analysis": {
    "cosine_similarity": 0.94,
    "js_divergence": 0.12,
    "correlation": 0.89
  },
  "link_usage": {
    "cosine_similarity": 0.87,
    "js_divergence": 0.18,
    "correlation": 0.82
  },
  "overall_similarity": {
    "score": 0.88,
    "classification": "Boa similaridade"
  }
}
```

## 🔧 Formato dos Dados de Entrada

### HTC Data (CSV/Cassandra):
```csv
car_id,link_id,timestamp,direction,lane
vehicle_1,2105,7.0,forward,1
vehicle_2,3345,8.0,forward,2
```

### Reference Data (XML):
```xml
<?xml version="1.0" encoding="utf-8"?>
<events>
<event time="7" type="entered link" person="vehicle_1" link="2105" vehicle="vehicle_1" />
<event time="8" type="left link" person="vehicle_1" link="2105" vehicle="vehicle_1" />
</events>
```

## 🛠️ Configurações Avançadas

### Modificar Pesos das Métricas:
Edite `simulator_comparator.py`:
```python
weights = {
    'temporal': 0.4,    # Padrões temporais
    'spatial': 0.3,     # Uso de links
    'behavioral': 0.3   # Tipos de eventos
}
```

### Adicionar Novas Métricas:
1. Implemente no `SimulatorComparator`
2. Adicione aos pesos
3. Inclua nas visualizações

## 🔍 Troubleshooting

### Problemas Comuns:

**Erro de conexão Cassandra:**
```bash
# Verificar se Cassandra está rodando
sudo systemctl status cassandra
# ou
docker ps | grep cassandra
```

**Arquivo XML inválido:**
- Verificar encoding UTF-8
- Validar estrutura XML
- Conferir elementos obrigatórios (`time`, `person`, `link`)

**Dependências ausentes:**
```bash
pip install pandas numpy matplotlib seaborn plotly scipy
```

**Dados incompatíveis:**
- Verificar se colunas necessárias existem
- Conferir tipos de dados (timestamp numérico)
- Validar IDs de veículos/links

## 📚 Exemplos de Uso

### Análise Rápida:
```bash
# Comparação com 100 registros do Cassandra
./run_comparison.sh --cassandra --limit 100 sample.xml
```

### Análise Completa:
```bash
# Comparação com arquivo CSV completo
./run_comparison.sh --csv data/simulation_results.csv reference_full.xml --output results/full_comparison
```

### Debugging:
```bash
# Criar dados de teste
./run_comparison.sh --sample

# Usar dados de teste
./run_comparison.sh --csv test_data.csv sample_reference_events.xml
```

## 🔄 Integração com Pipeline

Para automatizar comparações:

```bash
#!/bin/bash
# Pipeline de validação
./run_traffic_analysis.sh          # Gerar dados HTC
./run_comparison.sh --cassandra reference.xml  # Comparar
# Análise dos resultados...
```

## 📞 Suporte

Para problemas ou melhorias:
1. Verificar logs em `scripts/output/`
2. Testar com dados de exemplo
3. Validar configurações de ambiente
4. Consultar documentação das dependências

---

**Nota**: Este sistema foi projetado para ser flexível e extensível. Novas métricas de comparação e formatos de dados podem ser facilmente adicionados conforme necessário.