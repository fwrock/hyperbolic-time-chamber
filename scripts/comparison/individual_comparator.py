"""
Sistema de comparação individual de veículos e links
"""
import pandas as pd
import numpy as np
from typing import Dict, List, Tuple, Any
import logging
from pathlib import Path
import json
import matplotlib.pyplot as plt
import seaborn as sns

logger = logging.getLogger(__name__)

class IndividualComparator:
    """
    Comparador individual de veículos e links entre simuladores
    """
    
    def __init__(self, output_path: Path, id_mapper):
        """
        Initialize individual comparator
        
        Args:
            output_path: Path to save results
            id_mapper: IDMapper instance
        """
        self.output_path = Path(output_path)
        self.id_mapper = id_mapper
        self.individual_results = {}
    
    def compare_individual_vehicles(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """
        Compare individual vehicles between simulators
        
        Args:
            htc_data: HTC vehicle data
            ref_data: Reference vehicle data
            
        Returns:
            Individual vehicle comparison results
        """
        logger.info("🚗 Iniciando comparação individual de veículos...")
        
        vehicle_comparisons = {}
        
        # Get mapped vehicle pairs
        total_vehicles = len(self.id_mapper.htc_to_ref_cars)
        logger.info(f"   📊 Total de veículos a comparar: {total_vehicles}")
        
        processed = 0
        for htc_id, ref_id in self.id_mapper.htc_to_ref_cars.items():
            vehicle_comparison = self._compare_single_vehicle(htc_data, ref_data, htc_id, ref_id)
            if vehicle_comparison:
                base_id = self.id_mapper.normalize_htc_car_id(htc_id)
                vehicle_comparisons[base_id] = vehicle_comparison
            
            processed += 1
            if processed % 100 == 0 or processed == total_vehicles:
                progress = (processed / total_vehicles) * 100
                logger.info(f"   🔄 Progresso: {processed}/{total_vehicles} ({progress:.1f}%) veículos processados")
        
        # Calculate summary statistics
        summary = self._calculate_vehicle_summary(vehicle_comparisons)
        
        result = {
            'individual_vehicles': vehicle_comparisons,
            'summary': summary,
            'total_compared': len(vehicle_comparisons)
        }
        
        logger.info(f"✅ Comparados {len(vehicle_comparisons)} veículos individualmente")
        return result
    
    def _compare_single_vehicle(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame, 
                               htc_id: str, ref_id: str) -> Dict[str, Any]:
        """
        Compare a single vehicle between simulators
        
        Args:
            htc_data: HTC data
            ref_data: Reference data
            htc_id: HTC vehicle ID
            ref_id: Reference vehicle ID
            
        Returns:
            Single vehicle comparison
        """
        # Get vehicle data
        htc_vehicle = htc_data[htc_data['car_id'] == htc_id].copy()
        ref_vehicle = ref_data[ref_data['car_id'] == ref_id].copy()
        
        if htc_vehicle.empty or ref_vehicle.empty:
            return None
        
        # Sort by time
        time_col_htc = 'tick' if 'tick' in htc_vehicle.columns else 'timestamp'
        time_col_ref = 'tick' if 'tick' in ref_vehicle.columns else 'time'
        
        # Convert time columns to numeric for sorting
        try:
            if time_col_htc in htc_vehicle.columns:
                htc_vehicle[time_col_htc] = pd.to_numeric(htc_vehicle[time_col_htc], errors='coerce')
            if time_col_ref in ref_vehicle.columns:
                ref_vehicle[time_col_ref] = pd.to_numeric(ref_vehicle[time_col_ref], errors='coerce')
                
            # Remove rows with invalid time values
            htc_vehicle = htc_vehicle.dropna(subset=[time_col_htc])
            ref_vehicle = ref_vehicle.dropna(subset=[time_col_ref])
            
            htc_vehicle = htc_vehicle.sort_values(time_col_htc)
            ref_vehicle = ref_vehicle.sort_values(time_col_ref)
        except Exception as e:
            logger.warning(f"⚠️ Error sorting by time for {htc_id}: {e}")
            # Continue without sorting if there's an error
        
        comparison = {
            'htc_id': htc_id,
            'ref_id': ref_id,
            'htc_events': len(htc_vehicle),
            'ref_events': len(ref_vehicle),
            'journey_comparison': self._compare_vehicle_journey(htc_vehicle, ref_vehicle),
            'route_comparison': self._compare_vehicle_route(htc_vehicle, ref_vehicle),
            'temporal_comparison': self._compare_vehicle_timing(htc_vehicle, ref_vehicle),
            'similarity_score': 0.0
        }
        
        # Calculate overall vehicle similarity
        comparison['similarity_score'] = self._calculate_vehicle_similarity(comparison)
        
        return comparison
    
    def _compare_vehicle_journey(self, htc_vehicle: pd.DataFrame, ref_vehicle: pd.DataFrame) -> Dict[str, Any]:
        """Compare journey characteristics"""
        journey = {}
        
        # Journey duration
        time_col_htc = 'tick' if 'tick' in htc_vehicle.columns else 'timestamp'
        time_col_ref = 'tick' if 'tick' in ref_vehicle.columns else 'time'
        
        htc_duration = htc_vehicle[time_col_htc].max() - htc_vehicle[time_col_htc].min()
        ref_duration = ref_vehicle[time_col_ref].max() - ref_vehicle[time_col_ref].min()
        
        journey['htc_duration'] = float(htc_duration) if not pd.isna(htc_duration) else 0.0
        journey['ref_duration'] = float(ref_duration) if not pd.isna(ref_duration) else 0.0
        
        # Duration similarity (0-1)
        if max(journey['htc_duration'], journey['ref_duration']) > 0:
            duration_diff = abs(journey['htc_duration'] - journey['ref_duration'])
            max_duration = max(journey['htc_duration'], journey['ref_duration'])
            journey['duration_similarity'] = 1 - (duration_diff / max_duration)
        else:
            journey['duration_similarity'] = 1.0
        
        # Distance comparison (if available)
        if 'total_distance' in htc_vehicle.columns and 'total_distance' in ref_vehicle.columns:
            htc_distance = htc_vehicle['total_distance'].iloc[-1] if not htc_vehicle.empty else 0
            ref_distance = ref_vehicle['total_distance'].iloc[-1] if not ref_vehicle.empty else 0
            
            journey['htc_distance'] = float(htc_distance)
            journey['ref_distance'] = float(ref_distance)
            
            if max(htc_distance, ref_distance) > 0:
                distance_diff = abs(htc_distance - ref_distance)
                max_distance = max(htc_distance, ref_distance)
                journey['distance_similarity'] = 1 - (distance_diff / max_distance)
            else:
                journey['distance_similarity'] = 1.0
        else:
            journey['distance_similarity'] = None
        
        return journey
    
    def _compare_vehicle_route(self, htc_vehicle: pd.DataFrame, ref_vehicle: pd.DataFrame) -> Dict[str, Any]:
        """Compare route characteristics"""
        route = {}
        
        # Links used
        htc_links = set(htc_vehicle['link_id'].dropna().astype(str)) if 'link_id' in htc_vehicle.columns else set()
        ref_links = set(ref_vehicle['link_id'].dropna().astype(str)) if 'link_id' in ref_vehicle.columns else set()
        
        # Normalize link IDs for comparison
        htc_normalized = set()
        ref_normalized = set()
        
        for link in htc_links:
            normalized = self.id_mapper.normalize_htc_link_id(link)
            if normalized:
                htc_normalized.add(normalized)
        
        for link in ref_links:
            normalized = self.id_mapper.normalize_ref_link_id(link)
            if normalized:
                ref_normalized.add(normalized)
        
        route['htc_links'] = list(htc_links)
        route['ref_links'] = list(ref_links)
        route['htc_normalized_links'] = list(htc_normalized)
        route['ref_normalized_links'] = list(ref_normalized)
        
        # Route similarity
        common_links = htc_normalized & ref_normalized
        all_links = htc_normalized | ref_normalized
        
        route['common_links'] = list(common_links)
        route['htc_unique_links'] = list(htc_normalized - common_links)
        route['ref_unique_links'] = list(ref_normalized - common_links)
        
        if all_links:
            route['route_similarity'] = len(common_links) / len(all_links)
        else:
            route['route_similarity'] = 1.0 if not htc_normalized and not ref_normalized else 0.0
        
        return route
    
    def _compare_vehicle_timing(self, htc_vehicle: pd.DataFrame, ref_vehicle: pd.DataFrame) -> Dict[str, Any]:
        """Compare timing patterns"""
        timing = {}
        
        time_col_htc = 'tick' if 'tick' in htc_vehicle.columns else 'timestamp'
        time_col_ref = 'tick' if 'tick' in ref_vehicle.columns else 'time'
        
        # Start and end times
        timing['htc_start'] = float(htc_vehicle[time_col_htc].min())
        timing['htc_end'] = float(htc_vehicle[time_col_htc].max())
        timing['ref_start'] = float(ref_vehicle[time_col_ref].min())
        timing['ref_end'] = float(ref_vehicle[time_col_ref].max())
        
        # Timing similarity based on start time difference
        start_diff = abs(timing['htc_start'] - timing['ref_start'])
        total_time_range = max(timing['htc_end'], timing['ref_end']) - min(timing['htc_start'], timing['ref_start'])
        
        if total_time_range > 0:
            timing['start_similarity'] = 1 - (start_diff / total_time_range)
        else:
            timing['start_similarity'] = 1.0
        
        return timing
    
    def _calculate_vehicle_similarity(self, comparison: Dict[str, Any]) -> float:
        """Calculate overall vehicle similarity score"""
        scores = []
        weights = []
        
        # Journey similarity
        journey = comparison['journey_comparison']
        if 'duration_similarity' in journey:
            scores.append(journey['duration_similarity'])
            weights.append(0.3)
        
        if journey.get('distance_similarity') is not None:
            scores.append(journey['distance_similarity'])
            weights.append(0.3)
        
        # Route similarity
        route = comparison['route_comparison']
        scores.append(route['route_similarity'])
        weights.append(0.3)
        
        # Timing similarity
        timing = comparison['temporal_comparison']
        scores.append(timing['start_similarity'])
        weights.append(0.1)
        
        if scores and weights:
            return float(np.average(scores, weights=weights))
        else:
            return 0.0
    
    def compare_individual_links(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """
        Compare individual links between simulators
        
        Args:
            htc_data: HTC data
            ref_data: Reference data
            
        Returns:
            Individual link comparison results
        """
        logger.info("🛣️ Iniciando comparação individual de links...")
        
        link_comparisons = {}
        
        # Get mapped link pairs
        if hasattr(self.id_mapper, 'htc_to_ref_links') and self.id_mapper.htc_to_ref_links:
            total_links = len(self.id_mapper.htc_to_ref_links)
            logger.info(f"   📊 Total de links a comparar: {total_links}")
            
            processed = 0
            for htc_id, ref_id in self.id_mapper.htc_to_ref_links.items():
                link_comparison = self._compare_single_link(htc_data, ref_data, htc_id, ref_id)
                if link_comparison:
                    base_id = self.id_mapper.normalize_htc_link_id(htc_id)
                    link_comparisons[base_id] = link_comparison
                
                processed += 1
                if processed % 50 == 0 or processed == total_links:
                    progress = (processed / total_links) * 100
                    logger.info(f"   🔄 Progresso: {processed}/{total_links} ({progress:.1f}%) links processados")
        else:
            logger.info("   ⚠️ Nenhum mapeamento de links encontrado, pulando comparação de links")
        
        # Calculate summary statistics
        summary = self._calculate_link_summary(link_comparisons)
        
        result = {
            'individual_links': link_comparisons,
            'summary': summary,
            'total_compared': len(link_comparisons)
        }
        
        logger.info(f"✅ Comparados {len(link_comparisons)} links individualmente")
        return result
    
    def _compare_single_link(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame,
                            htc_id: str, ref_id: str) -> Dict[str, Any]:
        """Compare a single link between simulators"""
        # Get link usage data
        htc_link = htc_data[htc_data['link_id'] == htc_id].copy()
        ref_link = ref_data[ref_data['link_id'] == ref_id].copy()
        
        if htc_link.empty or ref_link.empty:
            return None
        
        comparison = {
            'htc_id': htc_id,
            'ref_id': ref_id,
            'htc_usage': len(htc_link),
            'ref_usage': len(ref_link),
            'htc_unique_vehicles': htc_link['car_id'].nunique(),
            'ref_unique_vehicles': ref_link['car_id'].nunique(),
            'usage_similarity': 0.0,
            'vehicle_similarity': 0.0
        }
        
        # Usage similarity
        max_usage = max(comparison['htc_usage'], comparison['ref_usage'])
        if max_usage > 0:
            usage_diff = abs(comparison['htc_usage'] - comparison['ref_usage'])
            comparison['usage_similarity'] = 1 - (usage_diff / max_usage)
        else:
            comparison['usage_similarity'] = 1.0
        
        # Vehicle count similarity
        max_vehicles = max(comparison['htc_unique_vehicles'], comparison['ref_unique_vehicles'])
        if max_vehicles > 0:
            vehicle_diff = abs(comparison['htc_unique_vehicles'] - comparison['ref_unique_vehicles'])
            comparison['vehicle_similarity'] = 1 - (vehicle_diff / max_vehicles)
        else:
            comparison['vehicle_similarity'] = 1.0
        
        # Overall link similarity
        comparison['similarity_score'] = (comparison['usage_similarity'] + comparison['vehicle_similarity']) / 2
        
        return comparison
    
    def _calculate_vehicle_summary(self, vehicle_comparisons: Dict[str, Any]) -> Dict[str, Any]:
        """Calculate summary statistics for vehicle comparisons"""
        if not vehicle_comparisons:
            return {}
        
        similarities = [v['similarity_score'] for v in vehicle_comparisons.values()]
        
        return {
            'average_similarity': float(np.mean(similarities)),
            'median_similarity': float(np.median(similarities)),
            'std_similarity': float(np.std(similarities)),
            'min_similarity': float(np.min(similarities)),
            'max_similarity': float(np.max(similarities)),
            'high_similarity_count': sum(1 for s in similarities if s >= 0.8),
            'low_similarity_count': sum(1 for s in similarities if s < 0.4)
        }
    
    def _calculate_link_summary(self, link_comparisons: Dict[str, Any]) -> Dict[str, Any]:
        """Calculate summary statistics for link comparisons"""
        if not link_comparisons:
            return {}
        
        similarities = [l['similarity_score'] for l in link_comparisons.values()]
        
        return {
            'average_similarity': float(np.mean(similarities)),
            'median_similarity': float(np.median(similarities)),
            'std_similarity': float(np.std(similarities)),
            'min_similarity': float(np.min(similarities)),
            'max_similarity': float(np.max(similarities)),
            'high_similarity_count': sum(1 for s in similarities if s >= 0.8),
            'low_similarity_count': sum(1 for s in similarities if s < 0.4)
        }
    
    def save_individual_results(self, vehicle_results: Dict[str, Any], link_results: Dict[str, Any]):
        """Save individual comparison results"""
        logger.info("💾 Salvando resultados de comparação individual...")
        
        # Save detailed results
        individual_results = {
            'timestamp': pd.Timestamp.now().isoformat(),
            'vehicles': vehicle_results,
            'links': link_results
        }
        
        # Save to JSON
        results_file = self.output_path / "individual_comparison_results.json"
        with open(results_file, 'w') as f:
            json.dump(individual_results, f, indent=2, default=str)
        
        # Generate summary report
        self._generate_individual_summary_report(vehicle_results, link_results)
        
        logger.info(f"✅ Resultados individuais salvos em: {self.output_path}")
    
    def _generate_individual_summary_report(self, vehicle_results: Dict[str, Any], link_results: Dict[str, Any]):
        """Generate markdown summary report for individual comparisons"""
        
        vehicle_summary = vehicle_results.get('summary', {})
        link_summary = link_results.get('summary', {})
        
        report = f"""# 🎯 Relatório de Comparação Individual

**Data**: {pd.Timestamp.now().strftime('%Y-%m-%d %H:%M:%S')}

## 🚗 Comparação Individual de Veículos

### Estatísticas Gerais:
- **Total de Veículos Comparados**: {vehicle_results.get('total_compared', 0)}
- **Similaridade Média**: {vehicle_summary.get('average_similarity', 0):.3f}
- **Similaridade Mediana**: {vehicle_summary.get('median_similarity', 0):.3f}
- **Desvio Padrão**: {vehicle_summary.get('std_similarity', 0):.3f}

### Distribuição de Qualidade:
- **Alta Similaridade (≥0.8)**: {vehicle_summary.get('high_similarity_count', 0)} veículos
- **Baixa Similaridade (<0.4)**: {vehicle_summary.get('low_similarity_count', 0)} veículos
- **Melhor Score**: {vehicle_summary.get('max_similarity', 0):.3f}
- **Pior Score**: {vehicle_summary.get('min_similarity', 0):.3f}

## 🛣️ Comparação Individual de Links

### Estatísticas Gerais:
- **Total de Links Comparados**: {link_results.get('total_compared', 0)}
- **Similaridade Média**: {link_summary.get('average_similarity', 0):.3f}
- **Similaridade Mediana**: {link_summary.get('median_similarity', 0):.3f}
- **Desvio Padrão**: {link_summary.get('std_similarity', 0):.3f}

### Distribuição de Qualidade:
- **Alta Similaridade (≥0.8)**: {link_summary.get('high_similarity_count', 0)} links
- **Baixa Similaridade (<0.4)**: {link_summary.get('low_similarity_count', 0)} links
- **Melhor Score**: {link_summary.get('max_similarity', 0):.3f}
- **Pior Score**: {link_summary.get('min_similarity', 0):.3f}

## 🏆 Conclusões Individuais

### Veículos:
"""
        
        if vehicle_summary.get('average_similarity', 0) >= 0.8:
            report += "✅ **EXCELENTE**: Veículos individuais mostram alta similaridade.\n"
        elif vehicle_summary.get('average_similarity', 0) >= 0.6:
            report += "✅ **BOM**: Maioria dos veículos são similares.\n"
        elif vehicle_summary.get('average_similarity', 0) >= 0.4:
            report += "⚠️ **MODERADO**: Algumas diferenças significativas entre veículos.\n"
        else:
            report += "❌ **PROBLEMÁTICO**: Veículos mostram diferenças substanciais.\n"
        
        report += "\n### Links:\n"
        
        if link_summary.get('average_similarity', 0) >= 0.8:
            report += "✅ **EXCELENTE**: Links individuais mostram alta similaridade.\n"
        elif link_summary.get('average_similarity', 0) >= 0.6:
            report += "✅ **BOM**: Maioria dos links são similares.\n"
        elif link_summary.get('average_similarity', 0) >= 0.4:
            report += "⚠️ **MODERADO**: Algumas diferenças significativas entre links.\n"
        else:
            report += "❌ **PROBLEMÁTICO**: Links mostram diferenças substanciais.\n"
        
        # Save report
        summary_file = self.output_path / "individual_comparison_summary.md"
        with open(summary_file, 'w') as f:
            f.write(report)
    
    def create_individual_visualizations(self, vehicle_results: Dict[str, Any], link_results: Dict[str, Any]):
        """Create visualizations for individual comparisons"""
        logger.info("📊 Criando visualizações de comparação individual...")
        
        # Vehicle similarity distribution
        if vehicle_results.get('individual_vehicles'):
            self._plot_similarity_distribution(
                vehicle_results['individual_vehicles'], 
                'Distribuição de Similaridade - Veículos',
                'vehicle_similarity_distribution.png'
            )
        
        # Link similarity distribution
        if link_results.get('individual_links'):
            self._plot_similarity_distribution(
                link_results['individual_links'], 
                'Distribuição de Similaridade - Links',
                'link_similarity_distribution.png'
            )
        
        logger.info("✅ Visualizações individuais criadas")
    
    def _plot_similarity_distribution(self, comparisons: Dict[str, Any], title: str, filename: str):
        """Plot similarity score distribution"""
        similarities = [comp['similarity_score'] for comp in comparisons.values()]
        
        plt.figure(figsize=(10, 6))
        plt.hist(similarities, bins=20, alpha=0.7, edgecolor='black')
        plt.title(title)
        plt.xlabel('Score de Similaridade')
        plt.ylabel('Quantidade')
        plt.axvline(np.mean(similarities), color='red', linestyle='--', label=f'Média: {np.mean(similarities):.3f}')
        plt.legend()
        plt.grid(True, alpha=0.3)
        
        plt.savefig(self.output_path / filename, dpi=300, bbox_inches='tight')
        plt.close()
    
    def generate_academic_pdf(self, individual_results: Dict[str, Any]) -> Path:
        """
        Gera PDF acadêmico com comparação individual
        
        Args:
            individual_results: Resultados da comparação individual
            
        Returns:
            Path para o PDF gerado
        """
        logger.info("📄 Gerando PDF acadêmico para comparação individual...")
        
        try:
            # Import acadêmico (pode falhar se dependências não estiverem instaladas)
            import sys
            sys.path.append(str(self.output_path.parent.parent))
            from visualization.academic_viz import create_academic_pdf_report
            
            # Gerar PDF
            pdf_paths = create_academic_pdf_report(
                'individual',
                individual_results=individual_results,
                output_path=self.output_path / "academic_reports",
                filename="individual_comparison_academic.pdf"
            )
            
            if pdf_paths:
                logger.info(f"✅ PDF acadêmico gerado: {pdf_paths[0]}")
                return pdf_paths[0]
            else:
                logger.warning("⚠️ Nenhum PDF foi gerado")
                return None
                
        except ImportError as e:
            logger.warning(f"⚠️ Dependências para PDF não encontradas: {e}")
            logger.info("💡 Para gerar PDFs, instale: pip install matplotlib seaborn plotly kaleido")
            return None
        except Exception as e:
            logger.warning(f"⚠️ Erro ao gerar PDF acadêmico: {e}")
            return None