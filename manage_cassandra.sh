#!/bin/bash

# Script para gerenciar Cassandra - Subir, limpar dados e preparar para simulação
# Uso: ./manage_cassandra.sh [start|stop|clean|reset|status]

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuração
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
CASSANDRA_CONTAINER="cassandra"
CASSANDRA_PORT="9042"
CASSANDRA_HOST="localhost"

# Função para mostrar help
show_help() {
    echo -e "${BLUE}🗄️ Gerenciador do Cassandra para HTC Simulator${NC}"
    echo ""
    echo "Uso: $0 [COMANDO]"
    echo ""
    echo "COMANDOS:"
    echo "  start     - Subir o Cassandra via Docker Compose"
    echo "  stop      - Parar o Cassandra"
    echo "  clean     - Limpar todos os dados das tabelas"
    echo "  reset     - Parar, limpar volumes e subir limpo"
    echo "  status    - Verificar status do Cassandra"
    echo "  init      - Inicializar schema (executar uma vez)"
    echo "  help      - Mostrar esta ajuda"
    echo ""
    echo "WORKFLOW RECOMENDADO:"
    echo "  1. $0 start     # Subir Cassandra"
    echo "  2. $0 clean     # Limpar dados antigos"
    echo "  3. ./build-and-run.sh  # Executar simulação"
    echo ""
    echo "EXEMPLOS:"
    echo "  # Preparar para nova simulação"
    echo "  $0 reset"
    echo "  ./build-and-run.sh"
    echo ""
    echo "  # Apenas limpar dados"
    echo "  $0 clean"
    echo "  ./build-and-run.sh"
}

# Função para verificar se Docker está rodando
check_docker() {
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}❌ Docker não encontrado. Instale o Docker primeiro.${NC}"
        exit 1
    fi
    
    if ! docker info &> /dev/null; then
        echo -e "${RED}❌ Docker não está rodando. Inicie o Docker primeiro.${NC}"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        echo -e "${RED}❌ Docker Compose não encontrado.${NC}"
        exit 1
    fi
}

# Função para verificar se arquivo docker-compose existe
check_compose_file() {
    if [[ ! -f "$DOCKER_COMPOSE_FILE" ]]; then
        echo -e "${RED}❌ Arquivo docker-compose.yml não encontrado em: $DOCKER_COMPOSE_FILE${NC}"
        echo -e "${YELLOW}💡 Certifique-se de executar este script no diretório raiz do projeto${NC}"
        exit 1
    fi
}

# Função para esperar Cassandra ficar pronto
wait_for_cassandra() {
    echo -e "${BLUE}⏳ Aguardando Cassandra ficar disponível...${NC}"
    local max_attempts=30
    local attempt=1
    
    while [[ $attempt -le $max_attempts ]]; do
        if docker exec cassandra cqlsh -e "DESCRIBE KEYSPACES;" &>/dev/null; then
            echo -e "${GREEN}✅ Cassandra está pronto!${NC}"
            return 0
        fi
        
        echo -e "${YELLOW}   Tentativa $attempt/$max_attempts...${NC}"
        sleep 5
        ((attempt++))
    done
    
    echo -e "${RED}❌ Timeout: Cassandra não ficou disponível após $((max_attempts * 5)) segundos${NC}"
    return 1
}

# Função para verificar status do Cassandra
check_status() {
    echo -e "${BLUE}🔍 Verificando status do Cassandra...${NC}"
    
    # Verificar se container está rodando
    if docker ps --format "table {{.Names}}\t{{.Status}}" | grep -q "cassandra"; then
        echo -e "${GREEN}✅ Container Cassandra está rodando${NC}"
        
        # Verificar conectividade
        if docker exec cassandra cqlsh -e "DESCRIBE KEYSPACES;" &>/dev/null; then
            echo -e "${GREEN}✅ Cassandra está acessível via CQL${NC}"
            
            # Mostrar informações do keyspace
            if docker exec cassandra cqlsh -e "USE htc_simulation; DESCRIBE TABLES;" 2>/dev/null | grep -q "vehicle_flow"; then
                echo -e "${GREEN}✅ Schema HTC está configurado${NC}"
                
                # Contar registros
                local count=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT COUNT(*) FROM vehicle_flow;" 2>/dev/null | grep -E "^\s*[0-9]+" | tr -d ' ')
                echo -e "${BLUE}📊 Registros na tabela vehicle_flow: ${count:-0}${NC}"
            else
                echo -e "${YELLOW}⚠️ Schema HTC não encontrado${NC}"
            fi
        else
            echo -e "${RED}❌ Cassandra não está respondendo${NC}"
        fi
    else
        echo -e "${RED}❌ Container Cassandra não está rodando${NC}"
        
        # Verificar se container existe mas está parado
        if docker ps -a --format "table {{.Names}}\t{{.Status}}" | grep -q "cassandra"; then
            echo -e "${YELLOW}⚠️ Container existe mas está parado${NC}"
        else
            echo -e "${YELLOW}⚠️ Container não existe${NC}"
        fi
    fi
}

# Função para subir Cassandra
start_cassandra() {
    echo -e "${BLUE}🚀 Iniciando Cassandra...${NC}"
    
    check_docker
    check_compose_file
    
    # Usar docker-compose ou docker compose conforme disponível
    if command -v docker-compose &> /dev/null; then
        docker-compose -f "$DOCKER_COMPOSE_FILE" up -d cassandra
    else
        docker compose -f "$DOCKER_COMPOSE_FILE" up -d cassandra
    fi
    
    # Esperar ficar disponível
    if wait_for_cassandra; then
        echo -e "${GREEN}🎉 Cassandra iniciado com sucesso!${NC}"
        check_status
    else
        echo -e "${RED}❌ Falha ao iniciar Cassandra${NC}"
        exit 1
    fi
}

# Função para parar Cassandra
stop_cassandra() {
    echo -e "${BLUE}🛑 Parando Cassandra...${NC}"
    
    check_docker
    check_compose_file
    
    if command -v docker-compose &> /dev/null; then
        docker-compose -f "$DOCKER_COMPOSE_FILE" stop cassandra
    else
        docker compose -f "$DOCKER_COMPOSE_FILE" stop cassandra
    fi
    
    echo -e "${GREEN}✅ Cassandra parado${NC}"
}

# Função para limpar dados do Cassandra
clean_data() {
    echo -e "${BLUE}🧹 Limpando dados do Cassandra...${NC}"
    
    # Verificar se Cassandra está rodando
    if ! docker ps --format "{{.Names}}" | grep -q "cassandra"; then
        echo -e "${RED}❌ Cassandra não está rodando. Execute '$0 start' primeiro.${NC}"
        exit 1
    fi
    
    # Aguardar estar disponível
    if ! wait_for_cassandra; then
        echo -e "${RED}❌ Cassandra não está disponível${NC}"
        exit 1
    fi
    
    echo -e "${YELLOW}⚠️ Isso irá apagar TODOS os dados das tabelas!${NC}"
    echo -e "${YELLOW}⚠️ Pressione Ctrl+C nos próximos 5 segundos para cancelar...${NC}"
    sleep 5
    
    echo -e "${BLUE}🗑️ Limpando tabela vehicle_flow...${NC}"
    
    # Limpar dados da tabela principal
    docker exec cassandra cqlsh -e "
        USE htc_simulation;
        TRUNCATE vehicle_flow;
    " 2>/dev/null || {
        echo -e "${YELLOW}⚠️ Tabela vehicle_flow não existe ou erro ao limpar${NC}"
        echo -e "${BLUE}💡 Tentando inicializar schema...${NC}"
        init_schema
        return
    }
    
    # Verificar se limpeza funcionou
    local count=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT COUNT(*) FROM vehicle_flow;" 2>/dev/null | grep -E "^\s*[0-9]+" | tr -d ' ')
    
    if [[ "$count" == "0" ]]; then
        echo -e "${GREEN}✅ Dados limpos com sucesso!${NC}"
        echo -e "${GREEN}📊 Tabela vehicle_flow: 0 registros${NC}"
    else
        echo -e "${RED}❌ Erro ao limpar dados${NC}"
        exit 1
    fi
}

# Função para resetar completamente (parar, limpar volumes, subir)
reset_cassandra() {
    echo -e "${BLUE}♻️ Resetando Cassandra completamente...${NC}"
    
    check_docker
    check_compose_file
    
    echo -e "${YELLOW}⚠️ Isso irá apagar TODOS os dados e volumes!${NC}"
    echo -e "${YELLOW}⚠️ Pressione Ctrl+C nos próximos 5 segundos para cancelar...${NC}"
    sleep 5
    
    # Parar e remover containers e volumes
    echo -e "${BLUE}🛑 Parando e removendo containers...${NC}"
    if command -v docker-compose &> /dev/null; then
        docker-compose -f "$DOCKER_COMPOSE_FILE" down -v
    else
        docker compose -f "$DOCKER_COMPOSE_FILE" down -v
    fi
    
    # Remover volumes órfãos relacionados ao Cassandra
    echo -e "${BLUE}🗑️ Limpando volumes...${NC}"
    docker volume ls -q | grep -E "(cassandra|htc)" | xargs docker volume rm 2>/dev/null || true
    
    # Subir novamente
    echo -e "${BLUE}🚀 Subindo Cassandra limpo...${NC}"
    start_cassandra
    
    # Inicializar schema
    init_schema
}

# Função para inicializar schema
init_schema() {
    echo -e "${BLUE}🏗️ Inicializando schema do HTC...${NC}"
    
    if ! wait_for_cassandra; then
        echo -e "${RED}❌ Cassandra não está disponível${NC}"
        exit 1
    fi
    
    # Verificar se existe script de inicialização
    local init_script="$SCRIPT_DIR/cassandra-init/init.cql"
    
    if [[ -f "$init_script" ]]; then
        echo -e "${BLUE}📋 Executando script de inicialização...${NC}"
        docker exec -i cassandra cqlsh < "$init_script"
    else
        echo -e "${BLUE}📋 Criando schema básico...${NC}"
        docker exec cassandra cqlsh -e "
            CREATE KEYSPACE IF NOT EXISTS htc_simulation 
            WITH REPLICATION = {
                'class': 'SimpleStrategy',
                'replication_factor': 1
            };
            
            USE htc_simulation;
            
            CREATE TABLE IF NOT EXISTS vehicle_flow (
                car_id text,
                link_id text,
                timestamp double,
                direction text,
                lane int,
                event_type text,
                tick bigint,
                data text,
                PRIMARY KEY (car_id, timestamp, link_id)
            );
            
            CREATE INDEX IF NOT EXISTS vehicle_flow_link_idx ON vehicle_flow (link_id);
            CREATE INDEX IF NOT EXISTS vehicle_flow_tick_idx ON vehicle_flow (tick);
            CREATE INDEX IF NOT EXISTS vehicle_flow_event_idx ON vehicle_flow (event_type);
        "
    fi
    
    echo -e "${GREEN}✅ Schema inicializado!${NC}"
}

# Função principal
main() {
    case "${1:-help}" in
        start)
            start_cassandra
            ;;
        stop)
            stop_cassandra
            ;;
        clean)
            clean_data
            ;;
        reset)
            reset_cassandra
            ;;
        status)
            check_status
            ;;
        init)
            init_schema
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            echo -e "${RED}❌ Comando inválido: $1${NC}"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# Executar função principal
main "$@"