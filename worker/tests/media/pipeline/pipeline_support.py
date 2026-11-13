"""Fakes for the T404b pipeline tests: a lexicon parser (with real override application) and a fake server.

``FakeParser`` tokenises by greedy longest match over ``LEXICON``; punctuation is a gap; any other run of
characters up to the next lexicon match or gap is one unmapped token. Overrides go through the real
``apply_overrides`` (PARSER.md 4), so scope and alignment rules are the production ones.

``FakeServer`` plays the server's ``media-publish`` runner: validates each queued child with the contract's
``validate.py``, rejects a stale base, "installs" the archives and serves them back as the next base.
"""

from __future__ import annotations

import importlib.util
import uuid
from collections.abc import Sequence
from pathlib import Path
from types import ModuleType
from typing import Any

from kotobaya_worker.media.pipeline.base import BaseSegment, BaseSentence, MediaBase
from kotobaya_worker.media.publish.snapshot import (
    ContentArchive,
    DeckArchive,
    read_content,
    read_deck_items,
)
from kotobaya_worker.parse.match import Token, TokenClass
from kotobaya_worker.parse.overrides import OverrideRow, apply_overrides

ROOT = Path(__file__).resolve().parents[4]
MEDIA = ROOT / "fixtures" / "media"


def _load(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


publish_support = _load(
    "t404b_publish_support", ROOT / "worker" / "tests" / "media" / "publish" / "publish_support.py"
)
Ctx = publish_support.Ctx
FakeObjects = publish_support.FakeObjects
FakeBlobTable = publish_support.FakeBlobTable
FakeStore = publish_support.FakeStore
load_validate = publish_support.load_validate
pg_server = publish_support.pg_server
db_url = publish_support.db_url

C: TokenClass = "content"
P: TokenClass = "particle"
A: TokenClass = "auxiliary"
O: TokenClass = "other"

# surface -> (seq, class). Covers fixtures/media/sample.srt and sample.epub.
LEXICON: dict[str, tuple[int, TokenClass]] = {
    "おはよう": (1001, C), "今日": (1002, C), "いい": (1003, C), "天気": (1004, C),
    "うん": (1005, C), "散歩": (1006, C), "行こ": (1007, C), "前": (1008, C), "食べ": (1009, C),
    "猫": (1010, C), "窓": (1011, C), "外": (1012, C), "見": (1013, C), "駅": (1014, C),
    "歩い": (1015, C), "十分": (1016, C), "かかる": (1017, C), "早く": (1018, C),
    "あ": (1019, O), "じゃあ": (1019, C), "その": (1020, C), "朝": (1021, C), "私": (1022, C),
    "空": (1023, C), "よく": (1024, C), "晴れ": (1025, C), "風": (1027, C),
    "心地よかっ": (1028, C), "友達": (1029, C), "言っ": (1030, C), "夜": (1031, C),
    "なっ": (1032, C), "雨": (1033, C), "降り始め": (1034, C), "家": (1035, C),
    "静か": (1036, C), "本": (1037, C), "読ん": (1038, C),
    "も": (2001, P), "ね": (2002, P), "に": (2003, P), "か": (2004, P), "を": (2005, P),
    "が": (2006, P), "の": (2007, P), "まで": (2008, P), "て": (2009, P), "よ": (2010, P),
    "は": (2011, P), "と": (2012, P), "で": (2013, P),
    "だ": (3001, A), "う": (3002, A), "た": (3003, A),
}  # fmt: skip
GAPS = frozenset("。、！？（）「」…・ 　\t")
_LONGEST = max(len(k) for k in LEXICON)


def len16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def _tok(text: str, i: int, j: int, seq: int | None, cls: TokenClass) -> Token:
    s = len16(text[:i])
    surface = text[i:j]
    return Token(s, s + len16(surface), surface, surface, "", (), None, seq, cls, False)


def lexicon_tokens(text: str) -> list[Token]:
    out: list[Token] = []
    i, run = 0, -1
    while i < len(text):
        hit = next(
            (n for n in range(min(_LONGEST, len(text) - i), 0, -1) if text[i : i + n] in LEXICON),
            0,
        )
        if hit or text[i] in GAPS:
            if run >= 0:
                out.append(_tok(text, run, i, None, "other"))
                run = -1
            if hit:
                seq, cls = LEXICON[text[i : i + hit]]
                out.append(_tok(text, i, i + hit, seq, cls))
                i += hit
            else:
                i += 1
            continue
        if run < 0:
            run = i
        i += 1
    if run >= 0:
        out.append(_tok(text, run, len(text), None, "other"))
    return out


class FakeParser:
    def __init__(self, overrides: Sequence[OverrideRow] = ()) -> None:
        self.overrides = list(overrides)
        self.calls: list[tuple[str, list[str], list[str]]] = []
        self.on_call: Any = None

    def __call__(
        self, media_id: str, sentence_ids: Sequence[str], texts: Sequence[str]
    ) -> list[list[dict[str, Any]]]:
        self.calls.append((media_id, list(sentence_ids), list(texts)))
        out = [
            [
                t.to_json()
                for t in apply_overrides(
                    text,
                    lexicon_tokens(text),
                    self.overrides,
                    media_id=media_id,
                    sentence_id=sid,
                    entry_pos=lambda _seq: None,
                    analyse=lambda _s: (),
                )
            ]
            for sid, text in zip(sentence_ids, texts, strict=True)
        ]
        if self.on_call is not None:
            self.on_call()
        return out


class FakeServer:
    """The server's publish runner and the media's installed rows, in memory."""

    def __init__(self, store: Any, objects: Any) -> None:
        self.store = store
        self.objects = objects
        self.validate = load_validate()
        self.content: ContentArchive | None = None
        self.deck: DeckArchive | None = None
        self.source = "file"
        self.live = True

    @property
    def version(self) -> int:
        return 0 if self.content is None else self.content.content_version

    def sleep(self, _: float) -> None:
        for row in self.store.rows.values():
            if row["status"] != "queued":
                continue
            p = row["payload"]
            if p["base_content_version"] != self.version:
                row.update(status="failed", error="stale_base: moved on")
                continue
            cb = self.objects.objects[p["content"]["blob_hash"]]
            db = self.objects.objects[p["deck_items"]["blob_hash"]]
            result = self.validate.validate_publish(p, cb, db)
            self.content, self.deck = read_content(cb), read_deck_items(db)
            if p["create"] is not None:
                self.source = p["create"]["source"]
            row.update(status="succeeded", result=result)

    def read(self, media_id: str) -> MediaBase:
        if self.content is None:
            return MediaBase(exists=False)
        assert self.content.media_id == media_id
        segs = sorted(self.content.segments, key=lambda s: s.ordinal)
        return MediaBase(
            exists=True,
            content_version=self.content.content_version,
            source=self.source,
            live=self.live,
            segments=tuple(
                BaseSegment(
                    s.id,
                    s.ordinal,
                    s.kind,
                    s.title,
                    tuple(
                        BaseSentence(
                            t.id,
                            t.ordinal,
                            t.text,
                            list(t.tokens),
                            t.origin,
                            t.start_ms,
                            t.end_ms,
                            t.audio_blob_hash,
                            t.image_blob_hash,
                            t.location,
                        )
                        for t in sorted(self.content.sentences, key=lambda t: t.ordinal)
                        if t.segment_id == s.id
                    ),
                )
                for s in segs
            ),
        )


def new_media_id() -> str:
    return str(uuid.UUID(int=uuid.uuid4().int))
