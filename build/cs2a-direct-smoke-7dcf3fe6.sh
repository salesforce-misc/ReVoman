#!/usr/bin/env bash
set -Eeuo pipefail

readonly EXPECTED_IMPLEMENTATION=7dcf3fe6dddb36d79f04645e01a1a45f93061b5e
readonly EXPECTED_RUNNER_SHA256=40190484c826fe79f78b9648d04fe5b3615a8889b7d16dab3b0d9c1ae057090b
readonly EXPECTED_SUPERVISOR_SHA256=e7f71462a2e640339272da04ed2cb4e1d84e8c3f6ac02ea830879d053a64c58c
readonly INSTALL_ROOT=/opt/revoman-benchmark

umask 077

test "$(tr -d '\r\n' <"$INSTALL_ROOT/cs2a-implementation-sha")" = \
  "$EXPECTED_IMPLEMENTATION"
test "$(sha256sum "$INSTALL_ROOT/cs2a-controlled-run.sh" | awk '{print $1}')" = \
  "$EXPECTED_RUNNER_SHA256"
test "$(sha256sum "$INSTALL_ROOT/cs2a-governor-supervisor.sh" | awk '{print $1}')" = \
  "$EXPECTED_SUPERVISOR_SHA256"

direct_dir=$(mktemp -d "$HOME/cs2a-direct-smoke.XXXXXXXX")
readonly direct_dir
supervisor_log="$direct_dir/supervisor.log"
readonly supervisor_log
printf 'CS2A_DIRECT_DIR=%s\n' "$direct_dir"

set +e
dzdo "$INSTALL_ROOT/cs2a-governor-supervisor.sh" --run-profile smoke \
  2>&1 | tee "$supervisor_log"
supervisor_status=${PIPESTATUS[0]}
set -e
readonly supervisor_status
printf '%s\n' "$supervisor_status" >"$direct_dir/supervisor-exit.txt"

mapfile -t run_roots < <(sed -n 's/^RUN_ROOT=//p' "$supervisor_log")
mapfile -t governor_states < <(sed -n 's/^GOVERNOR_STATE=//p' "$supervisor_log")
test "${#run_roots[@]}" -eq 1
test "${#governor_states[@]}" -eq 1

run_root=${run_roots[0]}
governor_state=${governor_states[0]}
readonly run_root governor_state
[[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]]
[[ "$governor_state" =~ ^/run/revoman-cs2a/governor-state\.[A-Za-z0-9]+$ ]]

dzdo /bin/bash -s -- "$governor_state" "$supervisor_status" <<'CS2A_ROOT'
set -Eeuo pipefail
umask 077

governor_state=$1
status=$2
destination="$governor_state/operator-post-supervisor-exit.txt"
candidate=$(mktemp "$governor_state/.operator-post-supervisor-exit.XXXXXXXX")
trap 'rm -f -- "$candidate"' EXIT

printf '%s\n' "$status" >"$candidate"
chmod 0400 "$candidate"

if ! ln "$candidate" "$destination"; then
  test -f "$destination"
  test ! -L "$destination"
fi

test "$(stat -c '%u:%g:%a' "$destination")" = 0:0:400
test "$(tr -d '\r\n' <"$destination")" = "$status"
CS2A_ROOT

dzdo "$INSTALL_ROOT/cs2a-governor-supervisor.sh" \
  --publish-final-handoff "$run_root" "$governor_state"
dzdo "$INSTALL_ROOT/cs2a-governor-supervisor.sh" \
  --validate-final-handoff "$run_root" "$governor_state"

{
  printf 'CS2A_DIRECT_DIR=%s\n' "$direct_dir"
  printf 'RUN_ROOT=%s\n' "$run_root"
  printf 'GOVERNOR_STATE=%s\n' "$governor_state"
  printf 'CS2A_SMOKE_STATUS=%s\n' "$supervisor_status"
} | tee "$direct_dir/result.env"

exit "$supervisor_status"
