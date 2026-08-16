#!/usr/bin/env bash
set -Eeuo pipefail

readonly EXPECTED_IMPLEMENTATION=7dcf3fe6dddb36d79f04645e01a1a45f93061b5e
readonly RUN_ROOT=/opt/revoman-benchmark/runs/cs2a.Gp3djMyg
readonly GOVERNOR_STATE=/run/revoman-cs2a/governor-state.ZAprGaVE
readonly DIRECT_DIR=/home/gopala.akshintala/cs2a-direct-smoke.jg6BwhxE
readonly OPERATOR_SCRIPT=docs/superpowers/benchmarks/operators/cs2a-operator.sh

# shellcheck source=docs/superpowers/benchmarks/operators/cs2a-operator.sh
source "$OPERATOR_SCRIPT"

CS2A_IMPLEMENTATION_SHA=$(tr -d '\r\n' <"$IMPLEMENTATION_FILE")
readonly CS2A_IMPLEMENTATION_SHA
test "$CS2A_IMPLEMENTATION_SHA" = "$EXPECTED_IMPLEMENTATION"
require_sha "$CS2A_IMPLEMENTATION_SHA"

collect_direct_smoke() {
  local direct_result expected_result run_real parent attempt canonical marker stage directory
  local post_status profile

  clear_fresh_attempt_state

  # shellcheck disable=SC2029 # RUN_ROOT is a fixed, closed absolute path above.
  run_real=$(ssh "$REMOTE_HOST" "readlink -f -- '$RUN_ROOT'")
  test "$run_real" = "$RUN_ROOT"
  # shellcheck disable=SC2029 # DIRECT_DIR is a fixed, closed absolute path above.
  test "$(ssh "$REMOTE_HOST" \
    "stat -Lc '%U:%G:%a' '$DIRECT_DIR' '$DIRECT_DIR/supervisor.log' \
      '$DIRECT_DIR/supervisor-exit.txt' '$DIRECT_DIR/result.env'")" = \
    $'gopala.akshintala:gopala.akshintala:700\n'\
$'gopala.akshintala:gopala.akshintala:600\n'\
$'gopala.akshintala:gopala.akshintala:600\n'\
$'gopala.akshintala:gopala.akshintala:600'

  # shellcheck disable=SC2029 # DIRECT_DIR is a fixed, closed absolute path above.
  direct_result=$(ssh "$REMOTE_HOST" "cat '$DIRECT_DIR/result.env'")
  expected_result=$(printf '%s\n' \
    "CS2A_DIRECT_DIR=$DIRECT_DIR" \
    "RUN_ROOT=$RUN_ROOT" \
    "GOVERNOR_STATE=$GOVERNOR_STATE" \
    'CS2A_SMOKE_STATUS=0')
  test "$direct_result" = "$expected_result"
  # shellcheck disable=SC2029 # DIRECT_DIR is a fixed, closed absolute path above.
  test "$(ssh "$REMOTE_HOST" "tr -d '\\r\\n' <'$DIRECT_DIR/supervisor-exit.txt'")" = 0

  test ! -e "$PWD/build/cs2a-supervisor.log"
  test ! -L "$PWD/build/cs2a-supervisor.log"
  scp "$REMOTE_HOST:$DIRECT_DIR/supervisor.log" "$PWD/build/cs2a-supervisor.log"
  test -f "$PWD/build/cs2a-supervisor.log"
  test ! -L "$PWD/build/cs2a-supervisor.log"
  chmod 0600 "$PWD/build/cs2a-supervisor.log"
  test "$(extract_supervisor_marker "$PWD/build/cs2a-supervisor.log" RUN_ROOT)" = \
    "$RUN_ROOT"
  test "$(extract_supervisor_marker "$PWD/build/cs2a-supervisor.log" GOVERNOR_STATE)" = \
    "$GOVERNOR_STATE"
  printf '%s\n' 0 >"$PWD/build/cs2a-supervisor-exit.txt"
  chmod 0600 "$PWD/build/cs2a-supervisor-exit.txt"

  parent="$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA"
  mkdir -p "$parent"
  attempt=$(basename "$RUN_ROOT")
  canonical="$parent/$attempt"
  marker="$PWD/build/cs2a-local-evidence-dir.txt"
  test ! -e "$canonical"
  test ! -L "$canonical"
  test ! -e "$marker"
  test ! -L "$marker"

  stage=$(mktemp -d "$parent/.cs2a-archive-stage.XXXXXXXX")
  case "$stage" in "$parent"/.cs2a-archive-stage.*) ;; *) return 70 ;; esac
  mkdir "$stage/manifests" "$stage/results" "$stage/logs" "$stage/meta"
  for directory in manifests results logs meta; do
    rsync -a "$REMOTE_HOST:$RUN_ROOT/$directory/" "$stage/$directory/"
  done
  validate_archive_safety "$stage"

  test "$(tr -d '\r\n' <"$stage/meta/run-root.txt")" = "$RUN_ROOT"
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/run-root.txt")" = "$RUN_ROOT"
  test "$(tr -d '\r\n' <"$stage/meta/implementation-sha.txt")" = \
    "$CS2A_IMPLEMENTATION_SHA"
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/implementation-sha.txt")" = \
    "$CS2A_IMPLEMENTATION_SHA"
  test "$(tr -d '\r\n' <"$stage/meta/profile.txt")" = smoke
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/child-or-supervisor-status.txt")" = 0
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/restoration-failed.txt")" = false
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/containment-failed.txt")" = false
  test "$(tr -d '\r\n' <"$stage/meta/supervisor/lock-released.txt")" = true

  post_status=$(tr -d '\r\n' <"$stage/meta/supervisor/operator-post-supervisor-exit.txt")
  test "$post_status" = 0
  publish_local_authority_file "$PWD/build/cs2a-supervisor.log" \
    "$stage/meta/operator-supervisor.log"
  publish_local_authority_file "$PWD/build/cs2a-supervisor-exit.txt" \
    "$stage/meta/operator-supervisor-exit.txt"
  publish_local_authority_value "$stage/meta/operator-post-supervisor-exit.txt" \
    "$post_status"
  publish_local_authority_value "$stage/meta/operator-resume-validation-exit.txt" 0

  profile=$(authenticated_archive_profile "$stage" "$CS2A_IMPLEMENTATION_SHA")
  test "$profile" = smoke
  prepare_operator_source
  prepare_local_driver
  validate_smoke_archive "$stage" "$CS2A_IMPLEMENTATION_SHA" "$LOCAL_DRIVER"
  publish_local_authority_value "$stage/meta/local-validation-passed.txt" true
  publish_local_authority_value "$stage/meta/operator-final-exit.txt" 0
  publish_archive "$stage" "$canonical" "$marker" "$CS2A_IMPLEMENTATION_SHA"
  printf '%s\n' 0 >"$PWD/build/cs2a-operator-status.txt"
  chmod 0600 "$PWD/build/cs2a-operator-status.txt"
  printf 'LOCAL_EVIDENCE_DIR=%s\n' "$canonical"
}

acquire_local_operator_lock
set +e
(
  set -Eeuo pipefail
  collect_direct_smoke
)
collection_status=$?
set -e

if test "$collection_status" -eq 0; then
  release_local_operator_lock
else
  printf 'cs2a-direct-collector: collection ended with status %s; manual cleanup required: %s\n' \
    "$collection_status" "$LOCAL_OPERATOR_LOCK" >&2
fi
exit "$collection_status"
