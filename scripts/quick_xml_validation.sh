#!/bin/bash

# Script Quick Start: Validação de Determinismo XML
# Para executar: chmod +x quick_xml_validation.sh && ./quick_xml_validation.sh

echo "🚀 VALIDAÇÃO RÁPIDA DE DETERMINISMO XML"
echo "======================================"
echo

# Verificar se os scripts existem
if [[ ! -f "compare_events_xml.py" ]]; then
    echo "❌ Erro: compare_events_xml.py não encontrado!"
    echo "   Execute este script no diretório scripts/"
    exit 1
fi

if [[ ! -f "validate_reference_xml_determinism.sh" ]]; then
    echo "❌ Erro: validate_reference_xml_determinism.sh não encontrado!"
    echo "   Execute este script no diretório scripts/"
    exit 1
fi

echo "🔍 CENÁRIOS DE VALIDAÇÃO DISPONÍVEIS:"
echo
echo "1) 🤖 AUTOMÁTICO - Executa simulação duas vezes e compara"
echo "   ./validate_reference_xml_determinism.sh"
echo
echo "2) 📁 MANUAL - Compara dois arquivos XML existentes"
echo "   python compare_events_xml.py arquivo1.xml arquivo2.xml"
echo
echo "3) 🧪 TESTE - Gera XMLs de exemplo para testar o sistema"
echo "   python compare_events_xml.py --generate-test"
echo

read -p "🤔 Escolha uma opção (1/2/3) ou ENTER para sair: " choice

case $choice in
    1)
        echo
        echo "🤖 MODO AUTOMÁTICO SELECIONADO"
        echo "============================="
        echo
        echo "⚠️  IMPORTANTE: Edite validate_reference_xml_determinism.sh primeiro!"
        echo "   Substitua os comandos de exemplo pelos comandos reais da sua simulação."
        echo
        read -p "✅ Já editou o script? (s/N): " edited
        if [[ $edited =~ ^[Ss]$ ]]; then
            echo
            echo "🚀 Executando validação automática..."
            ./validate_reference_xml_determinism.sh
        else
            echo
            echo "📝 Para editar o script:"
            echo "   nano validate_reference_xml_determinism.sh"
            echo
            echo "   Procure por 'SIMULANDO EXECUÇÃO' e substitua pelos comandos reais."
        fi
        ;;
    
    2)
        echo
        echo "📁 MODO MANUAL SELECIONADO"
        echo "========================="
        echo
        read -p "📄 Caminho do primeiro arquivo XML: " file1
        read -p "📄 Caminho do segundo arquivo XML: " file2
        
        if [[ -f "$file1" && -f "$file2" ]]; then
            echo
            echo "🔄 Comparando $file1 com $file2..."
            python compare_events_xml.py "$file1" "$file2"
        else
            echo "❌ Um ou ambos os arquivos não existem!"
        fi
        ;;
    
    3)
        echo
        echo "🧪 MODO TESTE SELECIONADO"
        echo "========================"
        echo
        echo "🔄 Gerando XMLs de exemplo para teste..."
        python compare_events_xml.py --generate-test
        echo
        echo "✅ Teste concluído! Verifique os arquivos gerados."
        ;;
    
    *)
        echo
        echo "👋 Saindo... Use o guia em docs/xml_determinism_validation_guide.md"
        exit 0
        ;;
esac

echo
echo "📊 INTERPRETAÇÃO DOS RESULTADOS:"
echo "================================"
echo "• Score = 1.000: PERFEITAMENTE DETERMINÍSTICO ✅"
echo "• Score ≥ 0.95:  ALTAMENTE DETERMINÍSTICO ✅"
echo "• Score ≥ 0.80:  DETERMINÍSTICO ✅"
echo "• Score < 0.80:  INVESTIGAR ⚠️"
echo "• Score < 0.60:  NÃO DETERMINÍSTICO ❌"
echo
echo "📖 Para mais detalhes: docs/xml_determinism_validation_guide.md"