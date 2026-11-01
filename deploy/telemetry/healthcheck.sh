#!/usr/bin/env bash
# Проверяем все компоненты; в Docker health log остаются имя, адрес и причина сбоя.
set -euo pipefail
status=0
while read -r name endpoint; do
  if output=$(curl --fail-with-body --silent --show-error \
      --connect-timeout 2 --max-time 5 "$endpoint" 2>&1); then
    continue
  else
    code=$?
    printf '%s (%s): curl exit %s: %s\n' "$name" "$endpoint" "$code" "$output" >&2
    status=1
  fi
done <<'ENDPOINTS'
Grafana http://127.0.0.1:3000/api/health
Prometheus http://127.0.0.1:9090/-/ready
Loki http://127.0.0.1:3100/ready
Tempo http://127.0.0.1:3200/ready
Pyroscope http://127.0.0.1:4040/ready
Collector http://127.0.0.1:13133/ready
ENDPOINTS
exit "$status"
