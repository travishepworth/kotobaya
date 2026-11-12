"""Download source files with SHA-256 verification and a local cache."""

from __future__ import annotations

import hashlib
import os
import shutil
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

# The network is injected: a fetcher writes the bytes at ``url`` into ``dest``.
Fetcher = Callable[[str, Path], None]

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
    """One downloadable source. ``sha256`` is the pinned checksum of the downloaded bytes."""

    name: str  # cache file name, e.g. JMdict_e.gz
    url: str
    sha256: str


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def urllib_fetcher(url: str, dest: Path) -> None:
    """Default network fetcher (stdlib only)."""
    with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as out:
        shutil.copyfileobj(resp, out)


def ensure_source(spec: SourceSpec, cache_dir: Path, fetch: Fetcher) -> Path:
    """Return the verified cached file, downloading only if absent or its checksum differs."""
    expected = spec.sha256.lower()
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = cache_dir / spec.name
    if target.is_file() and sha256_file(target) == expected:
        return target
    part = cache_dir / (spec.name + ".part")
    try:
        fetch(spec.url, part)
        actual = sha256_file(part)
        if actual != expected:
            raise ChecksumMismatch(spec.name, expected, actual)
        os.replace(part, target)
    finally:
        part.unlink(missing_ok=True)
    return target
