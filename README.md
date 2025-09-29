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
│   │       ├── traffic_visualizer.py  # Gráficos e dashboard
│   │       └── academic_viz.py        # PDFs acadêmicos 🆕
│   │
│   ├── 🔬 Sistema de Comparação
│   │   ├── compare_simulators.py      # Script principal
│   │   └── comparison/                # Framework de comparação
│   │       ├── reference_parser.py    # Parser XML (MATSim/SUMO)
│   │       ├── simulator_comparator.py # Engine de comparação
│   │       ├── id_mapper.py           # Mapeamento de IDs
│   │       └── individual_comparator.py # Comparação individual
│   │
│   └── output/                        # Resultados e relatórios
│       └── academic_reports/          # PDFs acadêmicos 🆕
│
├── 🚀 Scripts de Execução
│   ├── run_traffic_analysis.sh        # Executor de análise
│   ├── run_comparison.sh              # Executor de comparação
│   ├── generate_academic_pdfs.sh      # Gerador de PDFs 🆕
│   └── run_individual_comparison.py   # Comparação individual 🆕
│
└── 📚 Documentação
    ├── docs/COMPARISON_GUIDE.md        # Guia de comparação
    ├── docs/CASSANDRA_MANAGEMENT_GUIDE.md # Guia do Cassandra
    └── docs/                          # Documentação técnica
```

---

## 🚀 **Como Usar**

### **📋 Interface de Gerenciamento Unificada (Recomendada)**
```bash
# Gerenciador completo com interface de menu
./htc-manager.sh

# Funcionalidades do gerenciador:
# ✅ Inicialização otimizada automática
# ✅ Diagnósticos completos do sistema  
# ✅ Análise de tráfego e comparação
# ✅ Geração de PDFs acadêmicos
# ✅ Gerenciamento do Cassandra
# ✅ Monitor de performance
# ✅ Limpeza e manutenção
```

### **⚡ Inicialização Rápida**
```bash
# Auto-detecta recursos e aplica melhor configuração
./start-optimized.sh

# Com diagnóstico incluído
./diagnose.sh && ./start-optimized.sh
```

### **1. Análise de Tráfego**

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

### 3. **Gerar PDFs Acadêmicos** 🆕

```bash
# Gerar PDFs para artigos científicos (alta qualidade 300 DPI)
./generate_academic_pdfs.sh

# Opções específicas:
./generate_academic_pdfs.sh traffic      # PDF de análise de tráfego
./generate_academic_pdfs.sh comparison   # PDF de comparação
./generate_academic_pdfs.sh individual   # PDF de comparação individual
./generate_academic_pdfs.sh all          # Todos os PDFs

# Listar PDFs existentes
./generate_academic_pdfs.sh list
```

### 4. **Comparação Individual de Veículos** 🆕

```bash
# Comparação individual com dados de exemplo
./run_individual_comparison.py --create-sample

# Comparação usando Cassandra
./run_individual_comparison.py --htc-cassandra reference_events.xml

# Comparação usando CSV
./run_individual_comparison.py --htc-csv data.csv reference_events.xml
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
├── 📈 visualizations/                  # Gráficos
│   ├── traffic_flow_timeline.png
│   ├── vehicle_distribution.png
│   └── link_usage_heatmap.png
└── 📄 academic_reports/               # PDFs acadêmicos 🆕
    └── traffic_analysis_academic.pdf
```

### **Comparação de Simuladores:**
```
scripts/output/comparison/
├── 📋 simulator_comparison_report.json # Dados detalhados
├── 📝 comparison_summary.md            # Relatório de similaridade
├── 📊 similarity_radar.png             # Radar chart comparativo
├── 📈 data_comparison.png              # Gráficos distribuição
└── 📄 academic_reports/               # PDFs acadêmicos 🆕
    └── simulator_comparison_academic.pdf
```

### **Comparação Individual:**
```
scripts/output/individual/
├── 📋 individual_comparison_report.json # Comparação detalhada
├── 📊 vehicle_similarity_distribution.png # Distribuição de similaridade
├── 📈 link_similarity_distribution.png    # Links comparados
└── 📄 academic_reports/                   # PDFs acadêmicos 🆕
    └── individual_comparison_academic.pdf
```

## 📄 **PDFs Acadêmicos para Artigos** 🆕

### **Características dos PDFs:**
- ✅ **Alta qualidade**: 300 DPI para publicação
- ✅ **Fontes acadêmicas**: Times New Roman (compatível LaTeX)
- ✅ **Cores otimizadas**: Para impressão e escala de cinza
- ✅ **Formato padrão**: A4, layout acadêmico profissional
- ✅ **Multi-página**: Cada PDF contém múltiplas análises

### **Conteúdo dos PDFs:**

#### **📊 PDF de Análise de Tráfego:**
- Página 1: Fluxo temporal de tráfego
- Página 2: Distribuição de veículos por link
- Página 3: Heatmap de uso de links
- Página 4: Métricas de performance

#### **🔬 PDF de Comparação de Simuladores:**
- Página 1: Radar chart de similaridade
- Página 2: Comparação temporal
- Página 3: Comparação de links
- Página 4: Métricas estatísticas
- Página 5: Box plots de distribuições

#### **🎯 PDF de Comparação Individual:**
- Página 1: Similaridade de veículos
- Página 2: Jornadas comparadas
- Página 3: Estatísticas de mapeamento

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

📄 **[Ver Guia Completo de PDFs Acadêmicos](docs/ACADEMIC_PDF_GUIDE.md)** 🆕

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

### **3. Configurar e Executar Sistema (Docker)**

#### **🚀 Inicialização Otimizada (Recomendada)**
```bash
# Script inteligente que detecta recursos do sistema
./start-optimized.sh

# Com limpeza de volumes antigos
./start-optimized.sh --clean-volumes

# Verificar ajuda
./start-optimized.sh --help
```

#### **⚡ Configurações por Recursos do Sistema**
```bash
# Para sistemas com 8GB+ RAM (configuração otimizada)
docker compose -f docker-compose-optimized.yml up

# Para sistemas com 4-8GB RAM (configuração mínima)
docker compose -f docker-compose-minimal.yml up

# Configuração padrão
docker compose up
```

#### **🔍 Diagnóstico de Sistema**
```bash
# Diagnóstico completo do sistema
./diagnose.sh

# Verificar recursos específicos
./diagnose.sh system      # Memória, CPU, disco
./diagnose.sh docker      # Instalação Docker
./diagnose.sh containers  # Status containers
./diagnose.sh logs        # Erros recentes
./diagnose.sh monitor     # Monitor em tempo real
```

### **3.1 Configurar Cassandra (Manual)**
```bash
# Via Docker (recomendado)
docker compose up cassandra

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

## ⚠️ **Problemas Conhecidos e Soluções**

### **Problema: WriteTimeoutException no Cassandra**
**Sintoma:** `WriteTimeoutException: Cassandra timeout during SIMPLE write query`

**Soluções:**
1. **Use a configuração otimizada (Recomendada):**
   ```bash
   ./start-optimized.sh
   ```

2. **Para sistemas com poucos recursos:**
   ```bash
   docker compose -f docker-compose-minimal.yml up
   ```

3. **Otimizações manuais do sistema (como root):**
   ```bash
   sudo sysctl -w vm.max_map_count=1048575
   sudo sysctl -w vm.swappiness=1
   sudo sysctl -w net.core.rmem_max=134217728
   sudo sysctl -w net.core.wmem_max=134217728
   ```

### **Problema: OutOfMemoryError na aplicação Java**
**Solução:** Ajustar configurações JVM no docker-compose:

```yaml
environment:
  JAVA_OPTS: >
    -Xms1g -Xmx2g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
```

### **Problema: Containers não inicializam**
1. **Verificar recursos:**
   ```bash
   ./diagnose.sh system
   ```

2. **Limpar estado antigo:**
   ```bash
   docker compose down --volumes --remove-orphans
   docker system prune -f
   ```

3. **Verificar logs:**
   ```bash
   ./diagnose.sh logs
   docker logs htc-cassandra-db
   ```

### **Problema: "connection refused" no Cassandra**
```bash
# Aguardar inicialização completa
./start-optimized.sh  # Aguarda automaticamente

# Ou manualmente
docker compose up --wait cassandra
docker logs htc-cassandra-db  # Verificar logs
```

### **Problema: Performance baixa na simulação**
1. **Use configuração otimizada:**
   ```bash
   docker compose -f docker-compose-optimized.yml up
   ```

2. **Monitore recursos:**
   ```bash
   ./diagnose.sh monitor
   ```

3. **Reduza tamanho da simulação** (no arquivo JSON de configuração)

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