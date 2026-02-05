# Person-Centric Input Model

## Overview

The Hyperbolic Time Chamber simulator has been refactored from **vehicle-centric** to **person-centric** (agent-based) model.

### Key Changes

1. **Person Actor** is the primary agent
   - Persists throughout the simulation day
   - Manages daily schedule (activities)
   - Makes mode choices for trips
   - Activates private vehicles as needed

2. **Private Vehicles** (Car, Bicycle, Motorcycle) are now **Assets**
   - Passive (Parked) by default
   - Activated by Person via `StartTrip` message
   - Configured with person's driving characteristics
   - Report back with `TripCompleted` when journey finishes

3. **Architecture Flow**
   ```
   Person -> (decides to travel) -> StartTrip -> Vehicle (parked)
   Vehicle (active) -> (performs physics on links) -> TripCompleted -> Person
   Person -> (advances to next activity)
   ```

---

## JSON Input Format

### Person Actor Input

```json
{
  "id": "htcaid:person;person_001",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;home_node_1",
          "endTime": "28800",
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;work_node_5",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;car_001",
              "classType": "hybrid.actor.Car"
            },
            "driverAttributes": {
              "aggressiveness": 0.6,
              "maxSpeedFactor": 1.1,
              "reactionTime": 0.9,
              "minGapFactor": 0.8
            }
          }
        },
        {
          "sequence": 2,
          "activityType": "Shopping",
          "nodeId": "htcaid:node;mall_node_3",
          "endTime": "68400",
          "arrivalLogistics": {
            "mode": "bicycle",
            "vehicle": {
              "id": "htcaid:bicycle;bike_001",
              "classType": "hybrid.actor.Bicycle"
            },
            "driverAttributes": {
              "aggressiveness": 0.3,
              "maxSpeedFactor": 0.9,
              "reactionTime": 1.2,
              "minGapFactor": 1.0
            }
          }
        },
        {
          "sequence": 3,
          "activityType": "Home",
          "nodeId": "htcaid:node;home_node_1",
          "endTime": "79200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;car_001",
              "classType": "hybrid.actor.Car"
            },
            "driverAttributes": {
              "aggressiveness": 0.5,
              "maxSpeedFactor": 1.0,
              "reactionTime": 1.0,
              "minGapFactor": 1.0
            }
          }
        }
      ],
      "currentActivityIndex": 0,
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;car_001",
          "classType": "hybrid.actor.Car"
        },
        "bicycle": {
          "id": "htcaid:bicycle;bike_001",
          "classType": "hybrid.actor.Bicycle"
        }
      },
      "currentTripVehicleId": null,
      "currentTripStartTick": null,
      "totalDistanceTraveled": 0.0,
      "completedTrips": 0
    }
  },
  "dependencies": {
    "car": {
      "id": "htcaid:car;car_001",
      "classType": "hybrid.actor.Car"
    },
    "bicycle": {
      "id": "htcaid:bicycle;bike_001",
      "classType": "hybrid.actor.Bicycle"
    }
  }
}
```

### Private Vehicle Input (Car)

**Important:** Vehicles start in `Parked` state. They do NOT have origin/destination initially.

```json
{
  "id": "htcaid:car;car_001",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "dataType": "model.hybrid.entity.state.CarState",
    "content": {
      "status": "Parked",
      "origin": null,
      "destination": null,
      "startTick": 0,
      "distance": 0.0,
      "eventCount": 0,
      "currentSimulationMode": "MESO",
      "microState": null,
      "bestRoute": null,
      "currentPath": null
    }
  },
  "dependencies": {}
}
```

### Private Vehicle Input (Bicycle)

```json
{
  "id": "htcaid:bicycle;bike_001",
  "typeActor": "hybrid.actor.Bicycle",
  "data": {
    "dataType": "model.hybrid.entity.state.BicycleState",
    "content": {
      "status": "Parked",
      "origin": null,
      "destination": null,
      "startTick": 0,
      "distance": 0.0,
      "currentSimulationMode": "MESO",
      "microState": null,
      "bestRoute": null,
      "currentPath": null
    }
  },
  "dependencies": {}
}
```

### Private Vehicle Input (Motorcycle)

```json
{
  "id": "htcaid:motorcycle;moto_001",
  "typeActor": "hybrid.actor.Motorcycle",
  "data": {
    "dataType": "model.hybrid.entity.state.MotorcycleState",
    "content": {
      "status": "Parked",
      "origin": null,
      "destination": null,
      "startTick": 0,
      "distance": 0.0,
      "currentSimulationMode": "MESO",
      "microState": null,
      "bestRoute": null,
      "currentPath": null
    }
  },
  "dependencies": {}
}
```

---

## Data Structure Reference

### Activity

```scala
case class Activity(
  sequence: Int,              // Order in schedule (0-based)
  activityType: String,       // "Home", "Work", "School", "Shopping", etc.
  nodeId: String,             // Location node ID
  endTime: String,            // Tick when activity ends (or "HH:MM" format)
  arrivalLogistics: Option[ArrivalLogistics]  // How to arrive (None for first activity)
)
```

### ArrivalLogistics

```scala
case class ArrivalLogistics(
  mode: String,                    // "car", "bicycle", "motorcycle", "walk", "transit"
  vehicle: Option[Identify],       // Required for private vehicles (contains id + classType)
  driverAttributes: DriverAttributes
)
```

**Note:** `vehicle` must be an `Identify` object containing both `id` and `classType` to enable proper distributed message routing between Person and Vehicle actors.

### DriverAttributes

```scala
case class DriverAttributes(
  aggressiveness: Double = 0.5,      // [0.0 - 1.0] How aggressive the driver is
  maxSpeedFactor: Double = 1.0,      // [0.5 - 1.5] Speed limit adherence multiplier
  reactionTime: Double = 1.0,        // [0.5 - 2.0] Reaction time in seconds
  minGapFactor: Double = 1.0         // [0.5 - 2.0] Minimum safe gap multiplier
)
```

**Effects of DriverAttributes:**
- `aggressiveness`: Higher values increase acceleration, reduce gaps, enable lane filtering (motorcycles)
- `maxSpeedFactor`: Multiplies desired velocity (1.1 = 10% over speed limit)
- `reactionTime`: Lower values = faster response to leader vehicle changes
- `minGapFactor`: Lower values = accepts smaller gaps (more aggressive following)

---

## Example Scenarios

### Scenario 1: Commuter with Car

Person drives to work, parks, then drives home.

```json
{
  "id": "htcaid:person;commuter_1",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "dataType": "model.hybrid.entity.state.PersonState",
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;residential_zone_a",
          "endTime": "25200",
          "arrivalLogistics": null
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;office_district_b",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;sedan_001",
              "classType": "hybrid.actor.Car"
            },
            "driverAttributes": {
              "aggressiveness": 0.7,
              "maxSpeedFactor": 1.15,
              "reactionTime": 0.8,
              "minGapFactor": 0.7
            }
          }
        },
        {
          "sequence": 2,
          "activityType": "Home",
          "nodeId": "htcaid:node;residential_zone_a",
          "endTime": "79200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;sedan_001",
              "classType": "hybrid.actor.Car"
            },
            "driverAttributes": {
              "aggressiveness": 0.5,
              "maxSpeedFactor": 1.0,
              "reactionTime": 1.0,
              "minGapFactor": 1.0
            }
          }
        }
      ],
      "currentActivityIndex": 0,
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;sedan_001",
          "classType": "hybrid.actor.Car"
        }
      }
    }
  }
}
```

### Scenario 2: Multi-Modal Traveler

Person uses car for main commute, bicycle for errands.

```json
{
  "dailySchedule": [
    {
      "sequence": 0,
      "activityType": "Home",
      "nodeId": "htcaid:node;home",
      "endTime": "28800"
    },
    {
      "sequence": 1,
      "activityType": "Work",
      "nodeId": "htcaid:node;work",
      "endTime": "57600",
      "arrivalLogistics": {
        "mode": "car",
        "vehicle": {
          "id": "htcaid:car;my_car",
          "classType": "hybrid.actor.Car"
        }
      }
    },
    {
      "sequence": 2,
      "activityType": "Gym",
      "nodeId": "htcaid:node;gym",
      "endTime": "64800",
      "arrivalLogistics": {
        "mode": "bicycle",
        "vehicle": {
          "id": "htcaid:bicycle;my_bike",
          "classType": "hybrid.actor.Bicycle"
        }
      }
    },
    {
      "sequence": 3,
      "activityType": "Home",
      "nodeId": "htcaid:node;home",
      "endTime": "72000",
      "arrivalLogistics": {
        "mode": "bicycle",
        "vehicle": {
          "id": "htcaid:bicycle;my_bike",
          "classType": "hybrid.actor.Bicycle"
        }
      }
    }
  ],
  "ownedVehicles": {
    "car": {
      "id": "htcaid:car;my_car",
      "classType": "hybrid.actor.Car"
    },
    "bicycle": {
      "id": "htcaid:bicycle;my_bike",
      "classType": "hybrid.actor.Bicycle"
    }
  }
}
```

### Scenario 3: Aggressive Motorcycle Rider

Person uses motorcycle with aggressive driving characteristics.

```json
{
  "sequence": 1,
  "activityType": "Meeting",
  "nodeId": "htcaid:node;downtown",
  "endTime": "32400",
  "arrivalLogistics": {
    "mode": "motorcycle",
    "vehicle": {
      "id": "htcaid:motorcycle;sport_bike",
      "classType": "hybrid.actor.Motorcycle"
    },
    "driverAttributes": {
      "aggressiveness": 0.9,
      "maxSpeedFactor": 1.3,
      "reactionTime": 0.7,
      "minGapFactor": 0.6
    }
  }
}
```

---

## Time Format

### Tick-Based (Recommended)

Use tick numbers directly for precise control:

```json
{
  "endTime": "28800"  // Tick 28800
}
```

### Time-Based (Future Feature)

Human-readable time strings:

```json
{
  "endTime": "08:00"  // 8:00 AM
}
```

**Conversion:**
- 1 tick = 1 second (typically)
- "08:00" = 8 * 3600 = 28800 ticks
- "17:00" = 17 * 3600 = 61200 ticks

---

## Simulation Flow

### Person Lifecycle

```
1. Person starts at Activity[0] (Home)
   Status: At Home
   
2. Wait until endTime reached
   
3. Read Activity[1].arrivalLogistics
   Mode: "car"
   VehicleId: "car_001"
   
4. Execute mode choice (currently: use specified mode)
   
5. Send StartTrip to car_001
   StartTripData:
     - personId: "person_001"
     - origin: Activity[0].nodeId
     - destination: Activity[1].nodeId
     - driverAttributes: {...}
     
6. Car activates (Parked -> Start -> Ready -> Moving)
   - Car registers with TimeManager
   - Car requests route
   - Car traverses links (MESO or MICRO)
   - Car reports progress
   
7. Car reaches destination
   Car sends TripCompleted to Person
   Car deactivates (-> Parked)
   
8. Person receives TripCompleted
   Person advances to Activity[1]
   Status: At Work
   
9. Repeat from step 2
```

### Vehicle Lifecycle

```
Initial State: Parked (passive)
  - NOT registered with TimeManager
  - No spontaneous events
  - Waiting for StartTrip
  
Receive StartTrip from Person:
  - Extract driverAttributes
  - Apply to physics (desiredVelocity, reactionTime, etc.)
  - Set origin and destination
  - Status: Parked -> Start
  - Register with TimeManager
  
Active State (Start -> Moving):
  - Request route
  - Enter links (MESO or MICRO)
  - Perform physics (car-following, lane changes)
  - Report progress
  
Reach Destination:
  - Send TripCompleted to Person
  - Deregister from TimeManager
  - Status: -> Parked
  - Return to passive state
```

---

## Migration Guide

### Old Format (Vehicle-Centric)

```json
{
  "id": "htcaid:car;trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "content": {
      "startTick": 154,
      "origin": "htcaid:node;60609822",
      "destination": "htcaid:node;4922987596",
      "status": "Start"
    }
  }
}
```

### New Format (Person-Centric)

```json
// Person
{
  "id": "htcaid:person;person_trip_1",
  "typeActor": "hybrid.actor.Person",
  "data": {
    "content": {
      "dailySchedule": [
        {
          "sequence": 0,
          "activityType": "Home",
          "nodeId": "htcaid:node;60609822",
          "endTime": "154"
        },
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "htcaid:node;4922987596",
          "endTime": "61200",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;car_person_trip_1",
              "classType": "hybrid.actor.Car"
            }
          }
        }
      ],
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;car_person_trip_1",
          "classType": "hybrid.actor.Car"
        }
      }
    }
  }
}

// Car (Parked)
{
  "id": "htcaid:car;car_person_trip_1",
  "typeActor": "hybrid.actor.Car",
  "data": {
    "content": {
      "status": "Parked",
      "origin": null,
      "destination": null
    }
  }
}
```

---

## Benefits of Person-Centric Model

1. **Realistic Agent Behavior**
   - People make mode choices
   - Daily schedules with activities
   - Vehicle ownership and sharing

2. **Multi-Modal Travel**
   - Person can use different vehicles for different trips
   - Walk, transit, private vehicles in same schedule

3. **Driver Heterogeneity**
   - Same vehicle can have different behaviors with different drivers
   - Aggressive/conservative driving characteristics per person

4. **Trip Chaining**
   - Natural representation of activity chains
   - Home -> Work -> Shopping -> Home

5. **Policy Analysis**
   - Mode choice modeling
   - Activity-based demand
   - Vehicle ownership patterns

---

## Testing

### Minimal Test Case

```json
{
  "persons": [
    {
      "id": "htcaid:person;test_1",
      "dailySchedule": [
        {"sequence": 0, "activityType": "Home", "nodeId": "node_a", "endTime": "0"},
        {
          "sequence": 1,
          "activityType": "Work",
          "nodeId": "node_b",
          "endTime": "1000",
          "arrivalLogistics": {
            "mode": "car",
            "vehicle": {
              "id": "htcaid:car;test_car_1",
              "classType": "hybrid.actor.Car"
            }
          }
        }
      ],
      "ownedVehicles": {
        "car": {
          "id": "htcaid:car;test_car_1",
          "classType": "hybrid.actor.Car"
        }
      }
    }
  ],
  "vehicles": [
    {
      "id": "htcaid:car;test_car_1",
      "status": "Parked"
    }
  ]
}
```

---

## Advanced Features

### Custom Mode Choice

Future enhancement: utility-based mode choice.

```scala
def executeModeChoice(logistics: ArrivalLogistics): String = {
  // Calculate utilities
  val carUtility = calculateUtility(mode = "car", travelTime, cost, comfort)
  val bikeUtility = calculateUtility(mode = "bicycle", travelTime, cost, comfort)
  
  // Choose mode with highest utility
  chooseModeByUtility(Map("car" -> carUtility, "bicycle" -> bikeUtility))
}
```

### Shared Vehicles

Future: multiple persons sharing vehicles.

```json
{
  "households": [
    {
      "id": "household_1",
      "persons": ["person_1", "person_2"],
      "sharedVehicles": {
        "car": "htcaid:car;family_car"
      }
    }
  ]
}
```

### Ride-Hailing

Future: persons can request ride-hail vehicles.

```json
{
  "arrivalLogistics": {
    "mode": "ridehail",
    "serviceProvider": "uber",
    "vehicleType": "sedan"
  }
}
```

---

## Contact & Support

For questions or issues with the person-centric model, refer to:
- [ARCHITECTURE.md](ARCHITECTURE.md) - System design
- [API_REFERENCE.md](API_REFERENCE.md) - Actor APIs
- [CONFIGURATION.md](CONFIGURATION.md) - Configuration options
