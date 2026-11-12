#!/usr/bin/env bash
# Weekly backup-verify: restore the newest verified snapshot from the off-cluster repository into a
# scratch database (C.UTF-8) and compare it with the counts recorded at dump time
# (app.change max seq, review_log, note, card, dict_entry, blob counts, log_epoch, V1 migration
# checksum). Also checks every blob row has its file in the mirrored blobs.
# Does not touch the live database. Failure: notify NOTIFY_URL, exit non-zero (the Job fails).
# BACKUP_VERIFY_INJECT_FAILURE=1 corrupts the expected counts (failure-injection drill).
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
trap 'finish backup-verify' EXIT

: "${WORK_DIR:=/work}"
restic_need; db_env; wait_net
rm -rf "${WORK_DIR:?}"/* 2>/dev/null || true

SNAP=$(restic snapshots --tag verified --latest 1 --json | jq -r 'sort_by(.time) | last | .id // empty')
[ -n "$SNAP" ] || { FAIL_DETAIL="no verified snapshot in the repository. "; log "$FAIL_DETAIL"; exit 1; }
log "restoring snapshot $SNAP"
restic restore "$SNAP" --target "$WORK_DIR/r" >/dev/null
DUMP=$(find "$WORK_DIR/r" -name db.dump | head -n 1)
COUNTS=$(find "$WORK_DIR/r" -name counts.json | head -n 1)
BLOBS=$(dirname "$DUMP")/blobs
[ -s "$DUMP" ] && [ -s "$COUNTS" ] && [ -d "$BLOBS" ] || { FAIL_DETAIL="snapshot $SNAP lacks db.dump, counts.json or blobs/. "; exit 1; }

create_scratch
pg_restore --no-owner --exit-on-error -d "$SCRATCH_DB" "$DUMP"
compare_counts "$COUNTS" "$SCRATCH_DB"
check_blobs "$SCRATCH_DB" "$BLOBS"
log "backup-verify OK: snapshot $SNAP restored and all checks passed"
