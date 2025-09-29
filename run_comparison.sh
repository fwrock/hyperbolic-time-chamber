#!/bin/bash

# Script para executar comparação entre simuladores
# Uso: ./run_comparison.sh [OPTIONS]

set -e  # Sair em caso de erro

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuração padrão
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
VENV_PATH="$PROJECT_ROOT/scripts/venv"
SCRIPTS_PATH="$PROJECT_ROOT/scripts"

# Função para mostrar help
show_help() {
    echo -e "${BLUE}🔄 Comparador de Simuladores HTC vs Referência${NC}"
    echo ""
    echo "Uso: $0 [OPÇÕES] <arquivo_xml_referencia>"
    echo ""
    echo "OPÇÕES:"
    echo "  -c, --cassandra          Usar dados do HTC via Cassandra"
    echo "  -f, --csv FILE           Usar dados do HTC via arquivo CSV"
    echo "  -l, --limit NUM          Limite de registros do Cassandra (padrão: todos)"
    echo "  -o, --output DIR         Diretório de saída"
    echo "  -s, --sample             Criar arquivo XML de exemplo"
    echo "  -h, --help               Mostrar esta ajuda"
    echo ""
    echo "EXEMPLOS:"
    echo "  # Comparar usando dados do Cassandra"
    echo "  $0 --cassandra reference_events.xml"
    echo ""
    echo "  # Comparar usando arquivo CSV"
    echo "  $0 --csv data/htc_output.csv reference_events.xml"
    echo ""
    echo "  # Criar arquivo de exemplo"
    echo "  $0 --sample"
    echo ""
    echo "  # Usar limite maior para Cassandra"
    echo "  $0 --cassandra --limit 5000 reference_events.xml"
}

# Função para verificar se o ambiente virtual existe
check_venv() {
    if [[ ! -d "$VENV_PATH" ]]; then
        echo -e "${RED}❌ Ambiente virtual não encontrado em: $VENV_PATH${NC}"
        echo -e "${YELLOW}💡 Execute primeiro: python -m venv venv && source venv/bin/activate && pip install -r requirements.txt${NC}"
        exit 1
    fi
}

# Função para ativar ambiente virtual
activate_venv() {
    echo -e "${BLUE}🐍 Ativando ambiente virtual...${NC}"
    source "$VENV_PATH/bin/activate"
    
    # Verificar se python está disponível
    if ! command -v python &> /dev/null; then
        echo -e "${RED}❌ Python não encontrado no ambiente virtual${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✅ Ambiente virtual ativo${NC}"
}

# Função para verificar dependências
check_dependencies() {
    echo -e "${BLUE}🔍 Verificando dependências...${NC}"
    
    # Verificar se o script de comparação existe
    if [[ ! -f "$SCRIPTS_PATH/compare_simulators.py" ]]; then
        echo -e "${RED}❌ Script de comparação não encontrado: $SCRIPTS_PATH/compare_simulators.py${NC}"
        exit 1
    fi
    
    # Verificar dependências Python básicas
    if ! python -c "import pandas, numpy, matplotlib" &> /dev/null; then
        echo -e "${RED}❌ Dependências Python ausentes${NC}"
        echo -e "${YELLOW}💡 Execute: pip install -r requirements.txt${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✅ Dependências verificadas${NC}"
}

# Função para verificar se Cassandra está rodando (se necessário)
check_cassandra() {
    if [[ "$USE_CASSANDRA" == "true" ]]; then
        echo -e "${BLUE}🔌 Verificando conexão com Cassandra...${NC}"
        
        # Tentar conectar via cqlsh (se disponível)
        if command -v cqlsh &> /dev/null; then
            if ! timeout 5 cqlsh -e "exit" &> /dev/null; then
                echo -e "${YELLOW}⚠️  Não foi possível conectar ao Cassandra${NC}"
                echo -e "${YELLOW}💡 Certifique-se de que o Cassandra está rodando${NC}"
            else
                echo -e "${GREEN}✅ Cassandra acessível${NC}"
            fi
        else
            echo -e "${YELLOW}⚠️  cqlsh não encontrado, pulando verificação do Cassandra${NC}"
        fi
    fi
}

# Função principal
main() {
    # Parse dos argumentos
    ARGS=()
    USE_CASSANDRA=false
    CSV_FILE=""
    XML_FILE=""
    LIMIT=999999999  # Limite muito alto para pegar todos os registros
    OUTPUT_DIR=""
    CREATE_SAMPLE=false
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            -c|--cassandra)
                USE_CASSANDRA=true
                ARGS+=("--htc-cassandra")
                shift
                ;;
            -f|--csv)
                CSV_FILE="$2"
                ARGS+=("--htc-csv" "$CSV_FILE")
                shift 2
                ;;
            -l|--limit)
                LIMIT="$2"
                ARGS+=("--limit" "$LIMIT")
                shift 2
                ;;
            -o|--output)
                OUTPUT_DIR="$2"
                ARGS+=("--output" "$OUTPUT_DIR")
                shift 2
                ;;
            -s|--sample)
                CREATE_SAMPLE=true
                ARGS+=("--create-sample")
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                if [[ "$1" != -* ]]; then
                    XML_FILE="$1"
                    ARGS+=("$XML_FILE")
                fi
                shift
                ;;
        esac
    done
    
    # Verificações iniciais
    check_venv
    activate_venv
    check_dependencies
    
    # Criar sample se solicitado
    if [[ "$CREATE_SAMPLE" == "true" ]]; then
        echo -e "${BLUE}📄 Criando arquivo XML de exemplo...${NC}"
        python "$SCRIPTS_PATH/compare_simulators.py" --create-sample
        exit 0
    fi
    
    # Validar argumentos
    if [[ -z "$XML_FILE" ]] && [[ "$CREATE_SAMPLE" == "false" ]]; then
        echo -e "${RED}❌ Arquivo XML de referência é obrigatório${NC}"
        echo ""
        show_help
        exit 1
    fi
    
    if [[ "$USE_CASSANDRA" == "false" ]] && [[ -z "$CSV_FILE" ]] && [[ "$CREATE_SAMPLE" == "false" ]]; then
        echo -e "${RED}❌ Deve especificar fonte de dados do HTC (--cassandra ou --csv)${NC}"
        echo ""
        show_help
        exit 1
    fi
    
    # Verificar se arquivos existem
    if [[ -n "$XML_FILE" ]] && [[ ! -f "$XML_FILE" ]]; then
        echo -e "${RED}❌ Arquivo XML não encontrado: $XML_FILE${NC}"
        exit 1
    fi
    
    if [[ -n "$CSV_FILE" ]] && [[ ! -f "$CSV_FILE" ]]; then
        echo -e "${RED}❌ Arquivo CSV não encontrado: $CSV_FILE${NC}"
        exit 1
    fi
    
    # Verificar Cassandra se necessário
    check_cassandra
    
    # Executar comparação
    echo -e "${BLUE}🚀 Iniciando comparação de simuladores...${NC}"
    echo -e "${BLUE}📊 Fonte HTC: ${NC}$(if [[ "$USE_CASSANDRA" == "true" ]]; then echo "Cassandra (limit: $LIMIT)"; else echo "CSV: $CSV_FILE"; fi)"
    echo -e "${BLUE}📄 Arquivo XML: ${NC}$XML_FILE"
    
    if [[ -n "$OUTPUT_DIR" ]]; then
        echo -e "${BLUE}📁 Diretório de saída: ${NC}$OUTPUT_DIR"
    fi
    
    echo ""
    
    # Executar o script Python
    cd "$PROJECT_ROOT"
    python "$SCRIPTS_PATH/compare_simulators.py" ${ARGS[@]}
    
    if [[ $? -eq 0 ]]; then
        echo ""
        echo -e "${GREEN}🎉 Comparação concluída com sucesso!${NC}"
        echo -e "${BLUE}📁 Verifique os resultados no diretório de saída${NC}"
    else
        echo ""
        echo -e "${RED}❌ Erro durante a comparação${NC}"
        exit 1
    fi
}

# Executar função principal
main "$@"