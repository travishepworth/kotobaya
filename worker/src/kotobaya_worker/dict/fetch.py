"""Download source files with SHA-256 verification and a local cache."""

from __future__ import annotations

import hashlib
import os
import shutil
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from pathlib import Path

# The network is injected: a fetcher writes the bytes at ``url`` into ``dest`` and may return the
# response headers (``Last-Modified`` / ``ETag`` are recorded in provenance when present).
Fetcher = Callable[[str, Path], Mapping[str, str] | None]

DEFAULT_CACHE_DIR = Path("~/.cache/kotobaya").expanduser()

JMDICT_URL = "http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
KANJIDIC2_URL = "http://www.edrdg.org/kanjidic/kanjidic2.xml.gz"
KRAD_URL = "http://ftp.edrdg.org/pub/Nihongo/kradzip.zip"


class ChecksumMismatch(Exception):
    def __init__(self, name: str, expected: str, actual: str) -> None:
        super().__init__(f"{name}: sha256 mismatch (expected {expected}, got {actual})")
        self.name = name
        self.expected = expected
        self.actual = actual


@dataclass(frozen=True)
class SourceSpec:
    """One downloadable source.

    ``sha256`` is an optional pin (ADR-020): when set the downloaded bytes must match exactly;
    when ``None`` (the EDRDG daily-changing files) whatever was fetched is accepted and its
    digest recorded. ``last_modified`` / ``etag`` are provenance only.
    """

    name: str  # cache file name, e.g. JMdict_e.gz
    url: str
    sha256: str | None = None
    last_modified: str | None = None
    etag: str | None = None

    def provenance(self, license: str | None = None) -> dict[str, str]:
        out: dict[str, str] = {"url": self.url}
        if self.sha256:
            out["sha256"] = self.sha256
        if self.last_modified:
            out["last_modified"] = self.last_modified
        if self.etag:
            out["etag"] = self.etag
        if license:
            out["license"] = license
        return out


@dataclass(frozen=True)
class FetchedSource:
    path: Path
    spec: SourceSpec  # sha256 / last_modified / etag filled in from what was fetched


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def urllib_fetcher(url: str, dest: Path) -> Mapping[str, str] | None:
    """Default network fetcher (stdlib only)."""
    with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out)
        return {k.lower(): v for k, v in resp.headers.items()}


def fetch_source(spec: SourceSpec, cache_dir: Path, fetch: Fetcher) -> FetchedSource:
    """Fetch ``spec`` and return the file plus the spec completed with its digest.

    Pinned: a cached file with the pinned digest is reused, else downloaded and verified
    (:class:`ChecksumMismatch` on a difference). Unpinned: always downloaded (the source changes
    daily); the digest and any Last-Modified / ETag are recorded on the returned spec.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = cache_dir / spec.name
    expected = spec.sha256.lower() if spec.sha256 else None
    if expected and target.is_file() and sha256_file(target) == expected:
        return FetchedSource(target, spec)
    part = cache_dir / (spec.name + ".part")
    try:
        headers = fetch(spec.url, part) or {}
        actual = sha256_file(part)
        if expected and actual != expected:
            raise ChecksumMismatch(spec.name, expected, actual)
        os.replace(part, target)
    finally:
        part.unlink(missing_ok=True)
    h = {k.lower(): v for k, v in headers.items()}
    return FetchedSource(
        target,
        replace(
            spec,
            sha256=actual,
            last_modified=h.get("last-modified", spec.last_modified),
            etag=h.get("etag", spec.etag),
        ),
    )


def ensure_source(spec: SourceSpec, cache_dir: Path, fetch: Fetcher) -> Path:
    """Return the verified cached file, downloading only if absent or its checksum differs."""
    return fetch_source(spec, cache_dir, fetch).path
