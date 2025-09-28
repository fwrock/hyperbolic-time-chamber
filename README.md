# 🚀 Hyperbolic Time Chamber - Traffic Analysis & Simulator Comparison

![HTC Banner](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**Sistema completo de análise de tráfego e validação de simuladores para pesquisa em mobilidade urbana**

Um simulador multi-agente baseado em atores usando Scala e Apache Pekko, com sistema avançado de análise e comparação de resultados.

---

## 🎯 **Funcionalidades Principais**

### 🏗️ **Simulador HTC (Core)**
- ✅ **Simulação baseada em eventos discretos** com atores Scala/Pekko
- ✅ **Gestão precisa do tempo** de simulação
- ✅ **Carregamento de dados** flexível (JSON, CSV)
- ✅ **Coordenação de eventos** distribuída
- ✅ **Sistema de relatórios** automatizado
- ✅ **Snapshots** do estado da simulação
- ✅ **Simulação distribuída** multi-nós

### 📊 **Sistema de Análise de Tráfego**
- ✅ **Múltiplas fontes de dados** (Cassandra, CSV)
- ✅ **Métricas avançadas** de fluxo de tráfego
- ✅ **Visualizações interativas** com Plotly
- ✅ **Relatórios automatizados** (JSON + Markdown)
- ✅ **Dashboard web** integrado

### 🔬 **Sistema de Comparação de Simuladores** 🆕
- ✅ **Comparação HTC vs Simuladores de Referência** (MATSim/SUMO)
- ✅ **Parsing de eventos XML** automático
- ✅ **Análise estatística de similaridade** multi-métrica
- ✅ **Score automático de validação** (0.0 - 1.0)
- ✅ **Visualizações comparativas** (radar charts, bar charts)
- ✅ **Relatórios detalhados** de validação

### 🗄️ **Sistema de Gerenciamento de Banco** 🆕
- ✅ **Scripts de gerenciamento Cassandra** automatizados
- ✅ **Limpeza de dados** entre simulações
- ✅ **Verificação de estado** do banco
- ✅ **Workflow integrado** para simulações limpas
- ✅ **Inicialização automática** de schema

## 🏗️ **Arquitetura do Sistema**

```
hyperbolic-time-chamber/
├── 🎯 Core Simulator (Scala/Pekko)
│   ├── src/main/scala/           # Código principal do simulador
│   ├── src/main/resources/       # Configurações
│   └── build.sbt                 # Build configuration
│
├── 📊 Sistema de Análise Python
│   ├── scripts/
│   │   ├── run_traffic_analysis.py    # Análise principal
│   │   ├── data_sources/              # Conectores de dados
│   │   │   ├── cassandra_source.py    # Integração Cassandra
│   │   │   └── file_sources.py        # Leitura CSV/JSON
│   │   ├── analytics/                 # Engine de métricas
│   │   │   └── traffic_analyzer.py    # Cálculos avançados
│   │   └── visualization/             # Sistema de visualização
│   │       └── traffic_visualizer.py  # Gráficos e dashboard
│   │
│   ├── 🔬 Sistema de Comparação
│   │   ├── compare_simulators.py      # Script principal
│   │   └── comparison/                # Framework de comparação
│   │       ├── reference_parser.py    # Parser XML (MATSim/SUMO)
│   │       └── simulator_comparator.py # Engine de comparação
│   │
│   └── output/                        # Resultados e relatórios
│
├── 🚀 Scripts de Execução
│   ├── run_traffic_analysis.sh        # Executor de análise
│   └── run_comparison.sh               # Executor de comparação
│
└── 📚 Documentação
    ├── docs/COMPARISON_GUIDE.md        # Guia de comparação
    └── docs/                          # Documentação técnica
```

---

## 🚀 **Como Usar**

### 1. **Análise de Tráfego**

```bash
# Análise usando dados do Cassandra
./run_traffic_analysis.sh

# Análise usando arquivo CSV
./run_traffic_analysis.sh --source csv --file data/traffic_data.csv

# Especificar diretório de saída
./run_traffic_analysis.sh --output /custom/path
```

### 2. **Comparação de Simuladores** 🆕

```bash
# Comparar HTC (Cassandra) vs Simulador de Referência
./run_comparison.sh --cassandra reference_events.xml

# Comparar HTC (CSV) vs Simulador de Referência  
./run_comparison.sh --csv data/htc_output.csv reference_events.xml

# Criar dados de exemplo para teste
./run_comparison.sh --sample

# Usar limite maior de dados
./run_comparison.sh --cassandra --limit 5000 reference_events.xml
```

### 3. **Ver Ajuda**

```bash
# Help da análise
./run_traffic_analysis.sh --help

# Help da comparação
./run_comparison.sh --help
```

---

## 📊 **Métricas de Comparação**

### 🔍 **Análises Realizadas:**

1. **⏰ Padrões Temporais**
   - Correlação temporal entre simuladores
   - Distribuição de eventos ao longo do tempo
   - Detecção de picos de tráfego

2. **🛣️ Uso de Links/Rotas**
   - Frequência de utilização por link
   - Distribuição espacial do tráfego
   - Similaridade de rotas utilizadas

3. **🚗 Comportamento dos Veículos**
   - Tipos de eventos gerados
   - Padrões de movimento
   - Distribuição de ações dos veículos

4. **📈 Métricas Estatísticas**
   - **Cosine Similarity**: Similaridade vetorial
   - **Jensen-Shannon Divergence**: Diferença entre distribuições
   - **Correlação de Pearson**: Correlação linear
   - **Score Geral**: Média ponderada das métricas

### 🎯 **Interpretação do Score de Similaridade:**

| Score | Classificação | Interpretação | Status |
|-------|---------------|---------------|--------|
| 0.9-1.0 | Excelente | Simuladores muito similares | ✅ |
| 0.8-0.9 | Boa | Boa correspondência | ✅ |
| 0.7-0.8 | Moderada | Similaridade aceitável | ⚠️ |
| 0.6-0.7 | Baixa | Diferenças notáveis | ⚠️ |
| <0.6 | Pouco Similar | Diferenças significativas | ❌ |

---

## 📈 **Outputs Gerados**

### **Análise de Tráfego:**
```
scripts/output/
├── 📋 traffic_analysis_report.json    # Dados completos
├── 📝 traffic_summary.md              # Relatório resumido
├── 📊 traffic_dashboard.html           # Dashboard interativo
└── 📈 visualizations/                  # Gráficos
    ├── traffic_flow_timeline.png
    ├── vehicle_distribution.png
    └── link_usage_heatmap.png
```

### **Comparação de Simuladores:**
```
scripts/output/comparison/
├── 📋 simulator_comparison_report.json # Dados detalhados
├── 📝 comparison_summary.md            # Relatório de similaridade
├── 📊 similarity_radar.png             # Radar chart comparativo
└── 📈 data_comparison.png              # Gráficos distribuição
```

### **Exemplo de Relatório JSON:**
```json
{
  "overall_similarity": {
    "score": 0.869,
    "classification": "Boa similaridade"
  },
  "temporal_analysis": {
    "correlation": 0.94,
    "js_divergence": 0.12
  },
  "link_analysis": {
    "common_links": 15,
    "usage_similarity": 0.87
  }
}
```

---

## 🗄️ **Gerenciamento do Cassandra**

### **Scripts de Controle:**

```bash
# Subir Cassandra
./manage_cassandra.sh start

# Limpar dados antigos (recomendado antes de cada simulação)
./manage_cassandra.sh clean

# Verificar estado atual dos dados
./check_cassandra_data.sh

# Reset completo (limpar tudo)
./manage_cassandra.sh reset

# Workflow automatizado
./simulation_workflow.sh clean
```

### **Workflow de Simulação Limpa:**

```bash
# 1. Limpar dados antigos
./manage_cassandra.sh clean

# 2. Verificar se está limpo
./check_cassandra_data.sh

# 3. Executar simulação
./build-and-run.sh

# 4. Analisar resultados
./run_traffic_analysis.sh
```

📖 **[Ver Guia Completo de Gerenciamento do Cassandra](docs/CASSANDRA_MANAGEMENT_GUIDE.md)**

---

## 🛠️ **Setup e Instalação**

### **1. Pré-requisitos**
```bash
# Java 11+ (para o simulador Scala)
java -version

# Python 3.8+ (para análise e comparação)
python --version

# SBT (para build do simulador)
sbt --version
```

### **2. Setup do Ambiente Python**
```bash
# Criar ambiente virtual
python -m venv scripts/venv

# Ativar ambiente
source scripts/venv/bin/activate  # Linux/Mac
# scripts\venv\Scripts\activate   # Windows

# Instalar dependências
pip install pandas numpy matplotlib seaborn plotly scipy cassandra-driver dash
```

### **3. Configurar Cassandra (Opcional)**
```bash
# Via Docker (recomendado)
docker-compose up cassandra

# Via sistema
sudo systemctl start cassandra
```

### **4. Testar Sistema**
```bash
# Teste básico de comparação
./run_comparison.sh --sample

# Executar comparação de exemplo
./run_comparison.sh --csv scripts/output/sample_htc_data.csv scripts/output/sample_reference_events.xml
```

---

## 🔧 **Configuração do Simulador HTC**

### **Arquivo de Configuração (JSON):**
```json
{
  "simulation": {
    "name": "HTC-Traffic-Simulation",
    "start": "2025-01-27T00:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 3600,
    "actorsDataSources": [
      {
        "id": "htc:vehicles:city:1",
        "classType": "com.htc.traffic.VehicleActor",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": { "path": "data/vehicles.json" }
        }
      }
    ]
  }
}
```

### **Compilar e Executar:**
```bash
# Compilar simulador
sbt compile

# Executar simulação
sbt run

# Build JAR
sbt assembly
```

---

## 🔍 **Casos de Uso**

### **🎓 Pesquisa Acadêmica**
```bash
# Validar novo modelo de simulação
./run_comparison.sh --cassandra matsim_baseline.xml --output validation/

# Comparar múltiplos cenários
for scenario in scenario_*.xml; do
    ./run_comparison.sh --cassandra "$scenario" --output "results_$(basename "$scenario" .xml)/"
done
```

### **🏙️ Planejamento Urbano**
```bash
# Analisar impacto de mudanças viárias
./run_traffic_analysis.sh --source csv --file before_changes.csv
./run_traffic_analysis.sh --source csv --file after_changes.csv
```

### **🚦 Otimização de Tráfego**
```bash
# Comparar estratégias de semáforos
./run_comparison.sh --csv strategy_a.csv sumo_baseline.xml
./run_comparison.sh --csv strategy_b.csv sumo_baseline.xml
```

---

## 🐛 **Troubleshooting**

### **Problemas Comuns:**

**❌ Cassandra não conecta:**
```bash
# Verificar status
sudo systemctl status cassandra
docker-compose logs cassandra

# Testar conexão
cqlsh localhost 9042
```

**❌ Dependências Python ausentes:**
```bash
# Reinstalar dependências
pip install --upgrade -r requirements.txt
source scripts/venv/bin/activate
```

**❌ XML mal formado:**
- Verificar encoding UTF-8
- Validar estrutura XML
- Conferir elementos obrigatórios (`time`, `person`, `link`)

**❌ Problemas de build Scala:**
```bash
# Limpar cache
sbt clean

# Verificar versão Java
java -version  # Deve ser 11+
```

---

## 📚 **Documentação**

- 📊 [**Guia de Análise de Tráfego**](docs/TRAFFIC_ANALYSIS_GUIDE.md)
- 🔬 [**Guia de Comparação de Simuladores**](docs/COMPARISON_GUIDE.md)
- 🏗️ [**Arquitetura do Sistema**](docs/ARCHITECTURE.md)
- 🚀 [**Tutorial Completo**](docs/TUTORIAL.md)

---

## 🤝 **Contribuição**

1. Fork o projeto
2. Criar branch para feature (`git checkout -b feature/nova-funcionalidade`)
3. Commit as mudanças (`git commit -am 'Adiciona nova funcionalidade'`)
4. Push para branch (`git push origin feature/nova-funcionalidade`)
5. Abrir Pull Request

---

## 🎓 **Uso Acadêmico**

Este sistema foi desenvolvido para pesquisa em **mobilidade urbana** e **simulação de tráfego**.

**Para citação:**
```bibtex
@software{hyperbolic_time_chamber,
  title={Hyperbolic Time Chamber: Traffic Simulation and Validation Framework},
  author={[Seu Nome]},
  year={2024},
  url={https://github.com/[seu-usuario]/hyperbolic-time-chamber},
  note={Sistema de simulação multi-agente com validação estatística}
}
```

---

## 📄 **Licença**

Este projeto está sob a licença MIT. Veja [LICENSE](LICENSE) para detalhes.

---

## 🆕 **Novidades v2.0 - Sistema de Comparação**

✨ **Principais Adições:**
- 🔬 **Framework completo de comparação** entre simuladores
- 📊 **Análise estatística avançada** com múltiplas métricas
- 🎯 **Scoring automático** de similaridade (0.0-1.0)
- 📈 **Visualizações comparativas** (radar charts, distribuições)
- 📋 **Relatórios detalhados** para validação acadêmica
- 🔧 **Scripts executáveis** com interface amigável
- 📚 **Documentação completa** com exemplos

🚀 **Casos de Uso:**
- **Validação de modelos**: Comparar HTC com MATSim/SUMO
- **Calibração de parâmetros**: Otimizar similaridade com referências
- **Pesquisa acadêmica**: Gerar relatórios para publicações
- **Desenvolvimento**: Testar impacto de mudanças no modelo

---

**🎉 Sistema pronto para simulação, análise e validação de tráfego urbano!**

*Inspirado na lendária "Câmara do Tempo" de Dragon Ball, onde 1 dia = 1 ano de treinamento, nosso simulador acelera o desenvolvimento e validação de soluções de mobilidade urbana!* ⚡