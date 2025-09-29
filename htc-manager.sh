#!/bin/bash

# HTC Management Script - Gerenciamento completo do sistema
# Facilita todas as operações do HTC com interface unificada

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Função para imprimir banner
print_banner() {
    echo -e "${CYAN}"
    echo "███████╗ ██╗████████╗ █████╗ "
    echo "██╔════╝ ██║╚══██╔══╝██╔══██╗"
    echo "███████╗ ██║   ██║   ██║  ██║"
    echo "╚════██║ ██║   ██║   ██║  ██║"
    echo "███████║ ██║   ██║   ╚█████╔╝"
    echo "╚══════╝ ╚═╝   ╚═╝    ╚════╝ "
    echo ""
    echo "🚀 HTC - Hyperbolic Time Chamber"
    echo "📊 Sistema Completo de Simulação e Análise"
    echo "=========================================="
    echo -e "${NC}"
}

# Função para mostrar menu principal
show_main_menu() {
    echo -e "${BLUE}📋 Menu Principal${NC}"
    echo "=================="
    echo "1.  🚀 Iniciar Sistema (Otimizado)"
    echo "2.  🛑 Parar Sistema"
    echo "3.  🔄 Reiniciar Sistema"
    echo "4.  📊 Status dos Serviços"
    echo "5.  🔍 Diagnóstico Completo"
    echo "6.  📈 Executar Análise de Tráfego"
    echo "7.  🔬 Executar Comparação de Simuladores"
    echo "8.  📄 Gerar PDFs Acadêmicos"
    echo "9.  🗄️  Gerenciar Banco Cassandra"
    echo "10. 🧹 Limpeza do Sistema"
    echo "11. 📋 Ver Logs dos Serviços"
    echo "12. 📊 Monitor de Performance"
    echo "13. 🛠️  Configurações Avançadas"
    echo "14. ❓ Ajuda e Documentação"
    echo "0.  🚪 Sair"
    echo ""
    echo -n "👉 Escolha uma opção (0-14): "
}

# Função para iniciar sistema
start_system() {
    echo -e "${GREEN}🚀 Iniciando HTC...${NC}"
    
    echo "Escolha o tipo de inicialização:"
    echo "1. Otimizada (Recomendada - Detecta recursos automaticamente)"
    echo "2. Configuração Mínima (4-8GB RAM)"
    echo "3. Configuração Padrão"
    echo -n "Opção (1-3): "
    read -r start_option
    
    case $start_option in
        1)
            ./start-optimized.sh
            ;;
        2)
            docker compose -f docker-compose-minimal.yml up
            ;;
        3)
            docker compose up
            ;;
        *)
            echo "Opção inválida. Usando inicialização otimizada..."
            ./start-optimized.sh
            ;;
    esac
}

# Função para parar sistema
stop_system() {
    echo -e "${YELLOW}🛑 Parando HTC...${NC}"
    
    # Tentar parar com diferentes configurações
    docker compose -f docker-compose-optimized.yml down 2>/dev/null || true
    docker compose -f docker-compose-minimal.yml down 2>/dev/null || true
    docker compose down 2>/dev/null || true
    
    echo -e "${GREEN}✅ Sistema parado!${NC}"
}

# Função para reiniciar sistema
restart_system() {
    echo -e "${CYAN}🔄 Reiniciando HTC...${NC}"
    stop_system
    sleep 2
    start_system
}

# Função para mostrar status
show_status() {
    echo -e "${BLUE}📊 Status dos Serviços${NC}"
    echo "======================"
    
    echo -e "\n${CYAN}Containers:${NC}"
    docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || echo "Nenhum container rodando"
    
    echo -e "\n${CYAN}Uso de Recursos:${NC}"
    docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}" 2>/dev/null || echo "Nenhum container rodando"
    
    echo -e "\n${CYAN}Conectividade:${NC}"
    ./diagnose.sh connectivity
}

# Função para executar diagnóstico
run_diagnostics() {
    echo -e "${PURPLE}🔍 Diagnóstico Completo${NC}"
    echo "======================="
    ./diagnose.sh
}

# Função para executar análise de tráfego
run_traffic_analysis() {
    echo -e "${GREEN}📈 Análise de Tráfego${NC}"
    echo "===================="
    
    echo "Escolha a fonte de dados:"
    echo "1. Cassandra (Padrão)"
    echo "2. Arquivo CSV"
    echo -n "Opção (1-2): "
    read -r data_option
    
    case $data_option in
        1)
            ./run_traffic_analysis.sh
            ;;
        2)
            echo -n "Caminho do arquivo CSV: "
            read -r csv_file
            if [ -f "$csv_file" ]; then
                ./run_traffic_analysis.sh --source csv --file "$csv_file"
            else
                echo -e "${RED}❌ Arquivo não encontrado: $csv_file${NC}"
            fi
            ;;
        *)
            echo "Usando fonte padrão (Cassandra)..."
            ./run_traffic_analysis.sh
            ;;
    esac
}

# Função para executar comparação
run_comparison() {
    echo -e "${PURPLE}🔬 Comparação de Simuladores${NC}"
    echo "============================="
    
    echo "Opções de comparação:"
    echo "1. Exemplo de demonstração"
    echo "2. Comparação com arquivos personalizados"
    echo -n "Opção (1-2): "
    read -r comp_option
    
    case $comp_option in
        1)
            ./run_comparison.sh --sample
            ;;
        2)
            echo -n "Arquivo CSV do HTC: "
            read -r htc_file
            echo -n "Arquivo XML de referência: "
            read -r ref_file
            
            if [ -f "$htc_file" ] && [ -f "$ref_file" ]; then
                ./run_comparison.sh --csv "$htc_file" "$ref_file"
            else
                echo -e "${RED}❌ Um ou ambos arquivos não encontrados${NC}"
            fi
            ;;
        *)
            echo "Executando exemplo..."
            ./run_comparison.sh --sample
            ;;
    esac
}

# Função para gerar PDFs
generate_pdfs() {
    echo -e "${CYAN}📄 Geração de PDFs Acadêmicos${NC}"
    echo "============================="
    
    if [ -f "./generate_academic_pdfs.sh" ]; then
        ./generate_academic_pdfs.sh
    else
        echo -e "${RED}❌ Script de PDFs não encontrado${NC}"
    fi
}

# Função para gerenciar Cassandra
manage_cassandra() {
    echo -e "${YELLOW}🗄️  Gerenciamento do Cassandra${NC}"
    echo "=============================="
    
    if [ -f "./manage_cassandra.sh" ]; then
        ./manage_cassandra.sh
    else
        echo "Opções básicas do Cassandra:"
        echo "1. Conectar ao console (cqlsh)"
        echo "2. Ver logs"
        echo "3. Reiniciar container"
        echo -n "Opção (1-3): "
        read -r cass_option
        
        case $cass_option in
            1)
                docker exec -it htc-cassandra-db cqlsh
                ;;
            2)
                docker logs -f htc-cassandra-db
                ;;
            3)
                docker restart htc-cassandra-db
                ;;
        esac
    fi
}

# Função para limpeza do sistema
cleanup_system() {
    echo -e "${RED}🧹 Limpeza do Sistema${NC}"
    echo "===================="
    
    echo "⚠️  ATENÇÃO: Esta operação removerá dados!"
    echo "1. Limpeza suave (containers parados)"
    echo "2. Limpeza completa (+ volumes)"
    echo "3. Limpeza total (+ imagens não utilizadas)"
    echo "4. Cancelar"
    echo -n "Opção (1-4): "
    read -r cleanup_option
    
    case $cleanup_option in
        1)
            docker compose down --remove-orphans
            docker container prune -f
            ;;
        2)
            docker compose down --volumes --remove-orphans
            docker volume prune -f
            ;;
        3)
            docker compose down --volumes --remove-orphans
            docker system prune -af
            ;;
        4)
            echo "Operação cancelada."
            ;;
        *)
            echo "Opção inválida."
            ;;
    esac
}

# Função para ver logs
show_logs() {
    echo -e "${BLUE}📋 Logs dos Serviços${NC}"
    echo "==================="
    
    echo "Escolha o serviço:"
    echo "1. Todos os serviços"
    echo "2. HTC Node (node_1)"
    echo "3. Cassandra"
    echo "4. Redis"
    echo "5. DataStax Studio"
    echo -n "Opção (1-5): "
    read -r log_option
    
    case $log_option in
        1)
            docker compose logs -f
            ;;
        2)
            docker logs -f node_1
            ;;
        3)
            docker logs -f htc-cassandra-db
            ;;
        4)
            docker logs -f htc-redis
            ;;
        5)
            docker logs -f htc-ds-studio
            ;;
        *)
            echo "Opção inválida."
            ;;
    esac
}

# Função para monitor de performance
performance_monitor() {
    echo -e "${CYAN}📊 Monitor de Performance${NC}"
    echo "========================="
    ./diagnose.sh monitor
}

# Função para configurações avançadas
advanced_config() {
    echo -e "${PURPLE}🛠️  Configurações Avançadas${NC}"
    echo "==========================="
    
    echo "1. Aplicar otimizações de sistema (requer root)"
    echo "2. Ver configurações atuais"
    echo "3. Editar docker-compose.yml"
    echo "4. Ver portas em uso"
    echo -n "Opção (1-4): "
    read -r config_option
    
    case $config_option in
        1)
            if [ "$(id -u)" = "0" ]; then
                echo "Aplicando otimizações..."
                sysctl -w vm.max_map_count=1048575
                sysctl -w vm.swappiness=1
                sysctl -w net.core.rmem_max=134217728
                sysctl -w net.core.wmem_max=134217728
                echo -e "${GREEN}✅ Otimizações aplicadas!${NC}"
            else
                echo -e "${RED}❌ Execute como root para aplicar otimizações${NC}"
            fi
            ;;
        2)
            echo "Configurações de sistema:"
            echo "vm.max_map_count: $(sysctl -n vm.max_map_count)"
            echo "vm.swappiness: $(sysctl -n vm.swappiness)"
            ;;
        3)
            ${EDITOR:-nano} docker-compose.yml
            ;;
        4)
            echo "Portas em uso pelo HTC:"
            netstat -tlnp | grep -E ":(6379|9042|9091|8558|1600)" || echo "Nenhuma porta HTC em uso"
            ;;
    esac
}

# Função para mostrar ajuda
show_help() {
    echo -e "${GREEN}❓ Ajuda e Documentação${NC}"
    echo "======================="
    
    echo "📚 Arquivos de Documentação:"
    echo "  - README.md                     # Documentação principal"
    echo "  - docs/COMPARISON_GUIDE.md      # Guia de comparação"
    echo "  - docs/CASSANDRA_MANAGEMENT_GUIDE.md # Guia do Cassandra"
    echo "  - docs/ACADEMIC_PDF_GUIDE.md    # Guia de PDFs acadêmicos"
    echo ""
    echo "🔗 URLs importantes:"
    echo "  - Cassandra: localhost:9042"
    echo "  - Redis: localhost:6379"
    echo "  - DataStax Studio: http://localhost:9091"
    echo "  - HTC Management: http://localhost:8558"
    echo ""
    echo "🛠️  Scripts úteis:"
    echo "  - ./start-optimized.sh          # Inicialização otimizada"
    echo "  - ./diagnose.sh                 # Diagnósticos"
    echo "  - ./run_traffic_analysis.sh     # Análise de tráfego"
    echo "  - ./run_comparison.sh           # Comparação de simuladores"
    echo ""
    echo -n "Pressione Enter para continuar..."
    read -r
}

# Função principal
main() {
    while true; do
        clear
        print_banner
        show_main_menu
        
        read -r choice
        
        case $choice in
            1) start_system ;;
            2) stop_system ;;
            3) restart_system ;;
            4) show_status ;;
            5) run_diagnostics ;;
            6) run_traffic_analysis ;;
            7) run_comparison ;;
            8) generate_pdfs ;;
            9) manage_cassandra ;;
            10) cleanup_system ;;
            11) show_logs ;;
            12) performance_monitor ;;
            13) advanced_config ;;
            14) show_help ;;
            0) 
                echo -e "${GREEN}👋 Obrigado por usar o HTC!${NC}"
                exit 0
                ;;
            *)
                echo -e "${RED}❌ Opção inválida!${NC}"
                sleep 2
                ;;
        esac
        
        if [ "$choice" != "0" ]; then
            echo ""
            echo -n "Pressione Enter para continuar..."
            read -r
        fi
    done
}

# Executar função principal
main "$@"