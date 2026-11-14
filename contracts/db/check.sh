#!/usr/bin/env bash
# Verifies the database contract. Run from anywhere:
#
#   contracts/db/check.sh            # everything
#   SKIP_GRADLE=1 contracts/db/check.sh   # skip the SQLDelight compile (slow first run)
#
# Steps:
#   0. media/validate.py: the snapshot archive formats, the media-publish payload,
#      their worked examples and negative cases (SNAPSHOT.md, MEDIA_PIPELINE.md).
#      optimizer/validate.py: the fsrs-params-publish payload, its worked examples and
#      negative cases (FSRS_PUBLISH.md, C-16).
#                                                              (needs python3 + jsonschema, or uv)
#   1. V1__init.sql, V2__jpdb_vocab.sql, V3__vocab_list.sql apply cleanly to a throwaway
#      postgres:17 container and test/smoke.sql, smoke_v2.sql, smoke_v3.sql pass. (needs docker)
#   2. The .sq files load into SQLite and the device schema matches the server
#      schema (same tables/columns, identical seed rows).     (needs sqlite3, python3)
#   3. The .sq files compile with the SQLDelight Gradle plugin. (needs JDK + network on first run)
#
# The container is always removed on exit.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"
container="kotobaya-contract-db-$$"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# ---------------------------------------------------------------- 0. Snapshot formats
echo "== media/validate.py: snapshot formats and examples"
if python3 -c 'import jsonschema, referencing' >/dev/null 2>&1; then
  python3 "$here/media/validate.py" >"$work/snapshot.out" || { cat "$work/snapshot.out" >&2; exit 1; }
elif command -v uv >/dev/null 2>&1; then
  uv run --no-project --with 'jsonschema>=4.18' --with 'zstandard>=0.25.0' python "$here/media/validate.py" >"$work/snapshot.out" \
    || { cat "$work/snapshot.out" >&2; exit 1; }
else
  echo "contracts/db/check.sh: need python3 with the jsonschema package (>= 4.18), or uv" >&2
  exit 1
fi
tail -n 1 "$work/snapshot.out"

echo "== optimizer/validate.py: fsrs-params-publish payload and examples"
if python3 -c 'import jsonschema, referencing' >/dev/null 2>&1; then
  python3 "$here/optimizer/validate.py" >"$work/optimizer.out" || { cat "$work/optimizer.out" >&2; exit 1; }
else
  uv run --no-project --with 'jsonschema>=4.18' python "$here/optimizer/validate.py" >"$work/optimizer.out" \
    || { cat "$work/optimizer.out" >&2; exit 1; }
fi
tail -n 1 "$work/optimizer.out"

# ---------------------------------------------------------------- 1. Postgres
echo "== postgres:17: apply V1__init.sql"
# Data directory on tmpfs: throwaway, faster, and independent of free space on the docker root.
docker run -d --name "$container" --tmpfs /var/lib/postgresql/data -e POSTGRES_PASSWORD=contract postgres:17 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$container" psql -U postgres -Atc 'select 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
# The image restarts the server once after init; make sure we talk to the final one.
sleep 2
docker exec "$container" pg_isready -U postgres -t 30 >/dev/null

psql_in() { docker exec -i "$container" psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }

psql_in <"$here/V1__init.sql"
echo "== postgres:17: test/smoke.sql"
psql_in <"$here/test/smoke.sql" >"$work/smoke.out"
grep -q '^smoke.sql: ok$' "$work/smoke.out"
echo "== postgres:17: apply V2__jpdb_vocab.sql, test/smoke_v2.sql"
psql_in <"$here/V2__jpdb_vocab.sql"
psql_in <"$here/test/smoke_v2.sql" >"$work/smoke_v2.out"
grep -q '^smoke_v2.sql: ok$' "$work/smoke_v2.out"
echo "== postgres:17: apply V3__vocab_list.sql, test/smoke_v3.sql"
psql_in <"$here/V3__vocab_list.sql"
psql_in <"$here/test/smoke_v3.sql" >"$work/smoke_v3.out"
grep -q '^smoke_v3.sql: ok$' "$work/smoke_v3.out"

echo "== postgres:17: migration refuses a C-ctype database"
psql_in -c "CREATE DATABASE c_ctype TEMPLATE template0 LC_COLLATE 'C' LC_CTYPE 'C'"
if psql_in -d c_ctype <"$here/V1__init.sql" >/dev/null 2>"$work/c.err"; then
  echo "expected V1__init.sql to fail on an LC_CTYPE=C database" >&2
  exit 1
fi
grep -q 'yields no trigrams' "$work/c.err"

# Dump server columns and seeds for the parity check.
psql_in -At -F '|' -c "
  SELECT table_name, column_name FROM information_schema.columns
   WHERE table_schema IN ('app', 'ref') ORDER BY 1, 2" >"$work/pg_columns.txt"
psql_in -At -c "
  SELECT json_agg(json_build_object('id', id, 'name', name, 'base_type', base_type, 'builtin', builtin,
                                    'fields', fields, 'templates', templates) ORDER BY id)
    FROM app.note_type" >"$work/pg_note_types.json"

# ---------------------------------------------------------------- 2. SQLite
echo "== sqlite: load .sq files"
load_sq() { # <db file> <dir>
  # SQLDelight's "AS kotlin.Type" column annotation is not SQL; strip it.
  find "$2" -name '*.sq' -print0 | sort -z | xargs -0 cat | sed -E 's/ AS kotlin\.[A-Za-z.]+//' | sqlite3 -bail "$1"
}
load_sq "$work/app.db" "$here/sqldelight/app"
load_sq "$work/ref.db" "$here/sqldelight/ref"

echo "== sqlite: device schema matches server schema"
python3 - "$work" <<'PY'
import json, sqlite3, sys

work = sys.argv[1]
pg = {}
for line in open(f"{work}/pg_columns.txt", encoding="utf-8"):
    table, column = line.rstrip("\n").split("|")
    pg.setdefault(table, set()).add(column)

def sqlite_columns(path):
    db = sqlite3.connect(path)
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")]
    return {t: {r[1] for r in db.execute(f"PRAGMA table_info({t})")} for t in tables}

# Tables that exist only on the device.
DEVICE_ONLY_TABLES = {"pending_op", "sync_state", "blob_cache"}
# Server tables deliberately absent from the device.
SERVER_ONLY_TABLES = {"device", "api_token", "change", "job", "blob", "parse_override", "server_state",
                      "jpdb_vocab", "jpdb_vocab_media",
                      "vocab_list", "vocab_list_item", "vocab_list_unmatched"}
# Per-table column differences: (extra on device, missing on device).
DIFF = {
    "note": (set(), {"search_text"}),
    "deck": ({"installed_content_version"}, set()),
    "media": ({"installed_content_version"}, set()),
    "sentence": (set(), {"unknown_count"}),
    "dict_release": ({"scope"}, {"loaded_at", "is_current", "artifacts"}),
    "dict_entry": ({"retired"}, {"added_release_id", "retired_release_id"}),
    "dict_entry_gloss": ({"gloss_lower"}, set()),
    "freq_list": (set(), {"entry_count", "source"}),
}

errors = []
device = {}
device.update(sqlite_columns(f"{work}/app.db"))
device.update(sqlite_columns(f"{work}/ref.db"))

for table, cols in sorted(device.items()):
    if table in DEVICE_ONLY_TABLES:
        continue
    if table not in pg:
        errors.append(f"device table {table} has no server table")
        continue
    extra, missing = DIFF.get(table, (set(), set()))
    if cols - pg[table] != extra:
        errors.append(f"{table}: unexpected device-only columns {sorted(cols - pg[table] - extra)} / stale allowance {sorted(extra - (cols - pg[table]))}")
    if pg[table] - cols != missing:
        errors.append(f"{table}: columns missing on device {sorted(pg[table] - cols - missing)} / stale allowance {sorted(missing - (pg[table] - cols))}")

for table in sorted(set(pg) - set(device) - SERVER_ONLY_TABLES):
    errors.append(f"server table {table} is neither on the device nor listed as server-only")

# Seeds must be identical on both sides.
pg_types = json.load(open(f"{work}/pg_note_types.json", encoding="utf-8"))
db = sqlite3.connect(f"{work}/app.db")
dev_types = [
    {"id": r[0], "name": r[1], "base_type": r[2], "builtin": bool(r[3]), "fields": json.loads(r[4]), "templates": json.loads(r[5])}
    for r in db.execute("SELECT id, name, base_type, builtin, fields, templates FROM note_type ORDER BY id")
]
if pg_types != dev_types:
    errors.append("built-in note_type seed rows differ between V1__init.sql and Learning.sq")
if [r[0] for r in db.execute("SELECT id FROM fsrs_params")] != ["00000000-0000-7000-8000-000000000010"]:
    errors.append("default fsrs_params seed row missing on device")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"   {len(device)} device tables checked against {len(pg)} server tables")
PY

# ---------------------------------------------------------------- 3. SQLDelight
if [ "${SKIP_GRADLE:-0}" = "1" ]; then
  echo "== sqldelight: SKIPPED (SKIP_GRADLE=1)"
else
  echo "== sqldelight: compile .sq files"
  "$root/gradlew" --no-daemon -q -p "$here/verify" generateSqlDelightInterface
fi

echo "contracts/db: ok"
