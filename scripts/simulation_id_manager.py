#!/usr/bin/env python3
"""
Script para gerenciar e visualizar Simulation IDs no HTC
"""

import sys
import os
from pathlib import Path
import argparse
import logging
from datetime import datetime
import uuid

# Adiciona o diretório scripts ao PYTHONPATH
SCRIPT_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(SCRIPT_DIR))

from config import OUTPUT_PATH, LOGGING_CONFIG

try:
    from data_sources.cassandra_source import CassandraDataSource
    CASSANDRA_AVAILABLE = True
except ImportError:
    CASSANDRA_AVAILABLE = False
    print("⚠️ Cassandra driver não disponível. Instale com: pip install cassandra-driver")

def setup_logging():
    """Configura o sistema de logging"""
    logging.basicConfig(**LOGGING_CONFIG)
    return logging.getLogger(__name__)

def list_simulation_ids():
    """Lista todos os simulation_ids disponíveis no Cassandra"""
    if not CASSANDRA_AVAILABLE:
        print("❌ Cassandra não disponível")
        return []
    
    logger = logging.getLogger(__name__)
    
    try:
        cassandra = CassandraDataSource()
        
        # Query para obter simulation_ids únicos
        query = """
        SELECT simulation_id, COUNT(*) as record_count, 
               MIN(timestamp) as first_record, 
               MAX(timestamp) as last_record
        FROM htc_reports.simulation_reports 
        WHERE report_type = 'vehicle_flow'
        GROUP BY simulation_id
        ALLOW FILTERING
        """
        
        logger.info("🔍 Consultando simulation IDs disponíveis...")
        
        if hasattr(cassandra, 'session') and cassandra.session:
            result = cassandra.session.execute(query)
            
            simulation_data = []
            for row in result:
                simulation_data.append({
                    'simulation_id': row.simulation_id,
                    'record_count': row.record_count,
                    'first_record': row.first_record,
                    'last_record': row.last_record,
                    'duration': row.last_record - row.first_record if row.last_record and row.first_record else None
                })
            
            if simulation_data:
                print("📊 SIMULATION IDs DISPONÍVEIS:")
                print("="*80)
                print(f"{'Simulation ID':<40} {'Registros':<10} {'Primeira':<20} {'Última':<20}")
                print("-"*80)
                
                for sim_data in sorted(simulation_data, key=lambda x: x['first_record'] or datetime.min, reverse=True):
                    sim_id = sim_data['simulation_id']
                    count = sim_data['record_count']
                    first = sim_data['first_record'].strftime('%Y-%m-%d %H:%M') if sim_data['first_record'] else 'N/A'
                    last = sim_data['last_record'].strftime('%Y-%m-%d %H:%M') if sim_data['last_record'] else 'N/A'
                    
                    print(f"{sim_id:<40} {count:<10} {first:<20} {last:<20}")
                
                print("="*80)
                print(f"📈 Total: {len(simulation_data)} simulações encontradas")
                
                return [sim_data['simulation_id'] for sim_data in simulation_data]
            else:
                print("⚠️ Nenhuma simulação encontrada no Cassandra")
                return []
                
        else:
            print("❌ Erro ao conectar com Cassandra")
            return []
            
    except Exception as e:
        logger.error(f"❌ Erro ao consultar simulation IDs: {e}")
        return []

def generate_simulation_id(prefix: str = None) -> str:
    """Gera um novo simulation ID único"""
    if prefix:
        return f"{prefix}_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"
    else:
        return f"sim_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}"

def show_simulation_details(simulation_id: str):
    """Mostra detalhes de uma simulação específica"""
    if not CASSANDRA_AVAILABLE:
        print("❌ Cassandra não disponível")
        return
    
    logger = logging.getLogger(__name__)
    
    try:
        cassandra = CassandraDataSource()
        
        # Carregar dados da simulação
        data = cassandra.get_vehicle_flow_data(simulation_id=simulation_id, limit=1000)
        
        if not data.empty:
            print(f"📊 DETALHES DA SIMULAÇÃO: {simulation_id}")
            print("="*60)
            print(f"📈 Total de registros: {len(data)}")
            print(f"🚗 Veículos únicos: {data['car_id'].nunique() if 'car_id' in data.columns else 'N/A'}")
            
            if 'timestamp' in data.columns:
                print(f"⏰ Primeiro registro: {data['timestamp'].min()}")
                print(f"⏰ Último registro: {data['timestamp'].max()}")
                print(f"⏱️ Duração: {data['timestamp'].max() - data['timestamp'].min()}")
            
            if 'tick' in data.columns:
                print(f"🔄 Tick inicial: {data['tick'].min()}")
                print(f"🔄 Tick final: {data['tick'].max()}")
                print(f"🔄 Total de ticks: {data['tick'].max() - data['tick'].min()}")
            
            print("="*60)
            
            # Mostrar amostra dos dados
            print("\n📋 AMOSTRA DOS DADOS (primeiros 5 registros):")
            print(data.head().to_string())
            
        else:
            print(f"⚠️ Nenhum dado encontrado para simulation_id: {simulation_id}")
            
    except Exception as e:
        logger.error(f"❌ Erro ao consultar detalhes da simulação: {e}")

def create_config_examples():
    """Cria exemplos de configuração para simulation IDs"""
    
    examples = f"""
# 🆔 CONFIGURAÇÃO DE SIMULATION IDs

## 1. VIA VARIÁVEL DE AMBIENTE (Recomendado)
export HTC_SIMULATION_ID="experiment_001"
docker-compose up  # ou executar a aplicação

## 2. VIA ARQUIVO DE CONFIGURAÇÃO
# Em src/main/resources/application.conf:
htc {{
    simulation {{
        id = "experiment_002"
    }}
}}

## 3. VIA DOCKER COMPOSE
# Em docker-compose.yml:
services:
  node1:
    environment:
      - HTC_SIMULATION_ID=experiment_003

## 4. GERAÇÃO AUTOMÁTICA
# Se não especificado, o sistema gera automaticamente:
# Formato: sim_YYYYMMDD_HHMMSS_UUID8

## 5. ANÁLISE DE REPRODUTIBILIDADE
# Para comparar múltiplas execuções:
./analysis_helper.sh repro-cassandra experiment_001_run1 experiment_001_run2 experiment_001_run3

## 6. SIMULATION IDs SUGERIDOS
# Para diferentes cenários:
experiment_baseline_001    # Linha de base
experiment_modified_001    # Configuração modificada  
validation_run_001         # Validação
performance_test_001       # Teste de performance
reproducibility_001        # Teste de reprodutibilidade

## 7. PADRÕES RECOMENDADOS
# Para experimentos científicos:
[projeto]_[versao]_[configuracao]_[execucao]

# Exemplos:
mobility_v1_baseline_run1
mobility_v1_hightraffic_run1
mobility_v2_optimized_run1

## 8. COMANDOS ÚTEIS
# Listar IDs disponíveis:
python simulation_id_manager.py --list

# Ver detalhes de uma simulação:
python simulation_id_manager.py --details experiment_001

# Gerar novo ID:
python simulation_id_manager.py --generate --prefix experiment

# Análise de reprodutibilidade:
python simulation_id_manager.py --reproducibility experiment_001_run1 experiment_001_run2
"""
    
    config_file = OUTPUT_PATH / "simulation_id_configuration_guide.txt"
    OUTPUT_PATH.mkdir(parents=True, exist_ok=True)
    
    with open(config_file, 'w') as f:
        f.write(examples)
    
    print(f"📄 Guia de configuração criado: {config_file}")
    return config_file

def main():
    """Função principal"""
    parser = argparse.ArgumentParser(
        description="Gerenciador de Simulation IDs do HTC",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemplos de uso:

  # Listar simulation IDs disponíveis
  python simulation_id_manager.py --list

  # Ver detalhes de uma simulação
  python simulation_id_manager.py --details sim_001

  # Gerar novo simulation ID
  python simulation_id_manager.py --generate

  # Gerar com prefixo personalizado
  python simulation_id_manager.py --generate --prefix experiment

  # Análise de reprodutibilidade
  python simulation_id_manager.py --reproducibility sim_001 sim_002 sim_003

  # Criar guia de configuração
  python simulation_id_manager.py --config-guide
        """
    )
    
    parser.add_argument('--list', action='store_true',
                       help='Listar todos os simulation IDs disponíveis')
    
    parser.add_argument('--details', type=str, metavar='SIM_ID',
                       help='Mostrar detalhes de uma simulação específica')
    
    parser.add_argument('--generate', action='store_true',
                       help='Gerar um novo simulation ID único')
    
    parser.add_argument('--prefix', type=str,
                       help='Prefixo para o simulation ID gerado')
    
    parser.add_argument('--reproducibility', nargs='+', metavar='SIM_ID',
                       help='Executar análise de reprodutibilidade entre simulation IDs')
    
    parser.add_argument('--config-guide', action='store_true',
                       help='Criar guia de configuração para simulation IDs')
    
    args = parser.parse_args()
    
    # Setup
    logger = setup_logging()
    
    try:
        if args.list:
            simulation_ids = list_simulation_ids()
            if simulation_ids:
                print(f"\n💡 Para analisar uma simulação específica:")
                print(f"   python {Path(__file__).name} --details <simulation_id>")
                print(f"\n💡 Para análise de reprodutibilidade:")
                print(f"   ./analysis_helper.sh repro-cassandra {' '.join(simulation_ids[:3])}")
        
        elif args.details:
            show_simulation_details(args.details)
        
        elif args.generate:
            new_id = generate_simulation_id(args.prefix)
            print(f"🆔 Novo Simulation ID gerado: {new_id}")
            print(f"\n💡 Para usar este ID:")
            print(f"   export HTC_SIMULATION_ID=\"{new_id}\"")
            print(f"   # Em seguida, execute sua simulação")
        
        elif args.reproducibility:
            if len(args.reproducibility) < 2:
                print("❌ São necessários pelo menos 2 simulation IDs para análise de reprodutibilidade")
                return 1
            
            print(f"🔄 Executando análise de reprodutibilidade para {len(args.reproducibility)} simulações...")
            
            # Usar o script de análise de reprodutibilidade
            import subprocess
            cmd = [
                "python", 
                str(SCRIPT_DIR / "reproducibility_analysis.py"),
                "--cassandra-sims"
            ] + args.reproducibility
            
            try:
                result = subprocess.run(cmd, capture_output=False)
                return result.returncode
            except Exception as e:
                logger.error(f"❌ Erro ao executar análise de reprodutibilidade: {e}")
                return 1
        
        elif args.config_guide:
            create_config_examples()
        
        else:
            parser.print_help()
            return 0
        
        return 0
        
    except Exception as e:
        logger.error(f"💥 Erro durante execução: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    exit(main())