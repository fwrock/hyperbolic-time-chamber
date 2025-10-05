# 🚀 Hyperbolic Time Chamber - Complete Documentation

![HTC Banner](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**A comprehensive multi-agent traffic simulation framework for general-purpose mobility research**

A distributed, event-driven multi-agent system built with Scala and Apache Pekko, featuring a mesoscopic mobility model implementation for urban traffic simulation and analysis.

---

## 📚 **Documentation Index**

### 🏗️ **Core Documentation**
- **[Architecture Overview](docs/ARCHITECTURE.md)** - System design and components
- **[Getting Started](docs/GETTING_STARTED.md)** - Installation and first simulation
- **[Configuration Guide](docs/CONFIGURATION.md)** - Complete configuration reference
- **[Scenario Creation](docs/SCENARIO_CREATION.md)** - How to create simulation scenarios

### 🔧 **Development & Operation**
- **[API Reference](docs/API_REFERENCE.md)** - Actor system and event documentation
- **[Developer Guide](docs/DEVELOPER_GUIDE.md)** - Contributing and extending the system
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** - Common issues and solutions

### 📊 **Analysis & Research**
- **[Academic Usage](docs/ACADEMIC_USAGE.md)** - Research and publication guidelines

### 🔍 **Examples & Tutorials**
- **[Examples](docs/examples/)** - Learning examples and tutorials

---

## 🎯 **What is Hyperbolic Time Chamber?**

The Hyperbolic Time Chamber (HTC) is a general-purpose, distributed traffic simulation framework designed for:

### **🏢 Core Capabilities**
- **Multi-Agent Simulation**: Actor-based architecture using Apache Pekko
- **Mesoscopic Mobility Model**: Built-in implementation for urban traffic simulation
- **Distributed Computing**: Horizontal scaling across multiple nodes
- **Event-Driven Design**: Discrete event simulation with precise time management
- **Flexible Data Sources**: JSON, CSV, and database input support

### **📊 Analysis & Research**
- **Traffic Flow Analysis**: Comprehensive traffic pattern analysis
- **Performance Metrics**: Detailed performance and scalability measurements
- **Academic Research**: Publication-ready analysis and validation capabilities
- **Extensible Framework**: Support for custom mobility models and scenarios

### **� Extensibility**
- **Plugin Architecture**: Easy integration of new actor types
- **Custom Events**: Support for domain-specific event types
- **Reporting Framework**: Flexible data collection and analysis
- **API Integration**: REST and gRPC interfaces for external systems

---

## 🚀 **Quick Start**

```bash
cd hyperbolic-time-chamber

# Run tests
sbt test

# Format code
sbt scalafmt

# Check for issues
sbt scalafix --check
```

### **Project Standards:**
- **Scala Version**: 3.3.5
- **Build Tool**: SBT 1.x
- **Code Style**: Scalafmt with standard configuration
- **Testing**: ScalaTest with coverage reports
- **Documentation**: ScalaDoc for API documentation

**For detailed development guidelines, see [Developer Guide](docs/DEVELOPER_GUIDE.md)**

### **Contributing Guidelines:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/new-feature`)
3. Commit your changes (`git commit -am 'Add new feature'`)
4. Push to the branch (`git push origin feature/new-feature`)
5. Open a Pull Request

---

## 🎓 **Academic Usage**

This system was developed for research in **urban mobility** and **traffic simulation**.

**For citation:**
```bibtex
@software{hyperbolic_time_chamber,
  title={Hyperbolic Time Chamber: Multi-Agent Traffic Simulation Framework},
  author={[Your Name]},
  year={2025},
  url={https://github.com/fwrock/hyperbolic-time-chamber},
  note={Multi-agent simulation system with mesoscopic mobility model}
}
```

**For detailed academic usage guidelines, see [Academic Usage](docs/ACADEMIC_USAGE.md)**

---

## 📄 **License**

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## 🌟 **Key Features Summary**

✨ **Core Advantages:**
- 🏗️ **Actor-based architecture** with horizontal scaling
- ⚡ **High-performance** discrete event simulation
- 🚗 **Built-in mesoscopic model** for realistic traffic simulation
- 🗄️ **Time-series data storage** with Cassandra integration
- 🔧 **Flexible configuration** and scenario management
- 📊 **Comprehensive reporting** and analysis capabilities
- 🐳 **Docker deployment** for easy setup and scaling

🚀 **Use Cases:**
- **Urban Traffic Analysis**: City-scale traffic simulation and optimization
- **Transportation Research**: Academic research and validation studies  
- **Policy Evaluation**: Impact assessment of transportation policies
- **Infrastructure Planning**: Network design and capacity analysis
- **Algorithm Development**: Testing new mobility and routing algorithms

---

**🎉 Ready for large-scale traffic simulation and urban mobility research!**

*Inspired by the legendary "Hyperbolic Time Chamber" from Dragon Ball, where 1 day = 1 year of training, our simulator allows for accelerated time analysis of traffic patterns and urban mobility scenarios.*
```

**For detailed setup instructions, see [Getting Started](docs/GETTING_STARTED.md)**

---

## 🏗️ **System Architecture Overview**

```
┌─────────────────────────────────────────────────────────────┐
│                    Hyperbolic Time Chamber                 │
├─────────────────────────────────────────────────────────────┤
│  🎯 Core Simulator (Scala/Pekko)                          │
│  ├── Simulation Manager     ── Orchestrates simulation     │
│  ├── Time Manager          ── Manages simulation time     │
│  ├── Load Manager          ── Handles data loading        │
│  ├── Report Manager        ── Collects and stores data    │
│  └── Actor System          ── Multi-agent simulation      │
├─────────────────────────────────────────────────────────────┤
│  🗄️ Data Layer                                           │
│  ├── Apache Cassandra      ── Time-series data storage    │
│  ├── Configuration Files   ── JSON/HOCON configuration    │
│  └── Docker Services       ── Containerized deployment    │
└─────────────────────────────────────────────────────────────┘
```

**For detailed architecture information, see [Architecture Overview](docs/ARCHITECTURE.md)**

---
│   └── build.sbt                 # Build configuration

---

## 🚀 **How to Use**

### **📋 Management Interface**
```bash
# Complete system manager with menu interface
./htc-manager.sh

# Manager features:
# ✅ Automatic optimized initialization
# ✅ Complete system diagnostics  
# ✅ Cassandra management
# ✅ Performance monitoring
# ✅ Cleanup and maintenance
```

### **⚡ Quick Start**
```bash
# Auto-detect resources and apply best configuration
./start-optimized.sh

# With included diagnostics
./diagnose.sh && ./start-optimized.sh
```

### **1. Running Simulations**

```bash
# Build and run the simulator
sbt compile
sbt run

# Run with Docker
docker-compose up

# Run specific simulation scenario
sbt "run --scenario examples/basic_scenario.json"
```

### **2. Managing Cassandra Database**

```bash
# Cassandra management script
./manage_cassandra.sh

# Check database status
./manage_cassandra.sh status

# Clean database between simulations
./manage_cassandra.sh clean

# Initialize database schema
./manage_cassandra.sh init
```

### **3. Configuration**

```bash
# Copy and edit configuration
cp src/main/resources/application.conf my-config.conf

# Run with custom configuration
sbt "run -Dconfig.file=my-config.conf"

# Edit Docker configuration
vim docker-compose.yml
```

### **4. Development**

```bash
# Run tests
sbt test

# Generate documentation
sbt doc

# Format code
sbt scalafmt

# Check code quality
sbt scalafix
```

---

## 🏗️ **Project Structure**

```
hyperbolic-time-chamber/
├── 🎯 Core Simulator (Scala/Pekko)
│   ├── src/main/scala/           # Main simulator code
│   ├── src/main/resources/       # Configuration files
│   ├── src/main/protobuf/        # Protocol Buffers definitions
│   └── build.sbt                 # Build configuration
├── 📁 Docker Infrastructure
│   ├── docker-compose.yml        # Service orchestration
│   ├── Dockerfile                # Container definition
│   └── cassandra-config/         # Cassandra configuration
├── 🔧 Management Scripts
│   ├── htc-manager.sh            # Main management interface
│   ├── manage_cassandra.sh       # Database management
│   ├── build-and-run.sh          # Build and run script
│   └── run.sh                    # Simple run script
├── � Documentation
│   └── docs/                     # Complete documentation
└── 📊 Configuration
    ├── cassandra-init/           # Database initialization
    └── logs/                     # Log files
```

---

## 🎯 **Core Features**

### **🏗️ Simulator Core**
- ✅ **Discrete Event Simulation** with Scala/Pekko actors
- ✅ **Precise Time Management** for simulation
- ✅ **Flexible Data Loading** (JSON, CSV)
- ✅ **Distributed Event Coordination**
- ✅ **Automated Reporting System**
- ✅ **Simulation State Snapshots**
- ✅ **Multi-node Distributed Simulation**

### **🚗 Mesoscopic Mobility Model**
- ✅ **Built-in Traffic Flow Model** for urban scenarios
- ✅ **Vehicle Behavior Modeling** with configurable parameters
- ✅ **Route Choice and Navigation** algorithms
- ✅ **Traffic Signal Integration** and timing optimization
- ✅ **Multi-modal Transportation** support

### **🗄️ Database Management**
- ✅ **Apache Cassandra Integration** for time-series data
- ✅ **Automated Schema Management**
- ✅ **Data Cleanup** between simulations
- ✅ **Performance Monitoring** and optimization
- ✅ **Backup and Recovery** procedures

---

## � **Simulation Outputs**

### **Data Storage:**
```
Cassandra Tables:
├── � simulation_events         # All simulation events
├── � vehicle_states           # Vehicle position and status
├── �️ link_flows               # Traffic flow on network links
├── ⏱️ time_aggregated_data      # Time-based aggregations
└── � performance_metrics      # System performance data
```

### **Generated Reports:**
```
simulation_output/
├── 📋 simulation_summary.json   # Complete simulation data
├── 📝 performance_report.md     # Performance analysis
├── 📊 network_statistics.json   # Network-level metrics
└── 📈 time_series_data.csv     # Temporal data for analysis
```
---

## 🗄️ **Database Management**

### **Cassandra Control Scripts:**

```bash
# Start Cassandra
./manage_cassandra.sh start

# Clean old data (recommended before each simulation)
./manage_cassandra.sh clean

# Check current data status
./manage_cassandra.sh status

# Complete reset (clean everything)
./manage_cassandra.sh reset

# Automated workflow
./manage_cassandra.sh init
```

### **Clean Simulation Workflow:**

```bash
# 1. Clean old data
./manage_cassandra.sh clean

# 2. Start new simulation
sbt run

# 3. Monitor progress
./manage_cassandra.sh status

# 4. Export results
./manage_cassandra.sh export
```

---

## ⚙️ **Configuration**

### **Main Configuration Files:**
- `src/main/resources/application.conf` - Main simulator configuration
- `src/main/resources/application-local.conf` - Local development settings
- `docker-compose.yml` - Docker services configuration
- `cassandra-config/cassandra.yaml` - Cassandra database settings

### **Key Configuration Sections:**

#### **Simulation Settings:**
```hocon
simulation {
  time {
    start = "06:00:00"
    end = "09:00:00"
    step = 1  # seconds
  }
  
  actors {
    vehicle-count = 1000
    max-concurrent = 100
  }
  
  network {
    file = "scenarios/network.json"
    validation = true
  }
}
```

#### **Database Settings:**
```hocon
cassandra {
  hosts = ["127.0.0.1"]
  port = 9042
  keyspace = "htc_simulation"
  replication-factor = 1
}
```

#### **Performance Tuning:**
```hocon
akka {
  actor {
    default-dispatcher {
      type = "Dispatcher"
      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-min = 8
        parallelism-max = 64
      }
    }
  }
}
```

---

## 🚀 **Performance & Scaling**

### **System Requirements:**
- **Minimum**: 4 GB RAM, 2 CPU cores
- **Recommended**: 16 GB RAM, 8 CPU cores
- **Large Scale**: 32+ GB RAM, 16+ CPU cores

### **Scaling Guidelines:**
- **Small scenarios**: < 1,000 vehicles, single node
- **Medium scenarios**: 1,000-10,000 vehicles, 2-4 nodes
- **Large scenarios**: 10,000+ vehicles, 4+ nodes

### **Performance Monitoring:**
```bash
# Monitor system resources
htop

# Monitor Cassandra performance
./manage_cassandra.sh metrics

# Monitor JVM performance
jstat -gc $(pgrep java)

# Monitor simulation progress
tail -f logs/simulation.log
```

---

## 🔧 **Development & Contributing**

### **Setting up Development Environment:**
```bash
# Install dependencies
sudo apt-get install openjdk-11-jdk sbt docker.io

# Clone repository
git clone https://github.com/fwrock/hyperbolic-time-chamber.git
cd hyperbolic-time-chamber

# Run tests
sbt test

# Format code
sbt scalafmt

# Check for issues
sbt scalafix --check
```

### **Project Standards:**
- **Scala Version**: 3.3.5
- **Build Tool**: SBT 1.x
- **Code Style**: Scalafmt with standard configuration
- **Testing**: ScalaTest with coverage reports
- **Documentation**: ScalaDoc for API documentation

**For detailed development guidelines, see [Developer Guide](docs/DEVELOPER_GUIDE.md)**

---
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