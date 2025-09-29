#!/bin/bash

# ============================================
# SCRIPT DE DEPLOY PARA CLUSTER GCP
# Hyperbolic Time Chamber - Cluster Multi-Node
# VM: n2-highmem-128 (128 vCPUs, 512GB RAM)
# ============================================

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Função para logging
log() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[WARN] $1${NC}"
}

error() {
    echo -e "${RED}[ERROR] $1${NC}"
}

info() {
    echo -e "${BLUE}[INFO] $1${NC}"
}

# Banner
echo -e "${PURPLE}"
cat << 'EOF'
╔═══════════════════════════════════════════════════════╗
║              HYPERBOLIC TIME CHAMBER                  ║
║           Google Cloud Cluster Deployment            ║
║                                                       ║
║  🌩️  128 vCPUs • 512GB RAM • Multi-Node Cluster     ║
║  🗄️  3x Cassandra Nodes                              ║
║  🚀  8x HTC Application Nodes                         ║
║  📊  Redis + Monitoring Stack                         ║
╚═══════════════════════════════════════════════════════╝
EOF
echo -e "${NC}"

# Verificar se estamos em uma VM do Google Cloud
check_gcp_vm() {
    log "Verificando ambiente Google Cloud..."
    
    # Verificar metadados do GCP
    if curl -s -f -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone &>/dev/null; then
        ZONE=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/zone | cut -d/ -f4)
        INSTANCE_NAME=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/name)
        MACHINE_TYPE=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/machine-type | cut -d/ -f4)
        
        info "🌍 Zona GCP: $ZONE"
        info "🖥️  Instância: $INSTANCE_NAME"
        info "⚙️  Tipo de Máquina: $MACHINE_TYPE"
        
        # Verificar se é uma instância adequada
        if [[ $MACHINE_TYPE == *"n2-"* ]]; then
            log "✅ Instância Google Cloud detectada!"
        else
            warn "⚠️  Tipo de instância não otimizado. Recomendado: n2-highmem-128"
        fi
    else
        warn "⚠️  Não foi possível detectar metadados do GCP. Continuando..."
    fi
}

# Verificar recursos do sistema
check_system_resources() {
    log "Verificando recursos do sistema..."
    
    # CPU
    CPU_COUNT=$(nproc)
    info "🖥️  CPUs disponíveis: $CPU_COUNT"
    
    if [ $CPU_COUNT -lt 64 ]; then
        warn "⚠️  Sistema com poucos CPUs. Cluster otimizado para 128+ vCPUs"
    fi
    
    # Memória
    TOTAL_MEM=$(free -g | awk '/^Mem:/{print $2}')
    info "🧠 Memória total: ${TOTAL_MEM}GB"
    
    if [ $TOTAL_MEM -lt 256 ]; then
        warn "⚠️  Memória limitada. Cluster otimizado para 512GB+"
    fi
    
    # Disco
    DISK_SPACE=$(df -h / | awk 'NR==2{print $4}')
    info "💾 Espaço em disco disponível: $DISK_SPACE"
    
    # Load average
    LOAD_AVG=$(uptime | awk -F'load average:' '{print $2}')
    info "📊 Load average: $LOAD_AVG"
}

# Instalar dependências
install_dependencies() {
    log "Instalando dependências..."
    
    # Atualizar sistema
    sudo apt-get update -y
    
    # Docker
    if ! command -v docker &> /dev/null; then
        info "📦 Instalando Docker..."
        curl -fsSL https://get.docker.com -o get-docker.sh
        sudo sh get-docker.sh
        sudo usermod -aG docker $USER
        rm get-docker.sh
    else
        info "✅ Docker já instalado"
    fi
    
    # Docker Compose
    if ! command -v docker-compose &> /dev/null; then
        info "📦 Instalando Docker Compose..."
        sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
        sudo chmod +x /usr/local/bin/docker-compose
    else
        info "✅ Docker Compose já instalado"
    fi
    
    # Ferramentas de sistema
    sudo apt-get install -y \
        htop \
        iotop \
        nethogs \
        ncdu \
        tree \
        jq \
        curl \
        wget \
        unzip \
        git
}

# Otimizar sistema para produção
optimize_system() {
    log "Otimizando sistema para produção..."
    
    # Configurações de kernel
    info "⚙️  Aplicando configurações de kernel..."
    cat << 'EOF' | sudo tee /etc/sysctl.d/99-htc-cluster.conf
# Network optimizations
net.core.rmem_max = 134217728
net.core.wmem_max = 134217728
net.ipv4.tcp_rmem = 4096 65536 134217728
net.ipv4.tcp_wmem = 4096 65536 134217728
net.core.netdev_max_backlog = 5000
net.ipv4.tcp_congestion_control = bbr

# File descriptor limits
fs.file-max = 2097152

# Virtual memory settings
vm.max_map_count = 1048575
vm.swappiness = 1
vm.dirty_ratio = 15
vm.dirty_background_ratio = 5

# Cassandra specific
net.ipv4.tcp_keepalive_time = 60
net.ipv4.tcp_keepalive_probes = 3
net.ipv4.tcp_keepalive_intvl = 10
EOF
    
    sudo sysctl -p /etc/sysctl.d/99-htc-cluster.conf
    
    # Configurações de limites
    info "⚙️  Configurando limites de sistema..."
    cat << 'EOF' | sudo tee -a /etc/security/limits.conf
* soft nofile 1048576
* hard nofile 1048576
* soft memlock unlimited
* hard memlock unlimited
* soft nproc 32768
* hard nproc 32768
EOF
    
    # Desabilitar swap se existir
    if [ $(swapon --show | wc -l) -gt 0 ]; then
        warn "⚠️  Desabilitando swap para melhor performance..."
        sudo swapoff -a
        sudo sed -i '/swap/d' /etc/fstab
    fi
    
    # Configurar transparent huge pages
    echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
    echo never | sudo tee /sys/kernel/mm/transparent_hugepage/defrag
}

# Preparar diretórios
prepare_directories() {
    log "Preparando estrutura de diretórios..."
    
    # Criar diretórios necessários
    mkdir -p logs/{cassandra1,cassandra2,cassandra3,htc{1..8},redis}
    mkdir -p data/{cassandra1,cassandra2,cassandra3,redis}
    mkdir -p snapshots
    
    # Verificar se os arquivos de configuração existem
    if [ ! -f docker-compose-gcp-cluster.yml ]; then
        error "❌ Arquivo docker-compose-gcp-cluster.yml não encontrado!"
        exit 1
    fi
    
    if [ ! -d cassandra-config ]; then
        error "❌ Diretório cassandra-config não encontrado!"
        exit 1
    fi
    
    info "✅ Estrutura de diretórios criada"
}

# Verificar conectividade de rede
check_network() {
    log "Verificando conectividade de rede..."
    
    # Testar DNS
    if nslookup google.com &>/dev/null; then
        info "✅ DNS funcionando"
    else
        error "❌ Problemas com DNS"
        exit 1
    fi
    
    # Verificar portas necessárias
    PORTS=(7000 7001 9042 9160 8080 6379 8181)
    for port in "${PORTS[@]}"; do
        if ss -tuln | grep ":$port " &>/dev/null; then
            warn "⚠️  Porta $port já está em uso"
        fi
    done
}

# Deploy do cluster
deploy_cluster() {
    log "Iniciando deploy do cluster..."
    
    # Pull das imagens
    info "📥 Fazendo pull das imagens Docker..."
    docker-compose -f docker-compose-gcp-cluster.yml pull --parallel
    
    # Iniciar cluster
    info "🚀 Iniciando cluster (modo detached)..."
    docker-compose -f docker-compose-gcp-cluster.yml up -d
    
    # Aguardar inicialização
    log "⏳ Aguardando inicialização dos serviços..."
    sleep 30
    
    # Verificar status
    check_cluster_health
}

# Verificar saúde do cluster
check_cluster_health() {
    log "Verificando saúde do cluster..."
    
    # Cassandra nodes
    info "🗄️  Verificando nós Cassandra..."
    for i in {1..3}; do
        if docker-compose -f docker-compose-gcp-cluster.yml exec -T cassandra$i nodetool status &>/dev/null; then
            info "✅ Cassandra$i: Online"
        else
            warn "⚠️  Cassandra$i: Verificando..."
        fi
    done
    
    # HTC nodes
    info "🚀 Verificando nós HTC..."
    for i in {1..8}; do
        if docker-compose -f docker-compose-gcp-cluster.yml ps htc$i | grep "Up" &>/dev/null; then
            info "✅ HTC$i: Online"
        else
            warn "⚠️  HTC$i: Verificando..."
        fi
    done
    
    # Redis
    info "📊 Verificando Redis..."
    if docker-compose -f docker-compose-gcp-cluster.yml exec -T redis redis-cli ping | grep PONG &>/dev/null; then
        info "✅ Redis: Online"
    else
        warn "⚠️  Redis: Verificando..."
    fi
    
    # Mostrar status geral
    echo ""
    info "📊 Status geral do cluster:"
    docker-compose -f docker-compose-gcp-cluster.yml ps
}

# Mostrar informações do cluster
show_cluster_info() {
    log "Informações do cluster..."
    
    # Obter IP da máquina
    EXTERNAL_IP=$(curl -s -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip 2>/dev/null || hostname -I | awk '{print $1}')
    INTERNAL_IP=$(hostname -I | awk '{print $1}')
    
    echo ""
    echo -e "${CYAN}╔═══════════════════════════════════════════════╗"
    echo -e "║                CLUSTER INFO                   ║"
    echo -e "╠═══════════════════════════════════════════════╣"
    echo -e "║ 🌐 IP Externo:    ${EXTERNAL_IP:-N/A}                    ║"
    echo -e "║ 🏠 IP Interno:    ${INTERNAL_IP}                  ║"
    echo -e "║                                               ║"
    echo -e "║ 🗄️  Cassandra CQL: ${EXTERNAL_IP}:9042             ║"
    echo -e "║ 🚀 HTC Apps:      ${EXTERNAL_IP}:8080-8087       ║"
    echo -e "║ 📊 Redis:         ${EXTERNAL_IP}:6379             ║"
    echo -e "║ 🔧 Portainer:     ${EXTERNAL_IP}:9000             ║"
    echo -e "╚═══════════════════════════════════════════════╝${NC}"
    echo ""
}

# Comandos úteis
show_useful_commands() {
    log "Comandos úteis para gerenciar o cluster..."
    
    echo -e "${YELLOW}"
    cat << 'EOF'
📋 COMANDOS ÚTEIS:

# Ver logs do cluster
docker-compose -f docker-compose-gcp-cluster.yml logs -f

# Ver logs de um serviço específico
docker-compose -f docker-compose-gcp-cluster.yml logs -f cassandra1

# Status do cluster Cassandra
docker-compose -f docker-compose-gcp-cluster.yml exec cassandra1 nodetool status

# Conectar ao CQL
docker-compose -f docker-compose-gcp-cluster.yml exec cassandra1 cqlsh

# Monitorar recursos
htop
docker stats

# Parar cluster
docker-compose -f docker-compose-gcp-cluster.yml down

# Restart do cluster
docker-compose -f docker-compose-gcp-cluster.yml restart

# Limpar tudo (CUIDADO!)
docker-compose -f docker-compose-gcp-cluster.yml down -v
docker system prune -af
EOF
    echo -e "${NC}"
}

# Menu interativo
show_menu() {
    echo ""
    echo -e "${PURPLE}Selecione uma opção:${NC}"
    echo "1) 🚀 Deploy completo (recomendado)"
    echo "2) 📦 Apenas instalar dependências"
    echo "3) ⚙️  Apenas otimizar sistema"
    echo "4) 🔍 Apenas verificar cluster"
    echo "5) ℹ️  Mostrar informações"
    echo "6) 📋 Mostrar comandos úteis"
    echo "7) 🛑 Parar cluster"
    echo "8) 🗑️  Limpar tudo"
    echo "0) ❌ Sair"
    echo ""
}

# Main
main() {
    case "${1:-menu}" in
        "deploy"|"full")
            check_gcp_vm
            check_system_resources
            install_dependencies
            optimize_system
            prepare_directories
            check_network
            deploy_cluster
            show_cluster_info
            show_useful_commands
            ;;
        "deps")
            install_dependencies
            ;;
        "optimize")
            optimize_system
            ;;
        "check")
            check_cluster_health
            ;;
        "info")
            show_cluster_info
            ;;
        "commands")
            show_useful_commands
            ;;
        "stop")
            docker-compose -f docker-compose-gcp-cluster.yml down
            ;;
        "clean")
            echo -e "${RED}⚠️  ATENÇÃO: Isso vai remover TODOS os dados!${NC}"
            read -p "Confirma? (digite 'yes'): " confirm
            if [ "$confirm" = "yes" ]; then
                docker-compose -f docker-compose-gcp-cluster.yml down -v
                docker system prune -af
                sudo rm -rf data/* logs/*
            fi
            ;;
        "menu"|*)
            while true; do
                show_menu
                read -p "Opção: " choice
                case $choice in
                    1) main deploy ;;
                    2) main deps ;;
                    3) main optimize ;;
                    4) main check ;;
                    5) main info ;;
                    6) main commands ;;
                    7) main stop ;;
                    8) main clean ;;
                    0) exit 0 ;;
                    *) error "Opção inválida" ;;
                esac
                echo ""
                read -p "Pressione Enter para continuar..."
            done
            ;;
    esac
}

# Verificar se está sendo executado como root
if [ "$EUID" -eq 0 ]; then
    warn "⚠️  Não execute como root. Use sudo quando necessário."
    exit 1
fi

# Executar
main "$@"