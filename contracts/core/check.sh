#!/usr/bin/env bash
# Self-check of contracts/core (T006). Needs python3 (stdlib only).
#   1. search.ebnf accepts every "+" and rejects every "-" line of search-examples.tsv
#   2. OpType matches contracts/sync/ops/index.json; every public Kotlin contract member has KDoc;
#      @JsExport only in CoreJs.kt
# Compilation for jvm/android/js is checked by `make check MODULE=core` / `MODULE=web`.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
python3 -I "$here/check_search.py"
python3 -I "$here/check_kotlin.py"
echo "contracts/core: OK"
