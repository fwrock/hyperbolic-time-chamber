#!/usr/bin/env python3
"""
Script principal para executar análise de tráfego
Com gerenciamento correto de imports
"""

import sys
import os
from pathlib import Path
import pandas as pd

# Adiciona o diretório scripts ao PYTHONPATH
SCRIPT_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(SCRIPT_DIR))

import argparse
import logging
from datetime import datetime

# Agora podemos fazer imports absolutos
from config import CASSANDRA_CONFIG, OUTPUT_PATH, LOGGING_CONFIG
from data_sources.cassandra_source import CassandraDataSource
from data_sources.file_sources import CSVDataSource, DataSourceFactory
from analysis.traffic_analyzer import TrafficAnalyzer
from visualization.traffic_viz import TrafficVisualizer
from reports.report_generator import TrafficReportGenerator

def setup_logging():
    """Configura o sistema de logging"""
    logging.basicConfig(**LOGGING_CONFIG)
    return logging.getLogger(__name__)

def run_cassandra_analysis(limit: int = None):
    """Executa análise usando dados do Cassandra"""
    logger = logging.getLogger(__name__)
    
    try:
        # Conectar ao Cassandra
        logger.info("🔌 Conectando ao Cassandra...")
        cassandra_source = CassandraDataSource()
        
        # Carregar dados
        logger.info(f"📊 Carregando dados (limite: {limit})...")
        data = cassandra_source.get_vehicle_flow_data(limit=limit)
        
        if data.empty:
            logger.warning("⚠️ Nenhum dado encontrado no Cassandra")
            return
        
        logger.info(f"✅ Carregados {len(data)} registros")
        
        # Executar análise
        run_analysis(data, "cassandra", logger)
        
    except Exception as e:
        logger.error(f"❌ Erro na análise do Cassandra: {e}")
        raise

def run_csv_analysis(file_path: str):
    """Executa análise usando dados de arquivo CSV"""
    logger = logging.getLogger(__name__)
    
    try:
        # Carregar dados do CSV
        logger.info(f"📁 Carregando dados do CSV: {file_path}")
        
        # Converter caminho relativo para absoluto se necessário
        if not os.path.isabs(file_path):
            file_path = os.path.join(SCRIPT_DIR.parent, file_path)
        
        # Se for um arquivo específico, carregar diretamente
        if os.path.isfile(file_path):
            data = pd.read_csv(file_path)
            logger.info(f"✅ Carregados {len(data)} registros do arquivo CSV")
        else:
            # Se for um diretório, usar o método original
            csv_source = CSVDataSource(file_path)
            data = csv_source.load_vehicle_flow_data()
        
        if data.empty:
            logger.warning("⚠️ Nenhum dado encontrado no arquivo CSV")
            return
        
        logger.info(f"✅ Carregados {len(data)} registros do CSV")
        
        # Executar análise
        run_analysis(data, "csv", logger)
        
    except Exception as e:
        logger.error(f"❌ Erro na análise do CSV: {e}")
        raise

def run_analysis(data, source_type: str, logger):
    """Executa a análise completa dos dados"""
    
    try:
        # Análise dos dados
        logger.info("🔍 Iniciando análise de tráfego...")
        analyzer = TrafficAnalyzer()
        
        # Executar análises
        analysis_results = analyzer.analyze_traffic_flow(data)
        
        # Visualizações
        logger.info("📊 Gerando visualizações...")
        visualizer = TrafficVisualizer(data)
        
        # Criar visualizações padrão
        timeline_fig = visualizer.create_traffic_flow_timeline()
        distribution_fig = visualizer.create_vehicle_distribution()
        patterns_fig = visualizer.create_mobility_patterns()
        
        # Gerar relatórios
        logger.info("📝 Gerando relatórios...")
        report_generator = TrafficReportGenerator()
        
        # Relatório JSON detalhado
        json_report_path = report_generator.generate_detailed_report(
            analysis_results, 
            OUTPUT_PATH / "traffic_analysis_report.json"
        )
        
        # Relatório Markdown
        md_report_path = report_generator.generate_summary_report(
            analysis_results,
            OUTPUT_PATH / "traffic_summary.md"
        )
        
        # Dashboard HTML
        dashboard_path = report_generator.generate_html_dashboard(
            analysis_results,
            [timeline_fig, distribution_fig, patterns_fig],
            OUTPUT_PATH / "traffic_dashboard.html"
        )
        
        # 🆕 GERAR PDF ACADÊMICO
        logger.info("📄 Gerando PDF acadêmico para artigo...")
        try:
            from visualization.academic_viz import create_academic_pdf_report
            
            academic_pdfs = create_academic_pdf_report(
                'traffic',
                data=data,
                analysis_results=analysis_results,
                output_path=OUTPUT_PATH / "academic_reports",
                filename="traffic_analysis_academic.pdf"
            )
            
            for pdf_path in academic_pdfs:
                logger.info(f"📄 PDF acadêmico gerado: {pdf_path}")
                
        except ImportError as e:
            logger.warning(f"⚠️ Dependências para PDF não encontradas: {e}")
            logger.info("💡 Para gerar PDFs, instale: pip install matplotlib seaborn plotly kaleido")
        except Exception as e:
            logger.warning(f"⚠️ Erro ao gerar PDF acadêmico: {e}")
        
        # Log dos arquivos gerados
        logger.info("✅ Análise concluída! Arquivos gerados:")
        logger.info(f"  📋 Relatório detalhado: {json_report_path}")
        logger.info(f"  📝 Resumo: {md_report_path}")
        logger.info(f"  📊 Dashboard: {dashboard_path}")
        
    except Exception as e:
        logger.error(f"❌ Erro durante análise: {e}")
        raise
        
        # Análises básicas
        summary = analyzer.basic_statistics(data)
        logger.info(f"📈 Estatísticas básicas calculadas")
        
        # Análise temporal
        temporal = analyzer.temporal_analysis(data)
        logger.info(f"⏰ Análise temporal concluída")
        
        # Análise espacial (se houver dados de localização)
        spatial = None
        if 'latitude' in data.columns and 'longitude' in data.columns:
            spatial = analyzer.spatial_analysis(data)
            logger.info(f"🗺️ Análise espacial concluída")
        
        # Visualizações
        logger.info("📊 Gerando visualizações...")
        visualizer = TrafficVisualizer(data)
        
        # Gráficos temporais
        logger.info("📈 Criando gráficos temporais...")
        
        # Gráficos de estatísticas  
        logger.info("📊 Criando gráficos de distribuição...")
        
        # Mapa (se dados espaciais disponíveis)
        if spatial and 'latitude' in data.columns:
            logger.info("🗺️ Criando mapa de tráfego...")
        
        logger.info("✅ Visualizações salvas em: " + str(OUTPUT_PATH))
        
        # Gerar relatório
        logger.info("📋 Gerando relatório...")
        
        report_data = {
            'summary': summary,
            'temporal': temporal,
            'spatial': spatial,
            'source_type': source_type,
            'timestamp': datetime.now().isoformat(),
            'total_records': len(data)
        }
        
        # Salvar dados do relatório em JSON
        report_file = OUTPUT_PATH / "reports" / f"traffic_analysis_report_{source_type}.json"
        report_file.parent.mkdir(parents=True, exist_ok=True)
        
        # Função para converter dados para JSON serializable
        def json_serializer(obj):
            if hasattr(obj, 'isoformat'):
                return obj.isoformat()
            elif hasattr(obj, 'item'):
                return obj.item()
            elif hasattr(obj, 'tolist'):
                return obj.tolist()
            return str(obj)
        
        with open(report_file, 'w') as f:
            import json
            json.dump(report_data, f, indent=2, default=json_serializer)
        
        logger.info("✅ Relatório gerado com sucesso!")
        
        # Estatísticas finais
        logger.info("="*60)
        logger.info("🎯 ANÁLISE CONCLUÍDA COM SUCESSO!")
        logger.info(f"📊 Total de registros: {len(data)}")
        logger.info(f"📈 Fonte dos dados: {source_type.upper()}")
        logger.info(f"📁 Outputs salvos em: {OUTPUT_PATH}")
        logger.info("="*60)
        
    except Exception as e:
        logger.error(f"❌ Erro durante análise: {e}")
        raise

def main():
    """Função principal"""
    parser = argparse.ArgumentParser(description="Traffic Analysis System")
    parser.add_argument('source', choices=['cassandra', 'csv'], help='Fonte dos dados')
    parser.add_argument('--limit', type=int, help='Limite de registros (Cassandra)')
    parser.add_argument('--file', type=str, help='Arquivo CSV (para source=csv)')
    
    args = parser.parse_args()
    
    # Setup logging
    logger = setup_logging()
    logger.info("🚀 Iniciando Traffic Analysis System...")
    
    # Garantir que diretórios existam
    OUTPUT_PATH.mkdir(parents=True, exist_ok=True)
    
    try:
        if args.source == 'cassandra':
            run_cassandra_analysis(args.limit)
        elif args.source == 'csv':
            if not args.file:
                logger.error("❌ --file é obrigatório para source=csv")
                return 1
            run_csv_analysis(args.file)
            
        return 0
        
    except Exception as e:
        logger.error(f"💥 Falha na execução: {e}")
        return 1

if __name__ == "__main__":
    exit(main())