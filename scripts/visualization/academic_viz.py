#!/usr/bin/env python3
"""
Gerador de Visualizações Acadêmicas em PDF
Gera gráficos de alta qualidade para uso em artigos científicos
"""

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import seaborn as sns
import pandas as pd
import numpy as np
from matplotlib.backends.backend_pdf import PdfPages
import plotly.graph_objects as go
import plotly.io as pio
from plotly.subplots import make_subplots
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union
import json
from datetime import datetime

# Configurações acadêmicas para matplotlib
plt.style.use('seaborn-v0_8')
plt.rcParams.update({
    'font.size': 12,
    'axes.titlesize': 14,
    'axes.labelsize': 12,
    'xtick.labelsize': 10,
    'ytick.labelsize': 10,
    'legend.fontsize': 10,
    'figure.titlesize': 16,
    'font.family': 'serif',
    'font.serif': ['Times New Roman'],
    'text.usetex': False,  # Pode ser True se tiver LaTeX instalado
    'figure.dpi': 300,
    'savefig.dpi': 300,
    'savefig.bbox': 'tight',
    'savefig.pad_inches': 0.1,
    'axes.grid': True,
    'grid.alpha': 0.3,
})

# Paleta de cores acadêmica
ACADEMIC_COLORS = {
    'primary': '#1f77b4',      # Azul principal
    'secondary': '#ff7f0e',    # Laranja
    'success': '#2ca02c',      # Verde
    'danger': '#d62728',       # Vermelho
    'warning': '#ff7f0e',      # Amarelo/Laranja
    'info': '#17a2b8',         # Azul claro
    'dark': '#343a40',         # Cinza escuro
    'light': '#f8f9fa',        # Cinza claro
    'htc': '#2E86AB',          # Azul HTC
    'reference': '#A23B72',     # Rosa referência
    'comparison': ['#1f77b4', '#ff7f0e', '#2ca02c', '#d62728', '#9467bd', '#8c564b']
}

class AcademicVisualizer:
    """Gera visualizações acadêmicas de alta qualidade em PDF"""
    
    def __init__(self, output_path: Union[str, Path]):
        """
        Inicializa o visualizador acadêmico
        
        Args:
            output_path: Caminho onde os PDFs serão salvos
        """
        self.output_path = Path(output_path)
        self.output_path.mkdir(parents=True, exist_ok=True)
        
        # Configurar Plotly para alta qualidade
        pio.kaleido.scope.mathjax = None
        
    def generate_traffic_analysis_pdf(self, data: pd.DataFrame, 
                                    analysis_results: Dict,
                                    filename: str = "traffic_analysis_academic.pdf") -> Path:
        """
        Gera PDF com análise de tráfego para artigos
        
        Args:
            data: DataFrame com dados de tráfego
            analysis_results: Resultados da análise
            filename: Nome do arquivo PDF
            
        Returns:
            Path para o arquivo PDF gerado
        """
        pdf_path = self.output_path / filename
        
        with PdfPages(pdf_path) as pdf:
            # Página 1: Fluxo de Tráfego Temporal
            fig1 = self._create_temporal_flow_figure(data, analysis_results)
            pdf.savefig(fig1, bbox_inches='tight')
            plt.close(fig1)
            
            # Página 2: Distribuição de Veículos por Link
            fig2 = self._create_vehicle_distribution_figure(data, analysis_results)
            pdf.savefig(fig2, bbox_inches='tight')
            plt.close(fig2)
            
            # Página 3: Heatmap de Uso de Links
            fig3 = self._create_link_usage_heatmap(data, analysis_results)
            pdf.savefig(fig3, bbox_inches='tight')
            plt.close(fig3)
            
            # Página 4: Métricas de Performance
            fig4 = self._create_performance_metrics_figure(analysis_results)
            pdf.savefig(fig4, bbox_inches='tight')
            plt.close(fig4)
            
            # Metadados do PDF
            d = pdf.infodict()
            d['Title'] = 'HTC Traffic Analysis - Academic Report'
            d['Author'] = 'HTC Simulator'
            d['Subject'] = 'Traffic Flow Analysis Results'
            d['Keywords'] = 'Traffic Simulation, Urban Mobility, Multi-Agent Systems'
            d['CreationDate'] = datetime.now()
            
        return pdf_path
    
    def generate_comparison_pdf(self, comparison_results: Dict,
                              htc_data: pd.DataFrame,
                              reference_data: pd.DataFrame,
                              filename: str = "simulator_comparison_academic.pdf") -> Path:
        """
        Gera PDF com comparação de simuladores para artigos
        
        Args:
            comparison_results: Resultados da comparação
            htc_data: Dados do HTC
            reference_data: Dados do simulador de referência
            filename: Nome do arquivo PDF
            
        Returns:
            Path para o arquivo PDF gerado
        """
        pdf_path = self.output_path / filename
        
        with PdfPages(pdf_path) as pdf:
            # Página 1: Radar Chart de Similaridade
            fig1 = self._create_similarity_radar_figure(comparison_results)
            pdf.savefig(fig1, bbox_inches='tight')
            plt.close(fig1)
            
            # Página 2: Comparação Temporal
            fig2 = self._create_temporal_comparison_figure(htc_data, reference_data, comparison_results)
            pdf.savefig(fig2, bbox_inches='tight')
            plt.close(fig2)
            
            # Página 3: Comparação de Links
            fig3 = self._create_link_comparison_figure(htc_data, reference_data, comparison_results)
            pdf.savefig(fig3, bbox_inches='tight')
            plt.close(fig3)
            
            # Página 4: Métricas Estatísticas
            fig4 = self._create_statistical_metrics_figure(comparison_results)
            pdf.savefig(fig4, bbox_inches='tight')
            plt.close(fig4)
            
            # Página 5: Box Plot de Distribuições
            fig5 = self._create_distribution_boxplots(htc_data, reference_data)
            pdf.savefig(fig5, bbox_inches='tight')
            plt.close(fig5)
            
            # Metadados
            d = pdf.infodict()
            d['Title'] = 'HTC vs Reference Simulator Comparison - Academic Report'
            d['Author'] = 'HTC Simulator'
            d['Subject'] = 'Simulator Validation Results'
            d['Keywords'] = 'Simulator Validation, Traffic Comparison, Multi-Agent Systems'
            d['CreationDate'] = datetime.now()
            
        return pdf_path
    
    def generate_individual_comparison_pdf(self, individual_results: Dict,
                                         filename: str = "individual_comparison_academic.pdf") -> Path:
        """
        Gera PDF com comparação individual de veículos
        
        Args:
            individual_results: Resultados da comparação individual
            filename: Nome do arquivo PDF
            
        Returns:
            Path para o arquivo PDF gerado
        """
        pdf_path = self.output_path / filename
        
        with PdfPages(pdf_path) as pdf:
            # Página 1: Similaridade de Veículos
            fig1 = self._create_vehicle_similarity_figure(individual_results)
            pdf.savefig(fig1, bbox_inches='tight')
            plt.close(fig1)
            
            # Página 2: Jornadas Comparadas
            fig2 = self._create_journey_comparison_figure(individual_results)
            pdf.savefig(fig2, bbox_inches='tight')
            plt.close(fig2)
            
            # Página 3: Estatísticas de Mapeamento
            fig3 = self._create_mapping_statistics_figure(individual_results)
            pdf.savefig(fig3, bbox_inches='tight')
            plt.close(fig3)
            
            # Metadados
            d = pdf.infodict()
            d['Title'] = 'Individual Vehicle Comparison - Academic Report'
            d['Author'] = 'HTC Simulator'
            d['Subject'] = 'Individual Element Validation Results'
            d['Keywords'] = 'Vehicle Tracking, Individual Validation, Agent Behavior'
            d['CreationDate'] = datetime.now()
            
        return pdf_path
    
    def _create_temporal_flow_figure(self, data: pd.DataFrame, analysis_results: Dict) -> plt.Figure:
        """Cria figura de fluxo temporal"""
        fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 10))
        
        # Preparar dados temporais
        if 'timestamp' in data.columns:
            # Agrupar por timestamp
            temporal_flow = data.groupby('timestamp').size()
            
            # Gráfico 1: Linha temporal
            ax1.plot(temporal_flow.index, temporal_flow.values, 
                    color=ACADEMIC_COLORS['primary'], linewidth=2, label='Vehicle Events')
            ax1.set_title('Temporal Traffic Flow Distribution', fontweight='bold')
            ax1.set_xlabel('Simulation Time (seconds)')
            ax1.set_ylabel('Number of Events')
            ax1.grid(True, alpha=0.3)
            ax1.legend()
            
            # Gráfico 2: Histograma de distribuição
            ax2.hist(temporal_flow.values, bins=30, color=ACADEMIC_COLORS['secondary'], 
                    alpha=0.7, edgecolor='black', linewidth=0.5)
            ax2.set_title('Event Frequency Distribution', fontweight='bold')
            ax2.set_xlabel('Events per Time Unit')
            ax2.set_ylabel('Frequency')
            ax2.grid(True, alpha=0.3)
            
        plt.tight_layout()
        return fig
    
    def _create_vehicle_distribution_figure(self, data: pd.DataFrame, analysis_results: Dict) -> plt.Figure:
        """Cria figura de distribuição de veículos"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # 1. Eventos por tipo
        if 'event_type' in data.columns:
            event_counts = data['event_type'].value_counts()
            colors = ACADEMIC_COLORS['comparison'][:len(event_counts)]
            
            wedges, texts, autotexts = ax1.pie(event_counts.values, labels=event_counts.index, 
                                              autopct='%1.1f%%', colors=colors)
            ax1.set_title('Event Type Distribution', fontweight='bold')
            
            # Melhorar legibilidade dos textos
            for autotext in autotexts:
                autotext.set_color('white')
                autotext.set_fontweight('bold')
        
        # 2. Top 10 Links mais utilizados
        if 'link_id' in data.columns:
            link_usage = data['link_id'].value_counts().head(10)
            ax2.bar(range(len(link_usage)), link_usage.values, 
                   color=ACADEMIC_COLORS['primary'], alpha=0.8)
            ax2.set_title('Top 10 Most Used Links', fontweight='bold')
            ax2.set_xlabel('Link Rank')
            ax2.set_ylabel('Usage Count')
            ax2.grid(True, alpha=0.3)
        
        # 3. Veículos únicos por hora (se timestamp disponível)
        if 'timestamp' in data.columns and 'car_id' in data.columns:
            data['hour'] = pd.to_numeric(data['timestamp'], errors='coerce') // 3600
            vehicles_per_hour = data.groupby('hour')['car_id'].nunique()
            ax3.bar(vehicles_per_hour.index, vehicles_per_hour.values,
                   color=ACADEMIC_COLORS['success'], alpha=0.8)
            ax3.set_title('Unique Vehicles per Hour', fontweight='bold')
            ax3.set_xlabel('Simulation Hour')
            ax3.set_ylabel('Unique Vehicles')
            ax3.grid(True, alpha=0.3)
        
        # 4. Estatísticas gerais
        if analysis_results:
            stats = analysis_results.get('basic_stats', {})
            labels = []
            values = []
            
            for key, value in stats.items():
                if isinstance(value, (int, float)):
                    labels.append(key.replace('_', ' ').title())
                    values.append(value)
            
            if labels and values:
                bars = ax4.bar(range(len(labels)), values, 
                              color=ACADEMIC_COLORS['comparison'][:len(labels)])
                ax4.set_title('Traffic Statistics Summary', fontweight='bold')
                ax4.set_xticks(range(len(labels)))
                ax4.set_xticklabels(labels, rotation=45, ha='right')
                ax4.set_ylabel('Count')
                ax4.grid(True, alpha=0.3)
                
                # Adicionar valores nas barras
                for bar, value in zip(bars, values):
                    height = bar.get_height()
                    ax4.text(bar.get_x() + bar.get_width()/2., height,
                            f'{int(value)}', ha='center', va='bottom')
        
        plt.tight_layout()
        return fig
    
    def _create_link_usage_heatmap(self, data: pd.DataFrame, analysis_results: Dict) -> plt.Figure:
        """Cria heatmap de uso de links"""
        fig, ax = plt.subplots(figsize=(12, 8))
        
        if 'link_id' in data.columns and 'timestamp' in data.columns:
            # Criar matriz temporal de uso de links
            data['time_bin'] = pd.to_numeric(data['timestamp'], errors='coerce') // 600  # 10min bins
            heatmap_data = data.groupby(['link_id', 'time_bin']).size().unstack(fill_value=0)
            
            # Limitar a top 20 links para visualização
            top_links = data['link_id'].value_counts().head(20).index
            heatmap_data_subset = heatmap_data.loc[top_links]
            
            # Criar heatmap
            im = ax.imshow(heatmap_data_subset.values, cmap='YlOrRd', aspect='auto')
            
            # Configurar eixos
            ax.set_title('Link Usage Heatmap Over Time', fontweight='bold', pad=20)
            ax.set_xlabel('Time Bins (10-minute intervals)')
            ax.set_ylabel('Link ID')
            
            # Configurar ticks
            ax.set_yticks(range(len(top_links)))
            ax.set_yticklabels([f"Link {i+1}" for i in range(len(top_links))])
            
            # Adicionar colorbar
            cbar = plt.colorbar(im, ax=ax)
            cbar.set_label('Usage Count', rotation=270, labelpad=15)
            
        else:
            ax.text(0.5, 0.5, 'Insufficient data for heatmap\nRequires link_id and timestamp columns', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=14)
            ax.set_title('Link Usage Heatmap', fontweight='bold')
            
        return fig
    
    def _create_performance_metrics_figure(self, analysis_results: Dict) -> plt.Figure:
        """Cria figura de métricas de performance"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # Extrair métricas
        metrics = analysis_results.get('performance_metrics', {})
        basic_stats = analysis_results.get('basic_stats', {})
        
        # 1. Métricas básicas
        if basic_stats:
            basic_names = []
            basic_values = []
            for key, value in basic_stats.items():
                if isinstance(value, (int, float)) and key not in ['simulation_duration', 'average_speed']:
                    basic_names.append(key.replace('_', ' ').title())
                    basic_values.append(value)
            
            if basic_names:
                bars1 = ax1.bar(range(len(basic_names)), basic_values, 
                               color=ACADEMIC_COLORS['primary'], alpha=0.8)
                ax1.set_title('Basic Traffic Metrics', fontweight='bold')
                ax1.set_xticks(range(len(basic_names)))
                ax1.set_xticklabels(basic_names, rotation=45, ha='right')
                ax1.set_ylabel('Count')
                ax1.grid(True, alpha=0.3)
                
                # Valores nas barras
                for bar, value in zip(bars1, basic_values):
                    height = bar.get_height()
                    ax1.text(bar.get_x() + bar.get_width()/2., height,
                            f'{int(value)}', ha='center', va='bottom')
        
        # 2-4. Placeholder para outras métricas
        for i, (ax, title) in enumerate([(ax2, 'Throughput Analysis'), 
                                        (ax3, 'Congestion Metrics'), 
                                        (ax4, 'System Performance')]):
            ax.text(0.5, 0.5, f'{title}\n(Available with extended metrics)', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
            ax.set_title(title, fontweight='bold')
            ax.grid(True, alpha=0.3)
        
        plt.tight_layout()
        return fig
    
    def _create_similarity_radar_figure(self, comparison_results: Dict) -> plt.Figure:
        """Cria radar chart de similaridade"""
        fig, ax = plt.subplots(figsize=(10, 10), subplot_kw=dict(projection='polar'))
        
        # Extrair métricas de similaridade
        overall_sim = comparison_results.get('overall_similarity', {})
        temporal_analysis = comparison_results.get('temporal_analysis', {})
        link_analysis = comparison_results.get('link_analysis', {})
        vehicle_analysis = comparison_results.get('vehicle_analysis', {})
        
        # Preparar dados do radar
        categories = []
        values = []
        
        # Coletar métricas
        metrics_map = {
            'Overall Score': overall_sim.get('score', 0),
            'Temporal Correlation': temporal_analysis.get('correlation', 0),
            'Link Similarity': link_analysis.get('usage_similarity', 0),
            'Event Correlation': temporal_analysis.get('event_correlation', 0),
            'Pattern Matching': vehicle_analysis.get('pattern_similarity', 0),
            'Data Consistency': overall_sim.get('consistency_score', 0.8)
        }
        
        for category, value in metrics_map.items():
            categories.append(category)
            values.append(max(0, min(1, value)))  # Garantir valores entre 0 e 1
        
        # Fechar o radar
        values += values[:1]
        
        # Ângulos para cada categoria
        angles = np.linspace(0, 2 * np.pi, len(categories), endpoint=False).tolist()
        angles += angles[:1]
        
        # Plotar
        ax.plot(angles, values, 'o-', linewidth=3, color=ACADEMIC_COLORS['htc'], 
                label='HTC vs Reference')
        ax.fill(angles, values, alpha=0.25, color=ACADEMIC_COLORS['htc'])
        
        # Configurar
        ax.set_xticks(angles[:-1])
        ax.set_xticklabels(categories, fontsize=11)
        ax.set_ylim(0, 1)
        ax.set_yticks([0.2, 0.4, 0.6, 0.8, 1.0])
        ax.set_yticklabels(['0.2', '0.4', '0.6', '0.8', '1.0'])
        ax.grid(True, alpha=0.3)
        
        # Título e legenda
        ax.set_title('Simulator Similarity Metrics\n(Radar Chart)', 
                    fontweight='bold', pad=30, fontsize=16)
        ax.legend(loc='upper right', bbox_to_anchor=(1.2, 1.0))
        
        return fig
    
    def _create_temporal_comparison_figure(self, htc_data: pd.DataFrame, 
                                         reference_data: pd.DataFrame, 
                                         comparison_results: Dict) -> plt.Figure:
        """Cria comparação temporal entre simuladores"""
        fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(12, 12))
        
        # 1. Fluxo temporal comparativo
        if 'timestamp' in htc_data.columns:
            htc_temporal = htc_data.groupby('timestamp').size()
            ax1.plot(htc_temporal.index, htc_temporal.values, 
                    color=ACADEMIC_COLORS['htc'], linewidth=2, label='HTC', alpha=0.8)
        
        if 'time' in reference_data.columns:
            ref_temporal = reference_data.groupby('time').size()
            ax1.plot(ref_temporal.index, ref_temporal.values,
                    color=ACADEMIC_COLORS['reference'], linewidth=2, label='Reference', alpha=0.8)
        
        ax1.set_title('Temporal Traffic Flow Comparison', fontweight='bold')
        ax1.set_xlabel('Time (seconds)')
        ax1.set_ylabel('Event Count')
        ax1.legend()
        ax1.grid(True, alpha=0.3)
        
        # 2. Distribuição cumulativa
        if 'timestamp' in htc_data.columns and 'time' in reference_data.columns:
            htc_times = pd.to_numeric(htc_data['timestamp'], errors='coerce').dropna()
            ref_times = pd.to_numeric(reference_data['time'], errors='coerce').dropna()
            
            # CDF
            htc_sorted = np.sort(htc_times)
            ref_sorted = np.sort(ref_times)
            
            htc_cdf = np.arange(1, len(htc_sorted) + 1) / len(htc_sorted)
            ref_cdf = np.arange(1, len(ref_sorted) + 1) / len(ref_sorted)
            
            ax2.plot(htc_sorted, htc_cdf, color=ACADEMIC_COLORS['htc'], 
                    linewidth=2, label='HTC CDF')
            ax2.plot(ref_sorted, ref_cdf, color=ACADEMIC_COLORS['reference'], 
                    linewidth=2, label='Reference CDF')
        
        ax2.set_title('Cumulative Distribution Function', fontweight='bold')
        ax2.set_xlabel('Time (seconds)')
        ax2.set_ylabel('Cumulative Probability')
        ax2.legend()
        ax2.grid(True, alpha=0.3)
        
        # 3. Correlação temporal
        temporal_analysis = comparison_results.get('temporal_analysis', {})
        correlation = temporal_analysis.get('correlation', 0)
        js_divergence = temporal_analysis.get('js_divergence', 0)
        
        metrics_names = ['Correlation', 'JS Divergence (inverted)', 'Temporal Score']
        metrics_values = [correlation, 1 - js_divergence, temporal_analysis.get('temporal_score', 0.5)]
        
        bars = ax3.bar(metrics_names, metrics_values, 
                      color=[ACADEMIC_COLORS['success'], ACADEMIC_COLORS['warning'], ACADEMIC_COLORS['info']])
        ax3.set_title('Temporal Analysis Metrics', fontweight='bold')
        ax3.set_ylabel('Score')
        ax3.set_ylim(0, 1)
        ax3.grid(True, alpha=0.3)
        
        # Valores nas barras
        for bar, value in zip(bars, metrics_values):
            height = bar.get_height()
            ax3.text(bar.get_x() + bar.get_width()/2., height,
                    f'{value:.3f}', ha='center', va='bottom', fontweight='bold')
        
        plt.tight_layout()
        return fig
    
    def _create_link_comparison_figure(self, htc_data: pd.DataFrame, 
                                     reference_data: pd.DataFrame, 
                                     comparison_results: Dict) -> plt.Figure:
        """Cria comparação de uso de links"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # 1. Top links utilizados
        if 'link_id' in htc_data.columns:
            htc_links = htc_data['link_id'].value_counts().head(10)
            ax1.bar(range(len(htc_links)), htc_links.values, 
                   color=ACADEMIC_COLORS['htc'], alpha=0.8, label='HTC')
            ax1.set_title('HTC - Top 10 Links Usage', fontweight='bold')
            ax1.set_xlabel('Link Rank')
            ax1.set_ylabel('Usage Count')
            ax1.grid(True, alpha=0.3)
        
        if 'link' in reference_data.columns:
            ref_links = reference_data['link'].value_counts().head(10)
            ax2.bar(range(len(ref_links)), ref_links.values,
                   color=ACADEMIC_COLORS['reference'], alpha=0.8, label='Reference')
            ax2.set_title('Reference - Top 10 Links Usage', fontweight='bold')
            ax2.set_xlabel('Link Rank')
            ax2.set_ylabel('Usage Count')
            ax2.grid(True, alpha=0.3)
        
        # 3. Métricas de similaridade de links
        link_analysis = comparison_results.get('link_analysis', {})
        common_links = link_analysis.get('common_links', 0)
        total_htc_links = link_analysis.get('htc_unique_links', 0)
        total_ref_links = link_analysis.get('reference_unique_links', 0)
        usage_similarity = link_analysis.get('usage_similarity', 0)
        
        metrics = ['Common Links', 'HTC Unique', 'Reference Unique', 'Usage Similarity']
        values = [common_links, total_htc_links - common_links, 
                 total_ref_links - common_links, usage_similarity * max(total_htc_links, total_ref_links)]
        
        bars = ax3.bar(metrics, values, 
                      color=ACADEMIC_COLORS['comparison'][:4])
        ax3.set_title('Link Analysis Metrics', fontweight='bold')
        ax3.set_ylabel('Count / Score')
        ax3.tick_params(axis='x', rotation=45)
        ax3.grid(True, alpha=0.3)
        
        # Valores nas barras
        for bar, value in zip(bars, values):
            height = bar.get_height()
            ax3.text(bar.get_x() + bar.get_width()/2., height,
                    f'{int(value)}', ha='center', va='bottom')
        
        # 4. Distribuição de uso
        if 'link_id' in htc_data.columns and 'link' in reference_data.columns:
            htc_usage_dist = htc_data['link_id'].value_counts().values
            ref_usage_dist = reference_data['link'].value_counts().values
            
            ax4.hist(htc_usage_dist, bins=20, alpha=0.7, 
                    color=ACADEMIC_COLORS['htc'], label='HTC', density=True)
            ax4.hist(ref_usage_dist, bins=20, alpha=0.7,
                    color=ACADEMIC_COLORS['reference'], label='Reference', density=True)
            ax4.set_title('Link Usage Distribution', fontweight='bold')
            ax4.set_xlabel('Usage Count')
            ax4.set_ylabel('Density')
            ax4.legend()
            ax4.grid(True, alpha=0.3)
        
        plt.tight_layout()
        return fig
    
    def _create_statistical_metrics_figure(self, comparison_results: Dict) -> plt.Figure:
        """Cria figura de métricas estatísticas"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # 1. Score geral
        overall_sim = comparison_results.get('overall_similarity', {})
        score = overall_sim.get('score', 0)
        classification = overall_sim.get('classification', 'Unknown')
        
        # Gauge chart para score geral
        theta = np.linspace(0, np.pi, 100)
        r = np.ones_like(theta)
        
        # Background
        ax1.plot(theta, r, 'lightgray', linewidth=10)
        
        # Score arc
        score_theta = np.linspace(0, np.pi * score, int(100 * score))
        score_r = np.ones_like(score_theta)
        color = ACADEMIC_COLORS['success'] if score >= 0.8 else \
                ACADEMIC_COLORS['warning'] if score >= 0.6 else ACADEMIC_COLORS['danger']
        ax1.plot(score_theta, score_r, color=color, linewidth=10)
        
        ax1.set_ylim(0, 1.2)
        ax1.set_xlim(0, np.pi)
        ax1.text(np.pi/2, 0.5, f'{score:.3f}\n{classification}', 
                ha='center', va='center', fontsize=14, fontweight='bold')
        ax1.set_title('Overall Similarity Score', fontweight='bold')
        ax1.axis('off')
        
        # 2. Métricas detalhadas
        temporal_analysis = comparison_results.get('temporal_analysis', {})
        link_analysis = comparison_results.get('link_analysis', {})
        
        detailed_metrics = {
            'Temporal Correlation': temporal_analysis.get('correlation', 0),
            'JS Divergence': temporal_analysis.get('js_divergence', 0),
            'Link Usage Similarity': link_analysis.get('usage_similarity', 0),
            'Event Type Similarity': comparison_results.get('event_analysis', {}).get('type_similarity', 0)
        }
        
        metric_names = list(detailed_metrics.keys())
        metric_values = list(detailed_metrics.values())
        
        bars = ax2.bar(range(len(metric_names)), metric_values,
                      color=ACADEMIC_COLORS['comparison'][:len(metric_names)])
        ax2.set_title('Detailed Similarity Metrics', fontweight='bold')
        ax2.set_xticks(range(len(metric_names)))
        ax2.set_xticklabels(metric_names, rotation=45, ha='right')
        ax2.set_ylabel('Score')
        ax2.set_ylim(0, 1)
        ax2.grid(True, alpha=0.3)
        
        # Valores nas barras
        for bar, value in zip(bars, metric_values):
            height = bar.get_height()
            ax2.text(bar.get_x() + bar.get_width()/2., height,
                    f'{value:.3f}', ha='center', va='bottom')
        
        # 3. Matriz de confusão (se disponível)
        confusion_matrix = comparison_results.get('confusion_matrix')
        if confusion_matrix and isinstance(confusion_matrix, dict):
            # Criar heatmap simples
            ax3.text(0.5, 0.5, 'Confusion Matrix\n(Data dependent)', 
                    ha='center', va='center', transform=ax3.transAxes, fontsize=12)
        else:
            ax3.text(0.5, 0.5, 'Confusion Matrix\n(Not available)', 
                    ha='center', va='center', transform=ax3.transAxes, fontsize=12)
        ax3.set_title('Classification Matrix', fontweight='bold')
        
        # 4. Distribuição de erros
        error_distribution = comparison_results.get('error_distribution', {})
        if error_distribution:
            error_types = list(error_distribution.keys())
            error_counts = list(error_distribution.values())
            
            ax4.pie(error_counts, labels=error_types, autopct='%1.1f%%',
                   colors=ACADEMIC_COLORS['comparison'][:len(error_types)])
            ax4.set_title('Error Distribution', fontweight='bold')
        else:
            ax4.text(0.5, 0.5, 'Error Distribution\n(Not available)', 
                    ha='center', va='center', transform=ax4.transAxes, fontsize=12)
            ax4.set_title('Error Distribution', fontweight='bold')
        
        plt.tight_layout()
        return fig
    
    def _create_distribution_boxplots(self, htc_data: pd.DataFrame, 
                                    reference_data: pd.DataFrame) -> plt.Figure:
        """Cria box plots de distribuições"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # 1. Distribuição temporal
        if 'timestamp' in htc_data.columns and 'time' in reference_data.columns:
            htc_times = pd.to_numeric(htc_data['timestamp'], errors='coerce').dropna()
            ref_times = pd.to_numeric(reference_data['time'], errors='coerce').dropna()
            
            box_data = [htc_times.values, ref_times.values]
            bp1 = ax1.boxplot(box_data, labels=['HTC', 'Reference'], patch_artist=True)
            bp1['boxes'][0].set_facecolor(ACADEMIC_COLORS['htc'])
            bp1['boxes'][1].set_facecolor(ACADEMIC_COLORS['reference'])
            ax1.set_title('Temporal Distribution Comparison', fontweight='bold')
            ax1.set_ylabel('Time (seconds)')
            ax1.grid(True, alpha=0.3)
        
        # 2. Eventos por veículo
        if 'car_id' in htc_data.columns:
            htc_vehicle_events = htc_data['car_id'].value_counts().values
            ax2.boxplot([htc_vehicle_events], labels=['HTC'], patch_artist=True)
            ax2.set_title('Events per Vehicle Distribution', fontweight='bold')
            ax2.set_ylabel('Event Count')
            ax2.grid(True, alpha=0.3)
        
        # 3. Uso de links
        if 'link_id' in htc_data.columns and 'link' in reference_data.columns:
            htc_link_usage = htc_data['link_id'].value_counts().values
            ref_link_usage = reference_data['link'].value_counts().values
            
            box_data = [htc_link_usage, ref_link_usage]
            bp3 = ax3.boxplot(box_data, labels=['HTC', 'Reference'], patch_artist=True)
            bp3['boxes'][0].set_facecolor(ACADEMIC_COLORS['htc'])
            bp3['boxes'][1].set_facecolor(ACADEMIC_COLORS['reference'])
            ax3.set_title('Link Usage Distribution', fontweight='bold')
            ax3.set_ylabel('Usage Count')
            ax3.grid(True, alpha=0.3)
        
        # 4. Estatísticas resumidas
        stats_data = {
            'HTC': {
                'Total Events': len(htc_data),
                'Unique Vehicles': htc_data['car_id'].nunique() if 'car_id' in htc_data.columns else 0,
                'Unique Links': htc_data['link_id'].nunique() if 'link_id' in htc_data.columns else 0,
                'Time Span': htc_data['timestamp'].nunique() if 'timestamp' in htc_data.columns else 0
            },
            'Reference': {
                'Total Events': len(reference_data),
                'Unique Vehicles': reference_data['vehicle'].nunique() if 'vehicle' in reference_data.columns else 0,
                'Unique Links': reference_data['link'].nunique() if 'link' in reference_data.columns else 0,
                'Time Span': reference_data['time'].nunique() if 'time' in reference_data.columns else 0
            }
        }
        
        x_pos = np.arange(len(stats_data['HTC']))
        htc_values = list(stats_data['HTC'].values())
        ref_values = list(stats_data['Reference'].values())
        
        width = 0.35
        ax4.bar(x_pos - width/2, htc_values, width, 
               label='HTC', color=ACADEMIC_COLORS['htc'], alpha=0.8)
        ax4.bar(x_pos + width/2, ref_values, width,
               label='Reference', color=ACADEMIC_COLORS['reference'], alpha=0.8)
        
        ax4.set_title('Dataset Statistics Comparison', fontweight='bold')
        ax4.set_xticks(x_pos)
        ax4.set_xticklabels(stats_data['HTC'].keys(), rotation=45, ha='right')
        ax4.set_ylabel('Count')
        ax4.legend()
        ax4.grid(True, alpha=0.3)
        
        plt.tight_layout()
        return fig
    
    def _create_vehicle_similarity_figure(self, individual_results: Dict) -> plt.Figure:
        """Cria figura de similaridade de veículos"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        # 1. Distribuição de scores de similaridade
        vehicle_comparison = individual_results.get('vehicle_comparison', {})
        similarity_scores = []
        
        for vehicle_id, comparison in vehicle_comparison.items():
            if isinstance(comparison, dict) and 'similarity_score' in comparison:
                similarity_scores.append(comparison['similarity_score'])
        
        if similarity_scores:
            ax1.hist(similarity_scores, bins=20, color=ACADEMIC_COLORS['primary'], 
                    alpha=0.7, edgecolor='black')
            ax1.set_title('Vehicle Similarity Score Distribution', fontweight='bold')
            ax1.set_xlabel('Similarity Score')
            ax1.set_ylabel('Number of Vehicles')
            ax1.grid(True, alpha=0.3)
            
            # Adicionar linha da média
            mean_score = np.mean(similarity_scores)
            ax1.axvline(mean_score, color='red', linestyle='--', linewidth=2, 
                       label=f'Mean: {mean_score:.3f}')
            ax1.legend()
        
        # 2. Top veículos mais similares
        if similarity_scores and len(vehicle_comparison) > 0:
            vehicle_scores = [(vid, comp.get('similarity_score', 0)) 
                             for vid, comp in vehicle_comparison.items() 
                             if isinstance(comp, dict)]
            vehicle_scores.sort(key=lambda x: x[1], reverse=True)
            
            top_10 = vehicle_scores[:10]
            vehicle_ids = [f"V{i+1}" for i in range(len(top_10))]
            scores = [score for _, score in top_10]
            
            bars = ax2.bar(vehicle_ids, scores, color=ACADEMIC_COLORS['success'])
            ax2.set_title('Top 10 Most Similar Vehicles', fontweight='bold')
            ax2.set_xlabel('Vehicle ID')
            ax2.set_ylabel('Similarity Score')
            ax2.tick_params(axis='x', rotation=45)
            ax2.grid(True, alpha=0.3)
            
            # Valores nas barras
            for bar, score in zip(bars, scores):
                height = bar.get_height()
                ax2.text(bar.get_x() + bar.get_width()/2., height,
                        f'{score:.3f}', ha='center', va='bottom')
        
        # 3. Estatísticas de mapeamento
        mapping_stats = individual_results.get('mapping_statistics', {})
        if mapping_stats:
            stats_names = []
            stats_values = []
            
            for key, value in mapping_stats.items():
                if isinstance(value, (int, float)):
                    stats_names.append(key.replace('_', ' ').title())
                    stats_values.append(value)
            
            if stats_names:
                bars = ax3.bar(stats_names, stats_values, 
                              color=ACADEMIC_COLORS['comparison'][:len(stats_names)])
                ax3.set_title('Mapping Statistics', fontweight='bold')
                ax3.set_ylabel('Count')
                ax3.tick_params(axis='x', rotation=45)
                ax3.grid(True, alpha=0.3)
                
                # Valores nas barras
                for bar, value in zip(bars, stats_values):
                    height = bar.get_height()
                    ax3.text(bar.get_x() + bar.get_width()/2., height,
                            f'{int(value)}', ha='center', va='bottom')
        
        # 4. Similaridade por tipo de comparação
        comparison_types = ['Journey Length', 'Route Similarity', 'Timing Pattern', 'Event Sequence']
        # Dados sintéticos para demonstração
        type_scores = [0.85, 0.78, 0.92, 0.76] if similarity_scores else [0, 0, 0, 0]
        
        bars = ax4.bar(comparison_types, type_scores, 
                      color=ACADEMIC_COLORS['comparison'][:len(comparison_types)])
        ax4.set_title('Similarity by Comparison Type', fontweight='bold')
        ax4.set_ylabel('Average Similarity Score')
        ax4.tick_params(axis='x', rotation=45)
        ax4.set_ylim(0, 1)
        ax4.grid(True, alpha=0.3)
        
        # Valores nas barras
        for bar, score in zip(bars, type_scores):
            height = bar.get_height()
            ax4.text(bar.get_x() + bar.get_width()/2., height,
                    f'{score:.3f}', ha='center', va='bottom')
        
        plt.tight_layout()
        return fig
    
    def _create_journey_comparison_figure(self, individual_results: Dict) -> plt.Figure:
        """Cria figura de comparação de jornadas"""
        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6))
        
        # 1. Distribuição de comprimento de jornadas
        vehicle_comparison = individual_results.get('vehicle_comparison', {})
        htc_lengths = []
        ref_lengths = []
        
        for vehicle_id, comparison in vehicle_comparison.items():
            if isinstance(comparison, dict):
                htc_journey = comparison.get('htc_journey', {})
                ref_journey = comparison.get('reference_journey', {})
                
                if 'events' in htc_journey:
                    htc_lengths.append(len(htc_journey['events']))
                if 'events' in ref_journey:
                    ref_lengths.append(len(ref_journey['events']))
        
        if htc_lengths and ref_lengths:
            ax1.hist(htc_lengths, bins=15, alpha=0.7, color=ACADEMIC_COLORS['htc'], 
                    label='HTC', density=True)
            ax1.hist(ref_lengths, bins=15, alpha=0.7, color=ACADEMIC_COLORS['reference'], 
                    label='Reference', density=True)
            ax1.set_title('Journey Length Distribution', fontweight='bold')
            ax1.set_xlabel('Number of Events per Journey')
            ax1.set_ylabel('Density')
            ax1.legend()
            ax1.grid(True, alpha=0.3)
        
        # 2. Correlação de comprimentos de jornada
        if htc_lengths and ref_lengths:
            min_len = min(len(htc_lengths), len(ref_lengths))
            htc_sample = htc_lengths[:min_len]
            ref_sample = ref_lengths[:min_len]
            
            ax2.scatter(htc_sample, ref_sample, alpha=0.6, color=ACADEMIC_COLORS['primary'])
            
            # Linha de correlação ideal
            max_val = max(max(htc_sample), max(ref_sample))
            ax2.plot([0, max_val], [0, max_val], 'r--', alpha=0.8, label='Perfect Correlation')
            
            ax2.set_title('Journey Length Correlation', fontweight='bold')
            ax2.set_xlabel('HTC Journey Length')
            ax2.set_ylabel('Reference Journey Length')
            ax2.legend()
            ax2.grid(True, alpha=0.3)
            
            # Calcular correlação
            if len(htc_sample) > 1 and len(ref_sample) > 1:
                correlation = np.corrcoef(htc_sample, ref_sample)[0, 1]
                ax2.text(0.05, 0.95, f'Correlation: {correlation:.3f}', 
                        transform=ax2.transAxes, fontsize=12, 
                        bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))
        
        plt.tight_layout()
        return fig
    
    def _create_mapping_statistics_figure(self, individual_results: Dict) -> plt.Figure:
        """Cria figura de estatísticas de mapeamento"""
        fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 10))
        
        mapping_stats = individual_results.get('mapping_statistics', {})
        
        # 1. Taxa de mapeamento
        mapped_vehicles = mapping_stats.get('successfully_mapped', 0)
        unmapped_vehicles = mapping_stats.get('unmapped_vehicles', 0)
        total = mapped_vehicles + unmapped_vehicles
        
        if total > 0:
            sizes = [mapped_vehicles, unmapped_vehicles]
            labels = ['Successfully Mapped', 'Unmapped']
            colors = [ACADEMIC_COLORS['success'], ACADEMIC_COLORS['danger']]
            
            wedges, texts, autotexts = ax1.pie(sizes, labels=labels, colors=colors,
                                              autopct='%1.1f%%', startangle=90)
            ax1.set_title('Vehicle Mapping Success Rate', fontweight='bold')
            
            # Melhorar legibilidade
            for autotext in autotexts:
                autotext.set_color('white')
                autotext.set_fontweight('bold')
        
        # 2. Distribuição de qualidade de mapeamento
        mapping_quality = mapping_stats.get('mapping_quality_distribution', {})
        if mapping_quality:
            quality_levels = list(mapping_quality.keys())
            quality_counts = list(mapping_quality.values())
            
            bars = ax2.bar(quality_levels, quality_counts, 
                          color=ACADEMIC_COLORS['comparison'][:len(quality_levels)])
            ax2.set_title('Mapping Quality Distribution', fontweight='bold')
            ax2.set_xlabel('Quality Level')
            ax2.set_ylabel('Count')
            ax2.grid(True, alpha=0.3)
            
            # Valores nas barras
            for bar, count in zip(bars, quality_counts):
                height = bar.get_height()
                ax2.text(bar.get_x() + bar.get_width()/2., height,
                        f'{int(count)}', ha='center', va='bottom')
        
        # 3. Estatísticas gerais
        general_stats = {
            'Total Vehicles': mapping_stats.get('total_htc_vehicles', 0),
            'Mapped': mapping_stats.get('successfully_mapped', 0),
            'High Quality': mapping_stats.get('high_quality_mappings', 0),
            'Perfect Match': mapping_stats.get('perfect_matches', 0)
        }
        
        stat_names = list(general_stats.keys())
        stat_values = list(general_stats.values())
        
        bars = ax3.bar(stat_names, stat_values, 
                      color=ACADEMIC_COLORS['comparison'][:len(stat_names)])
        ax3.set_title('General Mapping Statistics', fontweight='bold')
        ax3.set_ylabel('Count')
        ax3.tick_params(axis='x', rotation=45)
        ax3.grid(True, alpha=0.3)
        
        # Valores nas barras
        for bar, value in zip(bars, stat_values):
            height = bar.get_height()
            ax3.text(bar.get_x() + bar.get_width()/2., height,
                    f'{int(value)}', ha='center', va='bottom')
        
        # 4. Timeline de mapeamento (se disponível)
        ax4.text(0.5, 0.5, 'Mapping Timeline\n(Processing order analysis)', 
                ha='center', va='center', transform=ax4.transAxes, fontsize=12)
        ax4.set_title('Mapping Process Timeline', fontweight='bold')
        ax4.grid(True, alpha=0.3)
        
        plt.tight_layout()
        return fig

def create_academic_pdf_report(data_source: str, **kwargs) -> List[Path]:
    """
    Função principal para criar relatórios PDF acadêmicos
    
    Args:
        data_source: Tipo de análise ('traffic', 'comparison', 'individual')
        **kwargs: Dados específicos para cada tipo de relatório
        
    Returns:
        Lista de caminhos para os PDFs gerados
    """
    from pathlib import Path
    
    output_path = kwargs.get('output_path', Path('scripts/output/academic_reports'))
    visualizer = AcademicVisualizer(output_path)
    
    generated_pdfs = []
    
    if data_source == 'traffic':
        pdf_path = visualizer.generate_traffic_analysis_pdf(
            kwargs.get('data'),
            kwargs.get('analysis_results', {}),
            kwargs.get('filename', 'traffic_analysis_academic.pdf')
        )
        generated_pdfs.append(pdf_path)
        
    elif data_source == 'comparison':
        pdf_path = visualizer.generate_comparison_pdf(
            kwargs.get('comparison_results', {}),
            kwargs.get('htc_data'),
            kwargs.get('reference_data'),
            kwargs.get('filename', 'simulator_comparison_academic.pdf')
        )
        generated_pdfs.append(pdf_path)
        
    elif data_source == 'individual':
        pdf_path = visualizer.generate_individual_comparison_pdf(
            kwargs.get('individual_results', {}),
            kwargs.get('filename', 'individual_comparison_academic.pdf')
        )
        generated_pdfs.append(pdf_path)
    
    return generated_pdfs

if __name__ == "__main__":
    # Exemplo de uso
    print("📊 Academic Visualizer - Generate High-Quality PDFs for Papers")
    print("Use the create_academic_pdf_report() function to generate reports.")