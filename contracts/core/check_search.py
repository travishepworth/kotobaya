#!/usr/bin/env python3
"""Checks search.ebnf against search-examples.tsv (T006 acceptance).

A tiny interpreter for the W3C EBNF subset used in search.ebnf: rules `name ::= expr`, literals
"x" / 'x', character classes [..] / [^..] with #xN escapes and ranges, #xN, grouping, `|`,
sequence, postfix ? * +, and `A - B`. Matching is a memoised set-of-end-positions recogniser over
code points, so ambiguity and backtracking are handled exactly. Stdlib only.

Usage: python3 check_search.py [grammar] [examples]   (exit 0 = every example behaves as expected)
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

HERE = Path(__file__).resolve().parent


@dataclass(frozen=True)
class Lit:
    text: str


@dataclass(frozen=True)
class Cls:
    negated: bool
    ranges: tuple[tuple[int, int], ...]


@dataclass(frozen=True)
class Ref:
    name: str


@dataclass(frozen=True)
class Seq:
    items: tuple[object, ...]


@dataclass(frozen=True)
class Alt:
    items: tuple[object, ...]


@dataclass(frozen=True)
class Rep:
    item: object
    op: str  # '?', '*', '+'


@dataclass(frozen=True)
class Diff:
    left: object
    right: object


TOKEN = re.compile(
    r"""\s*(?:
      (?P<ident>[A-Za-z_][A-Za-z0-9_]*)
    | (?P<lit>"[^"]*"|'[^']*')
    | (?P<cls>\[\^?[^\]]*\])
    | (?P<hex>\#x[0-9A-Fa-f]+)
    | (?P<op>[()|?*+-])
    )""",
    re.VERBOSE,
)


def tokenize(body: str) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    pos = 0
    while pos < len(body):
        if body[pos:].strip() == "":
            break
        m = TOKEN.match(body, pos)
        if not m:
            raise SyntaxError(f"cannot tokenize at: {body[pos:pos + 30]!r}")
        kind = m.lastgroup
        assert kind is not None
        out.append((kind, m.group(kind)))
        pos = m.end()
    return out


def parse_class(text: str) -> Cls:
    inner = text[1:-1]
    negated = inner.startswith("^")
    if negated:
        inner = inner[1:]
    items: list[int] = []
    i = 0
    seps: list[bool] = []  # True where a '-' range operator follows the item
    while i < len(inner):
        if inner.startswith("#x", i):
            m = re.match(r"#x([0-9A-Fa-f]+)", inner[i:])
            assert m
            items.append(int(m.group(1), 16))
            i += m.end()
        else:
            items.append(ord(inner[i]))
            i += 1
        if i < len(inner) - 1 and inner[i] == "-":
            seps.append(True)
            i += 1
        else:
            seps.append(False)
    ranges: list[tuple[int, int]] = []
    j = 0
    while j < len(items):
        if seps[j]:
            ranges.append((items[j], items[j + 1]))
            j += 2
        else:
            ranges.append((items[j], items[j]))
            j += 1
    return Cls(negated, tuple(ranges))


class Parser:
    def __init__(self, tokens: list[tuple[str, str]]):
        self.t = tokens
        self.i = 0

    def peek(self) -> tuple[str, str] | None:
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self) -> tuple[str, str]:
        tok = self.t[self.i]
        self.i += 1
        return tok

    def alt(self) -> object:
        items = [self.seq()]
        while self.peek() == ("op", "|"):
            self.take()
            items.append(self.seq())
        return items[0] if len(items) == 1 else Alt(tuple(items))

    def seq(self) -> object:
        items = []
        while True:
            p = self.peek()
            if p is None or p == ("op", "|") or p == ("op", ")"):
                break
            items.append(self.diff())
        if not items:
            raise SyntaxError("empty sequence")
        return items[0] if len(items) == 1 else Seq(tuple(items))

    def diff(self) -> object:
        left = self.postfix()
        if self.peek() == ("op", "-"):
            self.take()
            return Diff(left, self.postfix())
        return left

    def postfix(self) -> object:
        node = self.primary()
        while self.peek() in (("op", "?"), ("op", "*"), ("op", "+")):
            node = Rep(node, self.take()[1])
        return node

    def primary(self) -> object:
        kind, text = self.take()
        if kind == "ident":
            return Ref(text)
        if kind == "lit":
            return Lit(text[1:-1])
        if kind == "cls":
            return parse_class(text)
        if kind == "hex":
            c = int(text[2:], 16)
            return Cls(False, ((c, c),))
        if (kind, text) == ("op", "("):
            node = self.alt()
            if self.take() != ("op", ")"):
                raise SyntaxError("expected )")
            return node
        raise SyntaxError(f"unexpected token {text!r}")


def load_grammar(path: Path) -> dict[str, object]:
    src = re.sub(r"/\*.*?\*/", " ", path.read_text(encoding="utf-8"), flags=re.S)
    parts = re.split(r"(?m)^([A-Za-z_][A-Za-z0-9_]*)\s*::=", src)
    if parts[0].strip():
        raise SyntaxError(f"text before the first rule: {parts[0].strip()[:40]!r}")
    rules: dict[str, object] = {}
    for name, body in zip(parts[1::2], parts[2::2]):
        if name in rules:
            raise SyntaxError(f"rule {name} defined twice")
        p = Parser(tokenize(body))
        rules[name] = p.alt()
        if p.peek() is not None:
            raise SyntaxError(f"rule {name}: trailing tokens {p.t[p.i:]}")
    return rules


def refs(node: object) -> set[str]:
    if isinstance(node, Ref):
        return {node.name}
    if isinstance(node, (Seq, Alt)):
        return set().union(*(refs(i) for i in node.items))
    if isinstance(node, Rep):
        return refs(node.item)
    if isinstance(node, Diff):
        return refs(node.left) | refs(node.right)
    return set()


def recogniser(rules: dict[str, object], text: str):
    sys.setrecursionlimit(20000)

    @lru_cache(maxsize=None)
    def rule(name: str, pos: int) -> frozenset[int]:
        return frozenset(match(rules[name], pos))

    def match(node: object, pos: int) -> set[int]:
        if isinstance(node, Ref):
            return set(rule(node.name, pos))
        if isinstance(node, Lit):
            return {pos + len(node.text)} if text.startswith(node.text, pos) else set()
        if isinstance(node, Cls):
            if pos >= len(text):
                return set()
            c = ord(text[pos])
            hit = any(lo <= c <= hi for lo, hi in node.ranges)
            return {pos + 1} if hit != node.negated else set()
        if isinstance(node, Seq):
            ends = {pos}
            for item in node.items:
                ends = set().union(*(match(item, e) for e in ends)) if ends else set()
            return ends
        if isinstance(node, Alt):
            return set().union(*(match(i, pos) for i in node.items))
        if isinstance(node, Rep):
            if node.op == "?":
                return {pos} | match(node.item, pos)
            seen: set[int] = set()
            frontier = {pos} if node.op == "*" else match(node.item, pos)
            while frontier:
                seen |= frontier
                nxt: set[int] = set()
                for e in frontier:
                    nxt |= match(node.item, e)
                frontier = nxt - seen
            return seen
        if isinstance(node, Diff):
            left = match(node.left, pos)
            right = match(node.right, pos)
            return left - right
        raise TypeError(node)

    return lambda start: pos_full(rule(start, 0), len(text))


def pos_full(ends: frozenset[int], n: int) -> bool:
    return n in ends


def main() -> int:
    grammar = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "search.ebnf"
    examples = Path(sys.argv[2]) if len(sys.argv) > 2 else HERE / "search-examples.tsv"
    rules = load_grammar(grammar)
    undefined = set().union(*(refs(r) for r in rules.values())) - rules.keys()
    if undefined:
        print(f"FAIL undefined rules: {sorted(undefined)}")
        return 1
    reachable: set[str] = set()
    todo = ["query"]
    while todo:
        n = todo.pop()
        if n not in reachable:
            reachable.add(n)
            todo.extend(refs(rules[n]))
    unused = rules.keys() - reachable
    if unused:
        print(f"FAIL unreachable rules: {sorted(unused)}")
        return 1
    failures = 0
    count = 0
    for line in examples.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        expect, query, *note = line.split("\t")
        q = json.loads(query)
        ok = recogniser(rules, q)("query")
        count += 1
        if ok != (expect == "+"):
            failures += 1
            print(f"FAIL {expect} {query} ({note[0] if note else ''}): parsed={ok}")
    print(f"search.ebnf: {count} examples, {failures} failures, {len(rules)} rules")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
