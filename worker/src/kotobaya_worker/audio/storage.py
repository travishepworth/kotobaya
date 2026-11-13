"""Object-storage glue for clip cutting: stream a source blob to disk, store clips by hash."""

from __future__ import annotations

import hashlib
import http.client
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any
from urllib.parse import quote, urlsplit

from kotobaya_worker.audio.clips import CONTENT_TYPE, ClipStore, sha256_file
from kotobaya_worker.dict.package.s3 import S3Client, blob_key, sign_headers

_EMPTY_SHA = hashlib.sha256(b"").hexdigest()


def download_blob(client: S3Client, blob_hash: str, dest: Path) -> None:
    """Stream `blob_hash` to `dest` in chunks (never whole in memory) and verify the hash."""
    cfg = client.cfg
    u = urlsplit(cfg.endpoint)
    path = f"/{cfg.bucket}/{blob_key(blob_hash)}"
    headers = sign_headers(cfg, "GET", path, u.netloc, _EMPTY_SHA, datetime.now(UTC))
    cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    conn = cls(u.netloc, timeout=300)
    h = hashlib.sha256()
    try:
        conn.request("GET", quote(path, safe="/-_.~"), headers=headers)
        resp = conn.getresponse()
        if resp.status == 404:
            raise FileNotFoundError(f"blob {blob_hash} is not in object storage")
        if resp.status != 200:
            raise RuntimeError(f"download of {blob_hash} failed: HTTP {resp.status}")
        with dest.open("wb") as f:
            while chunk := resp.read(1 << 20):
                h.update(chunk)
                f.write(chunk)
    except BaseException:
        dest.unlink(missing_ok=True)
        raise
    finally:
        conn.close()
    if h.hexdigest() != blob_hash:
        dest.unlink(missing_ok=True)
        raise ValueError(f"blob {blob_hash} is corrupt (hash mismatch)")


@contextmanager
def fetched_source(client: S3Client, blob_hash: str) -> Iterator[Path]:
    """Temp file holding the source blob; removed on exit, also on error."""
    with TemporaryDirectory(prefix="kotobaya-src-") as tmp:
        dest = Path(tmp) / "source"
        download_blob(client, blob_hash, dest)
        yield dest


def make_clip_store(client: S3Client, conn: Any) -> ClipStore:
    """Store a clip in S3 by SHA-256, then insert its `app.blob` row (kind audio, origin clip).

    The row follows the durable upload; an existing hash is a no-op for both. The caller commits
    `conn` once the batch is done (rows for already-uploaded clips are harmless if it does not).
    """
    client.ensure_bucket()

    def store(path: Path) -> str:
        digest = sha256_file(path)
        client.put_blob(path, digest)
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO app.blob (hash, size_bytes, content_type, kind, origin)
                   VALUES (%s, %s, %s, 'audio', 'clip') ON CONFLICT (hash) DO NOTHING""",
                (digest, path.stat().st_size, CONTENT_TYPE),
            )
        return digest

    return store
