# Hyperbolic Time Chamber - Google Cloud Cluster

## 🌩️ Configuração Completa para GCP

Este projeto agora inclui uma configuração completa para deploy em Google Cloud Platform, otimizada para uma VM **n2-highmem-128** com **128 vCPUs** e **512GB RAM**.

### 🏗️ Arquitetura do Cluster

#### 📊 Distribuição de Recursos
- **Total**: 128 vCPUs, 512GB RAM
- **Cassandra Cluster**: 3 nós × 16 vCPUs × 40GB = 48 vCPUs, 120GB RAM
- **HTC Applications**: 8 nós × 16 vCPUs × 56GB = 128 vCPUs, 448GB RAM  
- **Redis**: 1 nó × 4 vCPUs × 10GB = 4 vCPUs, 10GB RAM
- **Monitoring**: Portainer para gerenciamento

#### 🗄️ Cassandra Cluster (3 nós)
- **cassandra1**: 16 vCPUs, 40GB RAM, 32GB heap
- **cassandra2**: 16 vCPUs, 40GB RAM, 32GB heap  
- **cassandra3**: 16 vCPUs, 40GB RAM, 32GB heap
- **Configuração**: Produção com otimizações para alta carga
- **Replicação**: RF=3, Consistency Level = LOCAL_QUORUM

#### 🚀 HTC Application Cluster (8 nós)
- **htc1-htc8**: Cada nó com 16 vCPUs, 56GB RAM, 48GB heap
- **Load Balancer**: HAProxy distribuindo carga
- **Pekko Cluster**: Cluster distribuído para processamento paralelo
- **Portas**: 8080-8087 (uma por instância)

#### 📊 Serviços Auxiliares
- **Redis**: Cache distribuído, 4 vCPUs, 10GB RAM
- **Portainer**: Interface web para gerenciar containers

### 🚀 Deploy Rápido

#### 1. Script de Deploy Automatizado
```bash
# Deploy completo (recomendado)
./deploy-gcp-cluster.sh deploy

# Menu interativo
./deploy-gcp-cluster.sh
```

#### 2. Deploy Manual
```bash
# 1. Instalar dependências
sudo apt-get update
sudo apt-get install -y docker.io docker-compose

# 2. Otimizar sistema
sudo sysctl vm.max_map_count=1048575
sudo sysctl vm.swappiness=1

# 3. Criar estrutura de diretórios
mkdir -p {data,logs}/{cassandra{1..3},htc{1..8},redis}

# 4. Iniciar cluster
docker-compose -f docker-compose-gcp-cluster.yml up -d
```

### 📊 Monitoramento

#### Dashboard em Tempo Real
```bash
# Monitoring dashboard completo
./monitor-cluster.sh dashboard

# Logs em tempo real
./monitor-cluster.sh logs cassandra1

# Estatísticas detalhadas  
./monitor-cluster.sh stats
```

#### URLs de Acesso
- **HTC Applications**: `http://[IP-EXTERNO]:8080-8087`
- **Cassandra CQL**: `[IP-EXTERNO]:9042`
- **Redis**: `[IP-EXTERNO]:6379`
- **Portainer**: `http://[IP-EXTERNO]:9000`

### 🔧 Configurações de Produção

#### Cassandra Production Settings
```yaml
# cassandra-config/cassandra-production.yaml
concurrent_reads: 256
concurrent_writes: 256
concurrent_counter_writes: 256
concurrent_materialized_view_writes: 256

# Timeouts otimizados
write_request_timeout_in_ms: 60000
read_request_timeout_in_ms: 30000

# Cache settings
key_cache_size_in_mb: 1024
row_cache_size_in_mb: 2048
```

#### JVM Heap Sizing
```bash
# Cassandra: 32GB heap por nó (80% de 40GB)
-Xms32G -Xmx32G

# HTC Apps: 48GB heap por nó (85% de 56GB)  
-Xms48g -Xmx48g

# G1GC otimizado para baixa latência
-XX:+UseG1GC -XX:MaxGCPauseMillis=25
```

### 📈 Performance Esperada

#### Throughput Estimado
- **Writes**: ~500K ops/sec (distribuído)
- **Reads**: ~1M ops/sec (distribuído)
- **HTC Simulations**: 8 simulações paralelas
- **Latência**: <1ms P95 (writes), <500μs P95 (reads)

#### Escalabilidade
- **Cassandra**: Linear scaling até 100+ nós
- **HTC Apps**: Linear scaling baseado em CPU
- **Memory**: Suporta simulações com 10M+ entidades

### 🛡️ Alta Disponibilidade

#### Replication & Consistency
```yaml
# Replication Factor
CREATE KEYSPACE hyperbolic_time_chamber 
WITH REPLICATION = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 3
};

# Consistency Levels
WRITE: LOCAL_QUORUM (2/3 nós)
READ: LOCAL_ONE (1/3 nós, com read repair)
```

#### Fault Tolerance
- **Cassandra**: Tolerância a 1 nó down (RF=3)
- **HTC Apps**: Tolerância a 2 nós down (8 total)
- **Load Balancer**: Health checks automáticos

### 🔍 Troubleshooting

#### Logs Importantes
```bash
# Logs do cluster
docker-compose -f docker-compose-gcp-cluster.yml logs -f

# Status do Cassandra
docker-compose -f docker-compose-gcp-cluster.yml exec cassandra1 nodetool status

# Heap usage
docker stats

# System resources
htop
iostat -x 1
```

#### Problemas Comuns

**1. Cassandra lento para iniciar**
```bash
# Normal: até 5 minutos para formar cluster
# Verificar logs: 
docker-compose -f docker-compose-gcp-cluster.yml logs cassandra1
```

**2. HTC apps não conectam ao Cassandra**
```bash
# Aguardar Cassandra estar completamente online
docker-compose -f docker-compose-gcp-cluster.yml exec cassandra1 nodetool status
# Deve mostrar 3 nós "UN" (Up Normal)
```

**3. Memória insuficiente**
```bash
# Verificar se VM tem recursos adequados
free -h
# Deve mostrar 512GB+ disponível

# Ajustar heap se necessário em docker-compose-gcp-cluster.yml
```

### 💰 Custos GCP Estimados

#### VM n2-highmem-128
- **Custo/hora**: ~$12-15 USD (varia por região)
- **Custo/mês**: ~$8,500-11,000 USD (24/7)
- **Preemptible**: ~70% desconto (adequado para desenvolvimento)

#### Otimização de Custos
```bash
# Usar instância preemptible para dev/test
gcloud compute instances create htc-cluster \
  --machine-type=n2-highmem-128 \
  --preemptible

# Parar quando não usar
gcloud compute instances stop htc-cluster

# Schedule automático para prod
# Ex: Ligar 8h-18h dias úteis
```

### 📚 Arquivos do Cluster GCP

#### Configurações Principais
- `docker-compose-gcp-cluster.yml` - Orquestração do cluster
- `cassandra-config/cassandra-production.yaml` - Config Cassandra
- `cassandra-config/jvm-production.options` - JVM tuning
- `deploy-gcp-cluster.sh` - Deploy automatizado
- `monitor-cluster.sh` - Monitoring em tempo real

#### Scripts de Automação
- Deploy: Instalação completa automatizada
- Monitor: Dashboard em tempo real
- Health checks automáticos
- System optimization
- Resource monitoring

Este cluster está pronto para simulações de grande escala no Google Cloud! 🚀