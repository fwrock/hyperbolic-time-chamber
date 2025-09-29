#!/home/dean/PhD/hyperbolic-time-chamber/scripts/venv/bin/python
"""
Script para diagnosticar e potencialmente corrigir problemas de timestamp no Cassandra
"""

import sys
import os
from pathlib import Path

# Add scripts to path
SCRIPT_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(SCRIPT_DIR / "scripts"))

from cassandra.cluster import Cluster
from cassandra.policies import DCAwareRoundRobinPolicy
import logging
import pandas as pd

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

def connect_cassandra():
    """Connect to Cassandra"""
    try:
        cluster = Cluster(['localhost'])
        
        # Try the correct keyspace: htc_reports (from config.py)
        try:
            session = cluster.connect('htc_reports')
            logger.info("✅ Conectado ao Cassandra (keyspace: htc_reports)")
            return cluster, session
        except Exception as e:
            logger.warning(f"⚠️ Falha ao conectar em 'htc_reports': {e}")
            
            # Try fallback keyspace
            try:
                session = cluster.connect('htc_keyspace')
                logger.info("✅ Conectado ao Cassandra (keyspace: htc_keyspace)")
                return cluster, session
            except Exception as e2:
                logger.warning(f"⚠️ Falha ao conectar em 'htc_keyspace': {e2}")
                
                # Try without keyspace to see what exists
                session = cluster.connect()
                logger.info("✅ Conectado ao Cassandra (sem keyspace específico)")
                
                # List available keyspaces
                result = session.execute("SELECT keyspace_name FROM system_schema.keyspaces;")
                keyspaces = [row.keyspace_name for row in result]
                logger.info(f"📋 Keyspaces disponíveis: {keyspaces}")
                
                return cluster, session
                
    except Exception as e:
        logger.error(f"❌ Erro ao conectar no Cassandra: {e}")
        return None, None

def analyze_timestamp_issues():
    """Analyze timestamp issues in the database"""
    cluster, session = connect_cassandra()
    if not session:
        return
    
    try:
        # Check basic table structure
        logger.info("🔍 Analisando estrutura da tabela...")
        result = session.execute("DESCRIBE TABLE simulation_reports;")
        for row in result:
            print(row)
            
        logger.info("🔍 Analisando timestamps...")
        
        # Try to get raw data without timestamp conversion
        query = """
        SELECT report_id, report_type, time, data 
        FROM simulation_reports 
        WHERE report_type = 'vehicle_flow' 
        LIMIT 10
        """
        
        logger.info("📊 Executando query de diagnóstico...")
        rows = session.execute(query)
        
        valid_rows = 0
        invalid_rows = 0
        
        for i, row in enumerate(rows):
            try:
                logger.info(f"Row {i}: report_id={row.report_id}, type={row.report_type}")
                logger.info(f"  Raw time field: {row.time} (type: {type(row.time)})")
                
                # Try to convert to pandas timestamp
                if hasattr(row, 'time') and row.time is not None:
                    try:
                        ts = pd.to_datetime(row.time, unit='s')
                        logger.info(f"  Converted timestamp: {ts}")
                        valid_rows += 1
                    except Exception as e:
                        logger.error(f"  ❌ Erro na conversão: {e}")
                        invalid_rows += 1
                else:
                    logger.warning(f"  ⚠️ Campo time é None ou não existe")
                    invalid_rows += 1
                    
            except Exception as e:
                logger.error(f"❌ Erro ao processar linha {i}: {e}")
                invalid_rows += 1
                
        logger.info(f"📊 Resumo: {valid_rows} linhas válidas, {invalid_rows} inválidas")
        
    except Exception as e:
        logger.error(f"❌ Erro durante análise: {e}")
    finally:
        if cluster:
            cluster.shutdown()

def count_records_by_type():
    """Count records by type"""
    cluster, session = connect_cassandra()
    if not session:
        return
        
    try:
        logger.info("📊 Contando registros por tipo...")
        
        # Count total records
        result = session.execute("SELECT COUNT(*) FROM simulation_reports;")
        total = result.one().count
        logger.info(f"Total de registros: {total}")
        
        # Count by type
        result = session.execute("SELECT report_type, COUNT(*) FROM simulation_reports GROUP BY report_type;")
        for row in result:
            logger.info(f"  {row.report_type}: {row.count} registros")
            
    except Exception as e:
        logger.error(f"❌ Erro ao contar registros: {e}")
    finally:
        if cluster:
            cluster.shutdown()

def try_alternative_queries():
    """Try alternative queries to get data"""
    cluster, session = connect_cassandra()
    if not session:
        return
        
    try:
        logger.info("🔍 Testando queries alternativas...")
        
        # Try without timestamp conversion
        queries = [
            "SELECT report_id, report_type FROM simulation_reports WHERE report_type = 'vehicle_flow' LIMIT 5;",
            "SELECT * FROM simulation_reports WHERE report_type = 'vehicle_flow' LIMIT 1 ALLOW FILTERING;",
            "SELECT report_id, report_type, data FROM simulation_reports LIMIT 5;",
        ]
        
        for i, query in enumerate(queries):
            logger.info(f"📊 Query {i+1}: {query}")
            try:
                result = session.execute(query)
                count = 0
                for row in result:
                    count += 1
                    logger.info(f"  Row {count}: encontrada")
                    if count == 1:  # Show details of first row
                        for attr in dir(row):
                            if not attr.startswith('_'):
                                try:
                                    value = getattr(row, attr)
                                    if not callable(value):
                                        logger.info(f"    {attr}: {value} ({type(value)})")
                                except:
                                    pass
                logger.info(f"  Total: {count} registros encontrados")
            except Exception as e:
                logger.error(f"  ❌ Erro: {e}")
                
    except Exception as e:
        logger.error(f"❌ Erro geral: {e}")
    finally:
        if cluster:
            cluster.shutdown()

def main():
    """Main function"""
    print("🔍 DIAGNÓSTICO DE PROBLEMAS NO CASSANDRA")
    print("=" * 50)
    
    logger.info("1. Analisando problemas de timestamp...")
    analyze_timestamp_issues()
    
    print("\n" + "=" * 50)
    logger.info("2. Contando registros por tipo...")
    count_records_by_type()
    
    print("\n" + "=" * 50)
    logger.info("3. Testando queries alternativas...")
    try_alternative_queries()
    
    print("\n" + "=" * 50)
    logger.info("✅ Diagnóstico completo!")
    
    print("""
💡 POSSÍVEIS SOLUÇÕES:

1. Se os timestamps estão corrompidos:
   ./manage_cassandra.sh clean  # Limpar dados
   
2. Se há dados mas com formato diferente:
   Modificar o CassandraDataSource para lidar com o formato

3. Se a tabela está vazia:
   Executar uma simulação primeiro para popular dados
   
4. Para testar só o XML:
   ./run_individual_comparison.py --create-sample
""")

if __name__ == "__main__":
    main()