"""Build the artifacts of one release: compress, hash, upload, register, describe."""

from __future__ import annotations

import hashlib
import io
import json
import sqlite3
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
import zstandard

from .s3 import S3Client
from .schema import USER_VERSION
from .source import TOP_N_DEFAULT, read_rows, referenced_seqs
from .sqlite import Diff, apply_diff, build_sqlite, compute_diff, open_readonly

ZSTD_LEVEL = 19
FULL_FORMAT_VERSION = USER_VERSION
BLOB_CONTENT_TYPE = "application/zstd"


def compress_file(src: Path, dst: Path, level: int = ZSTD_LEVEL) -> tuple[str, int]:
    """zstd-compress ``src`` into ``dst``; returns (sha256 hex of the compressed bytes, size)."""
    h = hashlib.sha256()
    size = 0
    cctx = zstandard.ZstdCompressor(level=level)
    with src.open("rb") as fi, dst.open("wb") as fo, cctx.stream_writer(fo, closefd=False) as w:
        while chunk := fi.read(1 << 20):
            w.write(chunk)
    with dst.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def decompress_file(src: Path, dst: Path) -> None:
    with src.open("rb") as fi, dst.open("wb") as fo:
        zstandard.ZstdDecompressor().copy_stream(fi, fo)


def diff_to_bytes(diff: Diff) -> bytes:
    return json.dumps(diff, ensure_ascii=False, separators=(",", ":")).encode()


def diff_from_file(path: Path) -> Diff:
    with path.open("rb") as f:
        raw = zstandard.ZstdDecompressor().stream_reader(f).read()
    out: Diff = json.loads(raw)
    return out


def apply_diff_file(db: Path, diff_zst: Path) -> None:
    """Apply a downloaded ``diff`` artifact to the package file ``db`` in place."""
    apply_diff(db, diff_from_file(diff_zst))


@dataclass(frozen=True)
class BuiltArtifact:
    kind: str  # 'full' | 'diff'
    scope: str
    from_release: int | None
    blob_hash: str
    size_bytes: int
    path: Path
    format_version: int = 1
    uncompressed_size_bytes: int = 0

    def manifest_entry(self) -> dict[str, Any]:
        """One element of ``ref.dict_release.artifacts`` / ``DictRelease.artifacts``."""
        return {
            "kind": self.kind,
            "scope": self.scope,
            "from_release": self.from_release,
            "format_version": self.format_version,
            "blob_hash": self.blob_hash,
            "size_bytes": self.size_bytes,
            "uncompressed_size_bytes": self.uncompressed_size_bytes,
        }


def build_artifacts(
    package: Path,
    scope: str,
    out_dir: Path,
    base: Path | None = None,
    s3: S3Client | None = None,
) -> list[BuiltArtifact]:
    """Compress ``package`` (and its diff from ``base``) into ``out_dir``.

    ``base`` MUST be the decompressed, hash-verified published ``full`` of the older release
    (see :func:`fetch_base`), never a file rebuilt from ``ref.*``. Uploads each blob to S3
    (creating the bucket first) when ``s3`` is given.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    built: list[BuiltArtifact] = []
    tmp_full = out_dir / "full.zst"
    h, n = compress_file(package, tmp_full)
    built.append(
        BuiltArtifact(
            "full", scope, None, h, n, tmp_full.rename(out_dir / f"{h}.zst"),
            FULL_FORMAT_VERSION, package.stat().st_size,
        )
    )  # fmt: skip
    if base is not None:
        diff = compute_diff(base, package)
        if diff["scope"] != scope:
            raise ValueError(f"diff scope {diff['scope']} != {scope}")
        raw = out_dir / "diff.json"
        raw.write_bytes(diff_to_bytes(diff))
        tmp = out_dir / "diff.zst"
        h, n = compress_file(raw, tmp)
        raw_size = raw.stat().st_size
        raw.unlink()
        built.append(
            BuiltArtifact(
                "diff", scope, int(diff["from_release"]), h, n, tmp.rename(out_dir / f"{h}.zst"),
                int(diff["format_version"]), raw_size,
            )
        )  # fmt: skip
    if s3 is not None:
        s3.ensure_bucket()
        for a in built:
            s3.put_blob(a.path, a.blob_hash)
    return built


def register_blobs(conn: psycopg.Connection[Any], artifacts: Iterable[BuiltArtifact]) -> None:
    """Insert the ``app.blob`` rows (DICT_PACKAGE.md 4) and commit.

    Call after the objects are durably stored and before the artifacts are written to
    ``ref.dict_release.artifacts``.
    """
    with conn.cursor() as cur:
        for a in artifacts:
            cur.execute(
                """INSERT INTO app.blob (hash, size_bytes, content_type, kind, origin)
                   VALUES (%s, %s, %s, 'snapshot', 'build') ON CONFLICT (hash) DO NOTHING""",
                (a.blob_hash, a.size_bytes, BLOB_CONTENT_TYPE),
            )
    conn.commit()


def fetch_base(
    published: Iterable[dict[str, Any]],
    from_release: int,
    scope: str,
    fetch: Callable[[str], bytes | None],
    work_dir: Path,
) -> Path | None:
    """The decompressed published ``full`` of ``from_release`` for ``scope``, or ``None``.

    ``published`` is that release's ``ref.dict_release.artifacts``. The compressed bytes
    (``fetch(blob_hash)``) must hash to ``blob_hash``, and the file must really be
    ``(from_release, scope)`` at the current ``user_version``; anything else (no such artifact,
    missing blob, hash mismatch, unreadable file) gives ``None`` and therefore no diff.
    """
    entry = next(
        (
            a
            for a in published
            if a.get("kind") == "full"
            and a.get("scope") == scope
            and a.get("format_version") == FULL_FORMAT_VERSION
        ),
        None,
    )
    if entry is None:
        return None
    blob_hash = entry["blob_hash"]
    data = fetch(blob_hash)
    if data is None or hashlib.sha256(data).hexdigest() != blob_hash:
        return None
    work_dir.mkdir(parents=True, exist_ok=True)
    dst = work_dir / f"base-{from_release}-{scope}.sqlite"
    try:
        dst.write_bytes(zstandard.ZstdDecompressor().stream_reader(io.BytesIO(data)).read())
        con = open_readonly(dst)
        try:
            ok = con.execute("PRAGMA user_version").fetchone()[0] == USER_VERSION and [
                tuple(r) for r in con.execute("SELECT id, scope FROM dict_release")
            ] == [(from_release, scope)]
        finally:
            con.close()
    except (zstandard.ZstdError, sqlite3.DatabaseError):
        ok = False
    if not ok:
        dst.unlink(missing_ok=True)
        return None
    return dst


def build_release(
    conn: psycopg.Connection[Any],
    release_id: int,
    scope: str,
    out_dir: Path,
    referenced: Iterable[int] | None = None,
    previous_release_id: int | None = None,
    s3: S3Client | None = None,
    top_n: int = TOP_N_DEFAULT,
) -> tuple[Path, list[BuiltArtifact]]:
    """Package release ``release_id`` from Postgres for ``scope``.

    ``referenced`` defaults to the seqs of the user's notes, word statuses and deck items. The
    diff base is the published ``full`` of ``previous_release_id`` (default: the highest older
    release) downloaded from ``s3``; without ``s3``, or if it is missing or fails its hash
    check, no diff is emitted. The ``app.blob`` rows are inserted after the upload. Returns the
    uncompressed package path and the artifacts. Raises if any table's row count differs
    between ``ref.*`` and the package.
    """
    refs = referenced_seqs(conn) if referenced is None else referenced
    rows, expected = read_rows(conn, release_id, scope, refs, top_n)
    out_dir.mkdir(parents=True, exist_ok=True)
    package = out_dir / f"dict-{release_id}-{scope}.sqlite"
    counts = build_sqlite(package, rows)
    if counts != expected:
        raise ValueError(f"row counts differ: package {counts}, ref.* {expected}")
    base: Path | None = None
    if s3 is not None:
        with conn.cursor() as cur:
            if previous_release_id is None:
                cur.execute("SELECT max(id) FROM ref.dict_release WHERE id < %s", (release_id,))
                one = cur.fetchone()
                previous_release_id = None if one is None else one[0]
            if previous_release_id is not None and previous_release_id < release_id:
                cur.execute(
                    "SELECT artifacts FROM ref.dict_release WHERE id = %s", (previous_release_id,)
                )
                got = cur.fetchone()
                if got is not None:
                    base = fetch_base(
                        got[0], previous_release_id, scope, s3.get_blob, out_dir / "base"
                    )
        conn.commit()
    arts = build_artifacts(package, scope, out_dir, base, s3)
    if s3 is not None:
        register_blobs(conn, arts)
    return package, arts
