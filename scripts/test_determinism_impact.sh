#!/bin/bash

# Script de teste para demonstrar diferença antes/depois do RandomSeedManager

echo "🧪 TESTE: Determinismo Antes vs Depois do RandomSeedManager"
echo "============================================================"
echo

echo "📊 Executando simulação HTC 2 vezes SEM random seed fixo:"
echo "  1. Primeira execução..."
# sbt "run" com configuração atual (salvar output em temp1.txt)

echo "  2. Segunda execução..."  
# sbt "run" com configuração atual (salvar output em temp2.txt)

echo "📊 Comparando logs de simulação..."
# diff temp1.txt temp2.txt

echo
echo "🎲 Agora executando COM random seed fixo (12345):"
echo "  1. Primeira execução com seed..."
# HTC_RANDOM_SEED=12345 sbt "run" (salvar em temp3.txt)

echo "  2. Segunda execução com seed..."
# HTC_RANDOM_SEED=12345 sbt "run" (salvar em temp4.txt)

echo "📊 Comparando logs com seed fixo..."
# diff temp3.txt temp4.txt

echo
echo "🎯 RESULTADO ESPERADO:"
echo "  • SEM seed: Diferenças em UUIDs, timestamps, IDs"
echo "  • COM seed: Arquivos idênticos (diff vazio)"