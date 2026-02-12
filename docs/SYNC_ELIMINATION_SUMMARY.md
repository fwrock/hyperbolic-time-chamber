# Eliminação de Sincronização Runtime

## 📋 Resumo

Eliminamos completamente a sincronização runtime entre Links, Nodes e TrafficSignals, substituindo por **pré-população de dados nos arquivos de entrada**.

## 🎯 Problemas Eliminados

### 1. **Race Conditions**
- Link enviava `LinkConnectionsData` para Node no primeiro veículo
- TrafficSignal enviava `TrafficSignalChangeStatusData` para Node na inicialização
- Atores podiam tentar se comunicar antes da inicialização completa

### 2. **Dados Incompletos**
- Nodes começavam sem conhecimento de connections
- Signals não estavam mapeados nos Nodes
- Veículos podiam consultar estado antes da sincronização

### 3. **Overhead de Mensagens**
- Centenas de mensagens `LinkConnectionsData` ao iniciar
- Mensagens `TrafficSignalChangeStatusData` por sinal
- Processamento extra durante inicialização crítica

## ✅ Solução Implementada

### Python: migrate_to_hybrid.py

#### Conexões de Links (node.connections)
```python
# _update_node_links()
connections = {}
for link in self.links:
    if from_node == node_id:
        connections[link_id] = {
            "id": to_node,
            "classType": "hybrid.actor.Node"
        }
    # ...
node_content['connections'] = connections
```

**Resultado:** 4544 nodes com ~6 connections cada

#### Signals em Nodes (node.signals)
```python
# _generate_traffic_signals_and_populate_nodes()
# IMPORTANTE: Gerar signals ANTES de escrever nodes!
for link_id in incoming_links:
    node_signals[link_id] = {
        "state": "Red",
        "remainingTime": 0,
        "nextTick": offset,
        "signalId": signal['id']
    }
```

**Resultado:** 408 nodes com signals, 884 signal entries no total

#### Ordem de Execução Crítica
```python
# _write_output()
# 1️⃣ Gerar signals e popular nodes
if self.config.generate_traffic_signals:
    self._generate_traffic_signals_and_populate_nodes(data_dir)

# 2️⃣ Escrever nodes (COM signals populados)
self._write_split_files(self.nodes, data_dir, "nodes")

# 3️⃣ Escrever signals
if hasattr(self, '_generated_signals'):
    self._write_split_files(self._generated_signals, data_dir, "traffic_signals")
```

### Scala: Remoção de Código Obsoleto

#### Link.scala
**Removido:**
- ❌ `ensureConnectionsRegistered()` - não precisa mais
- ❌ `sendConnectionsToNode()` - sem mensagens runtime
- ❌ `connectionsRegistered: Boolean` - flag desnecessária
- ❌ Import `LinkConnectionsData`

#### RailLink.scala
**Removido:**
- ❌ `ensureConnectionsRegistered()` 
- ❌ `sendConnectionsToNode()`
- ❌ `connectionsRegistered: Boolean`
- ❌ Import `LinkConnectionsData`

#### Node.scala
**Removido:**
- ❌ `handleLinkConnections()` - connections vêm do arquivo
- ❌ Case `LinkConnectionsData` no event handler
- ❌ Import `LinkConnectionsData`

## 📊 Benefícios

### Performance
- ✅ **Inicialização mais rápida** - sem overhead de sincronização
- ✅ **Menos mensagens** - centenas eliminadas
- ✅ **Determinismo** - estado inicial sempre consistente

### Confiabilidade
- ✅ **Zero race conditions** - tudo pré-configurado
- ✅ **Dados completos desde o início** - veículos podem consultar imediatamente
- ✅ **Comportamento previsível** - sem lazy initialization

### Manutenibilidade
- ✅ **Código mais simples** - menos lógica de sincronização
- ✅ **Debugging mais fácil** - estado inicial visível nos arquivos
- ✅ **Testes mais confiáveis** - comportamento determinístico

## 🧪 Validação

### Teste de Connections
```bash
cd test_scenario/data
python3 -c "
import json, glob
for f in glob.glob('nodes_*.json')[:1]:
    nodes = json.load(open(f))
    print(f'Connections: {len(nodes[0][\"data\"][\"content\"][\"connections\"])}')
"
```
**Output:** `Connections: 6` ✅

### Teste de Signals
```bash
cd test_scenario/data
python3 -c "
import json, glob
nodes_with_signals = sum(
    1 for f in glob.glob('nodes_*.json')
    for n in json.load(open(f))
    if n['data']['content'].get('signals')
)
print(f'Nodes com signals: {nodes_with_signals}')
"
```
**Output:** `Nodes com signals: 408` ✅

### Exemplo de Node Completo
```json
{
  "id": "htcaid:node;1038746200",
  "data": {
    "content": {
      "connections": {
        "htcaid:link;4977": {
          "id": "htcaid:node;1234567",
          "classType": "hybrid.actor.Node"
        }
      },
      "signals": {
        "htcaid:link;4977": {
          "state": "Red",
          "remainingTime": 0,
          "nextTick": 14,
          "signalId": "htcaid:signal;signal_308"
        }
      }
    }
  }
}
```

## 📝 Notas Importantes

### EventTypeEnum.LinkConnection
O enum `LinkConnection` ainda existe mas não é mais usado. Pode ser removido em limpeza futura se não houver outros usos.

### TrafficSignalChangeStatusData
`handleReceiveSignalChangeStatus()` ainda existe no Node para **atualizações** de estado durante a simulação, mas a **inicialização** não depende mais dessas mensagens.

### Backward Compatibility
Cenários antigos que não têm `connections` ou `signals` pré-populados podem ter problemas. Use `migrate_to_hybrid.py` para converter.

## 🔮 Próximos Passos

1. ✅ Testar simulação completa com novo cenário
2. ⏳ Considerar remover `EventTypeEnum.LinkConnection` se não usado
3. ⏳ Documentar formato de entrada esperado
4. ⏳ Adicionar validação de schema para entrada

---
**Data:** 2026-02-12  
**Versão:** 1.0  
**Autores:** Dean + GitHub Copilot
