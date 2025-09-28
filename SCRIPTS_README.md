# 🚀 Scripts de Execução - Hyperbolic Time Chamber

Este diretório contém scripts automatizados para configurar e executar a análise de tráfego do projeto.

## 📁 Arquivos Principais

- **`run_traffic_analysis.sh`** - Script principal com todas as funcionalidades
- **`quick_analysis.sh`** - Script para execução rápida (primeira vez)
- **`requirements.txt`** - Dependências Python
- **`config.py`** - Configurações do sistema

## ⚡ Execução Rápida (Primeira Vez)

```bash
# No diretório raiz do projeto
./quick_analysis.sh
```

Este script vai:
1. ✅ Configurar ambiente Python
2. ✅ Instalar todas as dependências
3. ✅ Iniciar serviços Docker
4. ✅ Executar análise com 3000 registros
5. ✅ Gerar relatórios e visualizações

## 🛠️ Script Principal - Opções Avançadas

### Configuração Inicial (só precisa rodar uma vez)
```bash
./run_traffic_analysis.sh setup
```

### Executar Análise
```bash
# Análise básica (5000 registros do Cassandra)
./run_traffic_analysis.sh run

# Análise com mais dados
./run_traffic_analysis.sh run cassandra 10000

# Análise usando arquivos CSV
./run_traffic_analysis.sh run csv

# Análise usando arquivos JSON
./run_traffic_analysis.sh run json
```

### Análise Interativa (Jupyter)
```bash
./run_traffic_analysis.sh jupyter
```
Abre o notebook interativo em: http://localhost:8888

### Outros Comandos
```bash
# Ver status dos serviços
./run_traffic_analysis.sh status

# Executar testes do sistema
./run_traffic_analysis.sh test

# Limpar arquivos temporários
./run_traffic_analysis.sh cleanup

# Controlar Docker
./run_traffic_analysis.sh docker-up
./run_traffic_analysis.sh docker-down

# Ajuda
./run_traffic_analysis.sh help
```

## 📊 Saídas Geradas

Depois da execução, você encontrará:

### Relatórios (`scripts/output/reports/`)
- **HTML**: `traffic_analysis_report_YYYYMMDD_HHMMSS.html`
- **Markdown**: `traffic_analysis_report_YYYYMMDD_HHMMSS.md`
- **JSON**: `traffic_analysis_report_YYYYMMDD_HHMMSS.json`

### Visualizações (`scripts/output/visualizations/`)
- **Heatmap de tráfego**: `traffic_heatmap.html`
- **Velocidade vs Densidade**: `speed_density_analysis.html`
- **Análise de gargalos**: `bottleneck_analysis.html`
- **Padrões de mobilidade**: `mobility_patterns.html`
- **Dashboard completo**: `comprehensive_dashboard.html`

## 🔧 Configuração Manual (Avançado)

Se você quiser configurar manualmente:

### 1. Criar Ambiente Virtual
```bash
cd scripts/
python3 -m venv venv
source venv/bin/activate
```

### 2. Instalar Dependências
```bash
pip install -r requirements.txt
```

### 3. Executar Análise
```bash
python run_analysis.py --verbose
```

## 📱 Serviços Web

Após iniciar os serviços, você terá acesso a:

- **DataStax Studio**: http://localhost:9091 (visualização de dados Cassandra)
- **Jupyter Notebook**: http://localhost:8888 (análise interativa)
- **Relatórios HTML**: Abrir arquivos em `scripts/output/visualizations/`

## 🐛 Solução de Problemas

### Erro de Conexão Cassandra
```bash
# Verificar se está rodando
docker ps | grep cassandra

# Reiniciar serviços
./run_traffic_analysis.sh docker-down
./run_traffic_analysis.sh docker-up
```

### Erro de Dependências Python
```bash
# Recriar ambiente
rm -rf scripts/venv/
./run_traffic_analysis.sh setup
```

### Sem Dados para Analisar
```bash
# Executar simulação primeiro
sbt run

# Depois executar análise
./run_traffic_analysis.sh run
```

### Problemas de Memória
```bash
# Usar menos registros
./run_traffic_analysis.sh run cassandra 1000
```

## 📚 Estrutura dos Módulos Python

```
scripts/
├── data_sources/          # Conectores de dados
│   ├── cassandra_source.py
│   └── file_sources.py
├── analysis/              # Algoritmos de análise
│   └── traffic_analyzer.py
├── visualization/         # Geração de gráficos
│   └── traffic_viz.py
├── reports/               # Relatórios
│   └── report_generator.py
└── output/                # Arquivos gerados
    ├── visualizations/
    └── reports/
```

## 🎯 Tipos de Análise Disponíveis

### Métricas Básicas
- Total de veículos únicos
- Velocidade média/mediana
- Tempos de viagem
- Cobertura da rede

### Análise Temporal
- Padrões por hora do dia
- Horários de pico
- Variações por dia da semana

### Análise Espacial
- Links mais utilizados
- Gargalos da rede
- Densidade por região

### Eficiência Operacional
- Velocidades por link
- Utilização da capacidade
- Indicadores de congestionamento

## 🌟 Próximos Passos

Após executar a análise:

1. **Abra os relatórios HTML** para visualização interativa
2. **Use o Jupyter Notebook** para análises personalizadas
3. **Explore o DataStax Studio** para consultas avançadas no Cassandra
4. **Ajuste parâmetros** na simulação baseado nos insights
5. **Execute análises comparativas** entre diferentes cenários

---

## 📞 Suporte

Para dúvidas ou problemas:
1. Execute `./run_traffic_analysis.sh test` para diagnóstico
2. Verifique os logs gerados
3. Consulte a documentação em `scripts/README.md`