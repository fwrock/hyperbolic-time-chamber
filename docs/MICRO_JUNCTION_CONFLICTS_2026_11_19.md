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

# G05 (primeira parte) — aceitação de brechas com observação causal

Estado: implementado, testado em nível de Node e executado com atores Node e Link reais em um cenário sintético (seção final).

## O que foi implementado

- `GapAcceptance(criticalGapSeconds, conflictOffsetM, assumedAccelerationMps2)` dentro de `JunctionConflict` (exige conector com
  prioridade). Todos os valores são parâmetros explícitos com unidade; **nenhum é calibrado** e nenhum tem valor padrão afirmado
  como empírico (a aceleração padrão 2,6 m/s² é a mesma suposição do Car nativo).
- O Node assina, uma vez e com a assinatura em checkpoint (`observationsRequested`), as publicações de fronteira da via de aproximação
  do movimento prioritário, reaproveitando o canal de G02 (`SubscribeMicroBoundaryData`, assinante Node). As observações ficam
  em `boundaryObservations` (duas coortes de ticks, como nos Links) e só se usa uma observação **estritamente anterior ao tick atual**.
- Regra de decisão para o conector que cede: para o veículo da frente de cada faixa da aproximação prioritária (pode ser filtrada
  pela faixa do movimento), estima-se a chegada mais cedo ao ponto de conflito com aceleração constante a partir da velocidade
  observada: `t = (-v + sqrt(v² + 2 a d)) / a`, `d = (L − x) + offset`. O tempo decorrido desde a observação é creditado ao veículo
  que se aproxima. A passagem só é concedida se `t − decorrido ≥ criticalGapSeconds` para todos.
- **Observação desconhecida não libera a passagem** (sem snapshot, o pedido espera). Um veículo prioritário já parado na linha
  (`pendingExit`) não conta como ameaça de brecha: o pedido dele é ordenado pela fila e excluído pela ocupação.
- Cada publicação recebida reavalia os pedidos pendentes interessados (`drainConflictGroup`); sem observação nova não há reavaliação
  extra. O movimento prioritário nunca passa pelo teste de brecha. Sem `gapAcceptance` o comportamento é o do G04.

## Custo

Sem `gapAcceptance` configurado, nada muda. Com ele, o Node recebe uma publicação por tick da aproximação prioritária (o mesmo
volume que a assinatura entre Links) e avalia só quando há pedido pendente do movimento que cede. Não medido.

## Limitações conhecidas

- **Só o veículo da frente de cada faixa** é testado (a publicação traz traseiro e dianteiro por faixa). É conservador para
  chegadas atrás de um veículo que vira para outro lado, mas ignora o tempo de passagem do próprio veículo que cede.
- **Pelotão que cede:** vários pedidos do mesmo movimento são liberados juntos por uma única brecha observada, sem exigir brecha
  maior para os seguidores (teste documenta). O limite prático hoje é a capacidade do conector.
- O Node não publica a permissão ao Link de aproximação: o veículo que cede só espera depois de chegar ao fim do Link, sem
  antecipar a frenagem (ver G04). Para antecipar, a permissão do movimento teria de ser publicada como aspecto, como os sinais.
- Chegada estimada com aceleração constante e sem teto de velocidade; não há modelo de veículo parado em fila a montante do prioritário.
- Valores de brecha crítica, deslocamento ao ponto de conflito e a regra de prioridade têm de vir de fonte declarada
  (`source`); nada gera esses parâmetros a partir de dados ainda.
- Não verifiquei o HCM nem a documentação do SUMO nesta sessão. A forma "tempo até o ponto de conflito contra brecha crítica" é o
  conceito geral de aceitação de brechas; não afirmo equivalência com nenhum simulador.

## Evidência

- Suíte completa: 1.131 testes passam e 1 falha, a mesma de antes e sem relação (`SettingsRoutesHttpSpec`).
- `JunctionConflictSpec` (12 testes no total): desconhecido espera e a assinatura é única; veículo próximo segura e a via livre libera;
  tempo decorrido é creditado; veículo parado na linha e movimento prioritário são tratados como descrito; checkpoint com
  observações e assinatura sem reassinar; validação dos parâmetros.


# Cenário integrado com atores reais (G03 + G04 + G05)

`scripts/scenario/micro_junction_conflict_fixture.py` e `check_micro_junction_conflict.py`. Cruzamento sem sinal com dois Links de
aproximação, dois conectores e duas vias de saída; o movimento prioritário P (11 → conector 30 → 12) e o que cede Y
(21 → conector 20 → 22) têm caminhos que se cruzam. Regra: prioridade para o 30, brecha crítica 3,0 s, deslocamento ao ponto de
conflito 8 m, aproximações com lookahead e spillback (G03). Carga: 6 carros prioritários (um a cada 8 s) e 6 que cedem. **Os valores
de brecha e de deslocamento são parâmetros de engenharia do fixture, não calibração nem dado empírico.**

| Verificação | Resultado |
| --- | --- |
| Atores Node e Link reais; conservativo e Time Warp; 1 e 2 JVMs | 4 execuções, 1.368 eventos físicos comprometidos **idênticos** |
| 12 viagens completas, cada carro entra em suas 3 vias | passa |
| Ocupações dos conectores conflitantes (entrada no conector até entrada na via de saída) nunca se sobrepõem | passa; **o controle sem regra tem 1 sobreposição** |
| Cada carro que cede entra no conector pelo menos 24,2 ticks antes da próxima entrada prioritária (3,0 s menos o tempo de 8 m a 50 km/h) | passa; folgas observadas entre 38 e 66 ticks; controle falha |
| Algum carro que cede espera na linha (≥ 10 ticks de retenção) | passa; controle falha |

O controle (`--no-conflict`, mesmo cenário sem regra no Node) falha exatamente nas três verificações de conflito, o que mostra que o
verificador detecta a ausência do mecanismo. Não há jar anterior para comparar: o código novo é o único que aceita a configuração.

Limites desta evidência: um cruzamento, um movimento que cede, tráfego leve (8 s entre prioritários), sem sinal, sem esquerda de
verdade (o conector é uma reta), sem frenagem antecipada, e os carros que cedem em pelotão passam juntos pela mesma brecha (dois
carros entraram no mesmo intervalo). Os jars incluem as alterações de settings/MESO do workspace
(`experiments/claude-g05/engine.jar`, sha256 `9de16f31…`).


## Ajuste posterior (mesmo dia): prioridade em vermelho não é ameaça de brecha

Para esquerdas permissivas contra um movimento prioritário com sinal, o G05 original tratava um veículo prioritário parado no vermelho como se fosse arrancar
(chegada pela aceleração a partir do repouso) e bloquearia a esquerda durante todo o vermelho. Agora, **se o movimento prioritário está em Vermelho no Node, sua
aproximação não é ameaça de brecha**; Amarelo continua contando, e aspecto desconhecido é tratado como ameaça. Quando o sinal do movimento prioritário muda,
os pedidos que cedem a ele são reavaliados (`drainYieldersOfOrigin`). Dois testes novos em `JunctionConflictSpec`. Limite: a decisão usa o aspecto do Node no
momento da avaliação; um veículo prioritário que já cruzou no amarelo continua sendo contabilizado pela ocupação do conector.


## Ajuste posterior (mesmo dia): brecha de seguimento para pelotões

`GapAcceptance.followUpSeconds` (padrão 0, sem mudar o comportamento anterior): cada veículo do movimento que cede já admitido e ainda não liberado
(`occupiedConnectors` do conector que cede) soma `followUpSeconds` à brecha crítica exigida do próximo. Com 0 uma única brecha libera a fila inteira (o limite documentado antes);
com valor positivo o k-ésimo seguidor exige `crítica + k × seguimento`. Dois testes novos em `JunctionConflictSpec` (a observação que libera o seguidor precisa ser estritamente
anterior ao tick de decisão, como em todo o G05). O valor de `followUpSeconds` não tem fonte empírica nesta rodada; fica 0 nos cenários dos relatórios, e o parâmetro existe para estudo de sensibilidade.


## Frenagem antecipada (opt-in `publishPermission`)

Antes, o veículo que cede só descobria que não podia entrar ao chegar ao fim do Link (parava na linha de forma geométrica, não por frenagem). Com `GapAcceptance.publishPermission = true`, o Node avisa a via de
aproximação, **somente quando a permissão muda**, se um veículo que chegasse agora seria liberado (nenhum conector conflitante ocupado e a brecha aceita). O aviso usa o mesmo caminho do sinal
(`LinkSignalStateData` com o destino e as faixas do movimento), com o aspecto **Vermelho** quando negado, e o aspecto do próprio sinal do movimento quando permitido; as publicações de sinal do Node passam a vir já combinadas
com a permissão (E lógico), para que o sinal verde não desfaça uma negação. A permissão é reavaliada quando chega uma observação, quando a ocupação de um conector muda e quando um movimento prioritário muda de sinal.

**Custo:** padrão desligado (nenhuma mudança onde não for pedido). Ligado, há uma mensagem Node-Link por mudança de permissão de um movimento que cede (não por tick); nenhum uso do Time Manager. **Limite:** a permissão é "agora" e
não prevê a chegada do veículo prioritário durante a frenagem; a decisão final de entrada continua no pedido de acesso. Dois testes em `JunctionConflictSpec` (publicação só na mudança, E lógico com o sinal próprio, desligado por padrão).


## Desempate de pedidos no mesmo tick (opt-in `decisionBatching`)

Sem esta opção, o primeiro pedido que chega ao Node no tick é decidido na hora, então dois pedidos do mesmo tick dão resultados diferentes conforme a ordem de entrega (o teste `Batched decisions` mostra os dois casos). Com
`JunctionConflict.decisionBatching = true`, os pedidos das conexões dessa regra são **sempre guardados** (resposta de espera imediata, como na fila de capacidade) e o Node agenda **um único acordar no tick seguinte**; nesse tick (`onDecisionTick`) ele
decide sobre o conjunto dos pedidos recebidos **até o tick anterior**, em ordem determinística (prioridade, depois idade, identidade e sequência), reavaliando a ocupação a cada concessão. Pedidos que chegam no próprio tick da decisão não participam dela. Observações,
mudanças de ocupação e de sinal não concedem na hora: só pedem o acordar do tick seguinte. Resultado: a decisão não depende da ordem de entrega dos pedidos do mesmo tick.

**Custo:** padrão desligado. Ligado, há no máximo um evento próprio do Node por tick com pedidos em conflito pendentes (o mesmo mecanismo de agendamento que os Links já usam), nenhuma mensagem nova entre atores, e **um tick (0,1 s) de latência** nas concessões dos movimentos em conflito. Três testes novos em
`JunctionConflictSpec` (independência da ordem, pedido no tick da decisão fora do conjunto, checkpoint com a decisão agendada e conector sem regra imediato). **Não** foi avaliado em execução de ponta a ponta nesta rodada; só em unidade.
