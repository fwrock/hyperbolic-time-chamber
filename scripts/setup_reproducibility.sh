#!/bin/bash
# Script para demonstrar configuração de Simulation IDs para análise de reprodutibilidade

echo "🚀 CONFIGURAÇÃO DE SIMULATION IDs PARA REPRODUTIBILIDADE"
echo "========================================================"
echo

# Função para mostrar como configurar simulation IDs
show_configuration_methods() {
    echo "📋 MÉTODOS DE CONFIGURAÇÃO (em ordem de prioridade):"
    echo
    
    echo "1️⃣  SIMULATION.JSON (Recomendado para Pesquisa Científica)"
    echo "   # Em simulation.json:"
    echo "   {"
    echo "     \"name\": \"mobility_baseline\","
    echo "     \"description\": \"Simulação baseline - Execução 1\","
    echo "     \"id\": \"mobility_baseline_run1\","
    echo "     \"startTick\": 0,"
    echo "     ..."
    echo "   }"
    echo "   export HTC_SIMULATION_CONFIG_FILE=\"path/to/simulation.json\""
    echo "   docker-compose up"
    echo
    
    echo "2️⃣  VARIÁVEL DE AMBIENTE (Override Temporário)"
    echo "   export HTC_SIMULATION_ID=\"experiment_baseline_run1\""
    echo "   docker-compose up"
    echo
    
    echo "3️⃣  CONFIGURAÇÃO NO CÓDIGO (Fallback)"
    echo "   # Em src/main/resources/application.conf:"
    echo "   htc {"
    echo "       simulation {"
    echo "           id = \"default_simulation_id\""
    echo "       }"
    echo "   }"
    echo
    
    echo "4️⃣  AUTO-GERAÇÃO (Último Recurso)"
    echo "   # Formato: {simulation_name}_{timestamp}_{uuid}"
    echo "   # Exemplo: mobility_baseline_1727612345_a1b2c3d4"
    echo
}

# Função para demonstrar workflow de reprodutibilidade
show_reproducibility_workflow() {
    echo "🔄 WORKFLOW DE ANÁLISE DE REPRODUTIBILIDADE:"
    echo "==========================================="
    echo
    
    echo "PASSO 1: Executar múltiplas simulações com IDs únicos"
    echo "------------------------------------------------------"
    echo "# Primeira execução"
    echo "export HTC_SIMULATION_ID=\"mobility_v1_baseline_run1\""
    echo "docker-compose up"
    echo
    echo "# Segunda execução" 
    echo "export HTC_SIMULATION_ID=\"mobility_v1_baseline_run2\""
    echo "docker-compose up"
    echo
    echo "# Terceira execução"
    echo "export HTC_SIMULATION_ID=\"mobility_v1_baseline_run3\""
    echo "docker-compose up"
    echo
    
    echo "PASSO 2: Listar simulações disponíveis"
    echo "---------------------------------------"
    echo "./scripts/analysis_helper.sh list-simulations"
    echo
    
    echo "PASSO 3: Executar análise de reprodutibilidade"
    echo "-----------------------------------------------"
    echo "./scripts/analysis_helper.sh repro-cassandra \\"
    echo "    mobility_v1_baseline_run1 \\"
    echo "    mobility_v1_baseline_run2 \\"
    echo "    mobility_v1_baseline_run3"
    echo
    
    echo "PASSO 4: Analisar resultados"
    echo "-----------------------------"
    echo "# Verificar arquivo: scripts/output/reproducibility/reproducibility_report.json"
    echo "# Visualizações: scripts/output/reproducibility/*.png"
    echo
}

# Função para gerar IDs de exemplo
generate_example_ids() {
    echo "🆔 GERANDO SIMULATION IDs DE EXEMPLO:"
    echo "====================================="
    echo
    
    # Gerar IDs para diferentes cenários
    scenarios=("baseline" "hightraffic" "optimized" "validation")
    
    for scenario in "${scenarios[@]}"; do
        echo "📊 Cenário: $scenario"
        for run in {1..3}; do
            id="mobility_v1_${scenario}_run${run}"
            echo "   $id"
        done
        echo
    done
    
    echo "💡 COMANDOS PARA USAR:"
    echo "# Para executar com um ID específico:"
    echo "export HTC_SIMULATION_ID=\"mobility_v1_baseline_run1\""
    echo "docker-compose up"
    echo
    echo "# Para análise de reprodutibilidade de um cenário:"
    echo "./scripts/analysis_helper.sh repro-cassandra \\"
    echo "    mobility_v1_baseline_run1 \\"
    echo "    mobility_v1_baseline_run2 \\"
    echo "    mobility_v1_baseline_run3"
    echo
}

# Função para criar script de execução automática
create_automated_script() {
    local script_path="./scripts/run_reproducibility_experiment.sh"
    
    cat > "$script_path" << 'EOF'
#!/bin/bash
# Script automatizado para experimento de reprodutibilidade

echo "🧪 EXECUTANDO EXPERIMENTO DE REPRODUTIBILIDADE"
echo "=============================================="

# Configuração
EXPERIMENT_NAME="mobility_experiment"
NUM_RUNS=3
BASE_DIR="./scripts/output"

echo "📝 Configuração:"
echo "   Experimento: $EXPERIMENT_NAME"
echo "   Número de execuções: $NUM_RUNS"
echo "   Diretório base: $BASE_DIR"
echo

# Array para armazenar simulation IDs
SIMULATION_IDS=()

# Executar múltiplas simulações
for i in $(seq 1 $NUM_RUNS); do
    SIM_ID="${EXPERIMENT_NAME}_run${i}"
    SIMULATION_IDS+=("$SIM_ID")
    
    echo "🚀 Executando simulação $i/$NUM_RUNS: $SIM_ID"
    
    # Configurar simulation ID
    export HTC_SIMULATION_ID="$SIM_ID"
    
    # Executar simulação (substituir por comando real)
    echo "   export HTC_SIMULATION_ID=\"$SIM_ID\""
    echo "   docker-compose up  # (não executado neste exemplo)"
    
    # Aguardar conclusão (substituir por lógica real)
    echo "   ✅ Simulação $SIM_ID concluída"
    echo
done

echo "📊 Todas as simulações concluídas!"
echo "📋 Simulation IDs gerados:"
for sim_id in "${SIMULATION_IDS[@]}"; do
    echo "   - $sim_id"
done

echo
echo "🔍 Para analisar reprodutibilidade, execute:"
echo "./scripts/analysis_helper.sh repro-cassandra ${SIMULATION_IDS[*]}"
echo

echo "💡 Ou use o gerenciador de simulation IDs:"
echo "./scripts/analysis_helper.sh list-simulations"

EOF

    chmod +x "$script_path"
    echo "📄 Script automatizado criado: $script_path"
    echo "   Execute com: $script_path"
}

# Função principal
main() {
    case "${1:-help}" in
        "config")
            show_configuration_methods
            ;;
        "workflow")
            show_reproducibility_workflow
            ;;
        "examples")
            generate_example_ids
            ;;
        "create-script")
            create_automated_script
            ;;
        "all")
            show_configuration_methods
            echo
            show_reproducibility_workflow
            echo
            generate_example_ids
            echo
            create_automated_script
            ;;
        *)
            echo "📋 OPÇÕES DISPONÍVEIS:"
            echo "   $0 config       # Métodos de configuração"
            echo "   $0 workflow     # Workflow de reprodutibilidade"
            echo "   $0 examples     # Exemplos de simulation IDs"
            echo "   $0 create-script # Criar script automatizado"
            echo "   $0 all          # Mostrar tudo"
            echo
            ;;
    esac
}

# Executar função principal
main "$@"