"""Build, check, diff and patch the device SQLite file."""

from __future__ import annotations

import hashlib
import sqlite3
import struct
from collections.abc import Iterable, Iterator, Mapping
from pathlib import Path
from typing import Any

from .schema import DDL, TABLE_BY_NAME, TABLES, USER_VERSION, Table

Row = tuple[Any, ...]
Diff = dict[str, Any]
DIFF_FORMAT = "kotobaya-dict-diff"
DIFF_FORMAT_VERSION = 1
TABLE_NAMES = tuple(t.name for t in TABLES)
EMPTY_TABLE_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"


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


def table_digest(con: sqlite3.Connection, t: Table) -> str:
    """Table digest of DICT_PACKAGE.md 3.5: SHA-256 over the self-delimiting row encoding.

    NULL = 0x00; integer = 0x01 + int64 big-endian; text = 0x02 + uint32 big-endian UTF-8 length
    + bytes. A real or blob value raises (it never occurs in a valid package).
    """
    h = hashlib.sha256()
    for row in _ordered(con, t):
        for v in row:
            if v is None:
                h.update(b"\x00")
            elif isinstance(v, int):
                h.update(b"\x01" + struct.pack(">q", v))
            elif isinstance(v, str):
                raw = v.encode("utf-8")
                h.update(b"\x02" + struct.pack(">I", len(raw)) + raw)
            else:
                raise ValueError(f"{t.name}: value of type {type(v).__name__} in a package")
    return h.hexdigest()


def table_digests(con: sqlite3.Connection) -> dict[str, str]:
    return {t.name: table_digest(con, t) for t in TABLES}


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
            "table_digests": table_digests(b),
        }
    finally:
        a.close()
        b.close()


def _validate_envelope(diff: Diff) -> None:
    """Step 1 of DICT_PACKAGE.md 3.4: format, version, exact table sets, columns and pk."""
    if diff.get("format") != DIFF_FORMAT or diff.get("format_version") != DIFF_FORMAT_VERSION:
        raise ValueError("unsupported diff format")
    for key in ("tables", "row_counts", "table_digests"):
        got = diff.get(key)
        if not isinstance(got, dict) or set(got) != set(TABLE_NAMES):
            raise ValueError(f"diff {key} is not exactly the {len(TABLE_NAMES)} dictionary tables")
    for t in TABLES:
        spec = diff["tables"][t.name]
        if spec.get("columns") != list(t.columns) or spec.get("pk") != list(t.pk):
            raise ValueError(f"diff columns/pk for {t.name} do not match the schema")
    for key in ("from_release", "to_release"):
        if type(diff.get(key)) is not int:
            raise ValueError(f"diff {key} missing or not an integer")


def _release_row(con: sqlite3.Connection) -> list[tuple[int, str]]:
    return [(r[0], r[1]) for r in con.execute("SELECT id, scope FROM dict_release")]


def apply_diff(db: Path, diff: Diff) -> None:
    """Patch the package at ``db`` in place to the diff's target release (DICT_PACKAGE.md 3.4).

    Everything is checked before COMMIT (row counts, table digests, release row, foreign keys);
    any failure rolls back and raises ``ValueError``, leaving the file untouched.
    """
    _validate_envelope(diff)
    con = sqlite3.connect(db, isolation_level=None)
    try:
        con.execute("PRAGMA foreign_keys = OFF")
        uv = con.execute("PRAGMA user_version").fetchone()[0]
        if uv != USER_VERSION:
            raise ValueError(f"file user_version {uv} != {USER_VERSION}")
        rows = _release_row(con)
        if rows != [(diff["from_release"], diff["scope"])]:
            raise ValueError(f"diff is {diff['from_release']}/{diff['scope']}, file is {rows}")
        con.execute("BEGIN")
        try:
            tables = diff["tables"]
            for t in reversed(TABLES):
                where = " AND ".join(f"{c} = ?" for c in t.pk)
                con.executemany(f"DELETE FROM {t.name} WHERE {where}", tables[t.name]["delete"])
            for t in TABLES:
                ph = ",".join("?" * len(t.columns))
                con.executemany(
                    f"INSERT OR REPLACE INTO {t.name} ({','.join(t.columns)}) VALUES ({ph})",
                    tables[t.name]["upsert"],
                )
            counts = row_counts(con)
            if counts != diff["row_counts"]:
                raise ValueError(f"row counts after apply differ: {counts}")
            digests = table_digests(con)
            bad = [n for n in TABLE_NAMES if digests[n] != diff["table_digests"][n]]
            if bad:
                raise ValueError(f"table digests after apply differ: {bad}")
            if _release_row(con) != [(diff["to_release"], diff["scope"])]:
                raise ValueError("dict_release after apply is not (to_release, scope)")
            dangling = con.execute("PRAGMA foreign_key_check").fetchall()
            if dangling:
                raise ValueError(f"foreign_key_check failed after apply: {dangling[:3]}")
            con.execute("COMMIT")
        except BaseException:
            con.execute("ROLLBACK")
            raise
    finally:
        con.close()
