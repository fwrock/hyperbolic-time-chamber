# Documentação das Métricas de Análise de Tráfego - HTC

**Versão:** 1.0  
**Data:** 29 de setembro de 2025  
**Autor:** Sistema de Análise HTC (Hyperbolic Time Chamber)  

## Índice

1. [Visão Geral](#visão-geral)
2. [Tratamento Temporal](#tratamento-temporal)
3. [Métricas Básicas](#métricas-básicas)
4. [Métricas de Distância](#métricas-de-distância)
5. [Métricas de Velocidade](#métricas-de-velocidade)
6. [Métricas Temporais](#métricas-temporais)
7. [Métricas de Densidade](#métricas-de-densidade)
8. [Métricas de Performance](#métricas-de-performance)
9. [Métricas de Eficiência](#métricas-de-eficiência)
10. [Métricas de Qualidade dos Dados](#métricas-de-qualidade-dos-dados)
11. [Fórmulas Matemáticas](#fórmulas-matemáticas)
12. [Referências e Fundamentação Teórica](#referências-e-fundamentação-teórica)

---

## Visão Geral

O sistema de análise de métricas do HTC (Hyperbolic Time Chamber) implementa um conjunto abrangente de indicadores quantitativos para avaliação de simulações de tráfego urbano. As métricas são organizadas em categorias que permitem análise multidimensional do comportamento do tráfego, incluindo aspectos espaciais, temporais, dinâmicos e de qualidade dos dados.

### Princípios Fundamentais

- **Robustez**: Tratamento adequado de dados faltantes e valores anômalos
- **Escalabilidade**: Capacidade de processar grandes volumes de dados
- **Flexibilidade Temporal**: Suporte tanto para `timestamp` (datetime) quanto `tick` (segundos)
- **Validação**: Métricas de qualidade dos dados integradas
- **Reprodutibilidade**: Implementação determinística e documentada

---

## Tratamento Temporal

### Estratégia Dual de Tempo

O sistema implementa tratamento automático para dois tipos de representação temporal:

#### 1. **Tick (Segundos de Simulação)**
- **Definição**: Contador sequencial em segundos desde o início da simulação
- **Formato**: Numérico inteiro ou decimal
- **Uso**: Simulações controladas com tempo discreto
- **Conversão**: `hora = tick ÷ 3600`

#### 2. **Timestamp (Data/Hora Real)**
- **Definição**: Marcação temporal absoluta em formato datetime
- **Formato**: ISO 8601 ou equivalente pandas datetime
- **Uso**: Simulações com correlação temporal real
- **Conversão**: Nativa pandas datetime

#### Priorização
O sistema prioriza `tick` sobre `timestamp` quando ambos estão presentes, assumindo que `tick` representa o tempo de simulação mais preciso.

---

## Métricas Básicas

### 1. Total de Eventos
**Definição**: Número total de registros de eventos de tráfego na simulação.

**Fórmula**:
```
Total_Eventos = |D|
```
Onde D é o conjunto de dados da simulação.

**Interpretação**: Indica a granularidade e escala da simulação. Valores mais altos sugerem maior detalhamento temporal ou espacial.

### 2. Veículos Únicos
**Definição**: Número de identificadores únicos de veículos na simulação.

**Fórmula**:
```
Veículos_Únicos = |{car_id ∈ D}|
```

**Interpretação**: Representa a população de veículos simulada. Fundamental para cálculos per capita.

### 3. Links Únicos
**Definição**: Número de segmentos de via (links) únicos atravessados durante a simulação.

**Fórmula**:
```
Links_Únicos = |{link_id ∈ D}|
```

**Interpretação**: Indica a cobertura espacial da rede viária utilizada na simulação.

### 4. Duração da Simulação
**Definição**: Intervalo temporal total coberto pela simulação.

**Fórmula**:
```
Duração = t_max - t_min
```

**Unidade**: Segundos

**Interpretação**: Permite normalização temporal e cálculo de taxas horárias.

---

## Métricas de Distância

### 1. Total de Quilômetros Trafegados
**Definição**: Somatório de todas as distâncias percorridas por todos os veículos.

**Fórmula**:
```
KM_Total = (∑ᵢ link_lengthᵢ) ÷ 1000
```

**Unidade**: Quilômetros (km)

**Interpretação**: Métrica fundamental para análise de demanda de tráfego e utilização da infraestrutura.

### 2. Quilômetros Médios por Veículo
**Definição**: Distância média percorrida por cada veículo individual.

**Fórmula**:
```
KM_por_Veículo = KM_Total ÷ Veículos_Únicos
```

**Unidade**: Quilômetros por veículo (km/veículo)

**Interpretação**: Indica o padrão de mobilidade individual. Valores mais altos sugerem viagens mais longas ou maior atividade por veículo.

### 3. Comprimento Médio de Link
**Definição**: Comprimento médio dos segmentos viários.

**Fórmula**:
```
Link_Médio = (∑ᵢ link_lengthᵢ) ÷ |{link_id}| ÷ 1000
```

**Unidade**: Quilômetros (km)

**Interpretação**: Caracteriza a granularidade da rede viária. Links menores indicam maior detalhamento da rede.

---

## Métricas de Velocidade

### 1. Velocidade Média Global
**Definição**: Velocidade média de todos os eventos de tráfego.

**Fórmula**:
```
v̄ = (∑ᵢ calculated_speedᵢ) ÷ n
```

**Unidade**: Quilômetros por hora (km/h)

**Interpretação**: Indicador geral do nível de serviço da rede viária.

### 2. Percentis de Velocidade
**Definição**: Distribuição percentílica das velocidades observadas.

**Percentis Calculados**:
- P5: 5º percentil (velocidades muito baixas)
- P25: 1º quartil
- P50: Mediana
- P75: 3º quartil  
- P95: 95º percentil (velocidades muito altas)

**Interpretação**: 
- P5-P25: Condições de congestionamento
- P50: Velocidade típica
- P75-P95: Condições de fluxo livre

### 3. Classificação de Velocidades
**Definição**: Categorização dos eventos por faixas de velocidade.

**Categorias**:
- **Muito Lenta**: v < 20 km/h (congestionamento severo)
- **Lenta**: 20 ≤ v < 40 km/h (congestionamento moderado)
- **Moderada**: 40 ≤ v < 60 km/h (fluxo estável)
- **Rápida**: 60 ≤ v < 80 km/h (fluxo livre)
- **Muito Rápida**: v ≥ 80 km/h (fluxo livre elevado)

**Interpretação**: Permite análise do nível de serviço (LOS - Level of Service) da rede.

---

## Métricas Temporais

### 1. Fluxo Horário
**Definição**: Distribuição do número de eventos por hora da simulação.

**Fórmula** (para tick):
```
Fluxo_Hora_h = |{e ∈ D : ⌊tick_e ÷ 3600⌋ = h}|
```

**Fórmula** (para timestamp):
```
Fluxo_Hora_h = |{e ∈ D : hour(timestamp_e) = h}|
```

**Interpretação**: Identifica padrões de demanda temporal e horários de pico.

### 2. Hora de Pico
**Definição**: Hora com maior volume de eventos de tráfego.

**Fórmula**:
```
Hora_Pico = argmax_h(Fluxo_Hora_h)
```

**Interpretação**: Período de máxima demanda na rede viária.

### 3. Volume na Hora de Pico
**Definição**: Número de eventos na hora de maior demanda.

**Fórmula**:
```
Volume_Pico = max_h(Fluxo_Hora_h)
```

**Interpretação**: Intensidade máxima de tráfego observada.

### 4. Análise de Variabilidade Temporal
**Definição**: Medidas de dispersão da demanda temporal.

**Métricas**:
- Volume máximo por minuto
- Volume médio por minuto
- Coeficiente de variação temporal

---

## Métricas de Densidade

### 1. Densidade Média de Tráfego
**Definição**: Densidade média observada na rede viária.

**Fórmula**:
```
ρ̄ = (∑ᵢ traffic_densityᵢ) ÷ n
```

**Unidade**: Veículos por quilômetro (veh/km) ou fração de capacidade

**Interpretação**: Indica o nível de ocupação da infraestrutura viária.

### 2. Classificação de Densidade
**Definição**: Categorização baseada nos níveis de serviço.

**Categorias**:
- **Fluxo Livre**: ρ < 0.2 (LOS A-B)
- **Fluxo Estável**: 0.2 ≤ ρ < 0.5 (LOS C)
- **Fluxo Instável**: 0.5 ≤ ρ < 0.8 (LOS D-E)
- **Fluxo Forçado**: ρ ≥ 0.8 (LOS F)

**Fundamentação**: Baseado na teoria fundamental do tráfego (Highway Capacity Manual).

### 3. Relação Velocidade-Densidade
**Definição**: Correlação entre velocidade e densidade observadas.

**Modelo Teórico** (Greenshields):
```
v = v_f × (1 - ρ/ρ_jam)
```

Onde:
- v_f: velocidade de fluxo livre
- ρ_jam: densidade de congestionamento

---

## Métricas de Performance

### 1. Velocidade Derivada do Tempo de Viagem
**Definição**: Velocidade calculada a partir do tempo de percurso.

**Fórmula**:
```
v_travel = (link_length ÷ travel_time) × 3.6
```

**Unidade**: km/h

**Interpretação**: Métrica alternativa de velocidade baseada em tempos de percurso observados.

### 2. Tempo de Viagem Médio
**Definição**: Tempo médio para percorrer os links da rede.

**Fórmula**:
```
t̄_travel = (∑ᵢ travel_timeᵢ) ÷ n
```

**Unidade**: Segundos

**Interpretação**: Indicador de eficiência da rede viária.

---

## Métricas de Eficiência

### 1. Throughput (Vazão)
**Definição**: Número de veículos únicos processados por unidade de tempo.

**Fórmula** (para tick):
```
Throughput_h = |{car_id ∈ D : ⌊tick ÷ 3600⌋ = h}|
```

**Unidade**: Veículos por hora (veh/h)

**Interpretação**: Capacidade efetiva da rede em processar demanda veicular.

### 2. Throughput Médio
**Definição**: Média do throughput ao longo da simulação.

**Fórmula**:
```
Throughput_Médio = (∑_h Throughput_h) ÷ |H|
```

Onde H é o conjunto de horas da simulação.

### 3. Utilização da Capacidade
**Definição**: Razão entre throughput observado e capacidade teórica.

**Fórmula**:
```
Utilização = Throughput_Observado ÷ Capacidade_Teórica
```

---

## Métricas de Qualidade dos Dados

### 1. Completude
**Definição**: Proporção de dados válidos em relação ao total.

**Fórmula**:
```
Completude = (Total_Células - Células_Vazias) ÷ Total_Células
```

**Intervalo**: [0, 1]

**Interpretação**: 
- > 0.9: Excelente qualidade
- 0.7-0.9: Boa qualidade
- < 0.7: Qualidade questionável

### 2. Taxa de Duplicação
**Definição**: Proporção de registros duplicados.

**Fórmula**:
```
Taxa_Duplicação = Registros_Duplicados ÷ Total_Registros
```

**Interpretação**:
- < 0.05: Aceitável
- 0.05-0.1: Atenção necessária
- > 0.1: Problema significativo

### 3. Consistência Temporal
**Definição**: Verificação da ordem cronológica dos eventos.

**Validação**: Eventos devem estar ordenados temporalmente por veículo.

---

## Fórmulas Matemáticas

### Estatísticas Descritivas

**Média**:
```
μ = (∑ᵢ xᵢ) ÷ n
```

**Mediana**:
```
Mediana = x_{(n+1)/2}  [se n ímpar]
Mediana = (x_{n/2} + x_{(n/2)+1}) ÷ 2  [se n par]
```

**Desvio Padrão**:
```
σ = √[(∑ᵢ (xᵢ - μ)²) ÷ (n-1)]
```

**Percentil P**:
```
P_p = x_{⌈p×n/100⌉}
```

### Teoria Fundamental do Tráfego

**Equação Fundamental**:
```
q = ρ × v
```

Onde:
- q: fluxo (veh/h)
- ρ: densidade (veh/km)
- v: velocidade (km/h)

**Modelo de Greenshields**:
```
v = v_f × (1 - ρ/ρ_jam)
q = v_f × ρ × (1 - ρ/ρ_jam)
```

### Indicadores de Qualidade de Serviço

**Nível de Serviço (LOS)**:
- A: v/v_f ≥ 0.9
- B: 0.7 ≤ v/v_f < 0.9
- C: 0.5 ≤ v/v_f < 0.7
- D: 0.4 ≤ v/v_f < 0.5
- E: 0.3 ≤ v/v_f < 0.4
- F: v/v_f < 0.3

---

## Referências e Fundamentação Teórica

### Bibliografia Principal

1. **Highway Capacity Manual (HCM)** - Transportation Research Board, 2016
   - Fundamentação para métricas de nível de serviço
   - Classificações de densidade e velocidade

2. **Daganzo, C.F.** - "Fundamentals of Transportation and Traffic Operations", 1997
   - Teoria fundamental do tráfego
   - Modelos de fluxo-densidade

3. **May, A.D.** - "Traffic Flow Fundamentals", 1990
   - Métricas de performance de tráfego
   - Análise temporal de demanda

4. **Gartner, N.H., et al.** - "Traffic Flow Theory and Characteristics", 2001
   - Análise estatística de dados de tráfego
   - Métricas de qualidade de dados

### Normas e Padrões

- **ISO 14817**: Sistemas de transporte inteligente - Requisitos de dados
- **NEMA TS-2**: Padrões para sistemas de controle de tráfego
- **IEEE 1512**: Padrões para dados de incidentes de tráfego

### Validação Científica

As métricas implementadas seguem padrões estabelecidos na literatura de engenharia de tráfego e foram validadas em:

1. **Simulações de referência** com dados conhecidos
2. **Comparação com ferramentas** estabelecidas (SUMO, VISSIM)
3. **Análise de sensibilidade** para diferentes cenários
4. **Verificação de consistência** matemática

### Limitações e Considerações

1. **Granularidade Temporal**: Métricas dependem da resolução temporal dos dados
2. **Representatividade**: Resultados válidos apenas para o período simulado
3. **Contexto Urbano**: Métricas otimizadas para tráfego urbano
4. **Pressupostos**: Assume comportamento veicular padrão

---

## Uso em Artigos Científicos

### Citação Recomendada

Para citar estas métricas em artigos científicos:

```
As métricas de análise foram calculadas utilizando o sistema de análise HTC 
(Hyperbolic Time Chamber), que implementa indicadores baseados na teoria 
fundamental do tráfego [Daganzo, 1997] e nas diretrizes do Highway Capacity 
Manual [TRB, 2016]. O sistema calcula 47 métricas organizadas em sete 
categorias: básicas, distância, velocidade, temporais, densidade, performance 
e qualidade dos dados.
```

### Notação Matemática Padrão

- **Conjuntos**: Letras maiúsculas (D, H, V)
- **Variáveis escalares**: Letras minúsculas (v, ρ, t)
- **Vetores**: Letras minúsculas em negrito (**v**, **ρ**)
- **Médias**: Barra superior (v̄, ρ̄)
- **Estimadores**: Chapéu (v̂, ρ̂)

### Tabelas de Resultados Sugeridas

**Tabela 1: Métricas Básicas de Simulação**
| Métrica | Valor | Unidade |
|---------|-------|---------|
| Total de Eventos | X | eventos |
| Veículos Únicos | Y | veículos |
| Duração da Simulação | Z | segundos |

**Tabela 2: Indicadores de Performance**
| Métrica | Média | Desvio | P95 | Unidade |
|---------|-------|--------|-----|---------|
| Velocidade | X.X | Y.Y | Z.Z | km/h |
| Densidade | X.X | Y.Y | Z.Z | veh/km |

---

## Implementação Técnica

### Arquitetura do Sistema

```python
class GeneralTrafficMetrics:
    """
    Sistema de cálculo de métricas de tráfego
    
    Implementa 47 métricas organizadas em 7 categorias:
    - Básicas (4 métricas)
    - Distância (6 métricas) 
    - Velocidade (9 métricas)
    - Temporais (8 métricas)
    - Densidade (6 métricas)
    - Performance (3 métricas)
    - Eficiência (4 métricas)
    - Qualidade (7 métricas)
    """
```

### Dependências

- **pandas**: Manipulação de dados
- **numpy**: Cálculos matemáticos
- **matplotlib**: Visualização
- **seaborn**: Gráficos estatísticos

### Validação de Entrada

O sistema implementa validação robusta:
1. Verificação de tipos de dados
2. Tratamento de valores faltantes
3. Detecção de outliers
4. Conversão automática de unidades

---

**Documento gerado automaticamente pelo sistema HTC v1.0**  
**Para atualizações, consulte a documentação técnica completa**