import json
from pathlib import Path

import jpdbmap as j

REPO = Path(__file__).resolve().parents[2]

PAGE = (
    '<a href="/x/vocabulary-list?offset=50#a">Next page</a><div class="vocabulary-list">'
    '<div class="entry"><div><div class="vocabulary-spelling"><a href="/vocabulary/1482340/番組#a">'
    "<ruby>番<rt>ばん</rt></ruby><ruby>組<rt>ぐみ</rt></ruby></a>"
    '<div class="tag tooltip" data-tooltip="x">Top 5300</div></div></div>'
    '<div><div></div><div style="opacity: 0.5; margin-top: 1rem;">11</div></div></div>'
    '<div class="entry"><div><div class="vocabulary-spelling"><a href="/vocabulary/1582920/この#a">'
    "<ruby>この</ruby></a></div></div>"
    '<div><div style="opacity: 0.5; margin-top: 1rem;">197</div></div></div>'
)


def test_parse_page():
    rows = j.parse_vocab_list_html(PAGE)
    assert rows[0] == {"vid": 1482340, "spelling": "番組", "reading": "ばんぐみ",
                       "global_rank_top": 5300, "occurrences": 11, "has_furigana": True}
    assert rows[1]["reading"] == "この" and rows[1]["occurrences"] == 197 and rows[1]["global_rank_top"] is None
    assert j.next_page_offset(PAGE).endswith("offset=50")


def test_mapping_on_fixture_subset():
    idx = j.load_jmdict(REPO / "fixtures" / "dict" / "JMdict_subset.xml")
    d = json.loads((REPO / "fixtures" / "jpdb" / "reviews-sample.json").read_text(encoding="utf-8"))
    for w in d["cards_vocabulary_jp_en"]:
        method, seqs = j.map_word(idx, w["spelling"], w["reading"])
        assert method != "unmapped" and len(seqs) >= 1


def test_api_uses_cache_and_key(monkeypatch, tmp_path):
    monkeypatch.setattr(j, "CACHE_DIR", tmp_path)
    monkeypatch.setenv("JPDB_API_KEY", "secret")
    calls = []

    class Resp:
        def __init__(self, b): self.b = b
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def read(self): return self.b

    def opener(req, timeout):
        calls.append(req)
        return Resp(json.dumps({"vocabulary": [[1, 2]]}).encode())

    slept = []
    def fetch(url, **kw):
        return j.polite_fetch(url, sleep=slept.append, opener=opener, **kw)

    assert j.deck_vocabulary(7, fetch=fetch) == [[1, 2]]
    assert j.deck_vocabulary(7, fetch=fetch) == [[1, 2]]  # second call served from disk
    assert len(calls) == 1
    assert calls[0].headers["Authorization"] == "Bearer secret"
    assert "secret" not in "".join(p.name for p in tmp_path.rglob("*"))
    assert "secret" not in "".join(p.read_text() for p in tmp_path.rglob("*.json"))
