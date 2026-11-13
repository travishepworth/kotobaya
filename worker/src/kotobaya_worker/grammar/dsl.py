"""Grammar rule language: text -> AST (see ``DSL.md`` next to this file).

Pure. ``parse_rule`` raises :class:`GrammarRuleError` with a 1-based column (code points) for
every invalid rule; a returned :class:`Pattern` is always matchable.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from typing import Literal as Lit

from kotobaya_worker.dict.normalize import fold_katakana

MAX_RULE_LENGTH = 500
MAX_REPEAT = 10
GAP_MAX = 5

Field = Lit["word", "surface", "lemma", "reading", "pos", "cform"]

POS_ALIASES: dict[str, str] = {
    "verb": "動詞",
    "adj": "形容詞",
    "na-adj": "形状詞",
    "adjna": "形状詞",
    "noun": "名詞",
    "pron": "代名詞",
    "particle": "助詞",
    "aux": "助動詞",
    "adv": "副詞",
    "adnominal": "連体詞",
    "conjunction": "接続詞",
    "interj": "感動詞",
    "prefix": "接頭辞",
    "suffix": "接尾辞",
    "symbol": "記号",
}
SUDACHI_POS = frozenset(
    {
        "名詞", "代名詞", "形状詞", "連体詞", "副詞", "接続詞", "感動詞", "動詞", "形容詞",
        "助動詞", "助詞", "接頭辞", "接尾辞", "補助記号", "記号", "空白",
    }
)  # fmt: skip
CFORM_ALIASES: dict[str, str] = {
    "mizen": "未然形",
    "neg": "未然形",
    "renyou": "連用形",
    "masu": "連用形",
    "shuushi": "終止形",
    "plain": "終止形",
    "rentai": "連体形",
    "katei": "仮定形",
    "ba": "仮定形",
    "meirei": "命令形",
    "imp": "命令形",
    "ishi": "意志推量形",
    "vol": "意志推量形",
    "stem": "語幹",
}
SUDACHI_CFORMS = frozenset(
    {
        "未然形",
        "連用形",
        "終止形",
        "連体形",
        "仮定形",
        "命令形",
        "意志推量形",
        "語幹",
        "已然形",
        "ク語法",
    }
)
KEYS: frozenset[str] = frozenset({"word", "surface", "lemma", "reading", "pos", "cform"})

_SPECIAL = frozenset('()[]{}|?"*…')


class GrammarRuleError(ValueError):
    """An invalid rule. ``column`` is 1-based, in code points of the rule text."""

    def __init__(self, message: str, column: int) -> None:
        super().__init__(f"column {column}: {message}")
        self.message = message
        self.column = column


# ------------------------------------------------------------------------------------- AST


@dataclass(frozen=True)
class Constraint:
    """One test on one token. ``values``: strings, or level tuples for ``pos`` / ``cform``."""

    field: Field
    values: tuple[str, ...] | tuple[tuple[str, ...], ...]
    negate: bool = False


@dataclass(frozen=True)
class TokenTest:
    constraints: tuple[Constraint, ...]
    col: int


@dataclass(frozen=True)
class Literal:
    """One or more adjacent tokens whose surfaces concatenate to ``text`` (NFKC)."""

    text: str
    col: int


@dataclass(frozen=True)
class AnyToken:
    col: int


@dataclass(frozen=True)
class Seq:
    items: tuple[Node, ...]


@dataclass(frozen=True)
class Alt:
    options: tuple[Node, ...]


@dataclass(frozen=True)
class Repeat:
    inner: Node
    min: int
    max: int
    lazy: bool


Node = TokenTest | Literal | AnyToken | Seq | Alt | Repeat


@dataclass(frozen=True)
class Pattern:
    source: str
    root: Node


def norm(text: str) -> str:
    return unicodedata.normalize("NFKC", text)


def norm_reading(text: str) -> str:
    return fold_katakana(norm(text))


# ----------------------------------------------------------------------------------- lexer


@dataclass(frozen=True)
class _Lex:
    kind: str  # word, string, bracket, star, gap, lpar, rpar, bar, quant
    text: str  # word / string value / bracket body / quantifier source
    col: int  # 1-based


def _lex(src: str) -> list[_Lex]:
    out: list[_Lex] = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        col = i + 1
        if ch.isspace():
            i += 1
        elif ch in "()|*…?":
            kind = {"(": "lpar", ")": "rpar", "|": "bar", "*": "star", "…": "gap", "?": "quant"}
            out.append(_Lex(kind[ch], ch, col))
            i += 1
        elif src.startswith("...", i):
            out.append(_Lex("gap", "...", col))
            i += 3
        elif ch == ".":
            raise GrammarRuleError("unexpected '.' (a gap is '...' or '…')", col)
        elif ch == "{":
            j = src.find("}", i)
            if j < 0:
                raise GrammarRuleError("unclosed '{'", col)
            out.append(_Lex("quant", src[i : j + 1], col))
            i = j + 1
        elif ch == "}":
            raise GrammarRuleError("unexpected '}'", col)
        elif ch == "[":
            j = src.find("]", i)
            if j < 0:
                raise GrammarRuleError("unclosed '['", col)
            out.append(_Lex("bracket", src[i + 1 : j], col))
            i = j + 1
        elif ch == "]":
            raise GrammarRuleError("unexpected ']'", col)
        elif ch == '"':
            buf: list[str] = []
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\" and j + 1 < n:
                    j += 1
                buf.append(src[j])
                j += 1
            if j >= n:
                raise GrammarRuleError("unclosed '\"'", col)
            if not buf:
                raise GrammarRuleError("empty quoted text", col)
            out.append(_Lex("string", "".join(buf), col))
            i = j + 1
        else:
            j = i
            while (
                j < n
                and not src[j].isspace()
                and src[j] not in _SPECIAL
                and not src.startswith("...", j)
            ):
                j += 1
            out.append(_Lex("word", src[i:j], col))
            i = j
    return out


# --------------------------------------------------------------------------- token tests


def _levels(value: str, col: int, kind: Lit["pos", "cform"]) -> tuple[str, ...]:
    if kind == "pos":
        base = POS_ALIASES.get(value.lower())
        if base is not None:
            return (base,)
        levels = tuple(norm(v) for v in value.split("-"))
        if levels[0] not in SUDACHI_POS or any(not v for v in levels):
            raise GrammarRuleError(
                f"unknown part of speech '{value}' (use {', '.join(POS_ALIASES)} "
                "or Sudachi levels such as 助詞-接続助詞)",
                col,
            )
        return levels
    base = CFORM_ALIASES.get(value.lower())
    if base is not None:
        return (base,)
    levels = tuple(norm(v) for v in value.split("-"))
    if levels[0] not in SUDACHI_CFORMS or any(not v for v in levels):
        raise GrammarRuleError(
            f"unknown conjugation form '{value}' (use {', '.join(CFORM_ALIASES)} "
            "or Sudachi forms such as 未然形 or 連用形-促音便)",
            col,
        )
    return levels


def _values(raw: str, col: int) -> list[str]:
    vals = [v.strip() for v in raw.split("/")]
    if not raw.strip() or any(not v for v in vals):
        raise GrammarRuleError("empty value", col)
    return vals


def _keyed(field: str, raw: str, negate: bool, col: int, vcol: int) -> Constraint:
    if field not in KEYS:
        raise GrammarRuleError(f"unknown key '{field}' (use {', '.join(sorted(KEYS))})", col)
    vals = _values(raw, col)
    if field == "pos":
        return Constraint("pos", tuple(_levels(v, vcol, "pos") for v in vals), negate)
    if field == "cform":
        return Constraint("cform", tuple(_levels(v, vcol, "cform") for v in vals), negate)
    if field == "reading":
        return Constraint("reading", tuple(norm_reading(v) for v in vals), negate)
    f: Field = "word" if field == "word" else "surface" if field == "surface" else "lemma"
    return Constraint(f, tuple(norm(v) for v in vals), negate)


def _bracket(body: str, col: int) -> TokenTest:
    if not body.strip():
        raise GrammarRuleError("empty token test '[]'", col)
    constraints: list[Constraint] = []
    offset = col + 1  # column of body[0]
    for k, part in enumerate(body.split(",")):
        lead = len(part) - len(part.lstrip())
        pcol = offset + lead
        item = part.strip()
        offset += len(part) + 1
        if not item:
            raise GrammarRuleError("empty item in token test", pcol)
        if "=" in item:
            key, _, raw = item.partition("=")
            negate = key.endswith("!")
            key = key.rstrip("!").strip()
            vcol = pcol + item.index("=") + 1
            constraints.append(_keyed(key, raw, negate, pcol, vcol))
            continue
        if k != 0:
            raise GrammarRuleError(
                "only the first item of a token test may be a bare POS[:CFORM]; use key=value",
                pcol,
            )
        pos_raw, colon, cform_raw = item.partition(":")
        pos_raw = pos_raw.strip()
        if pos_raw not in ("*", "_"):
            vals = _values(pos_raw, pcol)
            constraints.append(Constraint("pos", tuple(_levels(v, pcol, "pos") for v in vals)))
        if colon:
            ccol = pcol + item.index(":") + 1
            vals = _values(cform_raw, ccol)
            constraints.append(Constraint("cform", tuple(_levels(v, ccol, "cform") for v in vals)))
        elif pos_raw in ("*", "_"):
            raise GrammarRuleError("'[*]' tests nothing; use * for any token", pcol)
    return TokenTest(tuple(constraints), col)


def _word(text: str, col: int) -> TokenTest:
    return TokenTest((Constraint("word", (norm(text),)),), col)


# ---------------------------------------------------------------------------------- parser


def _quant(lex: _Lex) -> tuple[int, int]:
    if lex.text == "?":
        return 0, 1
    body = lex.text[1:-1]
    parts = [p.strip() for p in body.split(",")]
    if len(parts) not in (1, 2) or not all(p.isdigit() and p.isascii() for p in parts):
        raise GrammarRuleError(f"bad quantifier '{lex.text}' (use {{n}} or {{m,n}})", lex.col)
    lo, hi = int(parts[0]), int(parts[-1])
    if lo > hi or hi < 1:
        raise GrammarRuleError(f"bad quantifier bounds '{lex.text}' (need m <= n, n >= 1)", lex.col)
    if hi > MAX_REPEAT:
        raise GrammarRuleError(f"quantifier '{lex.text}' may repeat at most {MAX_REPEAT}", lex.col)
    return lo, hi


class _Parser:
    def __init__(self, toks: list[_Lex], src: str) -> None:
        self.toks = toks
        self.i = 0
        self.end_col = len(src) + 1

    def peek(self) -> _Lex | None:
        return self.toks[self.i] if self.i < len(self.toks) else None

    def alt(self, opener: _Lex | None) -> Node:
        options = [self.seq(opener)]
        while (t := self.peek()) is not None and t.kind == "bar":
            self.i += 1
            options.append(self.seq(t))
        return options[0] if len(options) == 1 else Alt(tuple(options))

    def seq(self, opener: _Lex | None) -> Node:
        items: list[Node] = []
        while (t := self.peek()) is not None and t.kind not in ("bar", "rpar"):
            items.append(self.item())
        if not items:
            t = self.peek()
            col = t.col if t is not None else self.end_col
            if opener is None and t is None:
                col = 1
            raise GrammarRuleError("empty alternative", col)
        return items[0] if len(items) == 1 else Seq(tuple(items))

    def item(self) -> Node:
        t = self.toks[self.i]
        self.i += 1
        node: Node
        gap = False
        if t.kind == "word":
            node = _word(t.text, t.col)
        elif t.kind == "string":
            node = Literal(norm(t.text), t.col)
        elif t.kind == "bracket":
            node = _bracket(t.text, t.col)
        elif t.kind == "star":
            node = AnyToken(t.col)
        elif t.kind == "gap":
            node, gap = Repeat(AnyToken(t.col), 0, GAP_MAX, lazy=True), True
        elif t.kind == "lpar":
            node = self.alt(t)
            close = self.peek()
            if close is None or close.kind != "rpar":
                raise GrammarRuleError("unclosed '('", t.col)
            self.i += 1
        elif t.kind == "rpar":
            raise GrammarRuleError("unexpected ')'", t.col)
        elif t.kind == "quant":
            raise GrammarRuleError(f"quantifier without an element: '{t.text}'", t.col)
        else:  # pragma: no cover - lexer kinds are closed
            raise GrammarRuleError(f"unexpected '{t.text}'", t.col)
        q = self.peek()
        if q is not None and q.kind == "quant":
            if gap:
                raise GrammarRuleError("a gap ('…') takes no quantifier; use *{m,n}", q.col)
            self.i += 1
            lo, hi = _quant(q)
            node = Repeat(node, lo, hi, lazy=isinstance(node, AnyToken))
            q2 = self.peek()
            if q2 is not None and q2.kind == "quant":
                raise GrammarRuleError("a quantifier cannot follow another quantifier", q2.col)
        return node


def min_tokens(node: Node) -> int:
    if isinstance(node, (TokenTest, Literal, AnyToken)):
        return 1
    if isinstance(node, Seq):
        return sum(min_tokens(n) for n in node.items)
    if isinstance(node, Alt):
        return min(min_tokens(n) for n in node.options)
    return node.min * min_tokens(node.inner)


def _is_gap(node: Node) -> bool:
    return isinstance(node, AnyToken) or (
        isinstance(node, Repeat) and isinstance(node.inner, AnyToken)
    )


def _gap_col(node: Node) -> int:
    inner = node.inner if isinstance(node, Repeat) else node
    assert isinstance(inner, AnyToken)
    return inner.col


def _check_edges(node: Node) -> None:
    options = node.options if isinstance(node, Alt) else (node,)
    for opt in options:
        items = opt.items if isinstance(opt, Seq) else (opt,)
        if _is_gap(items[0]):
            raise GrammarRuleError("a rule cannot start with a gap ('*', '…')", _gap_col(items[0]))
        if _is_gap(items[-1]):
            raise GrammarRuleError("a rule cannot end with a gap ('*', '…')", _gap_col(items[-1]))


def parse_rule(source: str) -> Pattern:
    """Parse one rule. Raises :class:`GrammarRuleError`."""
    if len(source) > MAX_RULE_LENGTH:
        raise GrammarRuleError(f"rule too long (max {MAX_RULE_LENGTH} characters)", 1)
    toks = _lex(source)
    if not toks:
        raise GrammarRuleError("empty rule", 1)
    p = _Parser(toks, source)
    root = p.alt(None)
    rest = p.peek()
    if rest is not None:  # only a stray ')' stops the top-level alternative early
        raise GrammarRuleError("unexpected ')'", rest.col)
    _check_edges(root)
    if min_tokens(root) == 0:
        raise GrammarRuleError("the rule can match no tokens (everything is optional)", 1)
    return Pattern(source, root)


__all__ = [
    "Alt",
    "AnyToken",
    "Constraint",
    "GrammarRuleError",
    "Literal",
    "Node",
    "Pattern",
    "Repeat",
    "Seq",
    "TokenTest",
    "min_tokens",
    "norm",
    "norm_reading",
    "parse_rule",
]
