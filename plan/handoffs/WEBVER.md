# HANDOFF WEBVER

STATUS: DONE
BRANCH: t/WEBVER
CHECK: `make check MODULE=web` → green (exit 0)

## Changed (≤ 6 lines)
- web/Dockerfile: `ARG KOTOBAYA_VERSION=dev` + `ENV VITE_APP_VERSION=${KOTOBAYA_VERSION}` in build stage, before `pnpm build`.
- Makefile `images`: web line now passes `--build-arg KOTOBAYA_VERSION=$(VERSION)`.
- Dev/`make dev-ui` not changed (default stays 0.0.0-dev).

## Acceptance → tests
- Web image receives version → `make -n images VERSION=0.1.10 REGISTRY=x` web line:
  `docker buildx build --platform linux/amd64 --load -f web/Dockerfile --build-arg KOTOBAYA_VERSION=0.1.10 -t x/web:0.1.10 .`
- web check green → `make check MODULE=web`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Dockerfile build stage has no ARG before FROM; the ARG is declared in the build stage so ENV sees it.
- Relies on Vite picking up VITE_* from process.env at build time (standard Vite behaviour); not verified via docker build.
- Branch created from main (a672203f); t/OBS-1c-web is not on main, so no web/src changes here.
