"""
Configuration module for HTC analysis scripts
"""
import os
from pathlib import Path

# Base paths
PROJECT_ROOT = Path(__file__).parent.parent
SCRIPTS_ROOT = Path(__file__).parent
OUTPUT_PATH = SCRIPTS_ROOT / "output"
REPORTS_PATH = OUTPUT_PATH / "reports"
CSV_DATA_PATH = PROJECT_ROOT / "data" / "csv" 
JSON_DATA_PATH = PROJECT_ROOT / "data" / "json"

# Ensure output directories exist
OUTPUT_PATH.mkdir(parents=True, exist_ok=True)
REPORTS_PATH.mkdir(parents=True, exist_ok=True)

# Cassandra configuration
CASSANDRA_CONFIG = {
    'hosts': ['localhost'],
    'port': 9042,
    'keyspace': 'htc_reports',
    'table': 'simulation_reports',
    'local_dc': 'datacenter1'
}

# Data file paths
CSV_DATA_PATH = PROJECT_ROOT / "data" / "csv"
JSON_DATA_PATH = PROJECT_ROOT / "data" / "json"

# Visualization settings
PLOT_CONFIG = {
    'figsize': (12, 8),
    'dpi': 300,
    'style': 'seaborn-v0_8',
    'color_palette': 'Set2'
}

# Report types
VEHICLE_FLOW_EVENTS = [
    'journey_started',
    'enter_link', 
    'leave_link',
    'journey_completed'
]

# Analysis parameters
ANALYSIS_CONFIG = {
    'min_travel_time': 1.0,  # seconds
    'max_travel_time': 3600.0,  # 1 hour
    'speed_bins': 20,
    'density_bins': 15,
    'time_window_minutes': 5
}

# Logging configuration
LOGGING_CONFIG = {
    'level': 'INFO',
    'format': '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    'datefmt': '%Y-%m-%d %H:%M:%S'
}

# Visualization configuration
VISUALIZATION_CONFIG = {
    'figure_size': (12, 8),
    'dpi': 300,
    'style': 'whitegrid',
    'color_palette': 'viridis',
    'save_format': 'png'
}