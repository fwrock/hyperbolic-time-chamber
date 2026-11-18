# HTC MICRO: requisitos da avaliação e lacunas de implementação

Data: 18/11/2026. Estado: **plano de execução; validação empírica completa pendente**.

Este documento transforma a avaliação da campanha em trabalho verificável. Distingue
funcionalidades ausentes, capacidades existentes ainda não validadas e problemas de
dados ou método. As extensões descritas abaixo **não estão implementadas**. A referência
dos resultados executados é o [registro da campanha](MICRO_COMPLETE_EMPIRICAL_CAMPAIGN.md).

## 1. Veredito e escopo da afirmação

Há evidência favorável de consistência de execução e habilidade preditiva condicional
de seguimento. Não há evidência suficiente para afirmar que o modelo microscópico
completo reproduz empiricamente o tráfego autônomo de um corredor.

| Evidência existente | O que demonstra | O que continua aberto |
| --- | --- | --- |
| Benchmark retido de Lankershim: 28 episódios | Comparação descritiva HTC/SUMO contra observações previamente inspecionadas | Independência, controle autônomo e validade de rede |
| Peachtree prospectivo: nove seguimentos, zero filas elegíveis | Habilidade relativa de seguimento frente às referências simples nos casos selecionados | Metas congeladas de dez seguimentos e cinco filas não atingidas; resultado inconclusivo |
| Atores nativos: quatro veículos, sinais medidos, dois runtimes | Inicialização e sinais corretos; 1.586 eventos físicos comprometidos iguais, com 31 rollbacks no Time Warp | Dois cruzamentos antecipados em 6,9 s e 6,2 s; causa não isolada |
| Horizonte do ensaio de atores: 69,9 s | Encerramento e censura reportados | As quatro viagens têm `arrival=-1` e `reached_destination=false`; não são chegadas ao destino |

O escopo inicial é **Car/Link/Node/TrafficSignal, MICRO, rotas estáticas e relógio de
0,1 s**. Não estender a conclusão automaticamente a pedestres, transporte coletivo,
escolha de rota, MESO, segurança viária ou desempenho de uma cidade inteira.

## 2. Requisitos para uma avaliação defensável

| Etapa | Entrega necessária | Condição de encerramento |
| --- | --- | --- |
| E01 — Cenário observado | Geometria por faixa, linhas de retenção, conversões, sinais, estado inicial, demanda externa e condições a jusante | Fontes, unidades, relógios, cobertura e incertezas auditados; hipóteses explícitas |
| E02 — Diagnóstico da antecipação | Experimentos de desenvolvimento separando erro de entrada, contexto omitido e comportamento do modelo | Explicações candidatas confrontadas com evidência; diferenças residuais preservadas |
| E03 — Capacidades do modelo | Implementações G02–G06 exigidas pelos cenários escolhidos | Contratos físicos e de protocolo verificados, sem apenas remover restrições existentes |
| E04 — Calibração | Parâmetros relevantes, limites plausíveis, orçamento de busca e análise de sensibilidade | Ajuste restrito ao desenvolvimento; seleção do modelo concluída antes do teste final |
| E05 — Protocolo e reserva | Aplicação, métricas, tolerâncias, referências, sementes, tamanho da amostra e partição independente | Congelamento antes de acessar resultados individuais da reserva |
| E06 — Avaliação e conclusão | Resultados por comportamento, cobertura, falhas, censura e incerteza | Protocolo completo executado e reportado, mesmo se o resultado for desfavorável ou inconclusivo |

Definir as tolerâncias absolutas a partir de uma aplicação concreta. Elas ainda não
foram definidas; este documento não inventa limites de RMSE, vazão ou headway para
aprovar o HTC. As metas históricas de dez/cinco permanecem congeladas nos estudos
anteriores; não são justificativa automática de poder estatístico para a campanha nova.

Os dados já inspecionados são de desenvolvimento para as próximas mudanças. As sementes
não multiplicam o número de episódios independentes. A nova amostra deve considerar
variabilidade, diferença relevante para a aplicação, dependência entre episódios e
número de gravações/dias. Registrar essa decisão antes de abrir a reserva final.

## 3. O que já existe e deve ser preservado

- Física longitudinal Krauss, parâmetros opcionais por Car e aleatoriedade com semente.
- Mudança de faixa MOBIL e subconjunto nativo documentado de LC2013.
- Sinais por estágio, movimento e faixa; geometria finita por Links conectores.
- Lookahead e spillback limitados ao escopo documentado de faixa única.
- Admissão MICRO com fila, em configuração separada do footprint retido de spillback.
- Posição inicial e os campos opcionais `microDepartSpeed` e `microDepartLane`, já
  implementados e verificados antes da primeira admissão aceita.
- Relatos comuns de posição, entrada, saída, sinal e censura; códigos de medição e
  importação de sinais medidos, com evidência retida.

Esses componentes precisam de avaliação empírica nos regimes relevantes; sua presença
não significa aprovação. Não reimplementar MOBIL, LC2013 ou o controlador de estágios
apenas por falta de evidência empírica. Consultar [TRAFFIC_CONTROL](TRAFFIC_CONTROL.md),
[JUNCTION_GEOMETRY](JUNCTION_GEOMETRY.md), [MICRO_SPILLBACK](MICRO_SPILLBACK.md) e
[MICRO_CROSS_LINK_FOLLOWING](MICRO_CROSS_LINK_FOLLOWING.md).

## 4. Backlog de lacunas

As prioridades indicam sequência técnica, não limites de aceitação científica. Todos
os itens estão abertos; G01/G07 são principalmente preparação e instrumentação. Uma
implementação só pode ser marcada concluída com evidência dos critérios correspondentes.

| ID | Prioridade | Trabalho | Dependências |
| --- | --- | --- | --- |
| G01 | P0 | Reconstrução auditável do cenário completo e das condições de contorno | Fontes de geometria, controles e trajetórias |
| G02 | P0 | Observações causais por faixa e caminho a jusante | Contrato de mapeamento por faixa de G01 |
| G03 | P0 | Handoff físico, spillback multifaixa e combinação com filas de admissão | G02; conectividade física de G01 |
| G04 | P0 | Controle de áreas de conflito e prioridade na junção | G02/G03; configuração de conflitos de G01 |
| G05 | P0 | Aceitação de brechas e conversões permissivas | G02/G03/G04; regras de circulação verificadas |
| G06 | P1, quando exigido pela geometria | Continuidade em cadeias de links curtos | G01/G02/G03; auditoria das restrições de passo/remainder |
| G07 | P1 | Instrumentação e avaliação autônoma de todos os comportamentos | Definições de medição de E05; implementação incremental com G01–G06 |

### G01 — Cenário completo, sinais e condições de contorno

**Lacuna:** os arquivos recuperados ainda não formam um cenário completo auditado.
O CAD convertido tem `$INSUNITS=0`; o GIS contém polígonos de câmeras, não o grafo
de faixas. A fronteira de 43,75 m e o limite de 56,32704 km/h usados no ensaio são
hipóteses de desenvolvimento. A janela 600–1600 contém conversões omitidas pelo
cenário simplificado e foi corretamente recusada pelo gerador.

**Implementar na preparação de dados:**

1. Manifesto de geometria com fonte, unidade, transformação de coordenadas, faixas,
   comprimento físico, linha de retenção, conectores, restrições e incertezas.
2. Inventário de todos os participantes no instante inicial e de todas as entradas
   externas na janela. Distinguir entrada física no domínio de primeiro aparecimento
   por cobertura/oclusão; não tratar qualquer nascimento de track como demanda a montante.
3. Rotas ou movimentos fornecidos como entrada explícita, sem alegar validação de
   escolha de rota. Representar baia de esquerda, tráfego conflitante e bloqueio a jusante.
4. Auditoria de cobertura temporal dos sinais e da permissão legal de conversão.
   Preservar aspectos medidos; não interpretar seta vermelha isoladamente como toda
   a regra de passagem. O importador existente já evita inventar verdes.
5. Resolver a semântica dos tubos: valores declarados em veículos/hora em linhas de
   15 minutos. Não convertê-los em contagens ou vazões sem justificativa independente.

**Área responsável:** ferramentas em `scripts/scenario/`, com saída pelo escritor
canônico `tools/scenario-db-converter/scenario_output_writer.py` do workspace da
plataforma. Ampliar o gerador de aproximação ou criar um conversor coerente com esse
contrato; não alterar a física para compensar erro de geometria.

**Aceitação:** todos os sujeitos e entradas têm uma decisão registrada de inclusão,
exclusão por qualidade ou condição não suportada; nenhuma exclusão depende do erro
simulado. Fontes e transformações têm hashes; a geração é reproduzível. Comprimento
de conectores não é contado novamente no comprimento das vias.

### G02 — Fronteiras causais por faixa

**Lacuna confirmada:** `LinkFollowingHandler` publica um único veículo traseiro mais
próximo entre todas as faixas. `LinkState` exige uma faixa para `downstreamLookahead`.
O contrato atual não representa caminhos multifaixa de forma geral.

**Implementar:** configuração tipada e não ambígua de faixa de origem → faixa do
conector → faixa de destino; observações imutáveis por faixa; seleção do líder físico
no caminho correto. A mudança de faixa deve verificar também o líder a jusante da
faixa candidata, e não reutilizar apenas o líder da faixa atual. Separar o veículo
traseiro necessário ao spillback do veículo dianteiro necessário à avaliação de
aproximação prioritária; um único resumo não atende automaticamente aos dois usos.

**Estado:** assinaturas, destinos solicitados, sequência de publicação e históricos
por origem/faixa devem sobreviver a checkpoint, rollback e migração suportada.
Para o controle no Node, projetar endereçamento por identidade e shard: a assinatura
atual encaminha publicações para `hybrid.actor.Link` e não pode ser reutilizada para
Node sem adaptação explícita.

**Aceitação:** ocupação em uma faixa não bloqueia incorretamente outra; líder real
não desaparece por escolha de faixa; conector vazio permite observar a via seguinte.
A física em t usa observações estritamente anteriores a t, mantendo os dois grupos
de ticks e a última sequência de cada grupo. Receber observação não republica nem
agenda trabalho. Ausência de observação válida não significa via livre.

**Pontos de alteração:** [LinkFollowingHandler](../src/main/scala/model/hybrid/support/link/LinkFollowingHandler.scala),
[MicroBoundarySnapshot](../src/main/scala/model/hybrid/entity/state/model/MicroBoundarySnapshot.scala),
[LinkState](../src/main/scala/model/hybrid/entity/state/LinkState.scala) e estratégias
de mudança de faixa. Expandir os testes existentes de fronteiras causais.

### G03 — Handoff, spillback multifaixa e filas de admissão

**Lacuna confirmada:** `microEntry.enabled` com `downstreamSpillback` é rejeitado.
O footprint retido atual cobre faixa única; não há combinação geral com filas de
entrada, múltiplos movimentos e escolha de faixa a jusante.

**Implementar:** transferência explícita com pedido, reserva compatível, admissão
efetiva e liberação. Enquanto a admissão não ocorreu, manter a ocupação física a
montante, sem integrar o veículo retido nem emitir outra saída. O Link de destino
continua responsável por decidir espaço e faixa reais; o Car conserva a identidade
da transferência. A reserva de capacidade do Node não equivale à aceitação física
do veículo pelo Link seguinte.

Guardar no estado as obrigações pendentes, identidade/seq do pedido, faixa escolhida,
espera física e liberação já realizada. O veículo não pode contar como duas entidades
em métricas de acumulação; um obstáculo retido para continuidade não é outra viagem.
Uma espera real limpa a velocidade/remainder conforme o contrato existente.

**Aceitação:** jusante saturado, admissões concorrentes, fila de origem e movimento
de junção usam o mesmo espaço sem sobreposição, desaparecimento ou dupla liberação.
Validar mensagens repetidas, cancelamento e replay antes/depois do ACK, retomada após
liberação e horizonte com transferência pendente. As restrições atuais só são
relaxadas para combinações explicitamente implementadas e verificadas.

**Pontos de alteração:** [LinkVehicleFlowHandler](../src/main/scala/model/hybrid/support/link/LinkVehicleFlowHandler.scala),
[LinkMicroSimulationHandler](../src/main/scala/model/hybrid/support/link/LinkMicroSimulationHandler.scala),
[CarMicroHandler](../src/main/scala/model/hybrid/support/car/CarMicroHandler.scala),
[CarJourneyState](../src/main/scala/model/hybrid/entity/state/CarJourneyState.scala),
Node e seus estados/eventos de admissão.

### G04 — Conflitos e prioridade na junção

**Lacuna confirmada:** o Node possui seleção de conectores, sinais e capacidade;
esses mecanismos não impedem, por si, ocupação simultânea de conectores conflitantes
nem implementam prioridade geral de movimentos.

**Implementar:** configuração tipada de áreas de conflito e movimentos compatíveis,
precedência e desempate explícitos; handler sem estado próprio delegado pelo Node.
Pedidos pendentes e reservas pertencem ao estado checkpointado do Node, preservando
a identidade da resposta original. A configuração precisa indicar a origem das
regras; não deduzir prioridade apenas da ordem numérica de IDs.

Uma autorização precisa respeitar sinal/permissão, conflito e espaço de recepção.
Movimentos não conflitantes devem poder ocorrer em paralelo. Definir como formar a
decisão com pedidos no mesmo tick sem depender arbitrariamente da ordem da mailbox;
usar os contratos de causalidade/accounting existentes. Não criar um protocolo novo
de rodadas do Time Manager para resolver a decisão de tráfego.

**Aceitação:** movimentos conflitantes não recebem ocupação incompatível; prioridades
e desempates são reproduzíveis nos contratos declarados; reserva não é liberada por
um tempo nominal de fluxo livre quando o conector ainda está ocupado. Liberação,
recusa, cancelamento, rollback e restauração conservam cada obrigação de resposta.
`signalAlreadyCleared` não deve contornar a nova verificação de conflitos/capacidade.

**Pontos de alteração:** [NodeEventHandler](../src/main/scala/model/hybrid/support/node/NodeEventHandler.scala),
[NodeState](../src/main/scala/model/hybrid/entity/state/NodeState.scala), tipos de
movimento/evento e novo handler de controle de junção, com nome a definir na implementação.

### G05 — Aceitação de brechas e conversões permissivas

**Lacuna:** exclusão mútua de conectores não é aceitação de brechas. É preciso
considerar veículos prioritários que ainda estão se aproximando e o tempo/espaço
necessário para ocupar e liberar as áreas de conflito.

**Implementar:** política de aceitação com observações causais das aproximações,
percurso de conflito, velocidade e dimensões relevantes; parâmetros físicos com
unidades e limites documentados, estimados no desenvolvimento. Explicitar o tratamento
de veículos parados, dados desconhecidos, cessão de passagem e regras de parada.
Não impor entrada insegura para resolver espera longa.

Separar aspecto semafórico observado, permissão operacional protegida/permissiva e
decisão de brecha. Em Peachtree há conversões observadas sob seta vermelha e verde
direto; isso exige auditoria da regra e do tráfego oposto, não prova de que toda
manobra observada seja legal. Preservar os dados brutos de sinal no relatório.

**Aceitação:** o veículo permanece fisicamente antes da entrada do conflito enquanto
aguarda. Aproximação prioritária impede brecha insuficiente; brecha admissível permite
progresso após espaço disponível. Observação desconhecida não libera passagem.
O controle não depende de observação futura nem de posição interna corrigida com
ground truth. A decisão física e sua agenda devem sobreviver a rollback e checkpoint.

**Pontos de alteração:** controlador proposto de G04, observações de G02,
[SignalProgram](../src/main/scala/model/hybrid/entity/state/model/SignalProgram.scala),
[SignalProgramHandler](../src/main/scala/model/hybrid/support/trafficsignal/SignalProgramHandler.scala)
quando necessário para distinguir permissões e integração de footprint de G03.
Não criar uma nova enumeração sem antes definir seu contrato nos consumidores.

### G06 — Cadeias curtas e continuidade física

**Lacuna de escopo:** o lookahead atual alcança um conector opcional e a próxima via.
O transporte de remainder tem restrições de passo e comprimento. Uma fila pode
propagar-se por mais de uma fronteira em geometrias que esse escopo não representa.

**Implementar quando a geometria auditada exigir:** alcance físico suficiente ao longo
do caminho, sem duplicar distâncias, autorizações ou espaço; passagem por fronteiras
curtas respeitando o intervalo de integração e a ordem causal. Não alongar vias ou
reduzir o passo silenciosamente para fazer um cenário suportado parecer equivalente
ao observado. Se houver discretização alternativa, registrá-la e avaliar convergência.

**Aceitação:** cadeia com links curtos, bloqueio além da primeira via, conector ocupado
e velocidades distintas conserva distância e continuidade, sem atravessar obstáculo.
Não retirar validações de comprimento/remainder antes dessas verificações.

### G07 — Instrumentação e avaliação autônoma

**Lacuna:** os relatos comuns existem, mas ainda falta uma campanha completa de
medição por faixa/movimento e de decisões de junção. A instrumentação de colisões dos
adaptadores HTC e SUMO não é equivalente; ausência de evento não significa segurança.

**Implementar/ampliar:** extratores e, onde necessário, eventos via `report()` para
pedidos, espera, autorização, admissão efetiva, liberação de conflito e motivo da decisão.
Preservar IDs, tick bruto, tempo físico, faixa, movimento e identidade da transferência.
Não escrever diretamente nos sinks a partir dos atores.

| Comportamento | Medidas necessárias |
| --- | --- |
| Seguimento | Posição, velocidade pelo mesmo operador causal, espaçamento e cobertura |
| Sinal e descarga | Linha de retenção verificada, primeira partida, tempo perdido e headways |
| Filas | Comprimento/veículos parados por faixa, duração e acumulação; definição de parada congelada |
| Mudança de faixa | Ocorrência, instante, posição, conclusão e brechas, por movimento |
| Junção | Entrada/saída de conflitos, cessão, brechas aceitas/rejeitadas e tempo de travessia |
| Spillback | Ocupação, bloqueio, descarga e propagação através das fronteiras |
| Corredor | Fluxos por janela/estação, distribuição de viagens, congestionamento e censura |

**Aceitação:** rastrear todos os sujeitos e conservar predições ausentes, cruzamentos
não realizados, sujeitos ainda em fila e viagens censuradas. Não inventar chegada nem
distância de link não concluído. Separar campos internos do modelo de observáveis
medidos. As diferenças de instrumentação de segurança continuam explícitas até
existir uma definição e implementação comparável.

## 5. Diagnóstico obrigatório antes de calibrar a descarga

Executar E02 em dados já expostos, com alterações controladas e todas as versões retidas:

1. Confirmar transformação, posição física da linha de retenção e significado do ponto
   frontal das trajetórias. Comparar o mesmo local físico em observação e simulação.
2. Auditar veículos iniciais e entradas conflitantes, baia de esquerda e bloqueio a jusante.
   Verificar se o primeiro aparecimento de cada track é realmente uma entrada no domínio.
3. Reproduzir o sinal no mesmo relógio: a mudança no tick t afeta o próximo intervalo;
   a física do tick t integra (t-1, t]. Evitar deslocamentos artificiais de fase.
4. Reexecutar com entradas auditadas e medir posição de parada, instante de partida,
   aceleração e cruzamento. Separar erro de estação de erro temporal de descarga.
5. Somente após esse diagnóstico considerar parâmetros comportamentais ou um mecanismo
   de resposta de partida ausente. Hoje não está demonstrado que falta um tempo de reação
   específico ao verde; adicionar um atraso de 6–7 s para encaixar dois casos seria ajuste
   sem identificação causal suficiente.

Preservar o resultado original de fronteira absorvente e os quatro sujeitos. Um novo
operador de medição deve receber nova versão; sua melhora não é automaticamente melhora
do modelo. O limite de velocidade de Peachtree permanece hipótese até verificação.

## 6. Contratos arquiteturais obrigatórios

Aplicam-se [AGENTS.md](../AGENTS.md) e [CODE_STYLE.md](CODE_STYLE.md):

- Atores coordenam; handlers são sem estado próprio, com callbacks. Física e política
  pertencem aos pacotes de domínio existentes, sem simulação paralela fora do engine.
- Estado necessário após rollback/migração suportada, sobretudo respostas pendentes,
  deve estar no estado persistível ou nos hooks explícitos já verificados; sem ActorRef
  em estado. Não presumir suporte a migração Time Warp ou recuperação independente.
- Usar `sendMessageTo` e `requestInteraction`/`replyInteraction` para os respectivos
  contratos. Cada obrigação de resposta precisa sobreviver a todas as alternativas.
  Observação sem resposta não deve criar uma espera de negócio.
- Cada evento espontâneo termina pelo contrato existente. Decisões futuras de tráfego
  usam tempo de simulação e agenda checkpointada; não usar relógio de parede, bloqueio,
  watchdog ou timeout para ocultar falhas de protocolo.
- Manter snapshots estritamente anteriores à integração, identidade de cancelamento,
  sequência, tratamento de publicações positivas tardias e histórico necessário ao replay.
- JSON numérico, mapas com chaves tipadas e codecs Kryo independentes precisam de
  verificação. Não reordenar registros explícitos de serialização nem misturar versões
  de JAR/configuração entre JVMs.
- Novas opções são explícitas; entradas legadas conservam o comportamento documentado.
  Rejeitar combinações ainda não suportadas com mensagem explicativa.
- O hook de horizonte é apenas de relatório; não enviar mensagens do modelo nem
  concluir movimentos fictícios para limpar filas ou reservas.

## 7. Matriz de verificação por implementação

| Verificação | Casos mínimos de engenharia |
| --- | --- |
| Lógica/física | Espaço livre e saturado; movimentos compatíveis e conflitantes; faixas distintas; prioridade e empate |
| Protocolo | Admissão recusada/pendente; ACK atrasado/repetido; cancelamento antes/depois da autorização; liberação única |
| Estado/codec | JSON legado e novo, números inteiros/decimais, chaves tipadas, codecs frios independentes e restauração de obrigações |
| Tempo | Limites de tick, fases no mesmo tick, observações atuais versus anteriores, estado parado e retomada |
| Otimista | Rollback antes/depois de reserva, admissão e liberação; comparação de relatos comprometidos |
| Distribuído | Uma e duas JVMs, transporte com atraso e configuração/relógio idênticos, nos modos efetivamente suportados |
| Horizonte | Veículo na origem, no sinal, aguardando conflito e em transferência; todos censurados quando inacabados |
| Compatibilidade | Casos de faixa única e entradas legadas continuam respeitando seus contratos |

Executar cenários reais no conservativo de referência `channel-closed` e no Time Warp.
Avaliar `direct` separadamente quando fizer parte do escopo, respeitando seu contrato
de ordem para entradas independentes no mesmo tick. Igualdade byte a byte entre
layouts só é critério quando a política de decisão e o cenário exigirem esse resultado;
não confundir variação admissível com erro nem usar a mailbox como regra de prioridade.

Esses testes demonstram correção de engenharia. Depois deles ainda é necessária a
comparação com observações independentes para afirmar validade empírica.

## 8. Ordem de execução e definição de concluído

1. G01 + E02: fechar os dados da aproximação e explicar a diferença observada antes de
   ajustar a descarga. Produzir inventário de cobertura, mapa auditado e diagnóstico.
2. G02: estabelecer o contrato de observação por faixa, incluindo aproximações e
   suporte ao endereçamento do controlador, preservando o caso legado.
3. G03/G04: desenvolver handoff e controlador de conflitos como contratos integrados;
   os componentes podem ter testes isolados, mas a aceitação exige a integração física.
4. G05: acrescentar brechas e permissões após confirmar a base de ocupação e prioridade.
   G06 entra nesta fase se o mapa contiver cadeias que excedam o escopo atual.
5. G07 desde o início; encerrar a instrumentação antes de congelar o protocolo final.
6. E04/E05/E06: calibrar, reservar/congelar e avaliar todos os comportamentos exigidos.

Um item implementado exige revisão dos contratos, testes aplicáveis e um cenário
executado; um comportamento empiricamente validado exige também dados independentes,
adequação amostral, cobertura e critérios predefinidos. Registrar separadamente
`implementado`, `verificado em engenharia` e `avaliado empiricamente`.

O relatório final deve aceitar os três desfechos: favorável, desfavorável ou inconclusivo.
Concluir a campanha é executar e reportar seu escopo integral, não obter aprovação a
qualquer custo. MESO tem [campanha própria](MESO_EMPIRICAL_VALIDATION_PLAN.md); interferência
de pedestres/ciclistas exige modelagem explícita ou limitação declarada do cenário.

## 9. Evidências e documentos relacionados

- [Registro completo da campanha](MICRO_COMPLETE_EMPIRICAL_CAMPAIGN.md).
- [Plano empírico MICRO](MICRO_EMPIRICAL_VALIDATION_PLAN.md) e
  [estado consolidado](MICRO_VALIDATION_STATUS.md).
- [Estudo prospectivo Peachtree](MICRO_PEACHTREE_PROSPECTIVE_STUDY_2026_11_18.md).
- [Diagnóstico dos quatro veículos](validation/micro-complete-20261118-actor-diagnostics.json),
  [resultados de runtime](validation/micro-complete-20261118-runtime-results.json) e
  [auditoria de conversões à esquerda](validation/micro-complete-20261118-left-turn-audit.json).
- Relatórios PT/EN e arquivos tabulares no workspace da plataforma:
  `output/pdf/htc-micro-empirical-20261118/`. A versão publicada anterior deste relatório
  continua sendo um retrato das evidências; este plano não altera seus resultados.

Esta alteração é documental. Nenhuma lacuna foi marcada como implementada e nenhum
experimento ou critério congelado foi modificado.
