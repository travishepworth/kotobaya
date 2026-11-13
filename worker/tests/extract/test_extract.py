from __future__ import annotations

import dataclasses
import json
from pathlib import Path

import pytest

from kotobaya_worker.extract import FORMATS_IMPLEMENTED, detect_format, extract
from kotobaya_worker.extract.model import FORMATS
from kotobaya_worker.extract.split import split_sentences

MEDIA = Path(__file__).resolve().parents[3] / "fixtures" / "media"

CASES = [
    ("sample.srt", "auto"),
    ("sample.ass", "auto"),
    ("sample.vtt", "auto"),
    ("sample.txt", "auto"),
    ("sample-sjis.txt", "auto"),
    ("sample-texthooker.txt", "texthooker"),
]


def _run(name: str, fmt: str) -> list[dict[str, object]]:
    segs = extract((MEDIA / name).read_bytes(), name, fmt)
    return [dataclasses.asdict(s) for s in segs]


@pytest.mark.parametrize(("name", "fmt"), CASES)
def test_fixture_matches_expected(name: str, fmt: str) -> None:
    expected = json.loads((MEDIA / (name + ".expected.json")).read_text("utf-8"))
    # dataclasses.asdict keeps tuples; JSON has lists.
    assert json.loads(json.dumps(_run(name, fmt))) == expected


@pytest.mark.parametrize(("name", "fmt"), CASES)
def test_invariants_no_empty_no_leaked_markup(name: str, fmt: str) -> None:
    segs = extract((MEDIA / name).read_bytes(), name, fmt)
    assert segs
    for seg in segs:
        assert seg.sentences
        for s in seg.sentences:
            assert s.text.strip() == s.text and s.text
            assert (s.start_ms is None) == (s.end_ms is None)
            if s.start_ms is not None and s.end_ms is not None:
                assert 0 <= s.start_ms <= s.end_ms
            for leak in ("{\\", "\\N", "<", ">", "&nbsp;", "さんぽ"):
                assert leak not in s.text


def test_sjis_equals_utf8() -> None:
    assert (
        _run("sample.txt", "auto")[0]["sentences"]
        == _run("sample-sjis.txt", "auto")[0]["sentences"]
    )


def test_detect_format() -> None:
    for ext, fmt in [
        ("a.SRT", "srt"),
        ("a.ass", "ass"),
        ("a.ssa", "ass"),
        ("a.vtt", "vtt"),
        ("a.epub", "epub"),
        ("a.txt", "txt"),
        ("a.mokuro", "mokuro"),
        ("a.html", "html"),
    ]:
        assert detect_format(ext, "auto") == fmt
    for declared in FORMATS:
        assert detect_format("whatever.bin", declared) == declared
    assert detect_format("log.txt", "texthooker") == "texthooker"
    with pytest.raises(ValueError):
        detect_format("a.bin", "auto")
    with pytest.raises(ValueError):
        detect_format("a.txt", "nope")


def test_implemented_formats_are_extractable() -> None:
    assert set(FORMATS_IMPLEMENTED) == {"srt", "ass", "vtt", "txt", "texthooker"}


def test_splitter() -> None:
    assert split_sentences("") == []
    assert split_sentences("。。\n  \n") == ["。。"]
    assert split_sentences("えっ？！本当に。") == ["えっ？！", "本当に。"]
    assert split_sentences("「はい。いいえ。」と言った。") == ["「はい。いいえ。」と言った。"]
    assert split_sentences("「はい。」「うん。」") == ["「はい。」", "「うん。」"]
    assert split_sentences("（笑）\nまた明日") == ["（笑）", "また明日"]
    assert split_sentences("「閉じない\n次の文。") == ["「閉じない", "次の文。"]
