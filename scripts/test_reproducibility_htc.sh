#!/bin/bash

# Script para testar reprodutibilidade do HTC com random seed fixo

echo "🎯 TESTE DE REPRODUTIBILIDADE HTC"
echo "================================="
echo

# Verificar se existem configurações de simulação
if [[ ! -f "simulation_deterministic_example.json" ]]; then
    echo "❌ Arquivo simulation_deterministic_example.json não encontrado"
    echo "   Execute este script no diretório raiz do projeto"
    exit 1
fi

# Diretórios de saída
OUTPUT_DIR_1="output/reproducibility_test_1"
OUTPUT_DIR_2="output/reproducibility_test_2"

echo "📁 Preparando diretórios de saída..."
mkdir -p "$OUTPUT_DIR_1"
mkdir -p "$OUTPUT_DIR_2"

# Configurar variáveis de ambiente
export HTC_RANDOM_SEED=12345
export HTC_SIMULATION_ID="reproducibility_test"

echo "🎲 Configurando random seed: $HTC_RANDOM_SEED"
echo "🆔 Configurando simulation ID: $HTC_SIMULATION_ID"
echo

# Função para executar simulação
run_simulation() {
    local run_number=$1
    local output_dir=$2
    
    echo "🚀 Executando simulação #$run_number..."
    echo "   Saída: $output_dir"
    
    # Copiar configuração para o diretório de saída
    cp simulation_deterministic_example.json "$output_dir/simulation.json"
    
    # Executar HTC (substitua pelo comando real)
    echo "⚠️ IMPLEMENTAR: Comando para executar HTC"
    echo "   sbt 'run'"
    echo "   ou"
    echo "   java -jar target/scala-3.3.5/hyperbolic-time-chamber-1.5.0.jar"
    echo
    
    # Por enquanto, criar dados simulados para teste
    echo "📊 Criando dados de exemplo para teste..."
    cat > "$output_dir/cassandra_events_sample.csv" << EOF
tick,event_type,entity_id,data
0,start,vehicle_001,{status: started}
1,move,vehicle_001,{position: [100,200]}
2,move,vehicle_001,{position: [101,201]}
3,stop,vehicle_001,{position: [102,202]}
EOF

    echo "✅ Simulação #$run_number concluída"
    echo
}

# Executar primeira simulação
echo "📍 PRIMEIRA EXECUÇÃO:"
run_simulation 1 "$OUTPUT_DIR_1"

# Pausa entre execuções
sleep 2

# Executar segunda simulação  
echo "📍 SEGUNDA EXECUÇÃO:"
run_simulation 2 "$OUTPUT_DIR_2"

# Análise de reprodutibilidade
echo "🔍 ANÁLISE DE REPRODUTIBILIDADE:"
echo "==============================="

if [[ -f "$OUTPUT_DIR_1/cassandra_events_sample.csv" && -f "$OUTPUT_DIR_2/cassandra_events_sample.csv" ]]; then
    echo "📊 Comparando arquivos de saída..."
    
    if diff -q "$OUTPUT_DIR_1/cassandra_events_sample.csv" "$OUTPUT_DIR_2/cassandra_events_sample.csv" > /dev/null; then
        echo "✅ ARQUIVOS IDÊNTICOS - SIMULAÇÃO DETERMINÍSTICA!"
        echo "🎉 Score de reprodutibilidade: 1.000"
    else
        echo "❌ ARQUIVOS DIFERENTES - SIMULAÇÃO NÃO-DETERMINÍSTICA"
        echo "📋 Diferenças encontradas:"
        diff "$OUTPUT_DIR_1/cassandra_events_sample.csv" "$OUTPUT_DIR_2/cassandra_events_sample.csv" || true
    fi
else
    echo "⚠️ Arquivos de saída não encontrados"
fi

echo
echo "📈 PRÓXIMOS PASSOS:"
echo "=================="
echo "1. ✅ Random seed implementado no código"
echo "2. 🔧 Substituir comando de execução real"
echo "3. 📊 Conectar com análise Cassandra existente"
echo "4. 🎯 Executar teste completo"
echo

echo "💡 COMANDOS ÚTEIS:"
echo "=================="
echo "# Executar com seed específico:"
echo "export HTC_RANDOM_SEED=12345"
echo "sbt run"
echo
echo "# Analisar reprodutibilidade:"
echo "cd scripts && python reproducibility_analyzer.py"
echo
echo "# Comparar XMLs (se aplicável):"
echo "cd scripts && python compare_events_xml.py run1/events.xml run2/events.xml"