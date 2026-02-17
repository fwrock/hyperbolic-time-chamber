# 🎯 Correção: Modelo Person-Centric

## 🚨 Problema Identificado

Os arquivos gerados anteriormente tinham uma **falha fundamental** no modelo person-centric:

### ❌ ERRADO (Modelo Anterior)

#### Carros Independentes
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "startTick": 154,                    // ❌ Carro tem startTick
      "scheduleOnTimeManager": true,       // ❌ Carro se auto-agenda
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596"
    }
  }
}
```

#### Pessoas Passivas
```json
{
  "id": "htcaid:person;person_0",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [...],
      "ownedVehicles": {},                 // ❌ Pessoa não possui veículos
      "scheduleOnTimeManager": false       // ❌ Pessoa não é agendada
    }
  },
  "dependencies": {}                       // ❌ Sem dependências
}
```

**Resultado:** Carros ativam sozinhos, pessoas são ignoradas ❌

---

## ✅ CORRETO (Modelo Person-Centric)

### Pessoas Ativas (Controlam a Simulação)
```json
{
  "id": "htcaid:person;person_0",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;60609822",
          "endTime": "11218",              // ✅ Quando pessoa sai de casa
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;4922987596",
          "endTime": "33315",
          "arrivalLogistics": {
            "mode": "car",                 // ✅ Pessoa usa carro
            "vehicle": "htcaid:car;trip_1", // ✅ Referência ao veículo
            "driverAttributes": {...}
          }
        },
        {
          "sequence": 2,
          "activityType": "Home",
          "nodeId": "htcaid:node;60609822",
          "endTime": "86400",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": "htcaid:car;trip_1",
            "driverAttributes": {...}
          }
        }
      ],
      "ownedVehicles": {
        "car": "htcaid:car;trip_1"       // ✅ Pessoa possui este carro
      },
      "scheduleOnTimeManager": true,     // ✅ Pessoa é agendada!
      "startTick": 11218                 // ✅ Quando pessoa inicia (deixa Home)
    }
  },
  "dependencies": {
    "car": {                             // ✅ Pessoa depende do carro
      "id": "htcaid:car;trip_1",
      "classType": "hybrid.actor.Car"
    }
  }
}
```

### Carros Passivos (Recursos Requisitados)
```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      // ✅ SEM startTick - não se auto-agenda
      // ✅ SEM scheduleOnTimeManager
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "ownedBy": "htcaid:person;person_0",  // ✅ Referência ao dono
      "actorType": "Car",
      "size": 4.5,
      "currentSimulationMode": "MESO",
      "status": "Start"
    }
  }
}
```

**Resultado:** Pessoas controlam quando e como viajar ✅

---

## 🔄 Fluxo de Execução Correto

### 1. Inicialização (Tick 0)
```
SimulationManager cria atores:
├─ Nodes (infraestrutura)
├─ Links (infraestrutura)
├─ Cars (PASSIVOS, aguardando pessoas)
└─ Persons (ATIVOS, com scheduleOnTimeManager)
```

### 2. TimeManager Registra Pessoas
```
TimeManager.receive(RegisterData):
  Para cada Person com scheduleOnTimeManager:
    ├─ Agenda Person no tick = startTick (endTime da atividade 0)
    └─ Person será ativado quando sair de casa
```

### 3. Pessoa Ativa no Tick Correto
```
Tick 11218 (endTime da atividade Home):
Person recebe InvokedData do TimeManager:
  ├─ Lê próxima atividade (Work)
  ├─ Verifica arrivalLogistics.mode = "car"
  ├─ Envia ActivateVehicleData(vehicle: "htcaid:car;trip_1")
  ├─ Carro recebe ativação e inicia viagem
  └─ Pessoa agenda próxima atividade (endTime = 33315)
```

### 4. Carro Executa Viagem
```
Car.receive(ActivateVehicleData):
  ├─ Calcula rota (origin → destination)
  ├─ Entra em Link
  ├─ Viaja seguindo modelo MESO ou MICRO
  ├─ Ao chegar, notifica Person
  └─ Person atualiza currentActivityIndex
```

---

## 📊 Estatísticas do Modelo Correto

### Distribuição de Veículos
- **60%** dos carros: **Owned by Persons** (sem `scheduleOnTimeManager`)
- **40%** dos carros: **Autônomos** (taxis, delivery, com `scheduleOnTimeManager`)

### Distribuição de Pessoas
- **60%** das pessoas: **Com carro próprio** (mode: "car")
- **40%** das pessoas: **Sem carro** (mode: "bus", "subway", "walk", "mixed")

---

## 🛠️ Implementação

### Script Corrigido: `migrate_to_hybrid.py`

#### 1. Geração de Pessoas com Ownership
```python
def _generate_persons(self):
    """Generate Person actors - PERSON-CENTRIC MODEL"""
    
    # Decide which cars will be owned
    car_ownership_ratio = 0.6  # 60% owned, 40% autonomous
    cars_for_persons = random.sample(all_cars, num_to_assign)
    
    # Generate persons with cars
    for person_idx, car_trip in enumerate(cars_for_persons):
        vehicle_id = car_trip['vehicle_id']
        
        person = {
            "id": f"htcaid:person;person_{person_idx}",
            "data": {
                "content": {
                    "ownedVehicles": {"car": vehicle_id},  # ✅
                    "scheduleOnTimeManager": True,         # ✅
                    "startTick": work_start_tick,          # ✅
                    "dailySchedule": [...]
                }
            },
            "dependencies": {
                "car": {
                    "id": vehicle_id,                      # ✅
                    "classType": "hybrid.actor.Car"
                }
            }
        }
        
        # Mark vehicle as owned
        self._vehicle_ownership[vehicle_id] = person_id
```

#### 2. Vinculação Pessoas ↔ Veículos
```python
def _link_persons_to_vehicles(self):
    """Link persons to their owned vehicles"""
    
    for car in self.vehicles[VehicleTypeEnum.CAR]:
        car_id = car['id']
        content = car['data']['content']
        
        if car_id in owned_vehicle_ids:
            # Car owned by person: make it PASSIVE
            del content['startTick']              # ✅ Remove
            del content['scheduleOnTimeManager']  # ✅ Remove
            content['ownedBy'] = owner_person_id  # ✅ Add reference
        else:
            # Autonomous vehicle: keep scheduling
            content['scheduleOnTimeManager'] = True
            content['startTick'] = 0
```

---

## ✅ Resultados Esperados

### Arquivos Gerados

#### `persons_1.json` ✅
```json
[
  {
    "id": "htcaid:person;person_0",
    "ownedVehicles": {"car": "htcaid:car;trip_1"},
    "scheduleOnTimeManager": true,
    "startTick": 11218,
    "dependencies": {"car": {...}}
  }
]
```

#### `cars_1.json` ✅
```json
[
  {
    "id": "htcaid:car;trip_1",
    // SEM startTick
    // SEM scheduleOnTimeManager
    "ownedBy": "htcaid:person;person_0"
  },
  {
    "id": "htcaid:car;taxi_1",
    "scheduleOnTimeManager": true,  // Autônomo
    "startTick": 0
  }
]
```

---

## 🎯 Como Usar

### 1. Executar Script Corrigido
```bash
cd /home/dean/PhD/hyperbolic-time-chamber
source .venv/bin/activate
python scripts/migrate_to_hybrid.py \
    --input simulations/input/mobility_scenario \
    --output scripts/output/person_centric_scenario \
    --generate-persons
```

### 2. Verificar Saída
```bash
# Verificar que pessoas têm ownedVehicles e scheduleOnTimeManager
jq '.[] | select(.ownedVehicles.car != null) | {id, ownedVehicles, scheduleOnTimeManager}' \
    scripts/output/person_centric_scenario/data/persons_1.json | head -20

# Verificar que carros owned NÃO têm scheduleOnTimeManager
jq '.[] | select(.data.content.ownedBy != null) | {id, ownedBy, scheduleOnTimeManager}' \
    scripts/output/person_centric_scenario/data/cars_1.json | head -20
```

---

## 📚 Referências

- **Copilot Instructions:** `.github/copilot-instructions.md`
- **Person-Centric Model:** `docs/PERSON_CENTRIC_MODEL.md`
- **Quick Reference:** `docs/PERSON_CENTRIC_QUICK_REFERENCE.md`

---

## 💡 Conclusão

O modelo **person-centric** agora está **corretamente implementado**:
- ✅ **Pessoas** são agentes ativos agendados no TimeManager
- ✅ **Carros** são recursos passivos requisitados por pessoas
- ✅ **Ownership** explícito via `ownedVehicles` e `dependencies`
- ✅ **Controle** de quando viajar está com a Pessoa, não com o veículo

**Próximos passos:**
1. Testar com cenário real
2. Validar comportamento em runtime
3. Verificar que Persons ativam veículos corretamente
