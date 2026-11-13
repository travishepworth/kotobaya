"""Runner handler for ``tts``: VOICEVOX -> WAV -> mono Opus/Ogg -> blob (kind audio, origin tts).

Payload is ``TtsRequest`` (``text``, ``voice``; the server fills the voice in). Result is
``JobResultTts`` (``blob_hash``, ``content_type``, ``duration_ms``).

Cache (no table): an earlier *succeeded* ``tts`` job with the same dedupe key
(``tts:<sha256(voice NUL text)>``, the key the server enqueues under) whose blob row still exists
supplies the result; VOICEVOX is not called. Object first, ``app.blob`` row second.
"""

from __future__ import annotations

import hashlib
import io
import os
import subprocess
import tempfile
import urllib.parse
import urllib.request
import wave
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, Protocol

import psycopg
from psycopg.rows import dict_row

from kotobaya_worker.audio.clips import CONTENT_TYPE, ClipOptions, sha256_file
from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.package.s3 import S3Client, S3Config

KIND = "tts"
DEFAULT_VOICE = "3"  # keep in step with the server's default (kotobaya.tts.DEFAULT_VOICE)
DEFAULT_VOICEVOX_URL = "http://localhost:50021"


def dedupe_key(voice: str, text: str) -> str:
    return "tts:" + hashlib.sha256(f"{voice}\x00{text}".encode()).hexdigest()


class Synthesizer(Protocol):
    def synthesize(self, text: str, voice: str) -> bytes:
        """WAV bytes for ``text`` spoken by VOICEVOX speaker ``voice``."""
        ...


class Cache(Protocol):
    def lookup(self, key: str) -> dict[str, Any] | None:
        """Result of an earlier succeeded job under ``key`` whose blob still exists, else None."""
        ...


class BlobSink(Protocol):
    def store(self, path: Path, digest: str) -> None:
        """Durably store the object, then insert the ``app.blob`` row (audio / tts). Idempotent."""
        ...


class Context(Protocol):
    payload: dict[str, Any]

    def check_cancelled(self) -> None: ...


Convert = Callable[[bytes, Path], None]


class VoicevoxClient:
    """``POST /audio_query`` then ``POST /synthesis`` (speaker = numeric style id)."""

    def __init__(self, base_url: str, timeout_s: float = 120.0) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = timeout_s

    def _post(self, path: str, query: dict[str, str], body: bytes = b"") -> bytes:
        url = f"{self._base}{path}?{urllib.parse.urlencode(query)}"
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self._timeout) as resp:
            data: bytes = resp.read()
            return data

    def synthesize(self, text: str, voice: str) -> bytes:
        if not voice.isdigit():
            raise ValueError(f"VOICEVOX voice must be a numeric speaker id, got {voice!r}")
        query = self._post("/audio_query", {"text": text, "speaker": voice})
        return self._post("/synthesis", {"speaker": voice}, query)


def wav_duration_ms(wav_bytes: bytes) -> int:
    with wave.open(io.BytesIO(wav_bytes), "rb") as w:
        return round(w.getnframes() * 1000 / w.getframerate())


def ffmpeg_command(out: Path, opts: ClipOptions) -> list[str]:
    """WAV on stdin -> mono Opus in Ogg (same encoder flags as the clip cutter)."""
    return [
        opts.ffmpeg, "-nostdin", "-v", "error", "-y", "-f", "wav", "-i", "pipe:0",
        "-vn", "-map_metadata", "-1", "-ac", "1", "-c:a", "libopus", "-b:a",
        f"{opts.bitrate_kbps}k", "-fflags", "+bitexact", "-flags:a", "+bitexact",
        "-f", "ogg", str(out),
    ]  # fmt: skip


def ffmpeg_convert(wav: bytes, out: Path, opts: ClipOptions | None = None) -> None:
    o = opts or ClipOptions()
    p = subprocess.run(
        ffmpeg_command(out, o), input=wav, capture_output=True, timeout=o.timeout_s, check=False
    )
    if p.returncode != 0:
        tail = p.stderr.decode("utf-8", "replace")[-300:]
        raise RuntimeError(f"ffmpeg exit {p.returncode}: {tail}")
    if not out.exists() or out.stat().st_size == 0:
        raise RuntimeError("ffmpeg produced no output")


def run_tts(
    ctx: Context, *, synth: Synthesizer, convert: Convert, cache: Cache, sink: BlobSink
) -> Mapping[str, Any]:
    text = ctx.payload.get("text")
    voice = ctx.payload.get("voice") or DEFAULT_VOICE
    if not isinstance(text, str) or not text or not isinstance(voice, str):
        raise TypeError("tts payload needs a non-empty string `text` (and a string `voice`)")
    hit = cache.lookup(dedupe_key(voice, text))
    if hit is not None:
        return hit
    ctx.check_cancelled()
    wav = synth.synthesize(text, voice)
    duration = wav_duration_ms(wav)
    ctx.check_cancelled()
    with tempfile.TemporaryDirectory(prefix="kotobaya-tts-") as tmp:
        out = Path(tmp) / "speech.ogg"
        convert(wav, out)
        digest = sha256_file(out)
        sink.store(out, digest)
    return {"blob_hash": digest, "content_type": CONTENT_TYPE, "duration_ms": duration}


class PgCache:
    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self._c = conn

    def lookup(self, key: str) -> dict[str, Any] | None:
        row = self._c.execute(
            "select j.result from app.job j join app.blob b on b.hash = j.result->>'blob_hash' "
            "where j.kind = 'tts' and j.status = 'succeeded' and j.dedupe_key = %s "
            "order by j.finished_at desc limit 1",
            (key,),
        ).fetchone()
        if row is None or not isinstance(row["result"], dict):
            return None
        r: dict[str, Any] = row["result"]
        return {k: r[k] for k in ("blob_hash", "content_type", "duration_ms")}


class S3PgSink:
    def __init__(self, client: S3Client, conn: psycopg.Connection[Any]) -> None:
        self._client = client
        self._c = conn

    def store(self, path: Path, digest: str) -> None:
        self._client.ensure_bucket()
        self._client.put_blob(path, digest)
        self._c.execute(
            "insert into app.blob (hash, size_bytes, content_type, kind, origin) "
            "values (%s, %s, %s, 'audio', 'tts') on conflict (hash) do nothing",
            (digest, path.stat().st_size, CONTENT_TYPE),
        )


def make_tts(database_url: str) -> Callable[[Any], Mapping[str, Any]]:
    """Runner handler; VOICEVOX at ``VOICEVOX_URL``, object store from the S3 environment."""

    def handler(ctx: Any) -> Mapping[str, Any]:
        synth = VoicevoxClient(os.environ.get("VOICEVOX_URL", DEFAULT_VOICEVOX_URL))
        with psycopg.connect(to_conninfo(database_url), autocommit=True, row_factory=dict_row) as c:
            return run_tts(
                ctx,
                synth=synth,
                convert=ffmpeg_convert,
                cache=PgCache(c),
                sink=S3PgSink(S3Client(S3Config.from_env()), c),
            )

    return handler


__all__ = ["KIND", "dedupe_key", "make_tts", "run_tts"]
