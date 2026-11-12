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
	$(GRADLE) :core:jvmTest

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
	@echo "deploy: no checks yet (stub; Helm chart arrives in T009)"

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
