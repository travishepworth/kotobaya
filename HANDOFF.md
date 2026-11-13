# HANDOFF F-28

STATUS: DONE
BRANCH: t/F-28-cpu-torch
CHECK: `make check MODULE=worker` → green (770 passed, mypy, ruff clean)

## Changed (≤ 6 lines)
- worker/pyproject.toml: added direct `torch>=2.5` dep, `[tool.uv.sources]` torch -> explicit `pytorch-cpu` index (linux only), `[[tool.uv.index]]`.
- worker/uv.lock: re-locked; 0 `nvidia-*`, 0 `triton`; linux torch = 2.15.0+cpu from CPU index.
- worker/Dockerfile: installs `torch==2.15.0` from CPU index before `pip install .`.

## Acceptance → tests
- nvidia count 0, CPU index → verified via grep
- docker build OK; image 2.04GB (was multi-GB); `import torch` → 2.15.0+cpu; image removed afterwards

## New dependencies
- torch declared directly (already transitive via fsrs[optimizer]); needed so uv.sources applies.

## For the reviewer (≤ 3 lines)
- Dockerfile torch version is hardcoded; must be bumped with uv.lock.
- Remaining 2GB is mostly sudachidict-full + torch CPU; SUDACHI_DICT=core shrinks it.
