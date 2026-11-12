#!/usr/bin/env bash
# Restore the newest verified backup into the cluster's database (SYNC.md 6.3 "Restore script").
#   1. scale api and worker to zero (no instance may serve the database while it is restored)
#   2. run the restore Job (restore.sh in the backup image): restore DB + blobs, set restore_pending
#   3. scale api and worker back; the api finalizes the restore at startup (new log_epoch,
#      durable_seq = max(change.seq), raised HLC) and only then becomes ready.
# env: NS (kotobaya)  REL (kotobaya)  TIMEOUT seconds (1800)
#      RESTORE_SNAPSHOT=<id> RESTORE_CONFIRM_OLDER=yes   restore an older snapshot (loses ops that
#                                                         devices already purged; asked for explicitly)
#      KUBECTL (kubectl)
# If the restore Job fails the workloads stay scaled down (the database may be half restored):
# fix the cause and re-run; the script is safe to re-run from the start.
set -euo pipefail
NS=${NS:-kotobaya}; REL=${REL:-kotobaya}; TIMEOUT=${TIMEOUT:-1800}; K=${KUBECTL:-kubectl}
k() { $K -n "$NS" "$@"; }

if [ -n "${RESTORE_SNAPSHOT:-}" ] && [ "${RESTORE_CONFIRM_OLDER:-}" != yes ]; then
  echo "RESTORE_SNAPSHOT set without RESTORE_CONFIRM_OLDER=yes. Restoring anything but the newest verified" >&2
  echo "snapshot loses ops that devices already purged. Re-run with RESTORE_CONFIRM_OLDER=yes to proceed." >&2
  exit 2
fi

API_REPLICAS=$(k get deploy "$REL-api" -o jsonpath='{.spec.replicas}')
WORKER_REPLICAS=$(k get deploy "$REL-worker" -o jsonpath='{.spec.replicas}')
echo "scaling api ($API_REPLICAS) and worker ($WORKER_REPLICAS) to 0"
k scale deploy "$REL-api" "$REL-worker" --replicas=0
k wait --for=delete pod -l "app.kubernetes.io/component in (api,worker)" --timeout=300s >/dev/null 2>&1 || true

JOB="$REL-restore-$(date -u +%Y%m%d%H%M%S)"
k create job "$JOB" --from="cronjob/$REL-backup-restore" --dry-run=client -o json |
  jq --arg s "${RESTORE_SNAPSHOT:-}" --arg c "${RESTORE_CONFIRM_OLDER:-}" \
    '.spec.template.spec.containers[0].env += [{"name":"RESTORE_SNAPSHOT","value":$s},{"name":"RESTORE_CONFIRM_OLDER","value":$c}]' |
  k apply -f - >/dev/null
echo "restore job $JOB started"

deadline=$((SECONDS + TIMEOUT)); rc=1
while [ $SECONDS -lt $deadline ]; do
  ok=$(k get job "$JOB" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)
  bad=$(k get job "$JOB" -o jsonpath='{.status.failed}' 2>/dev/null || true)
  if [ "${ok:-0}" -ge 1 ] 2>/dev/null; then rc=0; break; fi
  if [ "${bad:-0}" -ge 1 ] 2>/dev/null; then break; fi
  sleep 3
done
k logs "job/$JOB" || true
if [ $rc -ne 0 ]; then
  echo "RESTORE FAILED; api and worker left scaled to 0. Job: $JOB" >&2
  exit 1
fi

echo "scaling api and worker back up"
k scale deploy "$REL-api" --replicas="$API_REPLICAS"
k scale deploy "$REL-worker" --replicas="$WORKER_REPLICAS"
k rollout status deploy "$REL-api" --timeout=600s   # readiness = /readyz: restore finalized, scheduler checked
echo "restore complete; api is ready"
