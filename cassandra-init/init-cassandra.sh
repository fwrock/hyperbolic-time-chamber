#!/bin/bash

# Aguarda o Cassandra estar totalmente pronto
echo "Aguardando Cassandra estar pronto..."
until cqlsh -e 'describe keyspaces' > /dev/null 2>&1; do
  echo "Cassandra ainda não está pronto. Aguardando 5 segundos..."
  sleep 5
done

echo "Cassandra está pronto! Executando scripts de inicialização..."

# Executa todos os scripts CQL na pasta
for cql_file in /docker-entrypoint-initdb.d/*.cql; do
  if [ -f "$cql_file" ]; then
    echo "Executando: $cql_file"
    cqlsh -f "$cql_file"
    if [ $? -eq 0 ]; then
      echo "✅ Script $cql_file executado com sucesso"
    else
      echo "❌ Erro ao executar script $cql_file"
      exit 1
    fi
  fi
done

echo "🎉 Todos os scripts de inicialização foram executados com sucesso!"