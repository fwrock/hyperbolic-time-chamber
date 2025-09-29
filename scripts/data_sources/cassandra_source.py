"""
Cassandra data source connector for vehicle flow analysis
"""
import pandas as pd
import sys
import logging
from pathlib import Path
from typing import Dict, List, Optional, Any
import json
from datetime import datetime, timedelta

try:
    from cassandra.cluster import Cluster
    from cassandra.auth import PlainTextAuthProvider
except ImportError:
    print("Warning: cassandra-driver not installed. Install with: pip install cassandra-driver")
    Cluster = None

# Handle imports for both relative and absolute paths
try:
    from ..config import CASSANDRA_CONFIG
except ImportError:
    try:
        from config import CASSANDRA_CONFIG
    except ImportError:
        # Fallback configuration
        CASSANDRA_CONFIG = {
            'hosts': ['127.0.0.1'],
            'port': 9042,
            'keyspace': 'htc_keyspace'
        }

logger = logging.getLogger(__name__)

class CassandraDataSource:
    """
    Connector for retrieving vehicle flow data from Cassandra database
    """
    
    def __init__(self, hosts: List[str] = None, port: int = None, 
                 keyspace: str = None, local_dc: str = None):
        """
        Initialize Cassandra connection
        
        Args:
            hosts: List of Cassandra host addresses
            port: Cassandra port
            keyspace: Target keyspace
            local_dc: Local datacenter name
        """
        self.hosts = hosts or CASSANDRA_CONFIG['hosts']
        self.port = port or CASSANDRA_CONFIG['port']
        self.keyspace = keyspace or CASSANDRA_CONFIG['keyspace']
        self.local_dc = local_dc or CASSANDRA_CONFIG['local_dc']
        self.table = CASSANDRA_CONFIG['table']
        
        self.cluster = None
        self.session = None
        self._connect()
    
    def _connect(self):
        """Establish connection to Cassandra cluster"""
        try:
            self.cluster = Cluster(
                self.hosts, 
                port=self.port,
                executor_threads=4
            )
            self.session = self.cluster.connect(self.keyspace)
            logger.info(f"Connected to Cassandra at {self.hosts}:{self.port}")
        except Exception as e:
            logger.error(f"Failed to connect to Cassandra: {type(e).__name__}: {str(e)}")
            logger.error(f"Connection details - Hosts: {self.hosts}, Port: {self.port}, Keyspace: {self.keyspace}")
            raise
    
    def get_vehicle_flow_data(self, 
                             simulation_id: str = None,
                             event_types: List[str] = None,
                             start_time: datetime = None,
                             end_time: datetime = None,
                             limit: int = None) -> pd.DataFrame:
        """
        Retrieve vehicle flow data from Cassandra
        
        Args:
            simulation_id: Filter by simulation ID
            event_types: Filter by event types
            start_time: Filter by start timestamp
            end_time: Filter by end timestamp
            limit: Maximum number of records
            
        Returns:
            DataFrame with vehicle flow data
        """
        # Buscar tanto eventos de flow quanto eventos de tráfego
        flow_data = self._get_data_by_type('vehicle_flow', simulation_id, start_time, end_time, limit)
        traffic_data = self._get_data_by_type('traffic_events', simulation_id, start_time, end_time, limit)
        
        # Combinar os DataFrames
        if not flow_data.empty and not traffic_data.empty:
            combined_data = pd.concat([flow_data, traffic_data], ignore_index=True)
        elif not flow_data.empty:
            combined_data = flow_data
        elif not traffic_data.empty:
            combined_data = traffic_data
        else:
            combined_data = pd.DataFrame()
            
        return combined_data
    
    def _get_data_by_type(self, 
                         report_type: str,
                         simulation_id: str = None,
                         start_time: datetime = None,
                         end_time: datetime = None,
                         limit: int = None) -> pd.DataFrame:
        """
        Helper method to retrieve data by report type
        """
        query_parts = [f"SELECT * FROM {self.table}"]
        conditions = [f"report_type = '{report_type}'"]
        
        if simulation_id:
            conditions.append(f"simulation_id = '{simulation_id}'")
            
        if start_time:
            conditions.append(f"timestamp >= '{start_time.isoformat()}'")
            
        if end_time:
            conditions.append(f"timestamp <= '{end_time.isoformat()}'")
        
        if conditions:
            query_parts.append("WHERE " + " AND ".join(conditions))
            
        if limit:
            query_parts.append(f"LIMIT {limit}")
        
        # Add ALLOW FILTERING for queries with non-primary key conditions
        if any("simulation_id" in cond for cond in conditions):
            query_parts.append("ALLOW FILTERING")
        
        query = " ".join(query_parts)
        
        try:
            logger.info(f"Executing query: {query}")
            if limit is None:
                logger.info("🚀 Carregando TODOS os dados do Cassandra (sem limite)...")
            else:
                logger.info(f"📊 Carregando até {limit} registros...")
                
            rows = self.session.execute(query)
            
            data = []
            for i, row in enumerate(rows):
                # Parse data - should be JSON now (after Scala code fix)
                data_str = row.data if isinstance(row.data, str) else str(row.data)
                
                # Try JSON parsing first (new format)
                try:
                    report_data = json.loads(data_str)
                except json.JSONDecodeError:
                    # Fallback: Parse HashMap format for old data
                    report_data = {}
                    if data_str.startswith('HashMap(') and data_str.endswith(')'):
                        # Remove HashMap( and )
                        content = data_str[8:-1]
                        # Split by ", " but be careful with -> in values
                        pairs = content.split(', ')
                        for pair in pairs:
                            if ' -> ' in pair:
                                key, value = pair.split(' -> ', 1)
                                report_data[key.strip()] = value.strip()
                        logger.debug(f"Parsed HashMap format for backward compatibility")
                    else:
                        logger.warning(f"Could not parse data: {data_str[:100]}...")
                        continue
                
                # Filter by event types - not needed here as filtering is done at method level
                # if event_types and report_data.get('event_type') not in event_types:
                #     continue
                
                # Flatten the data structure
                record = {
                    'id': row.id,
                    'timestamp': row.timestamp,
                    'simulation_id': row.simulation_id,
                    'report_type': row.report_type,
                    **report_data  # Unpack report_data fields
                }
                data.append(record)
                
                # Progress logging for large datasets
                if (i + 1) % 10000 == 0:
                    logger.info(f"📊 Processados {i + 1} registros...")
            
            df = pd.DataFrame(data)
            if not df.empty:
                # PRIORITY: Use tick as primary time reference for simulation analysis
                # Timestamp is just execution metadata and causes false differences
                if 'tick' in df.columns:
                    logger.info("🎯 Using tick as primary time reference (simulation time)")
                    # Keep original timestamp for reference but use tick for analysis
                    df['execution_timestamp'] = df['timestamp']  # Preserve original
                    # Convert tick to standardized simulation time starting from epoch
                    base_time = pd.Timestamp('2025-01-01 00:00:00')
                    df['timestamp'] = base_time + pd.to_timedelta(df['tick'], unit='s')
                    logger.info(f"✅ Converted {len(df)} records using tick-based simulation time")
                else:
                    # Fallback to timestamp conversion only if tick is not available
                    logger.warning("⚠️ No tick column found, falling back to timestamp conversion")
                    try:
                        # Convert timestamps safely
                        df['timestamp'] = pd.to_datetime(df['timestamp'], errors='coerce')
                        
                        # Filter out timestamps that are clearly invalid (before 2020 or after 2030)
                        current_year = datetime.now().year
                        min_year = 2020
                        max_year = current_year + 10  # Allow some future dates but not centuries ahead
                        
                        # Count invalid timestamps before filtering
                        if not df['timestamp'].isna().all():
                            valid_timestamps = df['timestamp'].dropna()
                            if not valid_timestamps.empty:
                                # Check year range
                                invalid_year_mask = (
                                    (valid_timestamps.dt.year < min_year) | 
                                    (valid_timestamps.dt.year > max_year)
                                )
                                
                                # Update DataFrame to mark invalid years as NaT
                                df.loc[df['timestamp'].dt.year < min_year, 'timestamp'] = pd.NaT
                                df.loc[df['timestamp'].dt.year > max_year, 'timestamp'] = pd.NaT
                        
                        # Remove rows with invalid timestamps
                        invalid_count = df['timestamp'].isna().sum()
                        if invalid_count > 0:
                            logger.warning(f"⚠️ Removed {invalid_count} records with invalid/unrealistic timestamps")
                            df = df.dropna(subset=['timestamp'])
                        
                        # If we still have valid data, continue processing
                        if not df.empty:
                            logger.info(f"✅ {len(df)} records with valid timestamps remaining")
                        else:
                            logger.error("❌ No valid timestamp or tick data available")
                            return pd.DataFrame()
                    except Exception as e:
                        logger.error(f"❌ Error processing timestamps: {e}")
                        return pd.DataFrame()
                
                # Sort by timestamp (now either tick-based or real timestamp)
                df = df.sort_values('timestamp')
                
            logger.info(f"Retrieved {len(df)} records")
            return df
            
        except Exception as e:
            logger.error(f"Error retrieving data: {e}")
            return pd.DataFrame()
    
    def get_simulation_ids(self) -> List[str]:
        """Get list of available simulation IDs"""
        try:
            # Note: DISTINCT on non-key columns requires ALLOW FILTERING
            query = f"SELECT simulation_id FROM {self.table} WHERE report_type = 'vehicle_flow' ALLOW FILTERING"
            rows = self.session.execute(query)
            # Manually deduplicate since DISTINCT might not work on simulation_id
            unique_ids = set()
            for row in rows:
                if row.simulation_id:
                    unique_ids.add(row.simulation_id)
            return list(unique_ids)
        except Exception as e:
            logger.error(f"Error getting simulation IDs: {e}")
            return []
    
    def get_event_summary(self, simulation_id: str = None) -> pd.DataFrame:
        """
        Get summary of events by type
        
        Args:
            simulation_id: Optional simulation ID filter
            
        Returns:
            DataFrame with event counts by type
        """
        try:
            conditions = ["report_type = 'vehicle_flow'"]
            if simulation_id:
                conditions.append(f"simulation_id = '{simulation_id}'")
            
            query = f"SELECT report_data FROM {self.table} WHERE " + " AND ".join(conditions)
            rows = self.session.execute(query)
            
            event_counts = {}
            for row in rows:
                report_data = json.loads(row.report_data) if isinstance(row.report_data, str) else row.report_data
                event_type = report_data.get('event_type', 'unknown')
                event_counts[event_type] = event_counts.get(event_type, 0) + 1
            
            return pd.DataFrame([
                {'event_type': event_type, 'count': count}
                for event_type, count in event_counts.items()
            ])
            
        except Exception as e:
            logger.error(f"Error getting event summary: {e}")
            return pd.DataFrame()
    
    def close(self):
        """Close Cassandra connection"""
        if self.cluster:
            self.cluster.shutdown()
            logger.info("Cassandra connection closed")

    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()