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
from analysis.general_metrics import GeneralTrafficMetrics

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
    
    # 🆕 ANÁLISE DE MÉTRICAS GERAIS
    logger.info("📊 Gerando análise de métricas gerais...")
    try:
        # Criar analisador de métricas gerais
        metrics_analyzer = GeneralTrafficMetrics(
            output_dir=str(comparison_output / "general_metrics")
        )
        
        # Analisar dados do HTC
        logger.info("📈 Analisando métricas do HTC...")
        htc_metrics = metrics_analyzer.calculate_all_metrics(htc_data)
        htc_plots = metrics_analyzer.generate_all_plots(htc_data, htc_metrics)
        htc_report = metrics_analyzer.save_metrics_report(htc_metrics, "htc_metrics.json")
        
        logger.info("📊 MÉTRICAS GERAIS - HTC:")
        metrics_analyzer.print_summary_report(htc_metrics)
        
        # Analisar dados de referência
        logger.info("📈 Analisando métricas da referência...")
        ref_metrics_analyzer = GeneralTrafficMetrics(
            output_dir=str(comparison_output / "reference_metrics")
        )
        ref_metrics = ref_metrics_analyzer.calculate_all_metrics(ref_data)
        ref_plots = ref_metrics_analyzer.generate_all_plots(ref_data, ref_metrics)
        ref_report = ref_metrics_analyzer.save_metrics_report(ref_metrics, "reference_metrics.json")
        
        logger.info("📊 MÉTRICAS GERAIS - REFERÊNCIA:")
        ref_metrics_analyzer.print_summary_report(ref_metrics)
        
        # Adicionar métricas aos resultados
        results['htc_metrics'] = htc_metrics
        results['reference_metrics'] = ref_metrics
        results['htc_plots'] = htc_plots
        results['reference_plots'] = ref_plots
        
        logger.info(f"✅ Análise de métricas concluída!")
        logger.info(f"📁 Gráficos HTC salvos em: {comparison_output / 'general_metrics'}")
        logger.info(f"📁 Gráficos Referência salvos em: {comparison_output / 'reference_metrics'}")
        
    except Exception as e:
        logger.error(f"❌ Erro na análise de métricas gerais: {e}")
    
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
    parser = argparse.ArgumentParser(
        description="Comparador de Simuladores HTC vs Referência",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos de uso:

  # Comparação tradicional HTC vs Referência
  python compare_simulators.py events.xml --htc-cassandra

  # Análise de reprodutibilidade (redireciona para script específico)
  python compare_simulators.py --reproducibility --cassandra-sims sim_001 sim_002

  # Criar amostra XML para testes
  python compare_simulators.py --create-sample
        """
    )
    
    # Grupos mutuamente exclusivos para diferentes tipos de análise
    mode_group = parser.add_mutually_exclusive_group(required=False)
    mode_group.add_argument('--reproducibility', action='store_true',
                           help='Executar análise de reprodutibilidade')
    mode_group.add_argument('--create-sample', action='store_true',
                           help='Criar arquivo XML de exemplo e sair')
    
    # Argumento posicional para modo tradicional
    parser.add_argument('reference_xml', nargs='?', 
                       help='Arquivo XML com eventos do simulador de referência (modo tradicional)')
    
    # HTC data source options (para modo tradicional)
    htc_group = parser.add_mutually_exclusive_group()
    htc_group.add_argument('--htc-cassandra', action='store_true', 
                          help='Usar dados do HTC via Cassandra')
    htc_group.add_argument('--htc-csv', type=str, metavar='FILE',
                          help='Usar dados do HTC via arquivo CSV')
    
    # Reprodutibilidade options
    repro_group = parser.add_mutually_exclusive_group()
    repro_group.add_argument('--cassandra-sims', nargs='+', metavar='SIM_ID',
                            help='IDs das simulações no Cassandra para análise de reprodutibilidade')
    repro_group.add_argument('--csv-files', nargs='+', metavar='FILE',
                            help='Arquivos CSV para análise de reprodutibilidade')
    repro_group.add_argument('--xml-files', nargs='+', metavar='FILE',
                            help='Arquivos XML para análise de reprodutibilidade')
    
    # Optional parameters
    parser.add_argument('--limit', type=int, default=999999999,
                       help='Limite de registros do Cassandra (default: todos)')
    parser.add_argument('--output', type=str, 
                       help='Diretório de saída (default: scripts/output)')
    
    args = parser.parse_args()
    
    # Setup
    logger = setup_logging()
    output_path = Path(args.output) if args.output else OUTPUT_PATH
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Create sample XML if requested
    if args.create_sample:
        create_sample_xml(output_path)
        return 0
    
    # Handle reproducibility analysis
    if args.reproducibility:
        logger.info("� Redirecionando para análise de reprodutibilidade...")
        
        # Build command for reproducibility script
        repro_script = SCRIPT_DIR / "reproducibility_analysis.py"
        cmd_parts = ["python", str(repro_script)]
        
        # Add appropriate arguments
        if args.cassandra_sims:
            cmd_parts.extend(["--cassandra-sims"] + args.cassandra_sims)
        elif args.csv_files:
            cmd_parts.extend(["--csv-files"] + args.csv_files)
        elif args.xml_files:
            cmd_parts.extend(["--xml-files"] + args.xml_files)
        else:
            logger.error("❌ Para análise de reprodutibilidade, especifique:")
            logger.error("   --cassandra-sims SIM_ID1 SIM_ID2 ...")
            logger.error("   --csv-files file1.csv file2.csv ...")
            logger.error("   --xml-files file1.xml file2.xml ...")
            return 1
        
        # Add optional parameters
        if args.limit != 999999999:
            cmd_parts.extend(["--limit", str(args.limit)])
        if args.output:
            cmd_parts.extend(["--output", args.output])
        
        # Execute reproducibility script
        import subprocess
        try:
            logger.info(f"🚀 Executando: {' '.join(cmd_parts)}")
            result = subprocess.run(cmd_parts, capture_output=False)
            return result.returncode
        except Exception as e:
            logger.error(f"❌ Erro ao executar análise de reprodutibilidade: {e}")
            return 1
    
    logger.info("🚀 Iniciando Comparador de Simuladores...")
    logger.info(f"📁 Diretório de saída: {output_path}")
    
    try:
        # Validate required arguments for traditional comparison
        if not args.reference_xml:
            logger.error("❌ Arquivo XML de referência é obrigatório para comparação tradicional")
            logger.error("💡 Use --reproducibility para análise de reprodutibilidade")
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
            
            # Exibir resumo das métricas geradas
            print("\n" + "="*80)
            print("📊 RESUMO DOS ARQUIVOS GERADOS")
            print("="*80)
            
            if 'htc_plots' in results:
                print(f"\n📈 GRÁFICOS HTC ({len(results['htc_plots'])} arquivos):")
                for plot_file in results['htc_plots']:
                    print(f"  • {plot_file}")
            
            if 'reference_plots' in results:
                print(f"\n📈 GRÁFICOS REFERÊNCIA ({len(results['reference_plots'])} arquivos):")
                for plot_file in results['reference_plots']:
                    print(f"  • {plot_file}")
            
            print(f"\n📁 Todos os arquivos salvos em: {output_path}")
            print(f"🔍 Verifique os subdiretórios:")
            print(f"  • comparison/ - Análise comparativa")
            print(f"  • general_metrics/ - Métricas gerais HTC")
            print(f"  • reference_metrics/ - Métricas gerais Referência")
            print("="*80 + "\n")
            
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