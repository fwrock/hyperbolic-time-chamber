#!/bin/bash

# Script de diagnóstico do HTC
# Identifica problemas de performance e configuração

set -e

echo "🔍 HTC - Diagnóstico de Sistema"
echo "================================"

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Função para imprimir com cores
print_status() {
    local status=$1
    local message=$2
    
    case $status in
        "OK")
            echo -e "${GREEN}✅ $message${NC}"
            ;;
        "WARNING")
            echo -e "${YELLOW}⚠️  $message${NC}"
            ;;
        "ERROR")
            echo -e "${RED}❌ $message${NC}"
            ;;
        "INFO")
            echo -e "${BLUE}ℹ️  $message${NC}"
            ;;
    esac
}

# Verificar recursos do sistema
check_system_resources() {
    echo -e "\n${BLUE}📊 Recursos do Sistema${NC}"
    echo "========================"
    
    # Memória
    local total_mem=$(free -g | awk '/^Mem:/{print $2}')
    local available_mem=$(free -g | awk '/^Mem:/{print $7}')
    local used_mem=$(free -g | awk '/^Mem:/{print $3}')
    
    if [ "$total_mem" -ge 8 ]; then
        print_status "OK" "Memória Total: ${total_mem}GB (Recomendado: 8GB+)"
    elif [ "$total_mem" -ge 4 ]; then
        print_status "WARNING" "Memória Total: ${total_mem}GB (Mínimo: 4GB, Recomendado: 8GB+)"
    else
        print_status "ERROR" "Memória Total: ${total_mem}GB (Insuficiente! Mínimo: 4GB)"
    fi
    
    print_status "INFO" "Memória Disponível: ${available_mem}GB"
    print_status "INFO" "Memória Usada: ${used_mem}GB"
    
    # CPU
    local cpu_cores=$(nproc)
    if [ "$cpu_cores" -ge 4 ]; then
        print_status "OK" "CPU Cores: $cpu_cores (Recomendado: 4+)"
    elif [ "$cpu_cores" -ge 2 ]; then
        print_status "WARNING" "CPU Cores: $cpu_cores (Mínimo: 2, Recomendado: 4+)"
    else
        print_status "ERROR" "CPU Cores: $cpu_cores (Insuficiente! Mínimo: 2)"
    fi
    
    # Espaço em disco
    local disk_space=$(df -h . | awk 'NR==2 {print $4}')
    local disk_space_gb=$(df -BG . | awk 'NR==2 {print $4}' | sed 's/G//')
    
    if [ "$disk_space_gb" -ge 10 ]; then
        print_status "OK" "Espaço em Disco: ${disk_space} disponível"
    elif [ "$disk_space_gb" -ge 5 ]; then
        print_status "WARNING" "Espaço em Disco: ${disk_space} disponível (Baixo!)"
    else
        print_status "ERROR" "Espaço em Disco: ${disk_space} disponível (Crítico!)"
    fi
}

# Verificar Docker
check_docker() {
    echo -e "\n${BLUE}🐳 Docker${NC}"
    echo "============"
    
    if command -v docker &> /dev/null; then
        local docker_version=$(docker --version | cut -d ' ' -f3 | cut -d ',' -f1)
        print_status "OK" "Docker instalado: v$docker_version"
        
        # Verificar se Docker daemon está rodando
        if docker info &> /dev/null; then
            print_status "OK" "Docker daemon rodando"
        else
            print_status "ERROR" "Docker daemon não está rodando!"
        fi
    else
        print_status "ERROR" "Docker não instalado!"
    fi
    
    # Verificar Docker Compose
    if docker compose version &> /dev/null; then
        local compose_version=$(docker compose version --short)
        print_status "OK" "Docker Compose instalado: v$compose_version"
    elif command -v docker-compose &> /dev/null; then
        local compose_version=$(docker-compose --version | cut -d ' ' -f4 | cut -d ',' -f1)
        print_status "OK" "Docker Compose instalado: v$compose_version (versão standalone)"
    else
        print_status "ERROR" "Docker Compose não instalado!"
    fi
}

# Verificar containers em execução
check_containers() {
    echo -e "\n${BLUE}📦 Containers${NC}"
    echo "==============="
    
    # Verificar se containers existem e estão rodando
    local containers=("htc-redis" "htc-cassandra-db" "node_1")
    
    for container in "${containers[@]}"; do
        if docker ps --format "table {{.Names}}" | grep -q "^$container$"; then
            print_status "OK" "$container: Rodando"
        elif docker ps -a --format "table {{.Names}}" | grep -q "^$container$"; then
            print_status "WARNING" "$container: Existe mas não está rodando"
        else
            print_status "INFO" "$container: Não existe"
        fi
    done
}

# Verificar conectividade dos serviços
check_services_connectivity() {
    echo -e "\n${BLUE}🔗 Conectividade dos Serviços${NC}"
    echo "==============================="
    
    # Verificar Redis
    if docker exec htc-redis redis-cli ping &> /dev/null; then
        print_status "OK" "Redis: Respondendo"
    else
        print_status "ERROR" "Redis: Não respondendo ou container parado"
    fi
    
    # Verificar Cassandra
    if docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES" &> /dev/null; then
        print_status "OK" "Cassandra: Respondendo"
    else
        print_status "ERROR" "Cassandra: Não respondendo ou container parado"
    fi
}

# Verificar logs recentes por problemas
check_recent_errors() {
    echo -e "\n${BLUE}📋 Análise de Logs Recentes${NC}"
    echo "=============================="
    
    # Função para contar erros nos logs
    count_errors_in_logs() {
        local container=$1
        local error_pattern=$2
        local description=$3
        
        if docker ps --format "table {{.Names}}" | grep -q "^$container$"; then
            local error_count=$(docker logs --tail 100 "$container" 2>&1 | grep -c "$error_pattern" || echo "0")
            
            if [ "$error_count" -gt 0 ]; then
                print_status "WARNING" "$description: $error_count ocorrências nos últimos 100 logs"
            else
                print_status "OK" "$description: Nenhum erro encontrado"
            fi
        else
            print_status "INFO" "$container não está rodando - análise de logs pulada"
        fi
    }
    
    # Verificar erros específicos
    count_errors_in_logs "htc-cassandra-db" "WriteTimeoutException" "Cassandra Write Timeout"
    count_errors_in_logs "htc-cassandra-db" "ReadTimeoutException" "Cassandra Read Timeout"
    count_errors_in_logs "node_1" "OutOfMemoryError" "Java OutOfMemory"
    count_errors_in_logs "node_1" "Exception" "Aplicação Java Exceptions"
}

# Verificar configurações de sistema
check_system_config() {
    echo -e "\n${BLUE}⚙️  Configurações do Sistema${NC}"
    echo "=============================="
    
    # Verificar vm.max_map_count (importante para Cassandra)
    local max_map_count=$(sysctl -n vm.max_map_count 2>/dev/null || echo "N/A")
    if [ "$max_map_count" != "N/A" ] && [ "$max_map_count" -ge 262144 ]; then
        print_status "OK" "vm.max_map_count: $max_map_count"
    else
        print_status "WARNING" "vm.max_map_count: $max_map_count (Recomendado: 262144+)"
    fi
    
    # Verificar swappiness
    local swappiness=$(sysctl -n vm.swappiness 2>/dev/null || echo "N/A")
    if [ "$swappiness" != "N/A" ] && [ "$swappiness" -le 10 ]; then
        print_status "OK" "vm.swappiness: $swappiness (Baixo - Bom para DB)"
    elif [ "$swappiness" != "N/A" ]; then
        print_status "WARNING" "vm.swappiness: $swappiness (Alto - Pode afetar performance do DB)"
    else
        print_status "INFO" "vm.swappiness: Não pode ser verificado"
    fi
}

# Verificar arquivos de configuração
check_config_files() {
    echo -e "\n${BLUE}📄 Arquivos de Configuração${NC}"
    echo "=============================="
    
    local config_files=(
        "docker-compose.yml:Configuração Docker padrão"
        "docker-compose-optimized.yml:Configuração Docker otimizada"
        "docker-compose-minimal.yml:Configuração Docker mínima"
        "cassandra-config/cassandra.yaml:Configuração Cassandra"
        "cassandra-config/jvm.options:Configuração JVM Cassandra"
    )
    
    for config_file in "${config_files[@]}"; do
        local file="${config_file%%:*}"
        local description="${config_file##*:}"
        
        if [ -f "$file" ]; then
            print_status "OK" "$description: Encontrado"
        else
            print_status "WARNING" "$description: Não encontrado ($file)"
        fi
    done
}

# Sugestões de otimização
show_optimization_suggestions() {
    echo -e "\n${BLUE}💡 Sugestões de Otimização${NC}"
    echo "============================"
    
    local total_mem=$(free -g | awk '/^Mem:/{print $2}')
    local cpu_cores=$(nproc)
    
    if [ "$total_mem" -lt 8 ]; then
        echo -e "${YELLOW}• Use o docker-compose-minimal.yml para sistemas com pouca memória${NC}"
        echo -e "${YELLOW}• Considere executar com menos veículos na simulação${NC}"
    fi
    
    if [ "$cpu_cores" -lt 4 ]; then
        echo -e "${YELLOW}• Reduza o paralelismo do Pekko no application.conf${NC}"
        echo -e "${YELLOW}• Use menos threads para compactação do Cassandra${NC}"
    fi
    
    echo -e "${GREEN}• Para melhor performance, use SSD em vez de HDD${NC}"
    echo -e "${GREEN}• Execute o script como root para aplicar otimizações completas${NC}"
    echo -e "${GREEN}• Monitore os logs com: docker logs -f [container-name]${NC}"
}

# Comando para verificar status dos serviços continuamente
monitor_services() {
    echo -e "\n${BLUE}📊 Monitor de Serviços (Pressione Ctrl+C para parar)${NC}"
    echo "======================================================="
    
    while true; do
        echo -e "\n$(date '+%Y-%m-%d %H:%M:%S')"
        echo "--------------------"
        
        # Status dos containers
        docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}" 2>/dev/null || echo "Nenhum container rodando"
        
        sleep 5
    done
}

# Menu principal
main() {
    case "${1:-all}" in
        "system")
            check_system_resources
            ;;
        "docker")
            check_docker
            ;;
        "containers")
            check_containers
            ;;
        "connectivity")
            check_services_connectivity
            ;;
        "logs")
            check_recent_errors
            ;;
        "config")
            check_config_files
            ;;
        "monitor")
            monitor_services
            ;;
        "all"|"")
            check_system_resources
            check_docker
            check_containers
            check_services_connectivity
            check_recent_errors
            check_system_config
            check_config_files
            show_optimization_suggestions
            ;;
        "help"|"-h"|"--help")
            echo "Uso: $0 [categoria]"
            echo ""
            echo "Categorias disponíveis:"
            echo "  all          - Executar todos os diagnósticos (padrão)"
            echo "  system       - Verificar recursos do sistema"
            echo "  docker       - Verificar instalação do Docker"
            echo "  containers   - Verificar status dos containers"
            echo "  connectivity - Testar conectividade dos serviços"
            echo "  logs         - Analisar logs recentes por erros"
            echo "  config       - Verificar arquivos de configuração"
            echo "  monitor      - Monitor contínuo dos serviços"
            echo "  help         - Mostrar esta ajuda"
            exit 0
            ;;
        *)
            echo "Categoria desconhecida: $1"
            echo "Use '$0 help' para ver as opções disponíveis"
            exit 1
            ;;
    esac
    
    echo -e "\n${GREEN}🎉 Diagnóstico concluído!${NC}"
    echo "Para mais detalhes sobre problemas específicos, execute:"
    echo "  docker logs [nome-do-container]"
    echo "  docker stats [nome-do-container]"
}

# Executar função principal
main "$@"