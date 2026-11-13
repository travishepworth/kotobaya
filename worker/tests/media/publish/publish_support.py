"""Shared helpers and in-memory fakes for the publish-kit tests."""

from __future__ import annotations

import importlib.util
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path
from types import ModuleType
from typing import Any

from kotobaya_worker.importers.jpdb_history.apply import ChildRow
from kotobaya_worker.media.publish.ids import (
    ExistingContent,
    ExistingSegment,
    ExistingSentence,
    NewSegment,
    assign_ids,
)
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    DeckArchive,
    OccurrenceRec,
    SegmentRec,
    SentenceRec,
    media_deck_items,
)

ROOT = Path(__file__).resolve().parents[4]
MEDIA_ID = "0193a1b2-0007-7000-8000-000000000001"


def load_validate() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "c11_validate", ROOT / "contracts" / "db" / "media" / "validate.py"
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build_archives(
    texts: Sequence[Sequence[str]],
    existing: ExistingContent,
    version: int,
    media_id: str = MEDIA_ID,
) -> tuple[ContentArchive, DeckArchive]:
    """Segments of sentences (one token per sentence, seq = 1000 + len(text)) with stable ids."""
    ids = assign_ids(media_id, existing, [NewSegment(i, t) for i, t in enumerate(texts)])
    segs: list[SegmentRec] = []
    sents: list[SentenceRec] = []
    occs: list[OccurrenceRec] = []
    for so, seg_texts in enumerate(texts):
        sid = ids.segment_ids[so]
        by_seq: dict[int, list[str]] = {}
        for i, text in enumerate(seg_texts):
            sent_id = ids.sentence_ids[(so, i)]
            seq = 1000 + len(text)
            tok = {"s": 0, "e": len(text), "seq": seq, "class": "content", "surface": text}
            sents.append(SentenceRec(sent_id, sid, i, text, [tok]))
            by_seq.setdefault(seq, []).append(sent_id)
        segs.append(SegmentRec(sid, so, "episode", f"ep{so}", len(seg_texts), len(seg_texts), 0))
        for seq, sent_ids in by_seq.items():
            occs.append(OccurrenceRec(sid, seq, len(sent_ids), "content", tuple(sent_ids[:20])))
    content = ContentArchive(media_id, version, segs, sents, occs)
    deck = DeckArchive(media_id, "media", version, media_deck_items(occs))
    return content, deck


def existing_from(content: ContentArchive) -> ExistingContent:
    ord_of = {s.id: s.ordinal for s in content.segments}
    return ExistingContent(
        content.content_version,
        [ExistingSegment(s.id, s.ordinal) for s in content.segments],
        [
            ExistingSentence(s.id, ord_of[s.segment_id], s.ordinal, s.text)
            for s in content.sentences
        ],
    )


class Ctx:
    def __init__(self, job_id: uuid.UUID | None = None) -> None:
        self.payload: dict[str, Any] = {}
        self.job_id = job_id or uuid.uuid4()
        self.cancelled = False

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        if self.cancelled:
            raise RuntimeError("cancelled")


class FakeObjects:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}
        self.puts = 0

    def put(self, blob_hash: str, data: bytes) -> None:
        self.puts += 1
        self.objects[blob_hash] = data


class FakeBlobTable:
    def __init__(self, objects: FakeObjects) -> None:
        self.rows: dict[str, int] = {}
        self._objects = objects

    def insert_snapshot(self, blob_hash: str, size_bytes: int) -> None:
        assert blob_hash in self._objects.objects  # row only after the object is durable
        self.rows.setdefault(blob_hash, size_bytes)


class FakeStore:
    """In-memory ``PublishStore`` with the dedupe-key rule (one queued/running child per key)."""

    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, dict[str, Any]] = {}

    def succeeded_result(self, parent: uuid.UUID) -> Mapping[str, Any] | None:
        for r in self.rows.values():
            if r["payload"]["parent_job_id"] == str(parent) and r["status"] == "succeeded":
                return r["result"]  # type: ignore[no-any-return]
        return None

    def insert(self, child: uuid.UUID, parent: uuid.UUID, payload: dict[str, Any]) -> bool:
        if self.active_child(parent) is not None:
            return False
        self.rows[child] = {"payload": payload, "status": "queued", "result": None, "error": None}
        return True

    def active_child(self, parent: uuid.UUID) -> uuid.UUID | None:
        for k, r in self.rows.items():
            if r["payload"]["parent_job_id"] == str(parent) and r["status"] in (
                "queued",
                "running",
            ):
                return k
        return None

    def get(self, child: uuid.UUID) -> ChildRow | None:
        r = self.rows.get(child)
        return None if r is None else ChildRow(r["status"], r["result"], r["error"])


_spec = importlib.util.spec_from_file_location(
    "media_publish_pg_fixtures", ROOT / "worker" / "tests" / "runner" / "conftest.py"
)
assert _spec is not None and _spec.loader is not None
_pg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pg)

# Throwaway-Postgres fixtures of tests/runner (docker; skipped when docker is absent).
pg_server = _pg.pg_server
db_url = _pg.db_url
