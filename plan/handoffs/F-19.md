# HANDOFF F-19

STATUS: DONE
BRANCH: t/F-19-image-version
CHECK: `make check MODULE=deploy` → green

## Changed (≤ 6 lines)
- server/Dockerfile: `ARG KOTOBAYA_VERSION=dev` plus `ENV KOTOBAYA_VERSION=${KOTOBAYA_VERSION}` in the build stage, before the gradle step.
- Makefile `images`: api buildx line now passes `--build-arg KOTOBAYA_VERSION=$(VERSION)`. Other image lines untouched.

## Acceptance → tests
- ARG declared in build stage, exposed to gradle → static review (no Gradle/docker run, per ticket)
- api build passes build-arg → `make -n images DRY_RUN=1 VERSION=test` shows `--build-arg KOTOBAYA_VERSION=test`
- Gate `make check MODULE=deploy` → green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The ENV line is redundant with ARG (ARG is already visible to RUN), but makes the gradle env explicit as the ticket asked. Not verified by a real image build.
