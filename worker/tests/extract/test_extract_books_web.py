from __future__ import annotations

import dataclasses
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.extract import extract, extract_url
from kotobaya_worker.extract.html import FetchError

MEDIA = Path(__file__).resolve().parents[3] / "fixtures" / "media"


def _run(name: str, ruby: str | None) -> object:
    opts = {} if ruby is None else {"ruby": ruby}
    segs = extract((MEDIA / name).read_bytes(), name, "auto", opts)
    return json.loads(json.dumps([dataclasses.asdict(s) for s in segs]))


def _expected(name: str) -> object:
    return json.loads((MEDIA / name).read_text("utf-8"))


def test_epub_two_chapters_strip_has_no_hints() -> None:
    got = _run("sample.epub", "strip")
    assert got == _expected("sample.epub.strip.expected.json")
    assert isinstance(got, list) and len(got) == 2
    assert [s["title"] for s in got] == ["第一章", "第二章"]
    assert all(not x["reading_hints"] for s in got for x in s["sentences"])


def test_epub_hint_mode_reading_hints() -> None:
    got = _run("sample.epub", "hint")
    assert got == _expected("sample.epub.hint.expected.json")
    first = got[0]["sentences"][0]  # type: ignore[index]
    text = first["text"]
    assert text == "朝、私は駅まで歩いた。"
    assert [(text[a:b], r) for a, b, r in first["reading_hints"]] == [
        ("朝", "あさ"),
        ("私", "わたし"),
        ("駅", "えき"),
        ("歩", "ある"),
    ]


def test_epub_default_is_hint_and_text_identical_across_modes() -> None:
    def texts(segs: Any) -> list[str]:
        return [x["text"] for s in segs for x in s["sentences"]]

    assert texts(_run("sample.epub", None)) == texts(_run("sample.epub", "strip"))
    assert _run("sample.epub", None) == _run("sample.epub", "hint")


def test_mokuro_sentences_with_page_and_box() -> None:
    got = _run("sample.mokuro", None)
    assert got == _expected("sample.mokuro.expected.json")
    locs = [json.loads(x["location"]) for x in got[0]["sentences"]]  # type: ignore[index]
    assert [lo["page"] for lo in locs] == [1, 1, 1, 2]
    assert locs[0]["box"] == [700, 100, 200, 400]


def test_mokuro_tolerates_unknown_keys_and_orders_vertical_lines() -> None:
    doc = {
        "extra": 1,
        "pages": [
            {
                "blocks": [
                    {
                        "box": [0, 0, 10, 10],
                        "vertical": True,
                        "lines": ["左。", "右。"],
                        "lines_coords": [[[0, 0], [5, 0]], [[8, 0], [9, 0]]],
                        "future": {},
                    },
                    {"lines": []},
                ]
            }
        ],
    }
    segs = extract(json.dumps(doc).encode(), "v.mokuro", "mokuro")
    assert [s.text for s in segs[0].sentences] == ["右。", "左。"]


def test_html_only_article_text() -> None:
    got = _run("sample.html", "hint")
    assert got == _expected("sample.html.expected.json")
    assert _run("sample.html", "strip") == _expected("sample.html.strip.expected.json")
    joined = "".join(x["text"] for s in got for x in s["sentences"])  # type: ignore[attr-defined]
    for noise in ("ホーム", "人気の記事", "共有", "著作権", "広告", "サンプルブログ"):
        assert noise not in joined
    assert got[0]["title"] == "猫と散歩"  # type: ignore[index]


def test_html_without_article_picks_densest_block() -> None:
    page = (
        "<html><body><div id=top><a href=/>home</a></div>"
        "<div id=content><p>これは本文です。長い文章がここに続きます。</p>"
        "<p>二つ目の段落です。</p></div></body></html>"
    )
    segs = extract(page.encode(), "p.html", "html")
    assert [s.text for s in segs[0].sentences] == [
        "これは本文です。",
        "長い文章がここに続きます。",
        "二つ目の段落です。",
    ]


def test_url_with_fake_fetcher() -> None:
    body = (MEDIA / "sample.html").read_bytes()
    calls: list[str] = []

    def fake(url: str, max_bytes: int, timeout: float) -> bytes:
        calls.append(url)
        return body

    segs = extract_url("https://example.com/post", {"ruby": "strip"}, fetch=fake)
    assert calls == ["https://example.com/post"]
    assert segs[0].sentences[0].text == "今日は猫と散歩に行った。"


@pytest.mark.parametrize("url", ["file:///etc/passwd", "ftp://example.com/a", "javascript:1", "/x"])
def test_url_non_http_scheme_refused(url: str) -> None:
    def boom(url: str, max_bytes: int, timeout: float) -> bytes:
        raise AssertionError("must not fetch")

    with pytest.raises(FetchError):
        extract_url(url, fetch=boom)


def test_url_size_cap_enforced() -> None:
    big = lambda url, max_bytes, timeout: b"a" * (max_bytes + 1)
    with pytest.raises(FetchError):
        extract_url("https://example.com/", fetch=big, max_bytes=100)


@pytest.fixture
def net(monkeypatch: pytest.MonkeyPatch) -> Iterator[dict[str, Any]]:
    """Fake DNS table plus a connector that records IPs and talks to a local server."""
    import http.server
    import socket
    import threading

    from kotobaya_worker.extract import html as h

    state: dict[str, Any] = {"dns": {}, "connected": [], "redirect": None, "body": b"ok"}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if state["redirect"]:
                self.send_response(302)
                self.send_header("Location", state["redirect"])
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self.send_response(200)
                self.send_header("Content-Length", str(len(state["body"])))
                self.end_headers()
                self.wfile.write(state["body"])

        def log_message(self, *a: object) -> None:
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    def create(ip: str, port: int, timeout: float) -> socket.socket:
        state["connected"].append(ip)
        return socket.create_connection(("127.0.0.1", srv.server_port), timeout)

    monkeypatch.setattr(h, "_resolve", lambda host, port: state["dns"].get(host, [host]))
    monkeypatch.setattr(h, "_create_connection", create)
    yield state
    srv.shutdown()
    srv.server_close()


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/",
        "http://10.0.0.5/",
        "http://192.168.1.1/",
        "http://172.16.0.1/",
        "http://169.254.169.254/latest/meta-data",
        "http://100.64.0.1/",
        "http://224.0.0.1/",
        "http://0.0.0.0/",
        "http://[::1]/",
        "http://[fd00::1]/",
        "http://[fe80::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://[::ffff:169.254.169.254]/",
    ],
)
def test_ssrf_private_literal_refused(net: dict[str, Any], url: str) -> None:
    with pytest.raises(FetchError, match="non-public"):
        extract_url(url)
    assert net["connected"] == []


def test_ssrf_hostname_resolving_to_private_refused(net: dict[str, Any]) -> None:
    net["dns"]["evil.example"] = ["93.184.216.34", "10.1.2.3"]
    with pytest.raises(FetchError, match="non-public"):
        extract_url("http://evil.example/")
    assert net["connected"] == []


def test_ssrf_redirect_to_private_refused(net: dict[str, Any]) -> None:
    net["dns"]["good.example"] = ["93.184.216.34"]
    net["redirect"] = "http://169.254.169.254/latest"
    with pytest.raises(FetchError, match="non-public"):
        extract_url("http://good.example/")
    assert net["connected"] == ["93.184.216.34"]


def test_redirect_to_other_scheme_refused(net: dict[str, Any]) -> None:
    net["dns"]["good.example"] = ["93.184.216.34"]
    net["redirect"] = "file:///etc/passwd"
    with pytest.raises(FetchError, match="http/https"):
        extract_url("http://good.example/")


def test_redirect_loop_capped(net: dict[str, Any]) -> None:
    net["dns"]["good.example"] = ["93.184.216.34"]
    net["redirect"] = "http://good.example/again"
    with pytest.raises(FetchError, match="too many redirects"):
        extract_url("http://good.example/")


def test_public_address_allowed_and_pinned(net: dict[str, Any]) -> None:
    net["dns"]["good.example"] = ["93.184.216.34"]
    net["body"] = "<html><body><article><p>今日は猫です。</p></article></body></html>".encode()
    segs = extract_url("http://good.example/x")
    assert net["connected"] == ["93.184.216.34"]
    assert segs[0].sentences[0].text == "今日は猫です。"
    net["dns"]["v6.example"] = ["2606:4700::1111"]
    extract_url("http://v6.example/")
    assert net["connected"][-1] == "2606:4700::1111"
