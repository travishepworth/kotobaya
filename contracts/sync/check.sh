#!/usr/bin/env bash
# Verifies the sync contract. Run from anywhere:
#
#   contracts/sync/check.sh
#
# Checks (all in tools/validate.py):
#   1. every *.schema.json is valid JSON Schema 2020-12;
#   2. every op type in ops/index.json has a payload schema, an example and an
#      OPS.md section with a conflict rule;
#   3. every example validates against its schema, every examples/invalid
#      instance is rejected;
#   4. every scenario's expected state follows from the rules (tools/model.py),
#      including the idempotency and interleaving claims.
#
# Needs python3 with jsonschema >= 4.18, or uv (which fetches it on first run).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if python3 -c 'import jsonschema, referencing' >/dev/null 2>&1; then
  exec python3 "$here/tools/validate.py"
elif command -v uv >/dev/null 2>&1; then
  exec uv run --no-project --with 'jsonschema>=4.18' python "$here/tools/validate.py"
else
  echo "contracts/sync/check.sh: need python3 with the jsonschema package (>= 4.18), or uv" >&2
  exit 1
fi
