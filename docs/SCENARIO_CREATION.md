# 🎬 Scenario Creation Guide

Learn how to create sophisticated traffic simulation scenarios in the Hyperbolic Time Chamber. This guide covers everything from basic scenarios to complex multi-modal transportation systems with realistic behavior patterns.

---

## 🎯 **Scenario Design Principles**

### **🏗️ Structured Approach**
1. **📋 Define Objectives** - What questions will your simulation answer?
2. **🗺️ Design Network** - Create the road network and infrastructure  
3. **🚗 Model Entities** - Define vehicles, signals, and other actors
4. **⏰ Plan Timeline** - Set simulation duration and key events
5. **📊 Configure Outputs** - Specify data collection and analysis
6. **✅ Validate Results** - Ensure realistic and meaningful outcomes

### **🎨 Design Patterns**
- **📍 Geographic Realism** - Base scenarios on real-world locations
- **🕐 Temporal Patterns** - Model realistic time-of-day variations
- **🎯 Behavioral Diversity** - Include different actor types and behaviors
- **📈 Scalable Complexity** - Start simple, add complexity incrementally
- **🔄 Reproducibility** - Use seeds and version control for consistency

---

## 🏙️ **Basic Urban Scenario**

### **🛣️ Simple Intersection**

Let's start with a basic four-way intersection scenario:

```json
{
  "simulation": {
    "name": "Basic Four-Way Intersection",
    "description": "Simple intersection with traffic signals and vehicles",
    "startTick": 0,
    "startRealTime": "2025-01-27T08:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 1800,
    "randomSeed": 42,
    "actorsDataSources": [
      {
        "id": "intersection-controller",
        "classType": "com.htc.traffic.IntersectionActor",
        "creationType": "Simple",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/scenarios/basic_intersection/intersection.json"
          }
        }
      },
      {
        "id": "traffic-signals",
        "classType": "com.htc.traffic.TrafficSignalActor", 
        "creationType": "Simple",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/scenarios/basic_intersection/signals.json"
          }
        }
      },
      {
        "id": "vehicle-fleet",
        "classType": "com.htc.traffic.VehicleActor",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/scenarios/basic_intersection/vehicles.json"
          }
        }
      }
    ]
  }
}
```

### **🚦 Intersection Configuration**
```json
{
  "intersections": [
    {
      "id": "intersection_001",
      "name": "Main & Oak Intersection",
      "position": {
        "x": 0.0,
        "y": 0.0
      },
      "links": {
        "north": "link_north_approach",
        "south": "link_south_approach", 
        "east": "link_east_approach",
        "west": "link_west_approach"
      },
      "geometry": {
        "type": "four_way",
        "radius": 15.0
      },
      "controlType": "signalized"
    }
  ]
}
```

### **🚥 Traffic Signal Configuration**
```json
{
  "signals": [
    {
      "id": "signal_001",
      "intersectionId": "intersection_001",
      "controlType": "fixed_time",
      "cycleTime": 120,
      "offset": 0,
      "phases": [
        {
          "id": "phase_1",
          "duration": 45,
          "yellowTime": 3,
          "allRedTime": 2,
          "movements": [
            {
              "from": "north",
              "to": "south",
              "type": "through"
            },
            {
              "from": "south", 
              "to": "north",
              "type": "through"
            }
          ]
        },
        {
          "id": "phase_2",
          "duration": 45,
          "yellowTime": 3,
          "allRedTime": 2,
          "movements": [
            {
              "from": "east",
              "to": "west", 
              "type": "through"
            },
            {
              "from": "west",
              "to": "east",
              "type": "through"
            }
          ]
        }
      ]
    }
  ]
}
```

### **🚗 Vehicle Fleet Configuration**
```json
{
  "vehicles": [
    {
      "id": "vehicle_001",
      "name": "Commuter Car 1",
      "vehicleType": "passenger_car",
      "startTime": 60,
      "route": {
        "origin": "link_north_approach",
        "destination": "link_south_departure",
        "path": ["link_north_approach", "intersection_001", "link_south_departure"]
      },
      "behavior": {
        "driverType": "normal",
        "maxSpeed": 50.0,
        "desiredSpeed": 45.0,
        "aggressiveness": 0.5,
        "reactionTime": 1.2
      }
    },
    {
      "id": "vehicle_002",
      "name": "Delivery Truck",
      "vehicleType": "light_truck",
      "startTime": 120,
      "route": {
        "origin": "link_east_approach",
        "destination": "link_west_departure",
        "path": ["link_east_approach", "intersection_001", "link_west_departure"]
      },
      "behavior": {
        "driverType": "conservative",
        "maxSpeed": 40.0,
        "desiredSpeed": 35.0,
        "aggressiveness": 0.2,
        "reactionTime": 1.8
      }
    }
  ],
  "generation": {
    "enabled": true,
    "patterns": [
      {
        "name": "morning_rush",
        "timeRange": [0, 600],
        "rate": 0.5,
        "vehicleTypes": ["passenger_car"],
        "origins": ["link_north_approach", "link_south_approach"],
        "destinations": ["link_east_departure", "link_west_departure"]
      }
    ]
  }
}
```

---

## 🌆 **Complex Urban Network**

### **🏢 Multi-District City Simulation**

For larger scenarios, create a multi-district city with different zones:

```json
{
  "simulation": {
    "name": "Multi-District Urban Simulation",
    "description": "Complex city simulation with residential, commercial, and industrial zones",
    "duration": 86400,
    "actorsDataSources": [
      {
        "id": "road-network",
        "classType": "com.htc.infrastructure.RoadNetworkActor",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/scenarios/city/road_network.json"
          }
        }
      },
      {
        "id": "traffic-management",
        "classType": "com.htc.traffic.TrafficManagementActor",
        "dataSource": {
          "type": "json", 
          "info": {
            "path": "data/scenarios/city/traffic_management.json"
          }
        }
      },
      {
        "id": "vehicle-population",
        "classType": "com.htc.traffic.VehicleActor",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "csv",
          "info": {
            "path": "data/scenarios/city/vehicle_population.csv",
            "batchSize": 5000
          }
        }
      },
      {
        "id": "public-transport",
        "classType": "com.htc.transit.PublicTransportActor",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/scenarios/city/public_transport.json"
          }
        }
      }
    ]
  }
}
```

### **🗺️ Road Network Definition**
```json
{
  "network": {
    "name": "Metro City Network",
    "units": "metric",
    "coordinateSystem": "WGS84",
    "zones": [
      {
        "id": "downtown",
        "type": "commercial",
        "bounds": {
          "minX": -2000, "maxX": 2000,
          "minY": -2000, "maxY": 2000
        },
        "characteristics": {
          "gridPattern": true,
          "blockSize": 200,
          "laneWidth": 3.5,
          "defaultSpeedLimit": 50
        }
      },
      {
        "id": "residential_north",
        "type": "residential", 
        "bounds": {
          "minX": -3000, "maxX": 3000,
          "minY": 2000, "maxY": 8000
        },
        "characteristics": {
          "gridPattern": false,
          "curvedStreets": true,
          "defaultSpeedLimit": 30,
          "parkingAvailable": true
        }
      }
    ],
    "links": [
      {
        "id": "highway_1",
        "from": "node_001",
        "to": "node_002",
        "linkType": "highway",
        "length": 5000,
        "numberOfLanes": 4,
        "speedLimit": 100,
        "capacity": 8000,
        "characteristics": {
          "divided": true,
          "accessControlled": true,
          "tollRoad": false
        }
      }
    ],
    "nodes": [
      {
        "id": "node_001",
        "position": {"x": -5000, "y": 0},
        "nodeType": "highway_onramp",
        "elevation": 0
      }
    ]
  }
}
```

---

## 🚌 **Multi-Modal Transportation**

### **🚇 Integrated Transit System**

Create scenarios with multiple transportation modes:

```json
{
  "transitSystem": {
    "modes": [
      {
        "id": "bus_system",
        "type": "bus",
        "routes": [
          {
            "id": "route_1",
            "name": "Downtown Circulator",
            "stops": [
              {"id": "stop_001", "name": "City Hall", "position": {"x": 0, "y": 0}},
              {"id": "stop_002", "name": "Shopping Center", "position": {"x": 1000, "y": 500}}
            ],
            "schedule": {
              "headway": 300,
              "operatingHours": {
                "start": "06:00",
                "end": "22:00"
              }
            },
            "vehicles": [
              {
                "id": "bus_001",
                "capacity": 80,
                "type": "articulated",
                "startTime": 0
              }
            ]
          }
        ]
      },
      {
        "id": "metro_system",
        "type": "rail",
        "routes": [
          {
            "id": "metro_blue_line",
            "stations": [
              {"id": "station_001", "name": "Central Station"},
              {"id": "station_002", "name": "University Station"}
            ],
            "schedule": {
              "headway": 180,
              "operatingHours": {
                "start": "05:00", 
                "end": "24:00"
              }
            }
          }
        ]
      }
    ]
  }
}
```

### **🚲 Active Transportation**
```json
{
  "activeTransport": {
    "bikeshare": {
      "enabled": true,
      "stations": [
        {
          "id": "bike_station_001",
          "position": {"x": 500, "y": 300},
          "capacity": 20,
          "initialBikes": 15
        }
      ],
      "usage": {
        "demandModel": "gravity",
        "tripDistribution": "lognormal",
        "seasonalFactor": 0.8
      }
    },
    "pedestrians": {
      "enabled": true,
      "walkingSpeed": 1.4,
      "crossingBehavior": "cautious",
      "sidewalkNetwork": "auto_generate"
    }
  }
}
```

---

## 📊 **Demand Generation Patterns**

### **🕐 Time-Based Demand**
```json
{
  "demandGeneration": {
    "patterns": [
      {
        "name": "weekday_commuter_pattern",
        "type": "temporal",
        "schedule": {
          "monday": [
            {"time": "07:00", "intensity": 0.1},
            {"time": "08:00", "intensity": 0.8},
            {"time": "09:00", "intensity": 0.4},
            {"time": "17:00", "intensity": 0.6},
            {"time": "18:00", "intensity": 0.9},
            {"time": "19:00", "intensity": 0.3}
          ]
        },
        "applies_to": ["passenger_car", "bus_passenger"]
      },
      {
        "name": "freight_pattern",
        "type": "temporal",
        "schedule": {
          "all_days": [
            {"time": "05:00", "intensity": 0.7},
            {"time": "10:00", "intensity": 0.3},
            {"time": "14:00", "intensity": 0.5}
          ]
        },
        "applies_to": ["truck", "delivery_van"]
      }
    ]
  }
}
```

### **🎯 Origin-Destination Matrices**
```json
{
  "od_matrices": [
    {
      "name": "morning_commute",
      "time_period": {"start": "07:00", "end": "09:00"},
      "matrix": [
        {
          "origin": "residential_north",
          "destinations": [
            {"zone": "downtown", "trips": 1500, "mode_split": {
              "car": 0.7, "bus": 0.2, "rail": 0.1
            }},
            {"zone": "industrial_south", "trips": 800, "mode_split": {
              "car": 0.8, "bus": 0.15, "bike": 0.05
            }}
          ]
        }
      ]
    }
  ]
}
```

---

## 🎭 **Advanced Actor Behaviors**

### **🤖 Autonomous Vehicle Integration**
```json
{
  "autonomousVehicles": {
    "enabled": true,
    "penetrationRate": 0.15,
    "behaviorModel": "cautious_av",
    "capabilities": {
      "vehicleToVehicle": true,
      "vehicleToInfrastructure": true,
      "platooning": true,
      "adaptiveCruiseControl": true
    },
    "configuration": {
      "maxSpeed": 60.0,
      "followingDistance": 2.0,
      "reactionTime": 0.1,
      "laneChangeAggressiveness": 0.3,
      "intersectionBehavior": "conservative"
    }
  }
}
```

### **🚨 Incident Management**
```json
{
  "incidents": {
    "enabled": true,
    "scenarios": [
      {
        "id": "traffic_accident",
        "type": "accident",
        "probability": 0.001,
        "location": {
          "type": "random_link",
          "filter": {
            "linkTypes": ["arterial", "highway"]
          }
        },
        "duration": {
          "min": 600,
          "max": 3600,
          "distribution": "exponential"
        },
        "impact": {
          "capacity_reduction": 0.5,
          "speed_reduction": 0.3
        },
        "response": {
          "detection_time": 120,
          "clearance_time": 1800,
          "emergency_vehicles": ["ambulance", "police"]
        }
      },
      {
        "id": "road_work",
        "type": "construction",
        "schedule": [
          {
            "start": "2025-01-27T09:00:00",
            "end": "2025-01-27T15:00:00",
            "location": "link_main_street",
            "lanes_closed": 1
          }
        ]
      }
    ]
  }
}
```

### **🌦️ Weather Effects**
```json
{
  "weather": {
    "enabled": true,
    "conditions": [
      {
        "type": "rain",
        "intensity": "moderate",
        "start": "2025-01-27T14:00:00",
        "duration": 7200,
        "effects": {
          "speed_reduction": 0.15,
          "capacity_reduction": 0.1,
          "accident_probability_multiplier": 2.0
        }
      },
      {
        "type": "fog",
        "intensity": "heavy",
        "start": "2025-01-27T06:00:00", 
        "duration": 3600,
        "effects": {
          "visibility_reduction": 0.6,
          "speed_reduction": 0.3
        }
      }
    ]
  }
}
```

---

## 📈 **Calibration and Validation**

### **🎯 Parameter Calibration**
```json
{
  "calibration": {
    "enabled": true,
    "method": "genetic_algorithm",
    "parameters": [
      {
        "name": "car_following_sensitivity",
        "min": 0.5,
        "max": 2.0,
        "initial": 1.0
      },
      {
        "name": "lane_change_threshold",
        "min": 0.1,
        "max": 1.0,
        "initial": 0.5
      }
    ],
    "objectives": [
      {
        "name": "speed_difference",
        "weight": 0.4,
        "target": "field_data_speeds.csv"
      },
      {
        "name": "flow_difference", 
        "weight": 0.6,
        "target": "field_data_flows.csv"
      }
    ],
    "algorithm": {
      "population_size": 50,
      "generations": 100,
      "mutation_rate": 0.1
    }
  }
}
```

### **📊 Validation Metrics**
```json
{
  "validation": {
    "metrics": [
      {
        "name": "GEH_statistic",
        "description": "Geoffrey E. Havers statistic for flow validation",
        "threshold": 5.0,
        "applies_to": "link_flows"
      },
      {
        "name": "speed_correlation",
        "description": "Correlation between simulated and observed speeds",
        "threshold": 0.8,
        "applies_to": "link_speeds"
      }
    ],
    "comparison_data": {
      "source": "field_measurements",
      "files": [
        "validation/link_flows_2025.csv",
        "validation/intersection_delays.csv"
      ]
    }
  }
}
```

---

## 🔧 **Scenario Templates**

### **📁 Template Library**

HTC provides pre-built scenario templates for common use cases:

```bash
# List available templates
./scripts/list_templates.sh

# Create scenario from template
./scripts/create_scenario.sh --template urban_intersection --name my_intersection

# Available templates:
# - urban_intersection: Basic signalized intersection
# - highway_segment: Highway with on/off ramps  
# - transit_corridor: Bus rapid transit system
# - downtown_grid: Downtown urban grid network
# - suburban_arterial: Suburban arterial with signals
# - mixed_use_district: Mixed residential/commercial area
```

### **🎨 Custom Template Creation**
```bash
# Create reusable template from existing scenario
./scripts/create_template.sh --from my_scenario.json --name custom_template

# Template structure:
templates/
├── urban_intersection/
│   ├── template.json          # Scenario configuration
│   ├── network.json          # Road network definition
│   ├── signals.json          # Traffic signal timing
│   ├── demand.json           # Traffic demand patterns
│   └── README.md             # Template documentation
```

---

## 📊 **Data Integration**

### **🗺️ Real-World Data Sources**

#### **OpenStreetMap Integration**
```json
{
  "dataImport": {
    "openStreetMap": {
      "enabled": true,
      "region": {
        "boundingBox": {
          "north": 37.7849,
          "south": 37.7549,
          "east": -122.3894,
          "west": -122.4394
        }
      },
      "filters": {
        "highways": ["primary", "secondary", "tertiary", "residential"],
        "exclude": ["footway", "cycleway", "steps"]
      },
      "processing": {
        "simplification": true,
        "mergeShortLinks": true,
        "minimumLinkLength": 10
      }
    }
  }
}
```

#### **Traffic Count Data**
```json
{
  "fieldData": {
    "trafficCounts": {
      "source": "city_traffic_department",
      "format": "csv",
      "files": [
        {
          "path": "data/counts/intersection_counts_2024.csv",
          "timeResolution": "15min",
          "spatialResolution": "intersection"
        }
      ],
      "mapping": {
        "simulation_link": "count_location",
        "time_field": "timestamp",
        "volume_field": "vehicle_count"
      }
    }
  }
}
```

---

## 🎮 **Interactive Scenarios**

### **🕹️ Real-Time Control**
```json
{
  "interactiveControl": {
    "enabled": true,
    "interface": "web_dashboard",
    "controls": [
      {
        "type": "signal_timing",
        "signals": ["signal_001", "signal_002"],
        "parameters": ["cycle_time", "green_split"]
      },
      {
        "type": "incident_injection", 
        "locations": ["any_link"],
        "incident_types": ["accident", "construction", "special_event"]
      },
      {
        "type": "demand_adjustment",
        "zones": ["downtown", "residential"],
        "adjustment_range": [0.5, 2.0]
      }
    ]
  }
}
```

### **🎯 Scenario Variants**
```json
{
  "scenarioVariants": [
    {
      "name": "baseline",
      "description": "Current conditions",
      "modifications": {}
    },
    {
      "name": "new_signal_timing",
      "description": "Optimized signal timing plan",
      "modifications": {
        "signals": {
          "cycle_time_multiplier": 0.9,
          "green_extension": true
        }
      }
    },
    {
      "name": "bus_priority", 
      "description": "Transit signal priority",
      "modifications": {
        "signals": {
          "transit_priority": true,
          "priority_vehicles": ["bus"]
        }
      }
    }
  ]
}
```

---

## ✅ **Best Practices**

### **📋 Scenario Development Checklist**

#### **🔍 Pre-Development**
- [ ] Define clear simulation objectives
- [ ] Identify key performance indicators
- [ ] Research real-world context and constraints
- [ ] Determine required level of detail
- [ ] Plan validation approach

#### **🏗️ During Development**
- [ ] Start with simple network, add complexity gradually
- [ ] Use realistic parameter values
- [ ] Include appropriate behavioral diversity
- [ ] Test with small vehicle populations first
- [ ] Document all assumptions and parameters

#### **✅ Post-Development**
- [ ] Validate against field data
- [ ] Test scenario robustness with different seeds
- [ ] Document scenario purpose and limitations
- [ ] Create clear usage instructions
- [ ] Share with community for peer review

### **⚡ Performance Optimization**

#### **🎯 Efficient Scenario Design**
```json
{
  "performance": {
    "tips": [
      "Use LoadBalancedDistributed for large vehicle populations",
      "Batch similar actors in single data sources",
      "Minimize unnecessary state updates",
      "Use appropriate time resolution (don't over-detail)",
      "Consider geographic partitioning for large networks"
    ],
    "monitoring": {
      "enabled": true,
      "metrics": ["memory_usage", "event_throughput", "actor_count"],
      "alerts": {
        "memory_threshold": 0.8,
        "cpu_threshold": 0.9
      }
    }
  }
}
```

---

## 📚 **Example Scenarios**

Complete example scenarios are available in the repository:

```
examples/
├── basic/
│   ├── simple_intersection/
│   ├── highway_segment/
│   └── bus_route/
├── intermediate/
│   ├── downtown_grid/
│   ├── suburban_network/
│   └── mixed_modal/
└── advanced/
    ├── autonomous_vehicle_integration/
    ├── smart_city_system/
    └── regional_network/
```

### **📖 Running Examples**
```bash
# Run basic intersection example
./run_example.sh basic/simple_intersection

# Run advanced smart city example
./run_example.sh advanced/smart_city_system

# List all available examples
./list_examples.sh
```

---

## 🔬 **Research Applications**

### **🎓 Academic Use Cases**

#### **Transportation Engineering**
- Traffic signal optimization studies
- Intersection capacity analysis
- Highway merge optimization
- Transit priority implementation

#### **Urban Planning**
- Land use impact assessment
- Development scenario comparison
- Transportation demand forecasting
- Sustainability impact analysis

#### **Technology Research**
- Autonomous vehicle integration
- Connected vehicle systems
- Smart traffic management
- Mobility as a Service platforms

### **📊 Research Methodology**
```json
{
  "research_design": {
    "experimental_factors": [
      "signal_timing_strategy",
      "av_penetration_rate",
      "transit_frequency"
    ],
    "response_variables": [
      "average_travel_time",
      "fuel_consumption", 
      "intersection_delay"
    ],
    "replication": {
      "seeds": [1, 2, 3, 4, 5],
      "statistical_significance": 0.05
    }
  }
}
```

---

**🎯 This guide provides you with the tools and knowledge to create sophisticated, realistic traffic simulation scenarios. Start with simple examples and gradually build complexity as you become more familiar with the system capabilities.**

For more specific examples and templates, explore the [examples directory](examples/) and consult the [Configuration Guide](CONFIGURATION.md) for detailed parameter options.