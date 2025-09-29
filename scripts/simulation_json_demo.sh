#!/bin/bash
# Demonstração do Sistema de Simulation ID via simulation.json

echo "🚀 DEMONSTRAÇÃO: SIMULATION ID VIA SIMULATION.JSON"
echo "=================================================="
echo

# Função para mostrar como usar
demonstrate_usage() {
    echo "🎯 NOVO SISTEMA IMPLEMENTADO:"
    echo
    echo "1️⃣ Simulation ID agora é definido no simulation.json (PRIORIDADE MÁXIMA)"
    echo "2️⃣ Sistema usa configuração específica da simulação"
    echo "3️⃣ Ideal para reprodutibilidade científica"
    echo
    
    echo "📁 EXEMPLOS CRIADOS:"
    echo "├── simulations/input/example_simulation/"
    echo "│   ├── simulation.json                    # ID: mobility_baseline_run1"
    echo "│   ├── simulation_baseline_run1.json     # ID: mobility_baseline_run1"
    echo "│   ├── simulation_baseline_run2.json     # ID: mobility_baseline_run2"
    echo "│   └── simulation_baseline_run3.json     # ID: mobility_baseline_run3"
    echo
    
    echo "🔄 WORKFLOW PARA REPRODUTIBILIDADE:"
    echo
    echo "# Passo 1: Executar múltiplas simulações"
    echo "export HTC_SIMULATION_CONFIG_FILE=\"simulations/input/example_simulation/simulation_baseline_run1.json\""
    echo "docker-compose up"
    echo
    echo "export HTC_SIMULATION_CONFIG_FILE=\"simulations/input/example_simulation/simulation_baseline_run2.json\""
    echo "docker-compose up"
    echo
    echo "export HTC_SIMULATION_CONFIG_FILE=\"simulations/input/example_simulation/simulation_baseline_run3.json\""
    echo "docker-compose up"
    echo
    echo "# Passo 2: Analisar reprodutibilidade"
    echo "./scripts/analysis_helper.sh repro-cassandra \\"
    echo "    mobility_baseline_run1 \\"
    echo "    mobility_baseline_run2 \\"
    echo "    mobility_baseline_run3"
    echo
}

# Função para mostrar vantagens
show_benefits() {
    echo "✅ VANTAGENS DO NOVO SISTEMA:"
    echo "============================="
    echo
    echo "🎯 Semântica Correta:"
    echo "   • ID faz parte da definição da simulação"
    echo "   • Não é configuração de infraestrutura"
    echo
    echo "📁 Organização Natural:"
    echo "   • Cada cenário tem sua configuração específica"
    echo "   • Pastas organizadas por experimento"
    echo
    echo "🔄 Reprodutibilidade Nativa:"
    echo "   • Arquivos podem ser versionados"
    echo "   • Configuração explícita e documentada"
    echo
    echo "🔬 Padrão Científico:"
    echo "   • Ideal para publicações acadêmicas"
    echo "   • Rastreabilidade completa"
    echo
    echo "🚀 Workflow Limpo:"
    echo "   • Troca de simulação = troca de arquivo"
    echo "   • Sem necessidade de variáveis de ambiente"
    echo
}

# Função para mostrar estrutura recomendada
show_recommended_structure() {
    echo "📋 ESTRUTURA RECOMENDADA PARA PESQUISA:"
    echo "======================================="
    echo
    echo "simulations/"
    echo "├── experiments/"
    echo "│   ├── baseline/"
    echo "│   │   ├── run1/"
    echo "│   │   │   ├── simulation.json        # id: \"exp2025_baseline_run1\""
    echo "│   │   │   └── data/"
    echo "│   │   ├── run2/"
    echo "│   │   │   ├── simulation.json        # id: \"exp2025_baseline_run2\""
    echo "│   │   │   └── data/"
    echo "│   │   └── run3/"
    echo "│   │       ├── simulation.json        # id: \"exp2025_baseline_run3\""
    echo "│   │       └── data/"
    echo "│   ├── optimized/"
    echo "│   │   ├── run1/"
    echo "│   │   │   ├── simulation.json        # id: \"exp2025_optimized_run1\""
    echo "│   │   │   └── data/"
    echo "│   │   └── run2/"
    echo "│   │       ├── simulation.json        # id: \"exp2025_optimized_run2\""
    echo "│   │       └── data/"
    echo "│   └── validation/"
    echo "│       ├── run1/"
    echo "│       │   ├── simulation.json        # id: \"exp2025_validation_run1\""
    echo "│       │   └── data/"
    echo "│       └── run2/"
    echo "│           ├── simulation.json        # id: \"exp2025_validation_run2\""
    echo "│           └── data/"
    echo
    echo "🔬 ANÁLISES CIENTÍFICAS:"
    echo
    echo "# Reprodutibilidade baseline"
    echo "./scripts/analysis_helper.sh repro-cassandra \\"
    echo "    exp2025_baseline_run1 exp2025_baseline_run2 exp2025_baseline_run3"
    echo
    echo "# Comparação entre abordagens"
    echo "./scripts/analysis_helper.sh compare-cassandra \\"
    echo "    exp2025_baseline_run1 exp2025_optimized_run1"
    echo
    echo "# Validação de resultados"
    echo "./scripts/analysis_helper.sh repro-cassandra \\"
    echo "    exp2025_validation_run1 exp2025_validation_run2"
    echo
}

# Função para mostrar JSON template
show_json_template() {
    echo "📄 TEMPLATE SIMULATION.JSON:"
    echo "============================"
    echo
    cat << 'EOF'
{
  "name": "nome_da_simulacao",
  "description": "Descrição detalhada do experimento",
  "id": "identificador_unico_da_execucao",
  "startTick": 0,
  "startRealTime": "2025-09-29T08:00:00",
  "timeUnit": "SECONDS",
  "timeStep": 1,
  "duration": 7200,
  "actorsDataSources": [
    {
      "id": "vehicles-source",
      "classType": "org.interscity.htc.core.actor.vehicle.VehicleActor",
      "path": "/app/hyperbolic-time-chamber/simulations/input/data/vehicles.csv",
      "dataType": "CSV",
      "creationType": "LoadBalancedDistributed"
    }
  ]
}
EOF
    echo
    echo "🎯 CAMPO IMPORTANTE:"
    echo "   \"id\": \"identificador_unico_da_execucao\""
    echo "   ↳ Este será o simulation_id usado no Cassandra"
    echo "   ↳ Use nomes semânticos como: mobility_baseline_run1"
    echo
}

# Menu principal
case "${1:-demo}" in
    "demo")
        demonstrate_usage
        ;;
    "benefits")
        show_benefits
        ;;
    "structure")
        show_recommended_structure
        ;;
    "template")
        show_json_template
        ;;
    "all")
        demonstrate_usage
        echo
        show_benefits
        echo
        show_recommended_structure
        echo
        show_json_template
        ;;
    *)
        echo "📋 OPÇÕES DISPONÍVEIS:"
        echo "   $0 demo         # Demonstração de uso"
        echo "   $0 benefits     # Vantagens do novo sistema"
        echo "   $0 structure    # Estrutura recomendada"
        echo "   $0 template     # Template JSON"
        echo "   $0 all          # Mostrar tudo"
        echo
        ;;
esac