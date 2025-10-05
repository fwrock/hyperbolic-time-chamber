# 🎮 Examples Directory

This directory contains practical examples and tutorials for using the Hyperbolic Time Chamber simulation framework. Examples are organized by complexity level and use case.

---

## 📚 **Example Categories**

### **🟢 Basic Examples**
Simple, focused examples that demonstrate core concepts:

- **[Simple Intersection](basic/simple_intersection.md)** - Single signalized intersection
- **[Highway Segment](basic/highway_segment.md)** - Basic highway with vehicles  
- **[Bus Route](basic/bus_route.md)** - Public transit simulation
- **[Vehicle Following](basic/vehicle_following.md)** - Car-following behavior

### **🟡 Intermediate Examples**
More complex scenarios combining multiple features:

- **[Downtown Grid](intermediate/downtown_grid.md)** - Urban grid network
- **[Suburban Network](intermediate/suburban_network.md)** - Mixed arterial/residential
- **[Mixed Modal](intermediate/mixed_modal.md)** - Cars, buses, and pedestrians
- **[Incident Management](intermediate/incident_management.md)** - Traffic incidents and response

### **🔴 Advanced Examples**
Comprehensive scenarios showcasing advanced capabilities:

- **[Smart City System](advanced/smart_city_system.md)** - Connected infrastructure
- **[Autonomous Vehicles](advanced/autonomous_vehicles.md)** - AV integration
- **[Regional Network](advanced/regional_network.md)** - Large-scale regional model
- **[Real-time Optimization](advanced/real_time_optimization.md)** - Adaptive control

---

## 🚀 **Quick Start Examples**

### **⚡ 5-Minute Example**
```bash
# Run basic intersection example
cd examples/basic/simple_intersection
./run.sh

# View results
open output/dashboard.html
```

### **🎯 Use Case Examples**

#### **Academic Research**
```bash
# Signal timing optimization study
./examples/research/signal_optimization.sh

# AV penetration analysis
./examples/research/av_penetration_study.sh
```

#### **Urban Planning**
```bash
# Development impact assessment
./examples/planning/development_impact.sh

# Transit corridor analysis
./examples/planning/transit_corridor.sh
```

#### **Technology Testing**
```bash
# Connected vehicle systems
./examples/technology/connected_vehicles.sh

# Smart traffic management
./examples/technology/smart_traffic.sh
```

---

## 📖 **Tutorial Series**

### **🎓 Beginner Tutorial Series**
1. **[Getting Started](tutorials/01_getting_started.md)** - Your first simulation
2. **[Understanding Actors](tutorials/02_understanding_actors.md)** - Actor system basics
3. **[Configuration Basics](tutorials/03_configuration_basics.md)** - Setting up scenarios
4. **[Data Analysis](tutorials/04_data_analysis.md)** - Analyzing results

### **🔧 Advanced Tutorial Series**
1. **[Custom Actors](tutorials/05_custom_actors.md)** - Building custom components
2. **[Plugin Development](tutorials/06_plugin_development.md)** - Extending the system
3. **[Performance Optimization](tutorials/07_performance_optimization.md)** - Scaling up
4. **[Production Deployment](tutorials/08_production_deployment.md)** - Going live

---

## 🛠️ **Running Examples**

### **📋 Prerequisites**
Ensure you have completed the [Getting Started Guide](../GETTING_STARTED.md) before running examples.

### **🎬 Running Individual Examples**
```bash
# Navigate to example directory
cd examples/basic/simple_intersection

# Follow example-specific README
cat README.md

# Run the example
./run.sh

# Or use the global runner
cd ../../..
./run_example.sh basic/simple_intersection
```

### **📊 Batch Running**
```bash
# Run all basic examples
./run_examples.sh --category basic

# Run specific examples
./run_examples.sh simple_intersection highway_segment

# Run with custom configuration
./run_examples.sh --config my_config.json basic/simple_intersection
```

---

## 📁 **Example Structure**

Each example follows a consistent structure:

```
example_name/
├── README.md                    # Example description and instructions
├── config/
│   ├── simulation.json         # Simulation configuration
│   ├── network.json           # Network definition
│   └── demand.json            # Traffic demand
├── data/
│   ├── vehicles.json          # Vehicle definitions
│   ├── signals.json           # Traffic signal config
│   └── validation/            # Validation data
├── scripts/
│   ├── run.sh                # Main execution script
│   ├── analyze.sh            # Analysis script
│   └── clean.sh              # Cleanup script
├── output/                    # Generated results (gitignored)
└── docs/
    ├── scenario_description.md # Detailed scenario documentation
    └── expected_results.md    # Expected outcomes
```

---

## 🎯 **Example Index**

### **By Simulation Type**

#### **🚗 Traffic Flow**
- Simple Intersection
- Highway Merge
- Roundabout
- Arterial Corridor

#### **🚌 Public Transit**
- Bus Route
- BRT System
- Rail Network
- Mixed Modal

#### **🏙️ Urban Planning**
- Downtown Grid
- Suburban Development
- Mixed Use District
- Transit-Oriented Development

#### **🤖 Technology Integration**
- Autonomous Vehicles
- Connected Infrastructure
- Smart Signals
- Dynamic Routing

### **By Analysis Focus**

#### **📊 Performance Analysis**
- Throughput Studies
- Delay Analysis
- Level of Service
- Capacity Utilization

#### **🌍 Environmental Impact**
- Emissions Modeling
- Fuel Consumption
- Noise Analysis
- Air Quality

#### **🚨 Safety Analysis**
- Conflict Analysis
- Incident Impact
- Emergency Response
- Risk Assessment

#### **💰 Economic Analysis**
- Cost-Benefit Studies
- Travel Time Value
- Infrastructure ROI
- Operational Costs

---

## 📚 **Learning Path**

### **🎯 Recommended Progression**

#### **Week 1: Foundations**
1. Run Simple Intersection example
2. Modify vehicle demand patterns
3. Analyze basic outputs
4. Experiment with signal timing

#### **Week 2: Network Modeling**
1. Highway Segment example
2. Add additional lanes
3. Model different vehicle types
4. Study capacity impacts

#### **Week 3: Multi-Modal**
1. Bus Route example
2. Add pedestrian crossing
3. Study mode interactions
4. Analyze transit performance

#### **Week 4: Advanced Features**
1. Choose an advanced example
2. Understand the architecture
3. Modify one component
4. Document your changes

### **🎓 Skill Building**

#### **Beginner Skills**
- [ ] Run existing examples successfully
- [ ] Modify basic parameters
- [ ] Interpret output graphs
- [ ] Understand configuration files

#### **Intermediate Skills**
- [ ] Create custom scenarios
- [ ] Modify actor behaviors
- [ ] Design validation studies
- [ ] Optimize performance

#### **Advanced Skills**
- [ ] Develop custom actors
- [ ] Build analysis plugins
- [ ] Design complex networks
- [ ] Contribute to the project

---

## 🔍 **Troubleshooting Examples**

### **Common Issues**

#### **❌ Example Won't Run**
```bash
# Check prerequisites
./diagnose.sh

# Verify example integrity
./validate_example.sh basic/simple_intersection

# Reset to clean state
./clean_example.sh basic/simple_intersection
./run_example.sh basic/simple_intersection
```

#### **❌ Unexpected Results**
```bash
# Compare with expected results
./compare_results.sh basic/simple_intersection

# Check configuration differences
diff config/simulation.json examples/basic/simple_intersection/config/simulation.json

# Validate input data
./validate_inputs.sh basic/simple_intersection
```

#### **❌ Performance Issues**
```bash
# Check system resources
./diagnose.sh system

# Use minimal configuration
./run_example.sh --config minimal basic/simple_intersection

# Monitor resource usage
./monitor_example.sh basic/simple_intersection
```

---

## 🤝 **Contributing Examples**

### **📝 Example Contribution Guidelines**

#### **Creating New Examples**
1. **Identify Need**: Does this example teach something new?
2. **Plan Structure**: Follow the standard example structure
3. **Document Thoroughly**: Include clear README and documentation
4. **Test Thoroughly**: Verify on clean system
5. **Review Process**: Submit PR for community review

#### **Example Quality Standards**
- [ ] Clear learning objectives
- [ ] Step-by-step instructions
- [ ] Expected runtime under 10 minutes
- [ ] Comprehensive documentation
- [ ] Validation against known results
- [ ] Cross-platform compatibility

#### **Documentation Requirements**
```markdown
# Example Name

## Learning Objectives
- Objective 1
- Objective 2

## Scenario Description
Detailed description of what is being simulated.

## Key Concepts
- Concept 1: Explanation
- Concept 2: Explanation

## Expected Results
Description of expected outcomes.

## Extensions
Ideas for extending this example.
```

### **🔧 Example Template**
```bash
# Create new example from template
./create_example.sh --template basic --name my_new_example

# This creates:
examples/basic/my_new_example/
├── README.md                  # From template
├── config/simulation.json     # Basic configuration
├── run.sh                     # Execution script
└── docs/scenario_description.md
```

---

## 🎉 **Community Examples**

### **🌟 Featured Examples**
Examples highlighted by the community:

- **[Urban Resilience](community/urban_resilience.md)** - Climate adaptation planning
- **[Freight Optimization](community/freight_optimization.md)** - Cargo flow optimization
- **[Event Management](community/event_management.md)** - Large event traffic planning

### **🏆 Example Showcase**
Outstanding examples from community contributors:

- **Best Educational Example**: Downtown Grid Network
- **Most Innovative**: Autonomous Vehicle Platooning
- **Best Validation**: Regional Network Calibration
- **Most Practical**: Incident Response Planning

---

**🎯 These examples provide hands-on learning experiences that build from basic concepts to advanced simulation capabilities. Start with the basics and progress through increasingly complex scenarios to master the Hyperbolic Time Chamber framework.**

Ready to start learning? Begin with the [Simple Intersection Example](basic/simple_intersection.md)!