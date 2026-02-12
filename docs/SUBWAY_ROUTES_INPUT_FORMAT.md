# Subway Routes Input Format

## Overview

As rotas das linhas de metrô devem ser fornecidas em um arquivo JSON separado em vez de serem geradas aleatoriamente pelo migration script.

## Formato do Arquivo

**Arquivo:** `subway_routes.json`

```json
{
  "routes": [
    {
      "id": "line_blue",
      "label": "Blue Line",
      "stations": [
        "htcaid:node;60609822",
        "htcaid:node;4922987596",
        "htcaid:node;295394530"
      ],
      "frequency": 5,
      "operatingHours": {
        "start": 300,
        "end": 1380
      },
      "trainsPerRoute": 3
    }
  ]
}
```

## Campos

### Route (Linha)

| Campo | Tipo | Obrigatório | Descrição |
|-------|------|-------------|-----------|
| `id` | string | Sim | Identificador único da linha (ex: "line_blue", "line_1_red") |
| `label` | string | Sim | Nome da linha (ex: "Blue Line", "Linha 1 - Azul") |
| `stations` | array[string] | Sim | Lista de node IDs das estações **em ordem** (mínimo 2) |
| `frequency` | number | Não | Frequência em minutos (padrão: 5) |
| `operatingHours` | object | Não | Horário de operação |
| `trainsPerRoute` | number | Não | Número de trens nesta linha (padrão: config.subways_per_route) |

### Operating Hours

| Campo | Tipo | Descrição |
|-------|------|-----------|
| `start` | number | Tick de início (ex: 300 = 5:00 AM) |
| `end` | number | Tick de fim (ex: 1380 = 11:00 PM) |

## Node IDs das Estações

**IMPORTANTE:** Os `node IDs` devem corresponder aos nós onde há **SubwayStations**.

### Como Obter Node IDs

1. **A partir do mapa existente:**
   ```bash
   jq '.[] | select(.typeActor == "hybrid.actor.Node") | {id, lat: .data.content.latitude, lon: .data.content.longitude}' input/nodes.json
   ```

2. **A partir das SubwayStations geradas:**
   ```bash
   jq '.[] | {stationId: .id, nodeId: .data.content.node, name: .data.content.name}' output/data/subway_stations_0.json
   ```

3. **Usando coordenadas GPS:**
   - Identifique as coordenadas das estações no mapa
   - Encontre os nodes mais próximos dessas coordenadas
   - Use os IDs desses nodes na definição da rota

## Ordem das Estações

A ordem das estações no array `stations` define a **sequência da linha**:

```json
{
  "stations": [
    "htcaid:node;station_A",  // Estação inicial (garagem/terminal)
    "htcaid:node;station_B",  // Segunda estação
    "htcaid:node;station_C",  // Terceira estação
    "htcaid:node;station_D"   // Estação final (terminal)
  ]
}
```

**Resultado:**
- Metrô viaja: A → B → C → D
- Metrô retorna: D → C → B → A (linha circular)

## Exemplo Completo (São Paulo)

```json
{
  "routes": [
    {
      "id": "line_1_blue",
      "label": "Linha 1 - Azul",
      "stations": [
        "htcaid:node;60609822",     // Jabaquara (garagem)
        "htcaid:node;4922987596",   // Conceição
        "htcaid:node;295394530",    // São Judas
        "htcaid:node;4922987590",   // Saúde
        "htcaid:node;60609838",     // Praça da Árvore
        "htcaid:node;4922987584",   // Santa Cruz
        "htcaid:node;295394528",    // Vila Mariana
        "htcaid:node;4922987582",   // Ana Rosa
        "htcaid:node;295394526",    // Paraíso
        "htcaid:node;4922987580",   // Vergueiro
        "htcaid:node;60609834",     // São Joaquim
        "htcaid:node;4922987578",   // Liberdade
        "htcaid:node;295394524",    // Sé
        "htcaid:node;4922987576",   // São Bento
        "htcaid:node;60609830",     // Luz
        "htcaid:node;4922987574",   // Tiradentes
        "htcaid:node;295394522",    // Armênia
        "htcaid:node;4922987572",   // Portuguesa-Tietê
        "htcaid:node;60609826",     // Carandiru
        "htcaid:node;4922987570",   // Santana
        "htcaid:node;295394520",    // Jardim São Paulo
        "htcaid:node;4922987568",   // Parada Inglesa
        "htcaid:node;60609824"      // Tucuruvi (terminal)
      ],
      "frequency": 3,
      "operatingHours": {
        "start": 240,
        "end": 1440
      },
      "trainsPerRoute": 10
    },
    {
      "id": "line_3_red",
      "label": "Linha 3 - Vermelha",
      "stations": [
        "htcaid:node;2345678901",   // Palmeiras-Barra Funda (terminal)
        "htcaid:node;3456789012",   // Marechal Deodoro
        "htcaid:node;4567890123",   // Santa Cecília
        "htcaid:node;5678901234",   // República
        "htcaid:node;295394524",    // Sé (integração com Linha 1)
        "htcaid:node;6789012345",   // Pedro II
        "htcaid:node;7890123456",   // Brás
        "htcaid:node;8901234567",   // Bresser-Mooca
        "htcaid:node;9012345678",   // Belém
        "htcaid:node;1234509876",   // Tatuapé
        "htcaid:node;2345609876",   // Carrão
        "htcaid:node;3456709876",   // Penha
        "htcaid:node;4567809876",   // Vila Matilde
        "htcaid:node;5678909876",   // Guilhermina-Esperança
        "htcaid:node;6789009876",   // Patriarca
        "htcaid:node;7890109876",   // Artur Alvim
        "htcaid:node;8901209876",   // Corinthians-Itaquera (terminal)
        "htcaid:node;9012309876"    // Dom Bosco
      ],
      "frequency": 4,
      "operatingHours": {
        "start": 240,
        "end": 1440
      },
      "trainsPerRoute": 12
    }
  ]
}
```

## Configuração no Migration Script

### Config YAML

```yaml
# migration_config.yaml
input_dir: ./input/cenario_sp
output_dir: ./output/cenario_sp_hybrid

# Subway routes input file
subway_routes_file: ./input/subway_routes_sp.json

# Public transport
generate_public_transport: true
subway_station_coverage: 0.05
```

### Linha de Comando

```bash
python migrate_to_hybrid.py \
  --input ./input/cenario_sp \
  --output ./output/cenario_sp_hybrid \
  --subway-routes ./input/subway_routes_sp.json
```

## Validação

O script valida automaticamente:

1. ✅ **Estações existem:** Verifica se todos os node IDs existem no mapa
2. ✅ **Mínimo de estações:** Cada linha deve ter pelo menos 2 estações
3. ✅ **SubwayStations criadas:** Verifica se há SubwayStation nos nós especificados

Se alguma validação falhar, a linha é **ignorada** com warning no log.

## Geração de Rail Links

Após ler as rotas do arquivo, o script automaticamente:

1. Gera `rail_links` conectando estações consecutivas (A→B, B→C, etc.)
2. Gera `rail_links` de retorno (C→B, B→A, etc.) para linhas circulares
3. Popula `linesRoute` nas SubwayStations com rail_link IDs
4. Cria trens (`Subway`) com `bestRoute` contendo rail_link IDs

## Logs

```
Step 5/8: Generating subway routes...
  ✓ Loaded 2 subway routes from file
  ✓ Generated 50 rail links for 2 subway lines
  ✓ Populated linesRoute for 35 stations
```

## Exemplo Simples (Teste)

Para testes rápidos, use uma linha simples:

```json
{
  "routes": [
    {
      "id": "test_line",
      "label": "Test Line",
      "stations": [
        "htcaid:node;1",
        "htcaid:node;2",
        "htcaid:node;3"
      ],
      "frequency": 5,
      "trainsPerRoute": 1
    }
  ]
}
```

## Troubleshooting

### Erro: "Station with node X not found"

**Causa:** O node ID especificado não existe no mapa ou não tem SubwayStation.

**Solução:**
1. Verifique se o node ID está correto
2. Certifique-se que `subway_station_coverage` é suficiente para criar estações nesses nós
3. Ou adicione manualmente SubwayStation nesses nós

### Erro: "Route has less than 2 valid stations"

**Causa:** A linha tem menos de 2 estações válidas após validação.

**Solução:**
1. Verifique se os node IDs estão corretos
2. Adicione mais estações à linha

### Warning: "No subway_routes_file provided, generating routes randomly"

**Causa:** Campo `subway_routes_file` não foi configurado.

**Solução:**
1. Adicione `subway_routes_file: ./input/subway_routes.json` no config YAML
2. Ou use `--subway-routes ./input/subway_routes.json` na linha de comando

## Benefícios

✅ **Controle total:** Defina exatamente quais estações e em qual ordem
✅ **Realismo:** Use linhas reais de metrô da cidade
✅ **Flexibilidade:** Diferentes frequências e horários por linha
✅ **Reprodutibilidade:** Mesmas rotas em todas as execuções
✅ **Facilidade:** Formato JSON simples e legível
