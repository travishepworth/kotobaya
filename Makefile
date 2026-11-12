.PHONY: check test fmt dev-up dev-down

MODULES := core server worker web android deploy contracts
GRADLE := ./gradlew --no-daemon -q
ANDROID_SDK := $(or $(ANDROID_HOME),$(ANDROID_SDK_ROOT))

# make check MODULE=<core|server|worker|web|android|deploy|contracts>
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

check-web:
	$(GRADLE) :core:npmPackage
	cd web && pnpm install --frozen-lockfile && pnpm test && pnpm run check && pnpm build

check-android:
	@if [ -z "$(ANDROID_SDK)" ] && [ ! -f local.properties ]; then \
	  echo "SKIPPED: Android SDK not found (set ANDROID_HOME or create local.properties)"; \
	else \
	  $(GRADLE) :android:testDebugUnitTest; \
	fi

check-deploy:
	bash deploy/helm/check.sh

check-contracts:
	@echo "contracts: no checks yet (stub; contracts arrive in T003-T006)"

test:
	@for m in $(MODULES); do echo "== $$m"; $(MAKE) --no-print-directory check MODULE=$$m || exit 1; done

fmt:
	-cd worker && uv run --frozen ruff format .
	-cd web && pnpm exec eslint . --fix

dev-up:
	docker compose up -d --build --wait

dev-down:
	docker compose down

# Release artefacts (T002). Images: $(REGISTRY)/{api,worker,web}:$(VERSION).
# DRY_RUN=1 prints the commands instead of running them.
ARCH ?= amd64
REGISTRY ?= registry.example.invalid/kotobaya
VERSION ?= $(shell git describe --always --dirty)
DRY_RUN ?= 0
RUN = $(if $(filter 1,$(DRY_RUN)),echo,)
.PHONY: images push apk

images:
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f server/Dockerfile -t $(REGISTRY)/api:$(VERSION) .
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f worker/Dockerfile -t $(REGISTRY)/worker:$(VERSION) worker
	$(RUN) docker buildx build --platform linux/$(ARCH) --load -f web/Dockerfile -t $(REGISTRY)/web:$(VERSION) .

push:
	@case "$(REGISTRY)" in *.invalid*) echo "push: REGISTRY is still the placeholder '$(REGISTRY)'; set REGISTRY=<host>/<path>" >&2; exit 1;; esac
	$(RUN) docker push $(REGISTRY)/api:$(VERSION)
	$(RUN) docker push $(REGISTRY)/worker:$(VERSION)
	$(RUN) docker push $(REGISTRY)/web:$(VERSION)

# Unsigned release APK. Gradle has no signingConfig yet (android/ is outside T002 scope).
apk:
	@if [ -z "$(ANDROID_SDK)" ] && [ ! -f local.properties ]; then echo "apk: Android SDK not found (set ANDROID_HOME or create local.properties)" >&2; exit 1; fi
	$(RUN) $(GRADLE) :android:assembleRelease
