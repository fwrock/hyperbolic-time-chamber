# Instalação de Dependências do Sistema de Análise HTC

echo "🚀 Configurando Sistema de Análise de Tráfego HTC"
echo "================================================"
echo

# Verificar se Python3 está instalado
if ! command -v python3 &> /dev/null; then
    echo "❌ Python3 não encontrado. Instale Python 3.8+ primeiro."
    exit 1
fi

echo "✅ Python3 encontrado: $(python3 --version)"
echo

# Criar ambiente virtual (opcional mas recomendado)
echo "🔧 Criando ambiente virtual..."
python3 -m venv venv_htc_analysis
source venv_htc_analysis/bin/activate

echo "📦 Instalando dependências Python..."

# Core dependencies para análise de dados
pip install pandas>=1.5.0
pip install numpy>=1.21.0
pip install matplotlib>=3.5.0
pip install seaborn>=0.11.0

# Scientific computing e statistical analysis
pip install scipy>=1.9.0
pip install scikit-learn>=1.1.0

# Database connectivity
pip install cassandra-driver>=3.25.0

# Visualization enhancements (optional)
pip install plotly>=5.0.0
pip install kaleido>=0.2.1

# Development and testing (optional)
pip install pytest>=7.0.0
pip install jupyter>=1.0.0

echo
echo "✅ Instalação concluída!"
echo
echo "🔄 Para ativar o ambiente virtual no futuro:"
echo "   source venv_htc_analysis/bin/activate"
echo
echo "🚀 Para testar o sistema:"
echo "   cd scripts/"
echo "   ./analysis_helper.sh status"
echo