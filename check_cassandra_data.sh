#!/bin/bash

# Script para verificar dados no Cassandra
# Uso: ./check_cassandra_data.sh

set -e

# Cores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}📊 Verificando dados do Cassandra...${NC}"

# Verificar se Cassandra está rodando
if ! docker ps --format "{{.Names}}" | grep -q "cassandra"; then
    echo -e "${RED}❌ Cassandra não está rodando${NC}"
    echo -e "${YELLOW}💡 Execute: ./manage_cassandra.sh start${NC}"
    exit 1
fi

# Verificar conectividade
if ! docker exec cassandra cqlsh -e "DESCRIBE KEYSPACES;" &>/dev/null; then
    echo -e "${RED}❌ Não foi possível conectar ao Cassandra${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Cassandra está acessível${NC}"

# Verificar se keyspace existe
if ! docker exec cassandra cqlsh -e "USE htc_simulation;" &>/dev/null; then
    echo -e "${YELLOW}⚠️ Keyspace htc_simulation não existe${NC}"
    echo -e "${YELLOW}💡 Execute: ./manage_cassandra.sh init${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Keyspace htc_simulation encontrado${NC}"

# Contar registros
echo -e "${BLUE}🔢 Contando registros...${NC}"

VEHICLE_FLOW_COUNT=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT COUNT(*) FROM vehicle_flow;" 2>/dev/null | grep -E "^\s*[0-9]+" | tr -d ' ' || echo "0")

echo ""
echo -e "${BLUE}📈 RESUMO DOS DADOS:${NC}"
echo -e "  🚗 vehicle_flow: ${GREEN}$VEHICLE_FLOW_COUNT${NC} registros"

if [[ "$VEHICLE_FLOW_COUNT" -gt 0 ]]; then
    echo ""
    echo -e "${BLUE}📝 ÚLTIMOS REGISTROS:${NC}"
    docker exec cassandra cqlsh -e "
        USE htc_simulation; 
        SELECT car_id, link_id, timestamp, event_type 
        FROM vehicle_flow 
        LIMIT 5;
    " 2>/dev/null || echo -e "${YELLOW}⚠️ Erro ao buscar registros${NC}"
    
    echo ""
    echo -e "${BLUE}🎯 ESTATÍSTICAS:${NC}"
    
    # Veículos únicos
    UNIQUE_CARS=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT DISTINCT car_id FROM vehicle_flow;" 2>/dev/null | grep -c "htc" || echo "0")
    echo -e "  🚙 Veículos únicos: ${GREEN}$UNIQUE_CARS${NC}"
    
    # Links únicos
    UNIQUE_LINKS=$(docker exec cassandra cqlsh -e "USE htc_simulation; SELECT DISTINCT link_id FROM vehicle_flow;" 2>/dev/null | grep -c "htc" || echo "0")
    echo -e "  🛣️  Links únicos: ${GREEN}$UNIQUE_LINKS${NC}"
    
    # Tipos de eventos
    echo -e "  📊 Tipos de eventos:"
    docker exec cassandra cqlsh -e "
        USE htc_simulation; 
        SELECT event_type, COUNT(*) as count 
        FROM vehicle_flow 
        GROUP BY event_type 
        ALLOW FILTERING;
    " 2>/dev/null | grep -E "(enter_link|leave_link|journey_completed)" | while IFS= read -r line; do
        echo -e "     ${GREEN}$line${NC}"
    done
else
    echo ""
    echo -e "${YELLOW}📭 Banco está vazio - pronto para nova simulação!${NC}"
fi

echo ""
echo -e "${BLUE}💡 COMANDOS ÚTEIS:${NC}"
echo -e "  🧹 Limpar dados:     ${YELLOW}./manage_cassandra.sh clean${NC}"
echo -e "  ♻️  Reset completo:   ${YELLOW}./manage_cassandra.sh reset${NC}"
echo -e "  🚀 Executar sim.:    ${YELLOW}./build-and-run.sh${NC}"