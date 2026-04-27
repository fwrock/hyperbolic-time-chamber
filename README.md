# Hyperbolic Time Chamber *(HTC)*

![HTC Banner](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**A comprehensive multi-agent traffic simulation framework for general-purpose mobility research**

A distributed, event-driven multi-agent system built with Scala and Apache Pekko, featuring a mesoscopic mobility model implementation for urban traffic simulation and analysis.

---

## **What is Hyperbolic Time Chamber?**

The Hyperbolic Time Chamber (HTC) is a general-purpose, distributed traffic simulation framework designed for:

### **Core Capabilities**
- **Multi-Agent Simulation**: Actor-based architecture using Apache Pekko
- **Mesoscopic Mobility Model**: Built-in implementation for urban traffic simulation
- **Distributed Computing**: Horizontal scaling across multiple nodes
- **Event-Driven Design**: Discrete event simulation with precise time management
- **Flexible Data Sources**: JSON, CSV, and database input support

### **Analysis & Research**
- **Traffic Flow Analysis**: Comprehensive traffic pattern analysis
- **Performance Metrics**: Detailed performance and scalability measurements
- **Academic Research**: Publication-ready analysis and validation capabilities
- **Extensible Framework**: Support for custom mobility models and scenarios

### **Extensibility**
- **Plugin Architecture**: Easy integration of new actor types
- **Custom Events**: Support for domain-specific event types
- **Reporting Framework**: Flexible data collection and analysis
- **API Integration**: REST and gRPC interfaces for external systems

---

## **Quick Start**

```bash
cd hyperbolic-time-chamber

# Build and run (recommended)
./build-and-run.sh

# Run tests
sbt test

# Format code
sbt scalafmt

# Check for issues
sbt scalafix --check
```

### **Project Standards:**
- **Scala Version**: 3.3.5
- **Java Version**: 21+
- **Build Tool**: SBT 1.x
- **Code Style**: Scalafmt with standard configuration
- **Testing**: ScalaTest with coverage reports
- **Documentation**: ScalaDoc for API documentation

**For detailed development guidelines, see [Developer Guide](docs/DEVELOPER_GUIDE.md)**

### **Contributing Guidelines:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/new-feature`)
3. Commit your changes (`git commit -am 'Add new feature'`)
4. Push to the branch (`git push origin feature/new-feature`)
5. Open a Pull Request

---

## **Scenario Modeling**

To build or customise an urban mobility scenario for the hybrid model — including road networks, public transit, traffic signals, and agent populations — see the **[Scenario Modeling Guide](docs/SCENARIO_MODELING.md)**.

---

## **Academic Usage**

This system was developed for research in **urban mobility** and **traffic simulation**.

**For citation:**
```bibtex
@software{hyperbolic_time_chamber,
  title={Hyperbolic Time Chamber: Multi-Agent Traffic Simulation Framework},
  author={Rocha, Francisco Wallison and Francesquini, Emilio and Cordeiro, Daniel},
  year={2025},
  url={https://github.com/fwrock/hyperbolic-time-chamber},
  note={Multi-agent simulation system with mesoscopic mobility model}
}
```

**For detailed academic usage guidelines, see [Academic Usage](docs/ACADEMIC_USAGE.md)**

---

## **License**

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## **Key Features Summary**

**Core Advantages:**
- **Actor-based architecture** with horizontal scaling
- **High-performance** discrete event simulation
- **Built-in mesoscopic model** for realistic traffic simulation
- **Time-series data storage** with Cassandra integration
- **Flexible configuration** and scenario management
- **Comprehensive reporting** and analysis capabilities
- **Docker deployment** for easy setup and scaling

**Use Cases:**
- **Urban Traffic Analysis**: City-scale traffic simulation and optimization
- **Transportation Research**: Academic research and validation studies  
- **Policy Evaluation**: Impact assessment of transportation policies
- **Infrastructure Planning**: Network design and capacity analysis
- **Algorithm Development**: Testing new mobility and routing algorithms

---

**Ready for large-scale traffic simulation and urban mobility research!**

*Inspired by the legendary "Hyperbolic Time Chamber" from Dragon Ball, where 1 day = 1 year of training, our simulator allows for accelerated time analysis of traffic patterns and urban mobility scenarios.*
