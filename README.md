# HTC-Simulator (Hyperbolic Time Chamber Simulator)

![image](https://github.com/user-attachments/assets/dddd6245-f4bd-43fc-8888-6ef73d01a221)

**An Actor-Based Multi-Agent Discrete Event Simulator Using Scala and Apache Pekko**

Inspired by the legendary "Hyperbolic Time Chamber" from Dragon Ball, HTC-Simulator is a powerful discrete event simulator that leverages the power of actor-based programming with Scala and Akka.

## Current Features:

* **Simulation Time Management:** Precise control over the flow of time within the simulation.
* **Simulation Load Data:** Ability to load simulation data from various sources, including JSON files.
* **Event Coordination:** Efficient orchestration of interactions between different entities (actors) in the simulation.
* **Reporting:** Generation of detailed reports on simulation results.
* **Snapshot:** Ability to capture the simulation state at specific moments for later analysis or restoration.
* **Distributed Simulation:** Support for running simulations across multiple nodes or clusters for enhanced performance and scalability.

## Features Under Development:

* **Digital Twin:** Creation of digital replicas of real-world systems for simulation and analysis.
* **Dataflow:** Support for real-time data stream processing within the simulation.
* **Machine Learning:** Integration of machine learning algorithms for predictive analysis and decision-making within the simulation.
* **Graphical User Interface (GUI):** A user-friendly interface for easier interaction with the simulator.


## Input Data:

### Simulation Configuration:

To start a new simulation, you need to provide a JSON configuration file with the simulation details:

```json
{
  "simulation": {
    "name": "HTC-Simulator",
    "description": "A powerful discrete event simulator based on actors using Scala and Apache Pekko.",
    "start": "2025-01-27T00:00:00.000",
    "timeUnit": "seconds",
    "timeStep": 1,
    "duration": 30,
    "actorsDataSources": [
      {
        "id": "htcid:resource:simulation-model-name:1",
        "classType": "com.example.Actor1",
        "creationType": "LoadBalancedDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/actor1.json"
          }
        }
      },
      {
        "id": "htcid:resource:simulation-model-name:2",
        "classType": "com.example.Actor2",
        "creationType": "PoolDistributed",
        "dataSource": {
          "type": "json",
          "info": {
            "path": "data/actor2.json"
          }
        }
      }
    ]
  }
}
```

### Actor Data Sources

Each actor in the simulation is defined by a specific type and a corresponding data source. Actor data can be provided in JSON format. You can define the actor's properties, dependencies, and other configurations in the JSON files. Each file should contain an array of n actors definitions.
The actor data source is defined in the simulation configuration file under `actorsDataSources`. Each actor's data source includes an ID, class type, creation type, and a reference to the JSON file containing the actor's data. 

The `creationType` can be either `LoadBalancedDistributed` or `PoolDistributed`, depending on how you want to manage the actor's lifecycle and distribution across nodes:
- **LoadBalancedDistributed:** This type allows the actor to be distributed across multiple nodes in a load-balanced manner. It is suitable for scenarios where you want to evenly distribute the workload among available nodes.
- **PoolDistributed:** This type allows you to create a pool of actor instances that can be used to handle requests. It is useful for scenarios where you want to limit the number of active instances and control resource usage.

The `dependencies` field allows you to specify other actors that this actor depends on. This is useful for establishing relationships between different actors in the simulation.
The `data` field contains the actor's properties and configuration. You can define various properties, including primitive types and nested objects.
**data/actor1.json:**
```json
[
    {
      "id": "htcid:actor:simulation-model-name:actor-type:1",
      "name": "Actor1",
      "typeActor": "com.example.actor.ActorType1",
      "creationType": "LoadBalancedDistributed",
      "data": {
        "dataType": "com.example.data.DataType1",
        "content": {
          "property1": 10,
          "property2": 20,
          "objectProperty": {
            "subProperty1": 30,
            "subProperty2": 40
          }
        }
      },
      "dependencies": {
        "example": {
          "id":  "htcid:actor:simulation-model-name:actor-type:2",
          "resourceId": "htcid:resource:simulation-model-name:2",
          "classType": "com.example.Actor2",
          "actorType": "PoolDistributed"
        }
      }
    }
]
```
**data/actor2.json:**

```json
[
  {
    "id": "htcid:actor:simulation-model-name:actor-type:2",
    "name": "Actor2",
    "typeActor": "com.example.actor.ActorType2",
    "creationType": "PoolDistributed",
    "poolConfiguration": {
      "roundRobinPool": 0,
      "totalInstances": 1,
      "maxInstancesPerNode": 1,
      "allowLocalRoutes": true
    },
    "data": {
      "dataType": "com.example.data.DataType2",
      "content": {
        "property1": 10,
        "property2": 20,
        "objectProperty": {
          "subProperty1": 30,
          "subProperty2": 40
        }
      }
    },
    "dependencies": {
      "example": {
        "id":  "htcid:actor:simulation-model-name:actor-type:1",
        "resourceId": "htcid:resource:simulation-model-name:1",
        "classType": "com.example.actor.Actor1",
        "actorType": "LoadBalancedDistributed"
      }
    }
  }
]
```

## Implementing a Simulation Model:

To implement a simulation model, you need to create a class that extends the `SimulationModel` trait. This class will define the behavior of your simulation and how actors interact with each other.

### Example Actor Model State:

```scala
package org.interscity.htc.model.example.state

case class ExampleState(
  property1: Int,
  property2: String,
  objectProperty: ExampleObject
) extends BaseState
```

### Example Actor Model:

```scala
package org.interscity.htc.model.example.actor

import org.interscity.htc.model.example.state.ExampleState

class Example extends BaseActor[ExampleState] {
  
  override def onStart(): Unit = {
    // This method is called when the actor starts
  }
  
  override def onInitialized(): Unit = {
    // This method is called when the actor is initialized
  }
  
  override def actSpontaneous((event: SpontaneousEvent): Unit = {
    // Handle spontaneous events (spontaneous events are events that occur without any external trigger by Time Manager)
  }

  def actInteractWith(event: ActorInteractionEvent): Unit = {
    // Handle interaction events (interaction events are events that occur as a result of interactions with other actors)
  }
  
}


````

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
