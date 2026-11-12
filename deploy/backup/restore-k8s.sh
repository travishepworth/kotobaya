#!/usr/bin/env bash
# Restore the newest verified backup into the cluster's database (SYNC.md 6.3 "Restore script").
#   1. scale api and worker to zero (no instance may serve the database while it is restored)
#   2. run the restore Job (restore.sh in the backup image): restore DB + blobs, set restore_pending
#   3. scale api and worker back; the api finalizes the restore at startup (new log_epoch,
#      durable_seq = max(change.seq), raised HLC) and only then becomes ready.
# env: NS (kotobaya)  REL (kotobaya)  TIMEOUT seconds (1800)
#      RESTORE_SNAPSHOT=<id> RESTORE_CONFIRM_OLDER=yes   restore an older snapshot (loses ops that
#                                                         devices already purged; asked for explicitly)
#      --accept-data-loss  (or RESTORE_ACCEPT_DATA_LOSS=yes)  proceed although the backup may be older
#                          than what devices already purged (live durable_seq above the backup's S, or
#                          a retained backup's recorded L above it); without it the restore is refused
#                          before anything is stopped. The message says how many seqs may be lost.
#      KUBECTL (kubectl)
# If the restore Job fails the workloads stay scaled down (the database may be half restored):
# fix the cause and re-run; the script is safe to re-run from the start.
set -euo pipefail
NS=${NS:-kotobaya}; REL=${REL:-kotobaya}; TIMEOUT=${TIMEOUT:-1800}; K=${KUBECTL:-kubectl}
k() { $K -n "$NS" "$@"; }
RESTORE_STAMP=$(date -u +%Y%m%d%H%M%S)   # names this run's pre-restore database; cleanup drops only that one

for a in "$@"; do
  case $a in --accept-data-loss) RESTORE_ACCEPT_DATA_LOSS=yes;; *) echo "unknown argument: $a" >&2; exit 2;; esac
done

if [ -n "${RESTORE_SNAPSHOT:-}" ] && [ "${RESTORE_CONFIRM_OLDER:-}" != yes ]; then
  echo "RESTORE_SNAPSHOT set without RESTORE_CONFIRM_OLDER=yes. Restoring anything but the newest verified" >&2
  echo "snapshot loses ops that devices already purged. Re-run with RESTORE_CONFIRM_OLDER=yes to proceed." >&2
  exit 2
fi

run_restore_job() { # $1 = mode ("" = restore, "cleanup"); returns 0 when the Job succeeded
  local job ok bad deadline=$((SECONDS + TIMEOUT))
  job="$REL-restore-${1:-run}-$(date -u +%Y%m%d%H%M%S)"
  k create job "$job" --from="cronjob/$REL-backup-restore" --dry-run=client -o json |
    jq --arg s "${RESTORE_SNAPSHOT:-}" --arg c "${RESTORE_CONFIRM_OLDER:-}" --arg dl "${RESTORE_ACCEPT_DATA_LOSS:-}" --arg m "${1:-}" --arg kill "${KILL_BETWEEN_RENAMES:-}" --arg drill "${DRILL:-}" --arg stamp "$RESTORE_STAMP" \
      '.spec.template.spec.containers[0].env += [{"name":"RESTORE_SNAPSHOT","value":$s},{"name":"RESTORE_CONFIRM_OLDER","value":$c},{"name":"RESTORE_ACCEPT_DATA_LOSS","value":$dl},{"name":"RESTORE_MODE","value":$m},{"name":"KILL_BETWEEN_RENAMES","value":$kill},{"name":"DRILL","value":$drill},{"name":"RESTORE_STAMP","value":$stamp}]' |
    k apply -f - >/dev/null
  echo "job $job started"
  JOB=$job
  while [ $SECONDS -lt $deadline ]; do
    ok=$(k get job "$job" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)
    bad=$(k get job "$job" -o jsonpath='{.status.failed}' 2>/dev/null || true)
    if [ "${ok:-0}" -ge 1 ] 2>/dev/null; then k logs "job/$job" || true; return 0; fi
    if [ "${bad:-0}" -ge 1 ] 2>/dev/null; then break; fi
    sleep 3
  done
  k logs "job/$job" || true
  return 1
}
# 0. Preflight (F-4): choose the snapshot and check the data-loss warnings (SYNC.md 6.3 restore step 1)
#    BEFORE stopping anything, so a refusal leaves the server running and the backups scheduled.
#    restore.sh repeats the check once the server is down (the authoritative one).
echo "preflight: checking the chosen backup against the live database and the backups' metadata"
if ! run_restore_job preflight; then
  echo "RESTORE REFUSED (or preflight failed); nothing was stopped or changed. Job: $JOB" >&2
  echo "If the log above shows a data-loss warning and you accept it, re-run with --accept-data-loss." >&2
  exit 2
fi

live_pods() { k get pods -l "$1" --field-selector=status.phase!=Succeeded,status.phase!=Failed -o name; }
wait_no_pods() { # selector, what
  local deadline=$((SECONDS + 600))
  while [ -n "$(live_pods "$1")" ]; do
    [ $SECONDS -lt $deadline ] || { echo "ERROR: $2 pods still present after 10 min:" >&2; live_pods "$1" >&2; exit 1; }
    sleep 3
  done
}

# 1. Suspend the backup jobs (a backup of a half-restored database must never become "verified").
#    Remember which we suspended (annotation) so a re-run after a failure still resumes them.
CRONS="$REL-backup $REL-backup-verify"
for c in $CRONS; do
  if [ "$(k get cronjob "$c" -o jsonpath='{.spec.suspend}')" != true ]; then
    k annotate cronjob "$c" kotobaya/suspended-by-restore="$(date -u +%s)" --overwrite >/dev/null
    k patch cronjob "$c" -p '{"spec":{"suspend":true}}' >/dev/null
  elif [ -n "$(k get cronjob "$c" -o jsonpath='{.metadata.annotations.kotobaya/suspended-by-restore}')" ]; then
    # retry of an earlier run: refresh the timestamp so the watchdog does not see a stale suspension
    k annotate cronjob "$c" kotobaya/suspended-by-restore="$(date -u +%s)" --overwrite >/dev/null
  fi
done
echo "backup CronJobs suspended; waiting for running backup jobs to finish"
wait_no_pods "app.kubernetes.io/component in (backup,backup-verify)" "backup"

# Replica counts are kept in an annotation so a re-run after a failed restore (workloads at 0) restores them.
replicas() { # deployment
  local r; r=$(k get deploy "$1" -o jsonpath='{.metadata.annotations.kotobaya/replicas-before-restore}')
  if [ -z "$r" ]; then
    r=$(k get deploy "$1" -o jsonpath='{.spec.replicas}')
    k annotate deploy "$1" "kotobaya/replicas-before-restore=$r" --overwrite >/dev/null
  fi
  echo "$r"
}
API_REPLICAS=$(replicas "$REL-api")
WORKER_REPLICAS=$(replicas "$REL-worker")
echo "scaling api ($API_REPLICAS) and worker ($WORKER_REPLICAS) to 0"
k scale deploy "$REL-api" "$REL-worker" --replicas=0
wait_no_pods "app.kubernetes.io/component in (api,worker)" "api/worker"

rc=0; run_restore_job "" || rc=1
if [ $rc -ne 0 ]; then
  echo "RESTORE FAILED; api and worker left scaled to 0 and the backup CronJobs suspended. Job: $JOB" >&2
  echo "(the restore loads a temporary database first, so the live one is untouched unless the final rename ran)" >&2
  exit 1
fi

echo "scaling api and worker back up"
k scale deploy "$REL-api" --replicas="$API_REPLICAS"
k scale deploy "$REL-worker" --replicas="$WORKER_REPLICAS"
# readiness = /readyz: restore finalized, scheduler checked
if ! k rollout status deploy "$REL-api" --timeout=600s; then
  echo "ERROR: api not ready within 600s. The restore itself succeeded; the pre-restore database is kept and" >&2
  echo "the backup CronJobs stay suspended. Next steps: inspect 'kubectl -n $NS logs deploy/$REL-api', then either" >&2
  echo "re-run this script (safe; it restores again), or once the api is ready resume the CronJobs by hand:" >&2
  echo "  kubectl -n $NS patch cronjob $REL-backup -p '{\"spec\":{\"suspend\":false}}'   (same for $REL-backup-verify)" >&2
  echo "and remove their kotobaya/suspended-by-restore annotations; drop ${REL}_pre_restore_$RESTORE_STAMP by hand." >&2
  exit 1
fi
k annotate deploy "$REL-api" "$REL-worker" kotobaya/replicas-before-restore- >/dev/null
# The server has finalized: only now drop the pre-restore database (kept until here so a crash
# between the two renames, or a restore that must be undone, can be recovered).
run_restore_job cleanup || echo "WARNING: could not drop <db>_pre_restore_* databases; drop them by hand (README)" >&2
# Only now (restore finalized by the server) may backups run again. The watchdog CronJob is never suspended.
for c in $CRONS; do
  if [ -n "$(k get cronjob "$c" -o jsonpath='{.metadata.annotations.kotobaya/suspended-by-restore}')" ]; then
    k patch cronjob "$c" -p '{"spec":{"suspend":false}}' >/dev/null
    k annotate cronjob "$c" kotobaya/suspended-by-restore- >/dev/null
  fi
done
echo "restore complete; api is ready; backup CronJobs resumed"
