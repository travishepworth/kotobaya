"""Build, check, diff and patch the device SQLite file."""

from __future__ import annotations

import sqlite3
from collections.abc import Iterable, Iterator, Mapping
from pathlib import Path
from typing import Any

from .schema import DDL, TABLE_BY_NAME, TABLES, USER_VERSION, Table

Row = tuple[Any, ...]
Diff = dict[str, Any]
DIFF_FORMAT = "kotobaya-dict-diff"
DIFF_FORMAT_VERSION = 1


def _pk_of(t: Table, row: Row) -> Row:
    return tuple(row[t.columns.index(c)] for c in t.pk)


def build_sqlite(path: Path, rows: Mapping[str, Iterable[Row]]) -> dict[str, int]:
    """Create ``path`` (replacing it) from per-table rows; returns rows inserted per table.

    Rows are inserted in primary-key order. Every row is inserted: a duplicate primary key or a
    NOT NULL violation raises instead of dropping data.
    """
    unknown = set(rows) - set(TABLE_BY_NAME)
    if unknown:
        raise ValueError(f"unknown tables: {sorted(unknown)}")
    path.unlink(missing_ok=True)
    con = sqlite3.connect(path)
    try:
        con.executescript(DDL)
        counts: dict[str, int] = {}
        for t in TABLES:
            data = sorted(rows.get(t.name, ()), key=lambda r: _pk_of(t, r))
            ph = ",".join("?" * len(t.columns))
            con.executemany(f"INSERT INTO {t.name} ({','.join(t.columns)}) VALUES ({ph})", data)
            counts[t.name] = len(data)
        con.execute(f"PRAGMA user_version = {USER_VERSION}")
        con.commit()
        con.execute("VACUUM")
    finally:
        con.close()
    problems = check_package(path)
    if problems:
        raise ValueError("; ".join(problems))
    return counts


def open_readonly(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)


def check_package(path: Path) -> list[str]:
    """Problems found in a package file: wrong user_version or dangling child rows."""
    con = open_readonly(path)
    try:
        problems: list[str] = []
        uv = con.execute("PRAGMA user_version").fetchone()[0]
        if uv != USER_VERSION:
            problems.append(f"user_version {uv} != {USER_VERSION}")
        for table, rowid, parent, _fk in con.execute("PRAGMA foreign_key_check"):
            problems.append(f"dangling {table} row {rowid} -> {parent}")
        return problems
    finally:
        con.close()


def row_counts(con: sqlite3.Connection) -> dict[str, int]:
    return {t.name: con.execute(f"SELECT count(*) FROM {t.name}").fetchone()[0] for t in TABLES}


def _ordered(con: sqlite3.Connection, t: Table) -> Iterator[Row]:
    cur = con.execute(f"SELECT {','.join(t.columns)} FROM {t.name} ORDER BY {','.join(t.pk)}")
    return iter(cur)


def compute_diff(old: Path, new: Path) -> Diff:
    """Diff of two package files (same scope) as a JSON-able dict; see the package docstring."""
    a, b = open_readonly(old), open_readonly(new)
    try:
        rel_a, scope_a = a.execute("SELECT id, scope FROM dict_release").fetchone()
        rel_b, scope_b = b.execute("SELECT id, scope FROM dict_release").fetchone()
        if scope_a != scope_b:
            raise ValueError(f"scope differs: {scope_a} vs {scope_b}")
        tables: dict[str, Any] = {}
        for t in TABLES:
            up: list[list[Any]] = []
            dele: list[list[Any]] = []
            ia, ib = _ordered(a, t), _ordered(b, t)
            ra, rb = next(ia, None), next(ib, None)
            while ra is not None or rb is not None:
                if rb is None:
                    assert ra is not None
                    dele.append(list(_pk_of(t, ra)))
                    ra = next(ia, None)
                    continue
                if ra is None:
                    up.append(list(rb))
                    rb = next(ib, None)
                    continue
                ka, kb = _pk_of(t, ra), _pk_of(t, rb)
                if ka < kb:
                    dele.append(list(ka))
                    ra = next(ia, None)
                elif ka > kb:
                    up.append(list(rb))
                    rb = next(ib, None)
                else:
                    if ra != rb:
                        up.append(list(rb))
                    ra, rb = next(ia, None), next(ib, None)
            tables[t.name] = {
                "columns": list(t.columns),
                "pk": list(t.pk),
                "upsert": up,
                "delete": dele,
            }
        return {
            "format": DIFF_FORMAT,
            "format_version": DIFF_FORMAT_VERSION,
            "from_release": rel_a,
            "to_release": rel_b,
            "scope": scope_b,
            "tables": tables,
            "row_counts": row_counts(b),
        }
    finally:
        a.close()
        b.close()


def apply_diff(db: Path, diff: Diff) -> None:
    """Patch the package at ``db`` in place to the diff's target release (one transaction)."""
    if diff.get("format") != DIFF_FORMAT or diff.get("format_version") != DIFF_FORMAT_VERSION:
        raise ValueError("unsupported diff format")
    con = sqlite3.connect(db, isolation_level=None)
    try:
        rel, scope = con.execute("SELECT id, scope FROM dict_release").fetchone()
        if rel != diff["from_release"] or scope != diff["scope"]:
            raise ValueError(
                f"diff is {diff['from_release']}/{diff['scope']}, file is {rel}/{scope}"
            )
        con.execute("BEGIN")
        try:
            tables = diff["tables"]
            for t in reversed(TABLES):
                where = " AND ".join(f"{c} = ?" for c in t.pk)
                con.executemany(f"DELETE FROM {t.name} WHERE {where}", tables[t.name]["delete"])
            for t in TABLES:
                cols = tables[t.name]["columns"]
                if cols != list(t.columns):
                    raise ValueError(f"diff columns for {t.name} do not match the schema")
                ph = ",".join("?" * len(cols))
                con.executemany(
                    f"INSERT OR REPLACE INTO {t.name} ({','.join(cols)}) VALUES ({ph})",
                    tables[t.name]["upsert"],
                )
            counts = row_counts(con)
            if counts != diff["row_counts"]:
                raise ValueError(f"row counts after apply differ: {counts}")
            con.execute("COMMIT")
        except BaseException:
            con.execute("ROLLBACK")
            raise
    finally:
        con.close()
