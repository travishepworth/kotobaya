#!/usr/bin/env bash
# Create a local k3d cluster, build + import dev images, install the chart, verify /healthz.
# Usage: deploy/k3d/up.sh [up|down]     env: CLUSTER (default kotobaya), HOST_PORT (default 8088)
# Dev-only credentials below; never reuse them anywhere real.
set -euo pipefail

CLUSTER="${CLUSTER:-kotobaya}"
HOST_PORT="${HOST_PORT:-8088}"
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
  k3d cluster create "$CLUSTER" --agents 0 -p "${HOST_PORT}:80@loadbalancer" --wait
kubectl config use-context "k3d-${CLUSTER}" >/dev/null

# Images: prefer `make images` if the Makefile defines it, else build T001's Dockerfiles.
if grep -q '^images:' "$ROOT/Makefile" 2>/dev/null; then
  make -C "$ROOT" images REGISTRY="$REG" TAG="$TAG"
else
  docker build -t "$REG/api:$TAG" -f "$ROOT/server/Dockerfile" "$ROOT"
  docker build -t "$REG/worker:$TAG" "$ROOT/worker"
  docker build -t "$REG/web:$TAG" -f "$ROOT/web/Dockerfile" "$ROOT"
fi
k3d image import -c "$CLUSTER" "$REG/api:$TAG" "$REG/worker:$TAG" "$REG/web:$TAG"

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
PGPW=kotobaya-dev
S3K=kotobaya
S3S=kotobaya-dev-secret
kubectl -n "$NS" create secret generic kotobaya-postgres --from-literal=POSTGRES_PASSWORD="$PGPW" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic kotobaya-app \
  --from-literal=DATABASE_URL="jdbc:postgresql://${REL}-postgres:5432/kotobaya?user=kotobaya&password=${PGPW}" \
  --from-literal=S3_ACCESS_KEY="$S3K" --from-literal=S3_SECRET_KEY="$S3S" \
  --dry-run=client -o yaml | kubectl apply -f -
S3JSON="{\"identities\":[{\"name\":\"kotobaya\",\"credentials\":[{\"accessKey\":\"$S3K\",\"secretKey\":\"$S3S\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\",\"Tagging\"]}]}"
kubectl -n "$NS" create secret generic kotobaya-objectstore \
  --from-literal=S3_ACCESS_KEY="$S3K" --from-literal=S3_SECRET_KEY="$S3S" --from-literal=s3.json="$S3JSON" \
  --dry-run=client -o yaml | kubectl apply -f -

helm upgrade --install "$REL" "$ROOT/deploy/helm/kotobaya" -n "$NS" --wait --timeout 8m \
  --set imageRegistry="$REG",imagePullPolicy=Never \
  --set api.image.tag="$TAG",worker.image.tag="$TAG",web.image.tag="$TAG" \
  --set domain=kotobaya.local --set ingress.className=traefik

echo "Waiting for /healthz via ingress (Host: $HOST) on localhost:${HOST_PORT} ..."
for _ in $(seq 1 60); do
  if curl -fsS -H "Host: $HOST" "http://localhost:${HOST_PORT}/healthz"; then
    echo
    echo "OK: cluster '$CLUSTER' is up; add '127.0.0.1 $HOST' to /etc/hosts to browse http://$HOST:${HOST_PORT}/"
    exit 0
  fi
  sleep 2
done
echo "healthz did not answer" >&2
exit 1
