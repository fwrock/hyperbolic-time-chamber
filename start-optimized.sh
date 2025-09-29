#!/bin/bash

# Script de inicialização otimizada do HTC
# Configurações de sistema para alta performance

set -e

echo "🚀 Iniciando HTC com configurações otimizadas..."

# Verificar se docker-compose está instalado
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "❌ Docker Compose não encontrado. Instale o Docker Compose primeiro."
    exit 1
fi

# Usar docker compose ou docker-compose baseado na disponibilidade
DOCKER_COMPOSE_CMD="docker compose"
if ! docker compose version &> /dev/null; then
    DOCKER_COMPOSE_CMD="docker-compose"
fi

# Função para verificar uso de memória do sistema
check_system_resources() {
    echo "📊 Verificando recursos do sistema..."
    
    # Verificar memória disponível (em GB)
    TOTAL_MEM=$(free -g | awk '/^Mem:/{print $2}')
    AVAILABLE_MEM=$(free -g | awk '/^Mem:/{print $7}')
    
    if [ "$TOTAL_MEM" -lt 8 ]; then
        echo "⚠️  AVISO: Sistema com pouca memória ($TOTAL_MEM GB). Recomendado: 8GB+"
        echo "   Usando configuração de recursos reduzida..."
        export COMPOSE_FILE="docker-compose-minimal.yml"
    else
        echo "✅ Memória adequada detectada: $TOTAL_MEM GB total, $AVAILABLE_MEM GB disponível"
        export COMPOSE_FILE="docker-compose-optimized.yml"
    fi
}

# Função para limpar containers antigos
cleanup_containers() {
    echo "🧹 Limpando containers antigos..."
    
    $DOCKER_COMPOSE_CMD -f "$COMPOSE_FILE" down --remove-orphans 2>/dev/null || true
    
    # Remover volumes órfãos se necessário
    if [ "$1" = "--clean-volumes" ]; then
        echo "🗑️  Removendo volumes antigos..."
        docker volume prune -f 2>/dev/null || true
    fi
}

# Função para aplicar otimizações do sistema
apply_system_optimizations() {
    echo "⚙️  Aplicando otimizações do sistema..."
    
    # Aumentar limites do sistema para Cassandra
    if [ "$(id -u)" = "0" ]; then
        # Configurações sysctl para rede
        sysctl -w net.core.rmem_max=134217728 2>/dev/null || true
        sysctl -w net.core.wmem_max=134217728 2>/dev/null || true
        sysctl -w net.ipv4.tcp_rmem="4096 65536 134217728" 2>/dev/null || true
        sysctl -w net.ipv4.tcp_wmem="4096 65536 134217728" 2>/dev/null || true
        
        # Configurações de memória virtual
        sysctl -w vm.max_map_count=1048575 2>/dev/null || true
        sysctl -w vm.swappiness=1 2>/dev/null || true
        
        echo "✅ Otimizações do sistema aplicadas (modo root)"
    else
        echo "⚠️  Execute como root para aplicar otimizações completas do sistema"
        echo "   Continuando sem otimizações de kernel..."
    fi
}

# Função para verificar status dos serviços
check_services_health() {
    echo "🔍 Verificando saúde dos serviços..."
    
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        attempt=$((attempt + 1))
        echo "   Tentativa $attempt/$max_attempts..."
        
        # Verificar Redis
        if docker exec htc-redis redis-cli ping > /dev/null 2>&1; then
            echo "   ✅ Redis: OK"
        else
            echo "   ⏳ Redis: Iniciando..."
            sleep 2
            continue
        fi
        
        # Verificar Cassandra
        if docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES" > /dev/null 2>&1; then
            echo "   ✅ Cassandra: OK"
            break
        else
            echo "   ⏳ Cassandra: Iniciando... (pode levar alguns minutos)"
            sleep 10
        fi
    done
    
    if [ $attempt -eq $max_attempts ]; then
        echo "❌ Timeout: Serviços não iniciaram completamente em tempo hábil"
        echo "   Verifique os logs com: $DOCKER_COMPOSE_CMD -f $COMPOSE_FILE logs"
        return 1
    fi
    
    echo "✅ Todos os serviços estão funcionando!"
}

# Função para criar keyspaces e tabelas necessárias
initialize_database() {
    echo "🏗️  Inicializando banco de dados..."
    
    # Aguardar Cassandra estar completamente pronto
    sleep 10
    
    # Criar keyspaces e tabelas via script CQL
    docker exec htc-cassandra-db cqlsh -f /docker-entrypoint-initdb.d/init.cql 2>/dev/null || {
        echo "⚠️  Script de inicialização não encontrado. Criando estrutura básica..."
        
        docker exec htc-cassandra-db cqlsh -e "
            CREATE KEYSPACE IF NOT EXISTS htc_persistence 
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
            
            CREATE KEYSPACE IF NOT EXISTS htc_reports 
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
            
            USE htc_persistence;
            
            CREATE TABLE IF NOT EXISTS messages (
                persistence_id text,
                partition_nr bigint,
                sequence_nr bigint,
                timestamp timeuuid,
                timebucket text,
                writer_uuid text,
                ser_id int,
                ser_manifest text,
                event_manifest text,
                event blob,
                meta_ser_id int,
                meta_ser_manifest text,
                meta blob,
                tags set<text>,
                PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
            ) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC);
            
            CREATE TABLE IF NOT EXISTS snapshots (
                persistence_id text,
                sequence_nr bigint,
                timestamp timeuuid,
                ser_id int,
                ser_manifest text,
                snapshot_data blob,
                snapshot blob,
                meta_ser_id int,
                meta_ser_manifest text,
                meta blob,
                PRIMARY KEY (persistence_id, sequence_nr)
            ) WITH CLUSTERING ORDER BY (sequence_nr DESC);
        " || true
    }
    
    echo "✅ Banco de dados inicializado!"
}

# Função para iniciar aplicação
start_application() {
    echo "🚀 Iniciando aplicação HTC..."
    
    # Aguardar mais um pouco para garantir estabilidade
    sleep 5
    
    # Iniciar container da aplicação
    $DOCKER_COMPOSE_CMD -f "$COMPOSE_FILE" up node1 --no-deps
}

# Função para mostrar informações de monitoramento
show_monitoring_info() {
    echo "📊 Informações de Monitoramento:"
    echo "   🔗 Cassandra: localhost:9042"
    echo "   🔗 Redis: localhost:6379" 
    echo "   🔗 DataStax Studio: http://localhost:9091"
    echo "   🔗 HTC Management: http://localhost:8558"
    echo ""
    echo "📋 Comandos úteis:"
    echo "   Ver logs: $DOCKER_COMPOSE_CMD -f $COMPOSE_FILE logs -f [serviço]"
    echo "   Status: $DOCKER_COMPOSE_CMD -f $COMPOSE_FILE ps"
    echo "   Parar: $DOCKER_COMPOSE_CMD -f $COMPOSE_FILE down"
    echo "   Console Cassandra: docker exec -it htc-cassandra-db cqlsh"
    echo "   Console Redis: docker exec -it htc-redis redis-cli"
}

# Função principal
main() {
    echo "🎯 HTC - Hyperbolic Time Chamber - Inicialização Otimizada"
    echo "=========================================================="
    
    # Parse argumentos
    CLEAN_VOLUMES=false
    for arg in "$@"; do
        case $arg in
            --clean-volumes)
                CLEAN_VOLUMES=true
                shift
                ;;
            --help|-h)
                echo "Uso: $0 [--clean-volumes] [--help]"
                echo "  --clean-volumes: Remove volumes antigos antes de iniciar"
                echo "  --help: Mostra esta ajuda"
                exit 0
                ;;
        esac
    done
    
    # Executar etapas de inicialização
    check_system_resources
    
    if [ "$CLEAN_VOLUMES" = true ]; then
        cleanup_containers --clean-volumes
    else
        cleanup_containers
    fi
    
    apply_system_optimizations
    
    echo "🐳 Iniciando serviços de infraestrutura..."
    $DOCKER_COMPOSE_CMD -f "$COMPOSE_FILE" up -d redis cassandra ds-studio
    
    check_services_health
    initialize_database
    show_monitoring_info
    
    echo "🎉 Sistema pronto! Iniciando aplicação..."
    echo "   Use Ctrl+C para parar a aplicação"
    echo ""
    
    # Iniciar aplicação em primeiro plano
    start_application
}

# Capturar sinais para cleanup
trap 'echo "🛑 Parando sistema..."; $DOCKER_COMPOSE_CMD -f "$COMPOSE_FILE" down; exit 0' INT TERM

# Executar função principal
main "$@"