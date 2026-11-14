.PHONY: check test fmt dev-up dev-down dev-ui screenshots-web screenshots-app tokens tokens-check

MODULES := core server worker web android deploy contracts acceptance cli
GRADLE := ./gradlew --no-daemon -q
ANDROID_SDK := $(or $(ANDROID_HOME),$(ANDROID_SDK_ROOT))

# make check MODULE=<core|server|worker|web|android|deploy|contracts|acceptance>
# deploy and contracts are no-op stubs until T009 (Helm) and T003-T006 (contracts) fill them.
check:
ifndef MODULE
	$(error usage: make check MODULE=<$(MODULES)>)
endif
	@$(MAKE) --no-print-directory check-$(MODULE)

check-core:
	$(GRADLE) :core:jvmTest :core:jsTest

check-server:
	$(GRADLE) :server:test

check-worker:
	cd worker && uv run --frozen pytest -q && uv run --frozen mypy && uv run --frozen ruff check .

check-cli:
	cd cli && uv run --frozen pytest -q && uv run --frozen mypy && uv run --frozen ruff check .

check-web: tokens-check
	$(GRADLE) :core:npmPackage
	cd web && pnpm install --frozen-lockfile && pnpm test && pnpm run check && pnpm build

check-android:
	@if [ -z "$(ANDROID_SDK)" ] && [ ! -f local.properties ]; then \
	  echo "SKIPPED: Android SDK not found (set ANDROID_HOME or create local.properties)"; \
	else \
	  $(GRADLE) :android:testDebugUnitTest; \
	fi

check-acceptance:
	$(GRADLE) :acceptance:test

check-deploy:
	bash deploy/helm/check.sh

check-contracts:
	bash contracts/db/check.sh
	bash contracts/sync/check.sh
	bash contracts/api/check.sh
	bash contracts/core/check.sh
	bash clients/generate.sh
	git diff --exit-code -- clients/
	@test -z "$$(git ls-files --others --exclude-standard -- clients/)" || { echo "clients/: untracked generated files (drift)"; exit 1; }

test:
	@for m in $(MODULES); do echo "== $$m"; $(MAKE) --no-print-directory check MODULE=$$m || exit 1; done

fmt:
	-cd worker && uv run --frozen ruff format .
	-cd web && pnpm exec eslint . --fix

dev-up:
	docker compose up -d --build --wait

dev-down:
	docker compose down

# Design tokens (UI-DS): design/tokens/tokens.json → web/src/lib/tokens.css, the token block of
# assets/card/card.css, web/src/lib/ui/tokens.generated.ts and the Compose theme (android .../ui/theme/Tokens.kt).
tokens:
	node design/tokens/generate.mjs

# Drift (outputs match the JSON) + WCAG AA contrast of every semantic pair; part of check-web.
tokens-check:
	node --test design/tokens/

# Web UI against the demo dataset (fixtures/demo), no server needed (UI-FOUND-WEB).
# SCENARIO=default|empty|due900; switch in the browser with /__mock/scenario/<name>.
SCENARIO ?= default
dev-ui:
	$(GRADLE) :core:npmPackage
	cd web && pnpm install --frozen-lockfile && KOTOBAYA_MOCK=$(SCENARIO) pnpm exec vite dev

# Screenshot matrix of every web route into web/screenshots/ (design feedback loop, not a gate).
# Uses a system Chromium when one is on PATH, else Playwright's own (`pnpm exec playwright install chromium`).
CHROMIUM_PATH ?= $(shell command -v chromium || command -v chromium-browser || command -v google-chrome || true)
screenshots-web:
	$(GRADLE) :core:npmPackage
	cd web && pnpm install --frozen-lockfile && rm -rf screenshots/default screenshots/empty screenshots/due900 \
		&& CHROMIUM_PATH=$(CHROMIUM_PATH) pnpm exec playwright test --config playwright.config.ts
	@find web/screenshots -name '*.png' -not -path '*/.results/*' | wc -l | xargs echo "screenshots:"

# Screenshot matrix of every Android screen into android/screenshots/ (UI-FOUND-APP; Roborazzi on Robolectric):
# demo dataset x phone/foldable/tablet x light/dark x font 1.0/2.0. Design feedback loop, not a gate.
screenshots-app:
	rm -rf android/screenshots/default android/screenshots/empty
	$(GRADLE) :android:testAnkidroidDebugUnitTest -Pscreenshots --tests 'kotobaya.android.screenshots.*'
	@find android/screenshots -name '*.png' | wc -l | xargs echo "screenshots:"

# Release artefacts (T002). Images: $(REGISTRY)/{api,worker,web,backup}:$(VERSION).
# DRY_RUN=1 prints the commands instead of running them.
ARCH ?= amd64
REGISTRY ?= registry.example.invalid/kotobaya
VERSION ?= $(shell git describe --always --dirty)
DRY_RUN ?= 0
RUN = $(if $(filter 1,$(DRY_RUN)),echo,)
.PHONY: images push apk

images:
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f server/Dockerfile --build-arg KOTOBAYA_VERSION=$(VERSION) -t $(REGISTRY)/api:$(VERSION) .
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f worker/Dockerfile -t $(REGISTRY)/worker:$(VERSION) worker
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f web/Dockerfile --build-arg KOTOBAYA_VERSION=$(VERSION) -t $(REGISTRY)/web:$(VERSION) .
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f deploy/backup/Dockerfile -t $(REGISTRY)/backup:$(VERSION) deploy/backup

push:
	@case "$(REGISTRY)" in *.invalid*) echo "push: REGISTRY is still the placeholder '$(REGISTRY)'; set REGISTRY=<host>/<path>" >&2; exit 1;; esac
	$(RUN) docker push $(REGISTRY)/api:$(VERSION)
	$(RUN) docker push $(REGISTRY)/worker:$(VERSION)
	$(RUN) docker push $(REGISTRY)/web:$(VERSION)
	$(RUN) docker push $(REGISTRY)/backup:$(VERSION)

# Release APKs, both flavors (T305a, T312). Signed only when ANDROID_KEYSTORE_PATH and the other signing env vars are set (android/signing/README.md); otherwise unsigned.
apk:
	@if [ -z "$(ANDROID_SDK)" ] && [ ! -f local.properties ]; then echo "apk: Android SDK not found (set ANDROID_HOME or create local.properties)" >&2; exit 1; fi
	$(RUN) $(GRADLE) :android:assembleRelease
