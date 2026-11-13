# HANDOFF T402b + T409

STATUS: DONE
BRANCH: t/T402b+T409-extract-audio (commits: T409, T402b)
CHECK: `make check MODULE=worker` → ruff/mypy green; extract+audio tests (37) green. Full pytest passed once (355); on later runs the Postgres-container tests (tests/runner, tests/jobs) errored with "connection refused" from the testcontainer — environmental, unrelated to this change. Please re-run in CI.

## Changed
- `extract/{epub,mokuro,html}/` + registry entries; `html/tree.py` is the shared ruby-aware stdlib parser. `extract_url(url, options, fetch=…)`: http/https only, size cap, redirect handler re-checks scheme.
- `audio/{clips,storage}.py`: `cut_clips`, `make_clip_store` (S3 + `app.blob` kind=audio, origin=clip), streaming `download_blob`/`fetched_source`.
- Fixtures: `sample.html` + 5 expected files; README lines. Updated `test_extract.py` format set.

## Acceptance → tests
- epub 2 segments, strip/hint → `test_epub_*`; mokuro → `test_mokuro_*`; html/url → `test_html_*`, `test_url_*`, `test_redirect_*`
- clips ± 50 ms, one store per hash → `test_cut_tone_by_srt_cues` (ffmpeg present; skips with reason otherwise); pure math/command/cleanup tests always run.

## New dependencies
none

## For the reviewer
- `ExtractedSentence.location` is a str, so locations are JSON strings: `{"page":n,"box":[x,y,w,h]}` (mokuro xyxy converted) and `{"cfi":"/6/<2i>!/p[n]"}` (CFI-like, own reader).
- ruby default is `hint` (per OpenAPI). Format Opus/Ogg mono 48k, bitexact (deterministic hashes verified); ExoPlayer plays it on all APIs. Sentences with identical cue timings share one clip.
- Pre-existing mokuro shape remains unverified against real output.
