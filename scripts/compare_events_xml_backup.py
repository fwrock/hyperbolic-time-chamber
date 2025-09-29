#!/usr/bin/env python3
"""
Comparador de arquivos events.xml para validar determinismo da simulação de referência
"""

import xml.etree.ElementTree as ET
import pandas as pd
import numpy as np
import sys
import logging
from pathlib import Path
from typing import Dict, List, Tuple
import argparse
from collections import defaultdict
import hashlib

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class EventsXMLComparator:
    """
    Comparador de arquivos events.xml para análise de determinismo
    """
    
    def __init__(self, output_dir: str = "xml_determinism_validation"):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
    
    def parse_events_xml(self, file_path):
        """Wrapper method to call the global parse function"""
        return parse_events_xml(file_path)
    
    def compare_events_determinism(self, xml_file1: str, xml_file2: str) -> Dict:
        """
        Compara dois arquivos events.xml para validar determinismo
        """
        logger.info(f"🔬 Comparando determinismo entre {xml_file1} e {xml_file2}")
        
        # Parse dos arquivos
        df1 = self.parse_events_xml(xml_file1)
        df2 = self.parse_events_xml(xml_file2)
        
        if df1 is None or df2 is None or df1.empty or df2.empty:
            logger.error("❌ Erro: Não foi possível carregar os arquivos XML")
            return {}
        
        results = {
            "files": {
                "file1": xml_file1,
                "file2": xml_file2
            },
            "basic_statistics": self._compare_basic_stats(df1, df2),
            "temporal_analysis": self._compare_temporal_patterns(df1, df2),
            "event_sequence_analysis": self._compare_event_sequences(df1, df2),
            "exact_match_analysis": self._compare_exact_matches(xml_file1, xml_file2)
        }
        
        # Calcular score de determinismo
        results["determinism_score"] = self._calculate_determinism_score(results)
        results["conclusion"] = self._get_conclusion(results["determinism_score"])
        
        # Salvar relatórios
        self._save_reports(results)
        
        return results
    
    def _compare_basic_stats(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        
def parse_events_xml(file_path):
    """
    Parse events XML file and return DataFrame with events
    Handles both wrapped XML (with root element) and raw event sequences
    """
    logger.info(f"📄 Parseando {file_path}...")
    
    try:
        # First, try to parse as normal XML with root element
        try:
            tree = ET.parse(file_path)
            root = tree.getroot()
            events = []
            for event in root.findall('.//event'):
                event_data = {
                    'time': event.get('time'),
                    'type': event.get('type'),
                    'person': event.get('person'),
                    'link': event.get('link'),
                    'actType': event.get('actType'),
                    'action': event.get('action'),
                    'vehicle': event.get('vehicle'),
                    'legMode': event.get('legMode')
                }
                events.append(event_data)
        except ET.ParseError:
            # If normal parsing fails, try parsing as sequence of events without root
            logger.info("⚠️ Parsing normal falhou, tentando como sequência de eventos...")
            events = []
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
                # Wrap content in a root element to make it valid XML
                wrapped_content = f"<events>\n{content}\n</events>"
                
                try:
                    root = ET.fromstring(wrapped_content)
                    for event in root.findall('event'):
                        event_data = {
                            'time': event.get('time'),
                            'type': event.get('type'),
                            'person': event.get('person'),
                            'link': event.get('link'),
                            'actType': event.get('actType'),
                            'action': event.get('action'),
                            'vehicle': event.get('vehicle'),
                            'legMode': event.get('legMode')
                        }
                        events.append(event_data)
                except ET.ParseError as inner_e:
                    logger.error(f"❌ Erro ao parsear mesmo com wrapper: {inner_e}")
                    return None
        
        df = pd.DataFrame(events)
        logger.info(f"✅ Parseado com sucesso: {len(df)} eventos encontrados")
        return df
        
    except Exception as e:
        logger.error(f"❌ Erro geral ao processar {file_path}: {e}")
        return None


class EventsComparator:
    """
    Classe para comparar eventos XML e validar determinismo
    """
    
    def parse_events_xml(self, file_path):
        """Wrapper method to call the global parse function"""
        return parse_events_xml(file_path)
    
    def compare_events_determinism(self, xml_file1: str, xml_file2: str) -> Dict:
        """
        Compara dois arquivos events.xml para validar determinismo
        """
        logger.info(f"🔬 Comparando determinismo entre {xml_file1} e {xml_file2}")
        
        # Parse dos arquivos
        df1 = self.parse_events_xml(xml_file1)
        df2 = self.parse_events_xml(xml_file2)
        
        if df1.empty or df2.empty:
            logger.error("❌ Erro: Não foi possível carregar os arquivos XML")
            return {}
        
        results = {
            "files": {
                "file1": xml_file1,
                "file2": xml_file2
            },
            "basic_statistics": self._compare_basic_stats(df1, df2),
            "temporal_analysis": self._compare_temporal_patterns(df1, df2),
            "event_sequence_analysis": self._compare_event_sequences(df1, df2),
            "person_vehicle_analysis": self._compare_persons_vehicles(df1, df2),
            "exact_match_analysis": self._compare_exact_matches(df1, df2),
            "determinism_score": 0.0,
            "conclusion": ""
        }
        
        # Calcular score de determinismo
        results["determinism_score"] = self._calculate_determinism_score(results)
        results["conclusion"] = self._generate_conclusion(results)
        
        # Salvar resultados
        self._save_comparison_results(results)
        
        return results
    
    def _compare_basic_stats(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        """Comparar estatísticas básicas"""
        logger.info("📊 Comparando estatísticas básicas...")
        
        return {
            "event_counts": {
                "file1": len(df1),
                "file2": len(df2),
                "difference": abs(len(df1) - len(df2)),
                "match": len(df1) == len(df2)
            },
            "unique_persons": {
                "file1": df1['person'].nunique(),
                "file2": df2['person'].nunique(),
                "difference": abs(df1['person'].nunique() - df2['person'].nunique()),
                "match": df1['person'].nunique() == df2['person'].nunique()
            },
            "unique_vehicles": {
                "file1": df1['vehicle'].nunique() if 'vehicle' in df1.columns else 0,
                "file2": df2['vehicle'].nunique() if 'vehicle' in df2.columns else 0
            },
            "event_types": {
                "file1": df1['type'].value_counts().to_dict(),
                "file2": df2['type'].value_counts().to_dict()
            },
            "time_range": {
                "file1": {"min": df1['time'].min(), "max": df1['time'].max()},
                "file2": {"min": df2['time'].min(), "max": df2['time'].max()}
            }
        }
    
    def _compare_temporal_patterns(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        """Comparar padrões temporais"""
        logger.info("⏰ Comparando padrões temporais...")
        
        # Eventos por tempo
        events_by_time1 = df1.groupby('time').size()
        events_by_time2 = df2.groupby('time').size()
        
        # Tempos em comum
        common_times = set(events_by_time1.index) & set(events_by_time2.index)
        unique_times1 = set(events_by_time1.index) - set(events_by_time2.index)
        unique_times2 = set(events_by_time2.index) - set(events_by_time1.index)
        
        return {
            "common_times": len(common_times),
            "unique_times_file1": len(unique_times1),
            "unique_times_file2": len(unique_times2),
            "temporal_overlap_ratio": len(common_times) / len(set(events_by_time1.index) | set(events_by_time2.index)) if (set(events_by_time1.index) | set(events_by_time2.index)) else 0,
            "identical_time_distribution": events_by_time1.equals(events_by_time2)
        }
    
    def _compare_event_sequences(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        """Comparar sequências de eventos"""
        logger.info("🔄 Comparando sequências de eventos...")
        
        # Ordenar por tempo para análise sequencial
        df1_sorted = df1.sort_values(['time', 'person', 'type']).reset_index(drop=True)
        df2_sorted = df2.sort_values(['time', 'person', 'type']).reset_index(drop=True)
        
        # Comparar primeiros N eventos
        n_events = min(1000, len(df1_sorted), len(df2_sorted))
        
        exact_matches = 0
        for i in range(n_events):
            event1 = df1_sorted.iloc[i]
            event2 = df2_sorted.iloc[i]
            
            # Comparar campos principais
            if (event1['time'] == event2['time'] and 
                event1['type'] == event2['type'] and
                event1['person'] == event2['person'] and
                event1['link'] == event2['link']):
                exact_matches += 1
        
        return {
            "first_n_events_compared": n_events,
            "exact_sequence_matches": exact_matches,
            "sequence_match_ratio": exact_matches / n_events if n_events > 0 else 0,
            "perfect_sequence_match": exact_matches == n_events
        }
    
    def _compare_persons_vehicles(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        """Comparar pessoas e veículos"""
        logger.info("🚗 Comparando pessoas e veículos...")
        
        persons1 = set(df1['person'].unique())
        persons2 = set(df2['person'].unique())
        
        vehicles1 = set(df1['vehicle'].dropna().unique()) if 'vehicle' in df1.columns else set()
        vehicles2 = set(df2['vehicle'].dropna().unique()) if 'vehicle' in df2.columns else set()
        
        return {
            "persons": {
                "common": len(persons1 & persons2),
                "unique_file1": len(persons1 - persons2),
                "unique_file2": len(persons2 - persons1),
                "overlap_ratio": len(persons1 & persons2) / len(persons1 | persons2) if (persons1 | persons2) else 0
            },
            "vehicles": {
                "common": len(vehicles1 & vehicles2),
                "unique_file1": len(vehicles1 - vehicles2),
                "unique_file2": len(vehicles2 - vehicles1),
                "overlap_ratio": len(vehicles1 & vehicles2) / len(vehicles1 | vehicles2) if (vehicles1 | vehicles2) else 0
            }
        }
    
    def _compare_exact_matches(self, df1: pd.DataFrame, df2: pd.DataFrame) -> Dict:
        """Comparar correspondências exatas"""
        logger.info("🎯 Verificando correspondências exatas...")
        
        # Criar hash de cada evento para comparação exata
        def create_event_hash(row):
            event_str = f"{row['time']}_{row['type']}_{row['person']}_{row['link']}"
            return hashlib.md5(event_str.encode()).hexdigest()
        
        df1_sorted = df1.sort_values(['time', 'person', 'type']).reset_index(drop=True)
        df2_sorted = df2.sort_values(['time', 'person', 'type']).reset_index(drop=True)
        
        hashes1 = df1_sorted.apply(create_event_hash, axis=1)
        hashes2 = df2_sorted.apply(create_event_hash, axis=1)
        
        # Verificar se os arquivos são idênticos
        identical_files = len(df1_sorted) == len(df2_sorted) and all(hashes1 == hashes2)
        
        # Contar eventos únicos vs comuns
        set_hashes1 = set(hashes1)
        set_hashes2 = set(hashes2)
        
        return {
            "identical_files": identical_files,
            "common_events": len(set_hashes1 & set_hashes2),
            "unique_events_file1": len(set_hashes1 - set_hashes2),
            "unique_events_file2": len(set_hashes2 - set_hashes1),
            "exact_match_ratio": len(set_hashes1 & set_hashes2) / len(set_hashes1 | set_hashes2) if (set_hashes1 | set_hashes2) else 0
        }
    
    def _calculate_determinism_score(self, results: Dict) -> float:
        """Calcular score de determinismo (0-1)"""
        score_components = []
        
        # Contagem de eventos (20%)
        basic = results["basic_statistics"]
        event_match = 1.0 if basic["event_counts"]["match"] else 0.0
        score_components.append(("event_counts", event_match, 0.2))
        
        # Sobreposição temporal (25%)
        temporal = results["temporal_analysis"]
        temporal_score = temporal.get("temporal_overlap_ratio", 0)
        score_components.append(("temporal_patterns", temporal_score, 0.25))
        
        # Sequência de eventos (25%)
        sequence = results["event_sequence_analysis"]
        sequence_score = sequence.get("sequence_match_ratio", 0)
        score_components.append(("event_sequences", sequence_score, 0.25))
        
        # Correspondências exatas (30%)
        exact = results["exact_match_analysis"]
        exact_score = exact.get("exact_match_ratio", 0)
        score_components.append(("exact_matches", exact_score, 0.3))
        
        # Calcular média ponderada
        total_score = sum(score * weight for _, score, weight in score_components)
        
        logger.info(f"🎯 Componentes do score de determinismo:")
        for name, score, weight in score_components:
            logger.info(f"  • {name}: {score:.3f} (peso: {weight})")
        
        return total_score
    
    def _generate_conclusion(self, results: Dict) -> str:
        """Gerar conclusão sobre determinismo"""
        score = results["determinism_score"]
        exact_match = results["exact_match_analysis"]["identical_files"]
        
        if exact_match:
            return "PERFEITAMENTE DETERMINÍSTICO - Arquivos XML são IDÊNTICOS"
        elif score >= 0.95:
            return "ALTAMENTE DETERMINÍSTICO - Diferenças mínimas entre execuções"
        elif score >= 0.80:
            return "DETERMINÍSTICO - Resultados consistentes com pequenas variações"
        elif score >= 0.60:
            return "PARCIALMENTE DETERMINÍSTICO - Algumas diferenças significativas"
        elif score >= 0.30:
            return "POUCO DETERMINÍSTICO - Muitas diferenças entre execuções"
        else:
            return "NÃO DETERMINÍSTICO - Execuções produzem resultados muito diferentes"
    
    def _save_comparison_results(self, results: Dict):
        """Salvar resultados da comparação"""
        import json
        
        output_file = self.output_dir / "xml_determinism_comparison.json"
        with open(output_file, 'w') as f:
            json.dump(results, f, indent=2, default=str)
        
        # Também salvar um relatório texto
        report_file = self.output_dir / "xml_determinism_report.txt"
        with open(report_file, 'w') as f:
            f.write("=" * 80 + "\n")
            f.write("RELATÓRIO DE DETERMINISMO - ARQUIVOS EVENTS.XML\n")
            f.write("=" * 80 + "\n\n")
            
            f.write(f"📁 Arquivo 1: {results['files']['file1']}\n")
            f.write(f"📁 Arquivo 2: {results['files']['file2']}\n\n")
            
            f.write("📊 ESTATÍSTICAS BÁSICAS:\n")
            basic = results["basic_statistics"]
            f.write(f"  • Eventos: {basic['event_counts']['file1']} vs {basic['event_counts']['file2']}")
            f.write(" ✅\n" if basic['event_counts']['match'] else " ❌\n")
            f.write(f"  • Pessoas: {basic['unique_persons']['file1']} vs {basic['unique_persons']['file2']}")
            f.write(" ✅\n" if basic['unique_persons']['match'] else " ❌\n")
            
            f.write("\n⏰ ANÁLISE TEMPORAL:\n")
            temporal = results["temporal_analysis"]
            f.write(f"  • Tempos em comum: {temporal['common_times']}\n")
            f.write(f"  • Sobreposição temporal: {temporal['temporal_overlap_ratio']:.3f}\n")
            
            f.write("\n🔄 SEQUÊNCIA DE EVENTOS:\n")
            sequence = results["event_sequence_analysis"]
            f.write(f"  • Eventos analisados: {sequence['first_n_events_compared']}\n")
            f.write(f"  • Correspondências exatas: {sequence['exact_sequence_matches']}\n")
            f.write(f"  • Taxa de correspondência: {sequence['sequence_match_ratio']:.3f}\n")
            
            f.write("\n🎯 CORRESPONDÊNCIAS EXATAS:\n")
            exact = results["exact_match_analysis"]
            f.write(f"  • Arquivos idênticos: {'SIM' if exact['identical_files'] else 'NÃO'}\n")
            f.write(f"  • Taxa de correspondência exata: {exact['exact_match_ratio']:.3f}\n")
            
            f.write(f"\n🎯 SCORE DE DETERMINISMO: {results['determinism_score']:.3f}\n")
            f.write(f"📝 CONCLUSÃO: {results['conclusion']}\n")
        
        logger.info(f"💾 Resultados salvos em:")
        logger.info(f"  📄 {output_file}")
        logger.info(f"  📄 {report_file}")

def main():
    parser = argparse.ArgumentParser(description='Comparar arquivos events.xml para validar determinismo')
    parser.add_argument('xml_file1', help='Primeiro arquivo events.xml')
    parser.add_argument('xml_file2', help='Segundo arquivo events.xml') 
    parser.add_argument('--output', '-o', default='xml_determinism_validation',
                        help='Diretório de saída (padrão: xml_determinism_validation)')
    
    args = parser.parse_args()
    
    # Verificar se os arquivos existem
    for xml_file in [args.xml_file1, args.xml_file2]:
        if not Path(xml_file).exists():
            logger.error(f"❌ Arquivo não encontrado: {xml_file}")
            sys.exit(1)
    
    # Executar comparação
    comparator = EventsXMLComparator(args.output)
    results = comparator.compare_events_determinism(args.xml_file1, args.xml_file2)
    
    if results:
        print("\n" + "="*80)
        print("🔬 RESULTADO DA VALIDAÇÃO DE DETERMINISMO")
        print("="*80)
        print(f"🎯 Score de determinismo: {results['determinism_score']:.3f}")
        print(f"📝 Conclusão: {results['conclusion']}")
        
        if results["exact_match_analysis"]["identical_files"]:
            print("🎉 SIMULAÇÃO DE REFERÊNCIA É PERFEITAMENTE DETERMINÍSTICA!")
        elif results['determinism_score'] >= 0.95:
            print("✅ SIMULAÇÃO DE REFERÊNCIA É ALTAMENTE DETERMINÍSTICA!")
        elif results['determinism_score'] >= 0.80:
            print("✅ SIMULAÇÃO DE REFERÊNCIA É DETERMINÍSTICA!")
        else:
            print("❌ SIMULAÇÃO DE REFERÊNCIA NÃO É DETERMINÍSTICA!")
        
        print("="*80)

if __name__ == "__main__":
    main()