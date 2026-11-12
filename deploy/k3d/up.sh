#!/usr/bin/env bash
# Create a local k3d cluster, build + import dev images, install the chart, verify /healthz.
# Usage: deploy/k3d/up.sh [up|down]     env: CLUSTER (default kotobaya), HOST_PORT (http, default 8088), HTTPS_PORT (default 8443)
# Dev HTTPS: Traefik serves its default self-signed cert on :443 (browser warning once). The session
# cookie is `Secure`, so browse the https URL; plain HTTP logins do not persist.
# Then load a dictionary fixture with deploy/k3d/load-dict-fixture.sh.
# Dev-only credentials below; never reuse them anywhere real.
set -euo pipefail

CLUSTER="${CLUSTER:-kotobaya}"
HOST_PORT="${HOST_PORT:-8088}"
HTTPS_PORT="${HTTPS_PORT:-8443}"
NS=kotobaya
REL=kotobaya
TAG=dev
REG=kotobaya-local
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOST="kotobaya.kotobaya.local"

if [ "${1:-up}" = "down" ]; then
  k3d cluster delete "$CLUSTER"
  exit 0
fi

k3d cluster list "$CLUSTER" >/dev/null 2>&1 ||
  k3d cluster create "$CLUSTER" --agents 0 -p "${HOST_PORT}:80@loadbalancer" -p "${HTTPS_PORT}:443@loadbalancer" --wait
kubectl config use-context "k3d-${CLUSTER}" >/dev/null

# Images: prefer `make images` if the Makefile defines it, else build T001's Dockerfiles.
if grep -q '^images:' "$ROOT/Makefile" 2>/dev/null; then
  make -C "$ROOT" images REGISTRY="$REG" VERSION="$TAG" ARCH="$(uname -m | sed -e s/x86_64/amd64/ -e s/aarch64/arm64/)"
else
  docker build -t "$REG/api:$TAG" -f "$ROOT/server/Dockerfile" "$ROOT"
  docker build -t "$REG/worker:$TAG" "$ROOT/worker"
  docker build -t "$REG/web:$TAG" -f "$ROOT/web/Dockerfile" "$ROOT"
fi
k3d image import -c "$CLUSTER" "$REG/api:$TAG" "$REG/worker:$TAG" "$REG/web:$TAG"

# Traefik: keep the client source IP (login rate limiter). k3s applies this HelmChartConfig.
kubectl apply -f - <<'YAML'
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata: { name: traefik, namespace: kube-system }
spec:
  valuesContent: |-
    service:
      spec:
        externalTrafficPolicy: Local
YAML

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
PGPW=kotobaya-dev
# argon2id hash of the dev password 'kotobaya-dev' (login with it; dev only)
DEV_HASH='$argon2id$v=19$m=65536,t=3,p=4$nNS46snGBC5n+khwDOhU0g$tG/lNL8mFiwvFLE4jaHw5cx/UczVd1svajy7d5oXg/c'
S3K=kotobaya
S3S=kotobaya-dev-secret
kubectl -n "$NS" create secret generic kotobaya-postgres --from-literal=POSTGRES_PASSWORD="$PGPW" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic kotobaya-app \
  --from-literal=DATABASE_URL="jdbc:postgresql://${REL}-postgres:5432/kotobaya?user=kotobaya&password=${PGPW}" \
  --from-literal=S3_ACCESS_KEY="$S3K" --from-literal=S3_SECRET_KEY="$S3S" \
  --from-literal=AUTH_PASSWORD_HASH="${DEV_AUTH_PASSWORD_HASH:-$DEV_HASH}" \
  --dry-run=client -o yaml | kubectl apply -f -
S3JSON="{\"identities\":[{\"name\":\"kotobaya\",\"credentials\":[{\"accessKey\":\"$S3K\",\"secretKey\":\"$S3S\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\",\"Tagging\"]}]}"
kubectl -n "$NS" create secret generic kotobaya-objectstore \
  --from-literal=S3_ACCESS_KEY="$S3K" --from-literal=S3_SECRET_KEY="$S3S" --from-literal=s3.json="$S3JSON" \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install "$REL" "$ROOT/deploy/helm/kotobaya" -n "$NS" --wait --timeout 8m \
  --set imageRegistry="$REG",imagePullPolicy=Never \
  --set api.image.tag="$TAG",worker.image.tag="$TAG",web.image.tag="$TAG" \
  --set domain=kotobaya.local --set ingress.className=traefik \
  --set api.trustedProxies=10.42.0.0/16 \
  --set-string worker.dictSourcesJson='{}'

echo "Waiting for /healthz via ingress (Host: $HOST) on localhost:${HOST_PORT} ..."
for _ in $(seq 1 60); do
  if curl -fsS -H "Host: $HOST" "http://localhost:${HOST_PORT}/healthz"; then
    echo
    curl -fsS -H "Host: $HOST" "http://localhost:${HOST_PORT}/api/v1/health" && echo
    curl -fsSk -H "Host: $HOST" "https://localhost:${HTTPS_PORT}/api/v1/health" >/dev/null && echo "https ok"
    echo "OK: cluster '$CLUSTER' is up; add '127.0.0.1 $HOST' to /etc/hosts to browse https://$HOST:${HTTPS_PORT}/ (self-signed cert; accept the warning)"
    exit 0
  fi
  sleep 2
done
echo "healthz did not answer" >&2
exit 1
