"""Reuse the throwaway-Postgres fixtures of tests/runner (docker; skipped when docker is absent)."""

from __future__ import annotations

import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "jpdb_history_pg_fixtures", Path(__file__).resolve().parents[2] / "runner" / "conftest.py"
)
assert _spec is not None and _spec.loader is not None
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

pg_server = _mod.pg_server
db_url = _mod.db_url
