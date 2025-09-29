# Sistema de Análise de Tráfego HTC

Sistema completo para análise de simulações de tráfego do Hyperbolic Time Chamber (HTC), incluindo comparação com simuladores de referência e análise de reprodutibilidade.

## 🎯 Funcionalidades

### 1. Comparação de Simuladores
- **HTC vs Referência**: Compara resultados entre HTC e simuladores de referência
- **Múltiplas fontes**: Suporte a Cassandra, CSV e XML
- **47 métricas**: Análise abrangente de métricas de tráfego
- **Visualizações**: Gráficos e relatórios automáticos
- **PDFs acadêmicos**: Relatórios prontos para publicação

### 2. Análise de Reprodutibilidade
- **Múltiplas execuções**: Comparação estatística entre simulações
- **Testes estatísticos**: Kolmogorov-Smirnov, Mann-Whitney U, ANOVA
- **Scores de similaridade**: Quantificação da consistência
- **Análise temporal**: Padrões de comportamento ao longo do tempo
- **Validação científica**: Suporte para publicações acadêmicas

### 3. Métricas Gerais de Tráfego
- **Estatísticas básicas**: Contagens, velocidades, tempos
- **Métricas de distância**: Quilometragem, distribuições espaciais
- **Análise temporal**: Padrões de movimento, duração de viagens
- **Densidade de tráfego**: Ocupação de vias, congestionamentos
- **Performance**: Throughput, eficiência, delays
- **Qualidade de serviço**: Experiência do usuário, satisfação
- **Segurança**: Indicadores de risco, eventos críticos

## 🚀 Como Usar

### Método 1: Script de Ajuda (Recomendado)

```bash
# Mostrar todas as opções disponíveis
./scripts/analysis_helper.sh help

# Verificar status do sistema
./scripts/analysis_helper.sh status

# Instalar dependências
./scripts/analysis_helper.sh install-deps
```

#### Comparação Tradicional
```bash
# HTC (Cassandra) vs Referência (XML)
./scripts/analysis_helper.sh compare-cassandra reference_events.xml

# HTC (CSV) vs Referência (XML)
./scripts/analysis_helper.sh compare-csv htc_data.csv reference_events.xml
```

#### Análise de Reprodutibilidade
```bash
# Múltiplas simulações via Cassandra
./scripts/analysis_helper.sh repro-cassandra sim_001 sim_002 sim_003

# Múltiplos arquivos CSV
./scripts/analysis_helper.sh repro-csv run1.csv run2.csv run3.csv

# Múltiplos arquivos XML
./scripts/analysis_helper.sh repro-xml events1.xml events2.xml
```

#### Análises Independentes
```bash
# Métricas gerais via Cassandra
./scripts/analysis_helper.sh metrics-cassandra 50000

# Métricas gerais via CSV
./scripts/analysis_helper.sh metrics-csv data.csv
```

### Método 2: Scripts Diretos

#### Comparação de Simuladores
```bash
cd scripts

# Tradicional: HTC vs Referência
python compare_simulators.py reference_events.xml --htc-cassandra
python compare_simulators.py reference_events.xml --htc-csv htc_data.csv

# Criar arquivo de exemplo
python compare_simulators.py --create-sample
```

#### Análise de Reprodutibilidade
```bash
cd scripts

# Via Cassandra
python reproducibility_analysis.py --cassandra-sims sim_001 sim_002 sim_003

# Via arquivos CSV
python reproducibility_analysis.py --csv-files run1.csv run2.csv run3.csv

# Via arquivos XML
python reproducibility_analysis.py --xml-files events1.xml events2.xml

# Criar configuração de exemplo
python reproducibility_analysis.py --create-config
```

## 📊 Tipos de Análise

### 1. Métricas Básicas (7 métricas)
- Total de registros
- Veículos únicos
- Tempo de simulação
- Registros por veículo
- Distribuição temporal
- Primeira/última atividade
- Taxa de atividade

### 2. Métricas de Distância (8 métricas)
- Total de quilômetros
- Distância média por veículo
- Distribuição de distâncias
- Máxima distância percorrida
- Densidade espacial
- Padrões de movimento
- Eficiência de rotas
- Cobertura geográfica

### 3. Métricas de Velocidade (7 métricas)
- Velocidade média geral
- Velocidade por veículo
- Distribuição de velocidades
- Velocidade máxima/mínima
- Variabilidade de velocidade
- Padrões de aceleração
- Eficiência energética

### 4. Métricas Temporais (8 métricas)
- Duração média de viagens
- Tempos de parada
- Padrões de horário de pico
- Distribuição temporal
- Sincronização de movimentos
- Periodicidade
- Consistência temporal
- Eficiência de cronograma

### 5. Métricas de Densidade (6 métricas)
- Densidade média de tráfego
- Picos de congestionamento
- Distribuição espacial
- Ocupação de vias
- Fluidez do tráfego
- Pontos de gargalo

### 6. Métricas de Performance (6 métricas)
- Throughput do sistema
- Eficiência global
- Tempo de resposta
- Capacidade utilizada
- Delays e atrasos
- Otimização de recursos

### 7. Métricas de Qualidade (5 métricas)
- Experiência do usuário
- Satisfação de viagem
- Confiabilidade
- Previsibilidade
- Qualidade de serviço

## 📁 Estrutura de Saída

```
output/
├── comparison/                     # Comparação tradicional
│   ├── comparison_report.json
│   ├── similarity_analysis.png
│   ├── general_metrics/           # Métricas HTC
│   │   ├── htc_metrics.json
│   │   ├── basic_metrics.png
│   │   ├── distance_metrics.png
│   │   └── ...
│   ├── reference_metrics/         # Métricas Referência
│   │   ├── reference_metrics.json
│   │   ├── basic_metrics.png
│   │   └── ...
│   └── academic_reports/          # PDFs para publicação
│       └── simulator_comparison_academic.pdf
├── reproducibility/               # Análise de reprodutibilidade
│   ├── reproducibility_report.json
│   ├── reproducibility_dashboard.png
│   ├── similarity_scores.png
│   ├── temporal_reproducibility.png
│   └── basic_metrics_comparison.png
└── standalone_metrics/            # Análises independentes
    ├── cassandra_metrics.json
    ├── csv_metrics.json
    └── ...
```

## 🔧 Configuração

### Dependências Python
```bash
pip install pandas matplotlib seaborn numpy scipy scikit-learn cassandra-driver plotly kaleido
```

### Cassandra (Docker)
```bash
# Iniciar cluster Cassandra
docker-compose up -d cassandra

# Verificar conectividade
docker exec -it cassandra cqlsh -e "DESCRIBE KEYSPACES;"
```

### Variáveis de Ambiente
```bash
# Configurações opcionais
export HTC_CASSANDRA_HOST=localhost
export HTC_CASSANDRA_PORT=9042
export HTC_OUTPUT_PATH=./output
```

## 📈 Interpretação dos Resultados

### Scores de Similaridade
- **≥ 0.8**: Alta similaridade (excelente)
- **≥ 0.6**: Similaridade moderada (boa)
- **< 0.6**: Baixa similaridade (investigar)

### Coeficiente de Variação (CV)
- **< 0.05**: Boa reprodutibilidade
- **< 0.1**: Reprodutibilidade moderada
- **≥ 0.1**: Baixa reprodutibilidade (revisar)

### Testes Estatísticos
- **p-value > 0.05**: Simulações estatisticamente similares
- **p-value ≤ 0.05**: Diferenças significativas detectadas

## 🔬 Para Publicações Científicas

### Documentação de Métricas
Ver `docs/metrics_documentation.md` para:
- Definições matemáticas completas
- Fórmulas de cálculo
- Referências bibliográficas
- Guidelines para citação

### PDFs Acadêmicos
O sistema gera automaticamente:
- Relatórios formatados para artigos
- Gráficos em alta resolução
- Tabelas de resultados
- Análises estatísticas

### Reprodutibilidade
- Validação de determinismo
- Análises de consistência
- Testes de confiabilidade
- Métricas de qualidade científica

## 🐛 Troubleshooting

### Erro de Conexão Cassandra
```bash
# Verificar se o Cassandra está rodando
docker ps | grep cassandra

# Reiniciar se necessário
docker-compose restart cassandra
```

### Dependências Python
```bash
# Verificar status
./scripts/analysis_helper.sh status

# Reinstalar se necessário
./scripts/analysis_helper.sh install-deps
```

### Logs Detalhados
```bash
# Executar com logs verbose
python compare_simulators.py --verbose reference.xml --htc-cassandra
```

## 📝 Exemplos Práticos

### Cenário 1: Validação de Nova Versão
```bash
# Comparar versão atual vs anterior
./scripts/analysis_helper.sh repro-cassandra sim_v1.0 sim_v1.1

# Análise detalhada se houver diferenças
./scripts/analysis_helper.sh metrics-cassandra
```

### Cenário 2: Publicação Científica
```bash
# Comparação completa para artigo
./scripts/analysis_helper.sh compare-cassandra reference_matsim.xml

# Verificar reprodutibilidade (3 execuções)
./scripts/analysis_helper.sh repro-cassandra sim_001 sim_002 sim_003
```

### Cenário 3: Análise de Performance
```bash
# Métricas de uma simulação específica
./scripts/analysis_helper.sh metrics-cassandra 100000

# Comparação de diferentes configurações
./scripts/analysis_helper.sh repro-csv config1.csv config2.csv config3.csv
```

## 🤝 Contribuindo

Para adicionar novas métricas ou funcionalidades:

1. **Métricas**: Editar `analysis/general_metrics.py`
2. **Comparações**: Editar `comparison/simulator_comparator.py`
3. **Reprodutibilidade**: Editar `analysis/reproducibility_analyzer.py`
4. **Documentação**: Atualizar `docs/metrics_documentation.md`

## 📞 Suporte

Para dúvidas ou problemas:
1. Verificar logs em `logs/application.log`
2. Executar `./scripts/analysis_helper.sh status`
3. Consultar documentação em `docs/`
4. Revisar exemplos em `scripts/output/`

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