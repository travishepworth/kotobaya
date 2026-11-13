"""Tiny HTML/XHTML tree builder and ruby-aware paragraph extraction (stdlib only)."""

from __future__ import annotations

from dataclasses import dataclass, field
from html.parser import HTMLParser

from kotobaya_worker.extract.model import ExtractedSentence, ReadingHint
from kotobaya_worker.extract.split import split_sentences

VOID = frozenset(
    [
        "area",
        "base",
        "br",
        "col",
        "embed",
        "hr",
        "img",
        "input",
        "link",
        "meta",
        "param",
        "source",
        "track",
        "wbr",
    ]
)
BLOCK = frozenset(
    [
        "address",
        "article",
        "aside",
        "blockquote",
        "body",
        "dd",
        "div",
        "dl",
        "dt",
        "figcaption",
        "figure",
        "footer",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "header",
        "hr",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "pre",
        "section",
        "table",
        "tbody",
        "td",
        "tfoot",
        "th",
        "thead",
        "tr",
        "ul",
        "br",
    ]
)
HEADINGS = frozenset(["h1", "h2", "h3", "h4", "h5", "h6"])
# Never carry body text. rt/rp are handled by the ruby logic, not here.
DROP = frozenset(
    [
        "script",
        "style",
        "noscript",
        "template",
        "svg",
        "iframe",
        "button",
        "select",
        "textarea",
        "head",
        "canvas",
        "object",
    ]
)

Hint = tuple[int, int, str]  # code point indices into the paragraph text


@dataclass
class Node:
    tag: str
    attrs: dict[str, str] = field(default_factory=dict)
    children: list[Node | str] = field(default_factory=list)

    def iter(self) -> list[Node]:
        out: list[Node] = []
        for c in self.children:
            if isinstance(c, Node):
                out.append(c)
                out.extend(c.iter())
        return out

    def text(self) -> str:
        """Visible text without ruby annotations or dropped elements."""
        parts: list[str] = []
        for c in self.children:
            if isinstance(c, str):
                parts.append(c)
            elif c.tag not in DROP and c.tag not in ("rt", "rp"):
                parts.append(c.text())
        return "".join(parts)


class _Builder(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = Node("#root")
        self.stack: list[Node] = [self.root]

    @staticmethod
    def _norm(tag: str) -> str:
        return tag.lower().split(":")[-1]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = self._norm(tag)
        node = Node(tag, {k.lower(): (v or "") for k, v in attrs})
        self.stack[-1].children.append(node)
        if tag not in VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = self._norm(tag)
        self.stack[-1].children.append(Node(tag, {k.lower(): (v or "") for k, v in attrs}))

    def handle_endtag(self, tag: str) -> None:
        tag = self._norm(tag)
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data: str) -> None:
        self.stack[-1].children.append(data)


def parse_html(markup: str) -> Node:
    b = _Builder()
    b.feed(markup)
    b.close()
    return b.root


def find_all(node: Node, tag: str) -> list[Node]:
    return [n for n in node.iter() if n.tag == tag]


def u16len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


def _raw_text(n: Node) -> str:
    return "".join(c if isinstance(c, str) else _raw_text(c) for c in n.children)


def paragraphs(
    root: Node, ruby: str = "hint", skip: frozenset[int] = frozenset()
) -> list[tuple[str, list[Hint]]]:
    """Block-level text of `root` as (text, hints). `skip` holds `id()`s of nodes to leave out.

    Whitespace is dropped inside non-ASCII text (it is source line wrapping, not content); ASCII
    runs are collapsed to single spaces. `ruby` is ``strip`` (drop `<rt>/<rp>`) or ``hint``
    (also record `(start, end, reading)` over the base text, code point indices).
    """
    out: list[tuple[str, list[Hint]]] = []
    chars: list[str] = []
    hints: list[Hint] = []

    def length() -> int:
        return sum(len(c) for c in chars)

    def flush() -> None:
        raw = "".join(chars)
        hs = list(hints)
        chars.clear()
        hints.clear()
        text = raw.strip()
        if not text:
            return
        shift = len(raw) - len(raw.lstrip())
        out.append((text, [(a - shift, b - shift, r) for a, b, r in hs]))

    def add_text(s: str) -> None:
        if not s.isascii():
            s = "".join(s.split())
        else:
            lead = " " if s[:1].isspace() else ""
            trail = " " if s[-1:].isspace() else ""
            s = (lead + " ".join(s.split()) + trail) if s.strip() else (" " if s else "")
        if s:
            chars.append(s)

    def ruby_node(n: Node) -> None:
        base = ""
        for c in n.children:
            if isinstance(c, str):
                base += "".join(c.split())
            elif c.tag == "rt":
                reading = "".join(_raw_text(c).split())
                if base:
                    start = length()
                    chars.append(base)
                    if ruby == "hint" and reading:
                        hints.append((start, start + len(base), reading))
                base = ""
            elif c.tag in ("rp", "rtc") or c.tag in DROP:
                continue
            else:
                base += "".join(c.text().split())
        if base:
            chars.append(base)

    def walk(n: Node) -> None:
        if id(n) in skip or n.tag in DROP or n.tag in ("rt", "rp"):
            return
        if n.tag == "ruby":
            ruby_node(n)
            return
        block = n.tag in BLOCK
        if block:
            flush()
        for c in n.children:
            if isinstance(c, str):
                add_text(c)
            else:
                walk(c)
        if block:
            flush()

    walk(root)
    flush()
    return out


def sentences_of(
    text: str, hints: list[Hint], location: str | None = None
) -> list[ExtractedSentence]:
    """Split one paragraph into sentences; hints become UTF-16 offsets within each sentence."""
    out: list[ExtractedSentence] = []
    pos = 0
    for s in split_sentences(text):
        a = text.find(s, pos)
        if a < 0:
            a = pos
        b = a + len(s)
        pos = b
        hs: tuple[ReadingHint, ...] = tuple(
            (u16len(text[a:h0]), u16len(text[a:h1]), r)
            for h0, h1, r in hints
            if h0 >= a and h1 <= b
        )
        out.append(ExtractedSentence(s, location=location, reading_hints=hs))
    return out
