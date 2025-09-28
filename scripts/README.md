# Traffic Analysis System Documentation

## Visão Geral

Este sistema oferece análise abrangente de dados de fluxo de veículos para o projeto Hyperbolic Time Chamber, permitindo gerar insights sobre padrões de tráfego urbano, identificar gargalos e avaliar a eficiência da rede viária.

## 🎯 Funcionalidades Principais

### Análises Disponíveis
- **Heatmaps de tráfego** por horário e localização
- **Gráficos densidade vs. velocidade** para análise de fluxo
- **Análise de gargalos** na rede viária
- **Padrões de mobilidade urbana** temporal
- **Eficiência de rotas** calculadas pelo sistema
- **Indicadores de performance** da rede de transporte

### Fontes de Dados Suportadas
- **Cassandra**: Dados em tempo real do sistema de persistência
- **CSV**: Arquivos de dados exportados
- **JSON**: Dados estruturados em formato JSON

### Formatos de Saída
- **Relatórios HTML** interativos com visualizações
- **Relatórios PDF** para apresentação
- **Arquivos JSON** para integração com outros sistemas
- **Visualizações interativas** em Plotly/Bokeh
- **Dashboards** executivos

## 🚀 Início Rápido

### 1. Instalação das Dependências

```bash
# No diretório do projeto
cd scripts/
pip install -r requirements.txt
```

### 2. Execução via Script Principal

```bash
# Análise básica usando Cassandra
python run_analysis.py

# Análise com parâmetros específicos
python run_analysis.py --source cassandra --simulation-id sim_001 --limit 10000

# Análise usando arquivos CSV
python run_analysis.py --source csv --data-path ../data/

# Pular visualizações (mais rápido)
python run_analysis.py --skip-viz

# Modo verboso
python run_analysis.py --verbose
```

### 3. Análise Interativa (Jupyter)

```bash
# Iniciar Jupyter
jupyter notebook Traffic_Analysis_Interactive.ipynb
```

## 📊 Estrutura do Sistema

```
scripts/
├── config.py                 # Configurações centralizadas
├── run_analysis.py           # Script principal
├── Traffic_Analysis_Interactive.ipynb  # Notebook interativo
├── requirements.txt          # Dependências Python
│
├── data_sources/            # Conectores de dados
│   ├── __init__.py
│   ├── cassandra_source.py  # Conector Cassandra
│   └── file_sources.py      # Conectores CSV/JSON
│
├── analysis/                # Algoritmos de análise
│   ├── __init__.py
│   └── traffic_analyzer.py  # Motor de análise principal
│
├── visualization/           # Geração de visualizações
│   ├── __init__.py
│   └── traffic_viz.py       # Criador de visualizações
│
└── reports/                 # Geração de relatórios
    ├── __init__.py
    └── report_generator.py   # Gerador de relatórios
```

## 🔧 Configuração

### Configuração do Cassandra

Edite `config.py` para ajustar a conexão:

```python
CASSANDRA_CONFIG = {
    'hosts': ['127.0.0.1'],
    'port': 9042,
    'keyspace': 'htc_keyspace',
    # ... outras configurações
}
```

### Configuração de Caminhos

```python
# Diretórios de entrada
CSV_DATA_PATH = Path("../data/csv")
JSON_DATA_PATH = Path("../data/json")

# Diretórios de saída
OUTPUT_PATH = Path("../output")
REPORTS_PATH = Path("../reports")
```

## 📈 Uso Programático

### Exemplo Básico

```python
from data_sources.cassandra_source import CassandraDataSource
from analysis.traffic_analyzer import TrafficAnalyzer
from visualization.traffic_viz import TrafficVisualizer

# Conectar aos dados
data_source = CassandraDataSource()
data_source.connect()

# Carregar dados
data = data_source.get_vehicle_flow_data(limit=5000)

# Executar análise
analyzer = TrafficAnalyzer(data)
results = analyzer.generate_comprehensive_report()

# Criar visualizações
visualizer = TrafficVisualizer(data)
heatmap = visualizer.create_traffic_heatmap()
heatmap.show()

# Limpeza
data_source.close()
```

### Análise Personalizada

```python
# Análise de gargalos com threshold personalizado
bottlenecks = analyzer.identify_bottlenecks(threshold_percentile=0.05)

# Métricas específicas por período
morning_data = data[data['timestamp'].dt.hour.between(7, 9)]
morning_analyzer = TrafficAnalyzer(morning_data)
morning_metrics = morning_analyzer.calculate_basic_metrics()
```

## 📊 Tipos de Análise

### 1. Métricas Básicas
- Total de veículos únicos
- Eventos capturados por tipo
- Estatísticas de velocidade
- Tempos de viagem
- Cobertura da rede

### 2. Padrões Temporais
- Distribuição horária de tráfego
- Padrões por dia da semana
- Identificação de horários de pico
- Variações sazonais

### 3. Análise Espacial
- Links mais utilizados
- Densidade por região
- Gargalos da rede
- Fluxos direcionais

### 4. Eficiência Operacional
- Velocidades médias por link
- Tempos de viagem por rota
- Utilização da capacidade
- Indicadores de congestionamento

## 🔥 Visualizações Disponíveis

### Heatmaps
- **Heatmap temporal**: Densidade por hora/dia
- **Heatmap espacial**: Uso por link/região
- **Heatmap de velocidade**: Performance da rede

### Gráficos Analíticos
- **Scatter plots**: Velocidade vs. densidade
- **Box plots**: Distribuições estatísticas
- **Time series**: Evolução temporal
- **Histogramas**: Distribuições de frequência

### Dashboards
- **Dashboard executivo**: Métricas principais
- **Dashboard operacional**: Monitoramento em tempo real
- **Dashboard comparativo**: Análise entre períodos

## 📋 Formatos de Relatório

### Relatório HTML
- Visualizações interativas integradas
- Sumário executivo
- Recomendações acionáveis
- Questões críticas identificadas

### Relatório Markdown
- Formato legível para documentação
- Compatível com Git/GitHub
- Tabelas e gráficos estáticos
- Fácil conversão para outros formatos

### Relatório JSON
- Dados estruturados para APIs
- Integração com outros sistemas
- Processamento automatizado
- Histórico de análises

## ⚙️ Opções de Linha de Comando

```bash
python run_analysis.py [OPTIONS]

Opções:
  --source {cassandra,csv,json}  Tipo de fonte de dados (padrão: cassandra)
  --data-path PATH               Caminho para arquivos (CSV/JSON)
  --simulation-id ID             ID da simulação (Cassandra)
  --limit N                      Máximo de registros (padrão: 10000)
  --output-dir PATH              Diretório de saída
  --skip-viz                     Pular visualizações
  --skip-reports                 Pular relatórios
  --verbose, -v                  Log detalhado
  --help                         Mostrar ajuda
```

## 🎯 Casos de Uso

### 1. Monitoramento Operacional
```bash
# Análise rápida da simulação atual
python run_analysis.py --limit 1000 --skip-viz
```

### 2. Análise Completa para Relatório
```bash
# Análise completa com todas as visualizações
python run_analysis.py --verbose
```

### 3. Comparação de Simulações
```python
# Via código Python
for sim_id in simulation_ids:
    analyzer = TrafficAnalyzer(get_data(sim_id))
    results[sim_id] = analyzer.generate_comprehensive_report()
```

### 4. Análise de Arquivo de Dados
```bash
# Análise de arquivo CSV exportado
python run_analysis.py --source csv --data-path ./exported_data/
```

## 🔍 Interpretação dos Resultados

### Métricas de Performance
- **Velocidade média > 50 km/h**: Rede eficiente
- **Velocidade média 30-50 km/h**: Congestionamento moderado  
- **Velocidade média < 30 km/h**: Congestionamento severo

### Scores de Bottleneck
- **0.0-0.3**: Fluxo normal
- **0.3-0.6**: Congestionamento leve
- **0.6-0.8**: Congestionamento moderado
- **0.8-1.0**: Congestionamento severo

### Indicadores de Eficiência
- **> 80%**: Rede altamente eficiente
- **60-80%**: Eficiência boa
- **40-60%**: Eficiência moderada
- **< 40%**: Problemas significativos

## 🚨 Solução de Problemas

### Erro de Conexão Cassandra
```bash
# Verificar se o Cassandra está rodando
docker ps | grep cassandra

# Verificar logs
docker logs cassandra_container
```

### Erro de Memória
```bash
# Reduzir limite de dados
python run_analysis.py --limit 1000

# Ou usar amostragem
python run_analysis.py --skip-viz
```

### Visualizações não Aparecem
```bash
# Instalar dependências de visualização
pip install plotly kaleido

# Verificar configuração do Jupyter
jupyter notebook --generate-config
```

## 📚 APIs e Extensões

### Criando Análises Personalizadas

```python
class CustomTrafficAnalyzer(TrafficAnalyzer):
    def analyze_custom_pattern(self):
        # Sua análise personalizada aqui
        pass
```

### Adicionando Novas Visualizações

```python
class CustomVisualizer(TrafficVisualizer):
    def create_custom_chart(self):
        # Sua visualização personalizada aqui
        pass
```

### Integrando Novas Fontes de Dados

```python
class CustomDataSource:
    def get_vehicle_flow_data(self):
        # Sua fonte de dados personalizada
        pass
```

## 🎉 Exemplos Avançados

### Análise Multi-Simulação
Ver `examples/multi_simulation_analysis.py`

### Análise Temporal Detalhada  
Ver `examples/temporal_analysis.py`

### Exportação Personalizada
Ver `examples/custom_export.py`

### Integração com ML
Ver `examples/machine_learning_integration.py`

---

## 📞 Suporte

Para dúvidas técnicas ou sugestões de melhorias, consulte:
- Documentação dos módulos individuais
- Notebook de exemplos interativos
- Issues no repositório do projeto

## 🔄 Atualizações

Este sistema é atualizado regularmente. Para obter a versão mais recente:
```bash
git pull origin main
pip install -r requirements.txt --upgrade
```