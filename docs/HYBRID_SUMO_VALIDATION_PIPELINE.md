# HTC × SUMO Validation Pipeline (Micro-Meso)

Este guia define um fluxo incremental para validar o modo híbrido MICRO/MESO do HTC contra o SUMO, com foco em:

- Cenário equivalente para ambos simuladores
- Comparabilidade de métricas/reports
- Diagnóstico das principais diferenças
- Ajustes orientados por evidências (sem modificar core HTC sem aprovação)

## Scripts adicionados

- `scripts/htc_to_sumo_scenario.py`
  - Converte cenário HTC para artefatos SUMO (`nodes`, `edges`, `network`, `trips`, `routes`, `run.sumocfg`)
- `scripts/compare_sumo_htc_results.py`
  - Compara `tripinfo.xml` (SUMO) com eventos JSONL do HTC
  - Gera métricas JSON, relatório Markdown e gráficos PNG
- `scripts/run_hybrid_sumo_validation.py`
  - Orquestra geração, execução e comparação com `--config` ou `--interactive`
- `scripts/example_configs/hybrid_sumo_validation.yaml`
  - Configuração de referência do pipeline

## Fase 1 (já implementada)

### 1) Gerar cenários equivalentes

Pipeline de geração:

1. `migrate_to_hybrid.py` cria cenário híbrido HTC a partir do input mobility
2. Cenário HTC é copiado para:
   - `/home/dean/hyperbolic-time-chamber/simulations/input/<scenario_name>`
3. `htc_to_sumo_scenario.py` gera cenário SUMO a partir do cenário HTC

Comando:

```bash
python scripts/run_hybrid_sumo_validation.py --config scripts/example_configs/hybrid_sumo_validation.yaml generate
```

### 2) Rodar SUMO + HTC

Por configuração padrão:

- SUMO roda automaticamente (`run_sumo: true`)
- HTC fica opcional (`run_htc: false`) para evitar execução pesada sem sua confirmação

Comando:

```bash
python scripts/run_hybrid_sumo_validation.py --config scripts/example_configs/hybrid_sumo_validation.yaml run
```

### 3) Comparar outputs

Comando:

```bash
python scripts/run_hybrid_sumo_validation.py --config scripts/example_configs/hybrid_sumo_validation.yaml compare
```

Artefatos gerados em:

- `scripts/output/validation_runs/comparison/<scenario_name>/comparison.json`
- `scripts/output/validation_runs/comparison/<scenario_name>/comparison.md`
- gráficos (`duration_distribution.png`, `duration_scatter.png`) quando `matplotlib` está disponível

## Fase 2 (recomendada)

Após rodar a Fase 1, usar o relatório para calibrar:

1. **Car-following (micro)**
   - aceleração/desaceleração
   - reaction time
   - gap mínimo
2. **Interseções/semafórico**
   - tempos de fase e offsets
   - regras de prioridade
3. **Roteamento**
   - confirmar mesmo algoritmo e custo (tempo/distância)

## Fase 3 (robustez)

Executar múltiplas seeds e níveis de demanda para estabilidade estatística:

- seed: 42, 123, 777
- demanda: baixa/média/alta
- comparar score médio e variância

## Notas importantes

- A execução do HTC usa `build-and-run.sh` (conforme seu fluxo atual).
- O cenário do HTC agora é parametrizável por variável de ambiente:
  - `HTC_SCENARIO_NAME=<nome_do_cenario> ./build-and-run.sh`
- O `docker-compose.yml` resolve automaticamente:
  - `HTC_SIMULATION_CONFIG_FILE`
  - `HTC_MOBILITY_CITY_MAP_FILE`
  com base em `HTC_SCENARIO_NAME`.
- O pipeline não altera código core do HTC.
- Caso você aprove mudanças no HTC, a recomendação é aplicar apenas expansão incremental e controlada por feature flag/config.

## Checklist de validação

- [ ] Mesmo conjunto OD (origem/destino) entre SUMO e HTC
- [ ] Mesma janela temporal (begin/end)
- [ ] IDs de viagem consistentes para pareamento
- [ ] Métricas de duração e distância dentro de tolerância alvo
- [ ] Principais outliers explicados por diferenças modeladas
