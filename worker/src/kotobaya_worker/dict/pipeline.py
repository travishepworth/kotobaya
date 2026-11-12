"""End to end: fetch + verify sources, parse, load one release. Entry point for T109."""

from __future__ import annotations

import zipfile
from dataclasses import dataclass, replace
from pathlib import Path

import psycopg

from .dburl import to_conninfo
from .fetch import (
    DEFAULT_CACHE_DIR,
    JMDICT_URL,
    KANJIDIC2_URL,
    KRAD_URL,
    Fetcher,
    SourceSpec,
    ensure_source,
    urllib_fetcher,
)
from .jmdict import parse_jmdict
from .kanjidic import merge_krad, parse_kanjidic, parse_krad, with_components
from .load import load_release
from .models import LoadResult, ReleaseInfo

LICENSE = "CC BY-SA 4.0"


@dataclass(frozen=True)
class Sources:
    """Pinned downloads for one refresh (checksums come from the job configuration)."""

    jmdict: SourceSpec
    kanjidic: SourceSpec
    krad: SourceSpec

    @staticmethod
    def edrdg(jmdict_sha256: str, kanjidic_sha256: str, krad_sha256: str) -> Sources:
        return Sources(
            SourceSpec("JMdict_e.gz", JMDICT_URL, jmdict_sha256),
            SourceSpec("kanjidic2.xml.gz", KANJIDIC2_URL, kanjidic_sha256),
            SourceSpec("kradzip.zip", KRAD_URL, krad_sha256),
        )


def read_krad_zip(path: Path) -> dict[str, tuple[str, ...]]:
    """``kradfile`` (required) merged with ``kradfile2`` (optional, extra kanji)."""
    with zipfile.ZipFile(path) as z:
        members = {Path(n).name.lower(): n for n in z.namelist()}
        if "kradfile" not in members:
            raise ValueError(f"{path.name}: no kradfile member")
        tables = [parse_krad(z.read(members["kradfile"]))]
        if "kradfile2" in members:
            tables.append(parse_krad(z.read(members["kradfile2"])))
    return merge_krad(*tables)


def refresh(
    database_url: str,
    release: ReleaseInfo,
    sources: Sources,
    *,
    cache_dir: Path = DEFAULT_CACHE_DIR,
    fetch: Fetcher = urllib_fetcher,
) -> LoadResult:
    """Download (checksum-verified, cached), parse and load. Nothing is written on any failure.

    ``database_url`` is the JDBC-form ``DATABASE_URL``.
    """
    # Every download is verified before the database is touched.
    jm = ensure_source(sources.jmdict, cache_dir, fetch)
    kd = ensure_source(sources.kanjidic, cache_dir, fetch)
    kz = ensure_source(sources.krad, cache_dir, fetch)
    krad = read_krad_zip(kz)
    info = replace(
        release,
        sources={
            "jmdict": {
                "url": sources.jmdict.url,
                "sha256": sources.jmdict.sha256,
                "license": LICENSE,
            },
            "kanjidic2": {
                "url": sources.kanjidic.url,
                "sha256": sources.kanjidic.sha256,
                "license": LICENSE,
            },
            "kradfile": {
                "url": sources.krad.url,
                "sha256": sources.krad.sha256,
                "license": LICENSE,
            },
            **release.sources,
        },
    )
    with psycopg.connect(to_conninfo(database_url)) as conn:
        return load_release(conn, info, parse_jmdict(jm), with_components(parse_kanjidic(kd), krad))
