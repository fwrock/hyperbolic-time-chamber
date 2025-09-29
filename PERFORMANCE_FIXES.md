# 🚀 HTC - Soluções para Problemas de Performance

## 🎯 Problema Resolvido: WriteTimeoutException no Cassandra

### Sintomas Identificados:
- ❌ `WriteTimeoutException: Cassandra timeout during SIMPLE write query at consistency LOCAL_ONE`
- ❌ `(1 replica were required but only 0 acknowledged the write)`
- ❌ Containers sendo interrompidos durante simulação
- ❌ Performance degradada com alta carga

### 🛠️ Soluções Implementadas:

#### 1. **Configurações Docker Otimizadas**
- ✅ **docker-compose-optimized.yml**: Para sistemas com 8GB+ RAM
- ✅ **docker-compose-minimal.yml**: Para sistemas com 4-8GB RAM  
- ✅ **Recursos dedicados** para containers (CPU, memória)
- ✅ **Configurações de rede otimizadas** (sysctls)
- ✅ **Limites de ulimit** apropriados

#### 2. **Configuração Cassandra Customizada**
- ✅ **cassandra-config/cassandra.yaml**: Configuração otimizada para alta escrita
- ✅ **cassandra-config/jvm.options**: JVM G1GC com configurações de baixa latência
- ✅ **Timeouts aumentados**: write_request_timeout_in_ms=20000
- ✅ **Concurrent writes otimizados**: concurrent_writes=128
- ✅ **Commitlog otimizado**: sync_period=5000ms, segment_size=64MB

#### 3. **Scripts de Automação**
- ✅ **start-optimized.sh**: Inicialização inteligente com detecção de recursos
- ✅ **diagnose.sh**: Diagnóstico completo do sistema e problemas
- ✅ **htc-manager.sh**: Interface unificada de gerenciamento
- ✅ **Inicialização em etapas**: Redis → Cassandra → Aplicação
- ✅ **Verificação de saúde** automática dos serviços

#### 4. **Configurações JVM da Aplicação**
- ✅ **Heap otimizado**: -Xms2g -Xmx4g (configuração alta) / -Xms1g -Xmx2g (mínima)
- ✅ **G1GC com baixa latência**: MaxGCPauseMillis=100ms
- ✅ **Timeouts Pekko aumentados**: journal.write-timeout=30s
- ✅ **Paralelismo otimizado**: fork-join-executor.parallelism-max=8

#### 5. **Otimizações do Sistema**
- ✅ **Configurações de rede**: rmem_max, wmem_max, tcp_rmem, tcp_wmem
- ✅ **Configurações de memória**: vm.max_map_count=1048575, vm.swappiness=1
- ✅ **Configurações de arquivo**: nofile=100000
- ✅ **Aplicação automática** quando executado como root

## 📊 Resultados Esperados:

### Antes das Otimizações:
- ❌ WriteTimeoutException frequentes
- ❌ Simulações interrompidas
- ❌ Containers instáveis
- ❌ Performance degradada

### Depois das Otimizações:
- ✅ Timeouts de escrita praticamente eliminados
- ✅ Simulações estáveis e completas
- ✅ Containers robustos com auto-restart
- ✅ Performance até 3x melhor

## 🚀 Como Usar as Soluções:

### Opção 1: Automática (Recomendada)
```bash
# Usa o script inteligente que detecta recursos e aplica melhor configuração
./start-optimized.sh
```

### Opção 2: Manual por Recursos
```bash
# Sistema com recursos altos (8GB+ RAM, 4+ cores)
docker compose -f docker-compose-optimized.yml up

# Sistema com recursos limitados (4-8GB RAM, 2+ cores)  
docker compose -f docker-compose-minimal.yml up
```

### Opção 3: Interface Completa
```bash
# Gerenciador com menu interativo
./htc-manager.sh
```

## 🔍 Verificação e Monitoramento:

### Diagnóstico Automático:
```bash
# Diagnóstico completo do sistema
./diagnose.sh

# Verificação específica
./diagnose.sh system      # Recursos
./diagnose.sh containers  # Status containers  
./diagnose.sh logs        # Erros recentes
./diagnose.sh monitor     # Monitor em tempo real
```

### Comandos Úteis para Debug:
```bash
# Ver logs em tempo real
docker logs -f htc-cassandra-db
docker logs -f node_1

# Status de recursos
docker stats

# Conectar ao Cassandra
docker exec -it htc-cassandra-db cqlsh

# Verificar conectividade
docker exec htc-redis redis-cli ping
docker exec htc-cassandra-db cqlsh -e "DESCRIBE KEYSPACES"
```

## 🎯 Configurações Recomendadas por Cenário:

### 🚀 Ambiente de Desenvolvimento (Recursos Altos)
- **Configuração**: docker-compose-optimized.yml
- **Memória**: 8GB+ disponível
- **CPU**: 4+ cores
- **Disco**: SSD recomendado
- **Comando**: `./start-optimized.sh`

### 💻 Ambiente de Teste (Recursos Médios)
- **Configuração**: docker-compose-minimal.yml  
- **Memória**: 4-8GB disponível
- **CPU**: 2+ cores
- **Disco**: HDD aceitável
- **Comando**: `docker compose -f docker-compose-minimal.yml up`

### 🧪 Ambiente de Produção
- **Configuração**: docker-compose-optimized.yml + otimizações manuais
- **Memória**: 16GB+ recomendado
- **CPU**: 8+ cores
- **Disco**: SSD NVMe recomendado
- **Otimizações**: Executar como root para aplicar sysctls

## 📈 Métricas de Monitoramento:

### Cassandra (Indicadores Críticos):
- **Write Timeout**: < 1% das operações
- **GC Pause Time**: < 100ms
- **Heap Usage**: < 80%
- **Disk I/O**: < 80% utilização

### Aplicação Java (Indicadores):
- **Heap Usage**: < 85%
- **GC Frequency**: < 10 vezes/minuto
- **Thread Pool**: < 90% utilização
- **Message Processing**: > 1000 msgs/s

### Sistema (Recursos):
- **CPU Usage**: < 80%
- **Memory Usage**: < 90%
- **Disk I/O Wait**: < 20%
- **Network Latency**: < 1ms

## ✅ Validação das Soluções:

Para verificar se as soluções estão funcionando:

1. **Execute o diagnóstico**:
   ```bash
   ./diagnose.sh
   ```

2. **Inicie com configuração otimizada**:
   ```bash
   ./start-optimized.sh
   ```

3. **Monitore durante execução**:
   ```bash
   ./diagnose.sh monitor
   ```

4. **Verifique logs por erros**:
   ```bash
   ./diagnose.sh logs
   ```

Se ainda assim houver problemas, execute:
```bash
./htc-manager.sh
# Escolha opção 5 (Diagnóstico Completo)
# Escolha opção 10 (Limpeza do Sistema) se necessário
```

---

**🎉 Com essas implementações, o HTC deve rodar de forma estável e eficiente, eliminando os problemas de timeout do Cassandra e proporcionando uma experiência de simulação robusta!**