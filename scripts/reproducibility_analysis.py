#!/usr/bin/env python3
"""
Script para análise de reprodutibilidade de simulações HTC
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
from analysis.reproducibility_analyzer import ReproducibilityAnalyzer

def setup_logging():
    """Configura o sistema de logging"""
    logging.basicConfig(**LOGGING_CONFIG)
    return logging.getLogger(__name__)

def load_simulation_run(source_type: str, **kwargs) -> pd.DataFrame:
    """
    Carrega dados de uma execução da simulação
    
    Args:
        source_type: Tipo da fonte (cassandra, csv, xml)
        **kwargs: Argumentos específicos da fonte
        
    Returns:
        DataFrame com dados da execução
    """
    logger = logging.getLogger(__name__)
    
    if source_type == 'cassandra':
        logger.info("🔌 Carregando dados via Cassandra...")
        cassandra_source = CassandraDataSource()
        data = cassandra_source.get_vehicle_flow_data(
            limit=kwargs.get('limit', 999999999),
            simulation_id=kwargs.get('simulation_id')
        )
        
    elif source_type == 'csv':
        logger.info(f"📁 Carregando dados via CSV...")
        file_path = kwargs.get('file_path')
        if not file_path or not os.path.isfile(file_path):
            logger.error(f"❌ Arquivo CSV não encontrado: {file_path}")
            return pd.DataFrame()
        data = pd.read_csv(file_path)
        
    elif source_type == 'xml':
        logger.info(f"📄 Carregando dados via XML...")
        file_path = kwargs.get('file_path')
        if not file_path or not os.path.isfile(file_path):
            logger.error(f"❌ Arquivo XML não encontrado: {file_path}")
            return pd.DataFrame()
        parser = ReferenceSimulatorParser(file_path)
        data = parser.get_traffic_flow_events()
        
    else:
        logger.error(f"❌ Tipo de fonte não suportado: {source_type}")
        return pd.DataFrame()
    
    if not data.empty:
        logger.info(f"✅ Carregados {len(data)} registros, {data['car_id'].nunique() if 'car_id' in data.columns else 0} veículos")
    else:
        logger.warning("⚠️ Nenhum dado encontrado")
    
    return data

def analyze_cassandra_runs(simulation_ids: list, limit: int, output_path: Path) -> dict:
    """
    Analisa reprodutibilidade de múltiplas execuções no Cassandra
    
    Args:
        simulation_ids: Lista de IDs de simulação
        limit: Limite de registros por simulação
        output_path: Caminho para salvar resultados
        
    Returns:
        Resultados da análise de reprodutibilidade
    """
    logger = logging.getLogger(__name__)
    
    logger.info(f"🔄 Analisando reprodutibilidade de {len(simulation_ids)} simulações...")
    
    # Carregar dados de cada simulação
    datasets = []
    run_names = []
    
    for i, sim_id in enumerate(simulation_ids):
        logger.info(f"📊 Carregando simulação {i+1}/{len(simulation_ids)}: {sim_id}")
        
        data = load_simulation_run(
            'cassandra',
            limit=limit,
            simulation_id=sim_id
        )
        
        if not data.empty:
            datasets.append(data)
            run_names.append(f"Sim_{sim_id}")
        else:
            logger.warning(f"⚠️ Simulação {sim_id} não contém dados válidos")
    
    if len(datasets) < 2:
        logger.error("❌ São necessárias pelo menos 2 simulações para análise de reprodutibilidade")
        return {}
    
    # Criar analisador e executar análise
    analyzer = ReproducibilityAnalyzer(
        output_dir=str(output_path / "reproducibility")
    )
    
    analysis = analyzer.analyze_multiple_runs(datasets, run_names)
    
    # Gerar visualizações
    viz_files = analyzer.create_reproducibility_visualizations(datasets, run_names, analysis)
    
    # Gerar relatório
    report_file = analyzer.generate_reproducibility_report(analysis)
    
    # Imprimir resumo
    analyzer.print_reproducibility_summary(analysis)
    
    logger.info(f"✅ Análise de reprodutibilidade concluída!")
    logger.info(f"📁 Arquivos gerados em: {output_path / 'reproducibility'}")
    logger.info(f"📄 Relatório: {report_file}")
    for viz_file in viz_files:
        logger.info(f"📊 Visualização: {viz_file}")
    
    return analysis

def analyze_file_runs(file_paths: list, source_types: list, output_path: Path) -> dict:
    """
    Analisa reprodutibilidade de múltiplas execuções de arquivos
    
    Args:
        file_paths: Lista de caminhos de arquivos
        source_types: Lista de tipos de fonte correspondentes
        output_path: Caminho para salvar resultados
        
    Returns:
        Resultados da análise de reprodutibilidade
    """
    logger = logging.getLogger(__name__)
    
    logger.info(f"🔄 Analisando reprodutibilidade de {len(file_paths)} arquivos...")
    
    # Carregar dados de cada arquivo
    datasets = []
    run_names = []
    
    for i, (file_path, source_type) in enumerate(zip(file_paths, source_types)):
        logger.info(f"📊 Carregando arquivo {i+1}/{len(file_paths)}: {file_path}")
        
        data = load_simulation_run(source_type, file_path=file_path)
        
        if not data.empty:
            datasets.append(data)
            file_name = Path(file_path).stem
            run_names.append(f"Run_{file_name}")
        else:
            logger.warning(f"⚠️ Arquivo {file_path} não contém dados válidos")
    
    if len(datasets) < 2:
        logger.error("❌ São necessários pelo menos 2 arquivos para análise de reprodutibilidade")
        return {}
    
    # Criar analisador e executar análise
    analyzer = ReproducibilityAnalyzer(
        output_dir=str(output_path / "reproducibility")
    )
    
    analysis = analyzer.analyze_multiple_runs(datasets, run_names)
    
    # Gerar visualizações
    viz_files = analyzer.create_reproducibility_visualizations(datasets, run_names, analysis)
    
    # Gerar relatório
    report_file = analyzer.generate_reproducibility_report(analysis)
    
    # Imprimir resumo
    analyzer.print_reproducibility_summary(analysis)
    
    logger.info(f"✅ Análise de reprodutibilidade concluída!")
    logger.info(f"📁 Arquivos gerados em: {output_path / 'reproducibility'}")
    logger.info(f"📄 Relatório: {report_file}")
    for viz_file in viz_files:
        logger.info(f"📊 Visualização: {viz_file}")
    
    return analysis

def create_sample_config(output_path: Path):
    """Cria arquivo de configuração de exemplo"""
    config_content = """# Configuração de Análise de Reprodutibilidade

## Exemplo 1: Análise via Cassandra
# Especifique os IDs das simulações a serem comparadas
--cassandra-sims sim_id_1 sim_id_2 sim_id_3

## Exemplo 2: Análise via arquivos CSV
--csv-files run1.csv run2.csv run3.csv

## Exemplo 3: Análise via arquivos XML
--xml-files events_run1.xml events_run2.xml events_run3.xml

## Exemplo 4: Análise mista
--mixed-files run1.csv:csv events.xml:xml data.csv:csv

## Opções adicionais
--limit 50000        # Limitar registros por execução
--output ./results   # Diretório de saída

# Interpretação dos Resultados:
# - CV < 0.05: Boa reprodutibilidade
# - CV < 0.1: Reprodutibilidade moderada  
# - CV >= 0.1: Baixa reprodutibilidade (investigar)
#
# - Similaridade >= 0.8: Alta similaridade
# - Similaridade >= 0.6: Similaridade moderada
# - Similaridade < 0.6: Baixa similaridade
"""
    
    config_file = output_path / "reproducibility_config_example.txt"
    with open(config_file, 'w') as f:
        f.write(config_content)
    
    print(f"📄 Arquivo de configuração de exemplo criado: {config_file}")
    return config_file

def main():
    """Função principal"""
    parser = argparse.ArgumentParser(
        description="Analisador de Reprodutibilidade de Simulações HTC",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos de uso:

  # Análise de simulações no Cassandra
  python reproducibility_analysis.py --cassandra-sims sim_001 sim_002 sim_003

  # Análise de arquivos CSV
  python reproducibility_analysis.py --csv-files run1.csv run2.csv run3.csv

  # Análise de arquivos XML
  python reproducibility_analysis.py --xml-files events1.xml events2.xml

  # Análise mista
  python reproducibility_analysis.py --mixed-files run1.csv:csv events.xml:xml

  # Criar exemplo de configuração
  python reproducibility_analysis.py --create-config
        """
    )
    
    # Grupos mutuamente exclusivos para tipos de análise
    analysis_group = parser.add_mutually_exclusive_group(required=True)
    
    analysis_group.add_argument('--cassandra-sims', nargs='+', metavar='SIM_ID',
                               help='IDs das simulações no Cassandra para comparar')
    
    analysis_group.add_argument('--csv-files', nargs='+', metavar='FILE',
                               help='Arquivos CSV para comparar')
    
    analysis_group.add_argument('--xml-files', nargs='+', metavar='FILE',
                               help='Arquivos XML para comparar')
    
    analysis_group.add_argument('--mixed-files', nargs='+', metavar='FILE:TYPE',
                               help='Arquivos mistos no formato arquivo:tipo (csv|xml)')
    
    analysis_group.add_argument('--create-config', action='store_true',
                               help='Criar arquivo de configuração de exemplo')
    
    # Parâmetros opcionais
    parser.add_argument('--limit', type=int, default=999999999,
                       help='Limite de registros por simulação (default: todos)')
    
    parser.add_argument('--output', type=str, 
                       help='Diretório de saída (default: scripts/output)')
    
    args = parser.parse_args()
    
    # Setup
    logger = setup_logging()
    output_path = Path(args.output) if args.output else OUTPUT_PATH
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Criar configuração de exemplo se solicitado
    if args.create_config:
        create_sample_config(output_path)
        return 0
    
    logger.info("🔄 Iniciando Análise de Reprodutibilidade...")
    logger.info(f"📁 Diretório de saída: {output_path}")
    
    try:
        results = {}
        
        if args.cassandra_sims:
            if len(args.cassandra_sims) < 2:
                logger.error("❌ São necessárias pelo menos 2 simulações para análise")
                return 1
            
            results = analyze_cassandra_runs(
                args.cassandra_sims,
                args.limit,
                output_path
            )
        
        elif args.csv_files:
            if len(args.csv_files) < 2:
                logger.error("❌ São necessários pelo menos 2 arquivos para análise")
                return 1
            
            source_types = ['csv'] * len(args.csv_files)
            results = analyze_file_runs(
                args.csv_files,
                source_types,
                output_path
            )
        
        elif args.xml_files:
            if len(args.xml_files) < 2:
                logger.error("❌ São necessários pelo menos 2 arquivos para análise")
                return 1
            
            source_types = ['xml'] * len(args.xml_files)
            results = analyze_file_runs(
                args.xml_files,
                source_types,
                output_path
            )
        
        elif args.mixed_files:
            if len(args.mixed_files) < 2:
                logger.error("❌ São necessários pelo menos 2 arquivos para análise")
                return 1
            
            # Parse mixed files format: file:type
            file_paths = []
            source_types = []
            
            for mixed_file in args.mixed_files:
                if ':' not in mixed_file:
                    logger.error(f"❌ Formato inválido para arquivo misto: {mixed_file}")
                    logger.error("   Use o formato: arquivo:tipo (ex: data.csv:csv)")
                    return 1
                
                file_path, source_type = mixed_file.split(':', 1)
                
                if source_type not in ['csv', 'xml']:
                    logger.error(f"❌ Tipo de arquivo não suportado: {source_type}")
                    logger.error("   Tipos suportados: csv, xml")
                    return 1
                
                file_paths.append(file_path)
                source_types.append(source_type)
            
            results = analyze_file_runs(
                file_paths,
                source_types,
                output_path
            )
        
        if results:
            logger.info("🎉 Análise de reprodutibilidade concluída com sucesso!")
            
            # Exibir resumo final
            print("\n" + "="*80)
            print("📊 RESUMO FINAL DOS ARQUIVOS GERADOS")
            print("="*80)
            print(f"\n📁 Todos os arquivos salvos em: {output_path / 'reproducibility'}")
            print(f"🔍 Verifique os arquivos:")
            print(f"  📋 RELATÓRIOS:")
            print(f"    • reproducibility_report.json - Relatório completo em JSON")
            print(f"    • reproducibility_complete_report.pdf - Relatório PDF consolidado")
            print(f"  📊 GRÁFICOS AGRUPADOS:")
            print(f"    • reproducibility_dashboard.png/.pdf - Dashboard visual completo")
            print(f"    • basic_metrics_comparison.png/.pdf - Comparação de métricas básicas")
            print(f"    • temporal_reproducibility.png/.pdf - Análise temporal agrupada")
            print(f"    • similarity_scores.png/.pdf - Scores de similaridade agrupados")
            print(f"  📈 GRÁFICOS INDIVIDUAIS:")
            print(f"    • metric_*.png/.pdf - Cada métrica básica em gráfico separado")
            print(f"    • temporal_*.png/.pdf - Gráficos temporais individuais")
            print(f"    • similarity_*.png/.pdf - Gráficos de similaridade individuais")
            print(f"    • distribution_*.png/.pdf - Distribuições de cada variável")
            print("="*80 + "\n")
            
            return 0
        else:
            logger.error("❌ Falha na análise de reprodutibilidade")
            return 1
            
    except Exception as e:
        logger.error(f"💥 Erro durante execução: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    exit(main())