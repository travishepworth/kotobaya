from __future__ import annotations

import hashlib
import io
import wave
from pathlib import Path
from typing import Any

import pytest

from kotobaya_worker.tts import dedupe_key, run_tts


def _wav(ms: int = 500) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(b"\x00\x00" * (24000 * ms // 1000))
    return buf.getvalue()


class FakeVoicevox:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def synthesize(self, text: str, voice: str) -> bytes:
        self.calls.append((text, voice))
        return _wav()


class FakeStore:
    """Stands in for S3 + app.blob + the succeeded-job cache lookup."""

    def __init__(self) -> None:
        self.blobs: dict[str, int] = {}  # app.blob rows: hash -> size
        self.objects: dict[str, bytes] = {}  # S3
        self.done: dict[str, dict[str, Any]] = {}  # dedupe key -> job result

    def store(self, path: Path, digest: str) -> None:
        self.objects[digest] = path.read_bytes()
        self.blobs.setdefault(digest, path.stat().st_size)

    def lookup(self, key: str) -> dict[str, Any] | None:
        r = self.done.get(key)
        return r if r is not None and r["blob_hash"] in self.blobs else None


class Ctx:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload

    def check_cancelled(self) -> None:
        pass


def _convert(wav: bytes, out: Path) -> None:
    out.write_bytes(b"OggS" + wav[:64])  # deterministic stand-in for ffmpeg


def _run(store: FakeStore, vv: FakeVoicevox, payload: dict[str, Any]) -> dict[str, Any]:
    result = dict(run_tts(Ctx(payload), synth=vv, convert=_convert, cache=store, sink=store))
    store.done[dedupe_key(payload.get("voice") or "3", payload["text"])] = result
    return result


def test_dedupe_key_matches_contract() -> None:
    assert dedupe_key("3", "パン") == "tts:" + hashlib.sha256("3\x00パン".encode()).hexdigest()


def test_job_yields_result_and_one_blob_then_second_job_reuses_it() -> None:
    store, vv = FakeStore(), FakeVoicevox()
    first = _run(store, vv, {"text": "パンを食べる", "voice": "3"})
    assert set(first) == {"blob_hash", "content_type", "duration_ms"}
    assert first["content_type"] == "audio/ogg"
    assert first["duration_ms"] == 500
    assert list(store.blobs) == [first["blob_hash"]]
    assert hashlib.sha256(store.objects[first["blob_hash"]]).hexdigest() == first["blob_hash"]
    assert len(vv.calls) == 1

    second = _run(store, vv, {"text": "パンを食べる", "voice": "3"})
    assert second == first
    assert len(vv.calls) == 1  # VOICEVOX not called again
    assert len(store.blobs) == 1


def test_cache_ignored_when_blob_row_is_gone() -> None:
    store, vv = FakeStore(), FakeVoicevox()
    first = _run(store, vv, {"text": "猫", "voice": "3"})
    store.blobs.clear()
    _run(store, vv, {"text": "猫", "voice": "3"})
    assert len(vv.calls) == 2
    assert list(store.blobs) == [first["blob_hash"]]


def test_missing_text_is_rejected() -> None:
    with pytest.raises(TypeError):
        run_tts(
            Ctx({"voice": "3"}),
            synth=FakeVoicevox(),
            convert=_convert,
            cache=FakeStore(),
            sink=FakeStore(),
        )
