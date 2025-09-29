#!/bin/bash

# Script para gerar relatórios PDF acadêmicos de alta qualidade
# Ideal para inclusão em artigos científicos

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
WHITE='\033[1;37m'
NC='\033[0m' # No Color

# Diretórios
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/scripts/output/academic_reports"

# Função para imprimir cabeçalho
print_header() {
    echo -e "${PURPLE}================================================================${NC}"
    echo -e "${WHITE}📄 GERADOR DE PDFs ACADÊMICOS - HTC SIMULATOR${NC}"
    echo -e "${WHITE}   Relatórios de alta qualidade para artigos científicos${NC}"
    echo -e "${PURPLE}================================================================${NC}"
    echo ""
}

# Função para verificar dependências
check_dependencies() {
    echo -e "${CYAN}🔍 Verificando dependências para geração de PDFs...${NC}"
    
    # Verificar Python
    if ! command -v python3 &> /dev/null; then
        echo -e "${RED}❌ Python3 não encontrado${NC}"
        exit 1
    fi
    
    # Verificar dependências Python
    local missing_deps=()
    
    if ! python3 -c "import matplotlib" 2>/dev/null; then
        missing_deps+=("matplotlib")
    fi
    
    if ! python3 -c "import seaborn" 2>/dev/null; then
        missing_deps+=("seaborn")
    fi
    
    if ! python3 -c "import plotly" 2>/dev/null; then
        missing_deps+=("plotly")
    fi
    
    if ! python3 -c "import kaleido" 2>/dev/null; then
        missing_deps+=("kaleido")
    fi
    
    if [ ${#missing_deps[@]} -gt 0 ]; then
        echo -e "${YELLOW}⚠️ Algumas dependências estão faltando:${NC}"
        for dep in "${missing_deps[@]}"; do
            echo "   - $dep"
        done
        echo ""
        echo -e "${CYAN}💡 Para instalar as dependências:${NC}"
        echo "   pip install matplotlib seaborn plotly kaleido"
        echo ""
        read -p "Deseja instalar automaticamente? (y/n): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo -e "${CYAN}📦 Instalando dependências...${NC}"
            pip install matplotlib seaborn plotly kaleido
            echo -e "${GREEN}✅ Dependências instaladas!${NC}"
        else
            echo -e "${RED}❌ Não é possível gerar PDFs sem as dependências${NC}"
            exit 1
        fi
    else
        echo -e "${GREEN}✅ Todas as dependências estão instaladas!${NC}"
    fi
    echo ""
}

# Função para verificar se Cassandra está rodando
check_cassandra() {
    echo -e "${CYAN}🔍 Verificando Cassandra...${NC}"
    
    if command -v docker &> /dev/null; then
        if docker ps | grep -q cassandra; then
            echo -e "${GREEN}✅ Cassandra está rodando${NC}"
            return 0
        else
            echo -e "${YELLOW}⚠️ Cassandra não está rodando${NC}"
            echo -e "${CYAN}💡 Para iniciar: ./manage_cassandra.sh start${NC}"
            return 1
        fi
    else
        echo -e "${YELLOW}⚠️ Docker não encontrado, não é possível verificar Cassandra${NC}"
        return 1
    fi
}

# Função para gerar PDF de análise de tráfego
generate_traffic_pdf() {
    echo -e "${BLUE}📊 Gerando PDF de Análise de Tráfego...${NC}"
    
    if check_cassandra; then
        echo "   📍 Usando dados do Cassandra"
        python3 "$SCRIPT_DIR/scripts/run_traffic_analysis.py" --source cassandra --limit 5000
    else
        # Procurar por arquivos CSV
        csv_files=$(find "$SCRIPT_DIR" -name "*.csv" -type f | head -1)
        if [ -n "$csv_files" ]; then
            echo "   📍 Usando dados do CSV: $csv_files"
            python3 "$SCRIPT_DIR/scripts/run_traffic_analysis.py" --source csv --file "$csv_files"
        else
            echo -e "${YELLOW}⚠️ Nenhuma fonte de dados encontrada para análise de tráfego${NC}"
            echo -e "${CYAN}💡 Execute uma simulação primeiro ou inicie o Cassandra${NC}"
            return 1
        fi
    fi
    
    echo -e "${GREEN}✅ PDF de análise de tráfego gerado!${NC}"
}

# Função para gerar PDF de comparação
generate_comparison_pdf() {
    echo -e "${BLUE}🔬 Gerando PDF de Comparação de Simuladores...${NC}"
    
    # Procurar por arquivo XML de referência
    xml_files=$(find "$SCRIPT_DIR" -name "*.xml" -type f | grep -E "(matsim|sumo|reference)" | head -1)
    
    if [ -z "$xml_files" ]; then
        echo -e "${YELLOW}⚠️ Nenhum arquivo XML de referência encontrado${NC}"
        echo -e "${CYAN}💡 Criando arquivo de exemplo...${NC}"
        python3 "$SCRIPT_DIR/scripts/compare_simulators.py" --create-sample
        xml_files="$SCRIPT_DIR/scripts/output/sample_reference_events.xml"
    fi
    
    echo "   📍 Usando arquivo XML: $(basename "$xml_files")"
    
    if check_cassandra; then
        echo "   📍 Comparando com dados do Cassandra"
        python3 "$SCRIPT_DIR/scripts/compare_simulators.py" --htc-cassandra --limit 5000 "$xml_files"
    else
        # Procurar por arquivos CSV
        csv_files=$(find "$SCRIPT_DIR" -name "*.csv" -type f | head -1)
        if [ -n "$csv_files" ]; then
            echo "   📍 Comparando com dados do CSV: $(basename "$csv_files")"
            python3 "$SCRIPT_DIR/scripts/compare_simulators.py" --htc-csv "$csv_files" "$xml_files"
        else
            echo -e "${YELLOW}⚠️ Nenhuma fonte de dados HTC encontrada${NC}"
            echo -e "${CYAN}💡 Execute uma simulação primeiro ou inicie o Cassandra${NC}"
            return 1
        fi
    fi
    
    echo -e "${GREEN}✅ PDF de comparação gerado!${NC}"
}

# Função para gerar PDF de comparação individual
generate_individual_pdf() {
    echo -e "${BLUE}🎯 Gerando PDF de Comparação Individual...${NC}"
    
    # Verificar se existe dados de comparação individual
    if [ -f "$SCRIPT_DIR/scripts/output/comparison/individual_comparison_report.json" ]; then
        echo "   📍 Usando dados de comparação individual existentes"
        python3 -c "
import sys
sys.path.insert(0, '$SCRIPT_DIR/scripts')
from visualization.academic_viz import create_academic_pdf_report
import json

# Carregar dados existentes
with open('$SCRIPT_DIR/scripts/output/comparison/individual_comparison_report.json', 'r') as f:
    individual_results = json.load(f)

# Gerar PDF
create_academic_pdf_report(
    'individual',
    individual_results=individual_results,
    output_path='$OUTPUT_DIR',
    filename='individual_comparison_academic.pdf'
)
print('✅ PDF de comparação individual gerado!')
"
    else
        echo -e "${YELLOW}⚠️ Dados de comparação individual não encontrados${NC}"
        echo -e "${CYAN}💡 Execute primeiro: python3 scripts/comparison/individual_comparator.py${NC}"
        return 1
    fi
    
    echo -e "${GREEN}✅ PDF de comparação individual gerado!${NC}"
}

# Função para mostrar arquivos gerados
show_generated_files() {
    echo ""
    echo -e "${PURPLE}================================================================${NC}"
    echo -e "${WHITE}📄 ARQUIVOS PDF GERADOS${NC}"
    echo -e "${PURPLE}================================================================${NC}"
    
    if [ -d "$OUTPUT_DIR" ]; then
        pdf_files=$(find "$OUTPUT_DIR" -name "*.pdf" -type f)
        
        if [ -n "$pdf_files" ]; then
            echo -e "${GREEN}📁 Diretório: $OUTPUT_DIR${NC}"
            echo ""
            
            while IFS= read -r pdf_file; do
                filename=$(basename "$pdf_file")
                filesize=$(du -h "$pdf_file" | cut -f1)
                echo -e "   📄 ${filename} ${CYAN}(${filesize})${NC}"
            done <<< "$pdf_files"
            
            echo ""
            echo -e "${CYAN}💡 Dicas para uso acadêmico:${NC}"
            echo "   • PDFs têm 300 DPI (alta qualidade para impressão)"
            echo "   • Fontes compatíveis com LaTeX (Times New Roman)"
            echo "   • Gráficos otimizados para publicação"
            echo "   • Cores adequadas para impressão em escala de cinza"
            
        else
            echo -e "${YELLOW}⚠️ Nenhum arquivo PDF encontrado${NC}"
        fi
    else
        echo -e "${YELLOW}⚠️ Diretório de output não encontrado${NC}"
    fi
}

# Função principal
main() {
    print_header
    
    # Verificar dependências
    check_dependencies
    
    # Criar diretório de output
    mkdir -p "$OUTPUT_DIR"
    
    # Menu de opções se nenhum argumento fornecido
    if [ $# -eq 0 ]; then
        echo -e "${CYAN}🎯 Selecione o tipo de relatório PDF para gerar:${NC}"
        echo ""
        echo "1) 📊 Análise de Tráfego"
        echo "2) 🔬 Comparação de Simuladores"
        echo "3) 🎯 Comparação Individual"
        echo "4) 📄 Todos os PDFs"
        echo "5) 📋 Listar PDFs existentes"
        echo "0) ❌ Sair"
        echo ""
        read -p "Digite sua opção (0-5): " -n 1 -r
        echo
        
        case $REPLY in
            1)
                generate_traffic_pdf
                ;;
            2)
                generate_comparison_pdf
                ;;
            3)
                generate_individual_pdf
                ;;
            4)
                echo -e "${BLUE}📄 Gerando todos os PDFs...${NC}"
                echo ""
                generate_traffic_pdf
                echo ""
                generate_comparison_pdf
                echo ""
                generate_individual_pdf
                ;;
            5)
                show_generated_files
                exit 0
                ;;
            0)
                echo -e "${CYAN}👋 Saindo...${NC}"
                exit 0
                ;;
            *)
                echo -e "${RED}❌ Opção inválida${NC}"
                exit 1
                ;;
        esac
    else
        # Processar argumentos de linha de comando
        case "$1" in
            "traffic")
                generate_traffic_pdf
                ;;
            "comparison")
                generate_comparison_pdf
                ;;
            "individual")
                generate_individual_pdf
                ;;
            "all")
                generate_traffic_pdf
                echo ""
                generate_comparison_pdf
                echo ""
                generate_individual_pdf
                ;;
            "list")
                show_generated_files
                exit 0
                ;;
            "help"|"--help"|"-h")
                echo "Uso: $0 [traffic|comparison|individual|all|list]"
                echo ""
                echo "Opções:"
                echo "  traffic      - Gerar PDF de análise de tráfego"
                echo "  comparison   - Gerar PDF de comparação de simuladores"
                echo "  individual   - Gerar PDF de comparação individual"
                echo "  all          - Gerar todos os PDFs"
                echo "  list         - Listar PDFs existentes"
                echo "  help         - Mostrar esta ajuda"
                exit 0
                ;;
            *)
                echo -e "${RED}❌ Opção inválida: $1${NC}"
                echo "Use '$0 help' para ver as opções disponíveis"
                exit 1
                ;;
        esac
    fi
    
    echo ""
    show_generated_files
    
    echo ""
    echo -e "${GREEN}🎉 Geração de PDFs concluída!${NC}"
    echo -e "${CYAN}📖 Use os PDFs gerados em seus artigos científicos${NC}"
}

# Executar função principal
main "$@"