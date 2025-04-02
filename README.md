# HTC-Simulator (Hyperbolic Time Chamber Simulator)

**An Actor-Based Discrete Event Simulator Using Scala and Akka**

Inspired by the legendary "Hyperbolic Time Chamber" from Dragon Ball, HTC-Simulator is a powerful discrete event simulator that leverages the power of actor-based programming with Scala and Akka.

## Current Features:

* **Simulation Time Management:** Precise control over the flow of time within the simulation.
* **Event Coordination:** Efficient orchestration of interactions between different entities (actors) in the simulation.

## Features Under Development:

* **Reporting:** Generation of detailed reports on simulation results.
* **Digital Twin:** Creation of digital replicas of real-world systems for simulation and analysis.
* **Dataflow:** Support for real-time data stream processing within the simulation.
* **Snapshot:** Ability to capture the simulation state at specific moments for later analysis or restoration.
* **Machine Learning:** Integration of machine learning algorithms for predictive analysis and decision-making within the simulation.

## Input Data:

### Simulation Configuration:

To start a new simulation, you need to provide a JSON configuration file with the simulation details, including the name, description, start date, end date, time unit, and time step.

```json
{
  "simulation": {
    "name": "HTC-Simulator",
    "description": "A powerful discrete event simulator based on actors using Scala and Akka.",
    "start": "2021-09-01T00:00:00Z",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 30,
    "actorsDataSources": [
      {
        "type": "Actor1",
        "dataSource": {
          "type": "json",
          "path": "data/actor1.json"
        }
      },
      {
        "type": "Actor2",
        "dataSource": {
          "type": "json",
          "path": "data/actor2.xml"
        }
      },
      {
        "type": "Actor3",
        "dataSource": {
          "type": "json",
          "path": "data/actor1.json"
        }
      },
      {
        "type": "Actor4",
        "dataSource": {
          "type": "mongo",
          "host": "localhost",
          "port": 27017,
          "database": "actors",
          "collection": "actor4",
          "query": {
            "name": "Actor4"
          }
        }
      },
      {
        "type": "Actor5",
        "dataSource": {
          "type": "cassandra",
          "host": "localhost",
          "port": 9042,
          "keyspace": "actors",
          "table": "actor5",
          "query": {
            "name": "Actor5"
          }
        }
      }
    ]
  },
  "output": [
    {
      "type": "console"
    },
    {
      "type": "json",
      "path": "output/simulation.json"
    },
    {
      "type": "xml",
      "path": "output/simulation.xml"
    },
    {
      "type": "csv",
      "path": "output/simulation.csv"
    },
    {
      "type": "mongo",
      "host": "localhost",
      "port": 27017,
      "database": "output",
      "collection": "simulation"
    }
  ]
}
```

### Actor Data Sources

Each actor in the simulation is defined by a specific type and a corresponding data source. Actor data can be provided in different formats, such as JSON, XML, MongoDB, Cassandra, etc.
**data/actor1.json:**
```json
[
    {
      "name": "Actor1",
      "type": "ActorType1",
      "data": {
        "type": "DataType1",
        "content": {
          "property1": 10,
          "property2": 20,
          "objectProperty": {
            "subProperty1": 30,
            "subProperty2": 40
          }
        }
      }
    }
]
```
**data/actor2.xml:**
```xml
<actors>
    <actor>
      <name>Actor1</name>
      <type>ActorType2</type>
      <data>
        <type>DataType2</type>
        <content>
          <property1>50</property1>
          <property2>60</property2>
          <objectProperty>
            <subProperty1>70</subProperty1>
            <subProperty2>80</subProperty2>
          </objectProperty>
        </content>
      </data>
    </actor>
</actors>
```
**data/actor3.json:**

```json
[
    {
      "name": "Actor1",
      "type": "ActorType3",
      "data": {
        "type": "Default",
        "content": {
          "property1": 10,
          "property2": 20,
          "objectProperty": {
            "subProperty1": 30,
            "subProperty2": 40
          }
        }
      }
    }
]
```


## Getting Started:

```bash
git clone [https://github.com/fwrock/hyperbolic-time-chamber.git](https://github.com/your-username/HTC-Simulator.git) # Please replace with the actual URL if different
cd hyperbolic-time-chamber
./build-and-run.sh # Build and run the simulator with docker compose
```

## Contributing:
Contributions are welcome! Feel free to open issues or submit pull requests.

## Notes:
HTC-Simulator is still under active development. New features and improvements are on the way!
The name "Hyperbolic Time Chamber" is a tribute to the Dragon Ball series and does not imply any affiliation or endorsement.
We hope HTC-Simulator will be a valuable tool for your simulation needs. Enjoy exploring the possibilities!

## Contato:
For questions or suggestions, please contact [wallison.rocha@usp.br].
Licença:
his project is licensed under the Apache 2.0 License - see the LICENSE file for details.
