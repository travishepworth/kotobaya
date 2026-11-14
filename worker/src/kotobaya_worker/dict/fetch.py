"""Download source files with SHA-256 verification and a local cache."""

from __future__ import annotations

import hashlib
import http.client
import logging
import os
import shutil
import tempfile
import urllib.error
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

# Downloads per fetch: a truncated or failed transfer is retried before anything is concluded.
MAX_ATTEMPTS = 3

log = logging.getLogger("kotobaya.worker.dict.fetch")


class ChecksumMismatch(Exception):
    def __init__(self, name: str, expected: str, actual: str) -> None:
        super().__init__(f"{name}: sha256 mismatch (expected {expected}, got {actual})")
        self.name = name
        self.expected = expected
        self.actual = actual


@dataclass(frozen=True)
class SourceSpec:
    """One downloadable source.

    ``sha256`` is an optional pin (ADR-020): when ``None`` whatever was fetched is accepted and its
    digest recorded. When set and ``strict`` (immutable URLs: kanjium, tatoeba, frequency and JLPT
    lists) the downloaded bytes must match exactly. When set and not ``strict`` (the EDRDG files,
    republished daily at a fixed URL) a cached file with the pinned digest is reused, but upstream
    content that differs from the pin is accepted once two downloads agree on it: the new digest
    is recorded and becomes a new release instead of failing every refresh.
    ``last_modified`` / ``etag`` are provenance only.
    """

    name: str  # cache file name, e.g. JMdict_e.gz
    url: str
    sha256: str | None = None
    last_modified: str | None = None
    etag: str | None = None
    strict: bool = True

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
    """Default network fetcher (stdlib only). A body shorter than its Content-Length raises."""
    with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out)
        headers = {k.lower(): v for k, v in resp.headers.items()}
        size = out.tell()
    length = headers.get("content-length", "")
    if length.isdigit() and int(length) != size:
        raise http.client.IncompleteRead(b"", int(length) - size)
    return headers


# Transient transfer failures worth another attempt (HTTP errors such as 404 are not).
_TRANSIENT = (http.client.HTTPException, urllib.error.URLError, TimeoutError, ConnectionError)


def fetch_source(
    spec: SourceSpec, cache_dir: Path, fetch: Fetcher, *, attempts: int = MAX_ATTEMPTS
) -> FetchedSource:
    """Fetch ``spec`` and return the file plus the spec completed with its digest.

    Pinned: a cached file with the pinned digest is reused, else downloaded and verified. A
    download that fails in transit or differs from the pin is retried (up to ``attempts``
    downloads): a partial transfer heals on the next try. A strict pin that still differs raises
    :class:`ChecksumMismatch`; a non-strict pin accepts the new content once two consecutive
    downloads agree (upstream republished). Unpinned: always downloaded; the digest and any
    Last-Modified / ETag are recorded on the returned spec.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = cache_dir / spec.name
    expected = spec.sha256.lower() if spec.sha256 else None
    if expected and target.is_file() and sha256_file(target) == expected:
        return FetchedSource(target, spec)
    # A private temp file: concurrent refreshes never write into each other's download.
    fd, tmp = tempfile.mkstemp(prefix=spec.name + ".", suffix=".part", dir=cache_dir)
    os.close(fd)
    part = Path(tmp)
    try:
        headers, actual = _download(spec, part, expected, fetch, max(1, attempts))
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


def _download(
    spec: SourceSpec, part: Path, expected: str | None, fetch: Fetcher, attempts: int
) -> tuple[Mapping[str, str], str]:
    """Download into ``part`` until it is acceptable; returns (headers, digest)."""
    previous: str | None = None
    actual = ""
    for attempt in range(1, attempts + 1):
        try:
            headers = fetch(spec.url, part) or {}
        except _TRANSIENT as e:
            if attempt == attempts:
                raise
            log.warning("%s: download attempt %d failed (%s); retrying", spec.name, attempt, e)
            previous = None
            continue
        actual = sha256_file(part)
        if expected is None or actual == expected:
            return headers, actual
        if not spec.strict and actual == previous:
            log.warning(
                "%s: upstream content changed (pinned %s, now %s); the pin is stale, "
                "using the new content",
                spec.name,
                expected,
                actual,
            )
            return headers, actual
        previous = actual
        log.warning(
            "%s: sha256 %s differs from pin %s (attempt %d/%d)",
            spec.name,
            actual,
            expected,
            attempt,
            attempts,
        )
    assert expected is not None
    raise ChecksumMismatch(spec.name, expected, actual)


def ensure_source(spec: SourceSpec, cache_dir: Path, fetch: Fetcher) -> Path:
    """Return the verified cached file, downloading only if absent or its checksum differs."""
    return fetch_source(spec, cache_dir, fetch).path
