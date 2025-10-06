-- Keyspaces para Pekko Persistence
CREATE KEYSPACE IF NOT EXISTS htc_persistence
WITH REPLICATION = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

CREATE KEYSPACE IF NOT EXISTS htc_reports
WITH REPLICATION = {
  'class': 'SimpleStrategy', 
  'replication_factor': 1
};

-- Use keyspace para criar tabelas do Pekko Persistence
USE htc_persistence;

-- Tabela de messages (journal) - Schema oficial do Pekko Persistence
CREATE TABLE IF NOT EXISTS messages (
  persistence_id text,
  partition_nr bigint,
  sequence_nr bigint,
  timestamp timeuuid,
  timebucket text,
  writer_uuid text,
  ser_id int,
  ser_manifest text,
  event_manifest text,
  event blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  tags set<text>,
  PRIMARY KEY ((persistence_id, partition_nr), sequence_nr, timestamp)
) WITH CLUSTERING ORDER BY (sequence_nr ASC, timestamp ASC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy',
    'enabled': true
  };

-- Tabela de tag_views - Schema oficial do Pekko Persistence
CREATE TABLE IF NOT EXISTS tag_views (
  tag_name text,
  timebucket bigint,
  timestamp timeuuid,
  persistence_id text,
  tag_pid_sequence_nr bigint,
  sequence_nr bigint,
  writer_uuid text,
  ser_id int,
  ser_manifest text,
  event_manifest text,
  event blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY ((tag_name, timebucket), timestamp, persistence_id, tag_pid_sequence_nr)
) WITH CLUSTERING ORDER BY (timestamp ASC, persistence_id ASC, tag_pid_sequence_nr ASC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy',
    'enabled': true
  };

-- Tabela de tag_write_progress
CREATE TABLE IF NOT EXISTS tag_write_progress(
  persistence_id text,
  tag text,
  sequence_nr bigint,
  tag_pid_sequence_nr bigint,
  offset timeuuid,
  PRIMARY KEY (persistence_id, tag)
);

-- Tabela de tag_scanning
CREATE TABLE IF NOT EXISTS tag_scanning(
  persistence_id text,
  sequence_nr bigint,
  PRIMARY KEY (persistence_id)
);

-- Tabela de metadata (para snapshots)
CREATE TABLE IF NOT EXISTS metadata(
  persistence_id text PRIMARY KEY,
  deleted_to bigint,
  properties map<text,text>
);

-- Tabela de snapshots - Schema oficial do Pekko Persistence  
CREATE TABLE IF NOT EXISTS snapshots (
  persistence_id text,
  sequence_nr bigint,
  timestamp timeuuid,
  ser_id int,
  ser_manifest text,
  snapshot_data blob,
  snapshot blob,
  meta_ser_id int,
  meta_ser_manifest text,
  meta blob,
  PRIMARY KEY (persistence_id, sequence_nr)
) WITH CLUSTERING ORDER BY (sequence_nr DESC)
  AND gc_grace_seconds = 864000
  AND compaction = {
    'class': 'SizeTieredCompactionStrategy',
    'enabled': true
  };

-- Tabela de all_persistence_ids
CREATE TABLE IF NOT EXISTS all_persistence_ids(
  persistence_id text PRIMARY KEY
);

-- Use keyspace de reports
USE htc_reports;

-- Tabela de relatórios de simulação
CREATE TABLE IF NOT EXISTS simulation_reports (
  id UUID PRIMARY KEY,
  created_at TIMESTAMP,
  data TEXT,
  node_id TEXT,
  report_type TEXT,
  simulation_id TEXT,
  timestamp TIMESTAMP
);

-- Índices para consultas eficientes
CREATE INDEX IF NOT EXISTS idx_report_type ON simulation_reports (report_type);
CREATE INDEX IF NOT EXISTS idx_simulation_id ON simulation_reports (simulation_id);