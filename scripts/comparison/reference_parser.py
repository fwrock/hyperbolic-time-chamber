"""
Parser para arquivos XML de eventos do simulador de referência (MATSim/SUMO style)
"""
import pandas as pd
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import List, Dict, Any, Optional
import logging
from datetime import datetime

logger = logging.getLogger(__name__)

class ReferenceSimulatorParser:
    """
    Parser para arquivos XML de eventos do simulador de referência
    """
    
    def __init__(self, xml_file_path: Path):
        """
        Initialize parser with XML file path
        
        Args:
            xml_file_path: Path to XML events file
        """
        self.xml_file_path = Path(xml_file_path)
        self.events = []
        
    def parse_xml_events(self) -> pd.DataFrame:
        """
        Parse XML events file and return DataFrame
        
        Returns:
            DataFrame with parsed events
        """
        logger.info(f"🔍 Parsing XML events file: {self.xml_file_path}")
        
        if not self.xml_file_path.exists():
            logger.error(f"❌ XML file not found: {self.xml_file_path}")
            return pd.DataFrame()
        
        try:
            events_data = []
            
            # Read file line by line since it may not have proper XML root
            with open(self.xml_file_path, 'r', encoding='utf-8') as file:
                for line_num, line in enumerate(file, 1):
                    line = line.strip()
                    if not line or not line.startswith('<event'):
                        continue
                    
                    try:
                        # Parse individual event element
                        event = ET.fromstring(line)
                        event_data = {
                            'time': float(event.get('time', 0)),
                            'type': event.get('type', ''),
                            'person': event.get('person', ''),
                            'link': event.get('link', ''),
                            'vehicle': event.get('vehicle', ''),
                            'actType': event.get('actType', ''),
                            'legMode': event.get('legMode', ''),
                            'action': event.get('action', '')
                        }
                        events_data.append(event_data)
                    except ET.ParseError as e:
                        logger.warning(f"⚠️ Failed to parse line {line_num}: {e}")
                        continue
                    
                    # Progress logging for large files
                    if len(events_data) % 100000 == 0:
                        logger.info(f"📊 Processados {len(events_data)} eventos...")
                    
                    # Remove the artificial limit - process ALL events
                    # if len(events_data) >= 50000:
                    #     logger.info(f"📊 Limiting to first {len(events_data)} events for memory efficiency")
                    #     break
                event_data = {
                    'time': float(event.get('time', 0)),
                    'type': event.get('type', ''),
                    'person': event.get('person', ''),
                    'link': event.get('link', ''),
                    'vehicle': event.get('vehicle', ''),
                    'actType': event.get('actType', ''),
                    'legMode': event.get('legMode', ''),
                    'action': event.get('action', '')
                }
                events_data.append(event_data)
            
            df = pd.DataFrame(events_data)
            
            if not df.empty:
                # Convert time to seconds and add timestamp
                df['timestamp'] = pd.to_datetime(df['time'], unit='s')
                df = df.sort_values(['time', 'person'])
                
            logger.info(f"✅ Parsed {len(df)} events from XML")
            return df
            
        except Exception as e:
            logger.error(f"❌ Error parsing XML: {e}")
            return pd.DataFrame()
    
    def get_traffic_flow_events(self) -> pd.DataFrame:
        """
        Extract traffic flow related events (entered/left link)
        
        Returns:
            DataFrame with traffic flow events
        """
        all_events = self.parse_xml_events()
        
        if all_events.empty:
            return pd.DataFrame()
        
        # Filter for traffic flow events
        traffic_events = all_events[
            all_events['type'].isin(['entered link', 'left link', 'wait2link'])
        ].copy()
        
        # Rename columns to match HTC format
        traffic_events['car_id'] = traffic_events['vehicle']
        traffic_events['event_type'] = traffic_events['type'].map({
            'entered link': 'enter_link',
            'left link': 'leave_link',
            'wait2link': 'wait_link'
        })
        traffic_events['link_id'] = traffic_events['link']
        traffic_events['tick'] = traffic_events['time']
        
        logger.info(f"🚗 Extracted {len(traffic_events)} traffic flow events")
        return traffic_events
    
    def get_summary_statistics(self) -> Dict[str, Any]:
        """
        Get summary statistics from reference simulator
        
        Returns:
            Dictionary with statistics
        """
        df = self.parse_xml_events()
        
        if df.empty:
            return {}
        
        stats = {
            'total_events': len(df),
            'unique_persons': df['person'].nunique(),
            'unique_vehicles': df['vehicle'].nunique(),
            'unique_links': df['link'].nunique(),
            'time_range': {
                'start': df['time'].min(),
                'end': df['time'].max(),
                'duration': df['time'].max() - df['time'].min()
            },
            'event_types': df['type'].value_counts().to_dict(),
            'vehicles_per_link': df[df['type'] == 'entered link'].groupby('link')['vehicle'].nunique().to_dict()
        }
        
        return stats