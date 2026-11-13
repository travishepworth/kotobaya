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
#   3. worker-parse.yaml (cluster-internal parse service, C-12) lints like openapi.yaml.
#   4. check_parser.py: token format 1 (token.schema.json), the token example built from
#      fixtures/parse, the PARSER.md golden vectors, the C-11 snapshot examples' tokens, and
#      SentenceToken/OverrideToken alignment.          (needs python3 + jsonschema, or uv)
#
# The push/pull bodies are contracts/sync/batch.schema.json#/$defs/* (owned by T005) and several
# examples are copies of contracts/sync/examples/batches/*.json; both must be present.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

REDOCLY="@redocly/cli@2.77.0"

mkdir -p "$work/contracts/api" "$work/contracts/sync"
cp "$here/openapi.yaml" "$here/redocly.yaml" "$here/worker-parse.yaml" "$here/token.schema.json" "$work/contracts/api/"
if [ ! -f "$root/contracts/sync/batch.schema.json" ]; then
  echo "contracts/sync/batch.schema.json not found: the API contract references it." >&2
  exit 1
fi
cp -R "$root/contracts/sync/." "$work/contracts/sync/"

cd "$work/contracts/api"

echo "== lint openapi.yaml"
npx --yes "$REDOCLY" lint openapi.yaml --config redocly.yaml --format stylish

echo "== bundle"
npx --yes "$REDOCLY" bundle openapi.yaml --config redocly.yaml -o "$work/openapi.json" >/dev/null

echo "== contract rules"
python3 "$here/check_contract.py" "$work/openapi.json" "$here/SUMMARY.md" "$root/contracts/db/SCHEMA.md"

echo "== lint worker-parse.yaml"
npx --yes "$REDOCLY" lint worker-parse.yaml --config redocly.yaml --format stylish

echo "== parser contract (C-12)"
if python3 -c 'import jsonschema, referencing' >/dev/null 2>&1; then
  python3 "$here/check_parser.py" "$work/openapi.json"
elif command -v uv >/dev/null 2>&1; then
  uv run --no-project --with 'jsonschema>=4.18' python "$here/check_parser.py" "$work/openapi.json"
else
  echo "contracts/api/check.sh: need python3 with the jsonschema package (>= 4.18), or uv" >&2
  exit 1
fi

echo "contracts/api: OK"
