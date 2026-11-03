#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077
mkdir -p backups
backup="backups/financeflow-$(date +%Y%m%d-%H%M%S).dump"
trap 'rm -f -- "$backup.tmp"' EXIT
sudo docker compose exec -T go_db sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup.tmp"
mv -- "$backup.tmp" "$backup"
printf 'Backup salvo: %s\n' "$backup"
