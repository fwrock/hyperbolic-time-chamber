#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ $# -lt 3 ]]; then
  echo "Usage: bash run.sh [single|distributed] [off|on] COMPOSE_ARGS..." >&2
  exit 2
fi
topology="$1"; arm="$2"; shift 2
case "$topology" in single|distributed) ;; *) echo "Unknown topology: $topology" >&2; exit 2 ;; esac
case "$arm" in off|on) ;; *) echo "Unknown arm: $arm" >&2; exit 2 ;; esac
[[ -f .env ]] || { echo 'Copy .env.example to .env first.' >&2; exit 2; }
exec docker compose --env-file .env --env-file "flags-$arm.env" \
  -p "htc-mobility-$topology-$arm" -f "compose-$topology.yml" "$@"
