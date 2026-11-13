"""Database access of the deck importer: corrections, dictionary POS, media state, remap claim (read-mostly)."""

from __future__ import annotations

import uuid
from collections.abc import Collection, Mapping
from dataclasses import dataclass
from typing import Any, Protocol

import psycopg

from kotobaya_worker.importers.jpdb_deck.plan import Correction

JPDB_MAPPING_LOCK = 7033011  # MEDIA_PIPELINE.md 5.4


@dataclass(frozen=True)
class MediaState:
    live: bool
    source: str


class DeckRepo(Protocol):
    def claim_remap(self, job_id: uuid.UUID) -> None:
        """R1: clear this job's dedupe key under the jpdb-mapping lock (committed before returning)."""
        ...

    def media_state(self, media_id: str) -> MediaState | None: ...

    def corrections(self, vids: Collection[int]) -> Mapping[int, Correction]: ...

    def entry_pos(self, seqs: Collection[int]) -> Mapping[int, frozenset[str]]: ...


class PgDeckRepo:
    """``conn`` must be autocommit with ``dict_row`` (the same connection the publish kit uses)."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def claim_remap(self, job_id: uuid.UUID) -> None:
        with self._c.transaction():
            self._c.execute("select pg_advisory_xact_lock(%s)", (JPDB_MAPPING_LOCK,))
            self._c.execute("update app.job set dedupe_key = null where id = %s", (job_id,))

    def media_state(self, media_id: str) -> MediaState | None:
        row = self._c.execute(
            "select deleted_at is null as live, source from app.media where id = %s",
            (uuid.UUID(media_id),),
        ).fetchone()
        return None if row is None else MediaState(bool(row["live"]), str(row["source"]))

    def corrections(self, vids: Collection[int]) -> Mapping[int, Correction]:
        rows = self._c.execute(
            "select jpdb_vid, resolution, entry_seq from app.jpdb_vocab "
            "where jpdb_vid = any(%s) and resolution <> 'unresolved'",
            (sorted(vids),),
        ).fetchall()
        return {
            int(r["jpdb_vid"]): Correction(
                str(r["resolution"]), None if r["entry_seq"] is None else int(r["entry_seq"])
            )
            for r in rows
        }

    def entry_pos(self, seqs: Collection[int]) -> Mapping[int, frozenset[str]]:
        rows = self._c.execute(
            "select seq, pos from ref.dict_entry where seq = any(%s)", (sorted(seqs),)
        ).fetchall()
        return {int(r["seq"]): frozenset(r["pos"]) for r in rows}
