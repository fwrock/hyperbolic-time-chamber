# 🎯 Sistema de Report de Eventos - Índice de Arquivos

## Implementação Completa

Aqui está o resumo de tudo o que foi implementado para análise de eventos JSONL:

---

## 📂 Estrutura de Arquivos

### 🐍 Scripts Python (Executáveis)

| Arquivo | Tamanho | Descrição |
|---------|---------|-----------|
| `scripts/analyze_events.py` | 16 KB | **Principal** - Análise geral com gráficos automáticos |
| `scripts/advanced_events_analysis.py` | 14 KB | Análise customizada com filtros |
| `scripts/generate_sample_events.py` | 12 KB | Gerar dados de teste para os scripts |

### 📖 Documentação

| Arquivo | Tamanho | Descrição |
|---------|---------|-----------|
| `README_REPORT_EVENTS.md` | 6.8 KB | **Quick Start** - Guia rápido de uso |
| `scripts/EVENTS_ANALYSIS_README.md` | 8.9 KB | **Detalhado** - Guia completo com exemplos |
| Este arquivo | - | Índice e referência |

### 💻 Código Modificado

| Arquivo | Mudança | Status |
|---------|---------|--------|
| `src/main/scala/model/hybrid/actor/Car.scala` | +signal_wait report | ✅ Completo |

---

## 🚀 Como Usar

### Início Rápido (1 minuto)

```bash
cd scripts/

# Gerar dados de teste
python3 generate_sample_events.py --count 2000

# Analisar
python3 analyze_events.py sample_events.jsonl
```

### Com Sua Simulação Real

```bash
# Após executar simulação
./build-and-run.sh

# Analisar eventos gerados
python3 scripts/analyze_events.py logs/events.jsonl --output my_results/
```

---

## 📊 O que Cada Script Faz

### 1️⃣ `analyze_events.py` - Análise Completa

```bash
python3 analyze_events.py arquivo.jsonl [--output=dir] [--no-plots] [--no-csv]
```

**Gera:**
- ✅ 7 gráficos PNG automáticos
- ✅ 3 arquivos CSV com dados
- ✅ Resumo estatístico em consola

**Gráficos:**
1. Distribuição de eventos (top 15)
2. Evolução temporal
3. Tempos de viagem
4. Velocidades médias
5. Veículos por tipo
6. Atrasos em sinais
7. MESO vs MICRO

**Exemplo de Output:**
```
======================================================================
RESUMO DA SIMULAÇÃO
======================================================================

Eventos:
  Total: 1,772
  Período: tick 0 - 122
  Atores: 110
  Veículos: 110

Desempenho:
  Viagens completadas: 132
  Velocidade média: 72 km/h
  Atrasos em sinais: 307 esperas
```

### 2️⃣ `advanced_events_analysis.py` - Análise Customizada

```bash
python3 advanced_events_analysis.py arquivo.jsonl [opções]
```

**Filtros Disponíveis:**
- `--vehicle CAR_1` - Analisar veículo específico
- `--vehicle-type bus` - Tipo de veículo
- `--event-type leave_link` - Tipo de evento
- `--link LINK_id` - Link específico
- `--mode MICRO` - Modo de simulação (MICRO/MESO)
- `--tick-min 100 --tick-max 500` - Período de ticks

**Exemplos:**
```bash
# Analisar um carro
python3 advanced_events_analysis.py events.jsonl --vehicle CAR_1

# Apenas ônibus
python3 advanced_events_analysis.py events.jsonl --vehicle-type bus

# Apenas modo MICRO
python3 advanced_events_analysis.py events.jsonl --mode MICRO

# Links mais usados
python3 advanced_events_analysis.py events.jsonl --links-top 20
```

**Saídas:**
- Jornada completa do veículo (cronologia)
- Análise de links (top N por uso)
- Estatísticas de modo
- CSV filtrado para análise posterior

### 3️⃣ `generate_sample_events.py` - Dados de Teste

```bash
python3 generate_sample_events.py [--output=file] [--count=N] [--seed=42]
```

**Cria eventos realistas para teste:**
- journey_started / journey_completed
- enter_link / leave_link
- signal_wait events
- bus_waiting
- MESO e MICRO mode switching

**Exemplo:**
```bash
python3 generate_sample_events.py --count 5000 --output test_events.jsonl
```

---

## 📋 Tipos de Eventos Suportados

| Evento | Campos | Descrição |
|--------|--------|-----------|
| `journey_started` | vehicle_type, vehicle_id, origin, destination | Início da jornada |
| `journey_completed` | vehicle_id, reached_destination, total_distance | Fim da jornada |
| `enter_link` | vehicle_id, link_id, mode, speed | Entra em link (MESO) |
| `enter_micro_link` | vehicle_id, link_id, lane, velocity | Entra em link (MICRO) |
| `leave_link` | vehicle_id, link_id, distance_traveled, avg_speed | Sai de link (MESO) |
| `leave_micro_link` | vehicle_id, final_position, travel_time | Sai de link (MICRO) |
| `signal_wait` | vehicle_id, phase, wait_until_tick | Espera semáforo |
| `bus_waiting` | vehicle_id, passengers_onboard, capacity | Ônibus aguardando |

---

## 📈 Exemplos de Análise

### Case 1: Encontrar Links Congestionados

```bash
python3 advanced_events_analysis.py events.jsonl --links-top 10

# Output mostra:
# Link LINK_downtown_main_st_0: 127 entradas, 82% MESO
# Link LINK_industrial_zone_rd_1: 89 entradas, 45% MICRO
```

### Case 2: Investigar Modo MICRO

```bash
python3 advanced_events_analysis.py events.jsonl --mode MICRO

# Estatísticas:
# MICRO: 891 eventos (45.3%)
# MESO: 1,076 eventos (54.7%)
```

### Case 3: Analisar Veículo Específico

```bash
python3 advanced_events_analysis.py events.jsonl --vehicle CAR_0042

# Jornada completa:
# 156 eventos
# 12 links traversados
# 3 esperas em sinais
# 2 mudanças de modo
```

### Case 4: Período Específico

```bash
python3 advanced_events_analysis.py events.jsonl --tick-min 1000 --tick-max 2000

# Análise apenas de ticks 1000-2000
# CSV exportado com dados filtrados
```

---

## 🔧 Integração com Simulação Scala

### Adicionando Reports nos Atores

```scala
// Em Car.scala, Bus.scala, ou outro ator
report(
  data = Map(
    "event_type" -> "seu_evento",
    "vehicle_type" -> "car",
    "vehicle_id" -> getEntityId,
    "seu_campo" -> seuValor,
    "tick" -> currentTick
  ),
  label = "seu_evento"
)
```

### Configuração de Report

Os reportadores estão configurados em `application.conf`:
```conf
htc.report-manager.default-strategy = "jsonl"
```

Os eventos são salvos automaticamente em JSONL.

---

## 📚 Documentação Detalhada

### Para Começar Rápido
👉 **Leia:** `README_REPORT_EVENTS.md`
- Quick start
- Exemplos práticos
- Troubleshooting

### Para Usar Completo
👉 **Leia:** `scripts/EVENTS_ANALYSIS_README.md`
- Detalhes de todos os campos
- Exemplos avançados
- Customização
- Performance tips

---

## ✅ Checklist de Implementação

- [x] Adicionado report de `signal_wait` em Car
- [x] Criar `analyze_events.py` com análises gerais
- [x] Criar `advanced_events_analysis.py` com filtros
- [x] Criar `generate_sample_events.py` para testes
- [x] Documentação Quick Start
- [x] Documentação Completa
- [x] Testes funcionando
- [x] Exemplos práticos

---

## 🎯 Próximos Passos

### Adicionar Mais Events

1. **Bus.scala** - Adicionar events de passageiros
2. **Link.scala** - Adicionar events de congestionamento
3. **Node.scala** - Adicionar events de interseção
4. **TrafficSignal.scala** - Adicionar events de fase

### Melhorias nos Scripts

1. **Correlações** - Analisar relação entre eventos
2. **Anomalias** - Detectar comportamento inusitado
3. **Comparação** - Comparar dois arquivos JSONL
4. **Dashboard** - Interface web interativa

### Performance

1. **Streaming** - Processar arquivos gigantes
2. **Paralelo** - Multi-threading para análise
3. **Índices** - Caching para busca rápida

---

## 💡 Dicas

### 1. Sempre Use Filtros para Arquivos Grandes
```bash
# Lento ❌
python3 analyze_events.py 10GB_file.jsonl

# Rápido ✅
python3 advanced_events_analysis.py 10GB_file.jsonl --tick-max 5000
```

### 2. Exportar para CSV Para Análise Excel
```bash
python3 advanced_events_analysis.py events.jsonl --vehicle-type car
# Gera: events_filtered.csv
```

### 3. Combinar Múltiplos Filtros
```bash
python3 advanced_events_analysis.py events.jsonl \
  --vehicle-type bus \
  --mode MICRO \
  --tick-min 1000 \
  --tick-max 2000
```

### 4. Usar Seed para Reproduzibilidade
```bash
python3 generate_sample_events.py --seed 123 --count 2000
# Mesmo output toda vez com seed=123
```

---

## 🐛 Troubleshooting

| Problema | Solução |
|----------|---------|
| `ModuleNotFoundError: pandas` | `pip install pandas numpy matplotlib seaborn` |
| Gráficos vazios | Verificar se há dados: `head eventos.jsonl` |
| Análise muito lenta | Usar `--tick-max` ou `--vehicle-type` para filtrar |
| Arquivo não encontrado | `ls -la seu_arquivo.jsonl` |
| CSV não gerado | Instalar pandas: `pip install pandas` |

---

## 📞 Referência Rápida

```bash
# Teste rápido
cd scripts/
python3 generate_sample_events.py --count 1000
python3 analyze_events.py sample_events.jsonl --no-plots

# Sua simulação
python3 scripts/analyze_events.py logs/events.jsonl --output results/

# Investigar
python3 scripts/advanced_events_analysis.py logs/events.jsonl --vehicle CAR_1
```

---

**Status:** ✅ Implementação Completa
**Versão:** 1.0  
**Data:** 16 de Fevereiro de 2026

---

## 📖 Índice de Documentação

1. Este arquivo → Visão geral e índice
2. `README_REPORT_EVENTS.md` → Quick start (5 min)
3. `scripts/EVENTS_ANALYSIS_README.md` → Guia completo (30 min)
4. Código comentado nos scripts Python

---

**Pronto para usar! 🚀**
