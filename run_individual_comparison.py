#!/home/dean/PhD/hyperbolic-time-chamber/scripts/venv/bin/python
"""
Script executável para comparação individual com geração de PDFs acadêmicos
"""

import sys
import os
from pathlib import Path
import pandas as pd
import logging
import argparse
import json
from typing import Dict

# Adiciona o diretório scripts ao PYTHONPATH
SCRIPT_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(SCRIPT_DIR))
sys.path.insert(0, str(SCRIPT_DIR / "scripts"))

from comparison.individual_comparator import IndividualComparator
from comparison.id_mapper import IDMapper
from data_sources.cassandra_source import CassandraDataSource
from comparison.reference_parser import ReferenceSimulatorParser

def setup_logging():
    """Setup logging configuration"""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger(__name__)

def load_htc_data_from_cassandra(limit: int = None) -> pd.DataFrame:
    """Load HTC data from Cassandra"""
    logger = logging.getLogger(__name__)
    logger.info("🔌 Conectando ao Cassandra...")
    
    try:
        cassandra_source = CassandraDataSource()
        # Load ALL data if no limit specified
        data = cassandra_source.get_vehicle_flow_data(limit=limit)
        logger.info(f"✅ Carregados {len(data)} registros do Cassandra")
        return data
    except Exception as e:
        logger.error(f"❌ Erro ao carregar dados do Cassandra: {e}")
        return pd.DataFrame()

def load_htc_data_from_csv(file_path: str) -> pd.DataFrame:
    """Load HTC data from CSV file"""
    logger = logging.getLogger(__name__)
    logger.info(f"📁 Carregando dados do CSV: {file_path}")
    
    try:
        data = pd.read_csv(file_path)
        logger.info(f"✅ Carregados {len(data)} registros do CSV")
        return data
    except Exception as e:
        logger.error(f"❌ Erro ao carregar CSV: {e}")
        return pd.DataFrame()

def load_reference_data(xml_path: str) -> pd.DataFrame:
    """Load reference data from XML"""
    logger = logging.getLogger(__name__)
    logger.info(f"📄 Carregando dados de referência: {xml_path}")
    
    try:
        parser = ReferenceSimulatorParser(xml_path)
        data = parser.parse_xml_events()
        logger.info(f"✅ Carregados {len(data)} eventos de referência")
        return data
    except Exception as e:
        logger.error(f"❌ Erro ao carregar dados de referência: {e}")
        return pd.DataFrame()

def run_individual_comparison(htc_data: pd.DataFrame, 
                            ref_data: pd.DataFrame,
                            output_path: Path) -> Dict:
    """Execute individual comparison"""
    logger = logging.getLogger(__name__)
    
    if htc_data.empty or ref_data.empty:
        logger.error("❌ Um ou ambos os datasets estão vazios")
        return {}
    
    logger.info("🆔 Criando mapeamento de IDs...")
    id_mapper = IDMapper()
    
    # Extract unique vehicle and link IDs
    htc_vehicle_ids = set()
    ref_vehicle_ids = set()
    
    logger.info("   🔍 Extraindo IDs de veículos do HTC...")
    # Get vehicle IDs from different columns depending on the data structure
    if 'car_id' in htc_data.columns:
        htc_vehicle_ids = set(htc_data['car_id'].dropna().unique())
    elif 'vehicle_id' in htc_data.columns:
        htc_vehicle_ids = set(htc_data['vehicle_id'].dropna().unique())
    elif 'person' in htc_data.columns:
        htc_vehicle_ids = set(htc_data['person'].dropna().unique())
    
    logger.info("   🔍 Extraindo IDs de veículos de referência...")    
    if 'vehicle' in ref_data.columns:
        ref_vehicle_ids = set(ref_data['vehicle'].dropna().unique())
    elif 'person' in ref_data.columns:
        ref_vehicle_ids = set(ref_data['person'].dropna().unique())
    
    logger.info(f"   Encontrados {len(htc_vehicle_ids)} veículos HTC")
    logger.info(f"   Encontrados {len(ref_vehicle_ids)} veículos de referência")
    
    if not htc_vehicle_ids or not ref_vehicle_ids:
        logger.error("❌ Não foram encontrados IDs de veículos válidos")
        return {}
    
    # Build car mapping
    id_mapper.htc_to_ref_cars, id_mapper.ref_to_htc_cars = id_mapper.build_car_mapping(
        htc_vehicle_ids, ref_vehicle_ids
    )
    
    if not id_mapper.htc_to_ref_cars:
        logger.error("❌ Falha no mapeamento de IDs")
        return {}
    
    logger.info("🔬 Preparando comparação individual...")
    logger.info(f"   📊 Registros HTC: {len(htc_data):,}")
    logger.info(f"   📊 Registros Referência: {len(ref_data):,}")
    logger.info(f"   🚗 Veículos a comparar: {len(id_mapper.htc_to_ref_cars):,}")
    
    # Estimate processing time
    total_vehicles = len(id_mapper.htc_to_ref_cars)
    if total_vehicles > 0:
        estimated_minutes = max(1, total_vehicles // 1000)  # Rough estimate: 1000 vehicles per minute
        logger.info(f"   ⏱️ Tempo estimado: ~{estimated_minutes} minutos")
        logger.info("   💡 O progresso será mostrado a cada 100 veículos processados")
    
    logger.info("🔬 Iniciando comparação individual...")
    
    # Standardize column names for the comparator
    htc_data_std = htc_data.copy()
    ref_data_std = ref_data.copy()
    
    # Ensure both datasets have 'car_id' column
    if 'car_id' not in htc_data_std.columns and 'person' in htc_data_std.columns:
        htc_data_std['car_id'] = htc_data_std['person']
    if 'car_id' not in ref_data_std.columns and 'person' in ref_data_std.columns:
        ref_data_std['car_id'] = ref_data_std['person']
    if 'car_id' not in ref_data_std.columns and 'vehicle' in ref_data_std.columns:
        ref_data_std['car_id'] = ref_data_std['vehicle']
    
    comparator = IndividualComparator(output_path, id_mapper)
    
    # Execute comparison
    results = {}
    
    # Vehicle comparison
    vehicle_results = comparator.compare_individual_vehicles(htc_data_std, ref_data_std)
    results.update(vehicle_results)
    
    # Link comparison
    link_results = comparator.compare_individual_links(htc_data_std, ref_data_std)
    results.update(link_results)
    
    # Create visualizations
    comparator.create_individual_visualizations(vehicle_results, link_results)
    
    # Generate academic PDF
    pdf_path = comparator.generate_academic_pdf(results)
    if pdf_path:
        results['academic_pdf_path'] = str(pdf_path)
    
    # Save results to JSON
    json_report_path = output_path / "individual_comparison_report.json"
    with open(json_report_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False, default=str)
    
    logger.info(f"💾 Relatório salvo em: {json_report_path}")
    
    return results

def create_sample_data(output_path: Path):
    """Create sample data for testing"""
    logger = logging.getLogger(__name__)
    logger.info("📋 Criando dados de exemplo...")
    
    # Sample XML
    sample_xml = """<?xml version="1.0" encoding="utf-8"?>
<events>
<event time="7" type="actend" person="trip_317_1" link="2105" actType="h" />
<event time="7" type="departure" person="trip_317_1" link="2105" legMode="car" />
<event time="7" type="PersonEntersVehicle" person="trip_317_1" vehicle="trip_317_1" />
<event time="7" type="entered link" person="trip_317_1" link="2105" vehicle="trip_317_1" />
<event time="15" type="left link" person="trip_317_1" link="2105" vehicle="trip_317_1" />
<event time="15" type="entered link" person="trip_317_1" link="4341" vehicle="trip_317_1" />
<event time="8" type="actend" person="trip_542_1" link="3345" actType="h" />
<event time="8" type="departure" person="trip_542_1" link="3345" legMode="car" />
<event time="8" type="PersonEntersVehicle" person="trip_542_1" vehicle="trip_542_1" />
<event time="8" type="entered link" person="trip_542_1" link="3345" vehicle="trip_542_1" />
</events>"""
    
    sample_xml_path = output_path / "sample_reference_individual.xml"
    with open(sample_xml_path, 'w') as f:
        f.write(sample_xml)
    
    # Sample HTC CSV
    htc_sample_data = {
        'car_id': ['htcaid:car;trip_317', 'htcaid:car;trip_317', 'htcaid:car;trip_542', 'htcaid:car;trip_542'],
        'link_id': ['htcaid:link;2105', 'htcaid:link;4341', 'htcaid:link;3345', 'htcaid:link;3345'],
        'timestamp': [7.0, 15.0, 8.0, 16.0],
        'event_type': ['enter_link', 'enter_link', 'enter_link', 'leave_link'],
        'tick': [7, 15, 8, 16],
        'direction': ['forward', 'forward', 'forward', 'backward']
    }
    
    htc_df = pd.DataFrame(htc_sample_data)
    htc_csv_path = output_path / "sample_htc_individual.csv"
    htc_df.to_csv(htc_csv_path, index=False)
    
    logger.info(f"✅ Dados de exemplo criados:")
    logger.info(f"   📄 XML: {sample_xml_path}")
    logger.info(f"   📊 CSV: {htc_csv_path}")
    
    return htc_csv_path, sample_xml_path

def main():
    """Main function"""
    parser = argparse.ArgumentParser(description="Comparação Individual de Veículos com PDFs Acadêmicos")
    
    parser.add_argument('reference_xml', nargs='?', help='Arquivo XML com eventos do simulador de referência')
    
    # HTC data source
    htc_group = parser.add_mutually_exclusive_group()
    htc_group.add_argument('--htc-cassandra', action='store_true',
                          help='Usar dados do HTC via Cassandra')
    htc_group.add_argument('--htc-csv', type=str, metavar='FILE',
                          help='Usar dados do HTC via arquivo CSV')
    
    # Options
    parser.add_argument('--limit', type=int, default=None,
                       help='Limite de registros do Cassandra (default: todos os dados)')
    parser.add_argument('--all', action='store_true',
                       help='Processar TODOS os dados (ignora --limit)')
    parser.add_argument('--output', type=str,
                       help='Diretório de saída (default: scripts/output/individual)')
    parser.add_argument('--create-sample', action='store_true',
                       help='Criar dados de exemplo e executar comparação')
    
    args = parser.parse_args()
    
    # Setup
    logger = setup_logging()
    output_path = Path(args.output) if args.output else Path("scripts/output/individual")
    output_path.mkdir(parents=True, exist_ok=True)
    
    # Create sample data if requested
    if args.create_sample:
        htc_csv, ref_xml = create_sample_data(output_path)
        logger.info("🧪 Executando comparação com dados de exemplo...")
        
        htc_data = load_htc_data_from_csv(str(htc_csv))
        ref_data = load_reference_data(str(ref_xml))
        
        results = run_individual_comparison(htc_data, ref_data, output_path)
        
        if results:
            logger.info("="*80)
            logger.info("🎯 COMPARAÇÃO INDIVIDUAL CONCLUÍDA!")
            logger.info(f"📁 Resultados salvos em: {output_path}")
            
            # Print summary
            if 'mapping_statistics' in results:
                stats = results['mapping_statistics']
                logger.info(f"📊 Veículos mapeados: {stats.get('successfully_mapped', 0)}")
                
            if 'vehicle_comparison' in results:
                vehicle_scores = [v.get('similarity_score', 0) 
                                for v in results['vehicle_comparison'].values() 
                                if isinstance(v, dict)]
                if vehicle_scores:
                    avg_similarity = sum(vehicle_scores) / len(vehicle_scores)
                    logger.info(f"📈 Similaridade média de veículos: {avg_similarity:.3f}")
            
            logger.info("="*80)
            
        return 0
    
    # Validate arguments
    if not args.reference_xml:
        logger.error("❌ Arquivo XML de referência é obrigatório")
        logger.info("💡 Use --create-sample para gerar dados de exemplo")
        return 1
    
    if not args.htc_cassandra and not args.htc_csv:
        logger.error("❌ Especifique uma fonte de dados HTC (--htc-cassandra ou --htc-csv)")
        return 1
    
    # Determine limit
    limit = None if args.all else args.limit
    
    # Load data
    if args.htc_cassandra:
        htc_data = load_htc_data_from_cassandra(limit)
    else:
        htc_data = load_htc_data_from_csv(args.htc_csv)
    
    ref_data = load_reference_data(args.reference_xml)
    
    # Run comparison
    results = run_individual_comparison(htc_data, ref_data, output_path)
    
    if results:
        logger.info("="*80)
        logger.info("🎯 COMPARAÇÃO INDIVIDUAL CONCLUÍDA!")
        logger.info(f"📁 Resultados salvos em: {output_path}")
        
        # Print summary
        if 'mapping_statistics' in results:
            stats = results['mapping_statistics']
            logger.info(f"📊 Veículos mapeados: {stats.get('successfully_mapped', 0)}")
            
        if 'vehicle_comparison' in results:
            vehicle_scores = [v.get('similarity_score', 0) 
                            for v in results['vehicle_comparison'].values() 
                            if isinstance(v, dict)]
            if vehicle_scores:
                avg_similarity = sum(vehicle_scores) / len(vehicle_scores)
                logger.info(f"📈 Similaridade média de veículos: {avg_similarity:.3f}")
        
        if 'academic_pdf_path' in results:
            logger.info(f"📄 PDF acadêmico: {results['academic_pdf_path']}")
        
        logger.info("="*80)
        return 0
    else:
        logger.error("❌ Falha na comparação individual")
        return 1

if __name__ == "__main__":
    sys.exit(main())