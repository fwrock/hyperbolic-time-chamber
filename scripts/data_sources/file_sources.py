"""
File-based data sources (CSV/JSON) for vehicle flow analysis
"""
import pandas as pd
import json
from pathlib import Path
from typing import List, Dict, Optional, Any, Union
import logging
import glob
from datetime import datetime

# Import absoluto para evitar problemas quando executado como script principal
try:
    from config import CSV_DATA_PATH, JSON_DATA_PATH
except ImportError:
    # Fallback para import relativo
    from ..config import CSV_DATA_PATH, JSON_DATA_PATH

logger = logging.getLogger(__name__)

class FileDataSource:
    """
    Base class for file-based data sources
    """
    
    def __init__(self, data_path: Path):
        """
        Initialize file data source
        
        Args:
            data_path: Path to data directory
        """
        self.data_path = Path(data_path)
        if not self.data_path.exists():
            logger.warning(f"Data path does not exist: {self.data_path}")
    
    def list_files(self, pattern: str = "*") -> List[Path]:
        """List files matching pattern"""
        if not self.data_path.exists():
            return []
        return list(self.data_path.glob(pattern))

class CSVDataSource(FileDataSource):
    """
    CSV file data source for vehicle flow analysis
    """
    
    def __init__(self, data_path: Path = None):
        """
        Initialize CSV data source
        
        Args:
            data_path: Path to CSV data directory
        """
        super().__init__(data_path or CSV_DATA_PATH)
    
    def load_vehicle_flow_data(self, 
                              file_pattern: str = "*vehicle_flow*.csv",
                              combine_files: bool = True) -> pd.DataFrame:
        """
        Load vehicle flow data from CSV files
        
        Args:
            file_pattern: Pattern to match CSV files
            combine_files: Whether to combine multiple files
            
        Returns:
            DataFrame with vehicle flow data
        """
        csv_files = self.list_files(file_pattern)
        
        if not csv_files:
            logger.warning(f"No CSV files found matching pattern: {file_pattern}")
            return pd.DataFrame()
        
        logger.info(f"Found {len(csv_files)} CSV files")
        
        if combine_files:
            dfs = []
            for file_path in csv_files:
                try:
                    df = pd.read_csv(file_path)
                    df['source_file'] = file_path.name
                    dfs.append(df)
                    logger.info(f"Loaded {len(df)} records from {file_path.name}")
                except Exception as e:
                    logger.error(f"Error loading {file_path}: {e}")
            
            if dfs:
                combined_df = pd.concat(dfs, ignore_index=True)
                # Convert timestamp column if exists
                if 'timestamp' in combined_df.columns:
                    combined_df['timestamp'] = pd.to_datetime(combined_df['timestamp'])
                return combined_df
        else:
            # Return first file only
            try:
                df = pd.read_csv(csv_files[0])
                if 'timestamp' in df.columns:
                    df['timestamp'] = pd.to_datetime(df['timestamp'])
                return df
            except Exception as e:
                logger.error(f"Error loading {csv_files[0]}: {e}")
        
        return pd.DataFrame()
    
    def get_available_reports(self) -> List[Dict[str, Any]]:
        """Get information about available CSV report files"""
        csv_files = self.list_files("*.csv")
        
        reports = []
        for file_path in csv_files:
            try:
                # Get basic file info
                stat = file_path.stat()
                
                # Try to read first few rows to get column info
                sample_df = pd.read_csv(file_path, nrows=5)
                
                report_info = {
                    'filename': file_path.name,
                    'path': str(file_path),
                    'size_mb': round(stat.st_size / (1024 * 1024), 2),
                    'modified': datetime.fromtimestamp(stat.st_mtime),
                    'columns': list(sample_df.columns),
                    'sample_rows': len(sample_df)
                }
                
                # Try to get row count (for smaller files)
                if stat.st_size < 100 * 1024 * 1024:  # Less than 100MB
                    try:
                        full_df = pd.read_csv(file_path)
                        report_info['total_rows'] = len(full_df)
                    except:
                        report_info['total_rows'] = 'Unknown (large file)'
                else:
                    report_info['total_rows'] = 'Unknown (large file)'
                
                reports.append(report_info)
                
            except Exception as e:
                logger.error(f"Error analyzing {file_path}: {e}")
        
        return reports

class JSONDataSource(FileDataSource):
    """
    JSON file data source for vehicle flow analysis
    """
    
    def __init__(self, data_path: Path = None):
        """
        Initialize JSON data source
        
        Args:
            data_path: Path to JSON data directory
        """
        super().__init__(data_path or JSON_DATA_PATH)
    
    def load_vehicle_flow_data(self, 
                              file_pattern: str = "*vehicle_flow*.json",
                              combine_files: bool = True) -> pd.DataFrame:
        """
        Load vehicle flow data from JSON files
        
        Args:
            file_pattern: Pattern to match JSON files
            combine_files: Whether to combine multiple files
            
        Returns:
            DataFrame with vehicle flow data
        """
        json_files = self.list_files(file_pattern)
        
        if not json_files:
            logger.warning(f"No JSON files found matching pattern: {file_pattern}")
            return pd.DataFrame()
        
        logger.info(f"Found {len(json_files)} JSON files")
        
        if combine_files:
            all_records = []
            for file_path in json_files:
                try:
                    with open(file_path, 'r') as f:
                        data = json.load(f)
                    
                    # Handle different JSON structures
                    if isinstance(data, list):
                        records = data
                    elif isinstance(data, dict) and 'records' in data:
                        records = data['records']
                    elif isinstance(data, dict) and 'data' in data:
                        records = data['data']
                    else:
                        records = [data]
                    
                    # Add source file information
                    for record in records:
                        if isinstance(record, dict):
                            record['source_file'] = file_path.name
                    
                    all_records.extend(records)
                    logger.info(f"Loaded {len(records)} records from {file_path.name}")
                    
                except Exception as e:
                    logger.error(f"Error loading {file_path}: {e}")
            
            if all_records:
                df = pd.json_normalize(all_records)
                # Convert timestamp column if exists
                if 'timestamp' in df.columns:
                    df['timestamp'] = pd.to_datetime(df['timestamp'])
                return df
        else:
            # Return first file only
            try:
                with open(json_files[0], 'r') as f:
                    data = json.load(f)
                
                if isinstance(data, list):
                    df = pd.json_normalize(data)
                else:
                    df = pd.json_normalize([data])
                
                if 'timestamp' in df.columns:
                    df['timestamp'] = pd.to_datetime(df['timestamp'])
                return df
            except Exception as e:
                logger.error(f"Error loading {json_files[0]}: {e}")
        
        return pd.DataFrame()
    
    def get_available_reports(self) -> List[Dict[str, Any]]:
        """Get information about available JSON report files"""
        json_files = self.list_files("*.json")
        
        reports = []
        for file_path in json_files:
            try:
                stat = file_path.stat()
                
                # Try to read file to get structure info
                with open(file_path, 'r') as f:
                    data = json.load(f)
                
                report_info = {
                    'filename': file_path.name,
                    'path': str(file_path),
                    'size_mb': round(stat.st_size / (1024 * 1024), 2),
                    'modified': datetime.fromtimestamp(stat.st_mtime),
                    'data_type': type(data).__name__
                }
                
                if isinstance(data, list):
                    report_info['record_count'] = len(data)
                    if data and isinstance(data[0], dict):
                        report_info['sample_fields'] = list(data[0].keys())
                elif isinstance(data, dict):
                    report_info['fields'] = list(data.keys())
                    if 'records' in data:
                        report_info['record_count'] = len(data['records'])
                
                reports.append(report_info)
                
            except Exception as e:
                logger.error(f"Error analyzing {file_path}: {e}")
        
        return reports

class DataSourceFactory:
    """
    Factory for creating appropriate data source instances
    """
    
    @staticmethod
    def create_source(source_type: str, **kwargs):
        """
        Create data source instance
        
        Args:
            source_type: Type of data source ('cassandra', 'csv', 'json')
            **kwargs: Additional arguments for data source
            
        Returns:
            Data source instance
        """
        if source_type.lower() == 'cassandra':
            from .cassandra_source import CassandraDataSource
            return CassandraDataSource(**kwargs)
        elif source_type.lower() == 'csv':
            return CSVDataSource(**kwargs)
        elif source_type.lower() == 'json':
            return JSONDataSource(**kwargs)
        else:
            raise ValueError(f"Unknown source type: {source_type}")