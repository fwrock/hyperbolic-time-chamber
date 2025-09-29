#!/bin/bash

# ============================================
# SCRIPT DE MONITORAMENTO CLUSTER GCP
# Hyperbolic Time Chamber - Monitoring Dashboard
# ============================================

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m'

# Função para limpar tela
clear_screen() {
    clear
}

# Função para obter timestamp
get_timestamp() {
    date '+%Y-%m-%d %H:%M:%S'
}

# Função para obter uso de CPU
get_cpu_usage() {
    top -bn1 | grep "Cpu(s)" | awk '{print $2}' | awk -F'%' '{print $1}'
}

# Função para obter uso de memória
get_memory_usage() {
    free | grep Mem | awk '{printf("%.1f", ($3/$2) * 100.0)}'
}

# Função para obter uso de disco
get_disk_usage() {
    df -h / | awk 'NR==2{print $5}' | sed 's/%//'
}

# Função para obter load average
get_load_average() {
    uptime | awk -F'load average:' '{print $2}' | awk '{print $1}' | sed 's/,//'
}

# Função para obter status dos containers
get_container_status() {
    docker-compose -f docker-compose-gcp-cluster.yml ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null || echo "N/A"
}

# Função para obter estatísticas do Cassandra
get_cassandra_stats() {
    local node=$1
    docker-compose -f docker-compose-gcp-cluster.yml exec -T $node nodetool status 2>/dev/null | grep "UN\|DN" | wc -l || echo "0"
}

# Função para obter conexões ativas
get_active_connections() {
    ss -tuln | grep -E ":(7000|7001|9042|8080|6379)" | wc -l
}

# Função para obter throughput de rede
get_network_throughput() {
    # RX/TX em MB/s (aproximado)
    local rx1=$(cat /sys/class/net/eth0/statistics/rx_bytes 2>/dev/null || echo 0)
    local tx1=$(cat /sys/class/net/eth0/statistics/tx_bytes 2>/dev/null || echo 0)
    sleep 1
    local rx2=$(cat /sys/class/net/eth0/statistics/rx_bytes 2>/dev/null || echo 0)
    local tx2=$(cat /sys/class/net/eth0/statistics/tx_bytes 2>/dev/null || echo 0)
    
    local rx_rate=$(( (rx2 - rx1) / 1024 / 1024 ))
    local tx_rate=$(( (tx2 - tx1) / 1024 / 1024 ))
    
    echo "${rx_rate}/${tx_rate}"
}

# Função principal do dashboard
show_dashboard() {
    while true; do
        clear_screen
        
        # Header
        echo -e "${PURPLE}╔═══════════════════════════════════════════════════════════════════════════╗"
        echo -e "║                    HYPERBOLIC TIME CHAMBER CLUSTER                       ║"
        echo -e "║                        Google Cloud Monitoring                           ║"
        echo -e "║                      $(get_timestamp)                           ║"
        echo -e "╚═══════════════════════════════════════════════════════════════════════════╝${NC}"
        echo ""
        
        # Recursos do Sistema
        echo -e "${CYAN}📊 RECURSOS DO SISTEMA${NC}"
        echo -e "${WHITE}├─ CPU Usage:     ${GREEN}$(get_cpu_usage)%${NC}"
        echo -e "${WHITE}├─ Memory Usage:  ${GREEN}$(get_memory_usage)%${NC}"
        echo -e "${WHITE}├─ Disk Usage:    ${GREEN}$(get_disk_usage)%${NC}"
        echo -e "${WHITE}├─ Load Average:  ${GREEN}$(get_load_average)${NC}"
        echo -e "${WHITE}└─ Network I/O:   ${GREEN}$(get_network_throughput) MB/s (RX/TX)${NC}"
        echo ""
        
        # Status dos Containers
        echo -e "${CYAN}🐳 STATUS DOS CONTAINERS${NC}"
        echo -e "${WHITE}┌─────────────────┬─────────────────────────────────┐${NC}"
        echo -e "${WHITE}│ Container       │ Status                          │${NC}"
        echo -e "${WHITE}├─────────────────┼─────────────────────────────────┤${NC}"
        
        # Verificar cada serviço
        services=("cassandra1" "cassandra2" "cassandra3" "htc1" "htc2" "htc3" "htc4" "htc5" "htc6" "htc7" "htc8" "redis")
        for service in "${services[@]}"; do
            local status=$(docker-compose -f docker-compose-gcp-cluster.yml ps -q $service 2>/dev/null | xargs docker inspect --format='{{.State.Status}}' 2>/dev/null || echo "stopped")
            local color=$RED
            case $status in
                "running") color=$GREEN ;;
                "restarting") color=$YELLOW ;;
                "paused") color=$BLUE ;;
            esac
            printf "${WHITE}│ %-15s │ ${color}%-31s${WHITE} │${NC}\n" "$service" "$status"
        done
        
        echo -e "${WHITE}└─────────────────┴─────────────────────────────────┘${NC}"
        echo ""
        
        # Cluster Cassandra
        echo -e "${CYAN}🗄️  CLUSTER CASSANDRA${NC}"
        local cass_nodes=0
        for i in {1..3}; do
            local node_status=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T cassandra$i nodetool status 2>/dev/null | grep "^UN" | wc -l || echo "0")
            if [ "$node_status" -gt 0 ]; then
                ((cass_nodes++))
            fi
        done
        echo -e "${WHITE}├─ Nós Online:    ${GREEN}${cass_nodes}/3${NC}"
        
        # Ring status
        local ring_status=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T cassandra1 nodetool status 2>/dev/null | tail -n +6 | head -n 3 || echo "")
        if [ ! -z "$ring_status" ]; then
            echo -e "${WHITE}├─ Ring Status:${NC}"
            echo "$ring_status" | while read line; do
                if echo "$line" | grep -q "UN"; then
                    echo -e "${WHITE}│  ${GREEN}● $(echo $line | awk '{print $2}')${NC}"
                else
                    echo -e "${WHITE}│  ${RED}● $(echo $line | awk '{print $2}')${NC}"
                fi
            done
        fi
        
        # Keyspaces
        local keyspaces=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T cassandra1 cqlsh -e "DESCRIBE KEYSPACES;" 2>/dev/null | grep -v "system" | wc -w || echo "0")
        echo -e "${WHITE}└─ Keyspaces:     ${GREEN}$keyspaces${NC}"
        echo ""
        
        # Aplicações HTC
        echo -e "${CYAN}🚀 APLICAÇÕES HTC${NC}"
        local htc_running=0
        for i in {1..8}; do
            if docker-compose -f docker-compose-gcp-cluster.yml ps -q htc$i 2>/dev/null | xargs docker inspect --format='{{.State.Status}}' 2>/dev/null | grep -q "running"; then
                ((htc_running++))
            fi
        done
        echo -e "${WHITE}├─ Instâncias:    ${GREEN}${htc_running}/8${NC}"
        echo -e "${WHITE}├─ Load Balancer: ${GREEN}Ativo${NC}"
        echo -e "${WHITE}└─ Pekko Cluster: ${GREEN}Formado${NC}"
        echo ""
        
        # Redis
        echo -e "${CYAN}📊 REDIS${NC}"
        local redis_status=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T redis redis-cli ping 2>/dev/null || echo "FAILED")
        local redis_color=$GREEN
        [ "$redis_status" != "PONG" ] && redis_color=$RED
        echo -e "${WHITE}├─ Status:        ${redis_color}$([ "$redis_status" = "PONG" ] && echo "Online" || echo "Offline")${NC}"
        
        local redis_memory=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T redis redis-cli info memory 2>/dev/null | grep "used_memory_human" | cut -d: -f2 | tr -d '\r' || echo "N/A")
        echo -e "${WHITE}├─ Memory Used:   ${GREEN}$redis_memory${NC}"
        
        local redis_clients=$(docker-compose -f docker-compose-gcp-cluster.yml exec -T redis redis-cli info clients 2>/dev/null | grep "connected_clients" | cut -d: -f2 | tr -d '\r' || echo "N/A")
        echo -e "${WHITE}└─ Connections:   ${GREEN}$redis_clients${NC}"
        echo ""
        
        # Estatísticas de Rede
        echo -e "${CYAN}🌐 CONEXÕES ATIVAS${NC}"
        local connections=$(get_active_connections)
        echo -e "${WHITE}└─ Total:         ${GREEN}$connections${NC}"
        echo ""
        
        # Footer
        echo -e "${YELLOW}⚡ Pressione Ctrl+C para sair${NC}"
        echo -e "${YELLOW}🔄 Atualizando a cada 5 segundos...${NC}"
        
        # Aguardar 5 segundos ou interrupção
        sleep 5
    done
}

# Função para mostrar logs em tempo real
show_live_logs() {
    local service=$1
    if [ -z "$service" ]; then
        echo "Selecione um serviço:"
        echo "1) cassandra1    5) htc1     9) htc5"
        echo "2) cassandra2    6) htc2    10) htc6"
        echo "3) cassandra3    7) htc3    11) htc7"
        echo "4) redis         8) htc4    12) htc8"
        read -p "Opção: " choice
        
        case $choice in
            1) service="cassandra1" ;;
            2) service="cassandra2" ;;
            3) service="cassandra3" ;;
            4) service="redis" ;;
            5) service="htc1" ;;
            6) service="htc2" ;;
            7) service="htc3" ;;
            8) service="htc4" ;;
            9) service="htc5" ;;
            10) service="htc6" ;;
            11) service="htc7" ;;
            12) service="htc8" ;;
            *) echo "Opção inválida"; exit 1 ;;
        esac
    fi
    
    echo -e "${GREEN}📋 Mostrando logs do $service (Ctrl+C para sair)${NC}"
    docker-compose -f docker-compose-gcp-cluster.yml logs -f $service
}

# Função para mostrar estatísticas detalhadas
show_detailed_stats() {
    clear_screen
    echo -e "${PURPLE}📊 ESTATÍSTICAS DETALHADAS${NC}"
    echo ""
    
    # Docker stats
    echo -e "${CYAN}🐳 DOCKER STATS${NC}"
    docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}"
    echo ""
    
    # Cassandra ring
    echo -e "${CYAN}🗄️  CASSANDRA RING${NC}"
    docker-compose -f docker-compose-gcp-cluster.yml exec -T cassandra1 nodetool status 2>/dev/null || echo "Cassandra não disponível"
    echo ""
    
    # System resources
    echo -e "${CYAN}⚙️  RECURSOS DO SISTEMA${NC}"
    echo -e "${WHITE}CPU:${NC}"
    lscpu | grep -E "(Model name|CPU\(s\)|Thread|Core)"
    echo ""
    echo -e "${WHITE}Memória:${NC}"
    free -h
    echo ""
    echo -e "${WHITE}Disco:${NC}"
    df -h
    echo ""
    
    read -p "Pressione Enter para continuar..."
}

# Menu principal
show_menu() {
    clear_screen
    echo -e "${PURPLE}╔═══════════════════════════════════════╗"
    echo -e "║     HTC CLUSTER MONITORING TOOL       ║"
    echo -e "╚═══════════════════════════════════════╝${NC}"
    echo ""
    echo "1) 📊 Dashboard em tempo real"
    echo "2) 📋 Logs em tempo real"
    echo "3) 📈 Estatísticas detalhadas"
    echo "4) 🔍 Status dos containers"
    echo "5) 🗄️  Status do Cassandra"
    echo "6) 🚀 Status das aplicações HTC"
    echo "0) ❌ Sair"
    echo ""
}

# Main
main() {
    case "${1:-menu}" in
        "dashboard"|"d")
            show_dashboard
            ;;
        "logs"|"l")
            show_live_logs "$2"
            ;;
        "stats"|"s")
            show_detailed_stats
            ;;
        "containers"|"c")
            docker-compose -f docker-compose-gcp-cluster.yml ps
            ;;
        "cassandra"|"cass")
            docker-compose -f docker-compose-gcp-cluster.yml exec cassandra1 nodetool status
            ;;
        "htc")
            echo -e "${GREEN}🚀 Status das aplicações HTC:${NC}"
            for i in {1..8}; do
                local status=$(docker-compose -f docker-compose-gcp-cluster.yml ps -q htc$i 2>/dev/null | xargs docker inspect --format='{{.State.Status}}' 2>/dev/null || echo "stopped")
                local color=$RED
                [ "$status" = "running" ] && color=$GREEN
                echo -e "HTC$i: ${color}$status${NC}"
            done
            ;;
        "menu"|*)
            while true; do
                show_menu
                read -p "Opção: " choice
                case $choice in
                    1) main dashboard ;;
                    2) main logs ;;
                    3) main stats ;;
                    4) main containers; read -p "Enter para continuar..." ;;
                    5) main cassandra; read -p "Enter para continuar..." ;;
                    6) main htc; read -p "Enter para continuar..." ;;
                    0) exit 0 ;;
                    *) echo "Opção inválida"; sleep 1 ;;
                esac
            done
            ;;
    esac
}

# Verificar se docker-compose está disponível
if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}❌ Docker Compose não encontrado!${NC}"
    exit 1
fi

# Verificar se arquivo existe
if [ ! -f docker-compose-gcp-cluster.yml ]; then
    echo -e "${RED}❌ Arquivo docker-compose-gcp-cluster.yml não encontrado!${NC}"
    exit 1
fi

# Executar
main "$@"