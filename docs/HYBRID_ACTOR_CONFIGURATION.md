# Configuração de Atores Híbridos - Guia de Uso

## 📋 Overview

Os atores híbridos (`HybridCar`, `HybridBus`, `HybridBicycle`, `HybridMotorcycle`) são **automaticamente reconhecidos** pelo sistema através do mecanismo de reflexão existente em `ActorCreatorUtil.createShardRegion()`.

**Não é necessário modificar o factory** - o sistema usa `Class.forName()` para instanciar dinamicamente qualquer classe especificada no campo `typeActor` do JSON.

---

## 🔧 Como Usar os Atores Híbridos

### 1. Configuração de Atores em JSON

Para usar os atores híbridos, basta especificar o caminho completo da classe no campo `typeActor`:

#### HybridCar (Carro Híbrido)

```json
{
  "id": "htcaid:car;hybrid_car_1",
  "typeActor": "hybrid.actor.HybridCar",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridCarState",
    "content": {
      "startTick": 10,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "actorType": "CAR",
      "size": 1.0,
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;60609822",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;4922987596",
      "classType": "mobility.actor.Node"
    }
  }
}
```

#### HybridBus (Ônibus Híbrido)

```json
{
  "id": "htcaid:bus;hybrid_bus_1",
  "typeActor": "hybrid.actor.HybridBus",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridBusState",
    "content": {
      "startTick": 5,
      "label": "BUS_LINE_101",
      "capacity": 80,
      "numberOfPorts": 2,
      "origin": "htcaid:node;bus_terminal",
      "destination": "htcaid:node;downtown_station",
      "busStops": {
        "stop_1": "htcaid:node;stop_1",
        "stop_2": "htcaid:node;stop_2",
        "stop_3": "htcaid:node;stop_3"
      },
      "people": {},
      "actorType": "BUS",
      "size": 12.0,
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;bus_terminal",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;downtown_station",
      "classType": "mobility.actor.Node"
    }
  }
}
```

#### HybridBicycle (Bicicleta - NOVO)

```json
{
  "id": "htcaid:bicycle;bike_1",
  "typeActor": "hybrid.actor.HybridBicycle",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridBicycleState",
    "content": {
      "startTick": 15,
      "origin": "htcaid:node;residential_area",
      "destination": "htcaid:node;park",
      "actorType": "BICYCLE",
      "size": 2.0,
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;residential_area",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;park",
      "classType": "mobility.actor.Node"
    }
  }
}
```

#### HybridMotorcycle (Motocicleta - NOVO)

```json
{
  "id": "htcaid:motorcycle;moto_1",
  "typeActor": "hybrid.actor.HybridMotorcycle",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridMotorcycleState",
    "content": {
      "startTick": 8,
      "origin": "htcaid:node;suburb",
      "destination": "htcaid:node;city_center",
      "actorType": "MOTORCYCLE",
      "size": 2.5,
      "currentSimulationMode": "MESO",
      "microState": null
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;suburb",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;city_center",
      "classType": "mobility.actor.Node"
    }
  }
}
```

---

### 2. Configuração de Links Híbridos

Links determinam o modo de simulação (MESO ou MICRO):

#### Link MESO (Mesoscópico)

```json
{
  "id": "htcaid:link;suburb_road",
  "typeActor": "hybrid.actor.HybridLink",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from": "htcaid:node;suburb_01",
      "to": "htcaid:node;suburb_02",
      "length": 1000.0,
      "lanes": 2,
      "speedLimit": 60.0,
      "freeSpeed": 60.0,
      "capacity": 1500,
      "congestionFactor": 1.0,
      "currentSpeed": 60.0,
      
      "simulationMode": "MESO"
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;suburb_01",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;suburb_02",
      "classType": "mobility.actor.Node"
    }
  }
}
```

#### Link MICRO (Microscópico)

```json
{
  "id": "htcaid:link;downtown_main",
  "typeActor": "hybrid.actor.HybridLink",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from": "htcaid:node;intersection_01",
      "to": "htcaid:node;intersection_02",
      "length": 500.0,
      "lanes": 3,
      "speedLimit": 50.0,
      "freeSpeed": 50.0,
      "capacity": 2000,
      "congestionFactor": 1.0,
      "currentSpeed": 50.0,
      
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      
      "laneConfigurations": [
        {"laneId": 0, "type": "NORMAL"},
        {"laneId": 1, "type": "NORMAL"},
        {"laneId": 2, "type": "BUS_LANE"}
      ]
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;intersection_01",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;intersection_02",
      "classType": "mobility.actor.Node"
    }
  }
}
```

#### Link MICRO com Ciclovia

```json
{
  "id": "htcaid:link;bike_corridor",
  "typeActor": "hybrid.actor.HybridLink",
  "data": {
    "dataType": "model.hybrid.entity.state.HybridLinkState",
    "content": {
      "from": "htcaid:node;park_entrance",
      "to": "htcaid:node;beach",
      "length": 800.0,
      "lanes": 3,
      "speedLimit": 40.0,
      "freeSpeed": 40.0,
      "capacity": 1200,
      "congestionFactor": 1.0,
      "currentSpeed": 40.0,
      
      "simulationMode": "MICRO",
      "microTimeStep": 0.1,
      "microTicksPerGlobalTick": 10,
      
      "laneConfigurations": [
        {"laneId": 0, "type": "BIKE_LANE"},
        {"laneId": 1, "type": "NORMAL"},
        {"laneId": 2, "type": "NORMAL"}
      ]
    }
  },
  "dependencies": {
    "from_node": {
      "id": "htcaid:node;park_entrance",
      "classType": "mobility.actor.Node"
    },
    "to_node": {
      "id": "htcaid:node;beach",
      "classType": "mobility.actor.Node"
    }
  }
}
```

---

## 🔄 Fluxo de Criação de Atores

### 1. Sistema lê JSON do arquivo de cenário
```
JSON → JsonLoadData → CreateActorsEvent
```

### 2. CreatorLoadData processa batch
```
CreateActorsEvent → CreatorLoadData → handleCreateActors()
  └─> batchesToCreate.put(batchId, actors)
  └─> self ! StartCreationEvent(batchId)
```

### 3. Criação em chunks (1000 atores por vez)
```
handleStartCreation() → handleProcessNextCreateChunk()
  └─> Para cada ator no chunk:
      ├─> ActorCreatorUtil.createShardRegion(
      │       actorClassName = "hybrid.actor.HybridCar"
      │   )
      ├─> StringUtil.getModelClassName() adiciona prefixo
      │   → "org.interscity.htc.model.hybrid.actor.HybridCar"
      ├─> Class.forName() carrega a classe
      └─> Props(clazz, Properties(...)) cria instância
```

### 4. Inicialização do ator
```
ShardRegion.StartEntityAck → handleInitialize()
  └─> EntityEnvelopeEvent(InitializeEvent)
  └─> HybridCar recebe InitializeEvent
  └─> Ator pronto para simulação
```

---

## ✅ Verificação de Registro

O sistema **NÃO REQUER** modificações no factory porque:

1. **Reflexão Java**: `Class.forName()` carrega qualquer classe pelo nome completo
2. **Prefixo automático**: `StringUtil.getModelClassName()` adiciona `org.interscity.htc.model.`
3. **Props genérico**: `Props(clazz, Properties(...))` funciona para qualquer ator

### Como o sistema resolve os tipos:

```
JSON typeActor: "hybrid.actor.HybridCar"
  ↓
StringUtil.getModelClassName()
  ↓
"org.interscity.htc.model.hybrid.actor.HybridCar"
  ↓
Class.forName() → carrega HybridCar.class
  ↓
Props(HybridCar.class, properties) → cria instância
  ↓
Ator registrado e pronto
```

---

## 📝 Convenções de Nomenclatura

### typeActor (JSON)
- **Formato:** `<package>.<subpackage>.ActorName`
- **Prefix automático:** `org.interscity.htc.model.` é adicionado
- **Exemplos:**
  - `"mobility.actor.Car"` → `org.interscity.htc.model.mobility.actor.Car`
  - `"hybrid.actor.HybridCar"` → `org.interscity.htc.model.hybrid.actor.HybridCar`
  - `"hybrid.actor.HybridBicycle"` → `org.interscity.htc.model.hybrid.actor.HybridBicycle`

### dataType (JSON)
- **Formato:** `model.<package>.<subpackage>.StateName`
- **Prefix automático:** `org.interscity.htc.` é adicionado
- **Exemplos:**
  - `"model.mobility.entity.state.CarState"`
  - `"model.hybrid.entity.state.HybridCarState"`
  - `"model.hybrid.entity.state.HybridBicycleState"`

---

## 🧪 Exemplo de Cenário Híbrido Completo

```json
{
  "actors": [
    {
      "id": "htcaid:link;suburb_residential",
      "typeActor": "hybrid.actor.HybridLink",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridLinkState",
        "content": {
          "from": "htcaid:node;home",
          "to": "htcaid:node;highway_entrance",
          "length": 2000.0,
          "lanes": 2,
          "speedLimit": 60.0,
          "simulationMode": "MESO"
        }
      }
    },
    {
      "id": "htcaid:link;downtown_avenue",
      "typeActor": "hybrid.actor.HybridLink",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridLinkState",
        "content": {
          "from": "htcaid:node;highway_exit",
          "to": "htcaid:node;downtown",
          "length": 800.0,
          "lanes": 3,
          "speedLimit": 50.0,
          "simulationMode": "MICRO",
          "microTimeStep": 0.1,
          "microTicksPerGlobalTick": 10,
          "laneConfigurations": [
            {"laneId": 0, "type": "BIKE_LANE"},
            {"laneId": 1, "type": "NORMAL"},
            {"laneId": 2, "type": "BUS_LANE"}
          ]
        }
      }
    },
    {
      "id": "htcaid:car;commuter_1",
      "typeActor": "hybrid.actor.HybridCar",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridCarState",
        "content": {
          "startTick": 10,
          "origin": "htcaid:node;home",
          "destination": "htcaid:node;downtown"
        }
      }
    },
    {
      "id": "htcaid:bus;line_101",
      "typeActor": "hybrid.actor.HybridBus",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridBusState",
        "content": {
          "startTick": 5,
          "label": "LINE_101",
          "capacity": 80,
          "origin": "htcaid:node;bus_terminal",
          "destination": "htcaid:node;downtown"
        }
      }
    },
    {
      "id": "htcaid:bicycle;cyclist_1",
      "typeActor": "hybrid.actor.HybridBicycle",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridBicycleState",
        "content": {
          "startTick": 15,
          "origin": "htcaid:node;home",
          "destination": "htcaid:node;park"
        }
      }
    },
    {
      "id": "htcaid:motorcycle;rider_1",
      "typeActor": "hybrid.actor.HybridMotorcycle",
      "data": {
        "dataType": "model.hybrid.entity.state.HybridMotorcycleState",
        "content": {
          "startTick": 8,
          "origin": "htcaid:node;suburb",
          "destination": "htcaid:node;downtown"
        }
      }
    }
  ]
}
```

**Fluxo do cenário:**
1. Car, Bus, Bicycle, Motorcycle iniciam em diferentes ticks
2. Todos atravessam link MESO (suburb) com velocidade agregada
3. Entram no link MICRO (downtown) → transição automática
4. Link MICRO executa sub-ticks com car-following
5. Bicycle usa BIKE_LANE, Bus usa BUS_LANE
6. Motorcycle pode filtrar entre faixas
7. Ao sair do link MICRO → retornam ao modo MESO

---

## ✅ Conclusão

**Status:** ✅ Sistema já suporta atores híbridos automaticamente

**Não é necessário:**
- ❌ Modificar factory
- ❌ Registrar novos tipos manualmente
- ❌ Atualizar código de infraestrutura

**Necessário apenas:**
- ✅ Especificar `typeActor` correto no JSON
- ✅ Especificar `dataType` correto no JSON
- ✅ Garantir que classes existem no classpath

**Próximos passos:**
- Criar cenários de teste (Task 6)
- Validar transições MESO ↔ MICRO
- Performance benchmarks

---

**Atualizado:** Novembro 2025
