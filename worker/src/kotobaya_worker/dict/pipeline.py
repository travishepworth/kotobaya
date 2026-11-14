"""End to end: fetch + verify sources, parse, load one release. Entry point for T109."""

from __future__ import annotations

import zipfile
from collections.abc import Sequence
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
    fetch_source,
    urllib_fetcher,
)
from .jmdict import parse_jmdict
from .kanjidic import merge_krad, parse_kanjidic, parse_krad, with_components
from .load import load_release
from .models import LoadResult, ReleaseInfo
from .vocab import VocabSource

LICENSE = "CC BY-SA 4.0"


@dataclass(frozen=True)
class Sources:
    """Downloads for one refresh; checksum pins are optional (ADR-020)."""

    jmdict: SourceSpec
    kanjidic: SourceSpec
    krad: SourceSpec

    @staticmethod
    def edrdg(
        jmdict_sha256: str | None = None,
        kanjidic_sha256: str | None = None,
        krad_sha256: str | None = None,
    ) -> Sources:
        return Sources(
            SourceSpec("JMdict_e.gz", JMDICT_URL, jmdict_sha256, strict=False),
            SourceSpec("kanjidic2.xml.gz", KANJIDIC2_URL, kanjidic_sha256, strict=False),
            SourceSpec("kradzip.zip", KRAD_URL, krad_sha256, strict=False),
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
    vocab_lists: Sequence[VocabSource] | None = None,
) -> LoadResult:
    """Download (verified against any pin, cached), parse and load. Nothing is written on any failure.

    ``vocab_lists``: see :func:`load_release` (None leaves the vocabulary tables untouched).

    ``database_url`` is the JDBC-form ``DATABASE_URL``.
    """
    # Every download is verified before the database is touched.
    jm = fetch_source(sources.jmdict, cache_dir, fetch)
    kd = fetch_source(sources.kanjidic, cache_dir, fetch)
    kz = fetch_source(sources.krad, cache_dir, fetch)
    krad = read_krad_zip(kz.path)
    info = replace(
        release,
        sources={
            "jmdict": jm.spec.provenance(LICENSE),
            "kanjidic2": kd.spec.provenance(LICENSE),
            "kradfile": kz.spec.provenance(LICENSE),
            **release.sources,
        },
    )
    with psycopg.connect(to_conninfo(database_url)) as conn:
        return load_release(
            conn,
            info,
            parse_jmdict(jm.path),
            with_components(parse_kanjidic(kd.path), krad),
            vocab_lists,
        )
