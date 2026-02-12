# ✅ Subway Routes from Input Files - COMPLETE

## Resumo

**Problema resolvido:** Rotas de metrô agora são lidas de **arquivos de entrada JSON** em vez de serem geradas aleatoriamente.

---

## 🎯 O Que Foi Feito

### 1. **Migration Script Modificado** ✅

**Arquivo:** `scripts/migrate_to_hybrid.py`

**Mudanças:**
- ✅ Adicionado campo `subway_routes_file: Optional[Path]` no `MigrationConfig`
- ✅ Nova função `_load_subway_routes_from_file()` para ler rotas do arquivo JSON
- ✅ Modificado `_generate_subway_routes()` para detectar se há arquivo de entrada
- ✅ Validação automática: verifica se node IDs existem no mapa
- ✅ Suporte para linha de comando: `--subway-routes ./input/subway_routes.json`
- ✅ Suporte para YAML config: `subway_routes_file: ./input/subway_routes.json`

### 2. **Formato de Entrada Documentado** ✅

**Arquivo:** `docs/SUBWAY_ROUTES_INPUT_FORMAT.md`

**Conteúdo:**
- ✅ Formato JSON completo das rotas
- ✅ Descrição de todos os campos
- ✅ Exemplos (simples + São Paulo completo)
- ✅ Como obter node IDs das estações
- ✅ Validação e troubleshooting
- ✅ Configuração via YAML e linha de comando

### 3. **Arquivo de Exemplo** ✅

**Arquivo:** `scripts/input/subway_routes_example.json`

**Conteúdo:**
- ✅ 3 linhas de exemplo (Blue, Red, Green)
- ✅ Diferentes configurações (frequency, operating hours, trains)
- ✅ Formato pronto para usar

---

## 📋 Formato de Entrada

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

### Campos Obrigatórios:
- `id` → Identificador único da linha
- `label` → Nome da linha (ex: "Linha 1 - Azul")
- `stations` → Array de node IDs **em ordem** (mínimo 2)

### Campos Opcionais:
- `frequency` → Frequência em minutos (padrão: 5)
- `operatingHours` → `{start, end}` em ticks (padrão: 5am-11pm)
- `trainsPerRoute` → Número de trens (padrão: config.subways_per_route)

---

## 🚀 Como Usar

### Opção 1: Via YAML Config

```yaml
# migration_config.yaml
input_dir: ./input/cenario_sp
output_dir: ./output/cenario_sp_hybrid
subway_routes_file: ./input/subway_routes_sp.json
```

```bash
python migrate_to_hybrid.py --config migration_config.yaml
```

### Opção 2: Via Linha de Comando

```bash
python migrate_to_hybrid.py \
  --input ./input/cenario_sp \
  --output ./output/cenario_sp_hybrid \
  --subway-routes ./input/subway_routes_sp.json
```

### Opção 3: Geração Aleatória (Legacy)

Se **não** fornecer `subway_routes_file`:

```bash
python migrate_to_hybrid.py \
  --input ./input/cenario \
  --output ./output/cenario_hybrid \
  --num-subway-routes 2
```

**Warning:** "No subway_routes_file provided, generating routes randomly"

---

## 🔄 Fluxo de Execução

```
1. Migration script inicia
   ↓
2. Lê nodes e subway_stations do mapa
   ↓
3. Verifica se subway_routes_file existe
   ├─ SIM → _load_subway_routes_from_file()
   │         ├─ Lê JSON
   │         ├─ Valida node IDs (estações existem?)
   │         ├─ Cria SubwayRoutes
   │         └─ Gera trens (Subway)
   │
   └─ NÃO → _generate_subway_routes() (random)
             └─ Gera rotas aleatórias (legacy)
   ↓
4. _generate_rail_network()
   ├─ Gera rail_links conectando estações consecutivas
   └─ Rail links IDA + VOLTA (circular)
   ↓
5. _populate_station_routes()
   └─ Popula linesRoute nas SubwayStations com rail_link IDs
   ↓
6. SubwayStation cria Subway com bestRoute
   └─ bestRoute contém rail_link IDs corretos
```

---

## ✅ Validação Automática

O script valida automaticamente:

1. ✅ **Node IDs existem:** Verifica se todos os nós estão no mapa
2. ✅ **Estações válidas:** Verifica se há SubwayStation nos nós
3. ✅ **Mínimo de estações:** Cada linha deve ter ≥ 2 estações
4. ✅ **Arquivo JSON válido:** Verifica estrutura e campos obrigatórios

**Se alguma validação falhar:**
- ⚠️ Warning no log
- Linha é ignorada
- Script continua processando outras linhas

---

## 📊 Logs Esperados

### Com arquivo de entrada:
```
Step 5/8: Generating subway routes...
  ✓ Loaded 3 subway routes from file
  ✓ Generated 72 rail links for 3 subway lines
  ✓ Populated linesRoute for 18 stations
  ✓ Created 9 subway trains
```

### Sem arquivo de entrada (random):
```
Step 5/8: Generating subway routes...
  ⚠️  No subway_routes_file provided, generating routes randomly
  ✓ Generated 2 random subway routes
  ✓ Generated 40 rail links for 2 subway lines
```

### Com erro de validação:
```
Step 5/8: Generating subway routes...
  ⚠️  Station with node htcaid:node;999 not found, skipping route line_invalid
  ⚠️  Route line_short has less than 2 valid stations, skipping
  ✓ Loaded 1 subway routes from file (2 skipped)
```

---

## 🧪 Como Testar

### 1. Criar arquivo de rotas

```bash
cat > input/subway_routes_test.json << 'EOF'
{
  "routes": [
    {
      "id": "test_line",
      "label": "Test Line",
      "stations": [
        "htcaid:node;60609822",
        "htcaid:node;4922987596",
        "htcaid:node;295394530"
      ],
      "frequency": 5,
      "trainsPerRoute": 1
    }
  ]
}
EOF
```

### 2. Rodar migration script

```bash
cd scripts
python migrate_to_hybrid.py \
  --input ./input/cenario_1000_viagens \
  --output ./output/test_subway_routes \
  --subway-routes ./input/subway_routes_test.json
```

### 3. Verificar outputs

```bash
# Verificar subway routes criados
jq '.[] | select(.typeActor == "hybrid.actor.SubwayRoute")' output/test_subway_routes/subway_routes_0.json

# Verificar rail_links gerados
jq '.[] | {id, from, to, subwayLine}' output/test_subway_routes/data/rail_links_0.json

# Verificar linesRoute nas estações
jq '.[] | select(.typeActor == "hybrid.actor.SubwayStation") | .data.content.linesRoute' \
   output/test_subway_routes/data/subway_stations_0.json

# Verificar trens criados
jq '.[] | select(.typeActor == "hybrid.actor.Subway") | {id, line: .data.content.route}' \
   output/test_subway_routes/subways_0.json
```

---

## 📝 Exemplo Real (São Paulo)

```json
{
  "routes": [
    {
      "id": "line_1_blue",
      "label": "Linha 1 - Azul",
      "stations": [
        "htcaid:node;jabaquara",
        "htcaid:node;conceicao",
        "htcaid:node;sao_judas",
        "htcaid:node;saude",
        "htcaid:node;praca_arvore",
        "htcaid:node;santa_cruz",
        "htcaid:node;vila_mariana",
        "htcaid:node;ana_rosa",
        "htcaid:node;paraiso",
        "htcaid:node;vergueiro",
        "htcaid:node;sao_joaquim",
        "htcaid:node;liberdade",
        "htcaid:node;se",
        "htcaid:node;sao_bento",
        "htcaid:node;luz",
        "htcaid:node;tiradentes",
        "htcaid:node;armenia",
        "htcaid:node;portuguesa_tiete",
        "htcaid:node;carandiru",
        "htcaid:node;santana",
        "htcaid:node;jardim_sp",
        "htcaid:node;parada_inglesa",
        "htcaid:node;tucuruvi"
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
        "htcaid:node;palmeiras_barra_funda",
        "htcaid:node;marechal_deodoro",
        "htcaid:node;santa_cecilia",
        "htcaid:node;republica",
        "htcaid:node;se",
        "htcaid:node;pedro_ii",
        "htcaid:node;bras",
        "htcaid:node;bresser_mooca",
        "htcaid:node;belem",
        "htcaid:node;tatuape",
        "htcaid:node;carrao",
        "htcaid:node;penha",
        "htcaid:node;vila_matilde",
        "htcaid:node;guilhermina",
        "htcaid:node;patriarca",
        "htcaid:node;artur_alvim",
        "htcaid:node;corinthians_itaquera"
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

---

## 🎯 Benefícios

✅ **Controle total:** Defina exatamente quais estações e ordem
✅ **Realismo:** Use linhas reais de metrô da cidade
✅ **Flexibilidade:** Diferentes configurações por linha
✅ **Reprodutibilidade:** Mesmas rotas em todas as execuções
✅ **Facilidade:** Formato JSON simples
✅ **Validação:** Erros detectados automaticamente
✅ **Backward compatible:** Geração random ainda funciona

---

## 📚 Arquivos Modificados/Criados

### Modificados:
- `scripts/migrate_to_hybrid.py`:
  - Adicionado `subway_routes_file` no `MigrationConfig`
  - Nova função `_load_subway_routes_from_file()` (150 linhas)
  - Modificado `_generate_subway_routes()` para detectar arquivo
  - Adicionado argumento `--subway-routes` na linha de comando

### Criados:
- `docs/SUBWAY_ROUTES_INPUT_FORMAT.md` (500+ linhas)
  - Formato completo documentado
  - Exemplos simples e complexos
  - Troubleshooting e validação
  
- `scripts/input/subway_routes_example.json`
  - 3 linhas de exemplo
  - Pronto para usar/modificar

---

## ✅ Status Final

**IMPLEMENTAÇÃO COMPLETA** ✅

**Funcionalidades:**
- ✅ Leitura de rotas de arquivo JSON
- ✅ Validação automática (node IDs, estações mínimas)
- ✅ Suporte via YAML config e linha de comando
- ✅ Backward compatible (geração random se não tiver arquivo)
- ✅ Documentação completa
- ✅ Arquivo de exemplo

**Pronto para:**
- Usar com rotas reais de cidades
- Testes com cenários customizados
- Produção

---

## 🔗 Documentação Relacionada

- [SUBWAY_ROUTES_INPUT_FORMAT.md](SUBWAY_ROUTES_INPUT_FORMAT.md) - Formato completo
- [RAIL_NETWORK_COMPLETE.md](RAIL_NETWORK_COMPLETE.md) - Implementação da malha ferroviária
- [RAIL_NETWORK_IMPLEMENTATION.md](RAIL_NETWORK_IMPLEMENTATION.md) - Detalhes técnicos
