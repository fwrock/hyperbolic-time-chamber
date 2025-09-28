#!/bin/bash

# Traffic Analysis System - Setup and Run Script
# Este script configura o ambiente Python e executa a análise de tráfego

set -e  # Exit on any error

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Função para log colorido
log() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[WARNING] $1${NC}"
}

error() {
    echo -e "${RED}[ERROR] $1${NC}"
}

info() {
    echo -e "${BLUE}[INFO] $1${NC}"
}

# Banner
echo -e "${PURPLE}"
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║                  HYPERBOLIC TIME CHAMBER                      ║"
echo "║                 Traffic Analysis System                       ║"
echo "║                   Setup & Run Script                          ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Verificar se estamos no diretório correto
if [ ! -f "docker-compose.yml" ] || [ ! -d "scripts" ]; then
    error "Este script deve ser executado no diretório raiz do projeto Hyperbolic Time Chamber"
    error "Certifique-se de estar no diretório que contém docker-compose.yml e scripts/"
    exit 1
fi

# Configurações
SCRIPTS_DIR="./scripts"
VENV_DIR="$SCRIPTS_DIR/venv"
PYTHON_CMD="python3"

# Verificar se Python 3 está instalado
if ! command -v $PYTHON_CMD &> /dev/null; then
    error "Python 3 não encontrado. Por favor, instale Python 3.8 ou superior."
    exit 1
fi

PYTHON_VERSION=$($PYTHON_CMD --version 2>&1 | grep -oP '\d+\.\d+')
info "Python version: $PYTHON_VERSION"

# Função para verificar e instalar pip
check_pip() {
    if ! $PYTHON_CMD -m pip --version &> /dev/null; then
        warn "pip não encontrado. Tentando instalar..."
        if command -v apt-get &> /dev/null; then
            sudo apt-get update && sudo apt-get install -y python3-pip
        elif command -v yum &> /dev/null; then
            sudo yum install -y python3-pip
        elif command -v brew &> /dev/null; then
            brew install python3
        else
            error "Não foi possível instalar pip automaticamente. Instale manualmente."
            exit 1
        fi
    fi
}

# Função para configurar ambiente virtual
setup_venv() {
    log "Configurando ambiente virtual Python..."
    
    # Remover venv existente se houver
    if [ -d "$VENV_DIR" ]; then
        warn "Removendo ambiente virtual existente..."
        rm -rf "$VENV_DIR"
    fi
    
    # Criar novo ambiente virtual
    $PYTHON_CMD -m venv "$VENV_DIR"
    
    # Ativar ambiente virtual
    source "$VENV_DIR/bin/activate"
    
    # Atualizar pip
    log "Atualizando pip..."
    pip install --upgrade pip setuptools wheel
    
    log "Ambiente virtual criado e ativado: $VENV_DIR"
}

# Função para instalar dependências
install_dependencies() {
    log "Instalando dependências Python..."
    
    # Ativar ambiente virtual
    source "$VENV_DIR/bin/activate"
    
    # Instalar dependências do requirements.txt
    if [ -f "$SCRIPTS_DIR/requirements.txt" ]; then
        log "Instalando dependências do requirements.txt..."
        pip install -r "$SCRIPTS_DIR/requirements.txt"
    else
        error "Arquivo requirements.txt não encontrado em $SCRIPTS_DIR"
        exit 1
    fi
    
    # Verificar instalações críticas
    log "Verificando instalações..."
    python -c "import pandas, numpy, matplotlib, seaborn, plotly, cassandra, folium, jupyter" 2>/dev/null && \
        log "Todas as dependências foram instaladas com sucesso!" || \
        error "Algumas dependências falharam na instalação"
}

# Função para verificar serviços Docker
check_docker_services() {
    log "Verificando serviços Docker..."
    
    # Verificar se Docker está rodando
    if ! docker ps &> /dev/null; then
        error "Docker não está rodando. Inicie o Docker primeiro."
        exit 1
    fi
    
    # Verificar se docker-compose está disponível
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        error "docker-compose não encontrado. Instale docker-compose."
        exit 1
    fi
    
    # Iniciar serviços se não estiverem rodando
    info "Verificando status dos serviços..."
    if ! docker ps | grep -q "htc-cassandra-db"; then
        log "Iniciando serviços Docker..."
        docker compose up -d
        
        log "Aguardando Cassandra inicializar (30 segundos)..."
        sleep 30
        
        # Verificar se Cassandra está acessível
        for i in {1..10}; do
            if docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES;" &> /dev/null; then
                log "Cassandra está funcionando!"
                break
            else
                warn "Tentativa $i/10: Cassandra ainda não está pronto..."
                sleep 5
            fi
        done
    else
        log "Serviços Docker já estão rodando"
    fi
}

# Função para executar testes do sistema
run_tests() {
    log "Executando testes do sistema..."
    
    source "$VENV_DIR/bin/activate"
    cd "$SCRIPTS_DIR"
    
    python test_system.py
    
    if [ $? -eq 0 ]; then
        log "Todos os testes passaram! Sistema está pronto para uso."
    else
        warn "Alguns testes falharam, mas o sistema pode ainda funcionar."
        info "Verifique os logs acima para detalhes."
    fi
}

# Função para executar análise
run_analysis() {
    log "Executando análise de tráfego..."
    
    source "$VENV_DIR/bin/activate"
    cd "$SCRIPTS_DIR"
    
    # Parâmetros padrão
    SOURCE_TYPE="${1:-cassandra}"
    LIMIT="${2:-5000}"
    
    case $SOURCE_TYPE in
        "cassandra")
            info "Executando análise com dados do Cassandra (limite: $LIMIT registros)..."
            python run_traffic_analysis.py cassandra --limit $LIMIT
            ;;
        "csv")
            info "Executando análise com arquivos CSV..."
            python run_traffic_analysis.py csv --file /home/dean/PhD/hyperbolic-time-chamber/data/csv/vehicle_flow_sample.csv
            ;;
        "json")
            info "Executando análise com arquivos JSON..."
            python run_traffic_analysis.py csv --file ../data/json/vehicle_flows.json
            ;;
        *)
            error "Tipo de fonte inválido: $SOURCE_TYPE"
            error "Tipos suportados: cassandra, csv, json"
            exit 1
            ;;
    esac
}

# Função para abrir Jupyter Notebook
start_jupyter() {
    log "Iniciando Jupyter Notebook..."
    
    source "$VENV_DIR/bin/activate"
    cd "$SCRIPTS_DIR"
    
    info "Abrindo notebook interativo..."
    info "URL: http://localhost:8888"
    info "Para parar, pressione Ctrl+C"
    
    jupyter notebook Traffic_Analysis_Interactive.ipynb --ip=0.0.0.0 --port=8888 --no-browser
}

# Função para mostrar status
show_status() {
    echo -e "${CYAN}"
    echo "═══════════════════════════════════════════════════════════════"
    echo "                        STATUS DO SISTEMA                      "
    echo "═══════════════════════════════════════════════════════════════"
    echo -e "${NC}"
    
    # Status do ambiente Python
    if [ -d "$VENV_DIR" ]; then
        echo -e "${GREEN}✓${NC} Ambiente virtual Python: CONFIGURADO"
        if [ -f "$VENV_DIR/pyvenv.cfg" ]; then
            PYTHON_VERSION=$(grep "version" "$VENV_DIR/pyvenv.cfg" | cut -d'=' -f2 | xargs)
            echo "  Python version: $PYTHON_VERSION"
        fi
    else
        echo -e "${RED}✗${NC} Ambiente virtual Python: NÃO CONFIGURADO"
    fi
    
    # Status dos serviços Docker
    if docker ps | grep -q "htc-cassandra-db"; then
        echo -e "${GREEN}✓${NC} Cassandra: RODANDO"
    else
        echo -e "${RED}✗${NC} Cassandra: PARADO"
    fi
    
    if docker ps | grep -q "htc-ds-studio"; then
        echo -e "${GREEN}✓${NC} DataStax Studio: RODANDO (http://localhost:9091)"
    else
        echo -e "${RED}✗${NC} DataStax Studio: PARADO"
    fi
    
    # Status dos arquivos de output
    if [ -d "$SCRIPTS_DIR/output" ]; then
        OUTPUT_COUNT=$(find "$SCRIPTS_DIR/output" -name "*.html" | wc -l)
        echo -e "${GREEN}✓${NC} Relatórios gerados: $OUTPUT_COUNT arquivos"
    else
        echo -e "${YELLOW}○${NC} Relatórios: NENHUM GERADO"
    fi
    
    echo ""
}

# Função para limpeza
cleanup() {
    log "Limpando arquivos temporários..."
    
    # Limpar outputs antigos (manter últimos 5)
    if [ -d "$SCRIPTS_DIR/output" ]; then
        find "$SCRIPTS_DIR/output" -name "analysis_*" -type d | sort -r | tail -n +6 | xargs rm -rf
    fi
    
    # Limpar cache Python
    find "$SCRIPTS_DIR" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
    find "$SCRIPTS_DIR" -name "*.pyc" -delete 2>/dev/null || true
    
    log "Limpeza concluída"
}

# Função para mostrar ajuda
show_help() {
    echo -e "${BLUE}"
    echo "Traffic Analysis System - Script de Automação"
    echo "============================================="
    echo -e "${NC}"
    echo "Uso: $0 [COMANDO] [OPÇÕES]"
    echo ""
    echo "COMANDOS:"
    echo "  setup              Configura ambiente Python e instala dependências"
    echo "  run [fonte] [qty]  Executa análise de tráfego"
    echo "                     fonte: cassandra|csv|json (padrão: cassandra)"
    echo "                     qty: número de registros (padrão: 5000)"
    echo "  jupyter           Inicia Jupyter Notebook interativo"
    echo "  test              Executa testes do sistema"
    echo "  status            Mostra status dos serviços"
    echo "  cleanup           Remove arquivos temporários"
    echo "  docker-up         Inicia serviços Docker"
    echo "  docker-down       Para serviços Docker"
    echo "  help              Mostra esta ajuda"
    echo ""
    echo "EXEMPLOS:"
    echo "  $0 setup                    # Configura tudo pela primeira vez"
    echo "  $0 run                      # Análise básica com Cassandra"
    echo "  $0 run cassandra 10000      # Análise com 10k registros"
    echo "  $0 run csv                  # Análise usando arquivos CSV"
    echo "  $0 jupyter                  # Abre análise interativa"
    echo "  $0 test                     # Testa se tudo está funcionando"
    echo ""
    echo -e "${YELLOW}PRIMEIRA VEZ?${NC}"
    echo "  Execute: $0 setup && $0 run"
    echo ""
}

# Processar argumentos da linha de comando
case "${1:-help}" in
    "setup")
        log "Iniciando configuração completa do sistema..."
        check_pip
        setup_venv
        install_dependencies
        check_docker_services
        run_tests
        show_status
        log "Sistema configurado com sucesso!"
        echo ""
        info "Para executar análise: $0 run"
        info "Para análise interativa: $0 jupyter"
        ;;
        
    "run")
        check_docker_services
        run_analysis "${2:-cassandra}" "${3:-5000}"
        show_status
        ;;
        
    "jupyter")
        check_docker_services
        start_jupyter
        ;;
        
    "test")
        if [ ! -d "$VENV_DIR" ]; then
            error "Ambiente virtual não configurado. Execute: $0 setup"
            exit 1
        fi
        run_tests
        ;;
        
    "status")
        show_status
        ;;
        
    "cleanup")
        cleanup
        ;;
        
    "docker-up")
        log "Iniciando serviços Docker..."
        docker compose up -d
        log "Serviços iniciados!"
        ;;
        
    "docker-down")
        log "Parando serviços Docker..."
        docker compose down
        log "Serviços parados!"
        ;;
        
    "help"|"--help"|"-h")
        show_help
        ;;
        
    *)
        error "Comando inválido: $1"
        show_help
        exit 1
        ;;
esac