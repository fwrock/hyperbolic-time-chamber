"""
Sistema de mapeamento de IDs entre HTC e simulador de referência
"""
import re
from typing import Dict, Optional, Tuple, Set
import logging

logger = logging.getLogger(__name__)

class IDMapper:
    """
    Mapeia IDs entre o simulador HTC e o simulador de referência
    """
    
    def __init__(self):
        """
        Initialize ID mapper with pattern definitions
        """
        # Patterns for HTC IDs
        self.htc_car_patterns = [
            r'htcaid:car;(.+)',      # htcaid:car;trip_317
            r'htcaid_car_(.+)',      # htcaid_car_trip_317
        ]
        
        self.htc_link_patterns = [
            r'htcaid:link;(.+)',     # htcaid:link;2114
            r'htcaid_link_(.+)',     # htcaid_link_2114
        ]
        
        # Patterns for reference simulator IDs
        self.ref_car_patterns = [
            r'(.+)_\d+$',            # trip_317_1, trip_317_2, etc.
        ]
        
        # Cache for mapping
        self.htc_to_ref_cars = {}
        self.ref_to_htc_cars = {}
        self.htc_to_ref_links = {}
        self.ref_to_htc_links = {}
        
    def extract_base_car_id(self, vehicle_id: str, simulator_type: str) -> Optional[str]:
        """
        Extract base vehicle ID from formatted ID
        
        Args:
            vehicle_id: Vehicle ID from simulator
            simulator_type: 'htc' or 'reference'
            
        Returns:
            Base ID or None if no pattern matches
        """
        if simulator_type == 'htc':
            patterns = self.htc_car_patterns
        elif simulator_type == 'reference':
            patterns = self.ref_car_patterns
        else:
            return None
        
        for pattern in patterns:
            match = re.match(pattern, vehicle_id)
            if match:
                return match.group(1)
        
        # If no pattern matches, return the original ID
        return vehicle_id
    
    def extract_base_link_id(self, link_id: str, simulator_type: str) -> Optional[str]:
        """
        Extract base link ID from formatted ID
        
        Args:
            link_id: Link ID from simulator
            simulator_type: 'htc' or 'reference'
            
        Returns:
            Base ID or None if no pattern matches
        """
        if simulator_type == 'htc':
            patterns = self.htc_link_patterns
        elif simulator_type == 'reference':
            # Reference links are usually just the base ID
            return link_id
        else:
            return None
        
        for pattern in patterns:
            match = re.match(pattern, link_id)
            if match:
                return match.group(1)
        
        # If no pattern matches, return the original ID
        return link_id
    
    def build_car_mapping(self, htc_car_ids: Set[str], ref_car_ids: Set[str]) -> Tuple[Dict[str, str], Dict[str, str]]:
        """
        Build bidirectional mapping between HTC and reference car IDs
        
        Args:
            htc_car_ids: Set of HTC car IDs
            ref_car_ids: Set of reference car IDs
            
        Returns:
            Tuple of (htc_to_ref, ref_to_htc) mappings
        """
        logger.info(f"🚗 Construindo mapeamento de veículos...")
        logger.info(f"   HTC: {len(htc_car_ids)} veículos")
        logger.info(f"   Referência: {len(ref_car_ids)} veículos")
        
        # Extract base IDs
        htc_base_ids = {}  # base_id -> original_htc_id
        ref_base_ids = {}  # base_id -> original_ref_id
        
        logger.info("   🔄 Processando IDs do HTC...")
        processed = 0
        for htc_id in htc_car_ids:
            base_id = self.extract_base_car_id(htc_id, 'htc')
            if base_id:
                htc_base_ids[base_id] = htc_id
            
            processed += 1
            if processed % 1000 == 0:
                progress = (processed / len(htc_car_ids)) * 100
                logger.info(f"     HTC: {processed}/{len(htc_car_ids)} ({progress:.1f}%)")
        
        logger.info("   🔄 Processando IDs de referência...")
        processed = 0
        for ref_id in ref_car_ids:
            base_id = self.extract_base_car_id(ref_id, 'reference')
            if base_id:
                ref_base_ids[base_id] = ref_id
            
            processed += 1
            if processed % 1000 == 0:
                progress = (processed / len(ref_car_ids)) * 100
                logger.info(f"     Ref: {processed}/{len(ref_car_ids)} ({progress:.1f}%)")
        
        # Create bidirectional mapping
        htc_to_ref = {}
        ref_to_htc = {}
        
        common_base_ids = set(htc_base_ids.keys()) & set(ref_base_ids.keys())
        
        for base_id in common_base_ids:
            htc_id = htc_base_ids[base_id]
            ref_id = ref_base_ids[base_id]
            htc_to_ref[htc_id] = ref_id
            ref_to_htc[ref_id] = htc_id
        
        logger.info(f"✅ Mapeados {len(common_base_ids)} veículos comuns")
        logger.info(f"   HTC únicos: {len(htc_car_ids) - len(common_base_ids)}")
        logger.info(f"   Referência únicos: {len(ref_car_ids) - len(common_base_ids)}")
        
        self.htc_to_ref_cars = htc_to_ref
        self.ref_to_htc_cars = ref_to_htc
        
        return htc_to_ref, ref_to_htc
    
    def build_link_mapping(self, htc_link_ids: Set[str], ref_link_ids: Set[str]) -> Tuple[Dict[str, str], Dict[str, str]]:
        """
        Build bidirectional mapping between HTC and reference link IDs
        
        Args:
            htc_link_ids: Set of HTC link IDs
            ref_link_ids: Set of reference link IDs
            
        Returns:
            Tuple of (htc_to_ref, ref_to_htc) mappings
        """
        logger.info(f"🛣️ Construindo mapeamento de links...")
        logger.info(f"   HTC: {len(htc_link_ids)} links")
        logger.info(f"   Referência: {len(ref_link_ids)} links")
        
        # Extract base IDs
        htc_base_ids = {}  # base_id -> original_htc_id
        ref_base_ids = {}  # base_id -> original_ref_id
        
        for htc_id in htc_link_ids:
            base_id = self.extract_base_link_id(htc_id, 'htc')
            if base_id:
                htc_base_ids[base_id] = htc_id
        
        for ref_id in ref_link_ids:
            base_id = self.extract_base_link_id(ref_id, 'reference')
            if base_id:
                ref_base_ids[base_id] = ref_id
        
        # Create bidirectional mapping
        htc_to_ref = {}
        ref_to_htc = {}
        
        common_base_ids = set(htc_base_ids.keys()) & set(ref_base_ids.keys())
        
        for base_id in common_base_ids:
            htc_id = htc_base_ids[base_id]
            ref_id = ref_base_ids[base_id]
            htc_to_ref[htc_id] = ref_id
            ref_to_htc[ref_id] = htc_id
        
        logger.info(f"✅ Mapeados {len(common_base_ids)} links comuns")
        logger.info(f"   HTC únicos: {len(htc_link_ids) - len(common_base_ids)}")
        logger.info(f"   Referência únicos: {len(ref_link_ids) - len(common_base_ids)}")
        
        self.htc_to_ref_links = htc_to_ref
        self.ref_to_htc_links = ref_to_htc
        
        return htc_to_ref, ref_to_htc
    
    def get_mapping_statistics(self) -> Dict[str, any]:
        """
        Get statistics about the ID mapping
        
        Returns:
            Dictionary with mapping statistics
        """
        return {
            'cars': {
                'mapped_pairs': len(self.htc_to_ref_cars),
                'htc_unmapped': 0,  # Will be calculated by comparator
                'ref_unmapped': 0   # Will be calculated by comparator
            },
            'links': {
                'mapped_pairs': len(self.htc_to_ref_links),
                'htc_unmapped': 0,  # Will be calculated by comparator
                'ref_unmapped': 0   # Will be calculated by comparator
            }
        }
    
    def normalize_htc_car_id(self, htc_id: str) -> str:
        """Normalize HTC car ID to base form"""
        base_id = self.extract_base_car_id(htc_id, 'htc')
        return base_id if base_id else htc_id
    
    def normalize_ref_car_id(self, ref_id: str) -> str:
        """Normalize reference car ID to base form"""
        base_id = self.extract_base_car_id(ref_id, 'reference')
        return base_id if base_id else ref_id
    
    def normalize_htc_link_id(self, htc_id: str) -> str:
        """Normalize HTC link ID to base form"""
        base_id = self.extract_base_link_id(htc_id, 'htc')
        return base_id if base_id else htc_id
    
    def normalize_ref_link_id(self, ref_id: str) -> str:
        """Normalize reference link ID to base form"""
        base_id = self.extract_base_link_id(ref_id, 'reference')
        return base_id if base_id else ref_id