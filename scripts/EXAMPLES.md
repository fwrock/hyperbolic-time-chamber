# 🎓 Exemplos Completos de Uso

## Demonstração Prática dos Scripts

Este arquivo mostra exemplos reais de como usar cada script de análise.

## 1. Gerar Dados de Teste

```bash
cd /home/dean/PhD/hyperbolic-time-chamber/scripts

# Gerar 2000 eventos (leva ~1 segundo)
python3 generate_sample_events.py --count 2000 --output my_events.jsonl

# Output:
# 🔨 Gerando 2000 eventos de exemplo...
# 💾 Salvando em my_events.jsonl...
# ✓ 1772 eventos escritos em my_events.jsonl
#
# ✅ Arquivo gerado com sucesso!
```

## 2. Análise Geral com Gráficos

### Execução Simples

```bash
python3 analyze_events.py my_events.jsonl

# Output: 
# 🔍 Analisando eventos de: my_events.jsonl
# ✓ Carregados 1772 eventos
# 
# ====== RESUMO DA SIMULAÇÃO ======
# Eventos: 1,772
# Período: tick 0 - 122
# Atores: 110
# Veículos: 110
# ...
```

### Com Diretório Customizado

```bash
python3 analyze_events.py my_events.jsonl --output ./my_analysis/

# Cria diretório my_analysis/ com:
# - 7 gráficos PNG
# - 3 arquivos CSV
# - Resumo em consola
```

### Apenas Estatísticas (Sem Gráficos)

```bash
python3 analyze_events.py my_events.jsonl --no-plots

# Rápido! Apenas texto e CSV
```

### Apenas Gráficos (Sem CSV)

```bash
python3 analyze_events.py my_events.jsonl --no-csv

# Gera gráficos bonitos, sem exportar dados
```

## 3. Análises Avançadas e Filtradas

### Analisar um Veículo Específico

```bash
python3 advanced_events_analysis.py my_events.jsonl --vehicle CAR_0001

# Output:
# 📖 Carregando eventos de my_events.jsonl...
# ✓ 1772 eventos carregados
# 📊 156 eventos após filtros
#
# ======================================================================
# JORNADA DE VEÍCULO: CAR_0001
# ======================================================================
#
# Resumo:
#   Total de eventos: 156
#   Links atravessados: 12
#   Distância total: 4523.5 m
#   Entradas em links: 12
#   Saídas de links: 12
#   Esperas em sinais: 3
#   Mudanças de modo: 2
#
# Links traversados:
#     - LINK_downtown_main_st_0
#     - LINK_central_park_way_1
#     ...
#
# Cronologia de eventos (primeiros 20):
#    1. Tick    100 - journey_started
#    2. Tick    105 - route_planned
#    3. Tick    106 - enter_link         [MESO]
#    ...
```

### Filtrar por Tipo de Veículo

```bash
# Apenas carros
python3 advanced_events_analysis.py my_events.jsonl --vehicle-type car

# Apenas ônibus
python3 advanced_events_analysis.py my_events.jsonl --vehicle-type bus

# Apenas bicicletas
python3 advanced_events_analysis.py my_events.jsonl --vehicle-type bicycle

# Output mostra:
# 📊 289 eventos após filtros (apenas de carros)
# ... análise completa dos carros ...
```

### Filtrar por Tipo de Evento

```bash
# Apenas entradas em links
python3 advanced_events_analysis.py my_events.jsonl --event-type enter_link

# Apenas saídas de links
python3 advanced_events_analysis.py my_events.jsonl --event-type leave_link

# Apenas esperas em sinais
python3 advanced_events_analysis.py my_events.jsonl --event-type signal_wait
```

### Filtrar por Link Específico

```bash
python3 advanced_events_analysis.py my_events.jsonl --link LINK_downtown_main_st_0

# Análise apenas daquele link
# Mostra todos os veículos que passaram por ali
```

### Filtrar por Modo de Simulação

```bash
# Apenas eventos MICRO (microscópicos)
python3 advanced_events_analysis.py my_events.jsonl --mode MICRO

# Apenas eventos MESO (mesoscópicos)
python3 advanced_events_analysis.py my_events.jsonl --mode MESO

# Output compara proporção:
# MESO:  949 eventos ( 82.4%)
# MICRO: 167 eventos ( 17.6%)
```

### Filtrar por Período de Ticks

```bash
# Apenas período entre ticks 100 e 500
python3 advanced_events_analysis.py my_events.jsonl \
  --tick-min 100 --tick-max 500

# Análise apenas daquele período
```

### Combinar Múltiplos Filtros

```bash
# Carros em modo MICRO entre ticks 50 e 200
python3 advanced_events_analysis.py my_events.jsonl \
  --vehicle-type car \
  --mode MICRO \
  --tick-min 50 \
  --tick-max 200

# Análise muito específica!
```

### Analisar Links (Top N)

```bash
# Top 10 links mais usados
python3 advanced_events_analysis.py my_events.jsonl --links-top 10

# Top 20 links
python3 advanced_events_analysis.py my_events.jsonl --links-top 20

# Output:
# Link ID                        Entradas    Saídas     MESO  MICRO Velocidade
# ----...
# LINK_downtown_main_st_0            127        124        78     49    45.2 km/h
# LINK_industrial_zone_rd_1           89         87        45     44    38.1 km/h
# ...
```

## 4. Casos de Uso Práticos

### Caso 1: Encontrar Gargalos de Tráfego

```bash
# Ver quais links têm mais congestionamento
python3 advanced_events_analysis.py events.jsonl --links-top 15

# Output mostra links com mais entradas/saídas
# Se a razão Saídas < Entradas, há congestionamento
```

### Caso 2: Investigar Veículo que Demorou Muito

```bash
# Encontrar veículos com jornadas longas
python3 analyze_events.py events.jsonl --output analysis/

# Ver arquivo: analysis/journey_times.csv
# Identificar PIDs com tempos muito altos

# Depois analisar:
python3 advanced_events_analysis.py events.jsonl --vehicle CAR_X
```

### Caso 3: Validar Modo MICRO

```bash
# Ver proporção MICRO/MESO
python3 advanced_events_analysis.py events.jsonl

# Ver apenas eventos MICRO
python3 advanced_events_analysis.py events.jsonl --mode MICRO

# Verificar se veículos estão realmente mudando de modo
python3 advanced_events_analysis.py events.jsonl --vehicle CAR_1
# Olhar para "Mudanças de modo" na cronologia
```

### Caso 4: Analisar Impacto de Sinais

```bash
# Ver quantas esperas em sinais
python3 advanced_events_analysis.py events.jsonl --event-type signal_wait

# Ver distribuição no gráfico
python3 analyze_events.py events.jsonl --output analysis/
# Arquivo: analysis/06_signal_waits.png
```

## 5. Fluxo Completo: Do Zero ao Relatório

### Passo 1: Preparar Dados

```bash
cd scripts/

# Opção A: Gerar dados de teste
python3 generate_sample_events.py --count 5000 --output test.jsonl

# Opção B: Usar arquivo real da simulação
# cp ../logs/events.jsonl ./
```

### Passo 2: Análise Geral

```bash
# Executar análise completa
python3 analyze_events.py test.jsonl --output report_1/

# Visualizar resultados
ls -lh report_1/
# - 7 gráficos PNG
# - 3 CSV com dados
```

### Passo 3: Investigação Específica

```bash
# Ver top 20 links
python3 advanced_events_analysis.py test.jsonl --links-top 20

# Analisar veículos mais lentos
python3 analyze_events.py test.jsonl --output report_2/
# Olhar journey_times.csv, encontrar veículo X mais lento

# Investigar veículo X
python3 advanced_events_analysis.py test.jsonl --vehicle BIKE_0003 > vehicle_report.txt
```

### Passo 4: Gerar Relatório Final

```bash
# Exportar dados filtrados para Excel
python3 advanced_events_analysis.py test.jsonl --mode MICRO
# Gera: test_filtered.csv
# Abrir em Excel para análise manual

# Combinar tudo em um relatório
cat > SIMULATION_REPORT.md << 'EOF'
# Relatório de Simulação

## Estatísticas Gerais
- Total eventos: 1,772
- Período: ticks 0-122
- Veículos: 110

## Top Links Congestionados
(copiar output de --links-top 10)

## Análise MICRO vs MESO
MESO: 949 (53.6%)
MICRO: 167 (9.4%)

## Investigações Específicas
- Veículo mais lento: CAR_042 (tempo: 39 ticks)
- Maior atraso em sinal: 67 ticks
EOF
```

## 6. Combinações Úteis

### Debug Rápido
```bash
# Ver tudo rapidamente
python3 analyze_events.py file.jsonl --no-plots --no-csv
```

### Análise de Performance
```bash
# Tempos de viagem
python3 analyze_events.py file.jsonl --output analysis/
cat analysis/journey_times.csv
```

### Verificação de Modo Híbrido
```bash
# Ver proporção MICRO/MESO
python3 advanced_events_analysis.py file.jsonl

# Investigar um veículo específico
python3 advanced_events_analysis.py file.jsonl --vehicle CAR_001
# Procurar "Mudanças de modo" na cronologia
```

### Análise de Congestionamento
```bash
# Links mais usados
python3 advanced_events_analysis.py file.jsonl --links-top 20

# Tempos de espera em sinais
python3 analyze_events.py file.jsonl --output analysis/
# Ver gráfico: 06_signal_waits.png
```

## 7. Dicas de Performance

### Arquivo Muito Grande?

```bash
# ❌ Lento
python3 analyze_events.py huge_10GB_file.jsonl

# ✅ Rápido - Filtrar primeiro
python3 advanced_events_analysis.py huge_10GB_file.jsonl --tick-max 5000
# Exporta CSV com dados filtrados
# Depois analisar CSV no Excel
```

### Salvar Saída em Arquivo

```bash
# Redirecionar output
python3 advanced_events_analysis.py file.jsonl --vehicle CAR_1 > report.txt

# Ou com tee (vê na tela E salva)
python3 analyze_events.py file.jsonl | tee analysis.log
```

## 8. Próximas Análises

Após isso, você pode:

1. **Abrir CSVs no Excel** para análise manual
2. **Combinar gráficos** em um PowerPoint
3. **Extrair dados** para Python/R para análises customizadas
4. **Criar dashboards** com Plotly/Dash

## Resumo Rápido

| Tarefa | Comando |
|--------|---------|
| Teste rápido | `python3 generate_sample_events.py --count 1000 && python3 analyze_events.py sample_events.jsonl --no-plots` |
| Análise completa | `python3 analyze_events.py events.jsonl --output results/` |
| Um veículo | `python3 advanced_events_analysis.py events.jsonl --vehicle CAR_1` |
| Um tipo | `python3 advanced_events_analysis.py events.jsonl --vehicle-type bus` |
| Período | `python3 advanced_events_analysis.py events.jsonl --tick-min 1000 --tick-max 2000` |
| Links | `python3 advanced_events_analysis.py events.jsonl --links-top 20` |
| MICRO mode | `python3 advanced_events_analysis.py events.jsonl --mode MICRO` |

---

Boa análise! 📊
