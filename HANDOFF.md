# HANDOFF BUG-4
Status: DONE

Changes (deploy/helm/kotobaya/templates/voicevox.yaml, deploy/helm/check.sh):
- Three root causes, all fixed:
  1. gosu entrypoint: `command: [/opt/python/bin/python3, ./run.py]` plus the image CMD's args (voicelib/runtime dirs, host 0.0.0.0, `--port` from values), bypassing gosu. No SETUID/SETGID added. Source: `docker image inspect` CMD and /entrypoint.sh of cpu-0.21.1.
  2. readOnlyRootFilesystem override ignored: Helm `merge` treats `false` as unset, so the global `true` won. Now `set (deepCopy containerSecurityContext) ...`, so the per-component value wins.
  3. Found via smoke test: the emptyDir over /home/user/.local hid the engine's Python packages (they live in ~/.local/lib), so the engine could never import uvicorn. The mount is now ~/.local/share only.
- check.sh asserts the command override, no gosu, pod runAsNonRoot, and readOnlyRootFilesystem for both true and false.

Smoke: `docker run --user 1000 --read-only --cap-drop ALL`, tmpfs on /tmp and ~/.local/share, the overridden command. Uvicorn started and /version returned "0.21.1".
make check MODULE=deploy: green.
Note: default readOnlyRootFilesystem stays false; true also worked in the smoke test (not changed).
