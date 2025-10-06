-- Script de inicialização do schema Cassandra para HTC Simulator
-- Este arquivo será executado automaticamente pelo manage_cassandra.sh

-- Criar keyspace para a simulação
CREATE KEYSPACE IF NOT EXISTS htc_simulation 
WITH REPLICATION = {
    'class': 'SimpleStrategy',
    'replication_factor': 1
};

USE htc_simulation;

-- Tabela principal para fluxo de veículos
CREATE TABLE IF NOT EXISTS vehicle_flow (
    car_id text,
    link_id text,
    timestamp double,
    tick bigint,
    direction text,
    lane int,
    event_type text,
    data text,
    created_at timestamp,
    PRIMARY KEY (car_id, timestamp, link_id)
) WITH CLUSTERING ORDER BY (timestamp ASC, link_id ASC);

-- Índices para consultas eficientes
CREATE INDEX IF NOT EXISTS vehicle_flow_link_idx ON vehicle_flow (link_id);
CREATE INDEX IF NOT EXISTS vehicle_flow_tick_idx ON vehicle_flow (tick);
CREATE INDEX IF NOT EXISTS vehicle_flow_event_idx ON vehicle_flow (event_type);
CREATE INDEX IF NOT EXISTS vehicle_flow_created_idx ON vehicle_flow (created_at);

-- Tabela para estatísticas de links (opcional)
CREATE TABLE IF NOT EXISTS link_stats (
    link_id text,
    date date,
    hour int,
    vehicle_count counter,
    PRIMARY KEY (link_id, date, hour)
);

-- Tabela para jornadas completas (opcional)
CREATE TABLE IF NOT EXISTS vehicle_journeys (
    car_id text,
    journey_start timestamp,
    journey_end timestamp,
    origin text,
    destination text,
    total_distance double,
    total_time double,
    status text,
    PRIMARY KEY (car_id, journey_start)
) WITH CLUSTERING ORDER BY (journey_start DESC);

-- Tabela para eventos do sistema (logs, debugging)
CREATE TABLE IF NOT EXISTS system_events (
    id uuid,
    timestamp timestamp,
    event_type text,
    component text,
    message text,
    data text,
    PRIMARY KEY (id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);

-- Comentários explicativos
-- vehicle_flow: Tabela principal com todos os eventos de movimento
-- link_stats: Estatísticas agregadas por link e hora (uso opcional)  
-- vehicle_journeys: Resumo das jornadas completas dos veículos
-- system_events: Logs e eventos do sistema para debugging