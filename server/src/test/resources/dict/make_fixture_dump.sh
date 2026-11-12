#!/usr/bin/env bash
# Regenerates ref_fixture.sql.gz: the T101 worker loader run on fixtures/dict, dumped data-only (COPY format).
# Needs docker and uv. Run: bash server/src/test/resources/dict/make_fixture_dump.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../../../.." && pwd)
OUT="$HERE/ref_fixture.sql.gz"
NAME=kotobaya-t106-dump-$$
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=pw \
  -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --lc-ctype=C.UTF-8" -p 127.0.0.1::5432 postgres:17 >/dev/null
PORT=$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')
for _ in $(seq 60); do
  sleep 1
  if docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1; then sleep 3; break; fi
done
docker exec -i "$NAME" psql -q -U postgres -v ON_ERROR_STOP=1 < "$ROOT/contracts/db/V1__init.sql" >/dev/null
cd "$ROOT/worker"
uv run --frozen python - "$PORT" <<'PY'
import sys
from pathlib import Path

import psycopg

from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import ReleaseInfo

f = Path("../fixtures/dict")
with psycopg.connect(host="127.0.0.1", port=int(sys.argv[1]), user="postgres", password="pw", dbname="postgres") as c:
    print(
        load_release(
            c,
            ReleaseInfo(1, "2026.11.1", "2026-11-12T00:00:00+00:00"),
            parse_jmdict(f / "JMdict_subset.xml"),
            parse_kanjidic(f / "kanjidic2_subset.xml"),
        )
    )
PY
docker exec "$NAME" pg_dump -U postgres --data-only --no-owner \
  -t ref.dict_release -t ref.dict_entry -t ref.dict_entry_form -t ref.dict_entry_gloss -t ref.entry_kanji -t ref.kanji \
  | gzip -9n > "$OUT"
ls -l "$OUT"
