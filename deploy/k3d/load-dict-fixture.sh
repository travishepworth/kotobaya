#!/usr/bin/env bash
# One-off: load the T101 JMdict/KANJIDIC2 fixture subset into the cluster's Postgres via the worker pod.
# Usage: deploy/k3d/load-dict-fixture.sh     env: CLUSTER (default kotobaya), NS (default kotobaya)
set -euo pipefail
NS="${NS:-kotobaya}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
kubectl config use-context "k3d-${CLUSTER:-kotobaya}" >/dev/null
POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/component=worker -o jsonpath='{.items[0].metadata.name}')
for f in JMdict_subset.xml kanjidic2_subset.xml; do
  kubectl -n "$NS" exec -i "$POD" -- sh -c "cat > /tmp/$f" <"$ROOT/fixtures/dict/$f"
done
kubectl -n "$NS" exec -i "$POD" -- python - <<'PY'
import os
from pathlib import Path

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.dict.kanjidic import parse_kanjidic
from kotobaya_worker.dict.load import load_release
from kotobaya_worker.dict.models import ReleaseInfo

info = ReleaseInfo(id=1, version="fixture", built_at="2026-11-12T00:00:00Z",
                   sources={"fixture": {"license": "CC BY-SA 4.0"}})
with psycopg.connect(to_conninfo(os.environ["DATABASE_URL"])) as conn:
    print(load_release(conn, info, parse_jmdict(Path("/tmp/JMdict_subset.xml")),
                       parse_kanjidic(Path("/tmp/kanjidic2_subset.xml"))))
PY
