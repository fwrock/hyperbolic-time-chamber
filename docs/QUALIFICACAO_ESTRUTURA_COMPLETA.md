# Proposta de Qualificação de Doutorado
## Hyperbolic Time Chamber: Simulador de Tráfego Urbano Híbrido Distribuído Orientado a Atores

> **Versão:** Documento de estrutura e conteúdo sugerido  
> **Sistema:** Hyperbolic Time Chamber (HTC) — Scala 3.3.5 / Apache Pekko  
> **Status:** Em desenvolvimento

---

## Estrutura Geral Sugerida

```
1. Introdução
   1.1 Contextualização e Motivação
   1.2 Problema de Pesquisa
   1.3 Hipótese
   1.4 Objetivos (Geral e Específicos)
   1.5 Contribuições Esperadas
   1.6 Organização do Documento

2. Fundamentação Teórica
   2.1 Simulação de Tráfego Urbano
   2.2 Modelos Mesoscópicos
   2.3 Modelos Microscópicos (Car-Following, Lane Change)
   2.4 Modelos Híbridos Micro-Mesoscópicos
   2.5 Sistemas Multi-Agentes e Paradigma de Atores
   2.6 Simulação Orientada a Eventos Distribuída
   2.7 Digital Twins e Gêmeos Digitais de Transporte
   2.8 Emissões de Poluentes no Contexto de Simulação

3. Trabalhos Relacionados
   3.1 Simuladores de Tráfego Estabelecidos
   3.2 Abordagens Híbridas na Literatura
   3.3 Simulação Distribuída e Multi-Agentes
   3.4 Digital Twins em Transporte Urbano
   3.5 Métricas de Sustentabilidade em Simuladores
   3.6 Posicionamento do HTC

4. Metodologia
   4.1 Visão Geral da Abordagem
   4.2 Arquitetura do HTC
   4.3 Motor de Simulação Híbrido
   4.4 Linguagem de Definição de Cenários
   4.5 Integração SimEDaPE
   4.6 Métricas de Sustentabilidade e Emissões
   4.7 Estudos de Caso

5. Resultados Iniciais
   5.1 Validação do Motor Híbrido vs. SUMO
   5.2 Desempenho e Escalabilidade
   5.3 Análise de Emissões

6. Conclusões e Trabalhos Futuros

7. Cronograma de Execução
```

---

## 1. Introdução

### 1.1 Contextualização e Motivação

A mobilidade urbana representa um dos maiores desafios das cidades contemporâneas. O crescimento acelerado das frotas veiculares, a ineficiência dos sistemas de transporte público e a pressão ambiental por redução de emissões de gases poluentes exigem ferramentas capazes de **modelar, analisar e prever** o comportamento do tráfego em escala metropolitana. Nesse contexto, **simuladores de tráfego** emergem como instrumentos centrais para planejadores, pesquisadores e gestores públicos, permitindo a avaliação de cenários hipotéticos ("what-if") sem a necessidade de intervenções físicas dispendiosas.

Os simuladores existentes, porém, expõem um trade-off fundamental: simuladores **microscópicos**, como o SUMO (*Simulation of Urban MObility*), oferecem alta fidelidade na dinâmica individual de veículos — posição, velocidade, aceleração, trocas de faixa — mas apresentam custo computacional proibitivo quando aplicados a redes com dezenas de milhares de agentes. Simuladores **mesoscópicos**, por outro lado, escalam para cidades inteiras ao utilizar modelos agregados de fluxo-densidade, sacrificando o nível de detalhe individual. Essa lacuna motivou o desenvolvimento de abordagens **híbridas**, que combinam os dois paradigmas em diferentes regiões da rede.

O **Hyperbolic Time Chamber (HTC)** é um simulador de tráfego urbano distribuído, baseado em multi-agentes e orientado a eventos, que implementa nativamente essa abordagem híbrida dentro de um framework de **atores distribuídos** (Apache Pekko/Akka). A arquitetura permite que cada segmento de via (*link*) opere independentemente em modo mesoscópico (cálculo agregado BPR) ou microscópico (car-following Krauss, gestão de faixas MOBIL), possibilitando simulações de cidades inteiras com regiões de alta fidelidade localizadas onde o estudo assim o demanda.

### 1.2 Problema de Pesquisa

> *Como projetar e implementar um simulador de tráfego urbano híbrido, distribuído e orientado a atores, capaz de executar transições dinâmicas entre níveis de abstração (micro ↔ meso) em tempo de execução, integrar técnicas de aceleração por reconhecimento de padrões (SimEDaPE) para suporte a Digital Twins, e produzir métricas de sustentabilidade validadas para estudos de caso reais?*

### 1.3 Hipótese

Um simulador híbrido baseado no paradigma de atores distribuídos, com transições automáticas entre granularidade micro e meso por link, é capaz de: (i) superar simuladores microscópicos monolíticos em escalabilidade sem perda significativa de fidelidade nas regiões de interesse; (ii) viabilizar execuções aceleradas de múltiplos cenários "what-if" via SimEDaPE; e (iii) produzir estimativas de emissões de poluentes suficientemente precisas para suporte a decisões de planejamento urbano.

### 1.4 Objetivos

**Objetivo Geral:**  
Projetar, implementar e validar o Hyperbolic Time Chamber como um simulador de tráfego urbano híbrido micro-mesoscópico, distribuído, extensível e orientado a suporte a Digital Twins, com aplicação em estudos de caso reais de mobilidade urbana.

**Objetivos Específicos:**

1. **Implementar o motor de simulação híbrido** no HTC, capaz de orquestrar a transição de agentes entre os níveis microscópico e mesoscópico em tempo de execução.
2. **Propor a aplicação ou extensão de uma linguagem de definição de simulação**, permitindo a padronização de dados de tráfego e integração com plataformas externas (SUMO, MATSim, OSM).
3. **Integrar a técnica SimEDaPE** ao motor de simulação, permitindo a identificação de padrões recorrentes para acelerar a execução de múltiplos cenários "what-if", habilitando o uso do simulador em Digital Twins.
4. **Modelar e validar métricas de sustentabilidade** e emissões de gases poluentes sensíveis à abordagem híbrida, aplicando-as em estudos de caso reais (São Paulo e Toulouse).

### 1.5 Contribuições Esperadas

- **Contribuição de Engenharia:** Implementação e disponibilização open-source do HTC como plataforma de simulação híbrida distribuída de tráfego urbano.
- **Contribuição Metodológica:** Protocolo de validação de simuladores híbridos contra referências estabelecidas (SUMO), com pipeline automatizado de comparação de métricas.
- **Contribuição Científica:** Caracterização empírica do impacto da granularidade de simulação (micro vs. meso vs. híbrida) na acurácia de estimativas de emissões de CO₂, NOx e PM2.5.
- **Contribuição para Digital Twins:** Framework de integração SimEDaPE-HTC para execução de múltiplos cenários "what-if" com aceleração por reconhecimento de padrões temporais.

---

## 2. Fundamentação Teórica

> **Como escrever de forma fluida:** A fundamentação deve ser construída como uma narrativa progressiva, do geral ao específico. Cada seção prepara o terreno para a próxima. Ao final, o leitor deve compreender naturalmente por que o HTC foi arquitetado da forma que foi. Evite listas de definições; prefira parágrafos que conectem conceitos.

### 2.1 Simulação de Tráfego Urbano

Iniciar com a classificação clássica dos modelos de tráfego em três níveis: **macroscópico** (fluxo contínuo, equações diferenciais parciais — LWR, Greenshields), **mesoscópico** (entidades individuais com dinâmica agregada — modelos de headway, densidade-velocidade) e **microscópico** (dinâmica individual completa — posição, velocidade, aceleração por agente). Apresentar a evolução histórica dos simuladores: TRANSIMS (1990s), VISSIM, CORSIM, SUMO (2001), MATSim (2009). Contextualizar que, à medida que as cidades crescem, nenhuma das três abordagens isolada resolve o problema de forma satisfatória, motivando as abordagens híbridas.

**Referências-chave:**
- Hoogendoorn & Bovy (2001) — Survey de modelos de tráfego
- Papageorgiou (1998) — Fundamentos de engenharia de tráfego
- Barceló (2010) — *Fundamentals of Traffic Simulation* (Springer)

### 2.2 Modelos Mesoscópicos: Função BPR e Relação Velocidade-Densidade

Explicar o modelo **BPR (Bureau of Public Roads)**, utilizado no HTC como motor do modo MESO:

$$
v(k) = v_f \cdot \left(1 - \left(\frac{k}{k_{\max}}\right)^\beta\right)^\alpha
$$

Descrever a intuição: velocidade cai progressivamente com a ocupação do link, atingindo velocidade mínima em congestionamento total. Apresentar o tempo de travessia como $t = L / v(k)$ e o papel dos parâmetros $\alpha$ e $\beta$. Conectar com os modelos de *headway* e *spacing* da literatura. Mostrar que o modelo mesoscópico é eficiente computacionalmente pois requer uma única operação por link por tick, independente do número de veículos.

**Referências-chave:**
- BPR (1964) — *Traffic Assignment Manual*
- Ran & Boyce (1996) — Modeling Dynamic Transportation Networks

### 2.3 Modelos Microscópicos

#### 2.3.1 Modelos de Car-Following

Apresentar a classe de modelos de car-following como aqueles que calculam a aceleração de um veículo em função do veículo líder imediato. Cobrir os modelos principais:

- **Modelo de Pipes (1953):** precursor, distância mínima proporcional à velocidade.
- **Gipps Model (1981):** baseado em segurança, vmax suave.
- **Krauss Model (1998):** adotado no SUMO e no HTC — calcula velocidade segura com fator de ruído estocástico:

$$
v_{\text{safe}} = -\tau \cdot b + \sqrt{(\tau \cdot b)^2 + v_{\text{leader}}^2 + 2 \cdot b \cdot \text{gap}}
$$

$$
v_{\text{new}} = \min(v + a \cdot \Delta t,\ v_{\text{safe}},\ v_{\text{desired}}) + \epsilon
$$

- **IDM — Intelligent Driver Model (Treiber, 2000):** determinístico, amplamente validado, gap desejado dinâmico.

Destacar por que o HTC adotou o Krauss: compatibilidade com SUMO (facilita validação cruzada), estabilidade numérica, e extensibilidade via interface `CarFollowingModel`.

#### 2.3.2 Modelos de Lane Change: MOBIL

Apresentar o framework MOBIL (Minimizing Overall Braking deceleration Induced by Lane Changes — Kesting, 2007). Explicar o critério de ganho de utilidade para troca de faixa e o critério de cortesia (impacto sobre veículos na faixa alvo). Descrever como o HTC implementa MOBIL via `MobilLaneChange.scala` e `LaneManager.scala`.

**Referências-chave:**
- Krauss (1998) — *Microscopic Modeling of Traffic Flow*
- Treiber, Hennecke & Helbing (2000) — IDM
- Kesting, Treiber & Helbing (2007) — MOBIL

### 2.4 Modelos Híbridos Micro-Mesoscópicos

Esta seção é o cerne teórico da proposta. Apresentar o problema de **acoplamento** entre regiões de diferentes granularidades: como um veículo transitando de uma região MESO para uma região MICRO deve ter seu estado inicializado? Como as condições de contorno entre regiões são tratadas?

Cobrir trabalhos seminais:
- **Bourrel & Lesort (2003):** primeiro trabalho sistemático sobre acoplamento micro-meso; define as "zonas de transição" como regiões buffer onde as densidades são harmonizadas.
- **Burghout (2004):** abordagem de acoplamento para simuladores de rede urbana; discute condições de contorno e preservação de fluxo.
- **Poschinger, Kates & Kuhne (2002):** interface entre Wiedemann (micro) e macroscópico.

Apresentar o diferencial do HTC: a hibridização é resolvida **ao nível do link individual**, sem zonas de transição explícitas — o próprio ator-veículo adapta seu comportamento ao entrar num link de modo diferente, mantendo o invariante de fluxo pela conservação do tick de saída.

### 2.5 Sistemas Multi-Agentes e Paradigma de Atores

Explicar o **Modelo de Ator** (Hewitt, 1973; Agha, 1986): cada ator é uma unidade computacional independente com estado privado, que se comunica exclusivamente por troca de mensagens assíncronas. Apresentar as propriedades desejáveis para simulação: isolamento de falhas, escalabilidade horizontal, ausência de memória compartilhada (sem locks).

Apresentar o **Apache Pekko** (fork open-source do Akka) como implementação industrial do modelo de atores na JVM. Descrever o mecanismo de **relógios de Lamport** para ordenação de eventos distribuídos, utilizado no HTC para garantir a ordenação causal dos ticks de simulação.

Conectar com a literatura de **Discrete Event Simulation (DES)**: o HTC é uma DES distribuída onde cada ator é uma entidade discreta, e o avanço do tempo é controlado pelo `TimeManager`.

**Referências-chave:**
- Hewitt (1973) — *A Universal Modular ACTOR Formalism*
- Agha (1986) — *Actors: A Model of Concurrent Computation*
- Lamport (1978) — *Time, Clocks, and the Ordering of Events*
- Bonabeau (2002) — *Agent-Based Modeling* (PNAS)

### 2.6 Simulação Orientada a Eventos Distribuída (PDES)

Apresentar os fundamentos de **Parallel and Distributed Discrete Event Simulation (PDES)**: particionamento do espaço de estados, sincronização (protocolos conservativos de Chandy-Misra vs. otimistas de Jefferson/Time Warp). Explicar como o HTC evita o problema de sincronização ao adotar um modelo de ticks globais gerenciados pelo `TimeManager`, com sub-ticks locais nos links MICRO (`LinkMicroTimeManager`) para evitar que a granularidade fina dos links microscópicos crie gargalo no relógio global.

**Referências-chave:**
- Fujimoto (2000) — *Parallel and Distributed Simulation Systems*
- Chandy & Misra (1979) — Conservative PDES
- Jefferson (1985) — Time Warp

### 2.7 Digital Twins e Gêmeos Digitais de Transporte

Definir **Digital Twin** no contexto de sistemas de transporte: uma réplica computacional em tempo (quase-)real de uma infraestrutura física, alimentada por dados de sensores, capaz de executar simulações preditivas. Apresentar aplicações na literatura: gestão de frotas, planejamento de manutenção, análise de impacto de obras.

Contextualizar que um requisito central para Digital Twins é a capacidade de executar **múltiplos cenários "what-if" rapidamente**. Um simulador que leva horas para simular um dia de tráfego é inviável nesse contexto. Isso motiva diretamente a integração do **SimEDaPE**.

**Referências-chave:**
- Grieves (2014) — *Digital Twin: Manufacturing Excellence*
- Tao et al. (2018) — *Digital Twin Driven Product Lifecycle Management*
- Boje et al. (2020) — *Towards a Semantic Construction Digital Twin*

### 2.8 SimEDaPE: Aceleração por Reconhecimento de Padrões

Apresentar o **SimEDaPE** (Simulation with Event-Driven Pattern Execution) como uma técnica de aceleração de simulações baseada na identificação de **padrões recorrentes de eventos**: quando o sistema detecta que o estado atual é equivalente a um estado anterior já simulado, é possível "pular" para o estado futuro correspondente, evitando recomputação.

Discutir as condições de aplicabilidade: convergência de estados, critério de equivalência, overhead de indexação vs. ganho de salto. Apresentar o cenário ideal de aplicação no HTC: simulações diárias de tráfego com picos matinais e vespertinos altamente recorrentes.

*(Esta seção deve ser expandida com referências específicas ao trabalho original do SimEDaPE — incluir publicações do grupo de pesquisa conforme disponível.)*

### 2.9 Modelagem de Emissões de Poluentes em Simuladores

Apresentar os modelos de estimativa de emissões instante a instante para simuladores microscópicos:
- **HBEFA (Handbook Emission Factors for Road Transport):** baseado em fatores de emissão por situação de tráfego.
- **COPERT:** metodologia europeia, velocidade média.
- **MOVES (EPA):** modelo americano de emissões por modo operacional.
- **Modelo de Akçelik:** baseado em velocidade instantânea e aceleração.

Discutir como o nível de granularidade do simulador impacta diretamente a qualidade das estimativas de emissão: modelos microscópicos capturam aceleração/desaceleração (ciclos de parada-e-arranca) que são os maiores geradores de emissões em ambiente urbano. Mesoscópicos subestimam essas emissões em congestionamento. Isso justifica a relevância científica da abordagem híbrida para métricas de sustentabilidade.

---

## 3. Trabalhos Relacionados

> **Como escrever de forma fluida:** Organize por tema (não por autor). Para cada grupo, apresente as contribuições e, ao final, sintetize como o HTC se diferencia. Evite simplesmente listar artigos; conecte cada trabalho ao problema que resolve e à lacuna que deixa.

### 3.1 Simuladores de Tráfego Estabelecidos

| Simulador | Nível | Distribuído | Híbrido | Open Source |
|-----------|-------|-------------|---------|-------------|
| SUMO | Micro | ❌ | ❌ | ✅ |
| MATSim | Meso/Macro | Parcial | ❌ | ✅ |
| VISSIM | Micro | ❌ | ❌ | ❌ |
| Aimsun Next | Micro+Meso | Parcial | ✅ | ❌ |
| DynaMIT | Meso | ❌ | ❌ | ❌ |
| **HTC** | **Híbrido** | **✅** | **✅** | **✅** |

**SUMO** é o principal ponto de comparação para validação do HTC. Discutir as limitações de escalabilidade do SUMO: monolítico, single-thread por padrão (TraCI para controle externo), sem suporte nativo a distribuição horizontal.

**MATSim** adota um modelo de co-evolução de planos de viagem sobre uma simulação mesoscópica de cargas, com forte suporte a escalabilidade via paralelismo de threads. A ausência de modo microscópico limita sua aplicabilidade para análise de corredores.

**Aimsun Next** é o único simulador comercial com modo híbrido nativo. Porém, é proprietário, não distribuído e não extensível livremente.

### 3.2 Abordagens Híbridas na Literatura

Apresentar cronologicamente o desenvolvimento das abordagens híbridas:

- **Poschinger et al. (2002):** acoplamento Wiedemann (micro) com Payne (macro) — zonas de transição com interpolação.
- **Bourrel & Lesort (2003):** trabalho seminal de acoplamento micro-meso; define o problema formal da condição de contorno.
- **Burghout (2004):** tese de doutorado; implementação prática de interfaces micro-meso em redes urbanas (Estocolmo).
- **Choudhury et al. (2010):** acoplamento multi-resolução para cenários de BRT.
- **Lu et al. (2015):** framework de acoplamento dinâmico com zonas de transição adaptativas.

Destacar a **lacuna que o HTC endereça**: nenhuma das abordagens acima implementa hibridização dentro de um framework de atores distribuídos horizontalmente escaláveis. A maioria adota a abordagem de regiões buffer explícitas; o HTC resolve o acoplamento implicitamente via adaptação do estado do ator-veículo ao entrar num link de modo diferente.

### 3.3 Simulação Multi-Agentes Distribuída para Tráfego

- **TRANSIMS (Barrett et al., 1995):** pioneiro em simulação de transporte baseada em agentes — cada pessoa é um agente com plano de viagem. Paralelo, mas não distribuído no sentido de cluster horizontal.
- **MATSIM + Parallel Quickest Path:** extensões MATSim para clusters; foco em routing, não em dinâmica veicular.
- **CityMoS (Aydt et al., 2012):** framework distribuído para simulação de cidades; orientado a Akka, mais próximo do HTC. Sem hibridização micro-meso.
- **SimMobility (Adnan et al., 2016):** simulação multi-escala de mobilidade com agentes, focada em demanda de viagens. Não distribui horizontalmente a dinâmica veicular.

O HTC se distingue por ser o único framework que combina: (i) paradigma de atores distribuídos, (ii) hibridização micro-meso por link, e (iii) modelo multi-modal completo (carro, ônibus, metrô, bicicleta, motocicleta, pedestre).

### 3.4 Digital Twins em Transporte Urbano

Apresentar iniciativas recentes:
- **Virtual Singapore:** gêmeo digital urbano com componente de tráfego, mas sem simulação dinâmica em tempo real.
- **Living Lab Helsinki:** integração IoT + simulação, foco em pedestres.
- **Siemens Yunex Traffic:** plataforma comercial de DT para semáforos e fluxo.

Lacuna: ausência de plataformas open-source de Digital Twin para tráfego urbano com suporte a cenários híbridos e aceleração por padrões.

### 3.5 Emissões em Simuladores

- **Integração SUMO-HBEFA:** extensão PHEMlight no SUMO para emissões veículo a veículo — limitada à escala do SUMO.
- **MOVES-HER:** modelo da EPA com granularidade horária por link — não integrado a simulação em tempo real.
- **Copert + MATSim:** pós-processamento de velocidades médias do MATSim com fatores COPERT — perde informação de aceleração.

**Oportunidade:** o HTC, operando no modo MICRO em corredores de alta emissão, pode calcular emissões instante a instante com acurácia comparável ao SUMO, mantendo eficiência mesoscópica no restante da rede.

---

## 4. Metodologia

> **Como escrever de forma fluida:** A metodologia deve ser uma narrativa de decisões justificadas. Para cada componente, apresente: *o que é*, *por que foi escolhido assim* (em contraposição a alternativas), e *como se conecta com os demais componentes*. Use diagramas quando disponíveis. Evite enumeração pura de tecnologias; contextualize cada escolha.

### 4.1 Visão Geral da Abordagem

O HTC é desenvolvido seguindo uma metodologia de **pesquisa orientada a artefato** (*Design Science Research* — Hevner et al., 2004): o próprio sistema computacional é o artefato de pesquisa, e as contribuições científicas emergem do processo de seu design, implementação e avaliação empírica.

A metodologia se organiza em quatro ciclos:

```
[CICLO 1] Motor Híbrido
    Implementar transições MESO ↔ MICRO em atores distribuídos
         ↓
[CICLO 2] Linguagem de Definição + Integração SUMO
    Padronizar formato de entrada e saída; pipeline de validação
         ↓
[CICLO 3] SimEDaPE
    Integrar aceleração por padrões; avaliar ganho em cenários "what-if"
         ↓
[CICLO 4] Emissões e Estudos de Caso
    Modelar CO₂/NOx/PM2.5; aplicar em SP e Toulouse
```

Cada ciclo produz: (a) implementação no HTC, (b) experimento de avaliação, (c) publicação científica associada.

### 4.2 O Simulador HTC: Arquitetura, Decisões e Modelo

Esta seção descreve em detalhe o Hyperbolic Time Chamber (HTC) como artefato de pesquisa: seu motor de simulação, os gerenciadores de sistema, o ciclo de vida completo de uma simulação e cada elemento do modelo de mobilidade.

---

#### 4.2.1 Decisões Arquiteturais Fundamentais

As decisões de design do HTC não são escolhas acidentais de tecnologia — cada uma resolve um problema específico de simulação distribuída em larga escala e devem ser apresentadas como tal na metodologia.

##### (A) Paradigma de Atores como núcleo computacional

**Problema:** simuladores de tráfego tradicionais são monolíticos. O SUMO executa em single-thread por padrão; mesmo implementações paralelas do MATSim limitam o paralelismo a operações de routing. Ao atingir centenas de milhares de veículos, o custo de sincronização de memória compartilhada (mutexes, locks) cresce super-linearmente.

**Decisão:** toda lógica de domínio é encapsulada em **atores independentes** (Apache Pekko) que se comunicam exclusivamente por troca de mensagens assíncronas. Não há memória compartilhada entre atores.

**Consequências projetadas:**
- Cada entidade (veículo, segmento de via, interseção, semáforo) é um ator com estado interno privado e imutável (Scala `case class`).
- Transições de estado são **funções puras**: `(State, Event) → NewState`. Isso facilita testes, rastreamento de bugs e raciocínio sobre comportamento.
- Comunicação entre entidades geograficamente separadas (e.g., carro em nó A pedindo estado do semáforo em nó B) é idêntica à comunicação local — o runtime resolve o roteamento transparentemente.
- Escalabilidade horizontal emerge naturalmente: atores são distribuídos pelo cluster via **Cluster Sharding**, sem alteração de código.

##### (B) Separação núcleo/modelo (Core vs. Domain)

**Problema:** acoplar a infraestrutura de simulação ao domínio de tráfego impede reutilização e dificulta testes unitários.

**Decisão:** o pacote `core/` é **agnóstico ao domínio**. Ele provê gerenciamento de tempo, eventos, carregamento de dados, serialização e métricas. Os pacotes `model/mobility/` e `model/hybrid/` implementam o domínio de tráfego via extensão das classes base do core. O pacote `model/supermarket/` demonstra que a mesma infraestrutura pode ser reutilizada para simulações completamente diferentes.

##### (C) Modo de simulação definido pelo Link, não pelo Veículo

**Problema:** numa abordagem híbrida ingênua, cada veículo decidiria seu modo de simulação. Isso criaria inconsistências: dois veículos no mesmo link poderiam usar modelos diferentes, tornando a dinâmica sem sentido físico.

**Decisão:** o **link é o ponto de decisão de modo**. Todos os veículos que entram num link adotam o modo daquele link (`MESO` ou `MICRO`). O link é também o locus natural de controle do fluxo — já gerencia entrada e saída de veículos, conhece a capacidade e o comprimento.

**Consequência:** a hibridização é transparente ao veículo. O mesmo ator-veículo transita entre links MESO e MICRO sem "saber" sobre a topologia global. Isso é análogo ao princípio de encapsulamento: o veículo conhece apenas a interface do link, não sua implementação interna.

##### (D) Gerenciamento de Tempo Hierárquico

**Problema:** um único gerenciador de tempo centralizado torna-se gargalo em clusters com milhões de atores. Por outro lado, sem coordenação global, a simulação perde consistência causal — um carro poderia "perceber" um evento que ainda não aconteceu em outro nó do cluster.

**Decisão:** três camadas de gerenciamento de tempo:

```
Camada 1: GlobalTimeManager (Cluster Singleton)
          ↓ coordena barreiras de tick
Camada 2: LocalTimeManagers (Pool Distribuído via ClusterRouterPool)
          ↓ executa eventos por subconjunto de atores
Camada 3: LinkMicroTimeManager (dentro de cada Link MICRO)
          ↓ executa sub-ticks sem comunicação com o nível acima
```

Links MICRO executam seus 10 sub-ticks de 0.1s internamente, gerando apenas 1 mensagem por tick global ao nível acima (ao invés de 10). Isso reduz o overhead de sincronização em 10× para a fração microscópica da rede.

##### (E) Carregamento Progressivo de Veículos

**Problema:** uma cidade como São Paulo tem ~6 milhões de viagens diárias. Criar todos os atores antes do início da simulação inviabiliza execuções em qualquer hardware razoável.

**Decisão:** veículos são carregados **sob demanda**, em janelas de ticks adaptativas (~50.000 atores por janela). A infraestrutura (nodes, links, semáforos) é carregada de forma EAGER antes do início. O `ProgressiveLoadDataManager` mantém apenas um índice leve (contagem de atores por tick) em memória e re-lê o JSON sob demanda.

---

#### 4.2.2 Hierarquia de Classes e Composição

O HTC usa uma hierarquia de classes cuidadosamente estratificada, explorada para explicar cada camada da stack ao avaliador:

```
PersistentActor (Pekko)
└── BaseActor[T <: BaseState]
     │  ├── entityId, state: T, ciclo de vida (preStart/onDestruct)
     │  ├── deserialização de estado via Jackson
     │  └── suporte a Cluster Sharding
     │
     └── SimulationBaseActor[T <: BaseState]
          │  ├── LamportClock (ordenação causal distribuída)
          │  ├── currentTick, switchTimeManager()
          │  ├── sendMessageTo() → abstrai shard vs. pool
          │  └── report() → coleta de métricas
          │
          ├── Movable[T <: MovableState]      ← template para entidades móveis
          │    ├── Car / Bus / Bicycle / Motorcycle (veículos privados)
          │    ├── Person (agente com agenda diária)
          │    └── Subway (metrô)
          │
          ├── Link                            ← segmento viário (MESO ou MICRO)
          ├── Node                            ← interseção
          ├── TrafficSignal                   ← semáforo
          ├── BusStop / BusStation            ← infraestrutura de ônibus
          └── SubwayStation                   ← estação de metrô
```

**Composição via Traits Scala:** o mecanismo de *mixin* do Scala é utilizado para adicionar comportamentos ortogonais sem herança múltipla:
- `PrivateVehicle[T]` — adicionado a `Car`, `Bicycle` e `Motorcycle`. Adiciona o estado `Parked`, gerencia ativação/desativação pelo agente `Person`, e implementa o ciclo `StartTrip → TripCompleted → Parked`.
- `MicroAwareTimeManager` — adicionado ao `LocalTimeManager` para habilitar o disparo de sub-ticks microscópicos antes do processamento regular de cada tick.

**Implicação para a apresentação:** esta separação demonstra que o HTC não é apenas um "simulador de tráfego" — é uma **plataforma de simulação orientada a atores** com um modelo de mobilidade como plugin.

---

#### 4.2.3 Os Gerenciadores do Sistema

O HTC tem quatro gerenciadores principais, cada um com papel distinto no ecossistema de simulação. Todos são Cluster Singletons (executam em exatamente um nó, com failover automático).

##### SimulationManager

O orquestrador central. Responsável por:
- Carregar e validar a configuração JSON da simulação.
- Coordenar o bootstrap: cria GlobalTimeManager, ReportManager, e os managers de carregamento.
- Receber e rotear eventos de ciclo de vida (`FinishLoadDataEvent`, `StopSimulationEvent`).
- Registrar o estado do cluster para diagnóstico: membros ativos, roles, nó líder.

O `SimulationManager` nunca toca dados de domínio diretamente — ele apenas coordena quem faz o quê e em que ordem.

##### GlobalTimeManager

O relógio global da simulação. Responsável por:
- Manter o `currentGlobalTick` e `simulationEndTick`.
- Criar e gerenciar o pool de `LocalTimeManagers` via `ClusterRouterPool`.
- Implementar a **barreira de sincronização conservadora**: coleta `LocalTimeReportEvent` de cada LocalTM e avança para `nextTick = min(todos os reportedTicks)`. Isso garante que nenhum LocalTM processe o tick T+1 antes que todos tenham terminado o tick T.
- Coordenar com o `ProgressiveLoadDataManager`: bloqueia o avanço de ticks quando a janela de carregamento ainda não foi completada.
- Publicar métricas de progresso via Prometheus (tick atual, taxa de ticks/s, progresso percentual).

O tick global é medido em **segundos de simulação**. Um dia completo de simulação corresponde a 86.400 ticks.

##### LoadDataManager e ProgressiveLoadDataManager

Responsáveis pela criação dos atores de simulação a partir das fontes de dados JSON.

O **LoadDataManager** processa fontes EAGER (infraestrutura), criando atores em batches e aguardando confirmação `InitializeEntityAckEvent` de cada um antes de prosseguir. Distingue entre atores `LoadBalancedDistributed` (Cluster Sharding) e `PoolDistributed`.

O **ProgressiveLoadDataManager** gerencia o carregamento dinâmico de veículos:

1. **Indexação leve**: percorre o arquivo JSON apenas para construir `Map[Tick, Int]` (quantos atores iniciam em cada tick), sem manter objetos em memória.
2. **Janela adaptativa**: quando o GlobalTM solicita `TickWindowRequest(atual, horizonte)`, calcula a próxima janela para atingir ~50.000 atores, expandindo ou contraindo o range conforme a densidade de veículos por tick.
3. **Pre-fetch proativo**: antes que a janela atual expire, solicita a próxima janela antecipadamente, eliminando stalls no loop de simulação.

##### ReportManager

Responsável pela coleta e exportação de métricas. Cria pools de reporters (CSV e JSON) distribuídos pelo cluster. Atores de domínio chamam `report(data, label)` no `SimulationBaseActor`, que roteia os dados para o reporter adequado. O ReportManager produz saídas compatíveis com o formato SUMO `tripinfo.xml`, habilitando o pipeline de validação HTC ↔ SUMO.

---

#### 4.2.4 Ciclo de Vida Completo da Simulação

O ciclo de vida de uma simulação no HTC passa por **sete fases** orquestradas sequencialmente:

```
┌─────────────────────────────────────────────────────────────────┐
│  FASE 1: Bootstrap do Sistema                                    │
│  main() → ActorSystem → RandomSeedManager → ShardRegions         │
│  → SimulationManager (singleton)                                 │
├─────────────────────────────────────────────────────────────────┤
│  FASE 2: Preparação da Simulação                                  │
│  SimulationManager → carrega config JSON → cria GlobalTM          │
│  → cria ReportManager                                            │
├─────────────────────────────────────────────────────────────────┤
│  FASE 3: Inicialização dos Gerenciadores                          │
│  GlobalTM → cria pool de LocalTMs (ClusterRouterPool)             │
│  ReportManager → cria pools CSV/JSON reporters                   │
├─────────────────────────────────────────────────────────────────┤
│  FASE 4: Carregamento Eager                                       │
│  LoadDataManager → cria Nodes, Links, TrafficSignals,            │
│  BusStops, SubwayStations (infraestrutura completa)               │
│  Cada ator recebe InitializeEvent → registra no TimeManager       │
├─────────────────────────────────────────────────────────────────┤
│  FASE 5: Início com Carregamento Progressivo                      │
│  ProgressiveLDM indexa veículos por tick (leve)                   │
│  GlobalTM aguarda janela inicial carregada → StartSimulation      │
├─────────────────────────────────────────────────────────────────┤
│  FASE 6: Loop Principal de Simulação                              │
│  GlobalTM → Broadcast(UpdateGlobalTime(tick))                    │
│  → LocalTMs disparam sub-ticks MICRO → processam eventos MESO     │
│  → SpontaneousEvent para cada ator registrado                    │
│  → atores executam lógica → emitem FinishEvent                   │
│  → LocalTMs reportam ao GlobalTM → nextTick = min(todos)          │
│  → pré-fetch adaptativo de próxima janela de veículos            │
│  → LOOP                                                          │
├─────────────────────────────────────────────────────────────────┤
│  FASE 7: Término                                                  │
│  tickOffset >= simulationDuration || sem eventos pendentes        │
│  → StopSimulation → destruct atores → flush reporters             │
└─────────────────────────────────────────────────────────────────┘
```

**Ponto importante para a qualificação:** a barreira de sincronização conservadora garante **consistência causal** mas impõe um custo: o throughput do loop é limitado pelo LocalTM mais lento de cada tick. Isso é um trade-off explícito escolhido por simplicidade e correção; o protocolo otimista (Time Warp) exigiria rollback de estado, que é incompatível com a comunicação assíncrona não-idempotente entre atores.

---

#### 4.2.5 O Sistema de Comunicação: Eventos e Protocolos

Toda comunicação no HTC é por mensagens tipadas. Há quatro categorias:

| Categoria | Direção | Propósito |
|-----------|---------|-----------|
| `SpontaneousEvent` | TM → Ator | "É a sua vez neste tick" |
| `ActorInteractionEvent` | Ator → Ator | Comunicação de domínio (EnterLink, SignalState, etc.) |
| `FinishEvent` | Ator → TM | "Terminei este tick; reagende-me no tick X" |
| `ScheduleEvent` | Ator → TM | Solicita ativação futura em tick específico |

O `ActorInteractionEvent` carrega um **Lamport clock** do remetente, garantindo ordenação causal: se o evento A causou o evento B, então `lamport(B) > lamport(A)` em qualquer execução, mesmo distribuída.

O **ShardRegion** (Pekko Cluster Sharding) atua como proxy transparente: `sendMessageTo(destinationId, data)` resolve automaticamente se o ator destino está no mesmo nó JVM ou em outro nó do cluster, sem que o código de domínio precise saber.

---

#### 4.2.6 Elementos do Modelo de Mobilidade

Esta seção descreve cada entidade do modelo `model/hybrid/`, explicando sua responsabilidade, estado interno e protocolo de comunicação.

##### Link (Segmento Viário)

O `Link` é o elemento mais crítico do modelo. Opera em dois modos completamente diferentes:

**Modo MESO (comportamento passivo — contador):**
- Mantém `registered: Set[VehicleEntry]` — conjunto de veículos presentes.
- Quando um veículo envia `EnterLinkData`: registra o veículo e responde com `LinkInfoData` contendo comprimento, capacidade, número atual de veículos e velocidade de fluxo livre.
- O veículo usa esses dados para calcular sua própria velocidade (BPR) e programar seu próprio timer de saída.
- Quando o veículo envia `LeaveLinkData`: remove o registro e confirma com `LinkInfoData`.
- O Link MESO **nunca agenda eventos espontâneos** — é completamente reativo.

**Modo MICRO (comportamento ativo — simulador local):**
- Mantém `vehiclesByLane: Map[Int, Queue[VehicleInLane]]` — veículos organizados por faixa e posição.
- Quando um veículo envia `EnterLinkData`: atribui faixa (faixa menos ocupada), cria `VehicleInLane` e responde com `MicroEnterLinkData` contendo parâmetros do link e faixa atribuída.
- A cada tick global, executa N sub-ticks internamente via `MicroSimulationStrategy`:
  - Para cada faixa, para cada veículo (frente → traseira):
    - Identifica o veículo líder e calcula o gap efetivo.
    - Aplica o modelo de car-following (Krauss por padrão) para calcular nova velocidade.
    - Atualiza posição via integração trapezoidal.
    - Rastreia tempo de espera (velocidade < 0.1 m/s).
  - Ao final de todos os sub-ticks: envia `MicroUpdateData` a cada veículo com posição, velocidade, aceleração e dados do líder.
- Publica custo dinâmico de roteamento periodicamente (baseado na velocidade média observada).

**Estado do Link (`HybridLinkState`):**
```
from, to               → nós de origem e destino
length                 → comprimento em metros
lanes                  → número de faixas
speedLimit             → velocidade máxima (km/h)
capacity               → capacidade máxima de veículos
freeSpeed              → velocidade de fluxo livre (m/s)
simulationMode         → MESO | MICRO
microTimeStep          → Δt por sub-tick (padrão: 0.1s)
microTicksPerGlobalTick → sub-ticks por tick global (padrão: 10)
vehiclesByLane         → faixas com filas de veículos (apenas MICRO)
laneConfigurations     → tipo de cada faixa (normal, bus_lane, bike_lane)
```

##### Node (Interseção)

O `Node` representa uma interseção viária. Suas responsabilidades são:

- **Resolver consultas de estado de semáforo:** quando um veículo está prestes a sair de um link, consulta o nó destino com `RequestSignalStateData`. O nó retorna `SignalStateData` com a fase atual (verde/vermelho) e o tick da próxima mudança.
- **Indexar conexões de transporte público:** mantém referências a `BusStop`s e `SubwayStation`s adjacentes.
- **Gerenciar conexões para roteamento dinâmico:** armazena mapa de links de saída e seus custos dinâmicos atuais.

Para o modo MICRO, o `Node` recebe eventos de veículos saindo de links MICRO e coordena a entrada no próximo link, respeitando prioridades de conflito de interseção (implementação futura via `MicroIntersectionController`).

**Estado do Node (`HybridNodeState`):**
```
latitude, longitude    → coordenadas geográficas
links                  → lista de links conectados
connections            → mapa de links de saída (para roteamento)
signals                → estados atuais de semáforos por fase
busStops               → paradas de ônibus adjacentes
subwayStations         → estações de metrô adjacentes
```

##### TrafficSignal (Semáforo)

O `TrafficSignal` implementa controles semafóricos com fases configuráveis. Cada fase define a duração de verde e vermelho por grupo de movimento. O semáforo envia `TrafficSignalChangeStatusData` ao `Node` quando muda de fase, atualizando o estado que os veículos consultam.

O sistema suporta tanto **semáforos de ciclo fixo** (duração determinística) quanto **semáforos atuados** (duração ajustada por densidade de veículos, como extensão futura).

##### Car (Veículo Privado)

O `Car` (e por extensão `Bicycle` e `Motorcycle` via `PrivateVehicle` trait) implementa a **máquina de estados finita de mobilidade**:

```
Parked → (StartTrip do Person) → Start → requestRoute() → Ready
→ enterLink() → Waiting → (LinkInfo) → Moving
→ (exitTick ou position >= length) → WaitingSignalState
→ (SignalStateData verde) → leavingLink() → Ready (próximo link)
→ ... (repete por cada link da rota) ...
→ Finished → (TripCompleted ao Person) → Parked
```

**No modo MESO:** o veículo é *self-managed* — calcula seu próprio tempo de saída via BPR e agenda seu próprio `SpontaneousEvent` para o `exitTick`.

**No modo MICRO:** o veículo é *passivo* — cancela seu timer espontâneo ao entrar no link MICRO e aguarda `MicroUpdateData` do link. Quando a posição atinge o comprimento do link, inicia o procedimento de saída.

**DriverAttributes:** cada instância de `Car` pode receber parâmetros de condução do `Person` que o ativa:
- `aggressiveness` [0–1]: multiplica a aceleração máxima.
- `maxSpeedFactor` [0.5–1.5]: multiplica a velocidade desejada.
- `reactionTime` [0.5–2.0s]: parâmetro τ do Krauss.
- `minGapFactor` [0.5–2.0]: multiplica o gap mínimo de segurança.

Isso introduz heterogeneidade de comportamento — um requisito de realismo em simuladores urbanos.

**Estado do Car (`HybridCarState`):**
```
startTick              → tick de início da viagem
origin, destination    → IDs dos nós de origem e destino
bestRoute              → fila de (linkId, nodeId) pré-calculada pelo A*
currentNode            → nó atual
distance               → distância percorrida (metros)
movableStatus          → estado atual da FSM
currentSimulationMode  → MESO | MICRO
microState             → Option[MicroCarState] (ativo apenas em links MICRO)
```

**Estado microscópico (`MicroCarState`):**
```
positionInLink         → posição no link (metros)
velocity               → velocidade atual (m/s)
acceleration           → aceleração atual (m/s²)
currentLane            → faixa atual (índice)
leaderVehicle          → ID do veículo à frente
gapToLeader            → gap efetivo (metros)
leaderVelocity         → velocidade do líder (m/s)
maxAcceleration        → parâmetro do carro (2.6 m/s² padrão)
maxDeceleration        → parâmetro do carro (4.5 m/s² padrão)
minGap                 → gap mínimo (2.0 m padrão)
desiredVelocity        → velocidade desejada (13.89 m/s = 50 km/h padrão)
vehicleLength          → comprimento do veículo (4.5 m padrão)
```

##### Bus (Ônibus)

O `Bus` estende `Movable` com gestão de passageiros e interações com paradas. Em modo MESO, segue o mesmo padrão de roteamento dos veículos privados, mas com paradas intermediárias obrigatórias (`busStops`). Em modo MICRO, seu comprimento (12m) afeta os gaps calculados pelo car-following dos veículos atrás.

**Diferencial arquitetural:** o `Bus` não usa `PrivateVehicle` trait — não tem proprietário `Person`. Tem seu próprio `startTick` e rota pré-definida entre terminais. Isso modela a operação de transporte público baseada em horários (GTFS-like).

##### Bicycle e Motorcycle

Tipos novos introduzidos no modelo híbrido, sem equivalente no modelo mesoscópico original (`model/mobility/`). Ambos usam `PrivateVehicle` trait e a mesma FSM dos carros, mas com parâmetros físicos distintos:

| Parâmetro | Bicicleta | Motocicleta |
|-----------|-----------|-------------|
| Comprimento | 2.0 m | 2.5 m |
| $a_{\max}$ | 1.0 m/s² | 3.5 m/s² |
| $v_{\text{des}}$ | 20 km/h | 60 km/h |
| Tempo de reação | 1.2 s | 0.9 s |
| Comportamento especial | Preferência por ciclovias | Lane filtering |

A **motocicleta** implementa `filteringBetweenLanes`: quando em modo MICRO, pode navegar entre faixas em congestionamento, modelando o comportamento de *lane splitting* observado em cidades brasileiras e francesas.

##### Person (Agente com Agenda Diária)

O `Person` é o agente de nível superior do modelo centrado em pessoas (*activity-based*). Persiste durante toda a simulação (ao contrário dos veículos, que podem ser destruídos após a viagem). Gerencia:

- **Agenda diária (`dailySchedule`):** sequência de atividades (Home → Work → Shopping → Home) com horários e modos de transporte para cada deslocamento.
- **Decisão de modo:** ao término de cada atividade, `executeModeChoice()` seleciona o veículo disponível (carro, bicicleta, etc.) ou modo de transporte público.
- **Gestão de veículos privados:** ativa o veículo com `StartTripData` (incluindo `DriverAttributes`) e aguarda `TripCompletedData`.
- **Transporte a pé e instantâneo:** deslocamentos curtos ou de transporte público podem ter chegada instantânea (`instant: true`) sem criar ator de veículo.

##### Subway (Metrô)

O `Subway` opera em redes ferroviárias separadas (`RailLink`) com validação de tipo de veículo na entrada (apenas subways podem usar rail links). Opera principalmente em modo MESO (velocidades altas em vias dedicadas), com modo MICRO opcional para interações em estações (múltiplos trens, embarque/desembarque com precisão de posição).

O modelo suporte rotas de metrô definidas via `SubwayRoutes` no input JSON, com paradas em `SubwayStation`s que gerenciam embarque de passageiros do tipo `Person`.

---

#### 4.2.7 Roteamento Dinâmico

O HTC implementa roteamento em duas camadas:

**Roteamento inicial (A\* com pesos estáticos):** ao iniciar uma viagem, o veículo calcula a rota ótima via algoritmo A* sobre o grafo da cidade. Os pesos são os tempos de travessia estimados em fluxo livre.

**Atualização dinâmica de pesos:** Links em modo MICRO publicam periodicamente seu custo dinâmico atual (baseado na velocidade média observada nos sub-ticks). Esses custos são propagados via um mecanismo de cache distribuído. Veículos que ainda não iniciaram uma viagem podem recalcular sua rota com os custos atualizados, modelando comportamento adaptativo de motoristas com informação de tráfego em tempo real.

---

#### 4.2.8 Sistema de Métricas e Observabilidade

O HTC produz métricas em dois níveis:

**Nível de runtime (Prometheus):**
- Tick atual, progresso percentual, taxa de ticks/s.
- Tamanho dos shards, balanceamento de carga pelo cluster.
- Latência de mensagens, tamanho de mailboxes de atores.

**Nível de simulação (CSV/JSON):**
- Por veículo: `tripinfo` (tempo de viagem, distância, atraso, velocidade média) — formato compatível com SUMO.
- Por link: contagem de veículos por tick, velocidade média, nível de congestionamento.
- Passo de tempo: resumo global a cada N ticks (SUMO `summary` format).

A compatibilidade com o formato SUMO habilita o pipeline de validação automatizado (`compare_sumo_htc_results.py`), central para o objetivo de validação da metodologia.

### 4.3 Motor de Simulação Híbrido (Objetivo 1)

#### 4.3.1 O Problema da Hibridização

O desafio central do motor híbrido é definir um **protocolo de transição** que garanta:
- **Conservação de fluxo:** o número de veículos que sai de um link mesoscópico deve ser consistente com a demanda de chegada ao link microscópico adjacente.
- **Inicialização de estado:** ao entrar num link MICRO, o veículo deve receber um estado microscópico válido (posição, velocidade, faixa) consistente com sua velocidade ao sair do link MESO anterior.
- **Terminação limpa:** ao sair de um link MICRO, o estado microscópico é descartado e o veículo retorna ao comportamento mesoscópico.

#### 4.3.2 Protocolo de Transição no HTC

A transição é implementada via protocolo de mensagens entre atores:

```
MESO → MICRO:
  Veículo recebe LeaveLink (link MESO)
    → Consulta próximo link → identifica modo MICRO
    → Envia EnterLink ao link MICRO
    → Link MICRO responde com MicroEnterLinkData
       (posição=0, velocidade=v_saída_meso, faixa=0)
    → Veículo ativa microState; desativa cálculo BPR

MICRO → MESO:
  LinkMicroTimeManager detecta posição >= comprimento
    → Envia MicroLeaveLinkData ao veículo
    → Veículo desativa microState
    → Veículo envia EnterLink ao próximo link (MESO)
    → Retorna ao comportamento mesoscópico normal
```

A velocidade de saída do link MESO é usada como velocidade inicial no link MICRO, preservando a continuidade cinética sem necessidade de zona de transição explícita.

#### 4.3.3 Modelos de Car-Following e Lane Change

O modelo padrão é o **Krauss (1998)**, implementado em `KraussModel.scala`:

$$
v_{\text{safe}} = -\tau b + \sqrt{(\tau b)^2 + v_l^2 + 2b \cdot g}
$$

$$
v_{\text{new}} = \min(v + a\Delta t,\ v_{\text{safe}},\ v_{\text{des}}) + \xi
$$

onde $\xi \sim U(0, \epsilon_{\text{max}})$ é o componente estocástico de variabilidade de motorista.

A interface `CarFollowingModel` permite substituição por IDM ou Gipps sem alteração dos atores de veículo.

Para troca de faixa, o critério MOBIL é:

$$
\tilde{a}_{\text{subject}} - a_{\text{subject}} > p \cdot (\tilde{a}_c + \tilde{a}_n - a_c - a_n) + \Delta a_{\text{th}}
$$

onde $p$ é o parâmetro de cortesia, $a$ representa acelerações e $\Delta a_{\text{th}}$ é um limiar de mudança.

#### 4.3.4 Parâmetros por Tipo de Veículo

| Parâmetro | Carro | Ônibus | Bicicleta | Motocicleta |
|-----------|-------|--------|-----------|-------------|
| Comprimento (m) | 4.5 | 12.0 | 2.0 | 2.5 |
| $a_{\max}$ (m/s²) | 2.6 | 1.2 | 1.0 | 3.5 |
| $b_{\max}$ (m/s²) | 4.5 | 3.5 | 3.0 | 5.0 |
| $v_{\text{des}}$ (km/h) | 50 | 40 | 20 | 60 |
| $\tau$ (s) | 1.0 | 1.5 | 1.2 | 0.9 |

### 4.4 Linguagem de Definição de Cenários (Objetivo 2)

#### 4.4.1 Motivação

A interoperabilidade entre simuladores é prejudicada pela ausência de um formato padrão de definição de cenários de tráfego. O SUMO usa `.net.xml` + `.rou.xml`; o MATSim usa `.xml` próprio; ferramentas de GIS exportam Shapefile ou GeoJSON. O HTC usa JSON estruturado, mas sem esquema formal publicado.

#### 4.4.2 Proposta

Propor e documentar formalmente um **esquema JSON (JSON Schema)** para definição de cenários HTC, cobrindo:
- Definição de rede (nós, links, modos de simulação por link)
- Definição de agentes (carros, ônibus, pedestres, com planos de viagem)
- Configuração de semáforos e transporte público
- Parâmetros de simulação (duração, tick size, seed)

Desenvolver conversores bidirecionais:
- **SUMO → HTC:** `htc_to_sumo_scenario.py` (já existente, a ser formalizado)
- **OSM → HTC:** extensão do pipeline de importação existente
- **HTC → SUMO:** para validação cruzada

#### 4.4.3 Critérios de Avaliação

A linguagem será avaliada quanto a: completude (cobertura de casos de uso), expressividade (capacidade de representar cenários híbridos), e interoperabilidade (roundtrip fidelity com SUMO).

### 4.5 Integração SimEDaPE (Objetivo 3)

#### 4.5.1 Motivação para Digital Twins

Um Digital Twin de tráfego urbano requer a execução de dezenas ou centenas de cenários "what-if" diariamente (e.g., "o que acontece se fecharmos a Avenida Paulista amanhã às 8h?"). Com tempo de simulação proporcional ao tempo real, isso é inviável. O SimEDaPE endereça esse gargalo.

#### 4.5.2 Mecanismo de Integração

A integração SimEDaPE no HTC ocorre em três camadas:

1. **Instrumentação:** o `ReportManager` do HTC emite snapshots de estado do sistema a cada N ticks, formando uma série temporal de estados.
2. **Reconhecimento de padrões:** o módulo SimEDaPE analisa a série temporal e identifica subsequências recorrentes (e.g., padrão de pico matinal).
3. **Aceleração:** quando o estado atual casa com um padrão conhecido, a simulação avança diretamente para o estado pós-padrão, substituindo N ticks de simulação por uma operação de lookup.

#### 4.5.3 Critério de Equivalência de Estados

O desafio técnico central é definir um **critério de equivalência** entre estados do sistema que seja: (a) suficientemente restritivo para garantir validade do salto, e (b) suficientemente permissivo para que padrões sejam encontrados com frequência. Propõe-se um critério baseado em:
- Densidade por link (desvio < $\delta_\rho$)
- Distribuição de velocidades por link (desvio < $\delta_v$)
- Estado dos semáforos (fase e tempo restante)

#### 4.5.4 Avaliação

Medir: (a) fator de aceleração (tempo simulação com SimEDaPE / sem SimEDaPE), (b) erro de métricas agregadas introduzido (tempo médio de viagem, densidade média), (c) número de padrões identificados por tipo de cenário.

### 4.6 Métricas de Sustentabilidade e Emissões (Objetivo 4)

#### 4.6.1 Modelo de Emissões

Para o modo MICRO, adotar o modelo **VSP (Vehicle Specific Power)** instante a instante:

$$
\text{VSP} = v \cdot (a \cdot 1.1 + g \cdot \sin\theta + 0.132) + 0.000302 \cdot v^3
$$

onde $v$ é a velocidade (m/s), $a$ a aceleração (m/s²), $g = 9.81$ m/s², $\theta$ o ângulo de inclinação da via.

O VSP é mapeado para taxas de emissão (g/s) via tabelas de modos operacionais (MOVES MOD).

Para o modo MESO, usar o modelo COPERT simplificado baseado em velocidade média do link.

#### 4.6.2 Métricas Produzidas

- CO₂ (g/km), NOx (g/km), PM2.5 (μg/km) por veículo e por link
- Consumo de combustível (L/100km)
- Índice de congestionamento por link e por corredor
- Tempo médio de viagem e atraso médio (compatível com SUMO tripinfo)

#### 4.6.3 Sensibilidade à Granularidade

Experimento central: simular o mesmo cenário em três configurações — (i) 100% MESO, (ii) 100% MICRO, (iii) Híbrido (MICRO apenas em corredores de alta emissão). Comparar as estimativas de emissão e o tempo de execução.

**Hipótese:** o modo Híbrido produz estimativas de emissão estatisticamente indistinguíveis do modo 100% MICRO nas regiões de interesse, com fração do custo computacional.

### 4.7 Estudos de Caso

#### 4.7.1 São Paulo — Corredor Paulista

- **Rede:** trecho da Avenida Paulista (2.8 km, 4 faixas) + vias arteriais adjacentes
- **Período:** hora de pico matinal (7h–9h) de um dia útil típico
- **Dados:** contagens de tráfego do CET-SP, rotas GTFS para SPTrans (ônibus)
- **Configuração HTC:** Av. Paulista em modo MICRO; restante em MESO
- **Métricas:** tempo de viagem, atraso, emissões CO₂ e PM2.5 por corredor

#### 4.7.2 Toulouse — Rocade e Centre-Ville

- **Rede:** anel viário (*Rocade*) + acesso ao centro histórico
- **Período:** pico vespertino (17h–19h) de dia útil
- **Dados:** Enquête Ménages Déplacements (EMD) Toulouse Métropole; loops de contagem Tisséo
- **Configuração HTC:** interseções semaforizadas do centro em modo MICRO; Rocade em MESO
- **Métricas:** tempo de viagem, velocidade média por segmento, emissões NOx (comparação com linhas de ônibus elétrico vs. diesel)

---

## 5. Resultados Iniciais

> **Quais experimentos apresentar na qualificação:** Os resultados iniciais devem demonstrar *prova de conceito* de cada objetivo, não resultados finais. O objetivo é mostrar que a abordagem é viável e que o sistema está operacional.

### 5.1 Validação do Motor Híbrido vs. SUMO

**Experimento:** Simular um cenário de via única (1 link de 500m, 3 faixas, 100 veículos) em modo 100% MICRO no HTC e no SUMO com configuração equivalente (Krauss + MOBIL, mesmos parâmetros).

**Métricas de comparação:**
- Distribuição de velocidades ao longo do link (KDE)
- Tempo médio de travessia
- Distribuição de headways na saída
- Número de trocas de faixa

**Resultado esperado:** convergência estatística entre HTC e SUMO (diferença < 5% nas métricas médias), validando a implementação do Krauss e MOBIL.

**Ferramenta:** pipeline `compare_sumo_htc_results.py` já implementado (ver `docs/HYBRID_SUMO_VALIDATION_PIPELINE.md`).

### 5.2 Validação da Transição MESO ↔ MICRO

**Experimento:** Cenário de 3 links em série: MESO(500m) → MICRO(300m) → MESO(500m), com fluxo contínuo de 500 veículos/hora.

**Métricas:**
- Conservação de fluxo (veículos/hora entrando = saindo)
- Ausência de "teletransporte" (nenhum veículo salta posições de forma não-física)
- Tempo de travessia do trecho híbrido vs. MESO puro vs. MICRO puro

**Resultado esperado:** o fluxo é conservado; o tempo de travessia híbrido está entre o MESO (menos detalhado) e o MICRO puro.

### 5.3 Escalabilidade: N Veículos × Tempo de Execução

**Experimento:** Simular a rede de São Paulo (∼30.000 links, ∼400.000 veículos/dia) em 3 configurações:
- 100% MESO (baseline)
- 1% dos links em MICRO (corredores principais)
- 5% dos links em MICRO

**Métricas:**
- Tempo de execução (wallclock) para simulação de 1h de tráfego
- Uso de memória por nó de cluster
- Speedup linear com adição de nós (teste de escalabilidade horizontal)

**Resultado esperado:** 1% MICRO adiciona < 10% de overhead computacional ao MESO puro.

### 5.4 Resultados Preliminares de Emissões

**Experimento:** Corredor Paulista — comparação de estimativas de CO₂ entre:
- Configuração MESO puro (velocidade média)
- Configuração MICRO (VSP instante a instante)
- Dados de referência (CETESB / inventário de emissões SP)

**Resultado esperado:** MESO subestima emissões em hora de pico em ∼15–25% em relação ao MICRO, confirmando a hipótese de que ciclos de parada-e-arranca não são capturados no modelo agregado.

---

## 6. Conclusões e Trabalhos Futuros

### 6.1 Síntese Preliminar

O HTC demonstra, nos resultados iniciais, a viabilidade de um simulador híbrido distribuído baseado em atores como plataforma de pesquisa para mobilidade urbana. A abordagem de hibridização por link, sem zonas de transição explícitas, simplifica o modelo sem sacrificar a fidelidade nas regiões de interesse.

### 6.2 Trabalhos Futuros (Escopo da Tese)

- Completar integração SimEDaPE e avaliar fator de aceleração em cenários reais
- Validar métricas de emissão contra dados observados (CETESB, Toulouse Métropole)
- Explorar modo MICRO para pedestres e interações veículo-pedestre
- Investigar atualização dinâmica de configuração de links (MESO ↔ MICRO em tempo de execução, baseado em densidade detectada)
- Publicar o HTC como plataforma open-source com documentação completa e exemplos reproduzíveis

---

## 7. Cronograma de Execução

| Período | Atividade | Objetivo Associado |
|---------|-----------|-------------------|
| **Jan–Mar 2026** | Motor híbrido: implementação completa e testes de integração | Obj. 1 |
| **Abr–Jun 2026** | Validação HTC vs. SUMO (pipeline automatizado); artigo de validação | Obj. 1 |
| **Jul–Sep 2026** | Formalização da linguagem de definição de cenários; conversores OSM/SUMO | Obj. 2 |
| **Out–Dez 2026** | Integração SimEDaPE: instrumentação e mecanismo de lookup | Obj. 3 |
| **Jan–Mar 2027** | Avaliação SimEDaPE em cenários "what-if" (SP + Toulouse); artigo | Obj. 3 |
| **Abr–Jun 2027** | Modelagem de emissões (VSP + COPERT); integração no ReportManager | Obj. 4 |
| **Jul–Sep 2027** | Estudo de caso São Paulo: coleta de dados, simulação, análise | Obj. 4 |
| **Out–Dez 2027** | Estudo de caso Toulouse: coleta de dados, simulação, análise | Obj. 4 |
| **Jan–Mar 2028** | Escrita da tese; revisão dos artigos | — |
| **Abr–Jun 2028** | Defesa da tese | — |

**Publicações planejadas:**
1. Artigo de sistema: "HTC — A Distributed Actor-Based Hybrid Traffic Simulator" (SIMULATION ou J. Parallel Distrib. Comput.)
2. Artigo de validação: "Micro-Meso Hybrid Simulation: Validation Against SUMO" (IEEE ITSC ou TRB)
3. Artigo SimEDaPE-HTC: "Accelerating What-If Traffic Simulations for Digital Twins" (ACM/IEEE SmartComp ou ICDCS)
4. Artigo de emissões: "Hybrid Simulation Granularity Effects on Urban Emission Estimates" (Transportation Research Part D)

---

## Referências Bibliográficas (Lista Sugerida)

### Simulação de Tráfego
- Barceló, J. (Ed.). (2010). *Fundamentals of Traffic Simulation*. Springer.
- Hoogendoorn, S. P., & Bovy, P. H. (2001). State-of-the-art of vehicular traffic flow modelling. *Proceedings of the Institution of Mechanical Engineers, Part I*, 215(4), 283–303.
- Lopez, P. A., et al. (2018). Microscopic Traffic Simulation using SUMO. *IEEE ITSC*.

### Modelos Microscópicos
- Krauss, S. (1998). *Microscopic Modeling of Traffic Flow: Investigation of Collision Free Vehicle Dynamics*. DLR.
- Treiber, M., Hennecke, A., & Helbing, D. (2000). Congested traffic states in empirical observations and microscopic simulations. *Physical Review E*, 62(2), 1805.
- Kesting, A., Treiber, M., & Helbing, D. (2007). General lane-changing model MOBIL for car-following models. *Transportation Research Record*, 1999(1), 86–94.
- Gipps, P. G. (1981). A behavioural car-following model for computer simulation. *Transportation Research Part B*, 15(2), 105–111.

### Modelos Híbridos
- Bourrel, E., & Lesort, J. B. (2003). Mixing microscopic and macroscopic representations of traffic flow. *Transportation Research Record*, 1852(1), 193–200.
- Burghout, W. (2004). *Hybrid microscopic-mesoscopic traffic simulation* (Doctoral thesis). KTH Royal Institute of Technology.
- Lu, L., et al. (2015). Hybrid micro-mesoscopic traffic simulation with dynamic coupling regions. *Transportation Research Part C*, 60, 256–271.

### Paradigma de Atores e PDES
- Hewitt, C. (1973). A universal modular ACTOR formalism for artificial intelligence. *IJCAI*.
- Lamport, L. (1978). Time, clocks, and the ordering of events in a distributed system. *Communications of the ACM*, 21(7), 558–565.
- Fujimoto, R. M. (2000). *Parallel and Distributed Simulation Systems*. Wiley-Interscience.

### Digital Twins
- Grieves, M. (2014). Digital twin: Manufacturing excellence through virtual factory replication. *White Paper*.
- Tao, F., et al. (2018). Digital twin-driven product lifecycle management. *IEEE Transactions on Industrial Informatics*, 14(10), 4405–4414.

### Emissões
- EPA (2014). *Motor Vehicle Emission Simulator (MOVES) User Guide*.
- Jiménez-Palacios, J. L. (1999). *Understanding and quantifying motor vehicle emissions with vehicle specific power and TILDAS remote sensing*. MIT PhD Thesis.

### Design Science Research
- Hevner, A. R., et al. (2004). Design science in information systems research. *MIS Quarterly*, 28(1), 75–105.

---

*Este documento é um guia vivo para a proposta de qualificação. Seções marcadas com "expandir" devem ser completadas com publicações específicas do grupo de pesquisa e dados de experimentos em andamento.*
