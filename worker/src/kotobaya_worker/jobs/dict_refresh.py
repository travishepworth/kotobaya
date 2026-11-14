"""``dict-refresh`` handler: refresh -> refresh_extra -> build_release (+ S3) -> ref.dict_release.artifacts.

Sources come from ``DICT_SOURCES_JSON``. The EDRDG files (jmdict, kanjidic, krad) change daily, so
their ``sha256`` is an optional, advisory pin (ADR-020): a cached file matching it is reused, but
when upstream has republished (two downloads agree on new content) the new file is used and its
digest recorded, which builds a new release. Other sources keep a mandatory, exact pin::

    {"jmdict": {"url": "optional override", "sha256": "optional pin"},
     "kanjidic": {}, "krad": {},
     "kanjium": {"url": "..", "sha256": ".."},          # optional
     "tatoeba": {"url": "..", "sha256": ".."},          # optional
     "freq_lists": {"jpdb": {"url": "..", "sha256": "..", "title": "jpdb"}},   # optional, priority = order
     "jlpt": {"n5": {"url": "..", "sha256": ".."}, "n4": {}, "n3": {}, "n2": {}, "n1": {}}}  # all five, pinned

``jlpt`` (contracts/db/VOCAB_LISTS.md 2): absent = the built-in pinned default, ``null`` = no JLPT lists,
an object = all five levels each with ``url`` and ``sha256``.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import psycopg

from kotobaya_worker.decks.enqueue import enqueue_list_deck_builds
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.extra.load import FreqListConfig
from kotobaya_worker.dict.extra.pipeline import ExtraSources, FreqListSource, refresh_extra
from kotobaya_worker.dict.fetch import (
    DEFAULT_CACHE_DIR,
    JMDICT_URL,
    KANJIDIC2_URL,
    KRAD_URL,
    Fetcher,
    SourceSpec,
    fetch_source,
    urllib_fetcher,
)
from kotobaya_worker.dict.models import ReleaseInfo
from kotobaya_worker.dict.package.release import build_release
from kotobaya_worker.dict.package.s3 import S3Client, S3Config
from kotobaya_worker.dict.package.source import SCOPES
from kotobaya_worker.dict.pipeline import Sources, refresh
from kotobaya_worker.dict.vocab import (
    JLPT_LEVELS,
    jlpt_default_specs,
    jlpt_fingerprint,
    jlpt_provenance,
    jlpt_sources,
)
from kotobaya_worker.runner.core import JobContext

log = logging.getLogger("kotobaya.worker.dict_refresh")


@dataclass(frozen=True)
class DictSources:
    core: Sources
    kanjium: SourceSpec | None
    tatoeba: SourceSpec | None
    freq: dict[str, FreqListSource]  # insertion order = priority order
    jlpt: dict[str, SourceSpec] | None = None  # level -> spec; None = no JLPT lists

    @staticmethod
    def from_json(text: str) -> DictSources:
        raw = json.loads(text)

        def spec(
            name: str,
            d: Mapping[str, str],
            default_url: str | None = None,
            *,
            pin_required: bool = True,
        ) -> SourceSpec:
            url = d.get("url", default_url)
            if not url:
                raise ValueError(f"DICT_SOURCES_JSON: {name} needs a url")
            pin = d.get("sha256") or None
            if pin is None and pin_required:
                raise ValueError(f"DICT_SOURCES_JSON: {name} needs a sha256")
            # Optional pins are on daily-republished files: advisory, never a permanent failure.
            return SourceSpec(name, url, pin, strict=pin_required)

        core = Sources(
            spec("JMdict_e.gz", raw.get("jmdict", {}), JMDICT_URL, pin_required=False),
            spec("kanjidic2.xml.gz", raw.get("kanjidic", {}), KANJIDIC2_URL, pin_required=False),
            spec("kradzip.zip", raw.get("krad", {}), KRAD_URL, pin_required=False),
        )
        freq: dict[str, FreqListSource] = {}
        for i, (slug, d) in enumerate(raw.get("freq_lists", {}).items(), start=1):
            freq[slug] = FreqListSource(
                FreqListConfig(slug, d.get("title", slug), i, d.get("url")),
                spec(f"freq-{slug}.zip", d),
            )
        jlpt: dict[str, SourceSpec] | None
        if "jlpt" not in raw:
            jlpt = jlpt_default_specs()
        elif raw["jlpt"] is None:
            jlpt = None
        else:
            cfg = raw["jlpt"]
            if not isinstance(cfg, dict) or set(cfg) != set(JLPT_LEVELS):
                raise ValueError(
                    f"DICT_SOURCES_JSON: jlpt needs exactly the levels {list(JLPT_LEVELS)}"
                )
            jlpt = {}
            for lvl in JLPT_LEVELS:
                d = cfg[lvl]
                if not isinstance(d, dict) or not d.get("url"):
                    raise ValueError(f"DICT_SOURCES_JSON: jlpt {lvl} needs a url")
                jlpt[lvl] = spec(f"jlpt-{lvl}.csv", d)
        return DictSources(
            core,
            spec("kanjium.txt", raw["kanjium"]) if "kanjium" in raw else None,
            spec("tatoeba.tsv", raw["tatoeba"]) if "tatoeba" in raw else None,
            freq,
            jlpt,
        )

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> DictSources:
        e = os.environ if env is None else env
        text = e.get("DICT_SOURCES_JSON")
        if not text:
            raise RuntimeError("DICT_SOURCES_JSON is not set; cannot run dict-refresh")
        return DictSources.from_json(text)

    def resolve(self, slugs: list[str], cache_dir: Path, fetch: Fetcher) -> DictSources:
        """Fetch every source (pins enforced) and return specs completed with the fetched digests."""

        def one(spec: SourceSpec) -> SourceSpec:
            return fetch_source(spec, cache_dir, fetch).spec

        return DictSources(
            Sources(one(self.core.jmdict), one(self.core.kanjidic), one(self.core.krad)),
            one(self.kanjium) if self.kanjium else None,
            one(self.tatoeba) if self.tatoeba else None,
            {s: replace(self.freq[s], source=one(self.freq[s].source)) for s in slugs},
            None if self.jlpt is None else {lvl: one(sp) for lvl, sp in self.jlpt.items()},
        )

    def fingerprint(self, slugs: list[str]) -> dict[str, str]:
        """Source key -> sha256. Only meaningful on a :meth:`resolve`d instance."""
        specs: dict[str, SourceSpec] = {
            "jmdict": self.core.jmdict,
            "kanjidic2": self.core.kanjidic,
            "kradfile": self.core.krad,
        }
        if self.kanjium:
            specs["kanjium"] = self.kanjium
        if self.tatoeba:
            specs["tatoeba"] = self.tatoeba
        for s in slugs:
            specs[f"freq:{s}"] = self.freq[s].source
        out = {k: v.sha256 or "" for k, v in specs.items()}
        if self.jlpt is not None:
            out["jlpt"] = jlpt_fingerprint(self.jlpt)
        return out


CORE_KEYS = ("jmdict", "kanjidic2", "kradfile")


def _published(artifacts: Any) -> bool:
    """True when every device scope has a ``full`` artifact recorded."""
    if not isinstance(artifacts, list):
        return False
    have = {a.get("scope") for a in artifacts if isinstance(a, dict) and a.get("kind") == "full"}
    return all(sc in have for sc in SCOPES)


def _requeue_decks(database_url: str) -> None:
    """After a release is loaded: rebuild the live list decks (VOCAB_LISTS.md 3.4; deduped)."""
    n = enqueue_list_deck_builds(database_url)
    log.info("queued %d list deck rebuild(s)", n)


def make_dict_refresh(
    database_url: str,
    sources: Callable[[], DictSources] = DictSources.from_env,
    s3: Callable[[], S3Client | None] = lambda: S3Client(S3Config.from_env()),
    cache_dir: Path = DEFAULT_CACHE_DIR,
    fetch: Fetcher = urllib_fetcher,
) -> Callable[[JobContext], dict[str, Any]]:
    """Build the handler. ``sources`` and ``s3`` are resolved per job so config errors fail the job, not startup."""

    def handler(ctx: JobContext) -> dict[str, Any]:
        raw_src = sources()
        wanted = ctx.payload.get("freq_lists")
        slugs = list(raw_src.freq) if wanted is None else list(wanted)
        unknown = [s for s in slugs if s not in raw_src.freq]
        if unknown:
            raise ValueError(f"frequency lists not configured: {unknown}")
        force = bool(ctx.payload.get("force", False))
        ctx.progress(0.02, "fetching sources")
        src = raw_src.resolve(slugs, cache_dir, fetch)
        fp = src.fingerprint(slugs)
        extra = ExtraSources(src.kanjium, tuple(src.freq[s] for s in slugs), src.tatoeba)

        # Resume: the current release already has this core data. Whatever step was cut short
        # (extras, device package) is redone for that same release; nothing is reloaded.
        resume_id: int | None = None
        resume_version = ""
        need_extra = False
        with psycopg.connect(to_conninfo(database_url)) as conn:
            cur = conn.execute(
                "SELECT id, version, sources, artifacts FROM ref.dict_release WHERE is_current"
            ).fetchone()
            if cur and not force:
                have = {k: v.get("sha256") for k, v in cur[2].items() if isinstance(v, dict)}
                # The JLPT lists are matched while a release loads, so their configuration is core
                # data: adding, changing or dropping them builds a new release (VOCAB_LISTS.md 2.2).
                core_keys = CORE_KEYS + (("jlpt",) if "jlpt" in fp else ())
                core_ok = all(have.get(k) == fp[k] for k in core_keys) and (
                    "jlpt" in fp or "jlpt" not in have
                )
                clash = any(have.get(k) not in (None, v) for k, v in fp.items())
                if core_ok and not clash:
                    resume_id, resume_version = cur[0], cur[1]
                    need_extra = any(k not in have for k in fp)
                    if not need_extra and _published(cur[3]):
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

        if resume_id is not None:
            release_id, version = resume_id, resume_version
            log.info("resuming release %s (extras=%s)", release_id, need_extra)
            if need_extra:
                ctx.progress(0.4, "loading pitch, frequency and examples")
                refresh_extra(database_url, release_id, extra, cache_dir=cache_dir, fetch=fetch)
            # Always: an earlier attempt may have loaded the release but died before queueing the
            # list-deck rebuilds. Builds are deduped, so a repeat is a no-op.
            _requeue_decks(database_url)
            ctx.check_cancelled()
            with psycopg.connect(to_conninfo(database_url)) as conn:
                live = conn.execute(
                    "SELECT count(*) FROM ref.dict_entry WHERE retired_release_id IS NULL"
                ).fetchone()
            entries, retired = (live[0] if live else 0), 0
            vocab_reports: list[dict[str, Any]] | None = None
        else:
            now = datetime.now(UTC)
            release_id = new_id
            version = f"{now:%Y.%m.%d}.{new_id}"
            sources_prov: dict[str, dict[str, str]] = {}
            vocab = None
            if src.jlpt is not None:
                files = {
                    lvl: fetch_source(sp, cache_dir, fetch).path for lvl, sp in src.jlpt.items()
                }  # pinned and cached by resolve(): no second download
                vocab = jlpt_sources(
                    files
                )  # invalid_source fails the job before the database is touched
                sources_prov["jlpt"] = jlpt_provenance(src.jlpt)
            else:
                vocab = []  # no JLPT configured: the new release has no vocabulary lists
            release = ReleaseInfo(new_id, version, now.isoformat(), sources_prov)
            ctx.progress(0.05, "loading JMdict and KANJIDIC2")
            loaded = refresh(
                database_url, release, src.core, cache_dir=cache_dir, fetch=fetch, vocab_lists=vocab
            )
            vocab_reports = [r.to_json() for r in loaded.vocab_lists]
            ctx.check_cancelled()
            ctx.progress(0.4, "loading pitch, frequency and examples")
            refresh_extra(database_url, new_id, extra, cache_dir=cache_dir, fetch=fetch)
            ctx.check_cancelled()
            entries, retired = loaded.entries, loaded.retired
            _requeue_decks(database_url)

        ctx.progress(0.6, "building device packages")
        client = s3()
        artifacts: list[dict[str, Any]] = []
        with (
            tempfile.TemporaryDirectory(prefix="dict-release-") as tmp,
            psycopg.connect(to_conninfo(database_url)) as conn,
        ):
            for scope in SCOPES:
                _, arts = build_release(conn, release_id, scope, Path(tmp) / scope, s3=client)
                artifacts.extend(a.manifest_entry() for a in arts)
                ctx.check_cancelled()
            conn.execute(
                "UPDATE ref.dict_release SET artifacts = %s::jsonb WHERE id = %s",
                (json.dumps(artifacts), release_id),
            )
            conn.commit()
        result: dict[str, Any] = {
            "release_id": release_id,
            "version": version,
            "entries": entries,
            "retired": retired,
        }
        if vocab_reports is not None:
            result["vocab_lists"] = vocab_reports
        return result

    return handler
