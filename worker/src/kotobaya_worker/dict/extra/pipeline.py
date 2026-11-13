"""Fetch + verify, parse and load pitch, frequency lists and Tatoeba for an existing release.

The set of frequency lists is configuration (docs/DECISIONS.md leaves the choice open); each
list carries its own pinned download. Entry point for T109 after ``dict.pipeline.refresh``.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import psycopg

from ..dburl import to_conninfo
from ..fetch import (
    DEFAULT_CACHE_DIR,
    Fetcher,
    SourceSpec,
    fetch_source,
    urllib_fetcher,
)
from .freq import parse_freq_zip
from .index import load_index
from .kanjium import parse_accents
from .load import (
    CoverageReport,
    ExampleResult,
    FreqListConfig,
    FreqResult,
    PitchResult,
    begin,
    coverage,
    load_examples,
    load_freq_list,
    load_pitch,
    recompute_freq_rank,
    record_source,
)
from .tatoeba import build_link_index, parse_pairs


@dataclass(frozen=True)
class FreqListSource:
    config: FreqListConfig
    source: SourceSpec  # Yomitan frequency dictionary zip


@dataclass(frozen=True)
class ExtraSources:
    """Any part may be omitted; frequency lists apply in the order given (priority is explicit)."""

    kanjium: SourceSpec | None = None
    frequency: tuple[FreqListSource, ...] = ()
    tatoeba: SourceSpec | None = None  # jpn-eng pairs TSV (optionally .bz2)


@dataclass(frozen=True)
class ExtraResult:
    pitch: PitchResult | None
    freq: tuple[FreqResult, ...]
    examples: ExampleResult | None
    report: CoverageReport


def refresh_extra(
    database_url: str,
    release_id: int,
    sources: ExtraSources,
    *,
    cache_dir: Path = DEFAULT_CACHE_DIR,
    fetch: Fetcher = urllib_fetcher,
) -> ExtraResult:
    """One transaction; nothing is written if any download fails its checksum.

    ``database_url`` is the JDBC-form ``DATABASE_URL``. Idempotent for a given release id.
    """
    kj = fetch_source(sources.kanjium, cache_dir, fetch) if sources.kanjium else None
    fq = [(f, fetch_source(f.source, cache_dir, fetch)) for f in sources.frequency]
    tt = fetch_source(sources.tatoeba, cache_dir, fetch) if sources.tatoeba else None

    pitch: PitchResult | None = None
    examples: ExampleResult | None = None
    freqs: list[FreqResult] = []
    with psycopg.connect(to_conninfo(database_url)) as conn, conn.transaction():
        begin(conn, release_id)
        idx = load_index(conn)
        if kj:
            pitch = load_pitch(conn, idx, parse_accents(kj.path))
            record_source(
                conn,
                release_id,
                "kanjium",
                kj.spec.provenance("CC BY-SA 4.0"),
            )
        for spec, path in fq:  # spec: config; path: fetched source
            freqs.append(load_freq_list(conn, idx, spec.config, parse_freq_zip(path.path)))
            record_source(
                conn,
                release_id,
                f"freq:{spec.config.id}",
                path.spec.provenance(),
            )
        if freqs:
            recompute_freq_rank(conn)
        if tt:
            examples = load_examples(conn, build_link_index(idx), parse_pairs(tt.path))
            record_source(
                conn,
                release_id,
                "tatoeba",
                tt.spec.provenance("CC BY 2.0 FR"),
            )
        report = coverage(conn)
    print(report.render())
    return ExtraResult(pitch, tuple(freqs), examples, report)
