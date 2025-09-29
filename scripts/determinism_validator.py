#!/usr/bin/env python3
"""
Análise estatística detalhada para validação de determinismo
"""

import pandas as pd
import numpy as np
import json
import sys
import logging
from pathlib import Path
from typing import List, Dict, Tuple
from scipy import stats
import matplotlib.pyplot as plt
import seaborn as sns

# Add the parent directory to Python path
sys.path.append(str(Path(__file__).parent))

from data_sources.cassandra_source import CassandraDataSource

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class DeterminismValidator:
    """
    Validator for checking determinism in simulation outputs
    """
    
    def __init__(self, output_dir: str = "determinism_validation"):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.cassandra = CassandraDataSource()
        
    def validate_determinism(self, simulation_ids: List[str], sample_size: int = 5000) -> Dict:
        """
        Comprehensive determinism validation
        """
        logger.info(f"🔬 Validando determinismo para {len(simulation_ids)} simulações")
        
        # Load all simulation data
        simulation_data = {}
        for sim_id in simulation_ids:
            logger.info(f"📊 Carregando dados de {sim_id}...")
            df = self.cassandra.get_vehicle_flow_data(simulation_id=sim_id, limit=sample_size)
            if not df.empty:
                simulation_data[sim_id] = df
                logger.info(f"✅ {sim_id}: {len(df)} registros carregados")
            else:
                logger.warning(f"⚠️ {sim_id}: Nenhum dado encontrado")
        
        if len(simulation_data) < 2:
            logger.error("❌ Necessário pelo menos 2 simulações para validação")
            return {}
        
        # Perform comprehensive analysis
        results = {
            "metadata": {
                "simulation_ids": list(simulation_data.keys()),
                "num_simulations": len(simulation_data),
                "sample_size": sample_size
            },
            "basic_statistics": self._analyze_basic_statistics(simulation_data),
            "temporal_analysis": self._analyze_temporal_patterns(simulation_data),
            "event_sequence_analysis": self._analyze_event_sequences(simulation_data),
            "statistical_tests": self._perform_statistical_tests(simulation_data),
            "determinism_score": 0.0,
            "conclusion": ""
        }
        
        # Calculate overall determinism score
        results["determinism_score"] = self._calculate_determinism_score(results)
        results["conclusion"] = self._generate_conclusion(results)
        
        # Save results
        self._save_results(results)
        self._generate_visualizations(simulation_data, results)
        
        return results
    
    def _analyze_basic_statistics(self, simulation_data: Dict[str, pd.DataFrame]) -> Dict:
        """Analyze basic statistical measures"""
        logger.info("📊 Analisando estatísticas básicas...")
        
        basic_stats = {
            "record_counts": {},
            "vehicle_counts": {},
            "event_type_distributions": {},
            "coefficient_variations": {}
        }
        
        # Record and vehicle counts
        for sim_id, df in simulation_data.items():
            basic_stats["record_counts"][sim_id] = len(df)
            basic_stats["vehicle_counts"][sim_id] = df['car_id'].nunique() if 'car_id' in df.columns else 0
        
        # Calculate coefficient of variation for counts
        record_counts = list(basic_stats["record_counts"].values())
        vehicle_counts = list(basic_stats["vehicle_counts"].values())
        
        basic_stats["coefficient_variations"]["record_count_cv"] = np.std(record_counts) / np.mean(record_counts) if np.mean(record_counts) > 0 else 0
        basic_stats["coefficient_variations"]["vehicle_count_cv"] = np.std(vehicle_counts) / np.mean(vehicle_counts) if np.mean(vehicle_counts) > 0 else 0
        
        # Event type distributions
        for sim_id, df in simulation_data.items():
            if 'event_type' in df.columns:
                basic_stats["event_type_distributions"][sim_id] = df['event_type'].value_counts().to_dict()
        
        return basic_stats
    
    def _analyze_temporal_patterns(self, simulation_data: Dict[str, pd.DataFrame]) -> Dict:
        """Analyze temporal patterns in the data"""
        logger.info("⏰ Analisando padrões temporais...")
        
        temporal_analysis = {
            "tick_overlaps": {},
            "event_timing_consistency": {},
            "temporal_correlations": {}
        }
        
        sim_ids = list(simulation_data.keys())
        
        # Analyze tick overlaps between simulations
        for i, sim1 in enumerate(sim_ids):
            for sim2 in sim_ids[i+1:]:
                df1, df2 = simulation_data[sim1], simulation_data[sim2]
                
                if 'tick' in df1.columns and 'tick' in df2.columns:
                    ticks1 = set(df1['tick'].unique())
                    ticks2 = set(df2['tick'].unique())
                    
                    overlap = len(ticks1 & ticks2)
                    total_unique = len(ticks1 | ticks2)
                    overlap_ratio = overlap / total_unique if total_unique > 0 else 0
                    
                    temporal_analysis["tick_overlaps"][f"{sim1}_vs_{sim2}"] = {
                        "common_ticks": overlap,
                        "total_unique_ticks": total_unique,
                        "overlap_ratio": overlap_ratio
                    }
        
        return temporal_analysis
    
    def _analyze_event_sequences(self, simulation_data: Dict[str, pd.DataFrame]) -> Dict:
        """Analyze event sequences and patterns"""
        logger.info("🔄 Analisando sequências de eventos...")
        
        sequence_analysis = {
            "vehicle_overlap": {},
            "event_pattern_similarity": {},
            "first_n_events_match": {}
        }
        
        sim_ids = list(simulation_data.keys())
        
        # Vehicle overlap analysis
        for i, sim1 in enumerate(sim_ids):
            for sim2 in sim_ids[i+1:]:
                df1, df2 = simulation_data[sim1], simulation_data[sim2]
                
                if 'car_id' in df1.columns and 'car_id' in df2.columns:
                    vehicles1 = set(df1['car_id'].unique())
                    vehicles2 = set(df2['car_id'].unique())
                    
                    overlap = len(vehicles1 & vehicles2)
                    total_unique = len(vehicles1 | vehicles2)
                    overlap_ratio = overlap / total_unique if total_unique > 0 else 0
                    
                    sequence_analysis["vehicle_overlap"][f"{sim1}_vs_{sim2}"] = {
                        "common_vehicles": overlap,
                        "total_unique_vehicles": total_unique,
                        "overlap_ratio": overlap_ratio
                    }
                
                # Check if first N events match (deterministic start)
                n_events = min(100, len(df1), len(df2))  # First 100 events
                if n_events > 0:
                    # Sort by tick to get chronological order
                    if 'tick' in df1.columns and 'tick' in df2.columns:
                        df1_sorted = df1.sort_values('tick').head(n_events)
                        df2_sorted = df2.sort_values('tick').head(n_events)
                        
                        # Compare event types and timing
                        matches = 0
                        for idx in range(min(len(df1_sorted), len(df2_sorted))):
                            event1 = df1_sorted.iloc[idx]
                            event2 = df2_sorted.iloc[idx]
                            
                            if ('event_type' in df1.columns and 
                                event1.get('event_type') == event2.get('event_type') and
                                event1.get('tick') == event2.get('tick')):
                                matches += 1
                        
                        match_ratio = matches / n_events if n_events > 0 else 0
                        sequence_analysis["first_n_events_match"][f"{sim1}_vs_{sim2}"] = {
                            "events_compared": n_events,
                            "matches": matches,
                            "match_ratio": match_ratio
                        }
        
        return sequence_analysis
    
    def _perform_statistical_tests(self, simulation_data: Dict[str, pd.DataFrame]) -> Dict:
        """Perform statistical tests for determinism"""
        logger.info("📈 Realizando testes estatísticos...")
        
        statistical_tests = {
            "anova_tests": {},
            "kruskal_wallis_tests": {},
            "chi_square_tests": {}
        }
        
        # Collect numerical columns for testing
        numerical_columns = []
        for df in simulation_data.values():
            for col in df.columns:
                if df[col].dtype in ['int64', 'float64'] and col not in ['id', 'tick']:
                    numerical_columns.append(col)
        
        numerical_columns = list(set(numerical_columns))
        
        # Perform tests for each numerical column
        for column in numerical_columns:
            column_data = []
            valid_sims = []
            
            for sim_id, df in simulation_data.items():
                if column in df.columns:
                    values = df[column].dropna()
                    if len(values) > 0:
                        column_data.append(values.values)
                        valid_sims.append(sim_id)
            
            if len(column_data) >= 2:
                try:
                    # ANOVA test (parametric)
                    f_stat, p_value_anova = stats.f_oneway(*column_data)
                    statistical_tests["anova_tests"][column] = {
                        "f_statistic": f_stat,
                        "p_value": p_value_anova,
                        "significant": p_value_anova < 0.05
                    }
                    
                    # Kruskal-Wallis test (non-parametric)
                    h_stat, p_value_kw = stats.kruskal(*column_data)
                    statistical_tests["kruskal_wallis_tests"][column] = {
                        "h_statistic": h_stat,
                        "p_value": p_value_kw,
                        "significant": p_value_kw < 0.05
                    }
                    
                except Exception as e:
                    logger.warning(f"⚠️ Erro no teste estatístico para {column}: {e}")
        
        return statistical_tests
    
    def _calculate_determinism_score(self, results: Dict) -> float:
        """Calculate overall determinism score (0-1)"""
        score_components = []
        
        # Basic statistics component (30%)
        basic_cv = results["basic_statistics"]["coefficient_variations"]
        record_cv = basic_cv.get("record_count_cv", 1.0)
        vehicle_cv = basic_cv.get("vehicle_count_cv", 1.0)
        
        basic_score = max(0, 1 - (record_cv + vehicle_cv) / 0.02)  # Perfect if CV < 0.01
        score_components.append(("basic_statistics", basic_score, 0.3))
        
        # Temporal analysis component (40%)
        temporal_scores = []
        if "tick_overlaps" in results["temporal_analysis"]:
            for overlap_data in results["temporal_analysis"]["tick_overlaps"].values():
                temporal_scores.append(overlap_data.get("overlap_ratio", 0))
        
        temporal_score = np.mean(temporal_scores) if temporal_scores else 0
        score_components.append(("temporal_patterns", temporal_score, 0.4))
        
        # Event sequence component (30%)
        sequence_scores = []
        if "vehicle_overlap" in results["event_sequence_analysis"]:
            for overlap_data in results["event_sequence_analysis"]["vehicle_overlap"].values():
                sequence_scores.append(overlap_data.get("overlap_ratio", 0))
        
        if "first_n_events_match" in results["event_sequence_analysis"]:
            for match_data in results["event_sequence_analysis"]["first_n_events_match"].values():
                sequence_scores.append(match_data.get("match_ratio", 0))
        
        sequence_score = np.mean(sequence_scores) if sequence_scores else 0
        score_components.append(("event_sequences", sequence_score, 0.3))
        
        # Calculate weighted average
        total_score = sum(score * weight for _, score, weight in score_components)
        
        logger.info(f"🎯 Componentes do score de determinismo:")
        for name, score, weight in score_components:
            logger.info(f"  • {name}: {score:.3f} (peso: {weight})")
        
        return total_score
    
    def _generate_conclusion(self, results: Dict) -> str:
        """Generate conclusion about determinism"""
        score = results["determinism_score"]
        
        if score >= 0.95:
            return "ALTAMENTE DETERMINÍSTICO - Simulador produz resultados quase idênticos"
        elif score >= 0.80:
            return "DETERMINÍSTICO - Simulador produz resultados consistentes"
        elif score >= 0.60:
            return "PARCIALMENTE DETERMINÍSTICO - Algumas variações entre execuções"
        elif score >= 0.30:
            return "POUCO DETERMINÍSTICO - Variações significativas entre execuções"
        else:
            return "NÃO DETERMINÍSTICO - Resultados variam substancialmente entre execuções"
    
    def _save_results(self, results: Dict):
        """Save analysis results"""
        output_file = self.output_dir / "determinism_analysis.json"
        with open(output_file, 'w') as f:
            json.dump(results, f, indent=2, default=str)
        logger.info(f"💾 Resultados salvos em: {output_file}")
    
    def _generate_visualizations(self, simulation_data: Dict, results: Dict):
        """Generate visualization plots"""
        logger.info("📊 Gerando visualizações...")
        
        # Set style
        plt.style.use('seaborn-v0_8')
        
        # Plot 1: Basic statistics comparison
        fig, axes = plt.subplots(2, 2, figsize=(15, 10))
        fig.suptitle('Análise de Determinismo - Estatísticas Básicas', fontsize=16)
        
        # Record counts
        sim_ids = list(simulation_data.keys())
        record_counts = [len(simulation_data[sim_id]) for sim_id in sim_ids]
        vehicle_counts = [simulation_data[sim_id]['car_id'].nunique() if 'car_id' in simulation_data[sim_id].columns else 0 for sim_id in sim_ids]
        
        axes[0, 0].bar(range(len(sim_ids)), record_counts)
        axes[0, 0].set_title('Número de Registros por Simulação')
        axes[0, 0].set_xticks(range(len(sim_ids)))
        axes[0, 0].set_xticklabels([f'Sim {i+1}' for i in range(len(sim_ids))], rotation=45)
        
        axes[0, 1].bar(range(len(sim_ids)), vehicle_counts)
        axes[0, 1].set_title('Número de Veículos por Simulação')
        axes[0, 1].set_xticks(range(len(sim_ids)))
        axes[0, 1].set_xticklabels([f'Sim {i+1}' for i in range(len(sim_ids))], rotation=45)
        
        # Temporal overlap visualization
        if "tick_overlaps" in results["temporal_analysis"]:
            overlap_ratios = [data["overlap_ratio"] for data in results["temporal_analysis"]["tick_overlaps"].values()]
            comparison_labels = list(results["temporal_analysis"]["tick_overlaps"].keys())
            
            axes[1, 0].bar(range(len(overlap_ratios)), overlap_ratios)
            axes[1, 0].set_title('Taxa de Sobreposição de Ticks')
            axes[1, 0].set_ylabel('Taxa de Sobreposição')
            axes[1, 0].set_xticks(range(len(comparison_labels)))
            axes[1, 0].set_xticklabels([label.replace('_vs_', ' vs ') for label in comparison_labels], rotation=45, ha='right')
            axes[1, 0].axhline(y=0.95, color='green', linestyle='--', label='Altamente Determinístico')
            axes[1, 0].axhline(y=0.80, color='orange', linestyle='--', label='Determinístico')
            axes[1, 0].legend()
        
        # Determinism score
        score = results["determinism_score"]
        axes[1, 1].pie([score, 1-score], labels=['Determinístico', 'Não Determinístico'], 
                      colors=['green' if score > 0.8 else 'orange' if score > 0.6 else 'red', 'lightgray'],
                      autopct='%1.1f%%', startangle=90)
        axes[1, 1].set_title(f'Score de Determinismo: {score:.3f}')
        
        plt.tight_layout()
        plt.savefig(self.output_dir / 'determinism_analysis.png', dpi=300, bbox_inches='tight')
        plt.close()
        
        logger.info(f"📊 Visualizações salvas em: {self.output_dir}")

def main():
    if len(sys.argv) < 2:
        print("Uso: python determinism_validator.py <sim_id1> <sim_id2> [sim_id3] ...")
        print("Exemplo: python determinism_validator.py determinism_test_run_1 determinism_test_run_2")
        sys.exit(1)
    
    simulation_ids = sys.argv[1:]
    
    validator = DeterminismValidator()
    results = validator.validate_determinism(simulation_ids)
    
    if results:
        print("\n" + "="*80)
        print("🔬 RESULTADOS DA VALIDAÇÃO DE DETERMINISMO")
        print("="*80)
        print(f"📊 Simulações analisadas: {len(results['metadata']['simulation_ids'])}")
        print(f"🎯 Score de determinismo: {results['determinism_score']:.3f}")
        print(f"📝 Conclusão: {results['conclusion']}")
        print("="*80)

if __name__ == "__main__":
    main()