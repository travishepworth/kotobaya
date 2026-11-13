"""Match a parsed rule against one sentence's tokens (see ``DSL.md``).

Tokens are the parser's (C-12 JSON objects as stored in ``app.sentence.tokens``, or any object
with the same attributes: ``s``, ``e``, ``surface``, ``lemma``, ``reading``, ``pos``, ``cform``).
Offsets are UTF-16 code units and are passed through unchanged, so spans are UTF-16 too.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from kotobaya_worker.grammar.dsl import (
    Alt,
    AnyToken,
    Constraint,
    Literal,
    Node,
    Pattern,
    Repeat,
    Seq,
    TokenTest,
    norm,
    norm_reading,
)

# Backtracking steps per start token; a pathological rule gives up (no match there) instead of hanging.
STEP_BUDGET = 50_000


@dataclass(frozen=True)
class MToken:
    s: int
    e: int
    surface: str
    lemma: str
    reading: str
    pos: tuple[str, ...]
    cform: tuple[str, ...] | None  # levels of Sudachi's cform ("連用形-一般" -> ("連用形", "一般"))


def _get(tok: Any, key: str) -> Any:
    return tok.get(key) if isinstance(tok, Mapping) else getattr(tok, key, None)


def to_mtoken(tok: Any) -> MToken:
    cform = _get(tok, "cform")
    return MToken(
        s=int(_get(tok, "s")),
        e=int(_get(tok, "e")),
        surface=norm(str(_get(tok, "surface") or "")),
        lemma=norm(str(_get(tok, "lemma") or "")),
        reading=norm_reading(str(_get(tok, "reading") or "")),
        pos=tuple(norm(p) for p in (_get(tok, "pos") or ()) if p != "*"),
        cform=None if not cform or cform == "*" else tuple(norm(cform).split("-")),
    )


def to_mtokens(tokens: Iterable[Any]) -> list[MToken]:
    return [to_mtoken(t) for t in tokens]


@dataclass(frozen=True)
class Match:
    first: int  # token index, inclusive
    last: int  # token index, exclusive
    start: int  # UTF-16, inclusive
    end: int  # UTF-16, exclusive


def _levels_match(have: tuple[str, ...], wanted: tuple[str, ...]) -> bool:
    return have[: len(wanted)] == wanted


def _passes(c: Constraint, t: MToken) -> bool:
    if c.field == "word":
        ok = t.surface in c.values or t.lemma in c.values
    elif c.field == "surface":
        ok = t.surface in c.values
    elif c.field == "lemma":
        ok = t.lemma in c.values
    elif c.field == "reading":
        ok = t.reading in c.values
    elif c.field == "pos":
        ok = any(_levels_match(t.pos, v) for v in c.values if isinstance(v, tuple))
    else:
        cf = t.cform
        ok = cf is not None and any(_levels_match(cf, v) for v in c.values if isinstance(v, tuple))
    return ok != c.negate


class _Budget(Exception):
    pass


class _Run:
    def __init__(self, toks: Sequence[MToken]) -> None:
        self.toks = toks
        self.steps = 0

    def tick(self) -> None:
        self.steps += 1
        if self.steps > STEP_BUDGET:
            raise _Budget

    def ends(self, node: Node, i: int) -> Iterator[int]:
        """End positions of ``node`` matched from token ``i``, in priority order."""
        self.tick()
        toks = self.toks
        if isinstance(node, TokenTest):
            if i < len(toks) and all(_passes(c, toks[i]) for c in node.constraints):
                yield i + 1
        elif isinstance(node, AnyToken):
            if i < len(toks):
                yield i + 1
        elif isinstance(node, Literal):
            acc, j = "", i
            while j < len(toks) and (j == i or toks[j].s == toks[j - 1].e):
                acc += toks[j].surface
                j += 1
                if acc == node.text:
                    yield j
                    return
                if not node.text.startswith(acc):
                    return
        elif isinstance(node, Seq):
            yield from self.seq(node.items, 0, i)
        elif isinstance(node, Alt):
            for opt in node.options:
                yield from self.ends(opt, i)
        else:
            yield from self.repeat(node, i, 0)

    def seq(self, items: Sequence[Node], k: int, i: int) -> Iterator[int]:
        if k == len(items):
            yield i
            return
        for j in self.ends(items[k], i):
            yield from self.seq(items, k + 1, j)

    def repeat(self, node: Repeat, i: int, count: int) -> Iterator[int]:
        self.tick()
        if node.lazy and count >= node.min:
            yield i
        if count < node.max:
            for j in self.ends(node.inner, i):
                if j > i:
                    yield from self.repeat(node, j, count + 1)
        if not node.lazy and count >= node.min:
            yield i


def find_matches(pattern: Pattern, tokens: Sequence[MToken]) -> list[Match]:
    """Leftmost, non-overlapping matches of ``pattern`` in one sentence's tokens."""
    out: list[Match] = []
    i = 0
    while i < len(tokens):
        run = _Run(tokens)
        try:
            end = next((j for j in run.ends(pattern.root, i) if j > i), None)
        except _Budget:
            end = None
        if end is None:
            i += 1
            continue
        out.append(Match(i, end, tokens[i].s, tokens[end - 1].e))
        i = end
    return out


__all__ = ["STEP_BUDGET", "MToken", "Match", "find_matches", "to_mtoken", "to_mtokens"]
