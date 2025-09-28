# 🗄️ Guia de Gerenciamento do Cassandra - HTC Simulator

Este guia explica como usar os scripts de gerenciamento do Cassandra para garantir simulações limpas e sem interferência de dados antigos.

## 🚀 Scripts Disponíveis

### **1. `./manage_cassandra.sh` - Gerenciador Principal**
Script principal para controlar o Cassandra:

```bash
# Subir Cassandra
./manage_cassandra.sh start

# Limpar todos os dados das tabelas
./manage_cassandra.sh clean

# Reset completo (para, limpa volumes, sobe)
./manage_cassandra.sh reset

# Verificar status
./manage_cassandra.sh status

# Parar Cassandra
./manage_cassandra.sh stop

# Inicializar schema
./manage_cassandra.sh init
```

### **2. `./check_cassandra_data.sh` - Verificador de Dados**
Verifica o estado atual dos dados:

```bash
./check_cassandra_data.sh
```

**Saída típica:**
```
📊 RESUMO DOS DADOS:
  🚗 vehicle_flow: 1,247 registros
  🚙 Veículos únicos: 157
  🛣️ Links únicos: 23
  📊 Tipos de eventos:
     enter_link: 623
     leave_link: 579
     journey_completed: 45
```

### **3. `./simulation_workflow.sh` - Workflow Completo**
Automatiza todo o processo:

```bash
# Workflow interativo (recomendado)
./simulation_workflow.sh

# Limpar dados e executar
./simulation_workflow.sh clean

# Reset completo e executar
./simulation_workflow.sh reset

# Apenas verificar status
./simulation_workflow.sh status
```

## 🔄 Workflows Recomendados

### **🎯 Workflow Básico (Primeira Execução)**
```bash
# 1. Subir Cassandra e inicializar
./manage_cassandra.sh start

# 2. Executar simulação
./build-and-run.sh

# 3. Analisar resultados
./run_traffic_analysis.sh
```

### **🧹 Workflow com Limpeza (Execução Subsequente)**
```bash
# Limpar dados antigos e executar nova simulação
./simulation_workflow.sh clean
```

### **♻️ Workflow Reset Completo**
```bash
# Quando quiser começar do zero (limpa tudo)
./simulation_workflow.sh reset
```

### **🔍 Workflow de Verificação**
```bash
# Antes de executar nova simulação
./check_cassandra_data.sh

# Se houver dados antigos, limpar:
./manage_cassandra.sh clean

# Executar simulação
./build-and-run.sh
```

## 📊 Entendendo os Estados do Sistema

### **Estado Limpo** ✅
```bash
$ ./check_cassandra_data.sh
✅ Cassandra está acessível
✅ Keyspace htc_simulation encontrado
📊 RESUMO DOS DADOS:
  🚗 vehicle_flow: 0 registros
📭 Banco está vazio - pronto para nova simulação!
```

### **Estado com Dados** ⚠️
```bash
$ ./check_cassandra_data.sh
✅ Cassandra está acessível  
✅ Keyspace htc_simulation encontrado
📊 RESUMO DOS DADOS:
  🚗 vehicle_flow: 1,247 registros
  🚙 Veículos únicos: 157
  🛣️ Links únicos: 23
```

### **Estado Não Inicializado** ❌
```bash
$ ./check_cassandra_data.sh
❌ Cassandra não está rodando
💡 Execute: ./manage_cassandra.sh start
```

## 🛠️ Schema do Banco de Dados

### **Tabela Principal: `vehicle_flow`**
```sql
CREATE TABLE vehicle_flow (
    car_id text,           -- ID do veículo (ex: htcaid:car;trip_317)
    link_id text,          -- ID do link (ex: htcaid:link;2105)
    timestamp double,      -- Timestamp da simulação
    tick bigint,           -- Tick da simulação
    direction text,        -- Direção do movimento
    lane int,              -- Faixa utilizada
    event_type text,       -- Tipo do evento (enter_link, leave_link, etc.)
    data text,             -- Dados adicionais em JSON
    created_at timestamp,  -- Timestamp de criação do registro
    PRIMARY KEY (car_id, timestamp, link_id)
);
```

### **Índices Criados:**
- `vehicle_flow_link_idx` - Por link_id
- `vehicle_flow_tick_idx` - Por tick
- `vehicle_flow_event_idx` - Por event_type
- `vehicle_flow_created_idx` - Por created_at

## 🚨 Resolução de Problemas

### **Cassandra não inicia**
```bash
# Verificar Docker
docker --version
docker info

# Verificar docker-compose
./manage_cassandra.sh start
```

### **Erro de conexão**
```bash
# Aguardar inicialização completa
./manage_cassandra.sh status

# Reset se necessário
./manage_cassandra.sh reset
```

### **Dados não são limpos**
```bash
# Verificar se tabelas existem
./manage_cassandra.sh status

# Reinicializar schema
./manage_cassandra.sh init

# Reset completo
./manage_cassandra.sh reset
```

### **Performance lenta**
```bash
# Limpar dados antigos
./manage_cassandra.sh clean

# Verificar uso de recursos
docker stats cassandra
```

## 📈 Integração com Análise

### **Após Simulação:**
```bash
# 1. Verificar dados gerados
./check_cassandra_data.sh

# 2. Executar análise de tráfego
./run_traffic_analysis.sh

# 3. Comparar com referência
./run_comparison.sh --cassandra reference.xml
```

### **Workflow Completo de Pesquisa:**
```bash
# Executar simulação limpa
./simulation_workflow.sh clean

# Analisar resultados
./run_traffic_analysis.sh

# Comparar com baseline
./run_comparison.sh --cassandra matsim_baseline.xml

# Verificar dados para próxima iteração
./check_cassandra_data.sh
```

## 💡 Dicas e Melhores Práticas

### **🎯 Para Desenvolvimento:**
- Sempre use `./simulation_workflow.sh clean` entre execuções
- Verifique dados com `./check_cassandra_data.sh` antes de executar
- Use `./manage_cassandra.sh reset` quando mudar configurações

### **🧪 Para Testes:**
- Mantenha dados limpos entre diferentes cenários
- Use timestamps para distinguir execuções
- Documente configurações usadas em cada teste

### **📊 Para Análise:**
- Execute `./check_cassandra_data.sh` para validar dados
- Use os relatórios de análise para documentar resultados
- Compare métricas entre execuções diferentes

### **⚠️ Para Produção:**
- Sempre faça backup antes de reset
- Use `clean` em vez de `reset` quando possível
- Monitor uso de recursos do Docker

## 🔧 Customização

### **Modificar Schema:**
Edite o arquivo `cassandra-init/init.cql` e execute:
```bash
./manage_cassandra.sh reset
```

### **Adicionar Tabelas:**
```sql
-- Adicione ao init.cql
CREATE TABLE custom_metrics (
    metric_name text,
    timestamp timestamp,
    value double,
    PRIMARY KEY (metric_name, timestamp)
);
```

### **Configurar Docker:**
Modifique `docker-compose.yml` conforme necessário e execute:
```bash
./manage_cassandra.sh reset
```

---

**🎉 Com esses scripts você tem controle total sobre o Cassandra e pode garantir simulações limpas e reprodutíveis!**