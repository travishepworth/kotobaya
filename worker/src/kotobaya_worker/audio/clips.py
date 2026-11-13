"""Cut per-sentence audio clips out of a video/audio file with ffmpeg (subprocess, no Python dep).

Format: mono Opus in Ogg, ~48 kbps. Opus is the best speech quality per byte, ffmpeg's libopus
is ubiquitous, and the Android player (ExoPlayer) decodes Ogg Opus on every supported API level.
Encoder flags are bitexact so identical input gives identical bytes (hash stability is best
effort: it depends on the ffmpeg/libopus build).
"""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from kotobaya_worker.extract.model import ExtractedSentence

CONTENT_TYPE = "audio/ogg"

# Runs a command; same shape as subprocess.run with capture. Injectable for tests.
Runner = Callable[[Sequence[str]], "subprocess.CompletedProcess[bytes]"]
# Stores one finished clip file, returns its SHA-256 (hex). Must be a no-op for known hashes.
ClipStore = Callable[[Path], str]


@dataclass(frozen=True)
class ClipOptions:
    padding_ms: int = 150
    bitrate_kbps: int = 48
    ffmpeg: str = "ffmpeg"
    ffprobe: str = "ffprobe"
    timeout_s: float = 120.0


@dataclass(frozen=True)
class ClipSpan:
    start_ms: int
    duration_ms: int


@dataclass
class CutResult:
    hashes: dict[int, str] = field(default_factory=dict)  # sentence index -> blob hash
    failed: dict[int, str] = field(default_factory=dict)  # sentence index -> error


def clip_span(
    start_ms: int, end_ms: int, padding_ms: int, source_ms: int | None = None
) -> ClipSpan | None:
    """Padded span clamped to [0, source_ms]. None if nothing is left (empty or out of range)."""
    start = max(start_ms - padding_ms, 0)
    end = max(end_ms + padding_ms, start_ms)
    if source_ms is not None:
        end = min(end, source_ms)
    if end <= start:
        return None
    return ClipSpan(start, end - start)


def plan_clips(
    sentences: Sequence[ExtractedSentence], opts: ClipOptions, source_ms: int | None = None
) -> tuple[dict[tuple[int, int], ClipSpan], dict[int, tuple[int, int]], dict[int, str]]:
    """Group timed sentences by identical (start, end): one clip serves them all.

    Returns (spans by key, sentence index -> key, sentence index -> reason it has no clip).
    Untimed sentences are simply left out (no reason: nothing was asked of them).
    """
    spans: dict[tuple[int, int], ClipSpan] = {}
    keys: dict[int, tuple[int, int]] = {}
    skipped: dict[int, str] = {}
    for i, s in enumerate(sentences):
        if s.start_ms is None or s.end_ms is None:
            continue
        key = (s.start_ms, s.end_ms)
        if key not in spans:
            span = clip_span(s.start_ms, s.end_ms, opts.padding_ms, source_ms)
            if span is None:
                skipped[i] = "span is empty or beyond the end of the source"
                continue
            spans[key] = span
        keys[i] = key
    return spans, keys, skipped


def build_command(src: Path, out: Path, span: ClipSpan, opts: ClipOptions) -> list[str]:
    return [
        opts.ffmpeg, "-nostdin", "-v", "error", "-y",
        "-ss", f"{span.start_ms / 1000:.3f}", "-i", str(src),
        "-t", f"{span.duration_ms / 1000:.3f}",
        "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1",
        "-ac", "1", "-c:a", "libopus", "-b:a", f"{opts.bitrate_kbps}k",
        "-fflags", "+bitexact", "-flags:a", "+bitexact", "-f", "ogg", str(out),
    ]  # fmt: skip


def ffmpeg_available(opts: ClipOptions | None = None) -> bool:
    o = opts or ClipOptions()
    return shutil.which(o.ffmpeg) is not None and shutil.which(o.ffprobe) is not None


def _default_runner(timeout_s: float) -> Runner:
    def run(cmd: Sequence[str]) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(cmd, capture_output=True, timeout=timeout_s, check=False)

    return run


def probe_duration_ms(src: Path, opts: ClipOptions, run: Runner) -> int | None:
    cmd = [
        opts.ffprobe,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "csv=p=0",
        str(src),
    ]
    try:
        p = run(cmd)
        return round(float(p.stdout.decode().strip()) * 1000) if p.returncode == 0 else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cut_clips(
    source: Path,
    sentences: Sequence[ExtractedSentence],
    opts: ClipOptions,
    store: ClipStore,
    run: Runner | None = None,
) -> CutResult:
    """One ffmpeg call per distinct timing; stores each clip and maps sentence index -> hash.

    A failing clip (ffmpeg error, timeout, store error) is recorded in `failed` and the batch
    carries on. Temp files are removed whatever happens.
    """
    runner = run or _default_runner(opts.timeout_s)
    result = CutResult()
    spans, keys, skipped = plan_clips(sentences, opts)
    result.failed.update(skipped)
    if not spans:
        return result
    total = probe_duration_ms(source, opts, runner)
    if total is not None:
        spans, keys, skipped = plan_clips(sentences, opts, total)
        result.failed = dict(skipped)
    by_key: dict[tuple[int, int], str] = {}
    errors: dict[tuple[int, int], str] = {}
    with tempfile.TemporaryDirectory(prefix="kotobaya-clips-") as tmp:
        for n, (key, span) in enumerate(spans.items()):
            out = Path(tmp) / f"clip{n}.ogg"
            try:
                p = runner(build_command(source, out, span, opts))
                if p.returncode != 0:
                    msg = p.stderr.decode("utf-8", "replace").strip()[-300:]
                    raise RuntimeError(f"ffmpeg exit {p.returncode}: {msg}")
                if not out.exists() or out.stat().st_size == 0:
                    raise RuntimeError("ffmpeg produced no output")
                by_key[key] = store(out)
            except (OSError, RuntimeError, subprocess.SubprocessError) as e:
                errors[key] = str(e)
            finally:
                out.unlink(missing_ok=True)
    for i, key in keys.items():
        if key in by_key:
            result.hashes[i] = by_key[key]
        else:
            result.failed[i] = errors.get(key, "no clip produced")
    return result
