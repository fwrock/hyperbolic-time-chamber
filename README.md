# HTC-Simulator (Hyperbolic Time Chamber Simulator)

**Um Simulador de Eventos Discretos Baseado em Atores Utilizando Scala e Akka**

Inspirado na lendária "Câmara do Tempo Hiperbólica" de Dragon Ball, o HTC-Simulator é um poderoso simulador de eventos discretos que aproveita o poder da programação baseada em atores com Scala e Akka.

## Recursos Atuais:

* Gerenciamento de Tempo de Simulação: Controle preciso do fluxo do tempo dentro da simulação.
* Coordenação de Eventos: Orquestração eficiente da interação entre diferentes entidades (atores) na simulação.

## Recursos em Desenvolvimento:

* Relatórios: Geração de relatórios detalhados sobre os resultados da simulação.
* Digital Twin: Criação de réplicas digitais de sistemas do mundo real para simulação e análise.
* Dataflow: Suporte para processamento de fluxo de dados em tempo real dentro da simulação.
* Snapshot: Capacidade de capturar o estado da simulação em momentos específicos para análise posterior ou restauração.
* Machine Learning: Integração de algoritmos de aprendizado de máquina para análise preditiva e tomada de decisão dentro da simulação.

## Dados de Entrada:

### Configuração da Simulação:

Para iniciar uma nova simulação, você precisa fornecer um arquivo de configuração JSON com os detalhes da simulação, incluindo o nome, descrição, data de início, data de término, unidade de tempo e passo de tempo.

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

### Fontes de Dados dos Atores:

Cada ator na simulação é definido por um tipo específico e uma fonte de dados correspondente. Os dados dos atores podem ser fornecidos em diferentes formatos, como JSON, XML, MongoDB, Cassandra, etc.

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


## Começando:

```bash
git clone [https://github.com/seu-usuario/HTC-Simulator.git](https://github.com/seu-usuario/HTC-Simulator.git)
cd HTC-Simulator
sbt compile
sbt run
```

## Contribuindo:
Contribuições são bem-vindas! Sinta-se à vontade para abrir issues ou enviar pull requests.

## Observações:
O HTC-Simulator ainda está em desenvolvimento ativo. Novos recursos e melhorias estão a caminho!
O nome "Hyperbolic Time Chamber" é uma homenagem à série Dragon Ball e não implica qualquer afiliação ou endosso.
Esperamos que o HTC-Simulator seja uma ferramenta valiosa para suas necessidades de simulação. Divirta-se explorando as possibilidades!

## Contato:
Em caso de dúvidas ou sugestões, entre em contato através de [wallison.rocha@usp.br].
Licença:
Este projeto está licenciado sob a Licença MIT - consulte o arquivo LICENSE para obter detalhes.

