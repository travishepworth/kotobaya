"""Recompute ``ref.entry_freq`` / ``dict_entry.freq_rank`` for the current release, then rebuild
the list decks. No JMdict reload, no new release.

For a change in how frequency rows map to entries (WRK-FREQ-MATCH). Sources come from
``DICT_SOURCES_JSON`` like ``dict-refresh``: a cached file matching its pinned sha256 is
reused, otherwise it is downloaded again and checked against the pin. Every list is reloaded
in one transaction (all or nothing). Afterwards one ``deck-build`` is queued per live list
deck; a build whose items changed publishes a new ``content_version`` (devices get
``deck.content_published``), an unchanged deck ends ``changed: false``. A build that is already
running may have planned before the rebuild committed, so a fresh one is queued behind it (a
queued build has not planned yet and is kept).

``app.word_status`` is keyed by ``entry_seq``, not by deck item: entries leaving a deck lose
no learning data.

Device dictionary packages of the current release are not rebuilt (their ``entry_freq`` and
top-60k scope follow at the next release); the server's ``ref.*`` is updated at once.

Run inside the worker pod::

    python -m kotobaya_worker.jobs.freq_rebuild [--lists jpdb,cejc] [--no-decks]
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from pathlib import Path

import psycopg

from kotobaya_worker import logs
from kotobaya_worker.decks.enqueue import KIND as DECK_BUILD
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.extra.freq import parse_freq_zip
from kotobaya_worker.dict.extra.index import load_index
from kotobaya_worker.dict.extra.load import (
    FreqResult,
    begin,
    load_freq_list,
    lock,
    recompute_freq_rank,
)
from kotobaya_worker.dict.fetch import DEFAULT_CACHE_DIR, Fetcher, fetch_source, urllib_fetcher
from kotobaya_worker.importers.jpdb_history.apply import EVENTS_CHANNEL, uuid7
from kotobaya_worker.jobs.dict_refresh import DictSources

log = logging.getLogger("kotobaya.worker.freq_rebuild")


def enqueue_fresh_list_deck_builds(database_url: str) -> int:
    """One ``deck-build`` per live list deck that will plan after now.

    A queued build (dedupe ``deck-build:<id>``) is kept: it has not planned yet. When the
    dedupe key is held by a running build, which may have planned on the old rows, another
    build is queued without a dedupe key; deck builds are idempotent (``changed: false``).
    Returns the number of jobs inserted.
    """
    inserted = 0
    with psycopg.connect(to_conninfo(database_url), autocommit=True) as conn:
        decks = conn.execute(
            "SELECT id FROM app.deck WHERE type = 'list' AND deleted_at IS NULL ORDER BY id"
        ).fetchall()
        for (deck_id,) in decks:
            key = f"{DECK_BUILD}:{deck_id}"
            payload = json.dumps({"deck_id": str(deck_id)})
            job = uuid7()
            cur = conn.execute(
                "INSERT INTO app.job (id, kind, payload, dedupe_key) VALUES (%s, %s, %s::jsonb, %s) "
                "ON CONFLICT DO NOTHING",
                (job, DECK_BUILD, payload, key),
            )
            if not cur.rowcount:
                running = conn.execute(
                    "SELECT 1 FROM app.job WHERE dedupe_key = %s AND status = 'running'", (key,)
                ).fetchone()
                if running is None:
                    continue  # a queued build: it plans after this commit
                conn.execute(
                    "INSERT INTO app.job (id, kind, payload) VALUES (%s, %s, %s::jsonb)",
                    (job, DECK_BUILD, payload),
                )
            conn.execute(
                "SELECT pg_notify(%s, %s)",
                (EVENTS_CHANNEL, json.dumps({"type": "job.updated", "data": {"id": str(job)}})),
            )
            inserted += 1
    return inserted


@dataclass(frozen=True)
class FreqRebuildResult:
    release_id: int
    lists: tuple[FreqResult, ...]
    freq_rank_changed: int  # dict_entry rows whose freq_rank changed
    deck_builds_queued: int

    def to_json(self) -> dict[str, object]:
        return {
            "release_id": self.release_id,
            "lists": [asdict(r) for r in self.lists],
            "freq_rank_changed": self.freq_rank_changed,
            "deck_builds_queued": self.deck_builds_queued,
        }


def rebuild_freq(
    database_url: str,
    sources: DictSources,
    slugs: Sequence[str] | None = None,
    *,
    requeue_decks: bool = True,
    cache_dir: Path = DEFAULT_CACHE_DIR,
    fetch: Fetcher = urllib_fetcher,
    enqueue: Callable[[str], int] = enqueue_fresh_list_deck_builds,
) -> FreqRebuildResult:
    """Reload the frequency lists ``slugs`` (default: every configured list) for the current
    release. Fetches (and verifies) before touching the database."""
    wanted = list(sources.freq) if slugs is None else list(slugs)
    unknown = [s for s in wanted if s not in sources.freq]
    if unknown:
        raise ValueError(f"frequency lists not configured: {unknown}")
    if not wanted:
        raise ValueError("no frequency lists configured in DICT_SOURCES_JSON")
    fetched = [
        (sources.freq[s], fetch_source(sources.freq[s].source, cache_dir, fetch)) for s in wanted
    ]

    results: list[FreqResult] = []
    with psycopg.connect(to_conninfo(database_url)) as conn, conn.transaction():
        lock(conn)  # before is_current: a concurrent dict-refresh finishes its extras first
        row = conn.execute("SELECT id FROM ref.dict_release WHERE is_current").fetchone()
        if row is None:
            raise ValueError("no current dictionary release; run dict-refresh first")
        release_id = int(row[0])
        begin(conn, release_id)
        idx = load_index(conn)
        for src, got in fetched:
            results.append(load_freq_list(conn, idx, src.config, parse_freq_zip(got.path)))
        changed = recompute_freq_rank(conn)
    queued = enqueue(database_url) if requeue_decks else 0
    log.info("freq rebuild: release %d, %d freq_rank change(s), %d deck build(s) queued",
             release_id, changed, queued)  # fmt: skip
    return FreqRebuildResult(release_id, tuple(results), changed, queued)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m kotobaya_worker.jobs.freq_rebuild")
    parser.add_argument("--lists", help="comma-separated list slugs (default: all configured)")
    parser.add_argument("--no-decks", action="store_true", help="do not queue deck-build jobs")
    args = parser.parse_args(argv)
    logs.configure()
    slugs = [s.strip() for s in args.lists.split(",") if s.strip()] if args.lists else None
    result = rebuild_freq(
        os.environ["DATABASE_URL"], DictSources.from_env(), slugs, requeue_decks=not args.no_decks
    )
    print(json.dumps(result.to_json(), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
