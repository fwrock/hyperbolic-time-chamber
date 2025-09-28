#!/usr/bin/env python3
"""
Quick test script for the traffic analysis system
"""
import sys
from pathlib import Path
import logging

# Add project root to path
project_root = Path(__file__).parent
sys.path.append(str(project_root))

def test_imports():
    """Test that all modules can be imported"""
    print("🧪 Testing module imports...")
    
    try:
        from config import CASSANDRA_CONFIG, OUTPUT_PATH
        print("✅ Config module imported successfully")
    except ImportError as e:
        print(f"❌ Config import failed: {e}")
        return False
    
    try:
        from data_sources.cassandra_source import CassandraDataSource
        print("✅ Cassandra data source imported successfully")
    except ImportError as e:
        print(f"❌ Cassandra import failed: {e}")
        return False
    
    try:
        from data_sources.file_sources import CSVDataSource, JSONDataSource
        print("✅ File data sources imported successfully")
    except ImportError as e:
        print(f"❌ File sources import failed: {e}")
        return False
    
    try:
        from analysis.traffic_analyzer import TrafficAnalyzer
        print("✅ Traffic analyzer imported successfully")
    except ImportError as e:
        print(f"❌ Traffic analyzer import failed: {e}")
        return False
    
    try:
        from visualization.traffic_viz import TrafficVisualizer
        print("✅ Traffic visualizer imported successfully")
    except ImportError as e:
        print(f"❌ Traffic visualizer import failed: {e}")
        return False
    
    try:
        from reports.report_generator import TrafficReportGenerator
        print("✅ Report generator imported successfully")
    except ImportError as e:
        print(f"❌ Report generator import failed: {e}")
        return False
    
    return True

def test_cassandra_connection():
    """Test Cassandra connection"""
    print("\\n🔌 Testing Cassandra connection...")
    
    try:
        from data_sources.cassandra_source import CassandraDataSource
        
        data_source = CassandraDataSource()
        if data_source.connect():
            print("✅ Cassandra connection successful")
            
            # Test basic query
            simulations = data_source.get_simulation_ids()
            print(f"📋 Found {len(simulations)} simulations: {simulations}")
            
            data_source.close()
            return True
        else:
            print("❌ Cassandra connection failed")
            return False
            
    except Exception as e:
        print(f"❌ Cassandra test error: {e}")
        return False

def test_data_processing():
    """Test basic data processing"""
    print("\\n📊 Testing data processing...")
    
    try:
        import pandas as pd
        from analysis.traffic_analyzer import TrafficAnalyzer
        
        # Create sample data
        sample_data = pd.DataFrame({
            'car_id': ['car_1', 'car_2', 'car_3'] * 10,
            'link_id': ['link_1', 'link_2', 'link_3'] * 10,
            'event_type': ['enter_link', 'leave_link', 'journey_completed'] * 10,
            'calculated_speed': [45.0, 60.0, 30.0] * 10,
            'travel_time': [120, 90, 180] * 10,
            'timestamp': pd.date_range('2024-01-01', periods=30, freq='1H')
        })
        
        print(f"📋 Created sample data: {len(sample_data)} records")
        
        # Test analyzer
        analyzer = TrafficAnalyzer(sample_data)
        basic_metrics = analyzer.calculate_basic_metrics()
        
        print(f"✅ Analysis completed:")
        print(f"   • Total vehicles: {basic_metrics.get('total_vehicles', 0)}")
        print(f"   • Total events: {basic_metrics.get('total_events', 0)}")
        print(f"   • Average speed: {basic_metrics.get('speed_stats', {}).get('mean', 0):.1f} km/h")
        
        return True
        
    except Exception as e:
        print(f"❌ Data processing test error: {e}")
        return False

def test_visualization():
    """Test visualization creation"""
    print("\\n📈 Testing visualization creation...")
    
    try:
        import pandas as pd
        from visualization.traffic_viz import TrafficVisualizer
        
        # Create sample data
        sample_data = pd.DataFrame({
            'car_id': ['car_1', 'car_2', 'car_3'] * 20,
            'link_id': ['link_1', 'link_2', 'link_3'] * 20,
            'calculated_speed': [45.0, 60.0, 30.0] * 20,
            'traffic_density': [0.5, 0.3, 0.8] * 20,
            'timestamp': pd.date_range('2024-01-01', periods=60, freq='1H')
        })
        
        # Test visualizer
        visualizer = TrafficVisualizer(sample_data)
        
        # Create a simple visualization (don't show/save)
        heatmap_fig = visualizer.create_traffic_heatmap()
        
        print("✅ Heatmap creation successful")
        
        speed_density_fig = visualizer.create_speed_density_plot()
        
        print("✅ Speed-density plot creation successful")
        
        return True
        
    except Exception as e:
        print(f"❌ Visualization test error: {e}")
        return False

def test_report_generation():
    """Test report generation"""
    print("\\n📋 Testing report generation...")
    
    try:
        from reports.report_generator import TrafficReportGenerator
        
        # Sample analysis results
        sample_results = {
            'basic_metrics': {
                'total_vehicles': 100,
                'total_events': 500,
                'unique_links': 10,
                'speed_stats': {'mean': 45.5, 'std': 12.0}
            },
            'traffic_patterns': {
                'peak_hours': {'morning_peak': 8, 'evening_peak': 18},
                'busiest_day': 'Tuesday'
            },
            'bottlenecks': [],
            'route_efficiency': {},
            'mobility_indicators': {}
        }
        
        reporter = TrafficReportGenerator()
        executive_summary = reporter.generate_executive_summary(sample_results)
        
        print("✅ Executive summary generation successful")
        print(f"   • Total vehicles: {executive_summary['overview']['total_vehicles_analyzed']}")
        print(f"   • Network efficiency: {executive_summary['key_findings']['network_efficiency']:.1%}")
        
        return True
        
    except Exception as e:
        print(f"❌ Report generation test error: {e}")
        return False

def main():
    """Run all tests"""
    print("🚀 Traffic Analysis System - Quick Test")
    print("=" * 50)
    
    tests = [
        ("Module Imports", test_imports),
        ("Cassandra Connection", test_cassandra_connection),
        ("Data Processing", test_data_processing),
        ("Visualization", test_visualization),
        ("Report Generation", test_report_generation)
    ]
    
    results = {}
    
    for test_name, test_func in tests:
        try:
            results[test_name] = test_func()
        except Exception as e:
            print(f"❌ {test_name} test crashed: {e}")
            results[test_name] = False
    
    # Summary
    print("\\n" + "=" * 50)
    print("📊 TEST SUMMARY")
    print("=" * 50)
    
    passed = sum(results.values())
    total = len(results)
    
    for test_name, passed_test in results.items():
        status = "✅ PASS" if passed_test else "❌ FAIL"
        print(f"{status:8} {test_name}")
    
    print("-" * 50)
    print(f"Result: {passed}/{total} tests passed ({passed/total*100:.1f}%)")
    
    if passed == total:
        print("🎉 All tests passed! System is ready to use.")
        return 0
    else:
        print("⚠️  Some tests failed. Check the errors above.")
        return 1

if __name__ == "__main__":
    sys.exit(main())