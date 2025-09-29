#!/usr/bin/env python3
"""
Módulo para análise de métricas gerais da simulação de tráfego
"""

import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from typing import Dict, Any, List, Tuple, Optional
import logging
from pathlib import Path
import json


class GeneralTrafficMetrics:
    """
    Classe para calcular e visualizar métricas gerais da simulação de tráfego
    """
    
    def __init__(self, output_dir: str = "output/metrics"):
        """
        Inicializa o analisador de métricas gerais
        
        Args:
            output_dir: Diretório para salvar gráficos e relatórios
        """
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.logger = logging.getLogger(__name__)
        
        # Configuração de estilo dos gráficos
        try:
            plt.style.use('seaborn-v0_8')
        except OSError:
            # Fallback para versões mais antigas do seaborn
            try:
                plt.style.use('seaborn')
            except OSError:
                plt.style.use('default')
        
        sns.set_palette("husl")
        
    def calculate_all_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """
        Calcula todas as métricas gerais da simulação
        
        Args:
            data: DataFrame com dados da simulação
            
        Returns:
            Dicionário com todas as métricas calculadas
        """
        if data.empty:
            return {}
            
        metrics = {
            'basic_stats': self._calculate_basic_stats(data),
            'distance_metrics': self._calculate_distance_metrics(data),
            'speed_metrics': self._calculate_speed_metrics(data),
            'temporal_metrics': self._calculate_temporal_metrics(data),
            'density_metrics': self._calculate_density_metrics(data),
            'performance_metrics': self._calculate_performance_metrics(data),
            'efficiency_metrics': self._calculate_efficiency_metrics(data)
        }
        
        return metrics
    
    def _calculate_basic_stats(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula estatísticas básicas da simulação"""
        stats = {
            'total_events': len(data),
            'unique_vehicles': data['car_id'].nunique() if 'car_id' in data.columns else 0,
            'unique_links': data['link_id'].nunique() if 'link_id' in data.columns else 0,
            'simulation_duration': 0,
            'data_quality': self._assess_data_quality(data)
        }
        
        # Determinar coluna de tempo (prioriza tick sobre timestamp)
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        
        # Duração da simulação
        if time_col in data.columns:
            if time_col == 'tick':
                # Para tick, calcular em segundos assumindo que tick é em segundos
                time_values = pd.to_numeric(data[time_col], errors='coerce').dropna()
                if not time_values.empty:
                    stats['simulation_duration'] = time_values.max() - time_values.min()
                    stats['start_time'] = time_values.min()
                    stats['end_time'] = time_values.max()
                    stats['time_unit'] = 'tick (seconds)'
            else:
                # Para timestamp, usar datetime
                timestamps = pd.to_datetime(data[time_col], errors='coerce').dropna()
                if not timestamps.empty:
                    stats['simulation_duration'] = (timestamps.max() - timestamps.min()).total_seconds()
                    stats['start_time'] = timestamps.min()
                    stats['end_time'] = timestamps.max()
                    stats['time_unit'] = 'timestamp (datetime)'
        
        return stats
    
    def _calculate_distance_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas relacionadas a distâncias percorridas"""
        metrics = {}
        
        if 'link_length' in data.columns:
            # Converte para numérico, ignorando erros
            lengths = pd.to_numeric(data['link_length'], errors='coerce').dropna()
            
            if not lengths.empty:
                # Total de km trafegados
                total_km = lengths.sum() / 1000  # Assumindo metros para km
                
                metrics.update({
                    'total_km_traveled': total_km,
                    'avg_link_length': lengths.mean() / 1000,
                    'median_link_length': lengths.median() / 1000,
                    'max_link_length': lengths.max() / 1000,
                    'min_link_length': lengths.min() / 1000,
                    'std_link_length': lengths.std() / 1000
                })
                
                # Km por veículo
                if 'car_id' in data.columns:
                    unique_vehicles = data['car_id'].nunique()
                    if unique_vehicles > 0:
                        metrics['avg_km_per_vehicle'] = total_km / unique_vehicles
        
        return metrics
    
    def _calculate_speed_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas relacionadas a velocidades"""
        metrics = {}
        
        if 'calculated_speed' in data.columns:
            speeds = pd.to_numeric(data['calculated_speed'], errors='coerce').dropna()
            
            if not speeds.empty:
                metrics.update({
                    'avg_speed_kmh': speeds.mean(),
                    'median_speed_kmh': speeds.median(),
                    'max_speed_kmh': speeds.max(),
                    'min_speed_kmh': speeds.min(),
                    'std_speed_kmh': speeds.std(),
                    'speed_75_percentile': speeds.quantile(0.75),
                    'speed_25_percentile': speeds.quantile(0.25),
                    'speed_95_percentile': speeds.quantile(0.95),
                    'speed_5_percentile': speeds.quantile(0.05)
                })
                
                # Classificação de velocidades
                metrics['speed_classification'] = {
                    'very_slow': len(speeds[speeds < 20]),  # < 20 km/h
                    'slow': len(speeds[(speeds >= 20) & (speeds < 40)]),  # 20-40 km/h
                    'moderate': len(speeds[(speeds >= 40) & (speeds < 60)]),  # 40-60 km/h
                    'fast': len(speeds[(speeds >= 60) & (speeds < 80)]),  # 60-80 km/h
                    'very_fast': len(speeds[speeds >= 80])  # >= 80 km/h
                }
        
        return metrics
    
    def _calculate_temporal_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas temporais"""
        metrics = {}
        
        # Determinar coluna de tempo
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        
        if time_col in data.columns:
            data_copy = data.copy()
            
            if time_col == 'tick':
                # Para tick, tratar como segundos e converter para horas
                data_copy[time_col] = pd.to_numeric(data_copy[time_col], errors='coerce')
                data_copy = data_copy.dropna(subset=[time_col])
                data_copy['hour'] = (data_copy[time_col] // 3600).astype(int)  # Converter segundos para horas
                data_copy['minute'] = ((data_copy[time_col] % 3600) // 60).astype(int)  # Minutos
            else:
                # Para timestamp, usar datetime normal
                data_copy[time_col] = pd.to_datetime(data_copy[time_col], errors='coerce')
                data_copy = data_copy.dropna(subset=[time_col])
                data_copy['hour'] = data_copy[time_col].dt.hour
                data_copy['minute'] = data_copy[time_col].dt.minute
            
            # Fluxo por hora
            hourly_flow = data_copy.groupby('hour').size()
            metrics['hourly_flow'] = hourly_flow.to_dict()
            metrics['peak_hour'] = hourly_flow.idxmax() if not hourly_flow.empty else None
            metrics['peak_hour_volume'] = hourly_flow.max() if not hourly_flow.empty else 0
            metrics['min_hour_volume'] = hourly_flow.min() if not hourly_flow.empty else 0
            
            # Fluxo por minuto para análise mais detalhada
            minutely_flow = data_copy.groupby(['hour', 'minute']).size()
            metrics['max_minute_volume'] = minutely_flow.max() if not minutely_flow.empty else 0
            metrics['avg_minute_volume'] = minutely_flow.mean() if not minutely_flow.empty else 0
            
            # Informação sobre a coluna de tempo usada
            metrics['time_column_used'] = time_col
        
        return metrics
    
    def _calculate_density_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas de densidade de tráfego"""
        metrics = {}
        
        if 'traffic_density' in data.columns:
            densities = pd.to_numeric(data['traffic_density'], errors='coerce').dropna()
            
            if not densities.empty:
                metrics.update({
                    'avg_density': densities.mean(),
                    'median_density': densities.median(),
                    'max_density': densities.max(),
                    'min_density': densities.min(),
                    'std_density': densities.std()
                })
                
                # Classificação de densidade
                metrics['density_classification'] = {
                    'free_flow': len(densities[densities < 0.2]),  # Densidade baixa
                    'stable_flow': len(densities[(densities >= 0.2) & (densities < 0.5)]),
                    'unstable_flow': len(densities[(densities >= 0.5) & (densities < 0.8)]),
                    'forced_flow': len(densities[densities >= 0.8])  # Densidade alta
                }
        
        return metrics
    
    def _calculate_performance_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas de performance da simulação"""
        metrics = {}
        
        # Eficiência temporal
        if 'travel_time' in data.columns and 'link_length' in data.columns:
            travel_times = pd.to_numeric(data['travel_time'], errors='coerce').dropna()
            lengths = pd.to_numeric(data['link_length'], errors='coerce').dropna()
            
            if not travel_times.empty and not lengths.empty:
                # Calcular velocidade média baseada em travel time
                # Assumindo travel_time em segundos e link_length em metros
                data_clean = data.dropna(subset=['travel_time', 'link_length'])
                if not data_clean.empty:
                    data_clean['speed_from_travel_time'] = (
                        pd.to_numeric(data_clean['link_length']) / 
                        pd.to_numeric(data_clean['travel_time']) * 3.6  # m/s para km/h
                    )
                    
                    speed_travel = data_clean['speed_from_travel_time'].dropna()
                    if not speed_travel.empty:
                        metrics['speed_from_travel_time'] = {
                            'avg': speed_travel.mean(),
                            'median': speed_travel.median(),
                            'std': speed_travel.std()
                        }
        
        return metrics
    
    def _calculate_efficiency_metrics(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Calcula métricas de eficiência do sistema"""
        metrics = {}
        
        # Determinar coluna de tempo
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        
        # Throughput (veículos por hora)
        if time_col in data.columns and 'car_id' in data.columns:
            data_copy = data.copy()
            
            if time_col == 'tick':
                # Para tick, agrupar por intervalos de 3600 segundos (1 hora)
                data_copy[time_col] = pd.to_numeric(data_copy[time_col], errors='coerce')
                data_copy = data_copy.dropna(subset=[time_col])
                data_copy['hour_bin'] = (data_copy[time_col] // 3600).astype(int)
                
                # Agrupar por hora e contar veículos únicos
                hourly_vehicles = data_copy.groupby('hour_bin')['car_id'].nunique()
            else:
                # Para timestamp, usar agrupamento datetime normal
                data_copy[time_col] = pd.to_datetime(data_copy[time_col], errors='coerce')
                data_copy = data_copy.dropna(subset=[time_col])
                
                # Agrupar por hora e contar veículos únicos
                hourly_vehicles = data_copy.groupby(
                    data_copy[time_col].dt.floor('H')
                )['car_id'].nunique()
            
            if not hourly_vehicles.empty:
                metrics['throughput'] = {
                    'avg_vehicles_per_hour': hourly_vehicles.mean(),
                    'max_vehicles_per_hour': hourly_vehicles.max(),
                    'min_vehicles_per_hour': hourly_vehicles.min(),
                    'time_column_used': time_col
                }
        
        return metrics
    
    def _assess_data_quality(self, data: pd.DataFrame) -> Dict[str, Any]:
        """Avalia a qualidade dos dados"""
        quality = {}
        
        # Completude dos dados
        total_cells = len(data) * len(data.columns)
        missing_cells = data.isnull().sum().sum()
        quality['completeness'] = 1 - (missing_cells / total_cells) if total_cells > 0 else 0
        
        # Colunas com dados faltantes
        missing_by_column = data.isnull().sum()
        quality['missing_by_column'] = missing_by_column[missing_by_column > 0].to_dict()
        
        # Duplicatas
        if 'car_id' in data.columns and 'timestamp' in data.columns and 'link_id' in data.columns:
            duplicates = data.duplicated(subset=['car_id', 'timestamp', 'link_id']).sum()
            quality['duplicates'] = duplicates
            quality['duplicate_ratio'] = duplicates / len(data) if len(data) > 0 else 0
        
        return quality
    
    def generate_all_plots(self, data: pd.DataFrame, metrics: Dict[str, Any]) -> List[str]:
        """
        Gera todos os gráficos de análise
        
        Returns:
            Lista de caminhos dos arquivos gerados
        """
        generated_files = []
        
        try:
            # Gráficos de velocidade
            speed_files = self._plot_speed_analysis(data, metrics)
            generated_files.extend(speed_files)
            
            # Gráficos temporais
            temporal_files = self._plot_temporal_analysis(data, metrics)
            generated_files.extend(temporal_files)
            
            # Gráficos de densidade
            density_files = self._plot_density_analysis(data, metrics)
            generated_files.extend(density_files)
            
            # Gráfico de resumo geral
            summary_file = self._plot_general_summary(metrics)
            if summary_file:
                generated_files.append(summary_file)
            
            # Dashboard principal
            dashboard_file = self._create_dashboard(data, metrics)
            if dashboard_file:
                generated_files.append(dashboard_file)
                
        except Exception as e:
            self.logger.error(f"Erro ao gerar gráficos: {e}")
        
        return generated_files
    
    def _plot_speed_analysis(self, data: pd.DataFrame, metrics: Dict[str, Any]) -> List[str]:
        """Gera gráficos de análise de velocidade"""
        files = []
        
        if 'calculated_speed' not in data.columns:
            return files
            
        speeds = pd.to_numeric(data['calculated_speed'], errors='coerce').dropna()
        if speeds.empty:
            return files
        
        # Histograma de velocidades
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Análise de Velocidades', fontsize=16, fontweight='bold')
        
        # Histograma
        ax1.hist(speeds, bins=30, alpha=0.7, edgecolor='black')
        ax1.set_xlabel('Velocidade (km/h)')
        ax1.set_ylabel('Frequência')
        ax1.set_title('Distribuição de Velocidades')
        ax1.axvline(speeds.mean(), color='red', linestyle='--', label=f'Média: {speeds.mean():.1f} km/h')
        ax1.axvline(speeds.median(), color='orange', linestyle='--', label=f'Mediana: {speeds.median():.1f} km/h')
        ax1.legend()
        
        # Box plot
        ax2.boxplot(speeds)
        ax2.set_ylabel('Velocidade (km/h)')
        ax2.set_title('Box Plot - Velocidades')
        ax2.set_xticklabels(['Velocidades'])
        
        # Classificação de velocidades
        speed_class = metrics.get('speed_metrics', {}).get('speed_classification', {})
        if speed_class:
            categories = list(speed_class.keys())
            values = list(speed_class.values())
            
            ax3.bar(categories, values, alpha=0.7)
            ax3.set_xlabel('Classificação')
            ax3.set_ylabel('Número de Eventos')
            ax3.set_title('Classificação de Velocidades')
            ax3.tick_params(axis='x', rotation=45)
        
        # Evolução temporal da velocidade (se tempo disponível)
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        if time_col in data.columns:
            data_temp = data.copy()
            data_temp['calculated_speed'] = pd.to_numeric(data_temp['calculated_speed'], errors='coerce')
            
            if time_col == 'tick':
                # Para tick, converter para horas
                data_temp[time_col] = pd.to_numeric(data_temp[time_col], errors='coerce')
                data_temp = data_temp.dropna(subset=[time_col])
                data_temp['hour'] = (data_temp[time_col] // 3600).astype(int)
            else:
                # Para timestamp, usar datetime
                data_temp[time_col] = pd.to_datetime(data_temp[time_col], errors='coerce')
                data_temp = data_temp.dropna(subset=[time_col])
                data_temp['hour'] = data_temp[time_col].dt.hour
            
            # Velocidade média por hora
            hourly_speed = data_temp.groupby('hour')['calculated_speed'].mean()
            
            if not hourly_speed.empty:
                ax4.plot(hourly_speed.index, hourly_speed.values, marker='o')
                ax4.set_xlabel('Hora (do início da simulação)' if time_col == 'tick' else 'Hora do Dia')
                ax4.set_ylabel('Velocidade Média (km/h)')
                ax4.set_title('Velocidade Média por Hora')
                ax4.grid(True, alpha=0.3)
        
        plt.tight_layout()
        
        speed_file = self.output_dir / 'speed_analysis.png'
        plt.savefig(speed_file, dpi=300, bbox_inches='tight')
        plt.close()
        files.append(str(speed_file))
        
        return files
    
    def _plot_temporal_analysis(self, data: pd.DataFrame, metrics: Dict[str, Any]) -> List[str]:
        """Gera gráficos de análise temporal"""
        files = []
        
        # Determinar coluna de tempo
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        
        if time_col not in data.columns:
            return files
        
        data_temp = data.copy()
        
        if time_col == 'tick':
            # Para tick, tratar como segundos
            data_temp[time_col] = pd.to_numeric(data_temp[time_col], errors='coerce')
            data_temp = data_temp.dropna(subset=[time_col])
        else:
            # Para timestamp, usar datetime
            data_temp[time_col] = pd.to_datetime(data_temp[time_col], errors='coerce')
            data_temp = data_temp.dropna(subset=[time_col])
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle(f'Análise Temporal do Tráfego (usando {time_col})', fontsize=16, fontweight='bold')
        
        # Fluxo por hora
        temporal_metrics = metrics.get('temporal_metrics', {})
        hourly_flow_data = temporal_metrics.get('hourly_flow', {})
        
        if hourly_flow_data:
            hours = list(hourly_flow_data.keys())
            flows = list(hourly_flow_data.values())
            ax1.bar(hours, flows, alpha=0.7)
            ax1.set_xlabel('Hora (desde início)' if time_col == 'tick' else 'Hora do Dia')
            ax1.set_ylabel('Número de Eventos')
            ax1.set_title('Fluxo de Tráfego por Hora')
            ax1.grid(True, alpha=0.3)
        
        # Evolução temporal detalhada
        if time_col == 'tick':
            # Para tick, criar bins de tempo
            data_temp['time_minutes'] = data_temp[time_col] / 60  # Converter para minutos
        else:
            # Para timestamp, calcular minutos desde o início
            data_temp['time_minutes'] = (data_temp[time_col] - data_temp[time_col].min()).dt.total_seconds() / 60
        
        # Agrupar por intervalos de 5 minutos
        time_bins = np.arange(0, data_temp['time_minutes'].max() + 5, 5)
        data_temp['time_bin'] = pd.cut(data_temp['time_minutes'], bins=time_bins)
        flow_evolution = data_temp.groupby('time_bin').size()
        
        if not flow_evolution.empty:
            ax2.plot(range(len(flow_evolution)), flow_evolution.values, alpha=0.8)
            ax2.set_xlabel('Tempo (intervalos de 5 min)')
            ax2.set_ylabel('Eventos por Intervalo')
            ax2.set_title('Evolução Temporal do Fluxo')
            ax2.grid(True, alpha=0.3)
        
        # Heatmap de atividade
        if time_col == 'tick':
            data_temp['hour'] = (data_temp[time_col] // 3600).astype(int)
            data_temp['minute_bin'] = ((data_temp[time_col] % 3600) // 600).astype(int) * 10  # Bins de 10 min
        else:
            data_temp['hour'] = data_temp[time_col].dt.hour
            data_temp['minute_bin'] = (data_temp[time_col].dt.minute // 10) * 10
        
        heatmap_data = data_temp.groupby(['hour', 'minute_bin']).size().unstack(fill_value=0)
        
        if not heatmap_data.empty:
            sns.heatmap(heatmap_data, ax=ax3, cmap='YlOrRd', cbar_kws={'label': 'Eventos'})
            ax3.set_xlabel('Minuto (bins de 10)')
            ax3.set_ylabel('Hora')
            ax3.set_title('Heatmap de Atividade (Hora vs Minuto)')
        
        # Estatísticas temporais
        if temporal_metrics:
            stats_text = []
            stats_text.append(f"Coluna de tempo: {time_col}")
            stats_text.append(f"Hora de Pico: {temporal_metrics.get('peak_hour', 'N/A')}")
            stats_text.append(f"Volume no Pico: {temporal_metrics.get('peak_hour_volume', 0):,}")
            stats_text.append(f"Volume Mínimo: {temporal_metrics.get('min_hour_volume', 0):,}")
            stats_text.append(f"Volume Máx/Min: {temporal_metrics.get('max_minute_volume', 0):,}")
            
            ax4.text(0.1, 0.8, '\n'.join(stats_text), transform=ax4.transAxes, 
                    fontsize=12, verticalalignment='top',
                    bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.8))
            ax4.set_title('Estatísticas Temporais')
            ax4.axis('off')
        
        plt.tight_layout()
        
        temporal_file = self.output_dir / 'temporal_analysis.png'
        plt.savefig(temporal_file, dpi=300, bbox_inches='tight')
        plt.close()
        files.append(str(temporal_file))
        
        return files
    
    def _plot_density_analysis(self, data: pd.DataFrame, metrics: Dict[str, Any]) -> List[str]:
        """Gera gráficos de análise de densidade"""
        files = []
        
        if 'traffic_density' not in data.columns:
            return files
            
        densities = pd.to_numeric(data['traffic_density'], errors='coerce').dropna()
        if densities.empty:
            return files
        
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Análise de Densidade de Tráfego', fontsize=16, fontweight='bold')
        
        # Histograma de densidades
        ax1.hist(densities, bins=30, alpha=0.7, edgecolor='black')
        ax1.set_xlabel('Densidade de Tráfego')
        ax1.set_ylabel('Frequência')
        ax1.set_title('Distribuição de Densidades')
        ax1.axvline(densities.mean(), color='red', linestyle='--', label=f'Média: {densities.mean():.3f}')
        ax1.legend()
        
        # Classificação de densidade
        density_class = metrics.get('density_metrics', {}).get('density_classification', {})
        if density_class:
            categories = list(density_class.keys())
            values = list(density_class.values())
            
            ax2.pie(values, labels=categories, autopct='%1.1f%%', startangle=90)
            ax2.set_title('Distribuição por Classe de Densidade')
        
        # Relação velocidade vs densidade (se ambas disponíveis)
        if 'calculated_speed' in data.columns:
            data_clean = data.copy()
            data_clean['calculated_speed'] = pd.to_numeric(data_clean['calculated_speed'], errors='coerce')
            data_clean['traffic_density'] = pd.to_numeric(data_clean['traffic_density'], errors='coerce')
            data_clean = data_clean.dropna(subset=['calculated_speed', 'traffic_density'])
            
            if not data_clean.empty:
                ax3.scatter(data_clean['traffic_density'], data_clean['calculated_speed'], 
                           alpha=0.5, s=1)
                ax3.set_xlabel('Densidade de Tráfego')
                ax3.set_ylabel('Velocidade (km/h)')
                ax3.set_title('Velocidade vs Densidade')
                ax3.grid(True, alpha=0.3)
                
                # Linha de tendência
                if len(data_clean) > 10:
                    z = np.polyfit(data_clean['traffic_density'], data_clean['calculated_speed'], 1)
                    p = np.poly1d(z)
                    ax3.plot(data_clean['traffic_density'], p(data_clean['traffic_density']), 
                            "r--", alpha=0.8, label=f'Tendência: y={z[0]:.2f}x+{z[1]:.2f}')
                    ax3.legend()
        
        # Box plot de densidade
        ax4.boxplot(densities)
        ax4.set_ylabel('Densidade de Tráfego')
        ax4.set_title('Box Plot - Densidades')
        ax4.set_xticklabels(['Densidade'])
        
        plt.tight_layout()
        
        density_file = self.output_dir / 'density_analysis.png'
        plt.savefig(density_file, dpi=300, bbox_inches='tight')
        plt.close()
        files.append(str(density_file))
        
        return files
    
    def _plot_general_summary(self, metrics: Dict[str, Any]) -> Optional[str]:
        """Gera gráfico de resumo geral"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
        fig.suptitle('Resumo Geral da Simulação', fontsize=16, fontweight='bold')
        
        # Estatísticas básicas
        basic_stats = metrics.get('basic_stats', {})
        if basic_stats:
            stats_text = []
            stats_text.append(f"Total de Eventos: {basic_stats.get('total_events', 0):,}")
            stats_text.append(f"Veículos Únicos: {basic_stats.get('unique_vehicles', 0):,}")
            stats_text.append(f"Links Únicos: {basic_stats.get('unique_links', 0):,}")
            
            duration = basic_stats.get('simulation_duration', 0)
            if duration > 0:
                hours = int(duration // 3600)
                minutes = int((duration % 3600) // 60)
                stats_text.append(f"Duração: {hours}h {minutes}min")
            
            ax1.text(0.1, 0.8, '\n'.join(stats_text), transform=ax1.transAxes,
                    fontsize=12, verticalalignment='top',
                    bbox=dict(boxstyle='round', facecolor='lightgreen', alpha=0.8))
            ax1.set_title('Estatísticas Básicas')
            ax1.axis('off')
        
        # Métricas de distância
        distance_metrics = metrics.get('distance_metrics', {})
        if distance_metrics:
            dist_text = []
            total_km = distance_metrics.get('total_km_traveled', 0)
            avg_km_vehicle = distance_metrics.get('avg_km_per_vehicle', 0)
            avg_link = distance_metrics.get('avg_link_length', 0)
            
            dist_text.append(f"Total KM Trafegados: {total_km:,.1f} km")
            dist_text.append(f"Média por Veículo: {avg_km_vehicle:.1f} km")
            dist_text.append(f"Comprimento Médio Link: {avg_link:.3f} km")
            
            ax2.text(0.1, 0.8, '\n'.join(dist_text), transform=ax2.transAxes,
                    fontsize=12, verticalalignment='top',
                    bbox=dict(boxstyle='round', facecolor='lightcoral', alpha=0.8))
            ax2.set_title('Métricas de Distância')
            ax2.axis('off')
        
        # Métricas de velocidade
        speed_metrics = metrics.get('speed_metrics', {})
        if speed_metrics:
            speed_text = []
            speed_text.append(f"Velocidade Média: {speed_metrics.get('avg_speed_kmh', 0):.1f} km/h")
            speed_text.append(f"Velocidade Mediana: {speed_metrics.get('median_speed_kmh', 0):.1f} km/h")
            speed_text.append(f"Velocidade Máxima: {speed_metrics.get('max_speed_kmh', 0):.1f} km/h")
            speed_text.append(f"Desvio Padrão: {speed_metrics.get('std_speed_kmh', 0):.1f} km/h")
            
            ax3.text(0.1, 0.8, '\n'.join(speed_text), transform=ax3.transAxes,
                    fontsize=12, verticalalignment='top',
                    bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.8))
            ax3.set_title('Métricas de Velocidade')
            ax3.axis('off')
        
        # Qualidade dos dados
        data_quality = basic_stats.get('data_quality', {})
        if data_quality:
            quality_text = []
            completeness = data_quality.get('completeness', 0)
            duplicates = data_quality.get('duplicates', 0)
            duplicate_ratio = data_quality.get('duplicate_ratio', 0)
            
            quality_text.append(f"Completude: {completeness*100:.1f}%")
            quality_text.append(f"Duplicatas: {duplicates:,}")
            quality_text.append(f"Taxa Duplicação: {duplicate_ratio*100:.2f}%")
            
            # Cor baseada na qualidade
            color = 'lightgreen' if completeness > 0.9 and duplicate_ratio < 0.05 else 'lightyellow'
            if completeness < 0.7 or duplicate_ratio > 0.1:
                color = 'lightcoral'
            
            ax4.text(0.1, 0.8, '\n'.join(quality_text), transform=ax4.transAxes,
                    fontsize=12, verticalalignment='top',
                    bbox=dict(boxstyle='round', facecolor=color, alpha=0.8))
            ax4.set_title('Qualidade dos Dados')
            ax4.axis('off')
        
        plt.tight_layout()
        
        summary_file = self.output_dir / 'general_summary.png'
        plt.savefig(summary_file, dpi=300, bbox_inches='tight')
        plt.close()
        
        return str(summary_file)
    
    def _create_dashboard(self, data: pd.DataFrame, metrics: Dict[str, Any]) -> Optional[str]:
        """Cria um dashboard principal com visão geral"""
        fig = plt.figure(figsize=(20, 12))
        
        # Layout do grid
        gs = fig.add_gridspec(3, 4, hspace=0.3, wspace=0.3)
        
        # Título principal
        fig.suptitle('Dashboard da Simulação de Tráfego', fontsize=20, fontweight='bold')
        
        # 1. Estatísticas principais (canto superior esquerdo)
        ax1 = fig.add_subplot(gs[0, :2])
        self._add_main_stats_to_ax(ax1, metrics)
        
        # 2. Gráfico de velocidades (superior direito)
        ax2 = fig.add_subplot(gs[0, 2:])
        self._add_speed_plot_to_ax(ax2, data)
        
        # 3. Fluxo temporal (meio esquerda)
        ax3 = fig.add_subplot(gs[1, :2])
        self._add_temporal_plot_to_ax(ax3, data)
        
        # 4. Densidade (meio direita)
        ax4 = fig.add_subplot(gs[1, 2:])
        self._add_density_plot_to_ax(ax4, data)
        
        # 5. Classificações (parte inferior)
        ax5 = fig.add_subplot(gs[2, :2])
        self._add_classification_plot_to_ax(ax5, metrics)
        
        # 6. Métricas de performance (inferior direita)
        ax6 = fig.add_subplot(gs[2, 2:])
        self._add_performance_metrics_to_ax(ax6, metrics)
        
        dashboard_file = self.output_dir / 'dashboard.png'
        plt.savefig(dashboard_file, dpi=300, bbox_inches='tight')
        plt.close()
        
        return str(dashboard_file)
    
    def _add_main_stats_to_ax(self, ax, metrics):
        """Adiciona estatísticas principais ao eixo"""
        basic_stats = metrics.get('basic_stats', {})
        distance_metrics = metrics.get('distance_metrics', {})
        
        stats_lines = [
            f"📊 ESTATÍSTICAS PRINCIPAIS",
            f"",
            f"🚗 Total de Eventos: {basic_stats.get('total_events', 0):,}",
            f"🚙 Veículos Únicos: {basic_stats.get('unique_vehicles', 0):,}",
            f"🛣️  Links Únicos: {basic_stats.get('unique_links', 0):,}",
            f"📏 Total KM: {distance_metrics.get('total_km_traveled', 0):,.1f} km",
            f"⚡ KM/Veículo: {distance_metrics.get('avg_km_per_vehicle', 0):.1f} km"
        ]
        
        ax.text(0.05, 0.95, '\n'.join(stats_lines), transform=ax.transAxes,
                fontsize=11, verticalalignment='top', fontfamily='monospace',
                bbox=dict(boxstyle='round,pad=0.5', facecolor='lightblue', alpha=0.8))
        ax.set_title('Resumo Geral', fontweight='bold')
        ax.axis('off')
    
    def _add_speed_plot_to_ax(self, ax, data):
        """Adiciona gráfico de velocidades ao eixo"""
        if 'calculated_speed' in data.columns:
            speeds = pd.to_numeric(data['calculated_speed'], errors='coerce').dropna()
            if not speeds.empty:
                ax.hist(speeds, bins=25, alpha=0.7, edgecolor='black', color='skyblue')
                ax.axvline(speeds.mean(), color='red', linestyle='--', 
                          label=f'Média: {speeds.mean():.1f} km/h')
                ax.axvline(speeds.median(), color='orange', linestyle='--', 
                          label=f'Mediana: {speeds.median():.1f} km/h')
                ax.set_xlabel('Velocidade (km/h)')
                ax.set_ylabel('Frequência')
                ax.legend()
        ax.set_title('Distribuição de Velocidades', fontweight='bold')
    
    def _add_temporal_plot_to_ax(self, ax, data):
        """Adiciona gráfico temporal ao eixo"""
        time_col = 'tick' if 'tick' in data.columns else 'timestamp'
        
        if time_col in data.columns:
            data_temp = data.copy()
            
            if time_col == 'tick':
                # Para tick, converter para horas
                data_temp[time_col] = pd.to_numeric(data_temp[time_col], errors='coerce')
                data_temp = data_temp.dropna(subset=[time_col])
                data_temp['hour'] = (data_temp[time_col] // 3600).astype(int)
                xlabel = 'Hora (desde início)'
            else:
                # Para timestamp, usar datetime
                data_temp[time_col] = pd.to_datetime(data_temp[time_col], errors='coerce')
                data_temp = data_temp.dropna(subset=[time_col])
                data_temp['hour'] = data_temp[time_col].dt.hour
                xlabel = 'Hora do Dia'
            
            hourly_flow = data_temp.groupby('hour').size()
            
            if not hourly_flow.empty:
                ax.bar(hourly_flow.index, hourly_flow.values, alpha=0.7, color='lightgreen')
                ax.set_xlabel(xlabel)
                ax.set_ylabel('Número de Eventos')
                ax.grid(True, alpha=0.3)
        
        ax.set_title('Fluxo por Hora', fontweight='bold')
    
    def _add_density_plot_to_ax(self, ax, data):
        """Adiciona gráfico de densidade ao eixo"""
        if 'traffic_density' in data.columns:
            densities = pd.to_numeric(data['traffic_density'], errors='coerce').dropna()
            if not densities.empty:
                ax.boxplot(densities, patch_artist=True, 
                          boxprops=dict(facecolor='lightcoral', alpha=0.7))
                ax.set_ylabel('Densidade de Tráfego')
                ax.set_xticklabels(['Densidade'])
        ax.set_title('Distribuição de Densidade', fontweight='bold')
    
    def _add_classification_plot_to_ax(self, ax, metrics):
        """Adiciona gráfico de classificações ao eixo"""
        speed_class = metrics.get('speed_metrics', {}).get('speed_classification', {})
        
        if speed_class:
            categories = ['Muito Lenta', 'Lenta', 'Moderada', 'Rápida', 'Muito Rápida']
            values = [
                speed_class.get('very_slow', 0),
                speed_class.get('slow', 0),
                speed_class.get('moderate', 0),
                speed_class.get('fast', 0),
                speed_class.get('very_fast', 0)
            ]
            
            colors = ['red', 'orange', 'yellow', 'lightgreen', 'green']
            ax.pie(values, labels=categories, autopct='%1.1f%%', colors=colors, startangle=90)
        
        ax.set_title('Classificação de Velocidades', fontweight='bold')
    
    def _add_performance_metrics_to_ax(self, ax, metrics):
        """Adiciona métricas de performance ao eixo"""
        speed_metrics = metrics.get('speed_metrics', {})
        temporal_metrics = metrics.get('temporal_metrics', {})
        
        perf_lines = [
            f"⚡ PERFORMANCE",
            f"",
            f"🏎️  Velocidade Média: {speed_metrics.get('avg_speed_kmh', 0):.1f} km/h",
            f"📈 Velocidade P95: {speed_metrics.get('speed_95_percentile', 0):.1f} km/h",
            f"📉 Velocidade P5: {speed_metrics.get('speed_5_percentile', 0):.1f} km/h",
            f"🕐 Hora de Pico: {temporal_metrics.get('peak_hour', 'N/A')}h",
            f"📊 Volume no Pico: {temporal_metrics.get('peak_hour_volume', 0):,}"
        ]
        
        ax.text(0.05, 0.95, '\n'.join(perf_lines), transform=ax.transAxes,
                fontsize=11, verticalalignment='top', fontfamily='monospace',
                bbox=dict(boxstyle='round,pad=0.5', facecolor='lightyellow', alpha=0.8))
        ax.set_title('Métricas de Performance', fontweight='bold')
        ax.axis('off')
    
    def save_metrics_report(self, metrics: Dict[str, Any], filename: str = "metrics_report.json"):
        """Salva relatório completo de métricas em JSON"""
        report_file = self.output_dir / filename
        
        with open(report_file, 'w', encoding='utf-8') as f:
            json.dump(metrics, f, indent=2, ensure_ascii=False, default=str)
        
        self.logger.info(f"Relatório de métricas salvo em: {report_file}")
        return str(report_file)
    
    def print_summary_report(self, metrics: Dict[str, Any]):
        """Imprime resumo das métricas no console"""
        print("\n" + "="*80)
        print("📊 RELATÓRIO DE MÉTRICAS GERAIS DA SIMULAÇÃO")
        print("="*80)
        
        # Estatísticas básicas
        basic_stats = metrics.get('basic_stats', {})
        print(f"\n🚗 ESTATÍSTICAS BÁSICAS:")
        print(f"  • Total de eventos: {basic_stats.get('total_events', 0):,}")
        print(f"  • Veículos únicos: {basic_stats.get('unique_vehicles', 0):,}")
        print(f"  • Links únicos: {basic_stats.get('unique_links', 0):,}")
        
        duration = basic_stats.get('simulation_duration', 0)
        if duration > 0:
            hours = int(duration // 3600)
            minutes = int((duration % 3600) // 60)
            print(f"  • Duração da simulação: {hours}h {minutes}min")
        
        # Métricas de distância
        distance_metrics = metrics.get('distance_metrics', {})
        if distance_metrics:
            print(f"\n📏 MÉTRICAS DE DISTÂNCIA:")
            print(f"  • Total KM trafegados: {distance_metrics.get('total_km_traveled', 0):,.1f} km")
            print(f"  • KM médio por veículo: {distance_metrics.get('avg_km_per_vehicle', 0):.1f} km")
            print(f"  • Comprimento médio do link: {distance_metrics.get('avg_link_length', 0):.3f} km")
        
        # Métricas de velocidade
        speed_metrics = metrics.get('speed_metrics', {})
        if speed_metrics:
            print(f"\n🏎️  MÉTRICAS DE VELOCIDADE:")
            print(f"  • Velocidade média: {speed_metrics.get('avg_speed_kmh', 0):.1f} km/h")
            print(f"  • Velocidade mediana: {speed_metrics.get('median_speed_kmh', 0):.1f} km/h")
            print(f"  • Velocidade máxima: {speed_metrics.get('max_speed_kmh', 0):.1f} km/h")
            print(f"  • Desvio padrão: {speed_metrics.get('std_speed_kmh', 0):.1f} km/h")
            print(f"  • Percentil 95: {speed_metrics.get('speed_95_percentile', 0):.1f} km/h")
            print(f"  • Percentil 5: {speed_metrics.get('speed_5_percentile', 0):.1f} km/h")
        
        # Métricas temporais
        temporal_metrics = metrics.get('temporal_metrics', {})
        if temporal_metrics:
            print(f"\n🕐 MÉTRICAS TEMPORAIS:")
            print(f"  • Hora de pico: {temporal_metrics.get('peak_hour', 'N/A')}h")
            print(f"  • Volume na hora de pico: {temporal_metrics.get('peak_hour_volume', 0):,}")
            print(f"  • Volume mínimo por hora: {temporal_metrics.get('min_hour_volume', 0):,}")
            print(f"  • Volume máximo por minuto: {temporal_metrics.get('max_minute_volume', 0):,}")
        
        # Métricas de densidade
        density_metrics = metrics.get('density_metrics', {})
        if density_metrics:
            print(f"\n🚦 MÉTRICAS DE DENSIDADE:")
            print(f"  • Densidade média: {density_metrics.get('avg_density', 0):.3f}")
            print(f"  • Densidade mediana: {density_metrics.get('median_density', 0):.3f}")
            print(f"  • Densidade máxima: {density_metrics.get('max_density', 0):.3f}")
            print(f"  • Desvio padrão: {density_metrics.get('std_density', 0):.3f}")
        
        # Qualidade dos dados
        data_quality = basic_stats.get('data_quality', {})
        if data_quality:
            print(f"\n✅ QUALIDADE DOS DADOS:")
            print(f"  • Completude: {data_quality.get('completeness', 0)*100:.1f}%")
            print(f"  • Duplicatas: {data_quality.get('duplicates', 0):,}")
            print(f"  • Taxa de duplicação: {data_quality.get('duplicate_ratio', 0)*100:.2f}%")
        
        print("\n" + "="*80)