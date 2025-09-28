"""
Traffic analysis algorithms for vehicle flow data
"""
import pandas as pd
import numpy as np
from typing import Dict, List, Tuple, Optional, Any
import logging
from datetime import datetime, timedelta
from collections import defaultdict
import warnings
warnings.filterwarnings('ignore')

logger = logging.getLogger(__name__)

class TrafficAnalyzer:
    """
    Main class for traffic analysis operations
    """
    
    def __init__(self):
        """
        Initialize traffic analyzer
        """
        self.logger = logging.getLogger(__name__)
    
    def _prepare_data(self):
        """Prepare data for analysis"""
        if self.data.empty:
            logger.warning("No data available for analysis")
            return
        
        # Convert timestamp if needed
        if 'timestamp' in self.data.columns:
            if not pd.api.types.is_datetime64_any_dtype(self.data['timestamp']):
                self.data['timestamp'] = pd.to_datetime(self.data['timestamp'], errors='coerce')
        
        # Create time-based features
        if 'timestamp' in self.data.columns:
            self.data['hour'] = self.data['timestamp'].dt.hour
            self.data['day_of_week'] = self.data['timestamp'].dt.dayofweek
            self.data['date'] = self.data['timestamp'].dt.date
        
        # Convert numeric columns
        numeric_cols = ['travel_time', 'calculated_speed', 'traffic_density']
        for col in numeric_cols:
            if col in self.data.columns:
                self.data[col] = pd.to_numeric(self.data[col], errors='coerce')
    
    def basic_statistics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """
        Calculate basic traffic statistics
        
        Args:
            data: DataFrame with vehicle flow data
            
        Returns:
            Dictionary with basic statistics
        """
        if data.empty:
            return {}
        
        stats = {
            'total_records': len(data),
            'unique_vehicles': data['car_id'].nunique() if 'car_id' in data.columns else 0,
            'time_range': {
                'start': data['timestamp'].min() if 'timestamp' in data.columns else None,
                'end': data['timestamp'].max() if 'timestamp' in data.columns else None
            }
        }
        
        # Vehicle types analysis
        if 'vehicle_type' in data.columns:
            vehicle_counts = data['vehicle_type'].value_counts()
            stats['vehicle_types'] = vehicle_counts.to_dict()
        
        # Speed analysis
        if 'calculated_speed' in data.columns:
            speed_data = pd.to_numeric(data['calculated_speed'], errors='coerce').dropna()
            if not speed_data.empty:
                stats['speed_stats'] = {
                    'mean': speed_data.mean(),
                    'median': speed_data.median(),
                    'std': speed_data.std(),
                    'min': speed_data.min(),
                    'max': speed_data.max()
                }
        
        return stats
    
    def temporal_analysis(self, data: pd.DataFrame) -> Dict[str, Any]:
        """
        Analyze traffic patterns over time
        
        Args:
            data: DataFrame with vehicle flow data
            
        Returns:
            Dictionary with temporal analysis results
        """
        if data.empty or 'timestamp' not in data.columns:
            return {}
        
        # Ensure timestamp is datetime
        data = data.copy()
        data['timestamp'] = pd.to_datetime(data['timestamp'])
        
        # Add time features
        data['hour'] = data['timestamp'].dt.hour
        data['day_of_week'] = data['timestamp'].dt.dayofweek
        data['date'] = data['timestamp'].dt.date
        
        temporal_stats = {}
        
        # Hourly analysis
        hourly_counts = data.groupby('hour').size()
        temporal_stats['hourly'] = hourly_counts.to_dict()
        
        # Daily analysis
        daily_counts = data.groupby(data['timestamp'].dt.date).size()
        temporal_stats['daily'] = {str(k): v for k, v in daily_counts.to_dict().items()}
        
        # Heatmap data for hour vs day of week
        heatmap_data = data.groupby(['day_of_week', 'hour']).size().unstack(fill_value=0)
        temporal_stats['heatmap_data'] = heatmap_data.to_dict()
        
        return temporal_stats
    
    def spatial_analysis(self, data: pd.DataFrame) -> Dict[str, Any]:
        """
        Analyze spatial distribution of traffic
        
        Args:
            data: DataFrame with vehicle flow data
            
        Returns:
            Dictionary with spatial analysis results
        """
        if data.empty:
            return {}
        
        spatial_stats = {}
        
        # Check if we have location data
        if 'latitude' in data.columns and 'longitude' in data.columns:
            lat_data = pd.to_numeric(data['latitude'], errors='coerce').dropna()
            lon_data = pd.to_numeric(data['longitude'], errors='coerce').dropna()
            
            if not lat_data.empty and not lon_data.empty:
                spatial_stats['location_stats'] = {
                    'center_lat': lat_data.mean(),
                    'center_lon': lon_data.mean(),
                    'lat_range': [lat_data.min(), lat_data.max()],
                    'lon_range': [lon_data.min(), lon_data.max()]
                }
        
        return spatial_stats
    
    def analyze_traffic_patterns(self) -> Dict[str, Any]:
        """
        Analyze traffic patterns by time
        
        Returns:
            Dictionary with traffic patterns
        """
        if self.data.empty or 'timestamp' not in self.data.columns:
            return {}
        
        patterns = {}
        
        # Hourly patterns
        hourly_counts = self.data.groupby('hour').size()
        patterns['hourly_distribution'] = hourly_counts.to_dict()
        patterns['peak_hours'] = {
            'morning_peak': hourly_counts[6:10].idxmax() if not hourly_counts[6:10].empty else None,
            'evening_peak': hourly_counts[17:20].idxmax() if not hourly_counts[17:20].empty else None,
            'busiest_hour': hourly_counts.idxmax()
        }
        
        # Daily patterns
        daily_counts = self.data.groupby('day_of_week').size()
        day_names = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
        patterns['daily_distribution'] = {
            day_names[i]: int(daily_counts.get(i, 0)) for i in range(7)
        }
        patterns['busiest_day'] = day_names[daily_counts.idxmax()]
        
        # Speed patterns by hour
        if 'calculated_speed' in self.data.columns:
            hourly_speed = self.data.groupby('hour')['calculated_speed'].agg(['mean', 'std']).fillna(0)
            patterns['speed_by_hour'] = {
                'mean': hourly_speed['mean'].to_dict(),
                'std': hourly_speed['std'].to_dict()
            }
        
        return patterns
    
    def identify_bottlenecks(self, threshold_percentile: float = 0.1) -> List[Dict[str, Any]]:
        """
        Identify traffic bottlenecks based on speed and density
        
        Args:
            threshold_percentile: Percentile threshold for identifying bottlenecks
            
        Returns:
            List of bottleneck information
        """
        if self.data.empty or 'link_id' not in self.data.columns:
            return []
        
        bottlenecks = []
        
        # Analyze by link
        link_analysis = self.data.groupby('link_id').agg({
            'calculated_speed': ['mean', 'min', 'std', 'count'],
            'traffic_density': ['mean', 'max'],
            'travel_time': ['mean', 'max']
        }).fillna(0)
        
        # Flatten column names
        link_analysis.columns = ['_'.join(col).strip() for col in link_analysis.columns]
        
        # Define bottleneck criteria
        if 'calculated_speed_mean' in link_analysis.columns:
            speed_threshold = link_analysis['calculated_speed_mean'].quantile(threshold_percentile)
            slow_links = link_analysis[link_analysis['calculated_speed_mean'] <= speed_threshold]
            
            for link_id, row in slow_links.iterrows():
                bottleneck_info = {
                    'link_id': link_id,
                    'avg_speed': float(row['calculated_speed_mean']),
                    'min_speed': float(row['calculated_speed_min']),
                    'speed_variance': float(row['calculated_speed_std']),
                    'vehicle_count': int(row['calculated_speed_count']),
                    'severity_score': float((speed_threshold - row['calculated_speed_mean']) / speed_threshold)
                }
                
                if 'traffic_density_mean' in link_analysis.columns:
                    bottleneck_info['avg_density'] = float(row['traffic_density_mean'])
                    bottleneck_info['max_density'] = float(row['traffic_density_max'])
                
                if 'travel_time_mean' in link_analysis.columns:
                    bottleneck_info['avg_travel_time'] = float(row['travel_time_mean'])
                    bottleneck_info['max_travel_time'] = float(row['travel_time_max'])
                
                bottlenecks.append(bottleneck_info)
        
        # Sort by severity
        bottlenecks.sort(key=lambda x: x['severity_score'], reverse=True)
        
        return bottlenecks
    
    def analyze_route_efficiency(self) -> Dict[str, Any]:
        """
        Analyze route efficiency patterns
        
        Returns:
            Dictionary with route efficiency metrics
        """
        if self.data.empty or 'car_id' not in self.data.columns:
            return {}
        
        efficiency_metrics = {}
        
        # Journey-based analysis
        journey_data = self.data[self.data['event_type'] == 'journey_completed'] if 'event_type' in self.data.columns else pd.DataFrame()
        
        if not journey_data.empty:
            if 'travel_time' in journey_data.columns:
                efficiency_metrics['journey_efficiency'] = {
                    'avg_journey_time': float(journey_data['travel_time'].mean()),
                    'journey_time_variance': float(journey_data['travel_time'].std()),
                    'efficient_journeys': int((journey_data['travel_time'] < journey_data['travel_time'].quantile(0.25)).sum()),
                    'inefficient_journeys': int((journey_data['travel_time'] > journey_data['travel_time'].quantile(0.75)).sum())
                }
        
        # Link-level efficiency
        if 'link_id' in self.data.columns and 'travel_time' in self.data.columns:
            link_efficiency = self.data.groupby('link_id').agg({
                'travel_time': ['mean', 'std', 'count'],
                'calculated_speed': ['mean', 'std']
            }).fillna(0)
            
            link_efficiency.columns = ['_'.join(col).strip() for col in link_efficiency.columns]
            
            # Identify most/least efficient links
            if 'travel_time_mean' in link_efficiency.columns:
                most_efficient = link_efficiency.nsmallest(5, 'travel_time_mean')
                least_efficient = link_efficiency.nlargest(5, 'travel_time_mean')
                
                efficiency_metrics['link_efficiency'] = {
                    'most_efficient_links': [
                        {
                            'link_id': str(idx),
                            'avg_travel_time': float(row['travel_time_mean']),
                            'avg_speed': float(row['calculated_speed_mean']) if 'calculated_speed_mean' in row else 0
                        }
                        for idx, row in most_efficient.iterrows()
                    ],
                    'least_efficient_links': [
                        {
                            'link_id': str(idx),
                            'avg_travel_time': float(row['travel_time_mean']),
                            'avg_speed': float(row['calculated_speed_mean']) if 'calculated_speed_mean' in row else 0
                        }
                        for idx, row in least_efficient.iterrows()
                    ]
                }
        
        return efficiency_metrics
    
    def calculate_mobility_indicators(self) -> Dict[str, Any]:
        """
        Calculate urban mobility indicators
        
        Returns:
            Dictionary with mobility indicators
        """
        if self.data.empty:
            return {}
        
        indicators = {}
        
        # Vehicle throughput
        if 'timestamp' in self.data.columns:
            # Vehicles per hour
            self.data['hour_timestamp'] = self.data['timestamp'].dt.floor('H')
            throughput = self.data.groupby('hour_timestamp')['car_id'].nunique()
            
            indicators['throughput'] = {
                'avg_vehicles_per_hour': float(throughput.mean()),
                'max_vehicles_per_hour': float(throughput.max()),
                'min_vehicles_per_hour': float(throughput.min()),
                'throughput_variance': float(throughput.std())
            }
        
        # Network utilization
        if 'link_id' in self.data.columns:
            link_usage = self.data['link_id'].value_counts()
            total_links = self.data['link_id'].nunique()
            
            indicators['network_utilization'] = {
                'total_active_links': int(total_links),
                'avg_usage_per_link': float(link_usage.mean()),
                'utilization_variance': float(link_usage.std()),
                'most_used_links': link_usage.head(10).to_dict(),
                'underutilized_links': int((link_usage < link_usage.quantile(0.1)).sum())
            }
        
        # Speed distribution analysis
        if 'calculated_speed' in self.data.columns:
            speed_data = self.data['calculated_speed'].dropna()
            if not speed_data.empty:
                indicators['speed_distribution'] = {
                    'low_speed_ratio': float((speed_data < speed_data.quantile(0.25)).mean()),
                    'medium_speed_ratio': float(((speed_data >= speed_data.quantile(0.25)) & 
                                               (speed_data <= speed_data.quantile(0.75))).mean()),
                    'high_speed_ratio': float((speed_data > speed_data.quantile(0.75)).mean()),
                    'speed_uniformity': float(1 - (speed_data.std() / speed_data.mean()) if speed_data.mean() > 0 else 0)
                }
        
        return indicators
    
    def generate_comprehensive_report(self) -> Dict[str, Any]:
        """
        Generate comprehensive traffic analysis report
        
        Returns:
            Complete analysis report
        """
        report = {
            'metadata': {
                'generated_at': datetime.now().isoformat(),
                'data_summary': {
                    'total_records': len(self.data),
                    'date_range': {
                        'start': self.data['timestamp'].min().isoformat() if 'timestamp' in self.data.columns and not self.data.empty else None,
                        'end': self.data['timestamp'].max().isoformat() if 'timestamp' in self.data.columns and not self.data.empty else None
                    } if 'timestamp' in self.data.columns else None
                }
            },
            'basic_metrics': self.calculate_basic_metrics(),
            'traffic_patterns': self.analyze_traffic_patterns(),
            'bottlenecks': self.identify_bottlenecks(),
            'route_efficiency': self.analyze_route_efficiency(),
            'mobility_indicators': self.calculate_mobility_indicators()
        }
        
        return report