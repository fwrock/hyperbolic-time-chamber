#!/bin/bash
# Sistema de Análise de Tráfego HTC - Script de Ajuda
# Este script fornece comandos rápidos para execução das análises

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANALYSIS_DIR="$SCRIPT_DIR"

echo "🚀 Sistema de Análise de Tráfego HTC"
echo "======================================"
echo

# Função para mostrar ajuda
show_help() {
    echo "📋 COMANDOS DISPONÍVEIS:"
    echo
    echo "1️⃣  COMPARAÇÃO TRADICIONAL (HTC vs Referência)"
    echo "   ./analysis_helper.sh compare-cassandra <reference.xml>"
    echo "   ./analysis_helper.sh compare-csv <htc_data.csv> <reference.xml>"
    echo
    echo "2️⃣  ANÁLISE DE REPRODUTIBILIDADE"
    echo "   ./analysis_helper.sh repro-cassandra <sim_id1> <sim_id2> [sim_id3...]"
    echo "   ./analysis_helper.sh repro-csv <file1.csv> <file2.csv> [file3.csv...]"
    echo "   ./analysis_helper.sh repro-xml <file1.xml> <file2.xml> [file3.xml...]"
    echo
    echo "3️⃣  CONFIGURAÇÃO E EXEMPLOS"
    echo "   ./analysis_helper.sh create-sample     # Criar XML de exemplo"
    echo "   ./analysis_helper.sh repro-config     # Criar config de reprodutibilidade"
    echo "   ./analysis_helper.sh install-deps     # Instalar dependências Python"
    echo
    echo "4️⃣  ANÁLISES INDEPENDENTES"
    echo "   ./analysis_helper.sh metrics-cassandra [limit]  # Métricas gerais via Cassandra"
    echo "   ./analysis_helper.sh metrics-csv <file.csv>     # Métricas gerais via CSV"
    echo
    echo "5️⃣  GERENCIAMENTO DE SIMULATION IDs"
    echo "   ./analysis_helper.sh list-simulations          # Listar simulation IDs disponíveis"
    echo "   ./analysis_helper.sh sim-details <sim_id>      # Detalhes de uma simulação"
    echo "   ./analysis_helper.sh generate-sim-id [prefix]  # Gerar novo simulation ID"
    echo
    echo "6️⃣  OUTROS"
    echo "   ./analysis_helper.sh help             # Mostrar esta ajuda"
    echo "   ./analysis_helper.sh status           # Status do sistema"
    echo
}

# Função para verificar status do sistema
check_status() {
    echo "🔍 VERIFICANDO STATUS DO SISTEMA"
    echo "================================"
    echo
    
    # Verificar Python
    if command -v python3 &> /dev/null; then
        echo "✅ Python3: $(python3 --version)"
    else
        echo "❌ Python3 não encontrado"
    fi
    
    # Verificar dependências Python
    echo "📦 Verificando dependências Python..."
    python3 -c "
import sys
deps = ['pandas', 'matplotlib', 'seaborn', 'numpy', 'scipy', 'sklearn', 'cassandra']
missing = []
for dep in deps:
    try:
        __import__(dep)
        print(f'✅ {dep}')
    except ImportError:
        print(f'❌ {dep}')
        missing.append(dep)

if missing:
    print(f'')
    print(f'💡 Para instalar dependências faltantes:')
    print(f'   pip install {\" \".join(missing)}')
" 2>/dev/null || echo "❌ Erro ao verificar dependências"
    
    # Verificar scripts
    echo
    echo "📄 Verificando scripts..."
    scripts=("compare_simulators.py" "reproducibility_analysis.py")
    for script in "${scripts[@]}"; do
        if [[ -f "$ANALYSIS_DIR/$script" ]]; then
            echo "✅ $script"
        else
            echo "❌ $script não encontrado"
        fi
    done
    
    # Verificar Cassandra (opcional)
    echo
    echo "🗄️  Verificando Cassandra..."
    if python3 -c "from cassandra.cluster import Cluster; Cluster(['localhost']).connect()" 2>/dev/null; then
        echo "✅ Cassandra conectável em localhost"
    else
        echo "⚠️  Cassandra não conectável (normal se usando Docker)"
    fi
    
    echo
}

# Função para instalar dependências
install_deps() {
    echo "📦 INSTALANDO DEPENDÊNCIAS PYTHON"
    echo "================================="
    echo
    
    echo "🔄 Atualizando pip..."
    python3 -m pip install --upgrade pip
    
    echo "📦 Instalando dependências principais..."
    python3 -m pip install pandas matplotlib seaborn numpy scipy scikit-learn
    
    echo "🗄️  Instalando driver Cassandra..."
    python3 -m pip install cassandra-driver
    
    echo "📊 Instalando dependências de visualização..."
    python3 -m pip install plotly kaleido
    
    echo "✅ Instalação concluída!"
    echo
}

# Processamento de comandos
case "$1" in
    # Ajuda
    "help"|"--help"|"-h"|"")
        show_help
        ;;
    
    # Status
    "status")
        check_status
        ;;
    
    # Instalar dependências
    "install-deps")
        install_deps
        ;;
    
    # Comparação tradicional
    "compare-cassandra")
        if [[ -z "$2" ]]; then
            echo "❌ Uso: $0 compare-cassandra <reference.xml>"
            exit 1
        fi
        echo "🔬 Executando comparação HTC (Cassandra) vs Referência..."
        python3 "$ANALYSIS_DIR/compare_simulators.py" "$2" --htc-cassandra
        ;;
    
    "compare-csv")
        if [[ -z "$2" || -z "$3" ]]; then
            echo "❌ Uso: $0 compare-csv <htc_data.csv> <reference.xml>"
            exit 1
        fi
        echo "🔬 Executando comparação HTC (CSV) vs Referência..."
        python3 "$ANALYSIS_DIR/compare_simulators.py" "$3" --htc-csv "$2"
        ;;
    
    # Análise de reprodutibilidade
    "repro-cassandra")
        if [[ $# -lt 3 ]]; then
            echo "❌ Uso: $0 repro-cassandra <sim_id1> <sim_id2> [sim_id3...]"
            echo "   Exemplo: $0 repro-cassandra sim_001 sim_002 sim_003"
            exit 1
        fi
        shift  # Remove o primeiro argumento
        echo "🔄 Executando análise de reprodutibilidade (Cassandra)..."
        python3 "$ANALYSIS_DIR/reproducibility_analysis.py" --cassandra-sims "$@"
        ;;
    
    "repro-csv")
        if [[ $# -lt 3 ]]; then
            echo "❌ Uso: $0 repro-csv <file1.csv> <file2.csv> [file3.csv...]"
            exit 1
        fi
        shift  # Remove o primeiro argumento
        echo "🔄 Executando análise de reprodutibilidade (CSV)..."
        python3 "$ANALYSIS_DIR/reproducibility_analysis.py" --csv-files "$@"
        ;;
    
    "repro-xml")
        if [[ $# -lt 3 ]]; then
            echo "❌ Uso: $0 repro-xml <file1.xml> <file2.xml> [file3.xml...]"
            exit 1
        fi
        shift  # Remove o primeiro argumento
        echo "🔄 Executando análise de reprodutibilidade (XML)..."
        python3 "$ANALYSIS_DIR/reproducibility_analysis.py" --xml-files "$@"
        ;;
    
    # Análises independentes de métricas
    "metrics-cassandra")
        limit=${2:-999999999}
        echo "📊 Executando análise de métricas gerais (Cassandra, limit=$limit)..."
        python3 -c "
import sys
sys.path.append('$ANALYSIS_DIR')
from data_sources.cassandra_source import CassandraDataSource
from analysis.general_metrics import GeneralTrafficMetrics
import logging

logging.basicConfig(level=logging.INFO)

# Carregar dados
cassandra = CassandraDataSource()
data = cassandra.get_vehicle_flow_data(limit=$limit)

if not data.empty:
    # Analisar métricas
    analyzer = GeneralTrafficMetrics(output_dir='$ANALYSIS_DIR/output/standalone_metrics')
    metrics = analyzer.calculate_all_metrics(data)
    plots = analyzer.generate_all_plots(data, metrics)
    report = analyzer.save_metrics_report(metrics, 'cassandra_metrics.json')
    
    print('✅ Análise concluída!')
    print(f'📁 Arquivos salvos em: $ANALYSIS_DIR/output/standalone_metrics')
else:
    print('❌ Nenhum dado encontrado')
"
        ;;
    
    "metrics-csv")
        if [[ -z "$2" ]]; then
            echo "❌ Uso: $0 metrics-csv <file.csv>"
            exit 1
        fi
        echo "📊 Executando análise de métricas gerais (CSV)..."
        python3 -c "
import sys
import pandas as pd
sys.path.append('$ANALYSIS_DIR')
from analysis.general_metrics import GeneralTrafficMetrics
import logging

logging.basicConfig(level=logging.INFO)

# Carregar dados
try:
    data = pd.read_csv('$2')
    print(f'✅ Carregados {len(data)} registros')
    
    # Analisar métricas
    analyzer = GeneralTrafficMetrics(output_dir='$ANALYSIS_DIR/output/standalone_metrics')
    metrics = analyzer.calculate_all_metrics(data)
    plots = analyzer.generate_all_plots(data, metrics)
    report = analyzer.save_metrics_report(metrics, 'csv_metrics.json')
    
    print('✅ Análise concluída!')
    print(f'📁 Arquivos salvos em: $ANALYSIS_DIR/output/standalone_metrics')
except Exception as e:
    print(f'❌ Erro: {e}')
"
        ;;
    
    # Configuração e exemplos
    "create-sample")
        echo "📄 Criando arquivo XML de exemplo..."
        python3 "$ANALYSIS_DIR/compare_simulators.py" --create-sample
        ;;
    
    "repro-config")
        echo "📄 Criando configuração de exemplo para análise de reprodutibilidade..."
        python3 "$ANALYSIS_DIR/reproducibility_analysis.py" --create-config
        ;;
    
    # Gerenciamento de Simulation IDs
    "list-simulations")
        echo "📊 Listando simulation IDs disponíveis..."
        python3 "$ANALYSIS_DIR/simulation_id_manager.py" --list
        ;;
    
    "sim-details")
        if [[ -z "$2" ]]; then
            echo "❌ Uso: $0 sim-details <simulation_id>"
            exit 1
        fi
        echo "🔍 Mostrando detalhes da simulação $2..."
        python3 "$ANALYSIS_DIR/simulation_id_manager.py" --details "$2"
        ;;
    
    "generate-sim-id")
        if [[ -n "$2" ]]; then
            echo "🆔 Gerando simulation ID com prefixo '$2'..."
            python3 "$ANALYSIS_DIR/simulation_id_manager.py" --generate --prefix "$2"
        else
            echo "🆔 Gerando simulation ID..."
            python3 "$ANALYSIS_DIR/simulation_id_manager.py" --generate
        fi
        ;;
    
    # Comando não reconhecido
    *)
        echo "❌ Comando não reconhecido: $1"
        echo
        show_help
        exit 1
        ;;
esac