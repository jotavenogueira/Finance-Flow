#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if ! command -v docker >/dev/null 2>&1 || ! sudo docker compose version >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y docker.io docker-compose-v2
fi
sudo systemctl enable --now docker
if [ ! -e .env ]; then
  (umask 077; python3 - <<'PYENV'
import secrets
from pathlib import Path
Path('.env').write_text('DB_HOST=127.0.0.1\nDB_PORT=5433\nDB_USER=financeflow\nDB_PASSWORD='+secrets.token_hex(32)+'\nDB_NAME=financeflow\nDB_SSLMODE=disable\nAPI_PORT=8081\nSESSION_COOKIE_SECURE=false\n')
PYENV
  )
fi
sudo docker compose config --quiet
sudo docker compose up -d --build
printf '\nFinanceFlow: http://localhost:8081\n'
