"""T404b: real parser (Sudachi full + fixture JMdict) through media-import on fixtures/parse/sentences.json,
and the Postgres base reader. Full-dictionary gated as in T403a."""

from __future__ import annotations

import json
from collections import Counter
from functools import lru_cache
from itertools import chain
from typing import Any

import psycopg
import pytest
from pipeline_support import (
    ROOT,
    Ctx,
    FakeBlobTable,
    FakeObjects,
    FakeServer,
    FakeStore,
    db_url,  # noqa: F401
    pg_server,  # noqa: F401
)

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.jmdict import parse_jmdict
from kotobaya_worker.media.pipeline.base import BaseSegment, BaseSentence, MediaBase, PgMediaReader
from kotobaya_worker.media.pipeline.import_job import run_import
from kotobaya_worker.media.pipeline.parser import StaticSegmentParser
from kotobaya_worker.parse.match import InMemoryMatchLookup
from kotobaya_worker.parse.sudachi import get_analyzer, is_dictionary_installed

PARSE_FIXTURE = ROOT / "fixtures" / "parse" / "sentences.json"
DICT_FIXTURES = (
    ROOT / "fixtures" / "dict" / "JMdict_subset.xml",
    ROOT / "fixtures" / "dict" / "JMdict_parse_extra.xml",
)
MEDIA_ID = "0193a1b2-0007-7000-8000-00000000b405"
# Share of the fixture's (seq, count) tokens found as occurrences: measured 0.990 with sudachidict_full.
RECALL_THRESHOLD = 0.98


@lru_cache(maxsize=1)
def lookup() -> InMemoryMatchLookup:
    return InMemoryMatchLookup.from_entries(
        tuple(chain.from_iterable(parse_jmdict(p) for p in DICT_FIXTURES))
    )


@pytest.mark.skipif(not is_dictionary_installed("full"), reason="sudachidict_full not installed")
def test_real_parser_occurrences_match_fixture_seqs() -> None:
    raw = json.loads(PARSE_FIXTURE.read_text(encoding="utf-8"))["sentences"]
    expected: Counter[int] = Counter(
        t["seq"] for s in raw for t in s["tokens"] if t["seq"] is not None
    )
    store, objects = FakeStore(), FakeObjects()
    server = FakeServer(store, objects)
    ctx = Ctx()
    text = "\n\n".join(s["text"] for s in raw)
    ctx.payload = {"media_id": MEDIA_ID, "title": "fixture", "kind": "other", "source": "paste",
                   "text": text}  # fmt: skip
    result = run_import(
        ctx,
        fetch_blob=lambda h: b"",
        parser=StaticSegmentParser(lookup(), get_analyzer("full")),
        base_reader=server,
        store=store,
        objects=objects,
        blobs=FakeBlobTable(objects),
        sleep=server.sleep,
    )
    assert server.content is not None
    got: Counter[int] = Counter()
    for o in server.content.occurrences:
        got[o.entry_seq] += o.count
    found = sum(min(n, got[seq]) for seq, n in expected.items())
    recall = found / sum(expected.values())
    missing = sorted((seq, n - got[seq]) for seq, n in expected.items() if got[seq] < n)
    assert recall >= RECALL_THRESHOLD, (recall, missing[:20])
    assert result["tokens"] == sum(s.token_count for s in server.content.segments)
    # every seq the parser produced is an occurrence and nothing else is
    produced = Counter(
        t["seq"] for s in server.content.sentences for t in s.tokens if t["seq"] is not None
    )
    assert produced == got


def test_pg_media_reader_reads_whole_rows(db_url: str) -> None:  # noqa: F811
    tokens: list[Any] = [{"s": 0, "e": 1, "surface": "猫", "seq": 1010, "class": "content"}]
    with psycopg.connect(to_conninfo(db_url), autocommit=True) as c:
        reader = PgMediaReader(c)
        assert reader.read(MEDIA_ID) == MediaBase(exists=False)
        c.execute(
            "insert into app.media (id, title, kind, source, content_version, created_at, "
            "updated_at) values (%s, 't', 'anime', 'file', 1, now(), now())",
            (MEDIA_ID,),
        )
        seg0, seg1 = "0193a1b2-0007-7000-8000-0000000000a0", "0193a1b2-0007-7000-8000-0000000000a1"
        for sid, o in ((seg1, 1), (seg0, 0)):
            c.execute(
                "insert into app.media_segment (id, media_id, ordinal, kind, title) "
                "values (%s, %s, %s, 'episode', %s)",
                (sid, MEDIA_ID, o, f"ep{o}"),
            )
        rows = [
            ("0193a1b2-0007-7000-8000-0000000000b2", seg1, 0, "犬。", None, None, None),
            ("0193a1b2-0007-7000-8000-0000000000b1", seg0, 1, "猫だ。", 10, 20, '{"page": 3}'),
            ("0193a1b2-0007-7000-8000-0000000000b0", seg0, 0, "猫。", None, None, None),
        ]
        for sid, seg, o, text, a, b, loc in rows:
            c.execute(
                "insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, "
                "start_ms, end_ms, audio_blob_hash, location) "
                "values (%s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s::jsonb)",
                (sid, MEDIA_ID, seg, o, text, json.dumps(tokens), a, b,
                 "c" * 64 if a else None, loc),
            )  # fmt: skip
        base = reader.read(MEDIA_ID)
        assert base == MediaBase(
            exists=True,
            content_version=1,
            source="file",
            live=True,
            segments=(
                BaseSegment(seg0, 0, "episode", "ep0", (
                    BaseSentence("0193a1b2-0007-7000-8000-0000000000b0", 0, "猫。", tokens),
                    BaseSentence("0193a1b2-0007-7000-8000-0000000000b1", 1, "猫だ。", tokens,
                                 "media", 10, 20, "c" * 64, None, {"page": 3}),
                )),
                BaseSegment(seg1, 1, "episode", "ep1", (
                    BaseSentence("0193a1b2-0007-7000-8000-0000000000b2", 0, "犬。", tokens),
                )),
            ),
        )  # fmt: skip
        assert [(s.segment_ordinal, s.ordinal) for s in base.existing().sentences] == [
            (0, 0), (0, 1), (1, 0),
        ]  # fmt: skip
        c.execute("update app.media set deleted_at = now() where id = %s", (MEDIA_ID,))
        assert reader.read(MEDIA_ID).live is False
