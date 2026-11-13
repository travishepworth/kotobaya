# D-1 HANDOFF — DONE
- Added `deploy/helm/kotobaya/templates/httproute.yaml` (gateway.networking.k8s.io/v1), toggle `httpRoute.enabled` (default false), independent of `ingress.enabled`.
- Values: `httpRoute.parentRefs`, `httpRoute.hostnames` (empty = `kotobaya.<domain>`), `httpRoute.sse.requestTimeout` (0s).
- Routes match the Ingress: `/api/v1/events` (SSE, no request timeout) and `/api`, `/healthz` -> api; `/` -> web.
- Worker resources: request 1Gi, limit 3Gi (CPU unchanged), overridable via `worker.resources`.
- Documented in values.yaml comments (the chart has no README).
- `make check MODULE=deploy` green; default render unchanged except worker resources.
- No new dependencies. Gateway API CRDs not installed (out of scope).
- HTTPRoute backendRefs use numeric `api.port`/`web.port` (Gateway API has no named ports).
