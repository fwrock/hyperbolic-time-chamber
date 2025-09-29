#!/usr/bin/env python3
"""
Módulo para análise de reprodutibilidade de simulações
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from typing import Dict, Any, List, Tuple, Optional
import logging
from pathlib import Path
import json
from scipy import stats
from sklearn.metrics import mean_squared_error, mean_absolute_error
import warnings
warnings.filterwarnings('ignore')
from matplotlib.backends.backend_pdf import PdfPages


class ReproducibilityAnalyzer:
    """
    Classe para análise de reprodutibilidade entre múltiplas execuções da mesma simulação
    """
    
    def __init__(self, output_dir: str = "output/reproducibility"):
        """
        Inicializa o analisador de reprodutibilidade
        
        Args:
            output_dir: Diretório para salvar relatórios e gráficos
        """
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.logger = logging.getLogger(__name__)
        
        # Configuração de estilo dos gráficos
        try:
            plt.style.use('seaborn-v0_8')
        except OSError:
            try:
                plt.style.use('seaborn')
            except OSError:
                plt.style.use('default')
        
        sns.set_palette("husl")
    
    def analyze_multiple_runs(self, 
                            datasets: List[pd.DataFrame], 
                            run_names: List[str] = None,
                            reference_run: int = 0) -> Dict[str, Any]:
        """
        Analisa reprodutibilidade entre múltiplas execuções
        
        Args:
            datasets: Lista de DataFrames com dados de cada execução
            run_names: Nomes das execuções (opcional)
            reference_run: Índice da execução de referência (default: 0)
            
        Returns:
            Dicionário com análise completa de reprodutibilidade
        """
        if len(datasets) < 2:
            raise ValueError("São necessárias pelo menos 2 execuções para análise de reprodutibilidade")
        
        if run_names is None:
            run_names = [f"Run_{i+1}" for i in range(len(datasets))]
        
        self.logger.info(f"📊 Analisando reprodutibilidade de {len(datasets)} execuções...")
        
        analysis = {
            'summary': self._calculate_summary_reproducibility(datasets, run_names),
            'basic_metrics': self._compare_basic_metrics(datasets, run_names),
            'vehicle_level': self._analyze_vehicle_level_reproducibility(datasets, run_names, reference_run),
            'temporal_patterns': self._analyze_temporal_reproducibility(datasets, run_names),
            'spatial_patterns': self._analyze_spatial_reproducibility(datasets, run_names),
            'statistical_tests': self._perform_statistical_tests(datasets, run_names),
            'similarity_scores': self._calculate_similarity_scores(datasets, run_names, reference_run),
            'variability_analysis': self._analyze_variability(datasets, run_names)
        }
        
        return analysis
    
    def _calculate_summary_reproducibility(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Calcula métricas resumo de reprodutibilidade"""
        summary = {
            'num_runs': len(datasets),
            'run_names': run_names,
            'data_consistency': {},
            'overall_reproducibility_score': 0.0
        }
        
        # Verificar consistência de dados básicos
        event_counts = [len(df) for df in datasets]
        vehicle_counts = [df['car_id'].nunique() if 'car_id' in df.columns else 0 for df in datasets]
        link_counts = [df['link_id'].nunique() if 'link_id' in df.columns else 0 for df in datasets]
        
        summary['data_consistency'] = {
            'event_counts': event_counts,
            'vehicle_counts': vehicle_counts,
            'link_counts': link_counts,
            'event_count_cv': np.std(event_counts) / np.mean(event_counts) if np.mean(event_counts) > 0 else float('inf'),
            'vehicle_count_cv': np.std(vehicle_counts) / np.mean(vehicle_counts) if np.mean(vehicle_counts) > 0 else float('inf'),
            'link_count_cv': np.std(link_counts) / np.mean(link_counts) if np.mean(link_counts) > 0 else float('inf')
        }
        
        return summary
    
    def _compare_basic_metrics(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Compara métricas básicas entre execuções"""
        metrics_comparison = {}
        
        # Métricas a serem comparadas
        metrics_to_analyze = {
            'calculated_speed': 'Velocidade',
            'travel_time': 'Tempo de Viagem',
            'traffic_density': 'Densidade de Tráfego',
            'link_length': 'Comprimento do Link'
        }
        
        for metric_col, metric_name in metrics_to_analyze.items():
            if any(metric_col in df.columns for df in datasets):
                metric_stats = []
                
                for i, df in enumerate(datasets):
                    if metric_col in df.columns:
                        values = pd.to_numeric(df[metric_col], errors='coerce').dropna()
                        if not values.empty:
                            stats_dict = {
                                'run': run_names[i],
                                'count': len(values),
                                'mean': values.mean(),
                                'median': values.median(),
                                'std': values.std(),
                                'min': values.min(),
                                'max': values.max(),
                                'q25': values.quantile(0.25),
                                'q75': values.quantile(0.75)
                            }
                            metric_stats.append(stats_dict)
                
                if metric_stats:
                    # Calcular variabilidade entre execuções
                    means = [s['mean'] for s in metric_stats]
                    medians = [s['median'] for s in metric_stats]
                    stds = [s['std'] for s in metric_stats]
                    
                    metrics_comparison[metric_col] = {
                        'metric_name': metric_name,
                        'run_statistics': metric_stats,
                        'cross_run_variability': {
                            'mean_cv': np.std(means) / np.mean(means) if np.mean(means) > 0 else float('inf'),
                            'median_cv': np.std(medians) / np.mean(medians) if np.mean(medians) > 0 else float('inf'),
                            'std_cv': np.std(stds) / np.mean(stds) if np.mean(stds) > 0 else float('inf'),
                            'mean_range': max(means) - min(means),
                            'median_range': max(medians) - min(medians)
                        }
                    }
        
        return metrics_comparison
    
    def _analyze_vehicle_level_reproducibility(self, 
                                             datasets: List[pd.DataFrame], 
                                             run_names: List[str],
                                             reference_run: int) -> Dict[str, Any]:
        """Analisa reprodutibilidade no nível de veículo individual"""
        vehicle_analysis = {}
        
        if reference_run >= len(datasets):
            reference_run = 0
        
        ref_df = datasets[reference_run]
        if 'car_id' not in ref_df.columns:
            return {}
        
        ref_vehicles = set(ref_df['car_id'].unique())
        
        for i, df in enumerate(datasets):
            if i == reference_run or 'car_id' not in df.columns:
                continue
            
            run_vehicles = set(df['car_id'].unique())
            
            # Veículos em comum
            common_vehicles = ref_vehicles.intersection(run_vehicles)
            
            if common_vehicles:
                # Análise para veículos em comum
                vehicle_comparisons = []
                
                # Amostra de veículos para análise detalhada (máximo 100 para performance)
                sample_vehicles = list(common_vehicles)[:100]
                
                for vehicle_id in sample_vehicles:
                    ref_vehicle_data = ref_df[ref_df['car_id'] == vehicle_id]
                    run_vehicle_data = df[df['car_id'] == vehicle_id]
                    
                    comparison = self._compare_individual_vehicle(
                        ref_vehicle_data, run_vehicle_data, vehicle_id
                    )
                    if comparison:
                        vehicle_comparisons.append(comparison)
                
                vehicle_analysis[f'{run_names[reference_run]}_vs_{run_names[i]}'] = {
                    'common_vehicles': len(common_vehicles),
                    'total_ref_vehicles': len(ref_vehicles),
                    'total_run_vehicles': len(run_vehicles),
                    'vehicle_overlap_ratio': len(common_vehicles) / len(ref_vehicles),
                    'sample_comparisons': vehicle_comparisons[:10],  # Primeiros 10 para o relatório
                    'summary_statistics': self._summarize_vehicle_comparisons(vehicle_comparisons)
                }
        
        return vehicle_analysis
    
    def _compare_individual_vehicle(self, 
                                  ref_data: pd.DataFrame, 
                                  run_data: pd.DataFrame, 
                                  vehicle_id: str) -> Optional[Dict[str, Any]]:
        """Compara dados de um veículo específico entre execuções"""
        if ref_data.empty or run_data.empty:
            return None
        
        comparison = {
            'vehicle_id': vehicle_id,
            'ref_events': len(ref_data),
            'run_events': len(run_data),
            'event_count_diff': abs(len(ref_data) - len(run_data)),
            'metrics': {}
        }
        
        # Comparar métricas disponíveis
        numeric_columns = ['calculated_speed', 'travel_time', 'traffic_density']
        
        for col in numeric_columns:
            if col in ref_data.columns and col in run_data.columns:
                ref_values = pd.to_numeric(ref_data[col], errors='coerce').dropna()
                run_values = pd.to_numeric(run_data[col], errors='coerce').dropna()
                
                if not ref_values.empty and not run_values.empty:
                    comparison['metrics'][col] = {
                        'ref_mean': ref_values.mean(),
                        'run_mean': run_values.mean(),
                        'mean_diff': abs(ref_values.mean() - run_values.mean()),
                        'ref_std': ref_values.std(),
                        'run_std': run_values.std()
                    }
        
        return comparison
    
    def _summarize_vehicle_comparisons(self, comparisons: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Sumariza comparações de veículos individuais"""
        if not comparisons:
            return {}
        
        summary = {
            'num_vehicles_analyzed': len(comparisons),
            'event_count_differences': [c['event_count_diff'] for c in comparisons],
            'metrics_summary': {}
        }
        
        # Sumarizar diferenças por métrica
        for metric in ['calculated_speed', 'travel_time', 'traffic_density']:
            metric_diffs = []
            for comp in comparisons:
                if metric in comp['metrics']:
                    metric_diffs.append(comp['metrics'][metric]['mean_diff'])
            
            if metric_diffs:
                summary['metrics_summary'][metric] = {
                    'mean_absolute_difference': np.mean(metric_diffs),
                    'median_absolute_difference': np.median(metric_diffs),
                    'max_difference': np.max(metric_diffs),
                    'std_difference': np.std(metric_diffs)
                }
        
        return summary
    
    def _analyze_temporal_reproducibility(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Analisa reprodutibilidade dos padrões temporais"""
        temporal_analysis = {}
        
        # Determinar coluna de tempo
        time_col = 'tick' if any('tick' in df.columns for df in datasets) else 'timestamp'
        
        if not any(time_col in df.columns for df in datasets):
            return {}
        
        # Análise de fluxo horário para cada execução
        hourly_flows = []
        
        for i, df in enumerate(datasets):
            if time_col in df.columns:
                df_copy = df.copy()
                
                if time_col == 'tick':
                    df_copy[time_col] = pd.to_numeric(df_copy[time_col], errors='coerce')
                    df_copy = df_copy.dropna(subset=[time_col])
                    df_copy['hour'] = (df_copy[time_col] // 3600).astype(int)
                else:
                    df_copy[time_col] = pd.to_datetime(df_copy[time_col], errors='coerce')
                    df_copy = df_copy.dropna(subset=[time_col])
                    df_copy['hour'] = df_copy[time_col].dt.hour
                
                hourly_flow = df_copy.groupby('hour').size()
                hourly_flows.append({
                    'run': run_names[i],
                    'flow': hourly_flow.to_dict(),
                    'peak_hour': hourly_flow.idxmax() if not hourly_flow.empty else None,
                    'peak_volume': hourly_flow.max() if not hourly_flow.empty else 0
                })
        
        if hourly_flows:
            temporal_analysis['hourly_flows'] = hourly_flows
            temporal_analysis['temporal_consistency'] = self._analyze_temporal_consistency(hourly_flows)
        
        return temporal_analysis
    
    def _analyze_temporal_consistency(self, hourly_flows: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Analisa consistência dos padrões temporais"""
        consistency = {}
        
        # Extrair horários de pico
        peak_hours = [hf['peak_hour'] for hf in hourly_flows if hf['peak_hour'] is not None]
        peak_volumes = [hf['peak_volume'] for hf in hourly_flows]
        
        if peak_hours:
            consistency['peak_hour_consistency'] = {
                'peak_hours': peak_hours,
                'most_common_peak': max(set(peak_hours), key=peak_hours.count),
                'peak_hour_variance': len(set(peak_hours)),
                'peak_volume_cv': np.std(peak_volumes) / np.mean(peak_volumes) if np.mean(peak_volumes) > 0 else float('inf')
            }
        
        # Correlação entre fluxos horários
        if len(hourly_flows) >= 2:
            correlations = []
            for i in range(len(hourly_flows)):
                for j in range(i+1, len(hourly_flows)):
                    flow1 = hourly_flows[i]['flow']
                    flow2 = hourly_flows[j]['flow']
                    
                    # Alinhar horas comuns
                    common_hours = set(flow1.keys()).intersection(set(flow2.keys()))
                    if len(common_hours) > 1:
                        values1 = [flow1[h] for h in sorted(common_hours)]
                        values2 = [flow2[h] for h in sorted(common_hours)]
                        
                        correlation = np.corrcoef(values1, values2)[0, 1]
                        if not np.isnan(correlation):
                            correlations.append({
                                'run1': hourly_flows[i]['run'],
                                'run2': hourly_flows[j]['run'],
                                'correlation': correlation
                            })
            
            if correlations:
                consistency['flow_correlations'] = correlations
                consistency['mean_correlation'] = np.mean([c['correlation'] for c in correlations])
        
        return consistency
    
    def _analyze_spatial_reproducibility(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Analisa reprodutibilidade dos padrões espaciais"""
        spatial_analysis = {}
        
        if not any('link_id' in df.columns for df in datasets):
            return {}
        
        # Análise por link para cada execução
        link_analyses = []
        
        for i, df in enumerate(datasets):
            if 'link_id' in df.columns:
                link_usage = df['link_id'].value_counts()
                
                link_analyses.append({
                    'run': run_names[i],
                    'unique_links': len(link_usage),
                    'total_usage': link_usage.sum(),
                    'most_used_link': link_usage.idxmax() if not link_usage.empty else None,
                    'max_usage': link_usage.max() if not link_usage.empty else 0,
                    'link_usage_distribution': link_usage.to_dict()
                })
        
        if link_analyses:
            spatial_analysis['link_analyses'] = link_analyses
            spatial_analysis['spatial_consistency'] = self._analyze_spatial_consistency(link_analyses)
        
        return spatial_analysis
    
    def _analyze_spatial_consistency(self, link_analyses: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Analisa consistência dos padrões espaciais"""
        consistency = {}
        
        # Links mais utilizados
        most_used_links = [la['most_used_link'] for la in link_analyses if la['most_used_link'] is not None]
        unique_link_counts = [la['unique_links'] for la in link_analyses]
        
        if most_used_links:
            consistency['most_used_link_consistency'] = {
                'most_used_links': most_used_links,
                'consistent_most_used': len(set(most_used_links)) == 1,
                'unique_links_cv': np.std(unique_link_counts) / np.mean(unique_link_counts) if np.mean(unique_link_counts) > 0 else float('inf')
            }
        
        return consistency
    
    def _perform_statistical_tests(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Realiza testes estatísticos de reprodutibilidade"""
        statistical_tests = {}
        
        # Métricas para teste
        metrics_to_test = ['calculated_speed', 'travel_time', 'traffic_density']
        
        for metric in metrics_to_test:
            available_datasets = [(i, df) for i, df in enumerate(datasets) if metric in df.columns]
            
            if len(available_datasets) >= 2:
                # Extrair valores da métrica de cada execução
                metric_values = []
                for i, df in available_datasets:
                    values = pd.to_numeric(df[metric], errors='coerce').dropna()
                    if not values.empty:
                        # Amostra para performance (máximo 10000 valores)
                        sample_size = min(len(values), 10000)
                        sampled_values = values.sample(n=sample_size, random_state=42)
                        metric_values.append(sampled_values.values)
                
                if len(metric_values) >= 2:
                    tests_results = {}
                    
                    # Teste de Kolmogorov-Smirnov (comparação de distribuições)
                    try:
                        ks_statistic, ks_p_value = stats.ks_2samp(metric_values[0], metric_values[1])
                        tests_results['kolmogorov_smirnov'] = {
                            'statistic': ks_statistic,
                            'p_value': ks_p_value,
                            'distributions_similar': ks_p_value > 0.05
                        }
                    except Exception as e:
                        self.logger.warning(f"Erro no teste KS para {metric}: {e}")
                    
                    # Teste de Mann-Whitney U (comparação de medianas)
                    try:
                        mw_statistic, mw_p_value = stats.mannwhitneyu(
                            metric_values[0], metric_values[1], alternative='two-sided'
                        )
                        tests_results['mann_whitney'] = {
                            'statistic': mw_statistic,
                            'p_value': mw_p_value,
                            'medians_similar': mw_p_value > 0.05
                        }
                    except Exception as e:
                        self.logger.warning(f"Erro no teste Mann-Whitney para {metric}: {e}")
                    
                    # ANOVA (se mais de 2 execuções)
                    if len(metric_values) > 2:
                        try:
                            f_statistic, anova_p_value = stats.f_oneway(*metric_values)
                            tests_results['anova'] = {
                                'f_statistic': f_statistic,
                                'p_value': anova_p_value,
                                'means_similar': anova_p_value > 0.05
                            }
                        except Exception as e:
                            self.logger.warning(f"Erro no ANOVA para {metric}: {e}")
                    
                    if tests_results:
                        statistical_tests[metric] = tests_results
        
        return statistical_tests
    
    def _calculate_similarity_scores(self, 
                                   datasets: List[pd.DataFrame], 
                                   run_names: List[str],
                                   reference_run: int) -> Dict[str, Any]:
        """Calcula scores de similaridade entre execuções"""
        similarity_scores = {}
        
        if reference_run >= len(datasets):
            reference_run = 0
        
        ref_df = datasets[reference_run]
        
        for i, df in enumerate(datasets):
            if i == reference_run:
                continue
            
            scores = {}
            
            # Métricas para comparação
            metrics_to_compare = ['calculated_speed', 'travel_time', 'traffic_density']
            
            for metric in metrics_to_compare:
                if metric in ref_df.columns and metric in df.columns:
                    ref_values = pd.to_numeric(ref_df[metric], errors='coerce').dropna()
                    run_values = pd.to_numeric(df[metric], errors='coerce').dropna()
                    
                    if not ref_values.empty and not run_values.empty:
                        # Amostra para performance
                        sample_size = min(len(ref_values), len(run_values), 10000)
                        ref_sample = ref_values.sample(n=sample_size, random_state=42)
                        run_sample = run_values.sample(n=sample_size, random_state=42)
                        
                        # Métricas de similaridade
                        try:
                            # Correlação de Pearson
                            correlation = np.corrcoef(ref_sample, run_sample)[0, 1]
                            if np.isnan(correlation):
                                correlation = 0.0
                            
                            # Erro médio absoluto normalizado
                            mae = mean_absolute_error(ref_sample, run_sample)
                            mae_normalized = mae / (ref_sample.mean() + 1e-10)
                            
                            # Similaridade baseada em distribuição (1 - KS statistic)
                            ks_stat, _ = stats.ks_2samp(ref_sample, run_sample)
                            distribution_similarity = 1 - ks_stat
                            
                            scores[metric] = {
                                'correlation': correlation,
                                'mae_normalized': mae_normalized,
                                'distribution_similarity': distribution_similarity,
                                'combined_score': (correlation + distribution_similarity - mae_normalized) / 2
                            }
                        except Exception as e:
                            self.logger.warning(f"Erro calculando similaridade para {metric}: {e}")
            
            if scores:
                # Score geral
                individual_scores = [s['combined_score'] for s in scores.values()]
                overall_score = np.mean(individual_scores) if individual_scores else 0.0
                
                similarity_scores[f'{run_names[reference_run]}_vs_{run_names[i]}'] = {
                    'individual_metrics': scores,
                    'overall_similarity_score': overall_score
                }
        
        return similarity_scores
    
    def _analyze_variability(self, datasets: List[pd.DataFrame], run_names: List[str]) -> Dict[str, Any]:
        """Analisa variabilidade entre execuções"""
        variability_analysis = {}
        
        metrics_to_analyze = ['calculated_speed', 'travel_time', 'traffic_density']
        
        for metric in metrics_to_analyze:
            available_data = []
            
            for i, df in enumerate(datasets):
                if metric in df.columns:
                    values = pd.to_numeric(df[metric], errors='coerce').dropna()
                    if not values.empty:
                        available_data.append({
                            'run': run_names[i],
                            'values': values,
                            'mean': values.mean(),
                            'std': values.std(),
                            'median': values.median()
                        })
            
            if len(available_data) >= 2:
                # Calcular variabilidade entre execuções
                means = [d['mean'] for d in available_data]
                stds = [d['std'] for d in available_data]
                medians = [d['median'] for d in available_data]
                
                variability_analysis[metric] = {
                    'runs_data': available_data,
                    'cross_run_variability': {
                        'mean_cv': np.std(means) / np.mean(means) if np.mean(means) > 0 else float('inf'),
                        'std_cv': np.std(stds) / np.mean(stds) if np.mean(stds) > 0 else float('inf'),
                        'median_cv': np.std(medians) / np.mean(medians) if np.mean(medians) > 0 else float('inf'),
                        'mean_range_normalized': (max(means) - min(means)) / np.mean(means) if np.mean(means) > 0 else float('inf')
                    }
                }
        
        return variability_analysis
    
    def generate_reproducibility_report(self, analysis: Dict[str, Any]) -> str:
        """Gera relatório de reprodutibilidade"""
        report_file = self.output_dir / "reproducibility_report.json"
        
        with open(report_file, 'w', encoding='utf-8') as f:
            json.dump(analysis, f, indent=2, ensure_ascii=False, default=str)
        
        self.logger.info(f"📄 Relatório de reprodutibilidade salvo em: {report_file}")
        return str(report_file)
    
    def create_reproducibility_visualizations(self, 
                                            datasets: List[pd.DataFrame],
                                            run_names: List[str],
                                            analysis: Dict[str, Any]) -> List[str]:
        """Cria visualizações da análise de reprodutibilidade em PNG e PDF separados"""
        generated_files = []
        
        try:
            # 1. Comparação de métricas básicas (agrupado)
            basic_viz = self._plot_basic_metrics_comparison(datasets, run_names, analysis)
            if basic_viz:
                generated_files.extend(basic_viz)
            
            # 2. Análise temporal (agrupado)
            temporal_viz = self._plot_temporal_reproducibility(datasets, run_names, analysis)
            if temporal_viz:
                generated_files.extend(temporal_viz)
            
            # 3. Scores de similaridade (agrupado)
            similarity_viz = self._plot_similarity_scores(analysis)
            if similarity_viz:
                generated_files.extend(similarity_viz)
            
            # 4. Dashboard de reprodutibilidade (agrupado)
            dashboard_viz = self._create_reproducibility_dashboard(datasets, run_names, analysis)
            if dashboard_viz:
                generated_files.extend(dashboard_viz)
            
            # 5. NOVOS: Gráficos individuais para cada métrica
            individual_viz = self._generate_individual_plots(datasets, run_names, analysis)
            if individual_viz:
                generated_files.extend(individual_viz)
            
            # 6. PDF consolidado com todos os gráficos
            consolidated_pdf = self._create_consolidated_pdf_report(datasets, run_names, analysis)
            if consolidated_pdf:
                generated_files.append(consolidated_pdf)
            
        except Exception as e:
            self.logger.error(f"Erro ao gerar visualizações: {e}")
        
        return generated_files
    
    def _plot_basic_metrics_comparison(self, 
                                     datasets: List[pd.DataFrame],
                                     run_names: List[str],
                                     analysis: Dict[str, Any]) -> Optional[List[str]]:
        """Gera gráfico de comparação de métricas básicas em PNG e PDF"""
        basic_metrics = analysis.get('basic_metrics', {})
        
        if not basic_metrics:
            return None
        
        fig, axes = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Comparação de Métricas Básicas entre Execuções', fontsize=16, fontweight='bold')
        
        metrics_plotted = 0
        
        for metric_key, metric_data in basic_metrics.items():
            if metrics_plotted >= 4:
                break
            
            ax = axes[metrics_plotted // 2, metrics_plotted % 2]
            
            runs = [stat['run'] for stat in metric_data['run_statistics']]
            means = [stat['mean'] for stat in metric_data['run_statistics']]
            stds = [stat['std'] for stat in metric_data['run_statistics']]
            
            # Gráfico de barras com erro padrão
            bars = ax.bar(runs, means, yerr=stds, capsize=5, alpha=0.7)
            ax.set_title(f"{metric_data['metric_name']}")
            ax.set_ylabel('Valor Médio')
            ax.tick_params(axis='x', rotation=45)
            
            # Adicionar valores nas barras
            for bar, mean_val in zip(bars, means):
                height = bar.get_height()
                ax.text(bar.get_x() + bar.get_width()/2., height + max(stds) * 0.01,
                       f'{mean_val:.2f}', ha='center', va='bottom', fontsize=9)
            
            # Adicionar CV no título
            cv = metric_data['cross_run_variability']['mean_cv']
            if cv != float('inf'):
                ax.set_title(f"{metric_data['metric_name']} (CV: {cv:.3f})")
            
            metrics_plotted += 1
        
        # Remover eixos não utilizados
        for i in range(metrics_plotted, 4):
            axes[i // 2, i % 2].axis('off')
        
        plt.tight_layout()
        
        # Salvar em PNG
        png_file = self.output_dir / 'basic_metrics_comparison.png'
        plt.savefig(png_file, dpi=300, bbox_inches='tight')
        
        # Salvar em PDF
        pdf_file = self.output_dir / 'basic_metrics_comparison.pdf'
        plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
        
        plt.close()
        
        self.logger.info(f"📊 Gráfico de métricas básicas salvo: {png_file} e {pdf_file}")
        return [str(png_file), str(pdf_file)]
        
        return str(viz_file)
    
    def _plot_temporal_reproducibility(self, 
                                     datasets: List[pd.DataFrame],
                                     run_names: List[str],
                                     analysis: Dict[str, Any]) -> Optional[List[str]]:
        """Gera gráfico de reprodutibilidade temporal em PNG e PDF"""
        temporal_data = analysis.get('temporal_patterns', {})
        
        if not temporal_data.get('hourly_flows'):
            return None
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Análise de Reprodutibilidade Temporal', fontsize=16, fontweight='bold')
        
        hourly_flows = temporal_data['hourly_flows']
        
        # 1. Fluxo horário para todas as execuções
        for hf in hourly_flows:
            flow_dict = hf['flow']
            hours = sorted(flow_dict.keys())
            flows = [flow_dict[h] for h in hours]
            ax1.plot(hours, flows, marker='o', label=hf['run'], alpha=0.7)
        
        ax1.set_xlabel('Hora')
        ax1.set_ylabel('Número de Eventos')
        ax1.set_title('Fluxo Horário por Execução')
        ax1.legend()
        ax1.grid(True, alpha=0.3)
        
        # 2. Comparação de horários de pico
        peak_hours = [hf['peak_hour'] for hf in hourly_flows if hf['peak_hour'] is not None]
        peak_volumes = [hf['peak_volume'] for hf in hourly_flows]
        
        if peak_hours:
            runs_with_peaks = [hf['run'] for hf in hourly_flows if hf['peak_hour'] is not None]
            
            bars = ax2.bar(runs_with_peaks, peak_hours, alpha=0.7, color='orange')
            ax2.set_ylabel('Hora de Pico')
            ax2.set_title('Horário de Pico por Execução')
            ax2.tick_params(axis='x', rotation=45)
            
            # Adicionar valores
            for bar, hour in zip(bars, peak_hours):
                height = bar.get_height()
                ax2.text(bar.get_x() + bar.get_width()/2., height + 0.1,
                        f'{hour}h', ha='center', va='bottom')
        
        # 3. Volume no pico
        runs_names_vol = [hf['run'] for hf in hourly_flows]
        bars3 = ax3.bar(runs_names_vol, peak_volumes, alpha=0.7, color='green')
        ax3.set_ylabel('Volume no Pico')
        ax3.set_title('Volume no Horário de Pico')
        ax3.tick_params(axis='x', rotation=45)
        
        # Adicionar valores
        for bar, vol in zip(bars3, peak_volumes):
            height = bar.get_height()
            ax3.text(bar.get_x() + bar.get_width()/2., height + max(peak_volumes) * 0.01,
                    f'{vol}', ha='center', va='bottom', fontsize=9)
        
        # 4. Correlações entre execuções
        temporal_consistency = temporal_data.get('temporal_consistency', {})
        correlations = temporal_consistency.get('flow_correlations', [])
        
        if correlations:
            correlation_labels = [f"{c['run1']} vs {c['run2']}" for c in correlations]
            correlation_values = [c['correlation'] for c in correlations]
            
            bars4 = ax4.bar(range(len(correlation_labels)), correlation_values, alpha=0.7, color='purple')
            ax4.set_xticks(range(len(correlation_labels)))
            ax4.set_xticklabels(correlation_labels, rotation=45, ha='right')
            ax4.set_ylabel('Correlação')
            ax4.set_title('Correlação dos Padrões Temporais')
            ax4.set_ylim(0, 1)
            
            # Linha de referência
            ax4.axhline(y=0.8, color='red', linestyle='--', alpha=0.7, label='Boa correlação (0.8)')
            ax4.legend()
            
            # Adicionar valores
            for bar, corr in zip(bars4, correlation_values):
                height = bar.get_height()
                ax4.text(bar.get_x() + bar.get_width()/2., height + 0.02,
                        f'{corr:.3f}', ha='center', va='bottom', fontsize=9)
        else:
            ax4.text(0.5, 0.5, 'Dados insuficientes\npara correlações', 
                    ha='center', va='center', transform=ax4.transAxes, fontsize=12)
            ax4.set_title('Correlação dos Padrões Temporais')
        
        plt.tight_layout()
        
        # Salvar em PNG
        png_file = self.output_dir / 'temporal_reproducibility.png'
        plt.savefig(png_file, dpi=300, bbox_inches='tight')
        
        # Salvar em PDF
        pdf_file = self.output_dir / 'temporal_reproducibility.pdf'
        plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
        
        plt.close()
        
        self.logger.info(f"📊 Gráfico temporal salvo: {png_file} e {pdf_file}")
        return [str(png_file), str(pdf_file)]
    
    def _plot_similarity_scores(self, analysis: Dict[str, Any]) -> Optional[List[str]]:
        """Gera gráfico dos scores de similaridade em PNG e PDF"""
        similarity_data = analysis.get('similarity_scores', {})
        
        if not similarity_data:
            return None
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Scores de Similaridade entre Execuções', fontsize=16, fontweight='bold')
        
        # Extrair dados para visualização
        comparisons = list(similarity_data.keys())
        overall_scores = [data['overall_similarity_score'] for data in similarity_data.values()]
        
        # 1. Scores gerais de similaridade
        bars1 = ax1.bar(range(len(comparisons)), overall_scores, alpha=0.7, color='skyblue')
        ax1.set_xticks(range(len(comparisons)))
        ax1.set_xticklabels(comparisons, rotation=45, ha='right')
        ax1.set_ylabel('Score de Similaridade')
        ax1.set_title('Score Geral de Similaridade')
        ax1.set_ylim(0, 1)
        
        # Linha de referência para alta similaridade
        ax1.axhline(y=0.8, color='green', linestyle='--', alpha=0.7, label='Alta similaridade (0.8)')
        ax1.axhline(y=0.6, color='orange', linestyle='--', alpha=0.7, label='Similaridade moderada (0.6)')
        ax1.legend()
        
        # Adicionar valores
        for bar, score in zip(bars1, overall_scores):
            height = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width()/2., height + 0.02,
                    f'{score:.3f}', ha='center', va='bottom', fontsize=9)
        
        # 2-4. Scores por métrica individual
        metrics = ['calculated_speed', 'travel_time', 'traffic_density']
        metric_titles = ['Velocidade', 'Tempo de Viagem', 'Densidade']
        axes_metrics = [ax2, ax3, ax4]
        
        for i, (metric, title, ax) in enumerate(zip(metrics, metric_titles, axes_metrics)):
            metric_scores = []
            valid_comparisons = []
            
            for comp, data in similarity_data.items():
                if metric in data['individual_metrics']:
                    metric_scores.append(data['individual_metrics'][metric]['combined_score'])
                    valid_comparisons.append(comp)
            
            if metric_scores:
                bars = ax.bar(range(len(valid_comparisons)), metric_scores, alpha=0.7)
                ax.set_xticks(range(len(valid_comparisons)))
                ax.set_xticklabels(valid_comparisons, rotation=45, ha='right')
                ax.set_ylabel('Score de Similaridade')
                ax.set_title(f'Similaridade - {title}')
                ax.set_ylim(0, 1)
                
                # Adicionar valores
                for bar, score in zip(bars, metric_scores):
                    height = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width()/2., height + 0.02,
                           f'{score:.3f}', ha='center', va='bottom', fontsize=8)
            else:
                ax.text(0.5, 0.5, f'Dados insuficientes\npara {title}', 
                       ha='center', va='center', transform=ax.transAxes, fontsize=12)
                ax.set_title(f'Similaridade - {title}')
        
        plt.tight_layout()
        
        # Salvar em PNG
        png_file = self.output_dir / 'similarity_scores.png'
        plt.savefig(png_file, dpi=300, bbox_inches='tight')
        
        # Salvar em PDF
        pdf_file = self.output_dir / 'similarity_scores.pdf'
        plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
        
        plt.close()
        
        self.logger.info(f"📊 Gráfico de similaridade salvo: {png_file} e {pdf_file}")
        return [str(png_file), str(pdf_file)]
    
    def _create_reproducibility_dashboard(self, 
                                        datasets: List[pd.DataFrame],
                                        run_names: List[str],
                                        analysis: Dict[str, Any]) -> Optional[List[str]]:
        """Cria dashboard de reprodutibilidade em PNG e PDF"""
        fig = plt.figure(figsize=(20, 12))
        gs = fig.add_gridspec(3, 4, hspace=0.3, wspace=0.3)
        
        fig.suptitle('Dashboard de Reprodutibilidade', fontsize=20, fontweight='bold')
        
        # 1. Resumo geral (canto superior esquerdo)
        ax1 = fig.add_subplot(gs[0, :2])
        self._add_summary_to_dashboard(ax1, analysis)
        
        # 2. Consistência de dados básicos
        ax2 = fig.add_subplot(gs[0, 2:])
        self._add_data_consistency_to_dashboard(ax2, analysis)
        
        # 3. Variabilidade por métrica
        ax3 = fig.add_subplot(gs[1, :2])
        self._add_variability_to_dashboard(ax3, analysis)
        
        # 4. Testes estatísticos
        ax4 = fig.add_subplot(gs[1, 2:])
        self._add_statistical_tests_to_dashboard(ax4, analysis)
        
        # 5. Scores de similaridade (parte inferior)
        ax5 = fig.add_subplot(gs[2, :])
        self._add_similarity_overview_to_dashboard(ax5, analysis)
        
        # Salvar em PNG
        png_file = self.output_dir / 'reproducibility_dashboard.png'
        plt.savefig(png_file, dpi=300, bbox_inches='tight')
        
        # Salvar em PDF
        pdf_file = self.output_dir / 'reproducibility_dashboard.pdf'
        plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
        
        plt.close()
        
        self.logger.info(f"📊 Dashboard salvo: {png_file} e {pdf_file}")
        return [str(png_file), str(pdf_file)]
    
    def _generate_individual_plots(self, 
                                 datasets: List[pd.DataFrame],
                                 run_names: List[str],
                                 analysis: Dict[str, Any]) -> List[str]:
        """Gera gráficos individuais para cada métrica"""
        generated_files = []
        
        try:
            # 1. Gráficos individuais de métricas básicas
            basic_individual = self._plot_individual_basic_metrics(datasets, run_names, analysis)
            if basic_individual:
                generated_files.extend(basic_individual)
            
            # 2. Gráficos individuais temporais
            temporal_individual = self._plot_individual_temporal_charts(datasets, run_names, analysis)
            if temporal_individual:
                generated_files.extend(temporal_individual)
            
            # 3. Gráficos individuais de similaridade
            similarity_individual = self._plot_individual_similarity_charts(analysis)
            if similarity_individual:
                generated_files.extend(similarity_individual)
            
            # 4. Gráficos de distribuições individuais
            distribution_individual = self._plot_individual_distributions(datasets, run_names)
            if distribution_individual:
                generated_files.extend(distribution_individual)
            
        except Exception as e:
            self.logger.error(f"Erro ao gerar gráficos individuais: {e}")
        
        return generated_files
    
    def _plot_individual_basic_metrics(self, 
                                     datasets: List[pd.DataFrame],
                                     run_names: List[str],
                                     analysis: Dict[str, Any]) -> List[str]:
        """Gera gráficos individuais para cada métrica básica"""
        generated_files = []
        basic_metrics = analysis.get('basic_metrics', {})
        
        if not basic_metrics:
            return generated_files
        
        for metric_key, metric_data in basic_metrics.items():
            try:
                # Criar gráfico individual para esta métrica
                fig, ax = plt.subplots(figsize=(10, 8))
                
                runs = [stat['run'] for stat in metric_data['run_statistics']]
                means = [stat['mean'] for stat in metric_data['run_statistics']]
                stds = [stat['std'] for stat in metric_data['run_statistics']]
                
                # Gráfico de barras com erro padrão
                bars = ax.bar(runs, means, yerr=stds, capsize=5, alpha=0.7, 
                             color=plt.cm.Set3(len(generated_files) % 12))
                
                # Configurar título e labels
                metric_name = metric_data['metric_name']
                cv = metric_data['cross_run_variability']['mean_cv']
                if cv != float('inf'):
                    title = f"{metric_name} (CV: {cv:.4f})"
                else:
                    title = metric_name
                
                ax.set_title(title, fontsize=14, fontweight='bold')
                ax.set_ylabel('Valor Médio', fontsize=12)
                ax.set_xlabel('Execução', fontsize=12)
                ax.tick_params(axis='x', rotation=45)
                ax.grid(True, alpha=0.3)
                
                # Adicionar valores nas barras
                for bar, mean_val, std_val in zip(bars, means, stds):
                    height = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width()/2., height + std_val * 0.1,
                           f'{mean_val:.2f}', ha='center', va='bottom', fontsize=10)
                
                # Adicionar informações estatísticas
                stats_text = f"Média: {np.mean(means):.2f}\nDesvio: {np.std(means):.4f}\nCV: {cv:.4f}"
                ax.text(0.02, 0.98, stats_text, transform=ax.transAxes, 
                       verticalalignment='top', bbox=dict(boxstyle='round', facecolor='lightgray', alpha=0.8))
                
                plt.tight_layout()
                
                # Salvar PNG
                safe_name = metric_key.replace(' ', '_').replace('/', '_').lower()
                png_file = self.output_dir / f'metric_{safe_name}.png'
                plt.savefig(png_file, dpi=300, bbox_inches='tight')
                
                # Salvar PDF
                pdf_file = self.output_dir / f'metric_{safe_name}.pdf'
                plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
                
                plt.close()
                
                generated_files.extend([str(png_file), str(pdf_file)])
                self.logger.info(f"📊 Métrica individual salva: {metric_name} -> {safe_name}")
                
            except Exception as e:
                self.logger.error(f"Erro ao gerar gráfico para métrica {metric_key}: {e}")
        
        return generated_files
    
    def _plot_individual_temporal_charts(self, 
                                        datasets: List[pd.DataFrame],
                                        run_names: List[str],
                                        analysis: Dict[str, Any]) -> List[str]:
        """Gera gráficos temporais individuais"""
        generated_files = []
        temporal_data = analysis.get('temporal_patterns', {})
        
        if not temporal_data.get('hourly_flows'):
            return generated_files
        
        try:
            # 1. Gráfico individual: Fluxo horário
            fig, ax = plt.subplots(figsize=(12, 8))
            
            hourly_flows = temporal_data['hourly_flows']
            for i, hf in enumerate(hourly_flows):
                flow_dict = hf['flow']
                hours = sorted(flow_dict.keys())
                flows = [flow_dict[h] for h in hours]
                ax.plot(hours, flows, marker='o', label=hf['run'], alpha=0.8, linewidth=2)
            
            ax.set_xlabel('Hora do Dia', fontsize=12)
            ax.set_ylabel('Número de Eventos', fontsize=12)
            ax.set_title('Fluxo Horário por Execução', fontsize=14, fontweight='bold')
            ax.legend()
            ax.grid(True, alpha=0.3)
            
            plt.tight_layout()
            
            # Salvar PNG e PDF
            png_file = self.output_dir / 'temporal_hourly_flow.png'
            pdf_file = self.output_dir / 'temporal_hourly_flow.pdf'
            plt.savefig(png_file, dpi=300, bbox_inches='tight')
            plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
            plt.close()
            
            generated_files.extend([str(png_file), str(pdf_file)])
            self.logger.info(f"📊 Gráfico temporal individual salvo: fluxo horário")
            
            # 2. Gráfico individual: Variabilidade horária
            if len(hourly_flows) > 1:
                fig, ax = plt.subplots(figsize=(12, 8))
                
                # Calcular variabilidade por hora
                all_hours = set()
                for hf in hourly_flows:
                    all_hours.update(hf['flow'].keys())
                
                hours_sorted = sorted(all_hours)
                hourly_cvs = []
                
                for hour in hours_sorted:
                    flows_hour = []
                    for hf in hourly_flows:
                        if hour in hf['flow']:
                            flows_hour.append(hf['flow'][hour])
                    
                    if len(flows_hour) > 1:
                        cv = np.std(flows_hour) / np.mean(flows_hour) if np.mean(flows_hour) > 0 else 0
                        hourly_cvs.append(cv)
                    else:
                        hourly_cvs.append(0)
                
                ax.bar(hours_sorted, hourly_cvs, alpha=0.7, color='orange')
                ax.set_xlabel('Hora do Dia', fontsize=12)
                ax.set_ylabel('Coeficiente de Variação', fontsize=12)
                ax.set_title('Variabilidade Horária entre Execuções', fontsize=14, fontweight='bold')
                ax.grid(True, alpha=0.3)
                
                # Linha de referência
                ax.axhline(y=0.1, color='red', linestyle='--', alpha=0.7, label='CV = 0.1 (Limite)')
                ax.legend()
                
                plt.tight_layout()
                
                png_file = self.output_dir / 'temporal_hourly_variability.png'
                pdf_file = self.output_dir / 'temporal_hourly_variability.pdf'
                plt.savefig(png_file, dpi=300, bbox_inches='tight')
                plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
                plt.close()
                
                generated_files.extend([str(png_file), str(pdf_file)])
                self.logger.info(f"📊 Gráfico temporal individual salvo: variabilidade horária")
            
        except Exception as e:
            self.logger.error(f"Erro ao gerar gráficos temporais individuais: {e}")
        
        return generated_files
    
    def _plot_individual_similarity_charts(self, analysis: Dict[str, Any]) -> List[str]:
        """Gera gráficos individuais de similaridade"""
        generated_files = []
        similarity_data = analysis.get('similarity_scores', {})
        
        if not similarity_data:
            return generated_files
        
        try:
            # Gráfico individual: Scores gerais de similaridade
            fig, ax = plt.subplots(figsize=(10, 8))
            
            comparisons = list(similarity_data.keys())
            overall_scores = [data['overall_similarity_score'] for data in similarity_data.values()]
            
            bars = ax.bar(range(len(comparisons)), overall_scores, alpha=0.7, color='skyblue')
            ax.set_xticks(range(len(comparisons)))
            ax.set_xticklabels(comparisons, rotation=45, ha='right')
            ax.set_ylabel('Score de Similaridade', fontsize=12)
            ax.set_title('Scores Gerais de Similaridade', fontsize=14, fontweight='bold')
            ax.set_ylim(0, 1)
            ax.grid(True, alpha=0.3)
            
            # Linhas de referência
            ax.axhline(y=0.8, color='green', linestyle='--', alpha=0.7, label='Alta similaridade (0.8)')
            ax.axhline(y=0.6, color='orange', linestyle='--', alpha=0.7, label='Similaridade moderada (0.6)')
            ax.axhline(y=0.4, color='red', linestyle='--', alpha=0.7, label='Baixa similaridade (0.4)')
            ax.legend()
            
            # Adicionar valores
            for bar, score in zip(bars, overall_scores):
                height = bar.get_height()
                ax.text(bar.get_x() + bar.get_width()/2., height + 0.02,
                       f'{score:.3f}', ha='center', va='bottom', fontsize=10)
            
            plt.tight_layout()
            
            png_file = self.output_dir / 'similarity_overall_scores.png'
            pdf_file = self.output_dir / 'similarity_overall_scores.pdf'
            plt.savefig(png_file, dpi=300, bbox_inches='tight')
            plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
            plt.close()
            
            generated_files.extend([str(png_file), str(pdf_file)])
            self.logger.info(f"📊 Gráfico de similaridade individual salvo: scores gerais")
            
        except Exception as e:
            self.logger.error(f"Erro ao gerar gráficos de similaridade individuais: {e}")
        
        return generated_files
    
    def _plot_individual_distributions(self, 
                                     datasets: List[pd.DataFrame],
                                     run_names: List[str]) -> List[str]:
        """Gera gráficos individuais de distribuições"""
        generated_files = []
        
        # Métricas para análise de distribuição
        numeric_columns = ['calculated_speed', 'travel_time', 'traffic_density', 'distance']
        
        for column in numeric_columns:
            # Verificar se a coluna existe em todos os datasets
            if not all(column in df.columns for df in datasets):
                continue
                
            try:
                fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6))
                fig.suptitle(f'Distribuição de {column.replace("_", " ").title()}', fontsize=14, fontweight='bold')
                
                # Histograma
                for i, (df, name) in enumerate(zip(datasets, run_names)):
                    if column in df.columns and not df[column].isna().all():
                        ax1.hist(df[column].dropna(), bins=50, alpha=0.6, label=name, density=True)
                
                ax1.set_xlabel(column.replace('_', ' ').title(), fontsize=12)
                ax1.set_ylabel('Densidade', fontsize=12)
                ax1.set_title('Histograma', fontsize=12)
                ax1.legend()
                ax1.grid(True, alpha=0.3)
                
                # Box plot
                data_for_boxplot = []
                labels_for_boxplot = []
                
                for df, name in zip(datasets, run_names):
                    if column in df.columns and not df[column].isna().all():
                        data_for_boxplot.append(df[column].dropna().values)
                        labels_for_boxplot.append(name)
                
                if data_for_boxplot:
                    ax2.boxplot(data_for_boxplot, labels=labels_for_boxplot)
                    ax2.set_ylabel(column.replace('_', ' ').title(), fontsize=12)
                    ax2.set_title('Box Plot', fontsize=12)
                    ax2.tick_params(axis='x', rotation=45)
                    ax2.grid(True, alpha=0.3)
                
                plt.tight_layout()
                
                # Salvar PNG e PDF
                safe_name = column.replace(' ', '_').lower()
                png_file = self.output_dir / f'distribution_{safe_name}.png'
                pdf_file = self.output_dir / f'distribution_{safe_name}.pdf'
                plt.savefig(png_file, dpi=300, bbox_inches='tight')
                plt.savefig(pdf_file, dpi=300, bbox_inches='tight')
                plt.close()
                
                generated_files.extend([str(png_file), str(pdf_file)])
                self.logger.info(f"📊 Gráfico de distribuição individual salvo: {column}")
                
            except Exception as e:
                self.logger.error(f"Erro ao gerar gráfico de distribuição para {column}: {e}")
        
        return generated_files

    def _add_summary_to_dashboard(self, ax, analysis):
        """Adiciona resumo ao dashboard"""
        summary = analysis.get('summary', {})
        
        summary_text = [
            "📊 RESUMO DE REPRODUTIBILIDADE",
            "",
            f"🔄 Número de Execuções: {summary.get('num_runs', 0)}",
            f"📋 Execuções: {', '.join(summary.get('run_names', []))}",
            "",
            "📈 CONSISTÊNCIA DE DADOS:",
        ]
        
        data_consistency = summary.get('data_consistency', {})
        if data_consistency:
            event_cv = data_consistency.get('event_count_cv', float('inf'))
            vehicle_cv = data_consistency.get('vehicle_count_cv', float('inf'))
            
            if event_cv != float('inf'):
                summary_text.append(f"  • Eventos CV: {event_cv:.3f}")
            if vehicle_cv != float('inf'):
                summary_text.append(f"  • Veículos CV: {vehicle_cv:.3f}")
        
        ax.text(0.05, 0.95, '\n'.join(summary_text), transform=ax.transAxes,
                fontsize=11, verticalalignment='top', fontfamily='monospace',
                bbox=dict(boxstyle='round,pad=0.5', facecolor='lightblue', alpha=0.8))
        ax.set_title('Resumo Geral', fontweight='bold')
        ax.axis('off')
    
    def _add_data_consistency_to_dashboard(self, ax, analysis):
        """Adiciona consistência de dados ao dashboard"""
        summary = analysis.get('summary', {})
        data_consistency = summary.get('data_consistency', {})
        
        if data_consistency:
            event_counts = data_consistency.get('event_counts', [])
            vehicle_counts = data_consistency.get('vehicle_counts', [])
            
            if event_counts and vehicle_counts:
                run_names = summary.get('run_names', [f"Run {i+1}" for i in range(len(event_counts))])
                
                x = np.arange(len(run_names))
                width = 0.35
                
                # Normalizar para comparação visual
                max_events = max(event_counts) if event_counts else 1
                max_vehicles = max(vehicle_counts) if vehicle_counts else 1
                
                norm_events = [e/max_events for e in event_counts]
                norm_vehicles = [v/max_vehicles for v in vehicle_counts]
                
                bars1 = ax.bar(x - width/2, norm_events, width, label='Eventos (norm)', alpha=0.7)
                bars2 = ax.bar(x + width/2, norm_vehicles, width, label='Veículos (norm)', alpha=0.7)
                
                ax.set_xlabel('Execuções')
                ax.set_ylabel('Valores Normalizados')
                ax.set_title('Consistência de Contagens')
                ax.set_xticks(x)
                ax.set_xticklabels(run_names, rotation=45, ha='right')
                ax.legend()
                ax.grid(True, alpha=0.3)
        else:
            ax.text(0.5, 0.5, 'Dados de consistência\nnão disponíveis', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
        
        ax.set_title('Consistência de Dados', fontweight='bold')
    
    def _add_variability_to_dashboard(self, ax, analysis):
        """Adiciona análise de variabilidade ao dashboard"""
        variability = analysis.get('variability_analysis', {})
        
        if variability:
            metrics = []
            cvs = []
            
            for metric, data in variability.items():
                cross_run_var = data.get('cross_run_variability', {})
                mean_cv = cross_run_var.get('mean_cv', float('inf'))
                
                if mean_cv != float('inf'):
                    metrics.append(metric.replace('_', ' ').title())
                    cvs.append(mean_cv)
            
            if metrics and cvs:
                bars = ax.bar(metrics, cvs, alpha=0.7, color=['red' if cv > 0.1 else 'orange' if cv > 0.05 else 'green' for cv in cvs])
                ax.set_ylabel('Coeficiente de Variação')
                ax.set_title('Variabilidade entre Execuções')
                ax.tick_params(axis='x', rotation=45)
                
                # Linhas de referência
                ax.axhline(y=0.05, color='green', linestyle='--', alpha=0.7, label='Baixa (0.05)')
                ax.axhline(y=0.1, color='orange', linestyle='--', alpha=0.7, label='Moderada (0.1)')
                ax.legend()
                
                # Adicionar valores
                for bar, cv in zip(bars, cvs):
                    height = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width()/2., height + max(cvs) * 0.02,
                           f'{cv:.3f}', ha='center', va='bottom', fontsize=9)
        else:
            ax.text(0.5, 0.5, 'Dados de variabilidade\nnão disponíveis', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
        
        ax.set_title('Variabilidade entre Execuções', fontweight='bold')
    
    def _add_statistical_tests_to_dashboard(self, ax, analysis):
        """Adiciona resultados de testes estatísticos ao dashboard"""
        statistical_tests = analysis.get('statistical_tests', {})
        
        if statistical_tests:
            test_results = []
            
            for metric, tests in statistical_tests.items():
                for test_name, test_data in tests.items():
                    p_value = test_data.get('p_value', 1.0)
                    significant = p_value <= 0.05
                    
                    test_results.append({
                        'metric': metric.replace('_', ' ').title(),
                        'test': test_name.replace('_', ' ').title(),
                        'p_value': p_value,
                        'significant': significant
                    })
            
            if test_results:
                # Criar matriz de p-values
                metrics = sorted(set([r['metric'] for r in test_results]))
                tests = sorted(set([r['test'] for r in test_results]))
                
                matrix = np.full((len(tests), len(metrics)), np.nan)
                
                for result in test_results:
                    i = tests.index(result['test'])
                    j = metrics.index(result['metric'])
                    matrix[i, j] = result['p_value']
                
                # Heatmap
                im = ax.imshow(matrix, cmap='RdYlGn', vmin=0, vmax=0.1, aspect='auto')
                
                ax.set_xticks(range(len(metrics)))
                ax.set_yticks(range(len(tests)))
                ax.set_xticklabels(metrics, rotation=45, ha='right')
                ax.set_yticklabels(tests)
                
                # Adicionar valores
                for i in range(len(tests)):
                    for j in range(len(metrics)):
                        if not np.isnan(matrix[i, j]):
                            text = f'{matrix[i, j]:.3f}'
                            color = 'white' if matrix[i, j] < 0.05 else 'black'
                            ax.text(j, i, text, ha='center', va='center', color=color, fontsize=8)
                
                # Colorbar
                cbar = plt.colorbar(im, ax=ax, shrink=0.6)
                cbar.set_label('p-value')
        else:
            ax.text(0.5, 0.5, 'Testes estatísticos\nnão disponíveis', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
        
        ax.set_title('Testes Estatísticos (p-values)', fontweight='bold')
    
    def _add_similarity_overview_to_dashboard(self, ax, analysis):
        """Adiciona overview de similaridade ao dashboard"""
        similarity_data = analysis.get('similarity_scores', {})
        
        if similarity_data:
            comparisons = list(similarity_data.keys())
            overall_scores = [data['overall_similarity_score'] for data in similarity_data.values()]
            
            # Gráfico de barras horizontais
            bars = ax.barh(range(len(comparisons)), overall_scores, alpha=0.7)
            
            # Colorir barras baseado no score
            for bar, score in zip(bars, overall_scores):
                if score >= 0.8:
                    bar.set_color('green')
                elif score >= 0.6:
                    bar.set_color('orange')
                else:
                    bar.set_color('red')
            
            ax.set_yticks(range(len(comparisons)))
            ax.set_yticklabels([comp.replace('_vs_', ' vs ') for comp in comparisons])
            ax.set_xlabel('Score de Similaridade')
            ax.set_xlim(0, 1)
            
            # Linhas de referência
            ax.axvline(x=0.8, color='green', linestyle='--', alpha=0.7, label='Alta (0.8)')
            ax.axvline(x=0.6, color='orange', linestyle='--', alpha=0.7, label='Moderada (0.6)')
            ax.legend()
            
            # Adicionar valores
            for bar, score in zip(bars, overall_scores):
                width = bar.get_width()
                ax.text(width + 0.02, bar.get_y() + bar.get_height()/2.,
                       f'{score:.3f}', ha='left', va='center', fontsize=10)
        else:
            ax.text(0.5, 0.5, 'Scores de similaridade\nnão disponíveis', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
        
        ax.set_title('Scores de Similaridade Geral', fontweight='bold')
    
    def print_reproducibility_summary(self, analysis: Dict[str, Any]):
        """Imprime resumo da análise de reprodutibilidade"""
        print("\n" + "="*80)
        print("🔄 RELATÓRIO DE REPRODUTIBILIDADE")
        print("="*80)
        
        summary = analysis.get('summary', {})
        print(f"\n📊 RESUMO GERAL:")
        print(f"  • Número de execuções analisadas: {summary.get('num_runs', 0)}")
        print(f"  • Execuções: {', '.join(summary.get('run_names', []))}")
        
        # Consistência de dados
        data_consistency = summary.get('data_consistency', {})
        if data_consistency:
            print(f"\n📈 CONSISTÊNCIA DE DADOS:")
            event_cv = data_consistency.get('event_count_cv', float('inf'))
            vehicle_cv = data_consistency.get('vehicle_count_cv', float('inf'))
            
            if event_cv != float('inf'):
                consistency_level = "Excelente" if event_cv < 0.01 else "Boa" if event_cv < 0.05 else "Moderada" if event_cv < 0.1 else "Baixa"
                print(f"  • Variação em número de eventos: CV = {event_cv:.4f} ({consistency_level})")
            
            if vehicle_cv != float('inf'):
                consistency_level = "Excelente" if vehicle_cv < 0.01 else "Boa" if vehicle_cv < 0.05 else "Moderada" if vehicle_cv < 0.1 else "Baixa"
                print(f"  • Variação em número de veículos: CV = {vehicle_cv:.4f} ({consistency_level})")
        
        # Similaridade geral
        similarity_scores = analysis.get('similarity_scores', {})
        if similarity_scores:
            print(f"\n🎯 SCORES DE SIMILARIDADE:")
            for comparison, data in similarity_scores.items():
                score = data['overall_similarity_score']
                level = "Alta" if score >= 0.8 else "Moderada" if score >= 0.6 else "Baixa"
                print(f"  • {comparison.replace('_vs_', ' vs ')}: {score:.3f} ({level})")
        
        # Testes estatísticos
        statistical_tests = analysis.get('statistical_tests', {})
        if statistical_tests:
            print(f"\n📊 TESTES ESTATÍSTICOS:")
            for metric, tests in statistical_tests.items():
                print(f"  • {metric.replace('_', ' ').title()}:")
                for test_name, test_data in tests.items():
                    p_value = test_data.get('p_value', 1.0)
                    result = "Diferença significativa" if p_value <= 0.05 else "Sem diferença significativa"
                    print(f"    - {test_name.replace('_', ' ').title()}: p = {p_value:.4f} ({result})")
        
        # Variabilidade
        variability = analysis.get('variability_analysis', {})
        if variability:
            print(f"\n📈 VARIABILIDADE ENTRE EXECUÇÕES:")
            for metric, data in variability.items():
                cross_run_var = data.get('cross_run_variability', {})
                mean_cv = cross_run_var.get('mean_cv', float('inf'))
                
                if mean_cv != float('inf'):
                    var_level = "Baixa" if mean_cv < 0.05 else "Moderada" if mean_cv < 0.1 else "Alta"
                    print(f"  • {metric.replace('_', ' ').title()}: CV = {mean_cv:.4f} ({var_level})")
        
        # Recomendações
        print(f"\n🎯 RECOMENDAÇÕES:")
        
        # Baseado nos CVs
        if data_consistency:
            max_cv = max([cv for cv in [data_consistency.get('event_count_cv', 0), 
                                      data_consistency.get('vehicle_count_cv', 0)] 
                         if cv != float('inf')])
            
            if max_cv < 0.01:
                print("  ✅ Excelente reprodutibilidade! O simulador produz resultados muito consistentes.")
            elif max_cv < 0.05:
                print("  ✅ Boa reprodutibilidade! Pequenas variações são aceitáveis.")
            elif max_cv < 0.1:
                print("  ⚠️  Reprodutibilidade moderada. Considere verificar:")
                print("     - Seeds de aleatoriedade")
                print("     - Configurações de simulação")
                print("     - Condições iniciais")
            else:
                print("  ❌ Baixa reprodutibilidade! Ação necessária:")
                print("     - Verificar determinismo do simulador")
                print("     - Analisar fontes de aleatoriedade")
                print("     - Validar configurações idênticas")
        
        print("\n" + "="*80)
    
    def _create_consolidated_pdf_report(self, 
                                      datasets: List[pd.DataFrame],
                                      run_names: List[str],
                                      analysis: Dict[str, Any]) -> Optional[str]:
        """Cria um relatório PDF consolidado com todos os gráficos"""
        pdf_file = self.output_dir / 'reproducibility_complete_report.pdf'
        
        try:
            with PdfPages(pdf_file) as pdf:
                # Página 1: Comparação de métricas básicas
                fig, axes = plt.subplots(2, 2, figsize=(15, 12))
                fig.suptitle('Relatório de Reprodutibilidade - Métricas Básicas', fontsize=16, fontweight='bold')
                
                basic_metrics = analysis.get('basic_metrics', {})
                metrics_plotted = 0
                
                for metric_key, metric_data in basic_metrics.items():
                    if metrics_plotted >= 4:
                        break
                    
                    ax = axes[metrics_plotted // 2, metrics_plotted % 2]
                    
                    runs = [stat['run'] for stat in metric_data['run_statistics']]
                    means = [stat['mean'] for stat in metric_data['run_statistics']]
                    stds = [stat['std'] for stat in metric_data['run_statistics']]
                    
                    bars = ax.bar(runs, means, yerr=stds, capsize=5, alpha=0.7)
                    ax.set_title(f"{metric_data['metric_name']}")
                    ax.set_ylabel('Valor Médio')
                    ax.tick_params(axis='x', rotation=45)
                    
                    cv = metric_data['cross_run_variability']['mean_cv']
                    if cv != float('inf'):
                        ax.set_title(f"{metric_data['metric_name']} (CV: {cv:.3f})")
                    
                    metrics_plotted += 1
                
                for i in range(metrics_plotted, 4):
                    axes[i // 2, i % 2].axis('off')
                
                plt.tight_layout()
                pdf.savefig(fig, dpi=300, bbox_inches='tight')
                plt.close(fig)
                
                # Página 2: Scores de similaridade
                similarity_data = analysis.get('similarity_scores', {})
                if similarity_data:
                    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
                    fig.suptitle('Relatório de Reprodutibilidade - Scores de Similaridade', fontsize=16, fontweight='bold')
                    
                    comparisons = list(similarity_data.keys())
                    overall_scores = [data['overall_similarity_score'] for data in similarity_data.values()]
                    
                    bars1 = ax1.bar(range(len(comparisons)), overall_scores, alpha=0.7, color='skyblue')
                    ax1.set_xticks(range(len(comparisons)))
                    ax1.set_xticklabels(comparisons, rotation=45, ha='right')
                    ax1.set_ylabel('Score de Similaridade')
                    ax1.set_title('Score Geral de Similaridade')
                    ax1.set_ylim(0, 1)
                    
                    ax1.axhline(y=0.8, color='green', linestyle='--', alpha=0.7, label='Alta similaridade')
                    ax1.legend()
                    
                    # Adicionar valores
                    for bar, score in zip(bars1, overall_scores):
                        height = bar.get_height()
                        ax1.text(bar.get_x() + bar.get_width()/2., height + 0.02,
                                f'{score:.3f}', ha='center', va='bottom', fontsize=9)
                    
                    # Outros gráficos simplificados para o PDF
                    ax2.text(0.5, 0.5, 'Velocidade\n(Ver gráficos individuais)', ha='center', va='center', transform=ax2.transAxes)
                    ax3.text(0.5, 0.5, 'Tempo de Viagem\n(Ver gráficos individuais)', ha='center', va='center', transform=ax3.transAxes)  
                    ax4.text(0.5, 0.5, 'Densidade\n(Ver gráficos individuais)', ha='center', va='center', transform=ax4.transAxes)
                    
                    plt.tight_layout()
                    pdf.savefig(fig, dpi=300, bbox_inches='tight')
                    plt.close(fig)
                
                # Página 3: Resumo e recomendações
                fig, ax = plt.subplots(figsize=(15, 12))
                fig.suptitle('Relatório de Reprodutibilidade - Resumo Final', fontsize=16, fontweight='bold')
                
                # Criar texto de resumo
                summary = analysis.get('summary', {})
                text_content = []
                text_content.append("📊 RESUMO GERAL:")
                text_content.append(f"  • Número de execuções analisadas: {summary.get('num_runs', 0)}")
                text_content.append(f"  • Execuções: {', '.join(summary.get('run_names', []))}")
                text_content.append("")
                
                data_consistency = summary.get('data_consistency', {})
                if data_consistency:
                    text_content.append("📈 CONSISTÊNCIA DE DADOS:")
                    event_cv = data_consistency.get('event_count_cv', float('inf'))
                    vehicle_cv = data_consistency.get('vehicle_count_cv', float('inf'))
                    
                    if event_cv != float('inf'):
                        text_content.append(f"  • Variação em número de eventos: CV = {event_cv:.4f}")
                    if vehicle_cv != float('inf'):
                        text_content.append(f"  • Variação em número de veículos: CV = {vehicle_cv:.4f}")
                
                # Adicionar scores de similaridade
                if similarity_data:
                    text_content.append("")
                    text_content.append("🎯 SCORES DE SIMILARIDADE:")
                    for comp, data in similarity_data.items():
                        score = data['overall_similarity_score']
                        text_content.append(f"  • {comp}: {score:.3f}")
                
                # Recomendações
                text_content.append("")
                text_content.append("🎯 RECOMENDAÇÕES:")
                if data_consistency:
                    max_cv = max([cv for cv in [event_cv, vehicle_cv] if cv != float('inf')])
                    if max_cv < 0.01:
                        text_content.append("  ✅ Excelente reprodutibilidade!")
                    elif max_cv < 0.05:
                        text_content.append("  ✅ Boa reprodutibilidade!")
                    else:
                        text_content.append("  ⚠️ Reprodutibilidade pode ser melhorada")
                
                ax.text(0.1, 0.9, '\n'.join(text_content), transform=ax.transAxes, 
                       fontsize=12, verticalalignment='top', fontfamily='monospace')
                ax.axis('off')
                
                plt.tight_layout()
                pdf.savefig(fig, dpi=300, bbox_inches='tight')
                plt.close(fig)
            
            self.logger.info(f"📑 Relatório PDF consolidado salvo: {pdf_file}")
            return str(pdf_file)
            
        except Exception as e:
            self.logger.error(f"Erro ao criar PDF consolidado: {e}")
            return None