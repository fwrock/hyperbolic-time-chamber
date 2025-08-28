# Simulação Microscópica de Tráfego - Documentação

## Visão Geral

O sistema de simulação agora suporta dois modos de operação:

1. **Simulação Mesoscópica** (modo padrão): Modelagem agregada do tráfego usando equações de fluxo
2. **Simulação Microscópica** (novo): Modelagem individual de cada veículo usando modelos IDM e MOBIL

## Componentes Implementados

### 1. Estado Microscópico do Veículo (`MicroVehicleState`)

Armazena informações detalhadas de cada veículo:
- Posição e velocidade precisas
- Parâmetros do modelo IDM (aceleração, desaceleração, velocidade desejada, etc.)
- Parâmetros do modelo MOBIL (polidez, limiar de troca de faixa)
- Estado da troca de faixa

### 2. Modelos de Tráfego (`TrafficModels`)

#### Modelo IDM (Intelligent Driver Model)
- Calcula aceleração longitudinal baseada no veículo líder
- Considera velocidade desejada, gap seguro e tempo de headway
- Produz comportamento realista de car-following

#### Modelo MOBIL (Minimizing Overall Braking Induced by Lane changes)
- Avalia segurança e incentivo para troca de faixa
- Considera impacto nos outros veículos (fator de polidez)
- Previne trocas de faixa perigosas

### 3. LinkActor Microscópico (`TimeSteppedLink`)

Quando `simulationType == "micro"`:
- Executa loop de sub-ticks (padrão: 10 sub-ticks por tick global)
- Coleta intenções de todos os veículos
- Resolve conflitos de troca de faixa
- Atualiza posições usando equações de movimento

### 4. CarActor Microscópico (`TimeSteppedCar`)

- Recebe contexto microscópico do LinkActor
- Calcula intenções usando IDM e MOBIL
- Responde com aceleração desejada e troca de faixa
- Mantém parâmetros de personalidade do motorista

## Protocolo de Comunicação

### Durante Sub-tick:

1. **LinkActor → CarActor**: `ProvideMicroContext`
   - Estado dos veículos vizinhos
   - Informações do link
   - Número do sub-tick

2. **CarActor → LinkActor**: `MyMicroIntention`
   - Aceleração desejada (IDM)
   - Faixa de destino desejada (MOBIL)

3. **LinkActor**: Resolve conflitos e atualiza estados

## Configuração

### LinkState
```scala
case class LinkState(
  // ... parâmetros existentes ...
  simulationType: String = "meso", // "meso" ou "micro"
  globalTickDuration: Double = 1.0, // duração do tick em segundos
  microTimestep: Double = 0.1       // intervalo dos sub-ticks
)
```

### CarState  
```scala
case class CarState(
  // ... parâmetros existentes ...
  
  // Parâmetros IDM
  maxAcceleration: Double = 2.0,
  desiredDeceleration: Double = 3.0,
  desiredSpeed: Double = 30.0,
  timeHeadway: Double = 1.5,
  minimumGap: Double = 2.0,
  
  // Parâmetros MOBIL
  politenessFactor: Double = 0.2,
  laneChangeThreshold: Double = 0.1,
  maxSafeDeceleration: Double = 4.0,
  
  // Personalidade
  aggressiveness: Double = 0.5
)
```

## Uso

### 1. Configurar Links para Simulação Microscópica

```scala
val microLink = LinkState(
  // ... parâmetros básicos ...
  simulationType = "micro",
  globalTickDuration = 1.0,  // 1 segundo por tick
  microTimestep = 0.1        // 10 sub-ticks por tick
)
```

### 2. Configurar Veículos com Personalidades

```scala
val aggressiveCar = CarState(
  // ... parâmetros básicos ...
  desiredSpeed = 35.0,       // Mais rápido
  timeHeadway = 1.0,         // Menor distância
  politenessFactor = 0.1,    // Menos educado
  aggressiveness = 0.8
)

val conservativeCar = CarState(
  // ... parâmetros básicos ...
  desiredSpeed = 25.0,       // Mais devagar
  timeHeadway = 2.0,         // Maior distância
  politenessFactor = 0.4,    // Mais educado
  aggressiveness = 0.2
)
```

## Parâmetros dos Modelos

### IDM (Intelligent Driver Model)
- `maxAcceleration`: Aceleração máxima (m/s²) - típico: 1.5-3.0
- `desiredDeceleration`: Desaceleração confortável (m/s²) - típico: 2.0-4.0
- `desiredSpeed`: Velocidade desejada (m/s) - típico: 22-36 (80-130 km/h)
- `timeHeadway`: Tempo de headway (s) - típico: 1.0-2.5
- `minimumGap`: Gap mínimo no trânsito parado (m) - típico: 1.5-3.0

### MOBIL (Minimizing Overall Braking)
- `politenessFactor`: Consideração pelos outros (0-1) - típico: 0.1-0.5
- `laneChangeThreshold`: Vantagem mínima para trocar (m/s²) - típico: 0.05-0.2
- `maxSafeDeceleration`: Desaceleração máxima segura (m/s²) - típico: 3.0-5.0

## Detecção de Modo

O modo microscópico é ativado quando:
1. `LinkState.simulationType == "micro"`
2. Ou baseado em padrões de ID do link
3. Ou baseado em densidade de tráfego

## Performance

### Considerações
- Simulação microscópica é computacionalmente mais intensiva
- Use apenas em áreas críticas (interseções, gargalos)
- Combine com simulação mesoscópica para otimizar performance

### Métricas
- Tempo de execução: ~10x mais lento que mesoscópico
- Memória: Proporcional ao número de veículos
- Precisão: Significativamente maior para comportamentos complexos

## Exemplos de Uso

### Estudo de Interseção
```scala
// Links de aproximação em modo microscópico
val approachLinks = List("intersection_north", "intersection_south").map { id =>
  LinkState(id = id, simulationType = "micro", lanes = 3)
}

// Links distantes em modo mesoscópico  
val distantLinks = List("highway_segment_1", "highway_segment_2").map { id =>
  LinkState(id = id, simulationType = "meso")
}
```

### Análise de Troca de Faixa
```scala
val highway = LinkState(
  id = "highway_bottleneck",
  simulationType = "micro",
  lanes = 4,
  length = 1000.0,
  microTimestep = 0.05  // Alta resolução temporal
)
```

## Validação

### Métricas de Validação
- Diagramas fundamentais (fluxo-densidade-velocidade)
- Tempos de viagem médios
- Distribuição de headways
- Frequência de trocas de faixa

### Comparação com Dados Reais
- Dados de detectores em loop
- Vídeos de tráfego
- Trajetórias de veículos individuais
