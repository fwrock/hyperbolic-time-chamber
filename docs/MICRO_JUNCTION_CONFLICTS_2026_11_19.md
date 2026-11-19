# MICRO G04 (primeira parte) — conflitos e prioridade no Node

Data: 19/11/2026. Estado: **implementado e testado em nível de Node; não executado em cenário integral**. Gap acceptance (G05) não existe.

## O que foi implementado

- `JunctionConflict(connectorA, connectorB, priorityConnector?, source)`: dois conectores cujos caminhos se cruzam. O campo
  `source` é obrigatório (a regra deve citar a origem; prioridade não é deduzida da ordem numérica dos IDs). Configuração em
  `NodeState.conflicts`, validada contra `junctionMovements` (conectores conhecidos, pares únicos).
- **Exclusão**: o Node não concede um conector enquanto o conector conflitante estiver ocupado. A ocupação
  (`NodeState.occupiedConnectors`, no checkpoint) sobe na concessão e desce quando o Link do conector envia
  `LinkCapacityFreedData`, ou seja, quando o veículo termina de fato a travessia (com o G03, só após a admissão na via
  seguinte). Não é um tempo nominal de fluxo livre.
- **Prioridade e ordem**: um pedido não ultrapassa pedido pendente que o vença (regra de prioridade; sem prioridade, o mais antigo por
  `(tick, id do veículo, sequência)`). Ao liberar, `drainConflictGroup` concede um pedido por vez nessa ordem determinística,
  reavaliando a ocupação a cada concessão. Movimentos sem conflito seguem o caminho anterior, inalterado.
- Reaproveita a fila de espera e a identidade de resposta existentes (`PendingLinkAccessRequest`, ganhou `requestedTick`), então
  cancelamento, replay e checkpoint herdam o contrato anterior. Nenhuma mensagem nova e nenhum uso do Time Manager.

## Custo

Só roda quando `conflicts` não está vazio; sem regras o custo é uma verificação de lista vazia por pedido. Não medido em carga.

## Limitações conhecidas (não esconder)

- **Pedidos no mesmo tick** dependem da ordem de entrega no Node: a decisão imediata de um pedido sem concorrente já pendente
  não vê pedidos simultâneos ainda não entregues. O desempate determinístico só atua entre pedidos pendentes. Independência total da
  ordem exigiria decisão em lote por tick (latência extra), não implementada.
- **Sem gap acceptance** (G05): um conector livre é concedido mesmo com veículo prioritário se aproximando, e o veículo que cede
  só espera depois de chegar ao fim do Link (a aproximação não antecipa a frenagem; no modo spillback a parada na fronteira é
  geométrica, não frenagem calibrada).
- Nada gera `conflicts` ainda: os conversores/geradores de cenário não emitem essas regras. Só testes de unidade as constroem.
- O `capacityState = Full` é reaproveitado como resposta de "espere": o Car trata como espera por capacidade.

## Fontes

- Conceito de áreas de conflito, prioridade e exclusão de movimentos em junções: documentação de interseções do SUMO (ligações,
  "foes" e "response") e HCM 6ª ed. para movimentos permissivos. **Não verifiquei o código nem as páginas do SUMO/HCM nesta
  sessão**; citadas só como origem conceitual, sem alegar equivalência.
- Contratos internos: `docs/TRAFFIC_CONTROL.md`, `docs/JUNCTION_GEOMETRY.md`, `docs/MICRO_MULTILANE_HANDOFF_2026_11_19.md`.

## Evidência

- Suíte completa: 1.125 testes passam e 1 falha, a mesma de antes, não relacionada (`SettingsRoutesHttpSpec`).
- `JunctionConflictSpec` (6 testes): exclusão com liberação única, prioridade sobre pedido posterior, desempate por idade,
  conectores sem regra inalterados, checkpoint JSON com ocupação e esperas, validação de configuração.
- Dois testes antigos de `NodeEventHandlerSpec` tiveram a expectativa atualizada porque o pedido pendente agora guarda `requestedTick`.
