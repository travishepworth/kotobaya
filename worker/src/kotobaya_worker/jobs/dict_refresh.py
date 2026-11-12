"""``dict-refresh`` handler: refresh -> refresh_extra -> build_release (+ S3) -> ref.dict_release.artifacts.

Pinned sources come from ``DICT_SOURCES_JSON`` (job configuration; checksums are never fetched blindly)::

    {"jmdict": {"sha256": "..", "url": "optional override"},
     "kanjidic": {"sha256": ".."}, "krad": {"sha256": ".."},
     "kanjium": {"url": "..", "sha256": ".."},          # optional
     "tatoeba": {"url": "..", "sha256": ".."},          # optional
     "freq_lists": {"jpdb": {"url": "..", "sha256": "..", "title": "jpdb"}}}   # optional, priority = order
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import psycopg

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.extra.load import FreqListConfig
from kotobaya_worker.dict.extra.pipeline import ExtraSources, FreqListSource, refresh_extra
from kotobaya_worker.dict.fetch import (
    DEFAULT_CACHE_DIR,
    JMDICT_URL,
    KANJIDIC2_URL,
    KRAD_URL,
    SourceSpec,
)
from kotobaya_worker.dict.models import ReleaseInfo
from kotobaya_worker.dict.package.release import build_release
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.dict.package.source import SCOPES
from kotobaya_worker.dict.pipeline import Sources, refresh
from kotobaya_worker.runner.core import JobContext

log = logging.getLogger("kotobaya.worker.dict_refresh")


@dataclass(frozen=True)
class DictSources:
    core: Sources
    kanjium: SourceSpec | None
    tatoeba: SourceSpec | None
    freq: dict[str, FreqListSource]  # insertion order = priority order

    @staticmethod
    def from_json(text: str) -> DictSources:
        raw = json.loads(text)

        def spec(name: str, d: Mapping[str, str], default_url: str | None = None) -> SourceSpec:
            url = d.get("url", default_url)
            if not url:
                raise ValueError(f"DICT_SOURCES_JSON: {name} needs a url")
            return SourceSpec(name, url, d["sha256"])

        core = Sources(
            spec("JMdict_e.gz", raw["jmdict"], JMDICT_URL),
            spec("kanjidic2.xml.gz", raw["kanjidic"], KANJIDIC2_URL),
            spec("kradzip.zip", raw["krad"], KRAD_URL),
        )
        freq: dict[str, FreqListSource] = {}
        for i, (slug, d) in enumerate(raw.get("freq_lists", {}).items(), start=1):
            freq[slug] = FreqListSource(
                FreqListConfig(slug, d.get("title", slug), i, d.get("url")),
                spec(f"freq-{slug}.zip", d),
            )
        return DictSources(
            core,
            spec("kanjium.txt", raw["kanjium"]) if "kanjium" in raw else None,
            spec("tatoeba.tsv", raw["tatoeba"]) if "tatoeba" in raw else None,
            freq,
        )

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> DictSources:
        e = os.environ if env is None else env
        text = e.get("DICT_SOURCES_JSON")
        if not text:
            raise RuntimeError("DICT_SOURCES_JSON is not set; cannot run dict-refresh")
        return DictSources.from_json(text)

    def fingerprint(self, slugs: list[str]) -> dict[str, str]:
        fp = {
            "jmdict": self.core.jmdict.sha256,
            "kanjidic2": self.core.kanjidic.sha256,
            "kradfile": self.core.krad.sha256,
        }
        if self.kanjium:
            fp["kanjium"] = self.kanjium.sha256
        if self.tatoeba:
            fp["tatoeba"] = self.tatoeba.sha256
        for s in slugs:
            fp[f"freq:{s}"] = self.freq[s].source.sha256
        return fp


def make_dict_refresh(
    database_url: str,
    sources: Callable[[], DictSources] = DictSources.from_env,
    s3: Callable[[], S3Client | None] = lambda: S3Client(S3Config.from_env()),
    cache_dir: Path = DEFAULT_CACHE_DIR,
) -> Callable[[JobContext], dict[str, Any]]:
    """Build the handler. ``sources`` and ``s3`` are resolved per job so config errors fail the job, not startup."""

    def handler(ctx: JobContext) -> dict[str, Any]:
        src = sources()
        wanted = ctx.payload.get("freq_lists")
        slugs = list(src.freq) if wanted is None else list(wanted)
        unknown = [s for s in slugs if s not in src.freq]
        if unknown:
            raise ValueError(f"frequency lists not configured: {unknown}")
        force = bool(ctx.payload.get("force", False))
        fp = src.fingerprint(slugs)

        with psycopg.connect(to_conninfo(database_url)) as conn:
            cur = conn.execute(
                "SELECT sources FROM ref.dict_release WHERE is_current"
            ).fetchone()
            if cur and not force:
                have = {k: v.get("sha256") for k, v in cur[0].items() if isinstance(v, dict)}
                if all(have.get(k) == v for k, v in fp.items()):
                    live = conn.execute(
                        "SELECT count(*) FROM ref.dict_entry WHERE retired_release_id IS NULL"
                    ).fetchone()
                    return {
                        "release_id": None,
                        "version": None,
                        "entries": live[0] if live else 0,
                        "retired": 0,
                    }
            row = conn.execute("SELECT coalesce(max(id), 0) FROM ref.dict_release").fetchone()
            new_id = (row[0] if row else 0) + 1

        now = datetime.now(UTC)
        release = ReleaseInfo(new_id, f"{now:%Y.%m.%d}.{new_id}", now.isoformat())

        ctx.progress(0.05, "loading JMdict and KANJIDIC2")
        loaded = refresh(database_url, release, src.core, cache_dir=cache_dir)
        ctx.check_cancelled()
        ctx.progress(0.4, "loading pitch, frequency and examples")
        refresh_extra(
            database_url,
            new_id,
            ExtraSources(src.kanjium, tuple(src.freq[s] for s in slugs), src.tatoeba),
            cache_dir=cache_dir,
        )
        ctx.check_cancelled()

        ctx.progress(0.6, "building device packages")
        client = s3()
        artifacts: list[dict[str, Any]] = []
        with (
            tempfile.TemporaryDirectory(prefix="dict-release-") as tmp,
            psycopg.connect(to_conninfo(database_url)) as conn,
        ):
            for scope in SCOPES:
                _, arts = build_release(conn, new_id, scope, Path(tmp) / scope, s3=client)
                artifacts.extend(a.manifest_entry() for a in arts)
                ctx.check_cancelled()
            conn.execute(
                "UPDATE ref.dict_release SET artifacts = %s::jsonb WHERE id = %s",
                (json.dumps(artifacts), new_id),
            )
            conn.commit()
        return {
            "release_id": new_id,
            "version": release.version,
            "entries": loaded.entries,
            "retired": loaded.retired,
        }

    return handler
