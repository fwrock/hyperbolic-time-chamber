# ClickHouse — Queries de Validação

Banco padrão: `htc` (ou o valor de `database` configurado).  
Porta HTTP: `8123`. Porta nativa (TCP): `9000`.

---

## 1. Saúde e conectividade

```sql
-- Versão do ClickHouse
SELECT version();

-- Verifica se o banco existe
SELECT name FROM system.databases WHERE name = 'htc';

-- Lista as tabelas criadas
SELECT name, engine, total_rows, total_bytes
FROM system.tables
WHERE database = 'htc'
ORDER BY name;
```

---

## 2. Tabela principal — `vehicle_link_events`

```sql
-- Total de eventos inseridos
SELECT count() AS total_events
FROM htc.vehicle_link_events;

-- Contagem por tipo de evento
SELECT event_type, count() AS qty
FROM htc.vehicle_link_events
GROUP BY event_type;

-- Primeiros 20 registros (verificação visual)
SELECT *
FROM htc.vehicle_link_events
LIMIT 20;

-- Últimos eventos inseridos (ordenado por tick decrescente)
SELECT *
FROM htc.vehicle_link_events
ORDER BY tick DESC
LIMIT 20;

-- Eventos por simulation_id
SELECT simulation_id, count() AS total, min(tick) AS first_tick, max(tick) AS last_tick
FROM htc.vehicle_link_events
GROUP BY simulation_id
ORDER BY total DESC;

-- Distribuição de eventos por tick (ritmo de inserção)
SELECT tick, count() AS events_in_tick
FROM htc.vehicle_link_events
GROUP BY tick
ORDER BY tick
LIMIT 100;

-- Links com mais movimentação
SELECT link_id, count() AS total_events
FROM htc.vehicle_link_events
GROUP BY link_id
ORDER BY total_events DESC
LIMIT 20;

-- Contagem de veículos únicos
SELECT uniq(vehicle_id) AS unique_vehicles
FROM htc.vehicle_link_events;

-- Eventos por tipo de ator (car, bus, bicycle…)
SELECT actor_type, count() AS qty
FROM htc.vehicle_link_events
GROUP BY actor_type
ORDER BY qty DESC;
```

---

## 3. Tabela agregada — `link_vehicle_counts`

```sql
-- Total de linhas na tabela agregada
SELECT count() AS rows
FROM htc.link_vehicle_counts;

-- Entradas e saídas totais por link
SELECT
    link_id,
    sum(enters)     AS total_enters,
    sum(leaves)     AS total_leaves,
    max(peak_count) AS peak_vehicles
FROM htc.link_vehicle_counts
GROUP BY link_id
ORDER BY total_enters DESC
LIMIT 20;

-- Evolução do tráfego ao longo do tempo (por tick)
SELECT
    tick,
    sum(enters)     AS enters,
    sum(leaves)     AS leaves,
    max(peak_count) AS peak
FROM htc.link_vehicle_counts
WHERE simulation_id = '<SIMULATION_ID>'
GROUP BY tick
ORDER BY tick
LIMIT 200;

-- Links congestionados (peak_count mais alto)
SELECT
    link_id,
    tick,
    peak_count
FROM htc.link_vehicle_counts
ORDER BY peak_count DESC
LIMIT 10;
```

> **Nota:** `link_vehicle_counts` usa `SummingMergeTree`. Para resultados
> consistentes use sempre `sum()` / `max()` nas colunas agregadas, pois
> partes ainda podem não ter sido mescladas.

---

## 4. View materializada — `link_vehicle_counts_mv`

```sql
-- Confirma que a MV existe e está ativa
SELECT name, is_populated
FROM system.tables
WHERE database = 'htc' AND name = 'link_vehicle_counts_mv';
```

---

## 5. Diagnóstico de ingestão

```sql
-- Taxa de inserção: eventos por minuto (usando real_time_ms)
SELECT
    toStartOfMinute(fromUnixTimestamp64Milli(real_time_ms)) AS minute,
    count() AS events
FROM htc.vehicle_link_events
GROUP BY minute
ORDER BY minute;

-- Detecta ticks faltando (gap > 1)
SELECT
    tick,
    neighbor(tick, 1) - tick AS gap_to_next
FROM (
    SELECT DISTINCT tick
    FROM htc.vehicle_link_events
    ORDER BY tick
)
WHERE neighbor(tick, 1) - tick > 1
LIMIT 20;

-- Verifica se entradas e saídas estão balanceadas por link
SELECT
    link_id,
    countIf(event_type = 'enter') AS enters,
    countIf(event_type = 'leave') AS leaves,
    enters - leaves               AS balance
FROM htc.vehicle_link_events
GROUP BY link_id
HAVING abs(balance) > 50
ORDER BY abs(balance) DESC
LIMIT 20;
```

---

## 6. Comandos via cURL (HTTP API)

Substitua `<HOST>` pelo IP/hostname do container ClickHouse.

```bash
# Saúde
curl -s "http://<HOST>:8123/ping"

# Total de eventos (autenticado)
curl -s "http://<HOST>:8123/?query=SELECT+count()+FROM+htc.vehicle_link_events" \
     -H "X-ClickHouse-User: default" \
     -H "X-ClickHouse-Key: <PASSWORD>"

# Últimos 5 eventos em formato TabSeparated
curl -s "http://<HOST>:8123/?query=SELECT+*+FROM+htc.vehicle_link_events+ORDER+BY+tick+DESC+LIMIT+5+FORMAT+TabSeparated" \
     -H "X-ClickHouse-User: default" \
     -H "X-ClickHouse-Key: <PASSWORD>"
```

---

## 7. Limpeza (use com cuidado)

```sql
-- Remove todos os dados de uma simulação específica
ALTER TABLE htc.vehicle_link_events
DELETE WHERE simulation_id = '<SIMULATION_ID>';

-- Trunca todas as tabelas (ambiente de dev/teste apenas)
TRUNCATE TABLE htc.vehicle_link_events;
TRUNCATE TABLE htc.link_vehicle_counts;
```
