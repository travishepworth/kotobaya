"""Parse overrides (SPEC Parsing step 4; contracts/api/PARSER.md 4).

:func:`apply_overrides` is the exact algorithm of PARSER.md 4.1-4.4 over one sentence:
scope rank (sentence 0, media 1, global 2), aligned candidates only, selection by
``(rank, -len16(match_text), -hlc, id, i)`` taking non-overlapping spans greedily, then the
replacement tokens built from the sanitised override tokens. :func:`load_overrides` reads the
live rows for a media (or the global rows only) from ``app.parse_override``.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Any, TypeGuard

import psycopg

from kotobaya_worker.parse.match import CLASSES, MAX_SEQ, Token, TokenClass, derive_class
from kotobaya_worker.parse.sudachi import Morph

# Sudachi top-level POS that are not word morphemes (PARSER.md 1.2.4).
NON_WORD_POS = frozenset({"補助記号", "空白"})

# Unicode White_Space (PropList.txt); str.isspace() differs (it includes U+001C..U+001F).
WHITE_SPACE = frozenset("\u0009\u000a\u000b\u000c\u000d \u0085                 　")

# Analyse a surface alone: normalise + Sudachi mode C, no fallback, no override (4.4 b).
Analyse = Callable[[str], Sequence[Morph]]
EntryPos = Callable[[int], frozenset[str] | None]


@dataclass(frozen=True)
class OverrideRow:
    """One ``app.parse_override`` row. Ids are lowercase UUID strings; ``tokens`` is raw JSON."""

    id: str
    media_id: str | None
    sentence_id: str | None
    match_text: str
    tokens: tuple[Any, ...]
    hlc: str
    deleted: bool = False


def len16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def is_white_space(text: str) -> bool:
    return bool(text) and all(c in WHITE_SPACE for c in text)


def to_hiragana(text: str) -> str:
    """Katakana U+30A1..U+30F6 shifted by -0x60; everything else kept (PARSER.md 1.1)."""
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in text)


def trim_pos(pos: Sequence[str]) -> tuple[str, ...]:
    return tuple(p for p in pos[:4] if p not in ("*", ""))


@dataclass(frozen=True)
class _OverrideToken:
    surface: str
    lemma: str | None
    reading: str | None
    cls: TokenClass | None
    seq: int | None


def _ok_str(v: object) -> TypeGuard[str]:
    return isinstance(v, str) and v != "" and "\u0000" not in v


def sanitise_token(raw: object) -> _OverrideToken | None:
    """PARSER.md 4.4 "Sanitising": invalid members are absent. None if ``surface`` is unusable."""
    if not isinstance(raw, dict):
        return None
    surface = raw.get("surface")
    if not isinstance(surface, str) or surface == "":
        return None
    lemma = raw.get("lemma")
    reading = raw.get("reading")
    cls = raw.get("class")
    seq = raw.get("seq")
    return _OverrideToken(
        surface=surface,
        lemma=lemma if _ok_str(lemma) else None,
        reading=to_hiragana(reading) if _ok_str(reading) else None,
        cls=next((c for c in CLASSES if c == cls), None) if isinstance(cls, str) else None,
        seq=seq
        if isinstance(seq, int) and not isinstance(seq, bool) and 1 <= seq <= MAX_SEQ
        else None,
    )


def _rank(o: OverrideRow, media_id: str | None, sentence_id: str | None) -> int | None:
    """PARSER.md 4.1: 0 sentence, 1 media, 2 global, None = does not apply."""
    if o.deleted:
        return None
    if o.media_id is None:
        return 2
    if media_id is None or o.media_id != media_id:
        return None
    if o.sentence_id is None:
        return 1
    return 0 if sentence_id is not None and o.sentence_id == sentence_id else None


def _usable_tokens(o: OverrideRow) -> list[_OverrideToken] | None:
    toks = [sanitise_token(t) for t in o.tokens]
    if not toks or any(t is None for t in toks):
        return None
    clean = [t for t in toks if t is not None]
    if "".join(t.surface for t in clean) != o.match_text:
        return None
    return clean


@dataclass(frozen=True)
class _Candidate:
    rank: int
    length: int
    hlc: str
    id: str
    i: int
    tokens: tuple[_OverrideToken, ...]


def apply_overrides(
    text: str,
    base: Sequence[Token],
    overrides: Sequence[OverrideRow],
    *,
    media_id: str | None,
    sentence_id: str | None,
    entry_pos: EntryPos,
    analyse: Analyse,
    skipped: set[str] | None = None,
) -> list[Token]:
    """PARSER.md 4 over one sentence. ``base`` are the step-3 tokens of ``text``.

    Rows whose sanitised token surfaces do not concatenate to ``match_text`` are skipped; their
    ids are added to ``skipped`` (the caller logs them once per parse).
    """
    hay = text.encode("utf-16-le")
    n16 = len(hay) // 2
    inside: set[int] = set()  # code-unit boundaries strictly inside a base token
    for t in base:
        inside.update(range(t.s + 1, t.e))

    cands: list[_Candidate] = []
    for o in overrides:
        r = _rank(o, media_id, sentence_id)
        if r is None:
            continue
        toks = _usable_tokens(o)
        if toks is None:
            if skipped is not None:
                skipped.add(o.id)
            continue
        needle = o.match_text.encode("utf-16-le")
        n = len(needle) // 2
        for i in range(n16 - n + 1):
            if hay[2 * i : 2 * (i + n)] != needle:
                continue
            if i in inside or (i + n) in inside:
                continue  # misaligned: would cut a base token (4.2)
            cands.append(_Candidate(r, n, o.hlc, o.id, i, tuple(toks)))

    # 4.3: (rank asc, len desc, hlc desc, id asc, i asc) via stable sorts, least key first.
    cands.sort(key=lambda c: (c.id, c.i))
    cands.sort(key=lambda c: c.hlc.encode("utf-8"), reverse=True)
    cands.sort(key=lambda c: (c.rank, -c.length))
    accepted: list[_Candidate] = []
    for c in cands:
        j = c.i + c.length
        if all(j <= a.i or c.i >= a.i + a.length for a in accepted):
            accepted.append(c)

    if not accepted:
        return list(base)

    by_span = {(t.s, t.e): t for t in base}
    out = [t for t in base if not any(a.i <= t.s and t.e <= a.i + a.length for a in accepted)]
    for a in accepted:
        pos16 = a.i
        for ot in a.tokens:
            s, e = pos16, pos16 + len16(ot.surface)
            pos16 = e
            if is_white_space(ot.surface):
                continue
            out.append(_build(ot, s, e, by_span.get((s, e)), entry_pos, analyse))
    out.sort(key=lambda t: t.s)
    return out


def _build(
    ot: _OverrideToken,
    s: int,
    e: int,
    same: Token | None,
    entry_pos: EntryPos,
    analyse: Analyse,
) -> Token:
    """PARSER.md 4.4 member table."""
    full_pos: Sequence[str]
    if same is not None:
        reading, full_pos, cform = same.reading, same.pos, same.cform
    else:
        morphs = analyse(ot.surface)
        if len(morphs) == 1 and morphs[0].pos[0] not in NON_WORD_POS:
            m = morphs[0]
            reading, full_pos = to_hiragana(m.reading), m.pos
            cform = m.cform if m.cform != "*" else None
        else:
            reading = "".join(to_hiragana(m.reading) for m in morphs)
            full_pos, cform = (), None
    if ot.reading is not None:
        reading = ot.reading
    lemma = ot.lemma or (same.lemma if same is not None else ot.surface)
    seq = ot.seq
    cls = ot.cls or derive_class(full_pos, seq, entry_pos(seq) if seq is not None else None)
    return Token(
        s=s,
        e=e,
        surface=ot.surface,
        lemma=lemma,
        reading=reading,
        pos=trim_pos(full_pos),
        cform=cform,
        seq=seq,
        cls=cls,
        override=True,
    )


# ------------------------------------------------------------------ loading

_SQL = """
select id::text, media_id::text, sentence_id::text, match_text, tokens, hlc::text
from app.parse_override
where deleted_at is null and (media_id is null or media_id = %s::uuid)
order by id
"""
_SQL_GLOBAL = """
select id::text, media_id::text, sentence_id::text, match_text, tokens, hlc::text
from app.parse_override
where deleted_at is null and media_id is null
order by id
"""


def load_overrides(conn: psycopg.Connection[Any], media_id: str | None) -> list[OverrideRow]:
    """Live overrides that can apply to ``media_id`` (sentence, media and global scope), or
    the global ones only when ``media_id`` is None (``POST /parse``)."""
    rows = (
        conn.execute(_SQL, (media_id,)).fetchall()
        if media_id is not None
        else conn.execute(_SQL_GLOBAL).fetchall()
    )
    out: list[OverrideRow] = []
    for r in rows:
        tokens = r[4]
        out.append(
            OverrideRow(
                id=str(r[0]),
                media_id=None if r[1] is None else str(r[1]),
                sentence_id=None if r[2] is None else str(r[2]),
                match_text=str(r[3]),
                tokens=tuple(tokens) if isinstance(tokens, list) else (),
                hlc=str(r[5]),
            )
        )
    return out


__all__ = [
    "Analyse",
    "EntryPos",
    "OverrideRow",
    "apply_overrides",
    "is_white_space",
    "load_overrides",
    "sanitise_token",
    "to_hiragana",
]
