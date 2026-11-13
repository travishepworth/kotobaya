# BUG-4 — VOICEVOX crash-loops (gosu under non-root); readOnlyRootFilesystem override ignored

- Phase: P7 (live bugfix) · Model: sonnet · Risk: low
- Deps: none
- Branch: t/BUG-4-voicevox
- Owned paths: `deploy/helm/kotobaya/templates/voicevox.yaml`, `deploy/helm/kotobaya/templates/_helpers.tpl` (only if the securityContext helper must take the override), `deploy/helm/kotobaya/values.yaml` (voicevox block only), `deploy/helm/kotobaya/check.sh` or the chart's template assertion test location
- Module(s) for CI: deploy

## Live report
Image voicevox/voicevox_engine:cpu-0.21.1 entrypoint runs `gosu user ...`; chart runs the pod as uid 1000,
all capabilities dropped → `failed switching to "user": operation not permitted`. Also the pod gets
`readOnlyRootFilesystem: true` though `voicevox.readOnlyRootFilesystem: false`.

## Do
- Preferred: override `command`/`args` to run the engine directly (bypass the gosu entrypoint) as the
  non-root user; verify the engine binary path/args from the image's Dockerfile/entrypoint (inspect with
  `docker run --rm --entrypoint cat <image> /entrypoint.sh` or `docker image inspect` if Docker is available;
  else document the source you used). Bind host 0.0.0.0, same port as now. Make writable dirs it needs
  (emptyDir for /tmp, home/cache) if readOnlyRootFilesystem stays true, or honour the value override.
- Fix the readOnlyRootFilesystem override so `voicevox.readOnlyRootFilesystem` is honoured.
- Do not add SETUID/SETGID unless the direct-run route is impossible (say why in HANDOFF).

## Acceptance
- [ ] Helm template assertion (in the chart's check script): with voicevox enabled, rendered container has the
      command override, runAsNonRoot, and readOnlyRootFilesystem equal to the value (test both true and false).
- [ ] If Docker is available: `docker run --user 1000 --read-only`-equivalent smoke of the overridden command reaches the engine listening (report result; not required to pass if no Docker).
- [ ] make check MODULE=deploy green.
