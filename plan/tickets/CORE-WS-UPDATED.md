# CORE-WS-UPDATED — word_status.updated_at depends on op arrival order

- Model: opus (writer-senior) · Risk: med · Deps: — · Gradle: yes (core + server + android parity)
- Owned: core/src/commonMain/kotlin/kotobaya/sync/client/apply/** (word status apply), server word-status apply path, their tests
## Found by SHELL-L4
Replicas converge on every word_status column except `updated_at`, which depends on the order ops are applied (out-of-order delivery). Make it deterministic (e.g. the HLC/instant of the winning op per LWW, not the apply time), the same on server and device.
## Acceptance
- [ ] Out-of-order convergence test including updated_at (core), server = device.
- [ ] make check MODULE=core, server, android green.
