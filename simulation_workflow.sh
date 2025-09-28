#!/bin/bash

# Script de workflow completo para simulação HTC
# Prepara Cassandra, executa simulação e permite análise
# Uso: ./simulation_workflow.sh [clean|reset|status]

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuração
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo -e "${CYAN}"
echo "🚀 ====================================="
echo "   HTC SIMULATOR - WORKFLOW COMPLETO"
echo "===================================== 🚀"
echo -e "${NC}"

show_help() {
    echo -e "${BLUE}Uso: $0 [OPÇÃO]${NC}"
    echo ""
    echo "OPÇÕES:"
    echo "  clean   - Limpar dados e executar simulação"
    echo "  reset   - Reset completo do Cassandra e executar"
    echo "  status  - Apenas verificar status"
    echo "  help    - Mostrar esta ajuda"
    echo ""
    echo "SEM OPÇÕES: Workflow padrão (verificar, preparar, executar)"
}

# Função para mostrar status
show_status() {
    echo -e "${BLUE}📊 VERIFICANDO STATUS DO SISTEMA...${NC}"
    echo ""
    
    # Status do Cassandra
    echo -e "${PURPLE}🗄️ CASSANDRA:${NC}"
    ./manage_cassandra.sh status
    echo ""
    
    # Status dos dados
    echo -e "${PURPLE}📊 DADOS:${NC}"
    ./check_cassandra_data.sh
    echo ""
}

# Função para preparar ambiente
prepare_environment() {
    echo -e "${BLUE}🔧 PREPARANDO AMBIENTE...${NC}"
    
    # Verificar se Cassandra está rodando
    if ! docker ps --format "{{.Names}}" | grep -q "cassandra"; then
        echo -e "${YELLOW}⚠️ Cassandra não está rodando. Iniciando...${NC}"
        ./manage_cassandra.sh start
    else
        echo -e "${GREEN}✅ Cassandra já está rodando${NC}"
    fi
    
    echo ""
}

# Função para limpar dados
clean_data() {
    echo -e "${BLUE}🧹 LIMPANDO DADOS ANTIGOS...${NC}"
    ./manage_cassandra.sh clean
    echo ""
}

# Função para reset completo
reset_system() {
    echo -e "${BLUE}♻️ RESETANDO SISTEMA COMPLETO...${NC}"
    ./manage_cassandra.sh reset
    echo ""
}

# Função para executar simulação
run_simulation() {
    echo -e "${BLUE}🎮 EXECUTANDO SIMULAÇÃO HTC...${NC}"
    echo ""
    
    if [[ -f "$SCRIPT_DIR/build-and-run.sh" ]]; then
        echo -e "${GREEN}▶️ Executando build-and-run.sh...${NC}"
        ./build-and-run.sh
    else
        echo -e "${RED}❌ Arquivo build-and-run.sh não encontrado!${NC}"
        echo -e "${YELLOW}💡 Certifique-se de que o arquivo existe no diretório raiz${NC}"
        return 1
    fi
    
    echo ""
}

# Função para mostrar opções pós-simulação
show_analysis_options() {
    echo -e "${CYAN}"
    echo "🎯 ====================================="
    echo "   SIMULAÇÃO CONCLUÍDA!"
    echo "===================================== 🎯"
    echo -e "${NC}"
    
    echo -e "${BLUE}📈 OPÇÕES DE ANÁLISE DISPONÍVEIS:${NC}"
    echo ""
    
    echo -e "${GREEN}1. Análise de Tráfego:${NC}"
    echo "   ./run_traffic_analysis.sh"
    echo ""
    
    echo -e "${GREEN}2. Comparação com Simulador de Referência:${NC}"
    echo "   ./run_comparison.sh --cassandra reference_events.xml"
    echo ""
    
    echo -e "${GREEN}3. Verificar dados gerados:${NC}"
    echo "   ./check_cassandra_data.sh"
    echo ""
    
    echo -e "${GREEN}4. Parar sistema:${NC}"
    echo "   ./manage_cassandra.sh stop"
    echo ""
    
    echo -e "${YELLOW}💡 Para executar nova simulação:${NC}"
    echo "   $0 clean"
    echo ""
}

# Workflow principal
run_main_workflow() {
    echo -e "${BLUE}🔄 EXECUTANDO WORKFLOW PRINCIPAL...${NC}"
    echo ""
    
    # 1. Verificar status atual
    echo -e "${PURPLE}PASSO 1: Verificação inicial${NC}"
    show_status
    
    # 2. Preparar ambiente
    echo -e "${PURPLE}PASSO 2: Preparação do ambiente${NC}"
    prepare_environment
    
    # 3. Perguntar sobre limpeza de dados
    local current_data=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT COUNT(*) FROM vehicle_flow;" 2>/dev/null | grep -E "^\s*[0-9]+" | tr -d ' ' || echo "0")
    
    if [[ "$current_data" != "0" ]]; then
        echo -e "${YELLOW}⚠️ Encontrados $current_data registros no banco.${NC}"
        echo -e "${YELLOW}Deseja limpar os dados antes da simulação? (s/N)${NC}"
        read -r response
        
        if [[ "$response" =~ ^[SsYy]$ ]]; then
            echo -e "${PURPLE}PASSO 3: Limpeza de dados${NC}"
            clean_data
        else
            echo -e "${YELLOW}⚠️ Mantendo dados existentes${NC}"
            echo ""
        fi
    else
        echo -e "${GREEN}✅ Banco está limpo${NC}"
        echo ""
    fi
    
    # 4. Executar simulação
    echo -e "${PURPLE}PASSO 4: Execução da simulação${NC}"
    if run_simulation; then
        # 5. Mostrar opções de análise
        show_analysis_options
    else
        echo -e "${RED}❌ Falha na execução da simulação${NC}"
        return 1
    fi
}

# Função principal
main() {
    case "${1:-main}" in
        clean)
            prepare_environment
            clean_data
            run_simulation
            show_analysis_options
            ;;
        reset)
            reset_system
            run_simulation
            show_analysis_options
            ;;
        status)
            show_status
            ;;
        help|--help|-h)
            show_help
            ;;
        main)
            run_main_workflow
            ;;
        *)
            echo -e "${RED}❌ Opção inválida: $1${NC}"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# Verificar dependências
check_dependencies() {
    local missing=()
    
    [[ ! -f "./manage_cassandra.sh" ]] && missing+=("manage_cassandra.sh")
    [[ ! -f "./check_cassandra_data.sh" ]] && missing+=("check_cassandra_data.sh")
    [[ ! -f "./build-and-run.sh" ]] && missing+=("build-and-run.sh")
    
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo -e "${RED}❌ Arquivos ausentes:${NC}"
        printf '  %s\n' "${missing[@]}"
        echo ""
        echo -e "${YELLOW}💡 Certifique-se de executar no diretório raiz do projeto${NC}"
        exit 1
    fi
}

# Executar
check_dependencies
main "$@"