# HANDOFF WEBVER-fix

STATUS: DONE
BRANCH: t/WEBVER-fix
CHECK: `make check MODULE=<module>` not run (Dockerfile-only change; ticket verification is the docker build below) → build green

## Changed (≤ 6 lines)
- web/Dockerfile: restored the two lines `# vite.config.ts imports mock/demo-api.ts ...` and `COPY fixtures/demo fixtures/demo` between `COPY clients/typescript` and `COPY web web`. WEBVER ARG/ENV lines kept.
- Branch cut from main (01e72650), not from 4b1b256e's branch.

## Acceptance → tests
- Web image builds from repo root → `docker buildx build --platform linux/amd64 --load -f web/Dockerfile --build-arg KOTOBAYA_VERSION=0.1.10-test` exit 0; test image removed.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Root cause: the fixtures/demo COPY was added in c4d8dcb0 on release/0.1.9, which is not on main. 4b1b256e was based on the pre-c4d8dcb0 Dockerfile, so main never had the lines; this is a missing-commit issue, not a real deletion in that commit.
- Restored text is byte-identical to c4d8dcb0's added lines.
