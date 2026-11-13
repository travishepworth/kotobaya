from __future__ import annotations

import subprocess
from collections.abc import Sequence
from pathlib import Path

import pytest

from kotobaya_worker.audio import (
    ClipOptions,
    build_command,
    clip_span,
    cut_clips,
    ffmpeg_available,
    plan_clips,
)
from kotobaya_worker.audio.clips import ClipSpan, sha256_file
from kotobaya_worker.extract import extract

MEDIA = Path(__file__).resolve().parents[3] / "fixtures" / "media"
OPTS = ClipOptions()
needs_ffmpeg = pytest.mark.skipif(
    not ffmpeg_available(), reason="ffmpeg/ffprobe not on PATH; ffmpeg-backed clip tests skipped"
)


def _cp(rc: int = 0, out: bytes = b"", err: bytes = b"") -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess([], rc, out, err)


# ---- pure tests (always run) ----


def test_clip_span_padding_and_clamping() -> None:
    assert clip_span(1000, 3500, 150) == ClipSpan(850, 2800)
    assert clip_span(50, 400, 150) == ClipSpan(0, 550)  # start clamped to 0
    assert clip_span(9000, 9900, 150, source_ms=10000) == ClipSpan(8850, 1150)
    assert clip_span(9900, 9990, 150, source_ms=10000) == ClipSpan(9750, 250)  # end clamped
    assert clip_span(12000, 13000, 150, source_ms=10000) is None  # beyond the source
    assert clip_span(1000, 1000, 0) is None  # empty


def test_plan_groups_identical_timings_and_skips_untimed() -> None:
    segs = extract((MEDIA / "sample.srt").read_bytes(), "sample.srt")
    sentences = segs[0].sentences
    spans, keys, skipped = plan_clips(sentences, OPTS)
    assert not skipped
    assert len(keys) == len(sentences)
    assert len(spans) < len(sentences)  # split cues share one clip
    txt = extract((MEDIA / "sample.txt").read_bytes(), "sample.txt")[0].sentences
    assert plan_clips(txt, OPTS) == ({}, {}, {})


def test_build_command_flags() -> None:
    cmd = build_command(Path("/in.mkv"), Path("/out.ogg"), ClipSpan(850, 2800), OPTS)
    assert cmd[0] == "ffmpeg"
    assert cmd[cmd.index("-ss") + 1] == "0.850" and cmd[cmd.index("-t") + 1] == "2.800"
    assert cmd.index("-ss") < cmd.index("-i")
    for flag, val in (("-ac", "1"), ("-c:a", "libopus"), ("-b:a", "48k"), ("-f", "ogg")):
        assert cmd[cmd.index(flag) + 1] == val
    assert "-vn" in cmd and "+bitexact" in cmd and cmd[-1] == "/out.ogg"


def _fake_runner(fail_on: set[int], seen: list[Path]) -> object:
    n = {"i": 0}

    def run(cmd: Sequence[str]) -> subprocess.CompletedProcess[bytes]:
        if "ffprobe" in cmd[0]:
            return _cp(out=b"100.0\n")
        out = Path(cmd[-1])
        seen.append(out)
        i = n["i"]
        n["i"] += 1
        if i in fail_on:
            return _cp(rc=1, err=b"boom")
        out.write_bytes(f"clip{i}".encode())
        return _cp()

    return run


def test_failed_clip_is_reported_and_batch_continues_and_temp_cleaned(tmp_path: Path) -> None:
    segs = extract((MEDIA / "sample.srt").read_bytes(), "sample.srt")
    sentences = segs[0].sentences
    seen: list[Path] = []
    stored: list[Path] = []

    def store(p: Path) -> str:
        stored.append(p)
        return sha256_file(p)

    res = cut_clips(
        tmp_path / "src",
        sentences,
        OPTS,
        store,
        run=_fake_runner({1}, seen),  # type: ignore[arg-type]
    )
    assert res.failed and all("boom" in m for m in res.failed.values())
    assert set(res.hashes) | set(res.failed) == set(range(len(sentences)))
    assert set(res.hashes).isdisjoint(res.failed)
    assert len(stored) == len(set(res.hashes.values()))
    assert all(not p.exists() for p in seen)  # cleaned up, including the failed one


def test_store_error_is_per_clip_and_temp_cleaned(tmp_path: Path) -> None:
    sentences = extract((MEDIA / "sample.srt").read_bytes(), "sample.srt")[0].sentences
    seen: list[Path] = []

    def store(p: Path) -> str:
        raise OSError("disk full")

    res = cut_clips(tmp_path / "s", sentences, OPTS, store, run=_fake_runner(set(), seen))  # type: ignore[arg-type]
    assert not res.hashes and len(res.failed) == len(sentences)
    assert all(not p.exists() for p in seen)


# ---- ffmpeg-backed tests ----


def _ffprobe_ms(path: Path) -> float:
    p = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True,
        check=True,
    )
    return float(p.stdout.decode()) * 1000


@needs_ffmpeg
def test_cut_tone_by_srt_cues(tmp_path: Path) -> None:
    tone = tmp_path / "tone.wav"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=60", str(tone)],
        check=True,
    )
    sentences = extract((MEDIA / "sample.srt").read_bytes(), "sample.srt")[0].sentences
    blobs: dict[str, bytes] = {}
    puts = 0

    def store(p: Path) -> str:
        nonlocal puts
        puts += 1
        h = sha256_file(p)
        blobs.setdefault(h, p.read_bytes())
        return h

    res = cut_clips(tone, sentences, OPTS, store)
    assert not res.failed
    assert set(res.hashes) == set(range(len(sentences)))
    kept = tmp_path / "kept"
    for i, s in enumerate(sentences):
        assert s.start_ms is not None and s.end_ms is not None
        kept.write_bytes(blobs[res.hashes[i]])
        want = (s.end_ms - s.start_ms) + 2 * OPTS.padding_ms
        want -= max(OPTS.padding_ms - s.start_ms, 0)  # start clamped at 0
        assert abs(_ffprobe_ms(kept) - want) <= 50, (i, want)
    # Sentences sharing a cue share one clip: one store call per distinct timing.
    assert puts == len({(s.start_ms, s.end_ms) for s in sentences})
    # Same input again gives the same hashes (bitexact flags).
    assert cut_clips(tone, sentences, OPTS, store).hashes == res.hashes


@needs_ffmpeg
def test_non_audio_source_fails_per_clip(tmp_path: Path) -> None:
    bad = tmp_path / "bad"
    bad.write_bytes(b"not media")
    sentences = extract((MEDIA / "sample.srt").read_bytes(), "sample.srt")[0].sentences
    res = cut_clips(bad, sentences, OPTS, sha256_file)
    assert not res.hashes and len(res.failed) == len(sentences)
