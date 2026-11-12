"""Build the artifacts of one release: compress, hash, upload, describe."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
import zstandard

from .s3 import S3Client
from .source import TOP_N_DEFAULT, read_rows
from .sqlite import Diff, apply_diff, build_sqlite, compute_diff

ZSTD_LEVEL = 19


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

    def manifest_entry(self) -> dict[str, Any]:
        """One element of ``ref.dict_release.artifacts`` / ``DictRelease.artifacts``."""
        return {
            "kind": self.kind,
            "scope": self.scope,
            "from_release": self.from_release,
            "blob_hash": self.blob_hash,
            "size_bytes": self.size_bytes,
        }


def build_artifacts(
    package: Path,
    scope: str,
    out_dir: Path,
    previous_package: Path | None = None,
    s3: S3Client | None = None,
) -> list[BuiltArtifact]:
    """Compress ``package`` (and its diff from ``previous_package``) into ``out_dir``.

    Uploads each blob to S3 (creating the bucket first) when ``s3`` is given. The previous
    package must be of the same scope.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    built: list[BuiltArtifact] = []
    tmp_full = out_dir / "full.zst"
    h, n = compress_file(package, tmp_full)
    built.append(BuiltArtifact("full", scope, None, h, n, tmp_full.rename(out_dir / f"{h}.zst")))
    if previous_package is not None:
        diff = compute_diff(previous_package, package)
        raw = out_dir / "diff.json"
        raw.write_bytes(diff_to_bytes(diff))
        tmp = out_dir / "diff.zst"
        h, n = compress_file(raw, tmp)
        raw.unlink()
        built.append(
            BuiltArtifact(
                "diff", scope, int(diff["from_release"]), h, n, tmp.rename(out_dir / f"{h}.zst")
            )
        )
    if s3 is not None:
        s3.ensure_bucket()
        for a in built:
            s3.put_blob(a.path, a.blob_hash)
    return built


def build_release(
    conn: psycopg.Connection[Any],
    release_id: int,
    scope: str,
    out_dir: Path,
    referenced: Iterable[int] = (),
    previous_package: Path | None = None,
    s3: S3Client | None = None,
    top_n: int = TOP_N_DEFAULT,
) -> tuple[Path, list[BuiltArtifact]]:
    """Package release ``release_id`` from Postgres for ``scope``.

    Returns the uncompressed package path (keep it: it is the next release's
    ``previous_package``) and the artifacts. Raises if any table's row count differs between
    ``ref.*`` and the package.
    """
    rows, expected = read_rows(conn, release_id, scope, referenced, top_n)
    out_dir.mkdir(parents=True, exist_ok=True)
    package = out_dir / f"dict-{release_id}-{scope}.sqlite"
    counts = build_sqlite(package, rows)
    if counts != expected:
        raise ValueError(f"row counts differ: package {counts}, ref.* {expected}")
    return package, build_artifacts(package, scope, out_dir, previous_package, s3)
