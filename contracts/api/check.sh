#!/usr/bin/env bash
# Verifies the API contract. Run from anywhere:
#
#   contracts/api/check.sh
#
# Steps:
#   1. openapi.yaml is valid OpenAPI 3.1 and passes the lint rules in redocly.yaml, including
#      "every example validates against its schema".            (needs node/npx, network on first run)
#   2. check_contract.py: every operation declares a scope, every mutating operation declares
#      the sync ops it emits, every body has an example, and SUMMARY.md's checklists cover every
#      operation and every learning-data table.                 (needs python3)
#
# The push/pull bodies reference ../sync/batch.schema.json (owned by T005). If that file is not
# present yet, the check runs against a copy with a permissive stand-in and says so.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

REDOCLY="@redocly/cli@2.77.0"

mkdir -p "$work/contracts/api" "$work/contracts/sync"
cp "$here/openapi.yaml" "$here/redocly.yaml" "$work/contracts/api/"
if [ -d "$root/contracts/sync" ]; then
  cp -R "$root/contracts/sync/." "$work/contracts/sync/"
fi
if [ ! -f "$work/contracts/sync/batch.schema.json" ]; then
  echo "NOTE: contracts/sync/batch.schema.json not found; using a permissive stand-in ({})."
  printf '{"$schema":"https://json-schema.org/draft/2020-12/schema","description":"stand-in"}\n' \
    > "$work/contracts/sync/batch.schema.json"
fi

cd "$work/contracts/api"

echo "== lint openapi.yaml"
npx --yes "$REDOCLY" lint openapi.yaml --config redocly.yaml --format stylish

echo "== bundle"
npx --yes "$REDOCLY" bundle openapi.yaml --config redocly.yaml -o "$work/openapi.json" >/dev/null

echo "== contract rules"
python3 "$here/check_contract.py" "$work/openapi.json" "$here/SUMMARY.md" "$root/contracts/db/SCHEMA.md"

echo "contracts/api: OK"
