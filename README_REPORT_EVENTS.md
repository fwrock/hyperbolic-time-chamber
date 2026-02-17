# 📊 Sistema de Report de Eventos - Guia Rápido

## Resumo do que foi feito

Adicionamos um sistema completo de **report de eventos** no Hyperbolic Time Chamber com:

### ✅ 1. Eventos Estruturados nos Atores

Os atores (Car, Bus, Bicycle, Motorcycle, Link, etc.) agora reportam eventos estruturados em formato de mapa:

```scala
// Exemplo em Car.scala
report(
  data = Map(
    "event_type" -> "signal_wait",
    "vehicle_type" -> "car",
    "vehicle_id" -> getEntityId,
    "phase" -> data.phase.toString,
    "wait_until_tick" -> data.nextTick,
    "tick" -> currentTick
  ),
  label = "signal_wait"
)
```

**Tipos de eventos reportados:**
- `journey_started` - Veículo inicia uma jornada
- `journey_completed` - Veículo completa jornada
- `enter_link` / `enter_micro_link` - Entra em um link
- `leave_link` / `leave_micro_link` - Sai de um link
- `signal_wait` - Aguarda semáforo vermelho
- `bus_waiting` - Ônibus aguardando passageiros
- `meso_update` / `micro_update` - Atualizações de estado

### ✅ 2. Scripts Python de Análise

Três scripts prontos para análise:

#### **`analyze_events.py`** - Análise Geral
```bash
python3 analyze_events.py seu_arquivo.jsonl --output results/
```

Gera:
- **7 gráficos PNG** com visualizações
- **3 arquivos CSV** com dados
- **Resumo em consola** com estatísticas

#### **`advanced_events_analysis.py`** - Análise Customizada
```bash
# Analisar veículo específico
python3 advanced_events_analysis.py file.jsonl --vehicle CAR_1

# Filtrar por tipo
python3 advanced_events_analysis.py file.jsonl --vehicle-type bus

# Por período de tempo
python3 advanced_events_analysis.py file.jsonl --tick-min 1000 --tick-max 2000

# Por modo de simulação
python3 advanced_events_analysis.py file.jsonl --mode MICRO
```

#### **`generate_sample_events.py`** - Gerar Dados de Teste
```bash
python3 generate_sample_events.py --count 2000
```

Cria arquivo JSONL com 2000 eventos realistas para testes.

## 📁 Arquivos Criados

```
scripts/
├── analyze_events.py                    # Script principal de análise
├── advanced_events_analysis.py          # Análise avançada e filtrada
├── generate_sample_events.py            # Gerar eventos de exemplo
├── EVENTS_ANALYSIS_README.md            # Documentação completa
└── README_REPORT_EVENTS.md              # Este arquivo
```

## 🚀 Quick Start

### 1. Gerar Eventos de Teste
```bash
cd scripts/
python3 generate_sample_events.py --count 2000
```

### 2. Analisar Eventos
```bash
# Análise completa
python3 analyze_events.py sample_events.jsonl --output analysis/

# Visualizar consola
ls -la analysis/
```

### 3. Explorar Dados
```bash
# Analisar apenas carros
python3 advanced_events_analysis.py sample_events.jsonl --vehicle-type car

# Analisar modo MICRO
python3 advanced_events_analysis.py sample_events.jsonl --mode MICRO

# Analisar link específico
python3 advanced_events_analysis.py sample_events.jsonl --link LINK_downtown_main_st_0
```

## 📊 Saídas Geradas

### Gráficos Gerados (PNG)
1. **01_event_distribution.png** - Top 15 tipos de eventos
2. **02_temporal_evolution.png** - Eventos por tick
3. **03_journey_times.png** - Distribuição de tempos de viagem
4. **04_average_speeds.png** - Distribuição de velocidades
5. **05_vehicles_by_type.png** - Eventos por tipo de veículo
6. **06_signal_waits.png** - Atrasos em sinais
7. **07_meso_vs_micro.png** - MESO vs MICRO (proporção)

### Dados CSV Exportados
- `event_types.csv` - Contagem de eventos por tipo
- `journey_times.csv` - Tempos de viagem
- `average_speeds.csv` - Velocidades médias

### Resumo em Consola
```
Total de eventos: 1,772
Período: tick 0 - 122
Veículos: 110
Links: 51
Viagens completadas: 132
Atrasos em sinais: 307
```

## 🔧 Integração com Sua Simulação

Para usar com sua simulação real:

1. **Executar simulação:**
   ```bash
   ./build-and-run.sh
   ```

2. **Encontrar arquivo de eventos:**
   ```bash
   find . -name "*.jsonl" -type f
   # Geralmente em: logs/ ou results/
   ```

3. **Analisar:**
   ```bash
   python3 scripts/analyze_events.py logs/events.jsonl --output my_analysis/
   ```

## 📈 Exemplos de Análise

### Encontrar Gargalos
```bash
python3 advanced_events_analysis.py events.jsonl --links-top 10
```
Mostra os 10 links mais usados e congestionados.

### Analisar Modo MICRO
```bash
python3 advanced_events_analysis.py events.jsonl --mode MICRO
```
Estatísticas apenas de eventos em modo microscópico.

### Investigar Veículo Específico
```bash
python3 advanced_events_analysis.py events.jsonl --vehicle CAR_0001
```
Cronologia completa de eventos do veículo.

### Período Específico
```bash
python3 advanced_events_analysis.py events.jsonl --tick-min 1000 --tick-max 2000
```
Análise apenas da simulação entre ticks 1000 e 2000.

## 🛠️ Requisitos

### Mínimo (apenas análise básica)
```bash
pip install pandas
```

### Completo (com gráficos)
```bash
pip install pandas numpy matplotlib seaborn
```

## 📝 Adicionar Mais Eventos aos Atores

Para adicionar reports em novos eventos:

```scala
// Em qualquer ator (Car.scala, Bus.scala, etc)

// Quando algo importante acontecer:
report(
  data = Map(
    "event_type" -> "seu_evento",
    "vehicle_id" -> getEntityId,
    "seu_campo" -> seuValor,
    "tick" -> currentTick
  ),
  label = "seu_evento"
)
```

Os dados são **automaticamente serializados** para JSONL pelos ReportManagers.

## 🐛 Troubleshooting

### "ModuleNotFoundError: pandas"
```bash
pip install pandas numpy matplotlib seaborn
```

### Gráficos vazios
Verifique se há eventos suficientes:
```bash
wc -l seu_arquivo.jsonl
head -5 seu_arquivo.jsonl | python3 -m json.tool
```

### Análise muito lenta
Use filtros para reduzir dados:
```bash
python3 advanced_events_analysis.py file.jsonl --tick-max 1000
```

## 📚 Documentação Completa

Ver: `EVENTS_ANALYSIS_README.md` para guia detalhado com:
- Descrição de todos os campos
- Exemplos avançados
- Customização de scripts
- Análises específicas

## 🎯 Casos de Uso

1. **Validação de Simulação:**
   - Verificar se eventos esperados ocorrem
   - Validar sequência de eventos
   - Detectar anomalias

2. **Performance:**
   - Identificar links congestionados
   - Analisar atrasos em sinais
   - Medir tempo de viagem

3. **Debugging:**
   - Investigar comportamento de veículo
   - Rastrear modo MESO vs MICRO
   - Auditar transições de estado

4. **Visualização:**
   - Mostrar resultados de simulação
   - Gerar relatórios
   - Comparar cenários

## 📞 Suporte

Para adicionar novo tipo de análise ou evento:

1. Editar script Python relevante
2. Adicionar função para processar evento
3. Opcionalmente, adicionar gráfico em `create_visualizations()`

Exemplos prontos em ambos os scripts para customização.

---

**Status:** ✅ Completo
**Versão:** 1.0
**Data:** 2026-02-16
