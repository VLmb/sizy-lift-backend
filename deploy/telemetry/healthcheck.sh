#!/usr/bin/env bash
# Проверяем каждый процесс: штатная проверка образа пропускает закрытый порт.
set -euo pipefail
for endpoint in \
  http://127.0.0.1:3000/api/health \
  http://127.0.0.1:9090/-/ready \
  http://127.0.0.1:3100/ready \
  http://127.0.0.1:3200/ready \
  http://127.0.0.1:4040/ready \
  http://127.0.0.1:13133/ready; do
  curl --fail --silent --show-error --max-time 1 "$endpoint" >/dev/null
done
