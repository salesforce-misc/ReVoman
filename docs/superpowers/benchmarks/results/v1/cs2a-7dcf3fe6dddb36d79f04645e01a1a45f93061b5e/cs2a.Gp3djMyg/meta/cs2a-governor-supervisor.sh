#!/usr/bin/env bash
# Newly authored CS2a root governor supervisor. No Task 13 source survives in repository history;
# this security-critical implementation requires independent fixed-range review before remote use.
set -Eeuo pipefail

readonly LOCK_FILE=/opt/revoman-benchmark/task13.lock
readonly IMPLEMENTATION_FILE=/opt/revoman-benchmark/cs2a-implementation-sha
readonly HANDOFF_FILE=/opt/revoman-benchmark/cs2a-operator-handoff.tsv
readonly CONTROLLED_UID_FILE=/opt/revoman-benchmark/controlled-uid
readonly RUNNER_FILE=/opt/revoman-benchmark/cs2a-controlled-run.sh
readonly POLICY_FILE=/opt/revoman-benchmark/controlled-host.json
readonly STATE_PARENT=/run/revoman-cs2a
readonly PROC_FD_ROOT=/proc/self/fd
readonly PROCESS_STAT_ROOT=/proc
readonly TRUSTED_CHILD_PATH=/usr/bin:/bin
readonly CONTROLLED_USER=gopala.akshintala
readonly RUN_TIMEOUT_SECONDS=43200
readonly RUN_KILL_AFTER_SECONDS=30
readonly POLICY_SHA256=7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79
readonly CONTROLLED_UID_POLICY_SHA256=abc4307b6eb40577163790a0c453ece3ff4bff8620c85471a35a1bd3a1aea44b

STATE=
CHILD_PID=
CHILD_PGID=
CHILD_GROUP_READY=false
CHILD_READY_FILE=
CHILD_RELEASE_FILE=
CHILD_STATUS_FILE=
CHILD_TERMINATION_ACTIVE=false
CHILD_LAUNCH_CRITICAL=false
SIGNAL_HANDLER_ACTIVE=false
CHILD_STATUS=70
RESTORATION_FAILED=false
CONTAINMENT_FAILED=false
SIGNAL_STATUS=
CLEANUP_COMPLETE=false
AUTHENTICATED_RUN_ROOT=
CONTROLLED_UID=
CONTROLLED_GID=
CONTROLLED_PROFILE=
LOCK_RELEASE_CANDIDATE=
readonly -a CORE_STATE_HANDOFF_FILES=(
  child-or-supervisor-status.txt
  restoration-failed.txt
  containment-failed.txt
  finished-at.txt
  original-governors.tsv
  restored-governors.tsv
  executed-script-sha256sums.tsv
  authenticated-handoff.tsv
  run-root.txt
  implementation-sha.txt
  lock-provenance.txt
)
readonly -a FINAL_STATE_HANDOFF_FILES=(
  "${CORE_STATE_HANDOFF_FILES[@]}"
  operator-post-supervisor-exit.txt
  lock-released.txt
)

fail() {
  printf 'cs2a-governor-supervisor: %s\n' "$*" >&2
  exit 70
}

handoff_value() {
  local key=$1
  awk -F '\t' -v key="$key" '$1 == key { count++; value=$2 } END {
    if (count != 1) exit 1
    print value
  }' "$HANDOFF_FILE"
}

require_root_file() {
  local path=$1 mode=$2
  test -f "$path" || fail "missing root-owned file: $path"
  test ! -L "$path" || fail "root-owned file must not be a symlink: $path"
  test "$(stat -c '%u:%g:%a' "$path")" = "0:0:$mode" \
    || fail "wrong root ownership or mode: $path"
}

controlled_uid_policy_is_provisioned() {
  [[ "$CONTROLLED_UID_POLICY_SHA256" =~ ^[0-9a-f]{64}$ ]]
}

prepare_lock_file() {
  if test -e "$LOCK_FILE"; then
    require_root_file "$LOCK_FILE" 600
  else
    install -o root -g root -m 0600 /dev/null "$LOCK_FILE" \
      || fail "cannot create root-owned benchmark lock"
  fi
}

validate_handoff() {
  local implementation runner supervisor controlled_uid controlled_uid_policy_sha profile
  controlled_uid_policy_is_provisioned \
    || fail "controlled UID policy hash is unprovisioned; administrator review is required"
  require_root_file "$IMPLEMENTATION_FILE" 444
  require_root_file "$HANDOFF_FILE" 400
  require_root_file "$CONTROLLED_UID_FILE" 444
  require_root_file "$RUNNER_FILE" 555
  require_root_file "$0" 555
  require_root_file "$POLICY_FILE" 444
  test "$(sha256sum "$POLICY_FILE" | cut -d' ' -f1)" = "$POLICY_SHA256" \
    || fail "controlled-host policy provenance mismatch"
  if ! awk -F '\t' '
    NF != 2 { exit 1 }
    $1 != "implementation" && $1 != "runner" && $1 != "supervisor" &&
      $1 != "uid" && $1 != "profile" { exit 1 }
    { count[$1]++; total++ }
    END {
      exit !(total == 5 && count["implementation"] == 1 && count["uid"] == 1 &&
        count["runner"] == 1 && count["supervisor"] == 1 && count["profile"] == 1)
    }
  ' "$HANDOFF_FILE"; then
    fail "invalid root-owned operator handoff"
  fi
  implementation=$(tr -d '\r\n' <"$IMPLEMENTATION_FILE")
  controlled_uid=$(tr -d '\r\n' <"$CONTROLLED_UID_FILE")
  controlled_uid_policy_sha=$(sha256sum "$CONTROLLED_UID_FILE" | cut -d' ' -f1)
  runner=$(sha256sum "$RUNNER_FILE" | cut -d' ' -f1)
  supervisor=$(sha256sum "$0" | cut -d' ' -f1)
  profile=$(handoff_value profile)
  [[ "$implementation" =~ ^[0-9a-f]{40}$ ]] || fail "invalid implementation identity"
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled UID policy"
  test "$controlled_uid_policy_sha" = "$CONTROLLED_UID_POLICY_SHA256" \
    || fail "controlled UID policy hash is not the reviewed implementation anchor"
  [[ "$runner" =~ ^[0-9a-f]{64}$ ]] || fail "invalid runner identity"
  [[ "$supervisor" =~ ^[0-9a-f]{64}$ ]] || fail "invalid supervisor identity"
  case "$profile" in full | smoke) ;; *) fail "invalid benchmark profile" ;; esac
  test "$implementation" = "$(handoff_value implementation)" \
    || fail "implementation handoff mismatch"
  test "$controlled_uid" = "$(handoff_value uid)" || fail "controlled UID handoff mismatch"
  test "$runner" = "$(handoff_value runner)" || fail "runner handoff mismatch"
  test "$supervisor" = "$(handoff_value supervisor)" || fail "supervisor handoff mismatch"
  test "$(id -u "$CONTROLLED_USER")" = "$controlled_uid" \
    || fail "controlled user UID mismatch"
  CONTROLLED_UID=$controlled_uid
  CONTROLLED_GID=$(id -g "$CONTROLLED_USER")
  [[ "$CONTROLLED_GID" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled group identity"
  CONTROLLED_PROFILE=$profile
}

governor_path_allowed() {
  local path=$1 prefix=${2:-/sys/devices/system/cpu}
  case "$path" in
    "$prefix"/cpu[0-9]*/cpufreq/scaling_governor) return 0 ;;
    *) return 1 ;;
  esac
}

capture_governors() {
  local output=$1 path value count=0
  : >"$output"
  for path in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
    test -f "$path" || continue
    value=$(cat "$path") || return 1
    case "$value" in performance | powersave | ondemand | conservative | schedutil) ;; *) return 1 ;; esac
    printf '%s\t%s\n' "$path" "$value" >>"$output"
    count=$((count + 1))
  done
  test "$count" -gt 0
}

set_performance_governors() {
  local inventory=$1 path original extra
  while IFS="$(printf '\t')" read -r path original extra; do
    test -z "$extra" || return 1
    governor_path_allowed "$path" || return 1
    printf '%s\n' performance >"$path" || return 1
    test "$(cat "$path")" = performance || return 1
  done <"$inventory"
}

restore_governors() {
  local inventory=$1 prefix=${2:-/sys/devices/system/cpu} path original extra failed=false
  test -f "$inventory" || return 1
  while IFS="$(printf '\t')" read -r path original extra; do
    test -n "$path" || { failed=true; continue; }
    test -z "$extra" || { failed=true; continue; }
    governor_path_allowed "$path" "$prefix" || { failed=true; continue; }
    case "$original" in performance | powersave | ondemand | conservative | schedutil) ;;
      *) failed=true; continue ;;
    esac
    if ! printf '%s\n' "$original" >"$path" || test "$(cat "$path")" != "$original"; then
      failed=true
    fi
  done <"$inventory"
  test "$failed" = false
}

capture_restored_governors() {
  local inventory=$1 output=$2 path original extra current
  : >"$output"
  while IFS="$(printf '\t')" read -r path original extra; do
    test -n "$path" && test -z "$extra" || return 1
    governor_path_allowed "$path" || return 1
    current=$(cat "$path") || return 1
    printf '%s\t%s\n' "$path" "$current" >>"$output"
  done <"$inventory"
}

validate_state_sources() {
  local files_name=$1 required source
  local -a files
  case "$files_name" in
    core) files=("${CORE_STATE_HANDOFF_FILES[@]}") ;;
    final) files=("${FINAL_STATE_HANDOFF_FILES[@]}") ;;
    *) return 1 ;;
  esac
  test -d "$STATE" && test ! -L "$STATE" || return 1
  for required in "${files[@]}"; do
    source="$STATE/$required"
    test -f "$source" && test ! -L "$source" || return 1
    test "$(stat -c '%u:%g:%a' "$source")" = 0:0:400 || return 1
  done
}

validate_state_destination() {
  local destination=$1 files_name=$2 required source target actual_count
  local -a files
  case "$files_name" in
    core) files=("${CORE_STATE_HANDOFF_FILES[@]}") ;;
    final) files=("${FINAL_STATE_HANDOFF_FILES[@]}") ;;
    *) return 1 ;;
  esac
  test -d "$destination" && test ! -L "$destination" || return 1
  test "$(stat -c '%u:%g:%a' "$destination")" = \
    "$CONTROLLED_UID:$CONTROLLED_GID:700" || return 1
  actual_count=$(find "$destination" -mindepth 1 -maxdepth 1 -print | wc -l \
    | tr -d ' ') || return 1
  test "$actual_count" -eq "${#files[@]}" || return 1
  for required in "${files[@]}"; do
    source="$STATE/$required"
    target="$destination/$required"
    test -f "$target" && test ! -L "$target" || return 1
    test "$(stat -c '%u:%g:%a' "$target")" = \
      "$CONTROLLED_UID:$CONTROLLED_GID:400" || return 1
    cmp -s "$source" "$target" || return 1
  done
}

cleanup_final_state_staging() {
  local staging=$1 files_name=$2 required target actual_count
  local -a files
  case "$files_name" in
    core) files=("${CORE_STATE_HANDOFF_FILES[@]}") ;;
    final) files=("${FINAL_STATE_HANDOFF_FILES[@]}") ;;
    *) return 1 ;;
  esac
  test -d "$staging" && test ! -L "$staging" || return 1
  for required in "${files[@]}"; do
    target="$staging/$required"
    if test -e "$target" || test -L "$target"; then
      test -f "$target" && test ! -L "$target" || return 1
      rm -f -- "$target" || return 1
    fi
  done
  actual_count=$(find "$staging" -mindepth 1 -maxdepth 1 -print | wc -l \
    | tr -d ' ') || return 1
  test "$actual_count" -eq 0 || return 1
  rmdir -- "$staging"
}

publish_final_state_directory() {
  local destination=$1 files_name=$2 parent staging required source target
  local -a files
  case "$files_name" in
    core) files=("${CORE_STATE_HANDOFF_FILES[@]}") ;;
    final) files=("${FINAL_STATE_HANDOFF_FILES[@]}") ;;
    *) return 1 ;;
  esac
  parent=$(dirname -- "$destination") || return 1
  validate_state_sources "$files_name" || return 1
  if test -e "$destination" || test -L "$destination"; then
    validate_state_destination "$destination" "$files_name"
    return
  fi

  staging=$(mktemp -d "$parent/.supervisor-stage.XXXXXXXX") || return 1
  test "$(dirname -- "$staging")" = "$parent" \
    && [[ "$(basename -- "$staging")" =~ ^\.supervisor-stage\.[A-Za-z0-9]{8}$ ]] \
    && test -d "$staging" && test ! -L "$staging" || return 1
  chmod 0700 "$staging" || {
    cleanup_final_state_staging "$staging" "$files_name" || true
    return 1
  }
  for required in "${files[@]}"; do
    source="$STATE/$required"
    target="$staging/$required"
    if ! install -o "$CONTROLLED_UID" -g "$CONTROLLED_GID" -m 0400 \
      "$source" "$target"; then
      cleanup_final_state_staging "$staging" "$files_name" || true
      return 1
    fi
  done
  if ! chown "$CONTROLLED_UID:$CONTROLLED_GID" "$staging" \
    || ! validate_state_destination "$staging" "$files_name"; then
    cleanup_final_state_staging "$staging" "$files_name" || true
    return 1
  fi
  if ! mv -Tn -- "$staging" "$destination"; then
    cleanup_final_state_staging "$staging" "$files_name" || true
    return 1
  fi
  if test -e "$staging" || test -L "$staging"; then
    cleanup_final_state_staging "$staging" "$files_name" || return 1
  fi
  validate_state_destination "$destination" "$files_name"
}

copy_final_state_to_run_root() {
  local run_root=$1 destination
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  test "$(readlink -f -- "$run_root")" = "$run_root" || return 1
  test -d "$run_root/meta" && test ! -L "$run_root/meta" || return 1
  test "$(stat -c '%u' "$run_root")" = "$CONTROLLED_UID" || return 1
  destination="$run_root/meta/supervisor-core"
  publish_final_state_directory "$destination" core
}

cleanup_lock_release_candidate() {
  test -n "$LOCK_RELEASE_CANDIDATE" || return 0
  case "$LOCK_RELEASE_CANDIDATE" in
    "$STATE"/.lock-released.*) rm -f -- "$LOCK_RELEASE_CANDIDATE" ;;
    *) return 1 ;;
  esac
  LOCK_RELEASE_CANDIDATE=
}

write_lock_released_evidence() {
  local destination
  destination="$STATE/lock-released.txt"
  if test -e "$destination" || test -L "$destination"; then
    require_root_file "$destination" 400
    test "$(tr -d '\r\n' <"$destination")" = true
    return
  fi
  LOCK_RELEASE_CANDIDATE=$(mktemp "$STATE/.lock-released.XXXXXXXX") || return 1
  case "$LOCK_RELEASE_CANDIDATE" in "$STATE"/.lock-released.*) ;; *) return 1 ;; esac
  if ! printf '%s\n' true >"$LOCK_RELEASE_CANDIDATE" \
    || ! chmod 0400 "$LOCK_RELEASE_CANDIDATE" \
    || ! chown root:root "$LOCK_RELEASE_CANDIDATE" \
    || ! require_root_file "$LOCK_RELEASE_CANDIDATE" 400; then
    cleanup_lock_release_candidate || true
    return 1
  fi
  if ! ln "$LOCK_RELEASE_CANDIDATE" "$destination"; then
    cleanup_lock_release_candidate || return 1
    require_root_file "$destination" 400
    test "$(tr -d '\r\n' <"$destination")" = true
    return
  fi
  cleanup_lock_release_candidate || return 1
  require_root_file "$destination" 400
  test "$(tr -d '\r\n' <"$destination")" = true
}

authenticate_released_lock() {
  local recorded_provenance lock_provenance descriptor_provenance
  require_root_file "$LOCK_FILE" 600
  require_root_file "$STATE/lock-provenance.txt" 400
  recorded_provenance=$(tr -d '\r\n' <"$STATE/lock-provenance.txt") || return 1
  [[ "$recorded_provenance" =~ ^0:0:600:[0-9]+:[0-9]+$ ]] || return 1
  lock_provenance=$(stat -Lc '%u:%g:%a:%d:%i' "$LOCK_FILE") || return 1
  test "$lock_provenance" = "$recorded_provenance" || return 1
  exec 8<>"$LOCK_FILE" || return 1
  descriptor_provenance=$(stat -Lc '%u:%g:%a:%d:%i' /proc/$$/fd/8) || return 1
  test "$descriptor_provenance" = "$recorded_provenance" || return 1
  flock -n 8 || return 1
  write_lock_released_evidence
}

validate_exact_authenticated_handoff() {
  local implementation=$1 controlled_uid=$2 runner_sha=$3 supervisor_sha=$4 profile=$5
  case "$profile" in full | smoke) ;; *) return 1 ;; esac
  awk -F '\t' \
    -v implementation="$implementation" \
    -v controlled_uid="$controlled_uid" \
    -v runner_sha="$runner_sha" \
    -v supervisor_sha="$supervisor_sha" \
    -v profile="$profile" '
    NF != 2 { exit 1 }
    $1 == "implementation" && $2 == implementation { implementation_count++; next }
    $1 == "uid" && $2 == controlled_uid { uid_count++; next }
    $1 == "runner" && $2 == runner_sha { runner_count++; next }
    $1 == "supervisor" && $2 == supervisor_sha { supervisor_count++; next }
    $1 == "profile" && $2 == profile { profile_count++; next }
    { exit 1 }
    END {
      exit !(NR == 5 && implementation_count == 1 && uid_count == 1 &&
        runner_count == 1 && supervisor_count == 1 && profile_count == 1)
    }
  ' "$STATE/authenticated-handoff.tsv"
}

validate_exact_executed_scripts() {
  local runner_sha=$1 supervisor_sha=$2
  awk -F '\t' -v runner_sha="$runner_sha" -v supervisor_sha="$supervisor_sha" '
    NF != 2 { exit 1 }
    $1 == "runner" && $2 == runner_sha { runner_count++; next }
    $1 == "supervisor" && $2 == supervisor_sha { supervisor_count++; next }
    { exit 1 }
    END { exit !(NR == 2 && runner_count == 1 && supervisor_count == 1) }
  ' "$STATE/executed-script-sha256sums.tsv"
}

validate_final_handoff_evidence() {
  local run_root=$1 implementation controlled_uid runner_sha supervisor_sha profile
  local recorded_run recorded_implementation recorded_provenance lock_provenance
  local restoration containment child_status post_status finished_at
  require_root_file "$IMPLEMENTATION_FILE" 444
  require_root_file "$CONTROLLED_UID_FILE" 444
  require_root_file "$RUNNER_FILE" 555
  require_root_file "$0" 555
  test "$(stat -c '%u:%g:%a' "$STATE")" = 0:0:700 || return 1
  validate_state_sources final || return 1

  implementation=$(tr -d '\r\n' <"$IMPLEMENTATION_FILE") || return 1
  controlled_uid=$(tr -d '\r\n' <"$CONTROLLED_UID_FILE") || return 1
  runner_sha=$(sha256sum "$RUNNER_FILE" | cut -d' ' -f1) || return 1
  supervisor_sha=$(sha256sum "$0" | cut -d' ' -f1) || return 1
  profile=$(awk -F '\t' '$1 == "profile" { count++; value=$2 } END {
    if (count != 1) exit 1
    print value
  }' "$STATE/authenticated-handoff.tsv") || return 1
  [[ "$implementation" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$runner_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ "$supervisor_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  test "$controlled_uid" = "$CONTROLLED_UID" || return 1

  recorded_run=$(tr -d '\r\n' <"$STATE/run-root.txt") || return 1
  recorded_implementation=$(tr -d '\r\n' <"$STATE/implementation-sha.txt") || return 1
  test "$recorded_run" = "$run_root" || return 1
  test "$recorded_implementation" = "$implementation" || return 1
  validate_exact_authenticated_handoff \
    "$implementation" "$controlled_uid" "$runner_sha" "$supervisor_sha" "$profile" || return 1
  validate_exact_executed_scripts "$runner_sha" "$supervisor_sha" || return 1

  recorded_provenance=$(tr -d '\r\n' <"$STATE/lock-provenance.txt") || return 1
  [[ "$recorded_provenance" =~ ^0:0:600:[0-9]+:[0-9]+$ ]] || return 1
  lock_provenance=$(stat -Lc '%u:%g:%a:%d:%i' "$LOCK_FILE") || return 1
  test "$lock_provenance" = "$recorded_provenance" || return 1
  test "$(tr -d '\r\n' <"$STATE/lock-released.txt")" = true || return 1

  restoration=$(tr -d '\r\n' <"$STATE/restoration-failed.txt") || return 1
  containment=$(tr -d '\r\n' <"$STATE/containment-failed.txt") || return 1
  child_status=$(tr -d '\r\n' <"$STATE/child-or-supervisor-status.txt") || return 1
  post_status=$(tr -d '\r\n' <"$STATE/operator-post-supervisor-exit.txt") || return 1
  finished_at=$(tr -d '\r\n' <"$STATE/finished-at.txt") || return 1
  test "$restoration" = false && test "$containment" = false || return 1
  [[ "$child_status" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  test "$post_status" = "$child_status" || return 1
  [[ "$finished_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([+-][0-9]{2}:[0-9]{2}|Z)$ ]] \
    || return 1
  cmp -s "$STATE/original-governors.tsv" "$STATE/restored-governors.tsv" || return 1
}

prepare_final_handoff_context() {
  local run_root=$1 governor_state=$2
  test "$(id -u)" -eq 0 || fail "must execute final handoff mode as root"
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] \
    || fail "invalid final handoff run root"
  [[ "$governor_state" =~ ^/run/revoman-cs2a/governor-state\.[A-Za-z0-9]+$ ]] \
    || fail "invalid final handoff governor state"
  test "$(readlink -f -- "$run_root")" = "$run_root" \
    || fail "final handoff run root is not canonical"
  test "$(readlink -f -- "$governor_state")" = "$governor_state" \
    || fail "final handoff governor state is not canonical"
  test -d "$run_root/meta" || fail "invalid final handoff metadata directory"
  test ! -L "$run_root/meta" || fail "invalid final handoff metadata directory"
  test -d "$governor_state" || fail "invalid final handoff state directory"
  test ! -L "$governor_state" || fail "invalid final handoff state directory"
  CONTROLLED_UID=$(cat "$CONTROLLED_UID_FILE") || fail "cannot read controlled UID"
  [[ "$CONTROLLED_UID" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled UID"
  CONTROLLED_GID=$(id -g "$CONTROLLED_USER") || fail "cannot read controlled GID"
  [[ "$CONTROLLED_GID" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled GID"
  test "$(stat -c '%u' "$run_root")" = "$CONTROLLED_UID" \
    || fail "final handoff run-root owner mismatch"
  STATE=$governor_state
}

validate_final_handoff_main() {
  local run_root=$1 governor_state=$2 destination
  prepare_final_handoff_context "$run_root" "$governor_state"
  validate_final_handoff_evidence "$run_root" \
    || fail "final supervisor evidence authentication failed"
  destination="$run_root/meta/supervisor"
  validate_state_destination "$destination" final \
    || fail "final supervisor handoff authentication failed"
}

publish_final_handoff_main() {
  local run_root=$1 governor_state=$2 destination
  prepare_final_handoff_context "$run_root" "$governor_state"
  trap cleanup_lock_release_candidate EXIT
  trap 'cleanup_lock_release_candidate || true; exit 70' INT TERM HUP
  authenticate_released_lock || fail "benchmark lock release is not authenticated"
  validate_final_handoff_evidence "$run_root" \
    || fail "final supervisor evidence authentication failed"
  destination="$run_root/meta/supervisor"
  publish_final_state_directory "$destination" final \
    || fail "cannot atomically publish final supervisor handoff"
  exec 8>&-
  cleanup_lock_release_candidate || fail "cannot clean lock-release candidate"
  trap - EXIT INT TERM HUP
}

recover_stale_state() {
  local stale=$1 owner_mode status restoration containment
  test -d "$stale" || return 0
  owner_mode=$(stat -c '%u:%g:%a' "$stale") || return 1
  test "$owner_mode" = 0:0:700 || return 1
  if test -e "$stale/finished-at.txt"; then
    test -f "$stale/child-or-supervisor-status.txt" \
      && test -f "$stale/restoration-failed.txt" \
      && test -f "$stale/containment-failed.txt" \
      && test -f "$stale/original-governors.tsv" \
      && test -f "$stale/restored-governors.tsv" || return 1
    status=$(tr -d '\r\n' <"$stale/child-or-supervisor-status.txt") || return 1
    restoration=$(tr -d '\r\n' <"$stale/restoration-failed.txt") || return 1
    containment=$(tr -d '\r\n' <"$stale/containment-failed.txt") || return 1
    [[ "$status" =~ ^[0-9]+$ ]] || return 1
    if test "$restoration" = false && test "$containment" = false \
      && cmp -s "$stale/original-governors.tsv" "$stale/restored-governors.tsv"; then
      return 0
    fi
    test "$containment" = false || return 1
  fi
  if test -f "$stale/original-governors.tsv"; then
    restore_governors "$stale/original-governors.tsv" || return 1
    capture_restored_governors "$stale/original-governors.tsv" \
      "$stale/restored-governors.tsv" || return 1
    cmp -s "$stale/original-governors.tsv" "$stale/restored-governors.tsv" || return 1
  else
    test ! -e "$stale/original-governors.tsv" \
      && test ! -L "$stale/original-governors.tsv" \
      && test ! -e "$stale/restored-governors.tsv" \
      && test ! -L "$stale/restored-governors.tsv" || return 1
    : >"$stale/original-governors.tsv" || return 1
    : >"$stale/restored-governors.tsv" || return 1
  fi
  printf '%s\n' true >"$stale/stale-recovered.txt" || return 1
  printf '%s\n' 70 >"$stale/child-or-supervisor-status.txt" || return 1
  printf '%s\n' false >"$stale/restoration-failed.txt" || return 1
  printf '%s\n' false >"$stale/containment-failed.txt" || return 1
  date -Iseconds >"$stale/finished-at.txt" || return 1
  chmod 0400 "$stale/stale-recovered.txt" \
    "$stale/child-or-supervisor-status.txt" \
    "$stale/restoration-failed.txt" "$stale/containment-failed.txt" \
    "$stale/original-governors.tsv" "$stale/restored-governors.tsv" \
    "$stale/finished-at.txt" || return 1
}

recover_stale_states() {
  local stale
  for stale in "$STATE_PARENT"/governor-state.*; do
    test -e "$stale" || continue
    recover_stale_state "$stale" || fail "stale governor recovery failed: $stale"
  done
}

clear_child_pid_identity() {
  local pid=$1
  test "$CHILD_PID" = "$pid" || return 0
  CHILD_PID=
}

clear_child_group_identity() {
  local pgid=$1
  test "$CHILD_PGID" = "$pgid" || return 0
  CHILD_PGID=
  CHILD_GROUP_READY=false
}

child_pid_is_owned() {
  local pid=$1 job_pid
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  while IFS= read -r job_pid; do
    test "$job_pid" = "$pid" && return 0
  done < <(jobs -p)
  return 1
}

process_identity() {
  local pid=$1 stat rest state pgrp start_time
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  stat=$(cat "$PROCESS_STAT_ROOT/$pid/stat") || return 1
  rest=${stat##*) }
  test "$rest" != "$stat" || return 1
  state=${rest%% *}
  case "$state" in Z | X | x) return 1 ;; esac
  rest=${rest#* }
  pgrp=$(printf '%s\n' "$rest" | awk '{ print $2 }') || return 1
  start_time=$(printf '%s\n' "$rest" | awk '{ print $19 }') || return 1
  [[ "$pgrp" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$start_time" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s:%s:%s\n' "$pid" "$pgrp" "$start_time"
}

stop_owned_child_anchor() {
  local pid=$1 state remaining=30
  child_pid_is_owned "$pid" || return 1
  kill -STOP "$pid" 2>/dev/null || return 1
  while test "$remaining" -gt 0; do
    child_pid_is_owned "$pid" || return 1
    state=$(ps -o stat= -p "$pid" 2>/dev/null) || return 1
    state=${state//[[:space:]]/}
    case "$state" in
      T* | t*) return 0 ;;
    esac
    sleep 0.1
    remaining=$((remaining - 1))
  done
  return 1
}

wait_for_controlled_child() {
  local pid=$CHILD_PID pgid=$CHILD_PGID status remaining
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return 1
  test "$CHILD_GROUP_READY" = true || return 1
  test -n "$CHILD_STATUS_FILE" || return 1
  remaining=$((RUN_TIMEOUT_SECONDS + RUN_KILL_AFTER_SECONDS + 30))
  while ! test -s "$CHILD_STATUS_FILE"; do
    if ! child_pid_is_owned "$pid"; then
      wait "$pid" 2>/dev/null || :
      clear_child_pid_identity "$pid"
      if kill -0 -- "-$pgid" 2>/dev/null; then
        CONTAINMENT_FAILED=true
      else
        clear_child_group_identity "$pgid"
      fi
      return 1
    fi
    test "$remaining" -gt 0 || {
      CONTAINMENT_FAILED=true
      return 1
    }
    sleep 1
    remaining=$((remaining - 1))
  done
  status=$(tr -d '\r\n' <"$CHILD_STATUS_FILE") || return 1
  [[ "$status" =~ ^([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$ ]] || return 1
  terminate_controlled_child_group || CONTAINMENT_FAILED=true
  return "$status"
}

terminate_unready_child() {
  local pid=$1
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  if child_pid_is_owned "$pid"; then
    kill -KILL "$pid" 2>/dev/null || return 1
  fi
  wait "$pid" 2>/dev/null || :
  clear_child_pid_identity "$pid"
  if test -n "$CHILD_PGID"; then clear_child_group_identity "$CHILD_PGID"; fi
}

terminate_child_group() {
  local pgid=$1 anchor_pid=${2:-} remaining=30 termination_failed=false
  [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$anchor_pid" =~ ^[1-9][0-9]*$ ]] || return 1
  if ! child_pid_is_owned "$anchor_pid"; then
    wait "$anchor_pid" 2>/dev/null || :
    clear_child_pid_identity "$anchor_pid"
    kill -0 -- "-$pgid" 2>/dev/null && return 1
    clear_child_group_identity "$pgid"
    return 0
  fi
  stop_owned_child_anchor "$anchor_pid" || return 1
  if ! kill -0 -- "-$pgid" 2>/dev/null; then
    kill -KILL "$anchor_pid" 2>/dev/null || return 1
    wait "$anchor_pid" 2>/dev/null || :
    clear_child_pid_identity "$anchor_pid"
    kill -0 -- "-$pgid" 2>/dev/null && return 1
    clear_child_group_identity "$pgid"
    return 0
  fi
  kill -TERM -- "-$pgid" 2>/dev/null || termination_failed=true
  sleep 1
  if ! kill -0 -- "-$pgid" 2>/dev/null; then
    wait "$anchor_pid" 2>/dev/null || :
    clear_child_pid_identity "$anchor_pid"
    clear_child_group_identity "$pgid"
    test "$termination_failed" = false
    return
  fi
  kill -KILL -- "-$pgid" 2>/dev/null || return 1
  # Linux kill(2) still observes an unreaped zombie, so reap only after the final destructive
  # group signal, then use signal-zero probes exclusively; never signal a numeric PGID after reap.
  wait "$anchor_pid" 2>/dev/null || :
  clear_child_pid_identity "$anchor_pid"
  while test "$remaining" -gt 0; do
    if ! kill -0 -- "-$pgid" 2>/dev/null; then
      clear_child_group_identity "$pgid"
      test "$termination_failed" = false
      return
    fi
    sleep 1
    remaining=$((remaining - 1))
  done
  return 1
}

terminate_controlled_child_group() {
  local pgid pid status=0
  test "$CHILD_TERMINATION_ACTIVE" != true || return 0
  local CHILD_TERMINATION_ACTIVE=true
  pgid=$CHILD_PGID
  pid=$CHILD_PID
  if test "$CHILD_GROUP_READY" = true && test -n "$pgid" && test -n "$pid"; then
    terminate_child_group "$pgid" "$pid" || status=$?
  fi
  return "$status"
}

handle_signal() {
  local signal=$1 status=$2
  SIGNAL_STATUS=$status
  test "$SIGNAL_HANDLER_ACTIVE" != true || return 0
  local SIGNAL_HANDLER_ACTIVE=true
  if test "$CHILD_GROUP_READY" = true && test -n "$CHILD_PGID"; then
    terminate_controlled_child_group || CONTAINMENT_FAILED=true
  fi
  printf 'cs2a-governor-supervisor: received %s\n' "$signal" >&2
  if test "$CHILD_LAUNCH_CRITICAL" = true; then
    return 1
  fi
}

install_supervisor_signal_traps() {
  trap 'handle_signal INT 130 || return $?' INT
  trap 'handle_signal TERM 143 || return $?' TERM
  trap 'handle_signal HUP 129 || return $?' HUP
}

write_state_file() {
  local name=$1 value=$2
  printf '%s\n' "$value" >"$STATE/$name"
  chmod 0400 "$STATE/$name"
}

extract_run_root_marker() {
  local output=$1 markers count
  markers=$(tr -d '\r' <"$output" | sed -n 's/^RUN_ROOT=//p') || return 1
  count=$(printf '%s\n' "$markers" | sed '/^$/d' | wc -l | tr -d ' ')
  test "$count" -eq 1 || return 1
  [[ "$markers" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  printf '%s\n' "$markers"
}

verify_child_lock_descriptor() {
  local descriptor_provenance lock_provenance
  test -d "$PROC_FD_ROOT" || return 1
  test -e "$PROC_FD_ROOT/9" || return 1
  descriptor_provenance=$(stat -Lc '%d:%i' "$PROC_FD_ROOT/9") || return 1
  lock_provenance=$(stat -Lc '%d:%i' "$LOCK_FILE") || return 1
  test "$descriptor_provenance" = "$lock_provenance"
}

close_unapproved_child_descriptors() {
  local descriptor_path descriptor
  test -d "$PROC_FD_ROOT" || return 1
  for descriptor_path in "$PROC_FD_ROOT"/*; do
    test -e "$descriptor_path" || continue
    descriptor=${descriptor_path##*/}
    [[ "$descriptor" =~ ^[0-9]+$ ]] || return 1
    case "$descriptor" in
      0 | 1 | 2 | 9) ;;
      *) eval "exec ${descriptor}>&-" || return 1 ;;
    esac
  done
}

controlled_child_exec() {
  local controlled_uid=$1 implementation=$2 runner_sha=$3 profile=$4
  test "$#" -eq 4 || fail "invalid controlled child identity arguments"
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled child UID"
  test "$(id -u)" = "$controlled_uid" || fail "controlled child UID mismatch"
  [[ "$implementation" =~ ^[0-9a-f]{40}$ ]] \
    || fail "invalid controlled child implementation identity"
  [[ "$runner_sha" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid controlled child runner identity"
  case "$profile" in full | smoke) ;; *) fail "invalid controlled child profile" ;; esac
  verify_child_lock_descriptor || fail "inherited benchmark lock descriptor substitution"
  close_unapproved_child_descriptors || fail "cannot close unapproved child descriptors"
  verify_child_lock_descriptor || fail "inherited benchmark lock descriptor substitution"
  exec /usr/bin/env -i \
    PATH="$TRUSTED_CHILD_PATH" \
    CS2A_LOCK_FD=9 \
    CS2A_AUTHENTICATED_UID="$controlled_uid" \
    CS2A_IMPLEMENTATION_SHA="$implementation" \
    CS2A_AUTHENTICATED_RUNNER_SHA="$runner_sha" \
    CS2A_PROFILE="$profile" \
    "$RUNNER_FILE"
}

authenticate_controlled_launcher() {
  local parent_pid=$1 parent_identity=$2 ready_file=$3 release_file=$4 status_file=$5
  local state state_name
  test "$PPID" = "$parent_pid" || return 1
  test "$(process_identity "$parent_pid")" = "$parent_identity" || return 1
  verify_child_lock_descriptor || return 1
  state=${ready_file%/child-output.log.group-ready}
  test "$state" != "$ready_file" || return 1
  test "$release_file" = "$state/child-output.log.group-release" || return 1
  test "$status_file" = "$state/child-output.log.child-status" || return 1
  test "${state%/*}" = "$STATE_PARENT" || return 1
  state_name=${state##*/}
  [[ "$state_name" =~ ^governor-state\.[A-Za-z0-9]{8}$ ]] || return 1
  test -d "$state" && test ! -L "$state" || return 1
  test "$(stat -Lc '%u:%g:%a' "$state")" = 0:0:700 || return 1
  test ! -e "$ready_file" && test ! -L "$ready_file" \
    && test ! -e "$release_file" && test ! -L "$release_file" \
    && test ! -e "$status_file" && test ! -L "$status_file"
}

watch_controlled_launcher_parent() {
  local parent_identity=$1 launcher_pid=$2
  [[ "$launcher_pid" =~ ^[1-9][0-9]*$ ]] || return 1
  trap ':' INT TERM HUP
  while test "$(process_identity "${parent_identity%%:*}" 2>/dev/null || :)" = "$parent_identity"; do
    sleep 1 &
    wait "$!" 2>/dev/null || :
  done
  printf '%s\n' \
    'cs2a-governor-supervisor: authenticated supervisor parent disappeared; terminating controlled group' \
    >&2
  if kill -TERM -- "-$launcher_pid" 2>/dev/null; then
    printf '%s\n' 'cs2a-governor-supervisor: orphan watchdog sent TERM to controlled group' >&2
  else
    printf '%s\n' 'cs2a-governor-supervisor: orphan watchdog failed to send TERM to controlled group' >&2
  fi
  sleep 1 &
  wait "$!" 2>/dev/null || :
  printf '%s\n' 'cs2a-governor-supervisor: orphan watchdog escalating controlled group to KILL' >&2
  kill -KILL -- "-$launcher_pid" 2>/dev/null || {
    printf '%s\n' 'cs2a-governor-supervisor: orphan watchdog failed to KILL controlled group' >&2
    return 1
  }
}

controlled_launcher_exec() {
  local parent_pid=$1 parent_identity=$2 ready_file=$3 release_file=$4 status_file=$5
  local controlled_uid=$6 implementation=$7 runner_sha=$8 profile=$9
  local ready_tmp status_tmp child_status
  test "$#" -eq 9 || fail "invalid controlled launcher arguments"
  authenticate_controlled_launcher \
    "$parent_pid" "$parent_identity" "$ready_file" "$release_file" "$status_file" \
    || fail "unauthenticated controlled launcher"
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || fail "invalid controlled launcher UID"
  [[ "$implementation" =~ ^[0-9a-f]{40}$ ]] \
    || fail "invalid controlled launcher implementation identity"
  [[ "$runner_sha" =~ ^[0-9a-f]{64}$ ]] \
    || fail "invalid controlled launcher runner identity"
  case "$profile" in full | smoke) ;; *) fail "invalid controlled launcher profile" ;; esac
  trap ':' INT TERM HUP
  watch_controlled_launcher_parent "$parent_identity" "$$" &
  test "$(process_identity "$parent_pid")" = "$parent_identity" \
    || fail "controlled launcher parent disappeared before readiness"
  ready_tmp=$ready_file.tmp.$$
  printf '%s\n' "$$" >"$ready_tmp"
  chmod 0400 "$ready_tmp"
  mv -f "$ready_tmp" "$ready_file"
  while ! test -s "$release_file"; do
    sleep 1 &
    wait "$!" 2>/dev/null || :
  done
  test "$(tr -d '\r\n' <"$release_file")" = go || fail "invalid launcher release"
  set +e
  /usr/bin/timeout --foreground --signal=TERM --kill-after="$RUN_KILL_AFTER_SECONDS" \
    "$RUN_TIMEOUT_SECONDS" /usr/sbin/runuser -u "$CONTROLLED_USER" -- \
    /usr/bin/env -i \
      PATH="$TRUSTED_CHILD_PATH" \
      CS2A_LOCK_FD=9 \
      CS2A_AUTHENTICATED_UID="$controlled_uid" \
      CS2A_IMPLEMENTATION_SHA="$implementation" \
      CS2A_AUTHENTICATED_RUNNER_SHA="$runner_sha" \
      /bin/bash "$0" --run-controlled-child "$controlled_uid" "$implementation" \
      "$runner_sha" "$profile"
  child_status=$?
  set -e
  status_tmp=$status_file.tmp.$$
  printf '%s\n' "$child_status" >"$status_tmp"
  chmod 0400 "$status_tmp"
  mv -f "$status_tmp" "$status_file"
  while :; do
    sleep 3600 &
    wait "$!" 2>/dev/null || :
  done
}

publish_controlled_child_release() {
  local release_tmp=$1
  local CHILD_LAUNCH_CRITICAL=true
  test -z "$SIGNAL_STATUS" || return 1
  printf 'go\n' >"$release_tmp" || return 1
  chmod 0400 "$release_tmp" || return 1
  test -z "$SIGNAL_STATUS" || return 1
  mv -f "$release_tmp" "$CHILD_RELEASE_FILE" || return 1
  test -z "$SIGNAL_STATUS" || return 1
}

launch_controlled_child() {
  local controlled_uid=$1 implementation=$2 runner_sha=$3 profile=$4 output=$5
  local launcher_pid parent_identity identity remaining=300 release_tmp release_status
  test "$#" -eq 5 || fail "invalid controlled child launch arguments"
  case "$profile" in full | smoke) ;; *) fail "invalid controlled launch profile" ;; esac
  test -z "$SIGNAL_STATUS" || return 1
  CHILD_READY_FILE=$output.group-ready
  CHILD_RELEASE_FILE=$output.group-release
  CHILD_STATUS_FILE=$output.child-status
  test ! -e "$CHILD_READY_FILE" && test ! -e "$CHILD_RELEASE_FILE" \
    && test ! -e "$CHILD_STATUS_FILE" || return 1
  parent_identity=$(process_identity "$$") || return 1
  /usr/bin/setsid /bin/bash "$0" --run-controlled-launcher "$$" "$parent_identity" \
    "$CHILD_READY_FILE" "$CHILD_RELEASE_FILE" "$CHILD_STATUS_FILE" \
    "$controlled_uid" "$implementation" "$runner_sha" "$profile" \
    >"$output" 2>&1 &
  launcher_pid=$!
  CHILD_PID=$launcher_pid
  CHILD_PGID=$launcher_pid
  CHILD_GROUP_READY=false
  while test "$remaining" -gt 0; do
    if test -s "$CHILD_READY_FILE"; then
      identity=$(tr -d '\r\n' <"$CHILD_READY_FILE") || break
      if test "$identity" = "$launcher_pid" && child_pid_is_owned "$launcher_pid"; then
        CHILD_GROUP_READY=true
        break
      fi
      CONTAINMENT_FAILED=true
      break
    fi
    child_pid_is_owned "$launcher_pid" || break
    sleep 0.1
    remaining=$((remaining - 1))
  done
  if test "$CHILD_GROUP_READY" != true; then
    terminate_unready_child "$launcher_pid" || CONTAINMENT_FAILED=true
    return 1
  fi
  release_tmp=$CHILD_RELEASE_FILE.tmp.$$
  if publish_controlled_child_release "$release_tmp"; then
    release_status=0
  else
    release_status=$?
  fi
  if test "$release_status" -ne 0; then
    rm -f -- "$release_tmp" || CONTAINMENT_FAILED=true
    if test -n "$CHILD_PGID"; then
      terminate_controlled_child_group || CONTAINMENT_FAILED=true
    fi
    return 1
  fi
}

finalize_supervisor() {
  local incoming=$? final_status=$CHILD_STATUS
  test "$CLEANUP_COMPLETE" = false || return "$incoming"
  CLEANUP_COMPLETE=true
  trap - EXIT
  set +e
  if test "$CHILD_GROUP_READY" = true && test -n "$CHILD_PGID"; then
    terminate_controlled_child_group || CONTAINMENT_FAILED=true
  elif test -n "$CHILD_PID"; then
    terminate_unready_child "$CHILD_PID" || CONTAINMENT_FAILED=true
  fi
  if test -n "$STATE" && test -f "$STATE/original-governors.tsv"; then
    restore_governors "$STATE/original-governors.tsv" || RESTORATION_FAILED=true
    capture_restored_governors "$STATE/original-governors.tsv" \
      "$STATE/restored-governors.tsv" || RESTORATION_FAILED=true
    chmod 0400 "$STATE/restored-governors.tsv" 2>/dev/null || RESTORATION_FAILED=true
    cmp -s "$STATE/original-governors.tsv" "$STATE/restored-governors.tsv" \
      || RESTORATION_FAILED=true
  fi
  trap '' INT TERM HUP
  if test -n "$SIGNAL_STATUS"; then final_status=$SIGNAL_STATUS; fi
  if test "$RESTORATION_FAILED" = true || test "$CONTAINMENT_FAILED" = true; then
    final_status=70
  fi
  if test -n "$STATE" && test -d "$STATE"; then
    write_state_file child-or-supervisor-status.txt "$final_status"
    write_state_file restoration-failed.txt "$RESTORATION_FAILED"
    write_state_file containment-failed.txt "$CONTAINMENT_FAILED"
    write_state_file finished-at.txt "$(date -Iseconds)"
    if test -n "$AUTHENTICATED_RUN_ROOT"; then
      if ! copy_final_state_to_run_root "$AUTHENTICATED_RUN_ROOT"; then
        CONTAINMENT_FAILED=true
        final_status=70
        write_state_file child-or-supervisor-status.txt "$final_status"
        write_state_file containment-failed.txt "$CONTAINMENT_FAILED"
        copy_final_state_to_run_root "$AUTHENTICATED_RUN_ROOT" || true
      fi
    fi
    printf 'GOVERNOR_STATE=%s\n' "$STATE"
  fi
  exit "$final_status"
}

supervisor_main() {
  local expected_profile=${1:-} implementation runner_sha supervisor_sha run_root
  test "$#" -eq 1 || fail "this supervisor requires one expected profile"
  case "$expected_profile" in full | smoke) ;; *) fail "invalid expected benchmark profile" ;; esac
  test "$(id -u)" -eq 0 || fail "must execute as root"
  umask 077
  install -d -o root -g root -m 0700 "$STATE_PARENT"
  prepare_lock_file
  exec 9<>"$LOCK_FILE"
  test "$(stat -Lc '%d:%i' /proc/$$/fd/9)" = "$(stat -Lc '%d:%i' "$LOCK_FILE")" \
    || fail "benchmark lock descriptor substitution"
  flock -n 9 || fail "exclusive benchmark lock is held"
  validate_handoff
  test "$CONTROLLED_PROFILE" = "$expected_profile" \
    || fail "operator handoff profile does not match this invocation"
  recover_stale_states
  STATE=$(mktemp -d "$STATE_PARENT/governor-state.XXXXXXXX")
  chmod 0700 "$STATE"
  trap finalize_supervisor EXIT
  install_supervisor_signal_traps

  implementation=$(tr -d '\r\n' <"$IMPLEMENTATION_FILE")
  runner_sha=$(sha256sum "$RUNNER_FILE" | cut -d' ' -f1)
  supervisor_sha=$(sha256sum "$0" | cut -d' ' -f1)
  printf 'implementation\t%s\nuid\t%s\nrunner\t%s\nsupervisor\t%s\nprofile\t%s\n' \
    "$implementation" "$(tr -d '\r\n' <"$CONTROLLED_UID_FILE")" \
    "$runner_sha" "$supervisor_sha" "$CONTROLLED_PROFILE" >"$STATE/authenticated-handoff.tsv"
  printf 'runner\t%s\nsupervisor\t%s\n' "$runner_sha" "$supervisor_sha" \
    >"$STATE/executed-script-sha256sums.tsv"
  printf '%s\n' "$implementation" >"$STATE/implementation-sha.txt"
  stat -Lc '%u:%g:%a:%d:%i' /proc/$$/fd/9 >"$STATE/lock-provenance.txt"
  capture_governors "$STATE/original-governors.tsv" || fail "cannot capture governors"
  chmod 0400 "$STATE"/*.tsv "$STATE/implementation-sha.txt" \
    "$STATE/lock-provenance.txt"
  set_performance_governors "$STATE/original-governors.tsv" \
    || fail "cannot set performance governors"

  set +e
  if launch_controlled_child "$(tr -d '\r\n' <"$CONTROLLED_UID_FILE")" \
    "$implementation" "$runner_sha" "$CONTROLLED_PROFILE" "$STATE/child-output.log"; then
    wait_for_controlled_child
    CHILD_STATUS=$?
  else
    CHILD_STATUS=$?
  fi
  set -e
  test -z "$SIGNAL_STATUS" || return
  cat "$STATE/child-output.log"

  run_root=$(extract_run_root_marker "$STATE/child-output.log") \
    || fail "runner emitted invalid run-root marker count"
  write_state_file run-root.txt "$run_root"
  AUTHENTICATED_RUN_ROOT=$run_root
}

supervisor_dispatch() {
  case "$#:${1:-}" in
    2:--run-profile) supervisor_main "$2" ;;
    3:--publish-final-handoff) publish_final_handoff_main "$2" "$3" ;;
    3:--validate-final-handoff) validate_final_handoff_main "$2" "$3" ;;
    5:--run-controlled-child) controlled_child_exec "$2" "$3" "$4" "$5" ;;
    10:--run-controlled-launcher) controlled_launcher_exec "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" ;;
    *) fail 'usage: cs2a-governor-supervisor.sh --run-profile full|smoke | --publish-final-handoff RUN_ROOT GOVERNOR_STATE | --validate-final-handoff RUN_ROOT GOVERNOR_STATE' ;;
  esac
}

if test "${BASH_SOURCE[0]}" = "$0"; then
  supervisor_dispatch "$@"
fi
