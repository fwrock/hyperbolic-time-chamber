#!/bin/bash
# Script para validar determinismo da simulação de referência comparando events.xml

echo "🔬 VALIDAÇÃO DE DETERMINISMO - SIMULAÇÃO DE REFERÊNCIA (events.xml)"
echo "=================================================================="

# Configurações
BASE_DIR="/home/dean/PhD/hyperbolic-time-chamber"
SCRIPTS_DIR="$BASE_DIR/scripts"
OUTPUT_DIR="$SCRIPTS_DIR/reference_xml_determinism"

# Criar diretório de saída
mkdir -p "$OUTPUT_DIR"

echo ""
echo "📋 CONFIGURAÇÃO DO TESTE:"
echo "  • Simulação: Simulação de referência"
echo "  • Execuções: 2 execuções idênticas"
echo "  • Comparação: Arquivos events.xml"
echo "  • Diretório de saída: $OUTPUT_DIR"
echo ""

echo "🚀 EXECUTANDO SIMULAÇÃO DE REFERÊNCIA - EXECUÇÃO 1"
echo "================================================"

# Execução 1
echo "📊 Executando primeira simulação..."
echo "⏰ Início: $(date)"

# Aqui você deve colocar o comando exato para executar sua simulação de referência
# Exemplo para MATSim:
# java -cp matsim.jar org.matsim.run.RunMatsim config.xml

# Exemplo para SUMO:
# sumo -c simulation.sumocfg --xml-output events1.xml

# Por enquanto, vou assumir um comando genérico
echo "🔧 CONFIGURE AQUI O COMANDO DA SUA SIMULAÇÃO DE REFERÊNCIA"
echo "   Exemplos:"
echo "   • MATSim: java -cp matsim.jar org.matsim.run.RunMatsim config.xml"
echo "   • SUMO: sumo -c simulation.sumocfg --xml-output events1.xml"
echo "   • Outro: seu_simulador --config config.xml --output events1.xml"

# Simular execução para demonstração
echo "⚠️ SIMULANDO EXECUÇÃO (substitua pelo comando real)..."
echo "   Comando seria algo como: seu_simulador --output $OUTPUT_DIR/events1.xml"

# Criar um arquivo XML de exemplo para demonstração
cat > "$OUTPUT_DIR/events1.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">
<event time="7" type="actend" person="trip_317_1" link="201" actType="h" action="ok" />
<event time="7" type="departure" person="trip_317_1" link="201" legMode="car" action="ok" />
<event time="7" type="PersonEntersVehicle" person="trip_317_1" vehicle="trip_317_1" action="ok" />
<event time="7" type="wait2link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="7" type="entered link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="11" type="left link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="11" type="entered link" person="trip_317_1" link="1009" vehicle="trip_317_1" action="ok" />
<event time="14" type="left link" person="trip_317_1" link="1009" vehicle="trip_317_1" action="ok" />
<event time="14" type="entered link" person="trip_317_1" link="1010" vehicle="trip_317_1" action="ok" />
<event time="17" type="left link" person="trip_317_1" link="1010" vehicle="trip_317_1" action="ok" />
<event time="17" type="entered link" person="trip_317_1" link="1011" vehicle="trip_317_1" action="ok" />
<event time="29" type="actend" person="trip_380_1" link="3067" actType="h" action="ok" />
<event time="29" type="departure" person="trip_380_1" link="3067" legMode="car" action="ok" />
<event time="29" type="PersonEntersVehicle" person="trip_380_1" vehicle="trip_380_1" action="ok" />
<event time="29" type="wait2link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="29" type="entered link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="31" type="left link" person="trip_317_1" link="1011" vehicle="trip_317_1" action="ok" />
<event time="31" type="entered link" person="trip_317_1" link="1012" vehicle="trip_317_1" action="ok" />
<event time="33" type="left link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="33" type="entered link" person="trip_380_1" link="81" vehicle="trip_380_1" action="ok" />
<event time="38" type="actend" person="trip_1037_1" link="423" actType="h" action="ok" />
<event time="38" type="departure" person="trip_1037_1" link="423" legMode="car" action="ok" />
</events>
EOF

echo "✅ Primeira execução concluída"
echo "📄 Arquivo gerado: $OUTPUT_DIR/events1.xml"

echo ""
echo "⏳ Aguardando 5 segundos antes da segunda execução..."
sleep 5

echo ""
echo "🚀 EXECUTANDO SIMULAÇÃO DE REFERÊNCIA - EXECUÇÃO 2"
echo "================================================"

# Execução 2
echo "📊 Executando segunda simulação..."
echo "⏰ Início: $(date)"

echo "⚠️ SIMULANDO EXECUÇÃO (substitua pelo comando real)..."
echo "   Comando seria algo como: seu_simulador --output $OUTPUT_DIR/events2.xml"

# Para demonstração, criar arquivo idêntico (determinístico) ou ligeiramente diferente (não-determinístico)
# Vou criar um arquivo ligeiramente diferente para mostrar detecção de não-determinismo
cat > "$OUTPUT_DIR/events2.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">
<event time="7" type="actend" person="trip_317_1" link="201" actType="h" action="ok" />
<event time="7" type="departure" person="trip_317_1" link="201" legMode="car" action="ok" />
<event time="7" type="PersonEntersVehicle" person="trip_317_1" vehicle="trip_317_1" action="ok" />
<event time="7" type="wait2link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="7" type="entered link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="11" type="left link" person="trip_317_1" link="201" vehicle="trip_317_1" action="ok" />
<event time="11" type="entered link" person="trip_317_1" link="1009" vehicle="trip_317_1" action="ok" />
<event time="14" type="left link" person="trip_317_1" link="1009" vehicle="trip_317_1" action="ok" />
<event time="14" type="entered link" person="trip_317_1" link="1010" vehicle="trip_317_1" action="ok" />
<event time="17" type="left link" person="trip_317_1" link="1010" vehicle="trip_317_1" action="ok" />
<event time="17" type="entered link" person="trip_317_1" link="1011" vehicle="trip_317_1" action="ok" />
<event time="29" type="actend" person="trip_380_1" link="3067" actType="h" action="ok" />
<event time="29" type="departure" person="trip_380_1" link="3067" legMode="car" action="ok" />
<event time="29" type="PersonEntersVehicle" person="trip_380_1" vehicle="trip_380_1" action="ok" />
<event time="29" type="wait2link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="29" type="entered link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="31" type="left link" person="trip_317_1" link="1011" vehicle="trip_317_1" action="ok" />
<event time="31" type="entered link" person="trip_317_1" link="1012" vehicle="trip_317_1" action="ok" />
<event time="33" type="left link" person="trip_380_1" link="3067" vehicle="trip_380_1" action="ok" />
<event time="33" type="entered link" person="trip_380_1" link="81" vehicle="trip_380_1" action="ok" />
<event time="39" type="actend" person="trip_1037_1" link="423" actType="h" action="ok" />
<event time="39" type="departure" person="trip_1037_1" link="423" legMode="car" action="ok" />
</events>
EOF

echo "✅ Segunda execução concluída"
echo "📄 Arquivo gerado: $OUTPUT_DIR/events2.xml"

echo ""
echo "🔍 VERIFICANDO ARQUIVOS GERADOS"
echo "==============================="

if [ -f "$OUTPUT_DIR/events1.xml" ] && [ -f "$OUTPUT_DIR/events2.xml" ]; then
    echo "✅ Ambos os arquivos XML foram gerados"
    
    size1=$(stat -f%z "$OUTPUT_DIR/events1.xml" 2>/dev/null || stat -c%s "$OUTPUT_DIR/events1.xml")
    size2=$(stat -f%z "$OUTPUT_DIR/events2.xml" 2>/dev/null || stat -c%s "$OUTPUT_DIR/events2.xml")
    
    echo "📊 Tamanhos dos arquivos:"
    echo "  • events1.xml: $size1 bytes"
    echo "  • events2.xml: $size2 bytes"
    
    # Contagem rápida de eventos
    events1=$(grep -c "<event" "$OUTPUT_DIR/events1.xml" 2>/dev/null || echo "0")
    events2=$(grep -c "<event" "$OUTPUT_DIR/events2.xml" 2>/dev/null || echo "0")
    
    echo "📈 Número de eventos:"
    echo "  • events1.xml: $events1 eventos"
    echo "  • events2.xml: $events2 eventos"
    
else
    echo "❌ Erro: Arquivos XML não foram gerados corretamente"
    exit 1
fi

echo ""
echo "🔬 EXECUTANDO ANÁLISE DE DETERMINISMO"
echo "====================================="

cd "$SCRIPTS_DIR"

echo "📊 Comparando arquivos events.xml..."

# Executar comparação usando o script Python
/home/dean/PhD/hyperbolic-time-chamber/.venv/bin/python compare_events_xml.py \
    "$OUTPUT_DIR/events1.xml" \
    "$OUTPUT_DIR/events2.xml" \
    --output "$OUTPUT_DIR" \
    > "$OUTPUT_DIR/comparison_log.txt" 2>&1

if [ $? -eq 0 ]; then
    echo "✅ Análise de determinismo concluída"
    
    # Mostrar resultado principal
    if [ -f "$OUTPUT_DIR/xml_determinism_report.txt" ]; then
        echo ""
        echo "📄 RESUMO DOS RESULTADOS:"
        echo "========================="
        grep -E "(SCORE DE DETERMINISMO|CONCLUSÃO)" "$OUTPUT_DIR/xml_determinism_report.txt" 2>/dev/null || echo "Ver relatório completo: $OUTPUT_DIR/xml_determinism_report.txt"
    fi
    
else
    echo "❌ Erro na análise de determinismo"
    echo "📄 Log de erro: $OUTPUT_DIR/comparison_log.txt"
fi

echo ""
echo "📁 ARQUIVOS GERADOS:"
echo "==================="
find "$OUTPUT_DIR" -name "*.xml" -o -name "*.txt" -o -name "*.json" | sort | while read file; do
    size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file")
    echo "  📄 $(basename "$file") - $size bytes"
done

echo ""
echo "🎯 CONCLUSÃO DA VALIDAÇÃO"
echo "========================="
echo "✅ Validação da simulação de referência concluída!"
echo "📁 Resultados salvos em: $OUTPUT_DIR"
echo ""
echo "🔍 COMO INTERPRETAR OS RESULTADOS:"
echo "  • Score > 0.95: Simulação de referência É DETERMINÍSTICA ✅"
echo "  • Score 0.80-0.95: Simulação de referência é QUASE DETERMINÍSTICA ⚠️"
echo "  • Score < 0.80: Simulação de referência NÃO É DETERMINÍSTICA ❌"
echo ""
echo "📊 PRÓXIMOS PASSOS:"
echo "  1. Verifique o relatório: $OUTPUT_DIR/xml_determinism_report.txt"
echo "  2. Analise as diferenças: $OUTPUT_DIR/xml_determinism_comparison.json"
echo "  3. Se não for determinística, investigue:"
echo "     • Random seed configuration"
echo "     • Non-deterministic algorithms"
echo "     • Concurrent execution issues"
echo ""
echo "🛠️ PARA USAR COM SUA SIMULAÇÃO REAL:"
echo "  1. Substitua os comandos simulados pelos comandos reais"
echo "  2. Ajuste os caminhos dos arquivos de saída"
echo "  3. Configure random seed se necessário"
echo "  4. Execute novamente o teste"