# HANDOFF T601

STATUS: DONE
BRANCH: t/T601-voicevox-tts
CHECK: `make check MODULE=worker` green; `MODULE=deploy` green; `MODULE=server` green

## Changed (≤ 6 lines)
- server: `kotobaya/tts/TtsModule.kt` (`synthesizeSpeech`: enqueue `tts`, dedupe `tts:<sha256(voice NUL text)>`, 202/200, voice = request, else setting `tts.voice`, else "3"); registered in `Main.kt`.
- worker: `tts/job.py` (VOICEVOX audio_query+synthesis, ffmpeg mono Opus/Ogg, S3 + `app.blob` audio/tts, cache via earlier succeeded job with same dedupe key + existing blob row); registered in runner.
- helm: `voicevox.yaml`, `voicevox:` values (default off), worker `VOICEVOX_URL`, worker-only NetworkPolicy.

## Acceptance → tests
- reuse without calling VOICEVOX, one blob row → `worker/tests/tts/test_tts_job.py`
- `helm template --set voicevox.enabled=true` renders Deployment, Service, NetworkPolicy, worker env (checked by hand)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Edited outside owned paths: `worker/tests/jobs/test_dict_refresh_wiring.py` and `server/src/test/.../WiringAuditTest.kt` (synthesizeSpeech removed from 501 allowlist). `deploy/helm/check.sh` not extended (not owned).
- Root fs of the VOICEVOX pod is writable (engine writes under home); `~/.local` is an emptyDir. Image tag `cpu-0.21.1` unverified offline. Only numeric speaker ids are supported; PG cache/sink SQL untested against a real DB.
- No server test added (ticket does not require one).
