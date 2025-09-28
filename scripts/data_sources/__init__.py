"""
Data sources package for traffic analysis system
"""

# Usar imports absolutos para evitar problemas quando executado como script principal
try:
    from data_sources.cassandra_source import CassandraDataSource
    from data_sources.file_sources import CSVDataSource, JSONDataSource, DataSourceFactory
except ImportError:
    # Fallback para imports relativos
    from .cassandra_source import CassandraDataSource
    from .file_sources import CSVDataSource, JSONDataSource, DataSourceFactory

__all__ = [
    'CassandraDataSource',
    'CSVDataSource', 
    'JSONDataSource',
    'DataSourceFactory'
]