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
            logger.error(f"Failed to connect to Cassandra: {e}")
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
        query_parts = [f"SELECT * FROM {self.table}"]
        conditions = ["report_type = 'vehicle_flow'"]
        
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
        
        query = " ".join(query_parts)
        
        try:
            logger.info(f"Executing query: {query}")
            rows = self.session.execute(query)
            
            data = []
            for row in rows:
                # Parse report_data JSON
                report_data = json.loads(row.report_data) if isinstance(row.report_data, str) else row.report_data
                
                # Filter by event types if specified
                if event_types and report_data.get('event_type') not in event_types:
                    continue
                
                # Flatten the data structure
                record = {
                    'id': row.id,
                    'timestamp': row.timestamp,
                    'simulation_id': row.simulation_id,
                    'report_type': row.report_type,
                    **report_data  # Unpack report_data fields
                }
                data.append(record)
            
            df = pd.DataFrame(data)
            if not df.empty:
                df['timestamp'] = pd.to_datetime(df['timestamp'])
                df = df.sort_values('timestamp')
                
            logger.info(f"Retrieved {len(df)} records")
            return df
            
        except Exception as e:
            logger.error(f"Error retrieving data: {e}")
            return pd.DataFrame()
    
    def get_simulation_ids(self) -> List[str]:
        """Get list of available simulation IDs"""
        try:
            query = f"SELECT DISTINCT simulation_id FROM {self.table} WHERE report_type = 'vehicle_flow'"
            rows = self.session.execute(query)
            return [row.simulation_id for row in rows if row.simulation_id]
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