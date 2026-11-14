#!/usr/bin/env bash
# Kotobaya endpoint smoke test. See README.md. Needs bash, curl, jq.
# Creates only throwaway objects named smoke-<UTC timestamp>; removes them on exit.
set -uo pipefail

BASE="${KOTOBAYA_URL:-https://kotobaya.batk.me}"
BASE="${BASE%/}"
API="$BASE/api/v1"
EXPECTED_VERSION="${SMOKE_VERSION:-0.1.11}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="smoke-$TS"
WORK="$(mktemp -d)"
CREATED="${SMOKE_CREATED_FILE:-$PWD/smoke-created-$TS.txt}"
JAR="$WORK/cookies"
FAILS=0

for tool in curl jq; do command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done

res() { # res PASS|FAIL|SKIP name reason
  printf '%-4s %-26s %s\n' "$1" "$2" "$3"
  if [ "$1" = FAIL ]; then FAILS=$((FAILS + 1)); fi
  return 0
}

uuid7() {
  local ms rnd
  ms="$(date +%s%3N)"
  rnd="$(od -An -N10 -tx1 /dev/urandom | tr -d ' \n')"
  printf '%012x-7%s-%x%s-%s\n' "$ms" "${rnd:0:3}" $((8 + 0x${rnd:3:1} % 4)) "${rnd:4:3}" "${rnd:7:12}" |
    sed -E 's/^([0-9a-f]{8})([0-9a-f]{4})-/\1-\2-/'
}

# api METHOD PATH [JSON_BODY] [curl args...] -> sets CODE and BODY (file path)
CODE=0
BODY="$WORK/body"
AUTH_ARGS=()
api() {
  local method="$1" path="$2" data="${3:-}"
  shift $(($# < 3 ? $# : 3))
  local args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "${AUTH_ARGS[@]}" "$@")
  if [ -n "$data" ]; then args+=(-H 'Content-Type: application/json' --data-binary "$data"); fi
  CODE="$(curl "${args[@]}" "$API$path" 2>"$WORK/curl.err")" || CODE=000
  [ -s "$BODY" ] || : >"$BODY"
}
getq() { # getq PATH key=value ... (URL-encoded query)
  local path="$1"
  shift
  local a=() kv
  for kv in "$@"; do a+=(--data-urlencode "$kv"); done
  api GET "$path" "" -G "${a[@]}"
}
j() { jq -r "$1" "$BODY" 2>/dev/null; }

# ---------------------------------------------------------------- auth
login() {
  if [ -n "${KOTOBAYA_TOKEN:-}" ]; then
    AUTH_ARGS=(-H "Authorization: Bearer $KOTOBAYA_TOKEN")
    return 0
  fi
  if [ -n "${KOTOBAYA_PASSWORD:-}" ]; then
    AUTH_ARGS=(-b "$JAR" -c "$JAR" -H 'X-Kotobaya-Csrf: 1')
    local body
    body="$(jq -nc --arg p "$KOTOBAYA_PASSWORD" --arg t "${KOTOBAYA_TOTP:-}" \
      '{password:$p} + (if $t != "" then {totp_code:$t} else {} end)')"
    api POST /auth/login "$body"
    [ "$CODE" = 200 ] && return 0
    echo "login failed: HTTP $CODE $(cat "$BODY")" >&2
    return 1
  fi
  echo "set KOTOBAYA_TOKEN (bearer) or KOTOBAYA_PASSWORD (session login)" >&2
  return 1
}

# ---------------------------------------------------------------- cleanup
record() { echo "$1 $2" >>"$CREATED"; }

cleanup_file() {
  local file="$1" kind id rc=0
  [ -f "$file" ] || { echo "no such file: $file" >&2; return 1; }
  # newest first: notes before the decks they sit in
  while read -r kind id; do
    [ -n "${id:-}" ] || continue
    case "$kind" in
      note) api DELETE "/notes/$id" ;;
      deck) api DELETE "/decks/$id" ;;
      media) api DELETE "/media/$id" ;;
      *) continue ;;
    esac
    case "$CODE" in
      204 | 404 | 410) echo "cleanup: $kind $id -> $CODE" ;;
      *)
        echo "cleanup FAILED: $kind $id -> $CODE $(cat "$BODY"); retry: $0 --cleanup $file" >&2
        rc=1
        ;;
    esac
  done < <(tac "$file")
  return $rc
}

CLEANUP_ON_EXIT=0
on_exit() {
  local rc=$?
  if [ "$CLEANUP_ON_EXIT" = 1 ] && [ -s "$CREATED" ]; then
    echo "--- cleanup"
    cleanup_file "$CREATED" || FAILS=$((FAILS + 1))
  fi
  rm -rf "$WORK"
  [ "$rc" -ne 0 ] && exit "$rc"
  [ "$FAILS" -gt 0 ] && exit 1
  exit 0
}

if [ "${1:-}" = "--cleanup" ]; then
  [ -n "${2:-}" ] || { echo "usage: $0 --cleanup <created-ids-file>" >&2; exit 2; }
  login || exit 2
  cleanup_file "$2"
  rc=$?
  rm -rf "$WORK"
  exit $rc
fi

CLEANUP_ON_EXIT=1
trap on_exit EXIT
trap 'exit 130' INT TERM

poll() { # poll SECONDS cmd... ; cmd returns 0 when done
  local limit="$1" i
  shift
  for ((i = 0; i < limit; i += 2)); do
    "$@" && return 0
    sleep 2
  done
  return 1
}

hira2kata() { jq -rn --arg s "$1" '$s|explode|map(if .>=12353 and .<=12438 then .+96 else . end)|implode'; }

echo "Kotobaya smoke $TS -> $BASE (expect version $EXPECTED_VERSION)"
echo "created-ids file: $CREATED"

# ---------------------------------------------------------------- health / version
api GET /health ""
if [ "$CODE" = 200 ]; then
  v="$(j .version)"
  if [ "${v#v}" = "${EXPECTED_VERSION#v}" ]; then res PASS health "status ok, version $v"; else res FAIL health "version $v, expected $EXPECTED_VERSION"; fi
else
  res FAIL health "HTTP $CODE"
fi

if ! login; then
  res FAIL auth "login failed"
  exit 1
fi

api GET /auth/session ""
if [ "$CODE" = 200 ]; then
  KIND="$(j .kind)"
  SCOPES="$(j '.scopes|join(",")')"
  DEVICE="$(j '.device_id // "none"')"
  res PASS auth/session "kind=$KIND scopes=$SCOPES device=$DEVICE"
else
  res FAIL auth/session "HTTP $CODE"
  exit 1
fi
has_scope() {
  case ",$SCOPES," in *",$1,"*) return 0 ;; esac
  return 1
}

api GET /meta ""
H0=0
if [ "$CODE" = 200 ]; then
  H0="$(j .high_water_seq)"
  mv="$(j .server_version)"
  if [ "${mv#v}" = "${EXPECTED_VERSION#v}" ]; then res PASS meta "server_version $mv, high_water_seq $H0"; else res FAIL meta "server_version $mv, expected $EXPECTED_VERSION"; fi
else
  res FAIL meta "HTTP $CODE"
fi

# ---------------------------------------------------------------- decks
api GET /decks ""
if [ "$CODE" = 200 ]; then res PASS decks/list "$(j '.items|length') decks"; else res FAIL decks/list "HTTP $CODE"; fi

DECK="$(uuid7)"
record deck "$DECK"
api POST /decks "$(jq -nc --arg id "$DECK" --arg n "$NAME" '{id:$id,type:"user",name:$n,new_cards_per_day:5}')"
if [ "$CODE" = 201 ]; then
  res PASS decks/create "user deck $NAME"
else
  res FAIL decks/create "HTTP $CODE $(head -c 200 "$BODY")"
  DECK=""
fi

# ---------------------------------------------------------------- JLPT lists
api GET /deck-lists ""
if [ "$CODE" = 200 ]; then
  CAT="$(cat "$BODY")"
  for n in 5 4 3 2 1; do
    c="$(jq -r --arg k "jlpt-n$n" '[.items[]|select(.list_key==$k)][0].item_count // 0' <<<"$CAT")"
    if [ "$c" -gt 0 ]; then res PASS "deck-lists/n$n" "item_count=$c"; else res FAIL "deck-lists/n$n" "missing or item_count=0"; fi
  done
  LKEY=""
  for n in 5 4 3 2 1; do
    d="$(jq -r --arg k "jlpt-n$n" '[.items[]|select(.list_key==$k)][0] | if . == null then "absent" else (.deck_id // "free") end' <<<"$CAT")"
    if [ "$d" = free ]; then
      LKEY="jlpt-n$n"
      break
    fi
  done
  if [ -z "$LKEY" ]; then
    res SKIP deck-lists/build "every JLPT list already has a live deck (creating would return yours)"
  else
    LD="$(uuid7)"
    record deck "$LD"
    api POST /decks "$(jq -nc --arg id "$LD" --arg k "$LKEY" --arg n "$NAME $LKEY" '{id:$id,type:"list",list_key:$k,name:$n,new_cards_per_day:1}')"
    if [ "$CODE" = 201 ] && [ "$(j .id)" = "$LD" ]; then
      filled() {
        api GET "/decks/$LD" ""
        [ "$CODE" = 200 ] && [ "$(j '.content_version')" -gt 0 ] 2>/dev/null
      }
      if poll 90 filled; then res PASS deck-lists/build "$LKEY filled: item_count=$(j .item_count)"; else res FAIL deck-lists/build "$LKEY not filled in 90s"; fi
    else
      res FAIL deck-lists/build "create $LKEY: HTTP $CODE $(head -c 200 "$BODY")"
    fi
  fi
else
  res FAIL deck-lists "HTTP $CODE"
fi

# ---------------------------------------------------------------- jpdb import (read-only unless SMOKE_JPDB_DECK)
getq /jobs kind=jpdb-history-import status=succeeded limit=1
if [ "$CODE" = 200 ]; then
  if [ "$(j '.items|length')" = 0 ]; then
    res SKIP import/jpdb-history "no succeeded jpdb-history-import job"
  else
    c="$(j '.items[0].result.vocabulary_carded // 0')"
    if [ "$c" -gt 0 ]; then
      res PASS import/jpdb-history "last job: vocabulary_carded=$c cards_created=$(j '.items[0].result.cards_created') reviews_imported=$(j '.items[0].result.reviews_imported') unmapped=$(j '.items[0].result.unmapped|length')"
    else
      res FAIL import/jpdb-history "last job result has no carded vocabulary"
    fi
  fi
else
  res FAIL import/jpdb-history "HTTP $CODE"
fi

if [ -n "${SMOKE_JPDB_DECK:-}" ]; then
  if ! has_scope import; then
    res SKIP import/jpdb-deck "token lacks import scope"
  else
    exists=0
    cursor=""
    while :; do
      if [ -n "$cursor" ]; then getq /media limit=200 "cursor=$cursor"; else getq /media limit=200; fi
      [ "$CODE" = 200 ] || break
      if jq -e --arg id "$SMOKE_JPDB_DECK" '[.items[]|select(.jpdb_id==$id)]|length>0' "$BODY" >/dev/null; then
        exists=1
        break
      fi
      cursor="$(j '.next_cursor // empty')"
      [ -n "$cursor" ] || break
    done
    if [ "$exists" = 1 ]; then
      res SKIP import/jpdb-deck "media with jpdb_id $SMOKE_JPDB_DECK exists; import would update your data"
    elif [ "$CODE" != 200 ]; then
      res FAIL import/jpdb-deck "media list HTTP $CODE"
    else
      api POST /media/import/jpdb "$(jq -nc --arg id "$SMOKE_JPDB_DECK" --arg t "$NAME" '{jpdb_id:$id,title:$t}')"
      if [ "$CODE" = 202 ] || [ "$CODE" = 200 ]; then
        JOB="$(j .id)"
        MID="$(j '.payload.media_id // empty')"
        [ -n "$MID" ] && record media "$MID"
        jdone() {
          api GET "/jobs/$JOB" ""
          case "$(j .status)" in succeeded | failed | cancelled) return 0 ;; esac
          return 1
        }
        if poll 180 jdone && [ "$(j .status)" = succeeded ]; then
          res PASS import/jpdb-deck "unique_entries=$(j '.result.unique_entries')"
        else
          res FAIL import/jpdb-deck "job status $(j .status)"
        fi
      else
        res FAIL import/jpdb-deck "HTTP $CODE $(head -c 200 "$BODY")"
      fi
    fi
  fi
else
  res SKIP import/jpdb-deck "SMOKE_JPDB_DECK not set"
fi

# ---------------------------------------------------------------- dictionary
getq /dict/search q=食べる limit=20
if [ "$CODE" = 200 ] && [ "$(j '.items|length')" -gt 0 ]; then
  SEQ="$(j '[.items[]|select(.headword=="食べる")][0].seq // .items[0].seq')"
  res PASS dict/search "食べる: $(j '.items|length') hits, first seq $SEQ"
  api GET "/dict/entries/$SEQ" ""
  if [ "$CODE" = 200 ] && [ "$(j .seq)" = "$SEQ" ]; then res PASS dict/entry "seq $SEQ"; else res FAIL dict/entry "HTTP $CODE"; fi
else
  res FAIL dict/search "食べる: HTTP $CODE or no hits"
fi

# ---------------------------------------------------------------- throwaway note (entry-linked)
# Use 食べる unless you already own a note for it; then the next free entry. Never touches your notes.
NOTE=""
ESEQ=""
HW=""
RD=""
CARDS=""
for w in 食べる 食べ物 食べ過ぎる 食べ歩き 食べ残す 食べ比べ; do
  getq /dict/search q="$w" limit=20
  [ "$CODE" = 200 ] || continue
  row="$(jq -c --arg w "$w" '[.items[]|select(.headword==$w and .note_id==null and (.retired|not))][0] // empty' "$BODY")"
  if [ -n "$row" ]; then
    ESEQ="$(jq -r .seq <<<"$row")"
    HW="$w"
    RD="$(jq -r .headword_reading <<<"$row")"
    break
  fi
done
api GET /note-types ""
NT="$(j '[.items[]|select(.base_type=="vocab" and .builtin)][0].id // empty')"
if [ -z "$DECK" ] || [ -z "$ESEQ" ] || [ -z "$NT" ]; then
  res SKIP note/create "no throwaway deck, free dictionary entry or vocab note type (deck=$DECK seq=$ESEQ nt=$NT)"
else
  NOTE="$(uuid7)"
  record note "$NOTE"
  api POST /notes "$(jq -nc --arg id "$NOTE" --arg nt "$NT" --argjson seq "$ESEQ" --arg d "$DECK" --arg n "$NAME" \
    '{id:$id,note_type_id:$nt,entry_seq:$seq,deck_id:$d,tags:["smoke"],source_sentence:$n,origin:"manual"}')"
  if [ "$CODE" = 201 ]; then
    CARDS="$(j '[.cards[].id]|join(" ")')"
    res PASS note/create "$HW (seq $ESEQ, reading $RD) with $(j '.cards|length') cards in $NAME"
  else
    res FAIL note/create "HTTP $CODE $(head -c 200 "$BODY")"
    NOTE=""
  fi
fi

if [ -n "$NOTE" ]; then
  api GET "/notes/$NOTE" ""
  if [ "$CODE" = 200 ] && [ "$(j .id)" = "$NOTE" ] && [ "$(j .entry_seq)" = "$ESEQ" ] && [ "$(j '.cards|length')" -gt 0 ]; then
    res PASS note/detail "entry_seq $ESEQ, $(j '.cards|length') cards"
  else
    res FAIL note/detail "HTTP $CODE or fields differ"
  fi

  # Browse > Cards: the same GET /cards?query= the SPA sends; "nid:" narrows to the throwaway note.
  KATA="$(hira2kata "$RD")"
  for term in "$HW" "$RD" "$KATA"; do
    getq /cards "query=$term nid:$NOTE" limit=50
    if [ "$CODE" = 200 ] && jq -e --arg n "$NOTE" '[.items[]|select(.note_id==$n)]|length>0' "$BODY" >/dev/null; then
      res PASS "browse/search $term" "found throwaway note"
    else
      res FAIL "browse/search $term" "HTTP $CODE, throwaway note not found"
    fi
  done
  getq /cards "query=nid:$NOTE" limit=50
  rows="$(j '[.items[].note_id]|unique|length')"
  ncards="$(j '.items|length')"
  if [ "$CODE" = 200 ] && [ "$rows" = 1 ] && [ "$ncards" -ge 1 ]; then
    res PASS browse/collapsed "$ncards cards -> 1 row (web groups cards by note_id)"
  else
    res FAIL browse/collapsed "HTTP $CODE, $ncards cards, $rows distinct notes"
  fi
fi

# ---------------------------------------------------------------- review: queue, grade, advance, pull
if [ -n "$NOTE" ]; then
  if ! has_scope review; then
    res SKIP review "token lacks review scope (needed by POST /review/answer)"
  else
    getq /review/queue deck_id="$DECK" limit=20
    CARD="$(jq -r --arg n "$NOTE" '[.items[]|select(.card.note_id==$n)][0].card.id // empty' "$BODY")"
    if [ "$CODE" = 200 ] && [ -n "$CARD" ]; then
      res PASS review/queue "throwaway card served ($(j '.items|length') items, new=$(j .counts.new))"
    elif [ "$CODE" = 200 ]; then
      res SKIP review/queue "throwaway card not in queue (daily new limit reached? new=$(j .counts.new)); grading card directly"
      CARD="${CARDS%% *}"
    else
      res FAIL review/queue "HTTP $CODE"
    fi
    if [ -n "${CARD:-}" ]; then
      RID="$(uuid7)"
      api POST /review/answer "$(jq -nc --arg id "$RID" --arg c "$CARD" '{id:$id,card_id:$c,rating:4,button:"easy",duration_ms:1000,utc_offset_min:0}')"
      if [ "$CODE" = 200 ] && [ "$(j .card.id)" = "$CARD" ] && [ "$(j .card.state)" != new ]; then
        res PASS review/answer "easy -> state $(j .card.state), due $(j .card.due_at)"
      else
        res FAIL review/answer "HTTP $CODE $(head -c 200 "$BODY")"
      fi
      getq /review/queue deck_id="$DECK" limit=20
      if [ "$CODE" = 200 ] && ! jq -e --arg c "$CARD" '[.items[].card.id]|index($c)' "$BODY" >/dev/null; then
        res PASS review/advance "graded card not served again (new=$(j .counts.new))"
      else
        res FAIL review/advance "HTTP $CODE or graded card served again"
      fi
      api GET "/cards/$CARD/reviews" ""
      if [ "$CODE" = 200 ] && jq -e --arg r "$RID" '[.items[].id]|index($r)' "$BODY" >/dev/null; then
        res PASS review/log "review $RID in review_log"
      else
        res FAIL review/log "HTTP $CODE, review not in history"
      fi
      # sync pull: the graded review and the note creation must be in the log after H0
      since="$H0"
      seen_review=0
      seen_note=0
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        getq /sync/pull since="$since" limit=1000
        [ "$CODE" = 200 ] || break
        if jq -e --arg r "$RID" 'any(.ops[]; .op_type=="review.logged" and .entity_id==$r)' "$BODY" >/dev/null; then seen_review=1; fi
        if jq -e --arg n "$NOTE" 'any(.ops[]; .op_type=="note.created" and .entity_id==$n)' "$BODY" >/dev/null; then seen_note=1; fi
        [ "$(j .has_more)" = true ] || break
        since="$(j .next_since)"
      done
      if [ "$CODE" = 200 ] && [ "$seen_review" = 1 ] && [ "$seen_note" = 1 ]; then
        res PASS sync/pull "review.logged and note.created present after seq $H0"
      else
        res FAIL sync/pull "HTTP $CODE review=$seen_review note=$seen_note"
      fi
    fi
  fi
else
  res SKIP review "no throwaway note"
fi

# ---------------------------------------------------------------- struggle lists
for p in /words/hot /words/leeches /kanji/hot; do
  getq "$p" limit=5
  if [ "$CODE" = 200 ]; then res PASS "struggle$p" "200"; else res FAIL "struggle$p" "HTTP $CODE"; fi
done

# ---------------------------------------------------------------- client errors
NOW="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
item() { jq -nc --arg m "$1" --arg t "$NOW" '{client:"web",app_version:"smoke",occurred_at:$t,level:"warn",kind:"exception",message:$m,route:"/smoke"}'; }
api POST /client-errors "$(jq -nc --argjson i "$(item "smoke-test $TS")" '{items:[$i]}')"
if [ "$CODE" = 202 ]; then res PASS client-errors "202 accepted=$(j .accepted)"; else res FAIL client-errors "HTTP $CODE $(head -c 200 "$BODY")"; fi
api POST /client-errors "$(jq -nc --argjson i "$(item "smoke-test $TS batch")" '{items:[range(21)|$i]}')"
if [ "$CODE" = 413 ]; then res PASS client-errors/413 "21 items rejected"; else res FAIL client-errors/413 "HTTP $CODE, expected 413"; fi

# ---------------------------------------------------------------- summary
echo "--- summary: $FAILS failure(s)"
echo "Find the synthetic client error in the api logs:"
echo "  <your log command> | grep 'kotobaya.client_error' | grep 'smoke-test $TS'"
echo "  e.g. kubectl -n kotobaya logs deploy/<api-deployment> --since=15m | grep 'smoke-test $TS'"
echo "Throwaway objects ($NAME) are deleted on exit; leftovers: $0 --cleanup $CREATED"
