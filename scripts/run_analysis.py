"""
Main script for running comprehensive traffic analysis
"""
import sys
import logging
from pathlib import Path
import argparse
from typing import Dict, Any, Optional
import pandas as pd

# Add project root to Python path
project_root = Path(__file__).parent.parent
sys.path.append(str(project_root))

from config import (
    CASSANDRA_CONFIG,
    OUTPUT_PATH,
    REPORTS_PATH,
    LOGGING_CONFIG
)
from data_sources.cassandra_source import CassandraDataSource
from data_sources.file_sources import CSVDataSource, JSONDataSource, DataSourceFactory
from analysis.traffic_analyzer import TrafficAnalyzer
from visualization.traffic_viz import TrafficVisualizer
from reports.report_generator import TrafficReportGenerator

# Setup logging
logging.basicConfig(**LOGGING_CONFIG)
logger = logging.getLogger(__name__)

class TrafficAnalysisManager:
    """
    Main manager for traffic analysis workflow
    """
    
    def __init__(self):
        """Initialize analysis manager"""
        self.data_source = None
        self.analyzer = None
        self.visualizer = None
        self.reporter = None
        self.results = {}
    
    def setup_data_source(self, source_type: str, **kwargs) -> bool:
        """
        Setup data source connection
        
        Args:
            source_type: Type of data source ('cassandra', 'csv', 'json')
            **kwargs: Additional arguments for data source
            
        Returns:
            True if setup successful, False otherwise
        """
        try:
            logger.info(f"Setting up {source_type} data source...")
            self.data_source = DataSourceFactory.create_source(source_type, **kwargs)
            
            # Test connection
            if hasattr(self.data_source, 'connect'):
                connection_success = self.data_source.connect()
                if not connection_success:
                    logger.error(f"Failed to connect to {source_type} data source")
                    return False
            
            logger.info(f"{source_type.title()} data source setup completed")
            return True
            
        except Exception as e:
            logger.error(f"Error setting up data source: {e}")
            return False
    
    def load_data(self, **kwargs) -> pd.DataFrame:
        """
        Load vehicle flow data from configured data source
        
        Args:
            **kwargs: Arguments for data loading
            
        Returns:
            DataFrame with vehicle flow data
        """
        if not self.data_source:
            logger.error("No data source configured")
            return pd.DataFrame()
        
        try:
            logger.info("Loading vehicle flow data...")
            
            if hasattr(self.data_source, 'get_vehicle_flow_data'):
                # Cassandra data source
                data = self.data_source.get_vehicle_flow_data(**kwargs)
            elif hasattr(self.data_source, 'load_vehicle_flow_data'):
                # File-based data sources
                data = self.data_source.load_vehicle_flow_data(**kwargs)
            else:
                logger.error("Data source does not support vehicle flow data loading")
                return pd.DataFrame()
            
            logger.info(f"Loaded {len(data)} records")
            return data
            
        except Exception as e:
            logger.error(f"Error loading data: {e}")
            return pd.DataFrame()
    
    def run_analysis(self, data: pd.DataFrame) -> Dict[str, Any]:
        """
        Run comprehensive traffic analysis
        
        Args:
            data: DataFrame with vehicle flow data
            
        Returns:
            Analysis results dictionary
        """
        if data.empty:
            logger.warning("No data available for analysis")
            return {}
        
        try:
            logger.info("Starting traffic analysis...")
            
            # Initialize analyzer
            self.analyzer = TrafficAnalyzer(data)
            
            # Run all analysis components
            results = {
                'basic_metrics': self.analyzer.calculate_basic_metrics(),
                'traffic_patterns': self.analyzer.analyze_traffic_patterns(),
                'bottlenecks': self.analyzer.identify_bottlenecks(),
                'route_efficiency': self.analyzer.analyze_route_efficiency(),
                'mobility_indicators': self.analyzer.calculate_mobility_indicators()
            }
            
            # Generate comprehensive report
            comprehensive_report = self.analyzer.generate_comprehensive_report()
            results.update(comprehensive_report)
            
            self.results = results
            logger.info("Traffic analysis completed successfully")
            
            return results
            
        except Exception as e:
            logger.error(f"Error during analysis: {e}")
            return {}
    
    def generate_visualizations(self, data: pd.DataFrame, results: Dict[str, Any]) -> Dict[str, str]:
        """
        Generate all visualizations
        
        Args:
            data: DataFrame with vehicle flow data
            results: Analysis results
            
        Returns:
            Dictionary mapping visualization names to file paths
        """
        if data.empty and not results:
            logger.warning("No data or results available for visualization")
            return {}
        
        try:
            logger.info("Generating visualizations...")
            
            # Initialize visualizer
            self.visualizer = TrafficVisualizer(data)
            
            # Generate and save all visualizations
            viz_paths = self.visualizer.save_all_visualizations(
                results,
                output_dir=OUTPUT_PATH / "visualizations"
            )
            
            logger.info(f"Generated {len(viz_paths)} visualizations")
            return viz_paths
            
        except Exception as e:
            logger.error(f"Error generating visualizations: {e}")
            return {}
    
    def generate_reports(self, results: Dict[str, Any], viz_paths: Dict[str, str] = None) -> Dict[str, str]:
        """
        Generate comprehensive reports
        
        Args:
            results: Analysis results
            viz_paths: Paths to visualization files
            
        Returns:
            Dictionary mapping report types to file paths
        """
        if not results:
            logger.warning("No results available for reporting")
            return {}
        
        try:
            logger.info("Generating reports...")
            
            # Initialize reporter
            self.reporter = TrafficReportGenerator(REPORTS_PATH)
            
            # Generate all report formats
            report_paths = self.reporter.generate_all_reports(results, viz_paths)
            
            logger.info(f"Generated {len(report_paths)} report formats")
            return report_paths
            
        except Exception as e:
            logger.error(f"Error generating reports: {e}")
            return {}
    
    def run_complete_analysis(self, 
                             source_type: str = 'cassandra',
                             **kwargs) -> Dict[str, Any]:
        """
        Run complete analysis workflow
        
        Args:
            source_type: Type of data source
            **kwargs: Additional arguments
            
        Returns:
            Dictionary with all generated file paths
        """
        logger.info("Starting complete traffic analysis workflow...")
        
        # Setup data source
        if not self.setup_data_source(source_type, **kwargs):
            logger.error("Failed to setup data source, aborting analysis")
            return {}
        
        # Load data
        data = self.load_data(**kwargs)
        if data.empty:
            logger.error("No data loaded, aborting analysis")
            return {}
        
        # Run analysis
        results = self.run_analysis(data)
        if not results:
            logger.error("Analysis failed, aborting workflow")
            return {}
        
        # Generate visualizations
        viz_paths = self.generate_visualizations(data, results)
        
        # Generate reports
        report_paths = self.generate_reports(results, viz_paths)
        
        # Cleanup
        if hasattr(self.data_source, 'close'):
            self.data_source.close()
        
        # Summary
        workflow_results = {
            'data_records': len(data),
            'analysis_results': results,
            'visualizations': viz_paths,
            'reports': report_paths,
            'success': True
        }
        
        logger.info("Complete analysis workflow finished successfully")
        logger.info(f"Generated {len(viz_paths)} visualizations and {len(report_paths)} reports")
        
        return workflow_results

def main():
    """Main entry point for traffic analysis script"""
    parser = argparse.ArgumentParser(description='Run comprehensive traffic flow analysis')
    
    # Data source arguments
    parser.add_argument(
        '--source', 
        choices=['cassandra', 'csv', 'json'],
        default='cassandra',
        help='Data source type (default: cassandra)'
    )
    
    parser.add_argument(
        '--data-path',
        type=str,
        help='Path to data files (for CSV/JSON sources)'
    )
    
    # Analysis arguments
    parser.add_argument(
        '--simulation-id',
        type=str,
        help='Simulation ID to analyze (for Cassandra source)'
    )
    
    parser.add_argument(
        '--limit',
        type=int,
        default=10000,
        help='Maximum number of records to analyze (default: 10000)'
    )
    
    # Output arguments
    parser.add_argument(
        '--output-dir',
        type=str,
        help='Directory for output files'
    )
    
    parser.add_argument(
        '--skip-viz',
        action='store_true',
        help='Skip visualization generation'
    )
    
    parser.add_argument(
        '--skip-reports',
        action='store_true',
        help='Skip report generation'
    )
    
    # Logging
    parser.add_argument(
        '--verbose', '-v',
        action='store_true',
        help='Enable verbose logging'
    )
    
    args = parser.parse_args()
    
    # Configure logging level
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Prepare kwargs for data loading
    data_kwargs = {}
    if args.simulation_id:
        data_kwargs['simulation_id'] = args.simulation_id
    if args.limit:
        data_kwargs['limit'] = args.limit
    if args.data_path:
        data_kwargs['data_path'] = Path(args.data_path)
    
    # Initialize and run analysis
    manager = TrafficAnalysisManager()
    
    try:
        # Setup data source
        if not manager.setup_data_source(args.source, **data_kwargs):
            logger.error("Failed to setup data source")
            sys.exit(1)
        
        # Load data
        data = manager.load_data(**data_kwargs)
        if data.empty:
            logger.error("No data loaded")
            sys.exit(1)
        
        print(f"\\n📊 Loaded {len(data):,} vehicle flow records")
        print(f"📅 Data period: {data['timestamp'].min()} to {data['timestamp'].max()}" if 'timestamp' in data.columns else "")
        
        # Run analysis
        results = manager.run_analysis(data)
        if not results:
            logger.error("Analysis failed")
            sys.exit(1)
        
        print(f"\\n🔍 Analysis Summary:")
        basic_metrics = results.get('basic_metrics', {})
        print(f"  • Total vehicles: {basic_metrics.get('total_vehicles', 0):,}")
        print(f"  • Average speed: {basic_metrics.get('speed_stats', {}).get('mean', 0):.1f} km/h")
        print(f"  • Bottlenecks found: {len(results.get('bottlenecks', []))}")
        
        viz_paths = {}
        if not args.skip_viz:
            print(f"\\n📈 Generating visualizations...")
            viz_paths = manager.generate_visualizations(data, results)
            print(f"  • Generated {len(viz_paths)} visualizations")
            for name, path in viz_paths.items():
                print(f"    - {name}: {path}")
        
        report_paths = {}
        if not args.skip_reports:
            print(f"\\n📋 Generating reports...")
            report_paths = manager.generate_reports(results, viz_paths)
            print(f"  • Generated {len(report_paths)} reports")
            for format_type, path in report_paths.items():
                print(f"    - {format_type.upper()}: {path}")
        
        print(f"\\n✅ Analysis completed successfully!")
        if report_paths.get('html'):
            print(f"\\n🌐 Open the HTML report in your browser:")
            print(f"   file://{Path(report_paths['html']).absolute()}")
        
    except KeyboardInterrupt:
        logger.info("Analysis interrupted by user")
        sys.exit(0)
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        sys.exit(1)
    finally:
        # Cleanup
        if manager.data_source and hasattr(manager.data_source, 'close'):
            manager.data_source.close()

if __name__ == "__main__":
    main()