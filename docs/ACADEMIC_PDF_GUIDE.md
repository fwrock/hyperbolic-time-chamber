# 📄 Guia de PDFs Acadêmicos - HTC Simulator

Este guia explica como gerar e usar PDFs de alta qualidade para inclusão em artigos científicos.

## 🎯 Objetivo

Os PDFs acadêmicos são gerados com configurações otimizadas para publicação científica:
- **300 DPI** para alta qualidade de impressão
- **Fontes Times New Roman** compatíveis com LaTeX
- **Cores acadêmicas** otimizadas para impressão e escala de cinza
- **Layout profissional** seguindo padrões de publicações

## 🚀 Como Gerar PDFs

### **Método 1: Script Interativo (Recomendado)**
```bash
./generate_academic_pdfs.sh
```
O script apresentará um menu interativo para escolher o tipo de PDF.

### **Método 2: Linha de Comando**
```bash
# Análise de tráfego
./generate_academic_pdfs.sh traffic

# Comparação de simuladores  
./generate_academic_pdfs.sh comparison

# Comparação individual
./generate_academic_pdfs.sh individual

# Todos os PDFs
./generate_academic_pdfs.sh all

# Listar PDFs existentes
./generate_academic_pdfs.sh list
```

### **Método 3: Scripts Python Diretos**
```bash
# Análise de tráfego (com PDF)
./scripts/run_traffic_analysis.py --source cassandra

# Comparação (com PDF)  
./scripts/compare_simulators.py --htc-cassandra reference.xml

# Individual (com PDF)
./run_individual_comparison.py --htc-cassandra reference.xml
```

## 📊 Tipos de PDFs Gerados

### **1. PDF de Análise de Tráfego**
**Arquivo:** `traffic_analysis_academic.pdf`
**Páginas:**
- **Página 1:** Fluxo de Tráfego Temporal
  - Linha temporal de eventos
  - Histograma de distribuição
- **Página 2:** Distribuição de Veículos
  - Gráfico pizza de tipos de eventos
  - Top 10 links mais utilizados
  - Veículos únicos por hora
  - Estatísticas gerais
- **Página 3:** Heatmap de Uso de Links
  - Mapa de calor temporal
  - Padrões de uso por time bins
- **Página 4:** Métricas de Performance
  - Métricas básicas de tráfego
  - Análises de throughput, congestionamento

### **2. PDF de Comparação de Simuladores**
**Arquivo:** `simulator_comparison_academic.pdf`
**Páginas:**
- **Página 1:** Radar Chart de Similaridade
  - Score geral, correlação temporal, similaridade de links
  - Métricas de padrões e consistência
- **Página 2:** Comparação Temporal
  - Fluxos temporais comparativos
  - Função de distribuição cumulativa (CDF)
  - Métricas de correlação
- **Página 3:** Comparação de Links
  - Top links de cada simulador
  - Métricas de links comuns
  - Distribuição de uso
- **Página 4:** Métricas Estatísticas
  - Score geral em formato gauge
  - Métricas detalhadas (correlação, JS divergence)
  - Matriz de confusão (se disponível)
- **Página 5:** Box Plots de Distribuições
  - Distribuições temporais comparativas
  - Eventos por veículo
  - Uso de links
  - Estatísticas resumidas

### **3. PDF de Comparação Individual**
**Arquivo:** `individual_comparison_academic.pdf`
**Páginas:**
- **Página 1:** Similaridade de Veículos
  - Histograma de scores de similaridade
  - Top 10 veículos mais similares
  - Estatísticas de mapeamento
  - Similaridade por tipo de comparação
- **Página 2:** Comparação de Jornadas
  - Distribuição de comprimento de jornadas
  - Correlação de comprimentos
- **Página 3:** Estatísticas de Mapeamento
  - Taxa de mapeamento (pie chart)
  - Distribuição de qualidade
  - Estatísticas gerais

## 🎨 Especificações Técnicas

### **Configurações de Qualidade**
```python
plt.rcParams.update({
    'font.size': 12,
    'axes.titlesize': 14,
    'axes.labelsize': 12,
    'xtick.labelsize': 10,
    'ytick.labelsize': 10,
    'legend.fontsize': 10,
    'figure.titlesize': 16,
    'font.family': 'serif',
    'font.serif': ['Times New Roman'],
    'figure.dpi': 300,
    'savefig.dpi': 300,
    'savefig.bbox': 'tight',
    'savefig.pad_inches': 0.1
})
```

### **Paleta de Cores Acadêmica**
- **Azul Principal:** `#1f77b4` (HTC)
- **Rosa Referência:** `#A23B72` (Simulador de referência)
- **Verde Sucesso:** `#2ca02c` (Métricas positivas)
- **Vermelho Alerta:** `#d62728` (Métricas de atenção)
- **Laranja:** `#ff7f0e` (Dados secundários)

## 📋 Dependências Necessárias

### **Instalação Automática**
O script `generate_academic_pdfs.sh` verifica e instala automaticamente:
```bash
pip install matplotlib seaborn plotly kaleido
```

### **Instalação Manual**
```bash
# Dependências básicas
pip install matplotlib>=3.5.0
pip install seaborn>=0.11.0
pip install plotly>=5.0.0
pip install kaleido>=0.2.0

# Dependências adicionais para alta qualidade
pip install pillow>=8.0.0
```

## 💡 Dicas para Uso em Artigos

### **Inclusão em LaTeX**
```latex
\begin{figure}[htbp]
    \centering
    \includegraphics[width=0.8\textwidth]{traffic_analysis_academic.pdf}
    \caption{Análise de fluxo de tráfego do simulador HTC}
    \label{fig:htc_traffic_analysis}
\end{figure}
```

### **Referência em Texto**
```latex
A Figura~\ref{fig:htc_traffic_analysis} apresenta a análise completa 
do fluxo de tráfego gerado pelo simulador HTC, demonstrando padrões
temporais consistentes e distribuição equilibrada entre links.
```

### **Páginas Específicas**
Se o PDF tem múltiplas páginas, você pode referenciar páginas específicas:
```latex
% Página 1 do PDF (fluxo temporal)
\includegraphics[page=1,width=0.8\textwidth]{traffic_analysis_academic.pdf}

% Página 2 do PDF (distribuição de veículos)  
\includegraphics[page=2,width=0.8\textwidth]{traffic_analysis_academic.pdf}
```

## 🔧 Solução de Problemas

### **Erro: "Dependências não encontradas"**
```bash
# Instalar dependências manualmente
pip install matplotlib seaborn plotly kaleido

# Verificar instalação
python3 -c "import matplotlib, seaborn, plotly, kaleido; print('OK')"
```

### **Erro: "Não é possível gerar PDF"**
```bash
# Verificar se dados existem
./check_cassandra_data.sh

# Executar simulação primeiro
./manage_cassandra.sh clean
./build-and-run.sh
```

### **PDFs com qualidade baixa**
- Verifique se `dpi=300` está configurado
- Confirme que `bbox_inches='tight'` está sendo usado
- Use `savefig.format='pdf'` nas configurações

### **Fontes não aparecem corretamente**
```bash
# Instalar fontes Times New Roman (Ubuntu/Debian)
sudo apt-get install msttcorefonts

# Limpar cache de fontes matplotlib
rm -rf ~/.cache/matplotlib
```

## 📈 Exemplos de Uso

### **Workflow Completo para Paper**
```bash
# 1. Limpar dados antigos
./manage_cassandra.sh clean

# 2. Executar simulação
./build-and-run.sh

# 3. Gerar todos os PDFs
./generate_academic_pdfs.sh all

# 4. Verificar arquivos gerados
./generate_academic_pdfs.sh list

# 5. Copiar PDFs para pasta do paper
cp scripts/output/academic_reports/*.pdf ~/paper/figures/
```

### **Comparação com Baseline**
```bash
# 1. Executar simulação HTC
./simulation_workflow.sh clean

# 2. Gerar PDF de comparação com MATSim
./generate_academic_pdfs.sh comparison

# 3. Usar PDF na seção de validação do paper
```

### **Análise de Sensibilidade**
```bash
# Para diferentes cenários, gerar PDFs separados
for scenario in cenario1 cenario2 cenario3; do
    ./manage_cassandra.sh clean
    ./build-and-run.sh $scenario
    ./generate_academic_pdfs.sh traffic
    mv scripts/output/academic_reports/traffic_analysis_academic.pdf \
       figures/traffic_analysis_${scenario}.pdf
done
```

---

## 📚 Referências e Padrões

### **Citação Sugerida**
```
Os resultados foram analisados utilizando o sistema de visualização 
acadêmica do simulador HTC, que gera relatórios em PDF de alta qualidade 
(300 DPI) otimizados para publicação científica.
```

### **Metadados dos PDFs**
Cada PDF contém metadados automáticos:
- **Título:** Específico para cada tipo de análise
- **Autor:** HTC Simulator
- **Assunto:** Tipo de análise realizada
- **Palavras-chave:** Traffic Simulation, Urban Mobility, Multi-Agent Systems
- **Data de Criação:** Timestamp da geração

### **Padrões de Nomenclatura**
- `traffic_analysis_academic.pdf` - Análise de tráfego
- `simulator_comparison_academic.pdf` - Comparação de simuladores
- `individual_comparison_academic.pdf` - Comparação individual

---

**🎉 Com estes PDFs você tem visualizações de qualidade profissional para suas publicações científicas!**