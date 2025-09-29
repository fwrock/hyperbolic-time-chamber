#!/usr/bin/env python3
"""
Script para comparar resultados entre o simulador HTC e um simulador de referência
"""

import sys
import os
from pathlib import Path
import pandas as pd
import argparse
import logging
from datetime import datetime

# Adiciona o diretório scripts ao PYTHONPATH
SCRIPT_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(SCRIPT_DIR))

from config import OUTPUT_PATH, LOGGING_CONFIG
from data_sources.cassandra_source import CassandraDataSource
from data_sources.file_sources import CSVDataSource
from comparison.reference_parser import ReferenceSimulatorParser
from comparison.simulator_comparator import SimulatorComparator

def setup_logging():
    """Configura o sistema de logging"""
    logging.basicConfig(**LOGGING_CONFIG)
    return logging.getLogger(__name__)

def load_htc_data(source_type: str, **kwargs) -> pd.DataFrame:
    """
    Carrega dados do simulador HTC
    
    Args:
        source_type: Tipo da fonte (cassandra, csv)
        **kwargs: Argumentos específicos da fonte
        
    Returns:
        DataFrame com dados do HTC
    """
    logger = logging.getLogger(__name__)
    
    if source_type == 'cassandra':
        logger.info("🔌 Carregando dados do HTC via Cassandra...")
        cassandra_source = CassandraDataSource()
        data = cassandra_source.get_vehicle_flow_data(limit=kwargs.get('limit'))
        
    elif source_type == 'csv':
        logger.info(f"📁 Carregando dados do HTC via CSV...")
        file_path = kwargs.get('file_path')
        if not file_path or not os.path.isfile(file_path):
            logger.error(f"❌ Arquivo CSV não encontrado: {file_path}")
            return pd.DataFrame()
        data = pd.read_csv(file_path)
        
    else:
        logger.error(f"❌ Tipo de fonte não suportado: {source_type}")
        return pd.DataFrame()
    
    if not data.empty:
        logger.info(f"✅ HTC: Carregados {len(data)} registros, {data['car_id'].nunique()} veículos")
    else:
        logger.warning("⚠️ Nenhum dado encontrado no HTC")
    
    return data

def load_reference_data(xml_file_path: str) -> pd.DataFrame:
    """
    Carrega dados do simulador de referência
    
    Args:
        xml_file_path: Caminho para o arquivo XML de eventos
        
    Returns:
        DataFrame com dados da referência
    """
    logger = logging.getLogger(__name__)
    
    logger.info(f"📄 Carregando dados do simulador de referência: {xml_file_path}")
    
    if not os.path.isfile(xml_file_path):
        logger.error(f"❌ Arquivo XML não encontrado: {xml_file_path}")
        return pd.DataFrame()
    
    parser = ReferenceSimulatorParser(xml_file_path)
    data = parser.get_traffic_flow_events()
    
    if not data.empty:
        logger.info(f"✅ Referência: Carregados {len(data)} eventos, {data['car_id'].nunique()} veículos")
    else:
        logger.warning("⚠️ Nenhum evento de tráfego encontrado na referência")
    
    return data

def run_comparison(htc_data: pd.DataFrame, ref_data: pd.DataFrame, output_path: Path) -> dict:
    """
    Executa a comparação entre os simuladores
    
    Args:
        htc_data: Dados do HTC
        ref_data: Dados de referência
        output_path: Caminho para salvar resultados
        
    Returns:
        Resultados da comparação
    """
    logger = logging.getLogger(__name__)
    
    if htc_data.empty or ref_data.empty:
        logger.error("❌ Um ou ambos os datasets estão vazios")
        return {}
    
    logger.info("🔬 Iniciando comparação detalhada...")
    
    # Create comparison output directory
    comparison_output = output_path / "comparison"
    comparison_output.mkdir(parents=True, exist_ok=True)
    
    # Initialize comparator
    comparator = SimulatorComparator(comparison_output)
    
    # Run comparison
    results = comparator.compare_traffic_flows(htc_data, ref_data)
    
    # Generate report
    summary = comparator.generate_comparison_report()
    
    # Create visualizations
    comparator.create_comparison_visualizations()
    
    # 🆕 GERAR PDF ACADÊMICO
    logger.info("📄 Gerando PDF acadêmico para artigo...")
    try:
        from visualization.academic_viz import create_academic_pdf_report
        
        academic_pdfs = create_academic_pdf_report(
            'comparison',
            comparison_results=results,
            htc_data=htc_data,
            reference_data=ref_data,
            output_path=comparison_output / "academic_reports",
            filename="simulator_comparison_academic.pdf"
        )
        
        for pdf_path in academic_pdfs:
            logger.info(f"📄 PDF acadêmico gerado: {pdf_path}")
            
    except ImportError as e:
        logger.warning(f"⚠️ Dependências para PDF não encontradas: {e}")
        logger.info("💡 Para gerar PDFs, instale: pip install matplotlib seaborn plotly kaleido")
    except Exception as e:
        logger.warning(f"⚠️ Erro ao gerar PDF acadêmico: {e}")
    
    # Print summary
    logger.info("="*80)
    logger.info("🎯 COMPARAÇÃO CONCLUÍDA!")
    logger.info(f"📊 Score de Similaridade: {results['overall_similarity']['score']:.3f}")
    logger.info(f"📈 Classificação: {results['overall_similarity']['classification']}")
    logger.info(f"📁 Relatórios salvos em: {comparison_output}")
    logger.info("="*80)
    
    return results

def create_sample_xml(output_path: Path):
    """
    Cria um arquivo XML de exemplo para testes
    """
    sample_xml = """<?xml version="1.0" encoding="utf-8"?>
<events>
<event time="7" type="actend" person="paraiso_regular_0_1" link="2105" actType="h" action="ok" />
<event time="7" type="departure" person="paraiso_regular_0_1" link="2105" legMode="car" action="ok" />
<event time="7" type="PersonEntersVehicle" person="paraiso_regular_0_1" vehicle="paraiso_regular_0_1" action="ok" />
<event time="7" type="wait2link" person="paraiso_regular_0_1" link="2105" vehicle="paraiso_regular_0_1" action="ok" />
<event time="7" type="entered link" person="paraiso_regular_0_1" link="2105" vehicle="paraiso_regular_0_1" action="ok" />
<event time="8" type="actend" person="consolacao_regular0_1" link="3345" actType="h" action="ok" />
<event time="8" type="departure" person="consolacao_regular0_1" link="3345" legMode="car" action="ok" />
<event time="8" type="PersonEntersVehicle" person="consolacao_regular0_1" vehicle="consolacao_regular0_1" action="ok" />
<event time="8" type="wait2link" person="consolacao_regular0_1" link="3345" vehicle="consolacao_regular0_1" action="ok" />
<event time="8" type="entered link" person="consolacao_regular0_1" link="3345" vehicle="consolacao_regular0_1" action="ok" />
<event time="15" type="left link" person="paraiso_regular_0_1" link="2105" vehicle="paraiso_regular_0_1" action="ok" />
<event time="15" type="entered link" person="paraiso_regular_0_1" link="4341" vehicle="paraiso_regular_0_1" action="ok" />
<event time="16" type="actend" person="paraiso_regular_5_1" link="2105" actType="h" action="ok" />
</events>"""
    
    sample_file = output_path / "sample_reference_events.xml"
    with open(sample_file, 'w') as f:
        f.write(sample_xml)
    
    print(f"📄 Arquivo XML de exemplo criado: {sample_file}")
    return sample_file

def main():
    """Função principal"""
    parser = argparse.ArgumentParser(description="Comparador de Simuladores HTC vs Referência")
    
    parser.add_argument('reference_xml', nargs='?', help='Arquivo XML com eventos do simulador de referência')
    
    # HTC data source options
    htc_group = parser.add_mutually_exclusive_group()
    htc_group.add_argument('--htc-cassandra', action='store_true', 
                          help='Usar dados do HTC via Cassandra')
    htc_group.add_argument('--htc-csv', type=str, metavar='FILE',
                          help='Usar dados do HTC via arquivo CSV')
    
    # Optional parameters
    parser.add_argument('--limit', type=int, default=1000,
                       help='Limite de registros do Cassandra (default: 1000)')
    parser.add_argument('--output', type=str, 
                       help='Diretório de saída (default: scripts/output)')
    parser.add_argument('--create-sample', action='store_true',
                       help='Criar arquivo XML de exemplo e sair')
    
    args = parser.parse_args()
    
    # Setup
    logger = setup_logging()
    output_path = Path(args.output) if args.output else OUTPUT_PATH
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Create sample XML if requested
    if args.create_sample:
        create_sample_xml(output_path)
        return 0
    
    logger.info("🚀 Iniciando Comparador de Simuladores...")
    logger.info(f"📁 Diretório de saída: {output_path}")
    
    try:
        # Create sample XML if requested
        if args.create_sample:
            create_sample_xml(output_path)
            return 0
            
        # Validate required arguments for comparison
        if not args.reference_xml:
            logger.error("❌ Arquivo XML de referência é obrigatório")
            return 1
        
        if not args.htc_cassandra and not args.htc_csv:
            logger.error("❌ Deve especificar fonte de dados do HTC (--htc-cassandra ou --htc-csv)")
            return 1
        
        # Load HTC data
        if args.htc_cassandra:
            htc_data = load_htc_data('cassandra', limit=args.limit)
        else:
            htc_data = load_htc_data('csv', file_path=args.htc_csv)
        
        # Load reference data
        ref_data = load_reference_data(args.reference_xml)
        
        # Run comparison
        results = run_comparison(htc_data, ref_data, output_path)
        
        if results:
            logger.info("🎉 Comparação concluída com sucesso!")
            return 0
        else:
            logger.error("❌ Falha na comparação")
            return 1
            
    except Exception as e:
        logger.error(f"💥 Erro durante execução: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    exit(main())