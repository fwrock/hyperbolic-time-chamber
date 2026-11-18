# Passagem para Claude — validação empírica MICRO do HTC

Data: 18/11/2026. Workspace: `/home/dean/workspace/phd/htc-platform`.
Repositório do motor: `hyperbolic-time-chamber`, branch `main`.
Última implementação publicada nesta passagem: **`2b6fab6`** (`feat(micro): restore measured snapshots and publish lane boundaries`).

## Pedido do usuário e situação real

O usuário quer **uma validação empírica completa e defensável do modelo microscópico**, começando por NGSIM, incluindo implementar os mecanismos ausentes segundo a arquitetura do HTC, executar os experimentos e produzir relatórios completos em português e inglês. Já autorizou commit/push e início dos experimentos. Pediu esta documentação para continuar com Claude; esta passagem não inicia novas implementações.

A campanha completa **não foi concluída**. Há resultados condicionais de following/filas, um estudo prospectivo inconclusivo, um ensaio autônomo pequeno com sinais observados e incrementos de engenharia executados. Nenhum deles permite declarar o modelo microscópico inteiro empiricamente validado. O usuário já contestou repetidamente entregas parciais apresentadas como suficientes. A continuidade deve implementar, testar, executar e registrar as lacunas, sem encerrar apenas com mais um plano.

## Ordem de leitura

1. [AGENTS.md](../AGENTS.md) e [CODE_STYLE.md](CODE_STYLE.md): contratos atuais. O [CLAUDE.md](../CLAUDE.md) existente é orientação geral mais antiga; contém exemplos de versão/JAR e MICRO que não representam todo o estado atual. Conferir AGENTS e código antes de reutilizá-los.
2. [Lacunas e critérios E01–E06/G01–G07](MICRO_EMPIRICAL_GAPS_AND_ACCEPTANCE.pt.md): backlog completo, dependências, decisões e aceitação.
3. [Incremento executado de reconstrução e snapshot](MICRO_RECONSTRUCTION_AND_SNAPSHOT_2026_11_18.md): estado mais recente, resultados, limites e falhas.
4. [Campanha completa](MICRO_COMPLETE_EMPIRICAL_CAMPAIGN.md), [plano empírico](MICRO_EMPIRICAL_VALIDATION_PLAN.md) e [estudo prospectivo Peachtree](MICRO_PEACHTREE_PROSPECTIVE_STUDY_2026_11_18.md).
5. [Following entre links](MICRO_CROSS_LINK_FOLLOWING.md), [spillback](MICRO_SPILLBACK.md), [geometria de junções](JUNCTION_GEOMETRY.md) e [controle de tráfego](TRAFFIC_CONTROL.md).

Os documentos de status e KNOWN_GAPS têm seções históricas; resolver divergências com o incremento mais recente, o código e os artefatos executados.

## O que foi implementado

### Condições iniciais e restauração de cena

Em `fc1377d`, `CarState.microDepartSpeed` (m/s) e `microDepartLane` (índice nativo zero-based) passaram a preservar velocidade/faixa da primeira admissão. O estado efetivamente admitido retorna no ACK. A configuração inicial não deve reaplicar-se em links posteriores. Posição inicial já existia.

Em `2b6fab6`, `MicroEntryConfig.initialSnapshotTick` permite restauração explícita de posições medidas, com admissão enfileirada:

```json
{"microEntry":{"enabled":true,"initialSnapshotTick":0}}
```

`MicroEntryAdmission` valida ocupação física à frente e atrás, tamanho finito positivo, faixa e ausência de remainder. `LinkVehicleFlowHandler` preserva a posição quando esse snapshot é válido. Sobreposição real ou restauração tardia falham com diagnóstico; não se move nem adia silenciosamente o veículo medido. Gap desejado e sobreposição física são conceitos diferentes. A admissão ordinária posterior conserva seu comportamento. Não há barreira global de inicialização ou nova rodada com Time Manager.

O gerador `scripts/scenario/ngsim_actor_approach.py` tem `--initial-snapshot`, `--stop-line-gap-m` e `--require-observed-boundary-arrivals`. O último recusa nascimentos internos/lacunas incompatíveis com a alegação de entrada observada; não certifica sozinho a reconstrução física inteira.

### Publicação por faixa: fundamento parcial de G02

`MicroBoundarySnapshot` acrescenta `laneCount` e `laneBoundaries`; `MicroLaneBoundary` contém ocupação traseira/dianteira por faixa, vazio explícito e footprints/destinos. Payload legado sem a lista significa detalhe desconhecido, não todas as faixas vazias. `MicroBoundaryVehicle` acrescenta `nextLinkId` e `pendingExit`.

`MicroBoundarySubscriber` e `MicroFollowingState.otherSubscribers` suportam identidade de Node além dos Links legados. IDs iguais em shards diferentes permanecem distintos. `LinkFollowingHandler` normaliza nomes completos de classe com o utilitário existente; `Link` passa o shard do remetente e interna strings restauradas. Usa transporte normal sem esperar observadores.

**Ainda não há seleção/consumo de caminhos multifaixa, segurança da faixa candidata a jusante ou consumidor de conflitos no Node.** O indicador de suporte e o consumidor longitudinal continuam limitados a uma faixa. Não remover os guards de lookahead/spillback.

## Evidências executadas e interpretação

### Estudos anteriores: conservar os resultados e os limites

- Lankershim: 28 episódios (21 following, sete filas), com subconjunto primário v2 de oito following/quatro filas. RMSE de posição determinístico HTC 1,98020 m e SUMO 2,01172 m; MAE de headway de fila 0,46303/0,46098 s. Cinco sementes 42–46: posição 1,91237/1,94750 m e headway 0,42498/0,42465 s. São evidências condicionais em dados já inspecionados, não aceitação independente de uma rede autônoma. Todos os resultados/falhas devem permanecer no conjunto.
- Peachtree prospectivo v3: 457.412 linhas, 1.222 veículos; nove episódios following contra meta congelada de dez; zero filas contra meta de cinco. **Conclusão inconclusiva**, apesar de comparações favoráveis: posição determinística 1,82685 m e velocidade causal 0,70674 m/s; posição estocástica média 1,77418 m. Não reduzir metas nem converter diagnósticos posteriores em novas filas elegíveis.
- O código/protocolo prospectivo `a1d3e15f03961a696d439f670a7f9d1fbcbe9483` foi publicado antes da aquisição reservada. A gravação PM inteira já foi exposta posteriormente. Reservar um conjunto realmente não inspecionado antes da avaliação final.

### Primeiro ensaio com atores e sinal observado

Em `experiments/micro-complete-20261118/`, quatro sujeitos 75/95/222/224, frames 600–1299, 69,9 s, duas faixas, aproximação 43,75 m e sink vazio de 300 m. Velocidade máxima 56,32704 km/h é hipótese ainda não verificada. Troca de faixa desativada; não há interseção finita completa. O caminhão 75 usa tamanho observado, mas dinâmica de Car.

Foram 1.293 amostras observadas da aproximação sem predições faltantes e condições iniciais exatas para os quatro sujeitos. Conservativo e Time Warp tiveram os mesmos 1.586 eventos físicos comprometidos; Time Warp registrou 31 rollbacks. As quatro viagens ficaram censuradas no horizonte (`arrival=-1`), sem chegada ao destino. RMSE de progresso com fronteira absorvente: 5,08156 m; erros de cruzamento -6,9/-6,2/-0,9/-1,4 s. Conclusão de engine no horizonte não significa viagens completas.

### Reconstrução e diagnóstico atuais

Raiz local: `experiments/micro-reconstruction-20261118/`.

- Ortofoto oficial recuperada com georreferenciamento e hashes; não foi adquirida trajetória nova neste incremento. Imagem 2.823×12.167, 34.396.304 bytes. A linha pintada foi interpretada manualmente, parcialmente oculta; não é levantamento topográfico. Fonte USDOT/FHWA NGSIM, DOI 10.21949/1504477, catálogo `8ect-6jqj`; manter atribuição/licença dos derivados.
- O registro global→local da gravação PM fornece estações aproximadas 38,774/38,987 m nas faixas diretas. Resíduo longitudinal ~0,143 m mede consistência do registro, não precisão independente da linha. A mesma feição tem deslocamento local AM–PM de aproximadamente -5,273 m. Não transferir estações locais entre sessões.
- Frames 600–1600: 16 sujeitos; dois iniciais e **14 tracks começam já dentro da aproximação**. Não representam automaticamente demanda medida na fronteira. Isso inclui entradas tardias do ensaio menor.
- Sensibilidade geométrica com protocolo anterior às execuções, mesmo JAR antigo e sem calibração de tráfego: parada central 38,880 m, com ±0,762 m fixos; fronteira de pontuação manteve 43,75 m. RMSE centro/montante/jusante: 1,74295/1,63711/2,02528 m. Erros 75/95 no centro: -5,1/-4,4 s. Centro conservativo/Time Warp iguais. Não escolher a variante de menor erro como calibração.
- A geometria explica parte do erro e ~1,8 s do cruzamento inicial; sobra discrepância de 4–5 s. Auditoria de todos os nove verdes em duas faixas: 18 situações retidas, 12 candidatos parados, cinco não parados, uma sem candidato. Operador causal de velocidade de um segundo, cinco amostras consecutivas acima de 0,5 m/s: atrasos de 3,3–8,0 s, mediana 5,5 s; limiares alternativos retidos.
- Comparação causal do mesmo operador: sujeito 75 observado 6,1 s após verde versus nativo 0,7 s; sujeito 95, 5,3 versus 0,7 s. **Não é identificação causal de tempo de reação.** Sincronização independente com vídeo, contexto/conflitos e veículo ainda precisam ser separados. Não acrescentar atraso arbitrário de cinco segundos ou deslocar sinais para melhorar dois sujeitos.
- O trace reproduz o arquivo de sinal importado; isso não verifica sincronização com vídeo. Os arquivos NB/SB são iguais: trocá-los não resolve. Seta vermelha e verde direto simultâneos não estabelecem sozinhos legalidade/controle de uma conversão. A baia esquerda e movimentos observados ainda precisam entrar no grafo.

### Engenharia e testes atuais

- **41 testes Scala em seis suítes e assembly aprovados**; **21 testes Python NGSIM aprovados**.
- Fixture de publicação: controle escalonado tem 160 eventos físicos, preservados entre versão antiga e nova. Snapshot simultâneo restaura 35/20/3 m no tick zero e tem 161 eventos iguais nos dois runtimes e uma/duas JVMs. Preserva o bloqueio pela cauda a jusante; três viagens censuradas, nenhuma saída fabricada.
- Aceitação final: cinco execuções aprovadas, em `snapshot-final-acceptance.json`; parâmetros em `snapshot-final-matrix.json`. Confere ACKs, ticks, todas as amostras, ocupação, censura e igualdade dos eventos comprometidos.
- Inventário do incremento: 29 execuções preservadas, 25 completas e quatro falhas da primeira publicação que não normalizava o shard completo. Correção testada e reexecutada em diretórios novos. Um build interrompido com código 143 também foi retido; não foi contado como aprovado.
- Um fixture inicial legado deslocou 35 m para 13,5 m por ordem de admissão. Foi preservado como diagnóstico. O controle sintético escalonado isola a publicação; não aplicar esse escalonamento artificial aos dados empíricos.

## Binários, proveniência e localização

Todos os caminhos desta seção partem do workspace pai, não do diretório do motor.

| Uso | Caminho | SHA-256 |
| --- | --- | --- |
| Ensaio empírico-base e sensibilidade geométrica | `experiments/micro-complete-20261118/engine.jar` | `f53ac8e0fa4d4f4155aa0ea96dfb4c2448c1921ce222cec367642d484ea2835b` |
| Engenharia final de snapshot/publicação | `experiments/micro-reconstruction-20261118/snapshot-engine-final.jar` | `986d64f67cefa94745232562432bb7a471638a59659f1db68a649eea553bb8d4` |

Esses JARs foram construídos com mudanças preexistentes do workspace. **Não alegar reconstrução byte a byte apenas com checkout de `2b6fab6`.** Conservar JAR, hash, `workspace-source.patch`, `workspace-extra-sources.tar.gz` e `evidence-manifest.json` no diretório do incremento. Não confundir binários intermediários defeituosos ou anteriores à validação final com o final.

Outros artefatos principais:

- `experiments/micro-reconstruction-20261118/increment-summary.json`, `execution-inventory.json`, `photo-stopbar.json`, `approach-audit.json`, `all-green-release-audit.json`, `causal-departure-comparison.json`, protocolos, logs e inputs.
- Resumos e figuras versionados: [docs/validation/micro-reconstruction-20261118](validation/micro-reconstruction-20261118/).
- Dados prospectivos: `experiments/micro-empirical-v3-20261118/heldout/page-000..009.json` (PM inteira já exposta), `verified-summary.json`, protocolo e registros de exposição. AM previamente inspecionado em `development-expanded-audit/page-*.json`.
- Separar gravações por chave `global_time - 100*frame_id`: desenvolvimento 1163018600 e PM 1163030300. Não usar ranges de `global_time` como separação segura; tempo físico vem da diferença de frames ×0,1 s.
- PDFs anteriores PT/EN, 25 páginas cada: `output/pdf/htc-micro-empirical-20261118/htc-micro-relatorio-completo-pt.pdf` e `htc-micro-complete-report-en.pdf`. Markdown, CSVs, figuras, manifestos e gerador estão junto. ZIP: `output/pdf/htc-micro-empirical-20261118-pt-en.zip`. **São registros anteriores ao último incremento de código**, não relatórios atualizados da campanha inteira.
- Bundles anteriores: `output/validation/htc-micro-actor-development-20261118.zip`, `output/validation/micro-peachtree-20261118/htc-micro-peachtree-prospective-evidence.zip` e `output/pdf/ngsim-complete-20261118/htc-ngsim-complete-report-and-evidence.zip`.

Os diretórios grandes `experiments/` e `output/` ficam no workspace local; não presumir que um clone remoto os contém. Conferir existência e hashes antes de prosseguir.

## Lacunas e sequência recomendada

| Item | O que falta e como avançar |
| --- | --- |
| G01 / E01 | Completar grafo medido, baia, conexões, rotas, demanda/conflictos e jusante; provar entradas de fronteira e sincronização. Reconstrução, geometria e snapshot já avançaram, mas não fecharam o cenário. |
| E02 | Isolar o resíduo de partida com evidência independente de sincronização/contexto/veículo; só depois avaliar mudança física justificável. |
| **G02 — próxima dependência do motor** | Modelar caminho explícito faixa de origem→faixa de conector→faixa de saída; consumir fronteiras por caminho. MOBIL/LC2013 precisam do líder a jusante da **faixa candidata**, não apenas o da faixa atual. Publicação pronta não resolve isso. |
| G03 | Handoff físico geral, admissão enfileirada com footprint retido e spillback multifaixa. Manter ocupação a montante até aceitação física real a jusante; liberação exatamente uma vez, com identidade e cancelamento/replay. Grant de capacidade do Node não equivale a entrada efetiva. |
| G04 | Áreas de conflito/reservas e prioridade determinística por movimento no Node, com estado persistido e cancelamento correto. |
| G05 | Aceitação causal de brechas e permissões protegidas/permissivas. Exclusão mútua sozinha não implementa gap acceptance. |
| G06 | Lookahead por mais de um conector/próxima via quando a geometria exigir; conservar contratos de remainder/comprimento. |
| G07 | Medidas autônomas completas por movimento: filas, descarga, conversões, trocas, spillback, censura/cobertura e segurança com definições comparáveis HTC/SUMO. |

MOBIL, subconjunto LC2013, sinais Fixed/GapActuated e conectores já existem: distinguir mecanismo ausente de capacidade existente ainda não validada. O mapa atual road→connector não basta para caminhos multifaixa. A API atual de líderes a jusante por veículo não deve ser simplesmente reutilizada para segurança de uma faixa candidata diferente.

Após implementar dependências, executar cenas integrais, verificar invariantes em conservativo/Time Warp e distribuição, congelar protocolo e reservar dados realmente novos antes de abrir a avaliação final. Manter metas e intervalos previstos no plano; relatar falhas, exclusões e censura. Atualizar relatórios PT/EN quando houver novas evidências consolidadas.

## Contratos arquiteturais que precisam sobreviver

- Scala/Pekko clássico, handlers sem estado próprio, obrigações/replies e estado de comportamento em estado persistido ou hooks testados; nenhum ActorRef no estado.
- Transporte positivo/anti normal por `sendMessageTo`; espera de negócio via contratos de interação existentes. Nenhuma sincronização manual com Time Manager, bloqueio de ator ou timeout/watchdog para esconder reply perdido.
- Toda execução espontânea resolve corretamente. Preservar sequência/identidade de pedidos, cancelamento, replay e ownership físico.
- Física no tick t integra (t−1,t]; sinais/entradas em t governam o intervalo seguinte. Observações de fronteira usadas causalmente são estritamente anteriores a t; manter duas coortes de ticks e sequência mais recente.
- ACK reflete estado admitido. Remainder apenas em continuação física imediata no mesmo tick; parada real limpa remainder/velocidade conforme contrato.
- Clock 0,1 s no escopo suportado Car/Link/Node/TrafficSignal com rotas estáticas. Não estender a Person/Bus/outros silenciosamente.
- Censura no horizonte é saída de relatório, sem enviar/agendar/criar/destruir entidades nessa etapa. `arrival=-1`, `censored=true`; não inventar distância ou chegada.
- Codecs frios independentes, JSON legado/novo e snapshot/rollback precisam de testes. Não misturar configurações/binários entre JVMs; não reordenar registros Kryo existentes.
- Referência conservativa com backend channel-closed; sem presumir que backend direct tem o mesmo contrato de ordem no mesmo tick. Não alegar suporte geral a migração/recuperação Time Warp.
- Preservar guards atuais de combinações não suportadas até a implementação correspondente ser verificada. Evitar formatação ampla ou refatoração alheia ao incremento.

## Reprodução mínima

Não sobrescrever os diretórios de evidência existentes. Os comandos abaixo usam diretórios novos; escolher portas livres. O `--policy` é argumento do gerador, não do runner.

A partir do workspace pai:

```bash
cd /home/dean/workspace/phd/htc-platform
sha256sum experiments/micro-reconstruction-20261118/snapshot-engine-final.jar
python3 hyperbolic-time-chamber/scripts/scenario/micro_boundary_publication_fixture.py \
  --output experiments/claude-snapshot-input-de \
  --policy discrete-event --initial-snapshot
python3 hyperbolic-time-chamber/scripts/scenario/run_cluster_validation.py \
  --jar experiments/micro-reconstruction-20261118/snapshot-engine-final.jar \
  --simulation experiments/claude-snapshot-input-de/simulation.json \
  --output experiments/claude-snapshot-run-de \
  --base-port 30100 --nodes 1 --timeout 300 \
  --extra-config experiments/micro-complete-20261118/actor-reporting.conf
python3 hyperbolic-time-chamber/scripts/scenario/check_micro_boundary_publication.py \
  --run experiments/claude-snapshot-run-de --expected-ticks 0 0 0 \
  --reference experiments/micro-reconstruction-20261118/final-snapshot-de \
  --output experiments/claude-snapshot-check-de.json
```

Repetir com input novo `--policy time-warp`, e com `--nodes 2` e portas distintas. A matriz preservada contém as cinco execuções exatas já realizadas. O config de relatório preservado solicita todas as amostras micro. Um retorno -9 após a conclusão pode ser encerramento deliberado do harness: conferir `engineCompleted`, `runtimeFailed` e erros, não só exit code do processo.

Testes a partir do motor:

```bash
cd /home/dean/workspace/phd/htc-platform/hyperbolic-time-chamber
sbt -J-Xmx2g 'testOnly *MicroEntryAdmissionSpec *MicroLaneBoundaryPublicationSpec *LinkFollowingHandlerSpec *LinkSpillbackSpec *KryoEventDataSerializationSpec *CarMicroDepartureSpec' assembly
python3 -m unittest discover -s scripts/scenario -p 'test_*ngsim*.py' -v
```

Para reconstrução, consultar `--help` dos scripts `audit_ngsim_approach.py`, `audit_ngsim_releases.py` e `fetch_ngsim_support.py`. Geometria usa `photo-stopbar.json`; raw PM usa `heldout`; outro registro usa `development-expanded-audit`. O sinal está em `experiments/micro-complete-20261118/support/peachtree-street-signal-timing/signal-timing/signal-change/Original Text files/Peachtree_10th_NB_0400-0415.txt`. O Python do venv preservado é `experiments/micro-complete-20261118/.venv/bin/python`. Para a ortofoto, o recuperador exige seleção explícita `--members aerial-ortho-photos`; não baixar trajetórias adicionais junto inadvertidamente antes de registrar exposição.

## Alterações locais a preservar

O workspace está deliberadamente sujo com trabalho preexistente de configuração/settings/MESO. O JAR experimental inclui esse contexto. Não usar reset/clean/stash indiscriminado, não incorporar tudo em um commit da validação e não parar processos de serviços alheios. A passagem adiciona apenas este documento e seu link no ledger.

Status imediatamente antes de criar esta passagem (caminhos relativos ao motor):

```text
 M README.md
 M src/main/resources/application.conf
 M src/main/scala/core/HyperbolicTimeChamber.scala
 M src/main/scala/core/actor/manager/SimulationManager.scala
 M src/main/scala/core/actor/manager/report/ClickHouseReportData.scala
 M src/main/scala/core/api/ConfigApiServer.scala
 M src/main/scala/core/api/SimulatorSettingsRegistry.scala
 M src/main/scala/core/cluster/NodeIdentity.scala
 M src/main/scala/core/entity/configuration/Simulation.scala
 M src/main/scala/core/util/SimulationUtil.scala
 M src/main/scala/model/hybrid/actor/Bicycle.scala
 M src/main/scala/model/hybrid/actor/Bus.scala
 M src/main/scala/model/hybrid/actor/Car.scala
 M src/main/scala/model/hybrid/actor/Motorcycle.scala
 M src/main/scala/model/hybrid/support/bicycle/BicycleLinkHandler.scala
 M src/main/scala/model/hybrid/support/bicycle/BicycleSignalHandler.scala
 M src/main/scala/model/hybrid/support/bus/BusLinkHandler.scala
 M src/main/scala/model/hybrid/support/car/CarLinkHandler.scala
 M src/main/scala/model/hybrid/support/motorcycle/MotorcycleLinkHandler.scala
 M src/main/scala/model/hybrid/util/SpeedUtil.scala
 M src/main/scala/model/hybrid/util/strategy/TravelTimeModeChoiceStrategy.scala
 M src/main/scala/system/database/redis/RedisClientManager.scala
 M src/test/scala/model/hybrid/util/SpeedUtilSpec.scala
?? docs/SIMULATION_SETTINGS.md
?? docs/simulation-settings-defaults.json
?? src/main/scala/core/util/ScenarioSettings.scala
?? src/main/scala/core/util/ScenarioSettingsExport.scala
?? src/main/scala/model/hybrid/util/MesoTrafficConfig.scala
?? src/test/scala/core/util/ScenarioSettingsSpec.scala
?? src/test/scala/model/hybrid/actor/BicycleMesoTraversalSpec.scala
?? tmp/
```

Scripts novos em `scripts/scenario/` podem estar ignorados por `.gitignore`; se necessário, adicionar com `git add -f` apenas os arquivos novos de responsabilidade do incremento. O status acima descreve trabalho a preservar, não uma lista de defeitos a corrigir.

## MESO: escopo separado

O usuário também pediu documentação da validação mesoscópica e indicou `/home/dean/Downloads/dot_60302_DS1.pdf`. A revisão e o plano estão em [MESO_VTRC_22_R7_REVIEW.md](MESO_VTRC_22_R7_REVIEW.md) e [MESO_EMPIRICAL_VALIDATION_PLAN.md](MESO_EMPIRICAL_VALIDATION_PLAN.md). Não foi estabelecida validação empírica independente MESO. A prioridade acordada de implementação/experimentos nesta continuidade é MICRO.
