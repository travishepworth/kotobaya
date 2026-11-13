"""HTML / URL extractor: stdlib readability heuristic, one `whole` segment."""

from __future__ import annotations

import http.client
import ipaddress
import re
import socket
import ssl
from collections.abc import Callable, Mapping
from urllib.parse import urljoin, urlsplit

from kotobaya_worker.extract.html.tree import (
    DROP,
    HEADINGS,
    Node,
    find_all,
    paragraphs,
    parse_html,
    sentences_of,
)
from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.text.txt import decode_japanese

MAX_BYTES = 5 * 1024 * 1024
TIMEOUT_S = 15.0
MAX_REDIRECTS = 5
_NOISE_TAGS = frozenset(["nav", "aside", "header", "footer", "form"])
_NOISE_ATTR = re.compile(
    r"sidebar|side-bar|menu|nav|footer|header|comment|advert|\bads?\b|share|social|related|breadcrumb",
    re.IGNORECASE,
)

# (url, max_bytes, timeout_s) -> body. Must raise if the body exceeds max_bytes.
Fetcher = Callable[[str, int, float], bytes]


class FetchError(Exception):
    pass


def ruby_option(options: Mapping[str, object]) -> str:
    v = options.get("ruby", "hint")
    if v not in ("strip", "hint"):
        raise ValueError(f"options.ruby must be 'strip' or 'hint', got {v!r}")
    return str(v)


def _is_noise(n: Node) -> bool:
    if n.tag in _NOISE_TAGS:
        return True
    if n.attrs.get("role") in ("navigation", "banner", "complementary", "contentinfo"):
        return True
    return bool(_NOISE_ATTR.search(n.attrs.get("class", "") + " " + n.attrs.get("id", "")))


def _noise_ids(root: Node) -> frozenset[int]:
    return frozenset(id(n) for n in root.iter() if _is_noise(n))


def _link_len(n: Node) -> int:
    return sum(len("".join(a.text().split())) for a in find_all(n, "a"))


def _score(n: Node, noise: frozenset[int]) -> int:
    """Text in the paragraphs directly under `n`, minus link text; noise subtrees score 0."""
    total = 0
    for c in n.children:
        if isinstance(c, str):
            total += len("".join(c.split()))
        elif id(c) in noise or c.tag in DROP:
            continue
        elif c.tag in ("p", "blockquote", "pre", "li"):
            total += max(len("".join(c.text().split())) - 2 * _link_len(c), 0)
    return total


def select_content(root: Node) -> tuple[Node, frozenset[int]]:
    """The article node and the ids of noise nodes to skip inside it."""
    noise = _noise_ids(root)
    for tag in ("article", "main"):
        found = [n for n in find_all(root, tag) if id(n) not in noise or tag == "article"]
        if found:
            best = max(found, key=lambda n: len("".join(n.text().split())))
            return best, noise - {id(best)}
    cands = [
        n for n in root.iter() if n.tag in ("div", "section", "td", "body") and id(n) not in noise
    ]
    if not cands:
        return root, noise
    best = max(cands, key=lambda n: _score(n, noise))
    return best, noise


def _title(root: Node, content: Node, fallback: str) -> str:
    for scope, tags in ((content, HEADINGS), (root, {"title"})):
        for n in scope.iter():
            if n.tag in tags:
                t = " ".join(n.text().split()) if n.tag != "title" else _title_text(n)
                if t:
                    return t
    return fallback


def _title_text(n: Node) -> str:
    return " ".join("".join(c for c in n.children if isinstance(c, str)).split())


def extract_html(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    ruby = ruby_option(options)
    root = parse_html(decode_japanese(data))
    content, noise = select_content(root)
    sentences: list[ExtractedSentence] = []
    for text, hints in paragraphs(content, ruby, noise):
        sentences.extend(sentences_of(text, hints))
    title = _title(root, content, default_title(filename))
    # The heading is the segment title, not a sentence.
    if (
        sentences
        and sentences[0].text == title
        and any(n.tag in HEADINGS and " ".join(n.text().split()) == title for n in content.iter())
    ):
        sentences = sentences[1:]
    return [ExtractedSegment("whole", title, tuple(sentences))]


def is_blocked_address(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """True for anything that is not a plain public unicast address."""
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return is_blocked_address(ip.ipv4_mapped)
        if ip in _NAT64:
            return is_blocked_address(ipaddress.IPv4Address(int(ip) & 0xFFFFFFFF))
        if ip in _SIXTO4:
            return is_blocked_address(ipaddress.IPv4Address((int(ip) >> 80) & 0xFFFFFFFF))
    return (
        not ip.is_global
        or ip.is_multicast
        or ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_unspecified
        or (isinstance(ip, ipaddress.IPv4Address) and ip in _CGNAT)
    )


_CGNAT = ipaddress.ip_network("100.64.0.0/10")
_NAT64 = ipaddress.ip_network("64:ff9b::/96")
_SIXTO4 = ipaddress.ip_network("2002::/16")


def _resolve(host: str, port: int) -> list[str]:
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return [str(i[4][0]) for i in infos]


def _create_connection(ip: str, port: int, timeout: float) -> socket.socket:
    return socket.create_connection((ip, port), timeout)


def resolve_public(host: str, port: int) -> str:
    """Resolve `host` and return one address, refusing if any candidate is non-public."""
    try:
        addrs = _resolve(host, port)
    except OSError as e:
        raise FetchError(f"cannot resolve {host!r}: {e}") from e
    if not addrs:
        raise FetchError(f"cannot resolve {host!r}")
    for a in addrs:
        try:
            ip = ipaddress.ip_address(a.split("%", 1)[0])
        except ValueError as e:
            raise FetchError(f"bad address {a!r} for {host!r}") from e
        if is_blocked_address(ip):
            raise FetchError(f"refusing to fetch {host!r}: resolves to non-public address {a}")
    return addrs[0]


class _PinnedHTTPConnection(http.client.HTTPConnection):
    """Connects to a pre-checked IP; Host header and TLS SNI keep the hostname."""

    def __init__(self, host: str, port: int, ip: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self._ip = ip

    def connect(self) -> None:
        self.sock = _create_connection(self._ip, self.port, self.timeout or TIMEOUT_S)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, port: int, ip: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout, context=ssl.create_default_context())
        self._ip = ip

    def connect(self) -> None:
        sock = _create_connection(self._ip, self.port, self.timeout or TIMEOUT_S)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)  # type: ignore[attr-defined]


def check_url(url: str) -> None:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc or not parts.hostname:
        raise FetchError(f"only http/https URLs are allowed: {url!r}")


def default_fetcher(url: str, max_bytes: int, timeout: float) -> bytes:
    """GET with manual redirects; every hop is scheme-checked, resolved and IP-pinned."""
    for _ in range(MAX_REDIRECTS + 1):
        check_url(url)
        parts = urlsplit(url)
        host = parts.hostname or ""
        https = parts.scheme == "https"
        try:
            port = parts.port or (443 if https else 80)
        except ValueError as e:
            raise FetchError(f"bad port in {url!r}") from e
        ip = resolve_public(host, port)
        conn: http.client.HTTPConnection = (
            _PinnedHTTPSConnection(host, port, ip, timeout)
            if https
            else _PinnedHTTPConnection(host, port, ip, timeout)
        )
        try:
            target = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
            conn.request("GET", target, headers={"User-Agent": "kotobaya-worker"})
            resp = conn.getresponse()
            if resp.status in (301, 302, 303, 307, 308):
                loc = resp.getheader("Location")
                if not loc:
                    raise FetchError(f"redirect without Location from {url!r}")
                url = urljoin(url, loc)
                continue
            if resp.status != 200:
                raise FetchError(f"HTTP {resp.status} from {url!r}")
            body: bytes = resp.read(max_bytes + 1)
        except (OSError, http.client.HTTPException) as e:
            raise FetchError(str(e)) from e
        finally:
            conn.close()
        if len(body) > max_bytes:
            raise FetchError(f"response larger than {max_bytes} bytes")
        return body
    raise FetchError("too many redirects")


def extract_url(
    url: str,
    options: Mapping[str, object] | None = None,
    fetch: Fetcher = default_fetcher,
    max_bytes: int = MAX_BYTES,
    timeout: float = TIMEOUT_S,
) -> list[ExtractedSegment]:
    """Fetch `url` (http/https only, size-capped) and extract its readable text."""
    check_url(url)
    body = fetch(url, max_bytes, timeout)
    if len(body) > max_bytes:
        raise FetchError(f"response larger than {max_bytes} bytes")
    return extract_html(body, urlsplit(url).path or "page", options or {})


__all__ = [
    "FetchError",
    "Fetcher",
    "default_fetcher",
    "extract_html",
    "extract_url",
    "ruby_option",
]
