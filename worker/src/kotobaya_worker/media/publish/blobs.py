"""Upload snapshot bytes under their SHA-256, then record the ``app.blob`` row (SNAPSHOT.md 2.6)."""

from __future__ import annotations

import hashlib
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import psycopg

from kotobaya_worker.dict.package.s3 import S3Client

CONTENT_TYPE = "application/zstd"


@dataclass(frozen=True)
class BlobRef:
    blob_hash: str
    size_bytes: int


class ObjectStore(Protocol):
    def put(self, blob_hash: str, data: bytes) -> None:
        """Store ``data`` under ``blob_hash``; storing existing bytes again is a no-op."""
        ...


class BlobTable(Protocol):
    def insert_snapshot(self, blob_hash: str, size_bytes: int) -> None:
        """``INSERT ... ON CONFLICT (hash) DO NOTHING`` of a snapshot/build row."""
        ...


class S3ObjectStore:
    def __init__(self, client: S3Client) -> None:
        self._client = client

    def put(self, blob_hash: str, data: bytes) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / blob_hash
            p.write_bytes(data)
            self._client.put_blob(p, blob_hash)  # HEADs first: existing object = no-op


class PgBlobTable:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def insert_snapshot(self, blob_hash: str, size_bytes: int) -> None:
        self._c.execute(
            "insert into app.blob (hash, size_bytes, content_type, kind, origin) "
            "values (%s, %s, %s, 'snapshot', 'build') on conflict (hash) do nothing",
            (blob_hash, size_bytes, CONTENT_TYPE),
        )


def upload_snapshot(data: bytes, store: ObjectStore, table: BlobTable) -> BlobRef:
    """Object first (durable), row second. Same bytes again changes nothing."""
    h = hashlib.sha256(data).hexdigest()
    store.put(h, data)
    table.insert_snapshot(h, len(data))
    return BlobRef(h, len(data))
