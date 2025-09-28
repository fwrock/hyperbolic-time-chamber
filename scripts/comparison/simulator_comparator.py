"""
Sistema de comparação entre simuladores HTC vs Referência
"""
import pandas as pd
import numpy as np
from pathlib import Path
from typing import Dict, Any, List, Tuple
import logging
from datetime import datetime
import json
import matplotlib.pyplot as plt
import seaborn as sns
from scipy import stats
from scipy.spatial.distance import cosine
import warnings
warnings.filterwarnings('ignore')

# Set matplotlib backend
import matplotlib
matplotlib.use('Agg')

logger = logging.getLogger(__name__)

class SimulatorComparator:
    """
    Comparador entre resultados do HTC e simulador de referência
    """
    
    def __init__(self, output_path: Path):
        """
        Initialize comparator
        
        Args:
            output_path: Path to save comparison results
        """
        self.output_path = Path(output_path)
        self.output_path.mkdir(parents=True, exist_ok=True)
        
        # Comparison results storage
        self.comparison_results = {}
    
    def compare_traffic_flows(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """
        Compare traffic flows between HTC and reference simulator
        
        Args:
            htc_data: HTC simulator data
            ref_data: Reference simulator data
            
        Returns:
            Comparison results dictionary
        """
        logger.info("🔬 Iniciando comparação de fluxos de tráfego...")
        
        comparison = {
            'timestamp': datetime.now().isoformat(),
            'data_summary': {
                'htc_records': len(htc_data),
                'ref_records': len(ref_data),
                'htc_vehicles': htc_data['car_id'].nunique() if not htc_data.empty else 0,
                'ref_vehicles': ref_data['car_id'].nunique() if not ref_data.empty else 0,
                'htc_links': htc_data['link_id'].nunique() if 'link_id' in htc_data.columns and not htc_data.empty else 0,
                'ref_links': ref_data['link_id'].nunique() if not ref_data.empty else 0
            }
        }
        
        # Time-based comparison
        comparison['temporal_analysis'] = self._compare_temporal_patterns(htc_data, ref_data)
        
        # Link usage comparison
        comparison['link_analysis'] = self._compare_link_usage(htc_data, ref_data)
        
        # Event type comparison
        comparison['event_analysis'] = self._compare_event_types(htc_data, ref_data)
        
        # Statistical similarity
        comparison['statistical_similarity'] = self._calculate_statistical_similarity(htc_data, ref_data)
        
        # Overall similarity score
        comparison['overall_similarity'] = self._calculate_overall_similarity(comparison)
        
        self.comparison_results = comparison
        return comparison
    
    def _compare_temporal_patterns(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """Compare temporal patterns between simulators"""
        logger.info("⏰ Comparando padrões temporais...")
        
        temporal = {}
        
        if htc_data.empty or ref_data.empty:
            return {'error': 'One or both datasets are empty'}
        
        # Time range comparison
        htc_time_col = 'tick' if 'tick' in htc_data.columns else 'timestamp'
        ref_time_col = 'tick' if 'tick' in ref_data.columns else 'time'
        
        if htc_time_col in htc_data.columns and ref_time_col in ref_data.columns:
            temporal['time_ranges'] = {
                'htc': {
                    'start': float(htc_data[htc_time_col].min()),
                    'end': float(htc_data[htc_time_col].max()),
                    'duration': float(htc_data[htc_time_col].max() - htc_data[htc_time_col].min())
                },
                'ref': {
                    'start': float(ref_data[ref_time_col].min()),
                    'end': float(ref_data[ref_time_col].max()),
                    'duration': float(ref_data[ref_time_col].max() - ref_data[ref_time_col].min())
                }
            }
            
            # Activity by time bins
            htc_binned = pd.cut(htc_data[htc_time_col], bins=20).value_counts().sort_index()
            ref_binned = pd.cut(ref_data[ref_time_col], bins=20).value_counts().sort_index()
            
            # Calculate correlation between temporal patterns
            if len(htc_binned) == len(ref_binned) and len(htc_binned) > 1:
                correlation = np.corrcoef(htc_binned.values, ref_binned.values)[0, 1]
                temporal['temporal_correlation'] = float(correlation) if not np.isnan(correlation) else 0.0
            else:
                temporal['temporal_correlation'] = 0.0
        
        return temporal
    
    def _compare_link_usage(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """Compare link usage patterns"""
        logger.info("🛣️ Comparando uso de links...")
        
        link_analysis = {}
        
        if htc_data.empty or ref_data.empty:
            return {'error': 'One or both datasets are empty'}
        
        # Link usage frequency
        htc_links = htc_data['link_id'].value_counts() if 'link_id' in htc_data.columns else pd.Series()
        ref_links = ref_data['link_id'].value_counts() if 'link_id' in ref_data.columns else pd.Series()
        
        if not htc_links.empty and not ref_links.empty:
            # Common links
            common_links = set(htc_links.index) & set(ref_links.index)
            
            link_analysis['common_links'] = len(common_links)
            link_analysis['htc_unique_links'] = len(set(htc_links.index) - common_links)
            link_analysis['ref_unique_links'] = len(set(ref_links.index) - common_links)
            
            if common_links:
                # Calculate usage similarity for common links
                htc_common = htc_links[list(common_links)]
                ref_common = ref_links[list(common_links)]
                
                # Normalize to compare patterns
                htc_norm = htc_common / htc_common.sum()
                ref_norm = ref_common / ref_common.sum()
                
                # Cosine similarity
                similarity = 1 - cosine(htc_norm.values, ref_norm.values)
                link_analysis['usage_similarity'] = float(similarity) if not np.isnan(similarity) else 0.0
            else:
                link_analysis['usage_similarity'] = 0.0
        
        return link_analysis
    
    def _compare_event_types(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """Compare event type distributions"""
        logger.info("🎯 Comparando tipos de eventos...")
        
        event_analysis = {}
        
        if htc_data.empty or ref_data.empty:
            return {'error': 'One or both datasets are empty'}
        
        # Event type distribution
        htc_events = htc_data['event_type'].value_counts() if 'event_type' in htc_data.columns else pd.Series()
        ref_events = ref_data['event_type'].value_counts() if 'event_type' in ref_data.columns else pd.Series()
        
        if not htc_events.empty and not ref_events.empty:
            event_analysis['htc_events'] = htc_events.to_dict()
            event_analysis['ref_events'] = ref_events.to_dict()
            
            # Common event types
            common_events = set(htc_events.index) & set(ref_events.index)
            event_analysis['common_event_types'] = list(common_events)
            
            if common_events:
                # Calculate distribution similarity
                htc_common = htc_events[list(common_events)]
                ref_common = ref_events[list(common_events)]
                
                # Normalize distributions
                htc_norm = htc_common / htc_common.sum()
                ref_norm = ref_common / ref_common.sum()
                
                # Jensen-Shannon divergence (symmetric)
                m = 0.5 * (htc_norm + ref_norm)
                js_div = 0.5 * stats.entropy(htc_norm, m) + 0.5 * stats.entropy(ref_norm, m)
                event_similarity = 1 - js_div
                
                event_analysis['event_distribution_similarity'] = float(event_similarity) if not np.isnan(event_similarity) else 0.0
            else:
                event_analysis['event_distribution_similarity'] = 0.0
        
        return event_analysis
    
    def _calculate_statistical_similarity(self, htc_data: pd.DataFrame, ref_data: pd.DataFrame) -> Dict[str, Any]:
        """Calculate statistical similarity measures"""
        logger.info("📊 Calculando similaridades estatísticas...")
        
        similarity = {}
        
        if htc_data.empty or ref_data.empty:
            return {'error': 'One or both datasets are empty'}
        
        # Volume similarity (total events)
        htc_volume = len(htc_data)
        ref_volume = len(ref_data)
        volume_similarity = 1 - abs(htc_volume - ref_volume) / max(htc_volume, ref_volume)
        similarity['volume_similarity'] = float(volume_similarity)
        
        # Vehicle count similarity
        htc_vehicles = htc_data['car_id'].nunique() if 'car_id' in htc_data.columns else 0
        ref_vehicles = ref_data['car_id'].nunique() if 'car_id' in ref_data.columns else 0
        
        if max(htc_vehicles, ref_vehicles) > 0:
            vehicle_similarity = 1 - abs(htc_vehicles - ref_vehicles) / max(htc_vehicles, ref_vehicles)
            similarity['vehicle_count_similarity'] = float(vehicle_similarity)
        else:
            similarity['vehicle_count_similarity'] = 0.0
        
        return similarity
    
    def _calculate_overall_similarity(self, comparison: Dict[str, Any]) -> Dict[str, Any]:
        """Calculate overall similarity score"""
        logger.info("🎯 Calculando score de similaridade geral...")
        
        scores = []
        weights = []
        
        # Temporal correlation
        temporal = comparison.get('temporal_analysis', {})
        if 'temporal_correlation' in temporal:
            scores.append(max(0, temporal['temporal_correlation']))
            weights.append(0.3)
        
        # Link usage similarity
        link = comparison.get('link_analysis', {})
        if 'usage_similarity' in link:
            scores.append(max(0, link['usage_similarity']))
            weights.append(0.3)
        
        # Event distribution similarity
        events = comparison.get('event_analysis', {})
        if 'event_distribution_similarity' in events:
            scores.append(max(0, events['event_distribution_similarity']))
            weights.append(0.2)
        
        # Statistical similarities
        stats_sim = comparison.get('statistical_similarity', {})
        if 'volume_similarity' in stats_sim:
            scores.append(max(0, stats_sim['volume_similarity']))
            weights.append(0.1)
        
        if 'vehicle_count_similarity' in stats_sim:
            scores.append(max(0, stats_sim['vehicle_count_similarity']))
            weights.append(0.1)
        
        if scores and weights:
            # Weighted average
            overall_score = np.average(scores, weights=weights)
            
            # Classification
            if overall_score >= 0.8:
                classification = "Muito Similar"
            elif overall_score >= 0.6:
                classification = "Similar"
            elif overall_score >= 0.4:
                classification = "Moderadamente Similar"
            elif overall_score >= 0.2:
                classification = "Pouco Similar"
            else:
                classification = "Muito Diferente"
                
            return {
                'score': float(overall_score),
                'classification': classification,
                'individual_scores': {
                    'temporal': scores[0] if len(scores) > 0 else 0,
                    'link_usage': scores[1] if len(scores) > 1 else 0,
                    'event_distribution': scores[2] if len(scores) > 2 else 0,
                    'volume': scores[3] if len(scores) > 3 else 0,
                    'vehicle_count': scores[4] if len(scores) > 4 else 0
                }
            }
        else:
            return {'score': 0.0, 'classification': 'Impossível Comparar', 'individual_scores': {}}
    
    def generate_comparison_report(self) -> str:
        """Generate detailed comparison report"""
        logger.info("📋 Gerando relatório de comparação...")
        
        if not self.comparison_results:
            return "Nenhuma comparação foi realizada ainda."
        
        report_file = self.output_path / "simulator_comparison_report.json"
        
        # Save detailed results
        with open(report_file, 'w') as f:
            json.dump(self.comparison_results, f, indent=2, default=str)
        
        # Generate summary report
        summary = self._generate_summary_report()
        
        summary_file = self.output_path / "comparison_summary.md"
        with open(summary_file, 'w') as f:
            f.write(summary)
        
        logger.info(f"✅ Relatórios salvos em: {self.output_path}")
        return summary
    
    def _generate_summary_report(self) -> str:
        """Generate markdown summary report"""
        results = self.comparison_results
        
        summary = f"""# 🔬 Relatório de Comparação de Simuladores

**Data**: {results.get('timestamp', 'N/A')}

## 📊 Resumo dos Dados

- **HTC Simulator**: {results['data_summary']['htc_records']} eventos, {results['data_summary']['htc_vehicles']} veículos
- **Simulador Referência**: {results['data_summary']['ref_records']} eventos, {results['data_summary']['ref_vehicles']} veículos

## 🎯 Score de Similaridade Geral

**Score**: {results['overall_similarity']['score']:.3f}
**Classificação**: {results['overall_similarity']['classification']}

### Scores Individuais:
- **Temporal**: {results['overall_similarity']['individual_scores'].get('temporal', 0):.3f}
- **Uso de Links**: {results['overall_similarity']['individual_scores'].get('link_usage', 0):.3f}
- **Distribuição de Eventos**: {results['overall_similarity']['individual_scores'].get('event_distribution', 0):.3f}
- **Volume**: {results['overall_similarity']['individual_scores'].get('volume', 0):.3f}
- **Contagem de Veículos**: {results['overall_similarity']['individual_scores'].get('vehicle_count', 0):.3f}

## ⏰ Análise Temporal

- **Correlação Temporal**: {results['temporal_analysis'].get('temporal_correlation', 0):.3f}

### Intervalos de Tempo:
- **HTC**: {results['temporal_analysis']['time_ranges']['htc']['start']:.1f} - {results['temporal_analysis']['time_ranges']['htc']['end']:.1f} (duração: {results['temporal_analysis']['time_ranges']['htc']['duration']:.1f})
- **Referência**: {results['temporal_analysis']['time_ranges']['ref']['start']:.1f} - {results['temporal_analysis']['time_ranges']['ref']['end']:.1f} (duração: {results['temporal_analysis']['time_ranges']['ref']['duration']:.1f})

## 🛣️ Análise de Links

- **Links Comuns**: {results['link_analysis'].get('common_links', 0)}
- **Links Únicos HTC**: {results['link_analysis'].get('htc_unique_links', 0)}
- **Links Únicos Referência**: {results['link_analysis'].get('ref_unique_links', 0)}
- **Similaridade de Uso**: {results['link_analysis'].get('usage_similarity', 0):.3f}

## 🎯 Análise de Eventos

- **Tipos Comuns**: {len(results['event_analysis'].get('common_event_types', []))}
- **Similaridade de Distribuição**: {results['event_analysis'].get('event_distribution_similarity', 0):.3f}

## 📈 Similaridades Estatísticas

- **Similaridade de Volume**: {results['statistical_similarity'].get('volume_similarity', 0):.3f}
- **Similaridade de Contagem de Veículos**: {results['statistical_similarity'].get('vehicle_count_similarity', 0):.3f}

## 🏆 Conclusão

"""

        # Add conclusion based on score
        score = results['overall_similarity']['score']
        if score >= 0.8:
            summary += "✅ **EXCELENTE**: Os simuladores produzem resultados muito similares. A validação foi bem-sucedida."
        elif score >= 0.6:
            summary += "✅ **BOM**: Os simuladores são similares com algumas diferenças menores. Validação satisfatória."
        elif score >= 0.4:
            summary += "⚠️ **MODERADO**: Há diferenças significativas que devem ser investigadas."
        elif score >= 0.2:
            summary += "❌ **PREOCUPANTE**: Grandes diferenças entre os simuladores. Revisão necessária."
        else:
            summary += "❌ **CRÍTICO**: Simuladores produzem resultados muito diferentes. Investigação urgente necessária."
        
        return summary
    
    def create_comparison_visualizations(self):
        """Create comparison visualizations"""
        logger.info("📊 Criando visualizações de comparação...")
        
        if not self.comparison_results:
            logger.warning("⚠️ Nenhum resultado de comparação disponível")
            return
        
        # Create similarity radar chart
        self._create_similarity_radar()
        
        # Create comparison bar charts
        self._create_comparison_bars()
        
        logger.info("✅ Visualizações criadas com sucesso")
    
    def _create_similarity_radar(self):
        """Create radar chart of similarity scores"""
        scores = self.comparison_results['overall_similarity']['individual_scores']
        
        categories = list(scores.keys())
        values = list(scores.values())
        
        # Create radar chart
        fig, ax = plt.subplots(figsize=(10, 10), subplot_kw=dict(projection='polar'))
        
        angles = np.linspace(0, 2 * np.pi, len(categories), endpoint=False).tolist()
        values += values[:1]  # Complete the circle
        angles += angles[:1]
        
        ax.plot(angles, values, 'o-', linewidth=2, label='Similaridade')
        ax.fill(angles, values, alpha=0.25)
        ax.set_xticks(angles[:-1])
        ax.set_xticklabels(categories)
        ax.set_ylim(0, 1)
        ax.set_title('Scores de Similaridade por Categoria', size=16, y=1.1)
        ax.grid(True)
        
        plt.savefig(self.output_path / 'similarity_radar.png', dpi=300, bbox_inches='tight')
        plt.close()
    
    def _create_comparison_bars(self):
        """Create comparison bar charts"""
        data_summary = self.comparison_results['data_summary']
        
        # Data comparison
        categories = ['Eventos', 'Veículos', 'Links']
        htc_values = [data_summary['htc_records'], data_summary['htc_vehicles'], data_summary['htc_links']]
        ref_values = [data_summary['ref_records'], data_summary['ref_vehicles'], data_summary['ref_links']]
        
        x = np.arange(len(categories))
        width = 0.35
        
        fig, ax = plt.subplots(figsize=(10, 6))
        ax.bar(x - width/2, htc_values, width, label='HTC Simulator', alpha=0.8)
        ax.bar(x + width/2, ref_values, width, label='Simulador Referência', alpha=0.8)
        
        ax.set_xlabel('Métricas')
        ax.set_ylabel('Contagem')
        ax.set_title('Comparação de Métricas entre Simuladores')
        ax.set_xticks(x)
        ax.set_xticklabels(categories)
        ax.legend()
        ax.grid(True, alpha=0.3)
        
        plt.savefig(self.output_path / 'data_comparison.png', dpi=300, bbox_inches='tight')
        plt.close()