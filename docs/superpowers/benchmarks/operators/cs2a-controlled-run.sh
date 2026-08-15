#!/usr/bin/env bash
# Newly authored CS2a controlled-host runner. There is no recoverable Task 13 source to reuse;
# changes to this file require fixed-range security review and the operator mutation suite.
set -Eeuo pipefail

readonly LOCK_FILE=/opt/revoman-benchmark/task13.lock
readonly BASELINE_SHA=83f3cd70f78ad733412d10cbc8287aaabafe7aac

fail() {
  printf 'cs2a-controlled-run: %s\n' "$*" >&2
  exit 70
}

authenticate_supervisor_handoff() {
  local runner_sha lock_target
  [[ "${CS2A_AUTHENTICATED_UID:-}" =~ ^[1-9][0-9]*$ ]] \
    || fail "missing authenticated controlled UID"
  test "$(id -u)" = "$CS2A_AUTHENTICATED_UID" || fail "unexpected controlled UID"
  [[ "${CS2A_LOCK_FD:-}" =~ ^[0-9]+$ ]] || fail "missing inherited locked FD"
  lock_target=$(readlink "/proc/$$/fd/$CS2A_LOCK_FD") \
    || fail "inherited locked FD is closed"
  test "$lock_target" = "$LOCK_FILE" || fail "inherited locked FD targets $lock_target"
  [[ "${CS2A_IMPLEMENTATION_SHA:-}" =~ ^[0-9a-f]{40}$ ]] \
    || fail "invalid authenticated implementation identity"
  [[ "${CS2A_AUTHENTICATED_RUNNER_SHA:-}" =~ ^[0-9a-f]{64}$ ]] \
    || fail "missing authenticated runner identity"
  case "${CS2A_PROFILE:-}" in
    full | smoke) ;;
    *) fail "invalid authenticated benchmark profile" ;;
  esac
  runner_sha=$(sha256sum "$0" | cut -d' ' -f1)
  test "$runner_sha" = "$CS2A_AUTHENTICATED_RUNNER_SHA" \
    || fail "runner provenance mismatch"
  readonly CS2A_IMPLEMENTATION_SHA
}

authenticate_supervisor_handoff

export JAVA_HOME=/home/gopala.akshintala/core-public/tools/Linux/jdk/sfdc-jdk-zulu-21.helium_x64
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
export GRADLE_OPTS=-Dorg.gradle.daemon=false
readonly SOURCE_REPO=/home/gopala.akshintala/code-clones/work/revoman-root
RUN_ROOT=

# shellcheck disable=SC2317,SC2329 # invoked by the EXIT trap installed below
early_runner_exit() {
  local status=$?
  trap - EXIT
  set +e
  case "$RUN_ROOT" in
    /opt/revoman-benchmark/runs/cs2a.*)
      mkdir -p "$RUN_ROOT/manifests" "$RUN_ROOT/results" "$RUN_ROOT/logs" "$RUN_ROOT/meta"
      test -f "$RUN_ROOT/meta/stage.txt" \
        || printf '%s\n' setup >"$RUN_ROOT/meta/stage.txt"
      printf '%s\n' "$status" >"$RUN_ROOT/meta/runner-exit.txt"
      printf '%s\n' 1 >"$RUN_ROOT/meta/inventory-exit.txt"
      (cd "$RUN_ROOT" && find manifests results logs meta -type f \
        ! -path 'meta/remote-byte-sha256sums.txt' -print0 \
        | LC_ALL=C sort -z | xargs -0 -r sha256sum \
        >meta/remote-byte-sha256sums.txt)
      printf 'RUN_ROOT=%s\n' "$RUN_ROOT"
      ;;
  esac
  exit "$status"
}

RUN_ROOT=$(mktemp -d /opt/revoman-benchmark/runs/cs2a.XXXXXXXX)
case "$RUN_ROOT" in /opt/revoman-benchmark/runs/cs2a.*) ;; *) fail "unsafe run root" ;; esac
trap early_runner_exit EXIT
mkdir "$RUN_ROOT/meta"
printf '%s\n' setup >"$RUN_ROOT/meta/stage.txt"
printf '%s\n' "$CS2A_PROFILE" >"$RUN_ROOT/meta/profile.txt"
HARNESS="$RUN_ROOT/checkouts/harness"
BASELINE_A="$RUN_ROOT/checkouts/baseline-a"
BASELINE_B="$RUN_ROOT/checkouts/baseline-b"
CANDIDATE="$RUN_ROOT/checkouts/candidate"
POLICY=/opt/revoman-benchmark/controlled-host.json
EXPECTED_POLICY_SHA256=7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79
EXPECTED_POLICY_SEMANTIC_SHA256=48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60
EXPECTED_HOST_FINGERPRINT=12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44
INIT="$HARNESS/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts"
DRIVER="$HARNESS/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
mkdir "$RUN_ROOT/checkouts" "$RUN_ROOT/manifests" "$RUN_ROOT/results" \
  "$RUN_ROOT/artifacts" "$RUN_ROOT/logs"

# shellcheck disable=SC2317,SC2329 # invoked from the EXIT handler installed below
write_inventory() (
  set -euo pipefail
  cd "$RUN_ROOT"
  find manifests results -type f -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum >meta/evidence-sha256sums.txt
  find artifacts -type f -printf '%p\t%s\n' \
    | LC_ALL=C sort >meta/artifact-inventory.tsv
  find artifacts -type f -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum >meta/artifact-sha256sums.txt
  find logs -type f -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum >meta/command-output-sha256sums.txt
)

# shellcheck disable=SC2317,SC2329 # invoked from the EXIT handler installed below
write_remote_byte_inventory() (
  set -euo pipefail
  cd "$RUN_ROOT"
  find manifests results logs meta -type f \
    ! -path 'meta/remote-byte-sha256sums.txt' -print0 \
    | LC_ALL=C sort -z | xargs -0 -r sha256sum >meta/remote-byte-sha256sums.txt
)

# shellcheck disable=SC2317,SC2329 # invoked by the EXIT trap installed below
on_runner_exit() {
  local status=$? inventory_status=0
  trap - EXIT
  set +e
  write_inventory
  inventory_status=$?
  printf '%s\n' "$status" >"$RUN_ROOT/meta/runner-exit.txt"
  printf '%s\n' "$inventory_status" >"$RUN_ROOT/meta/inventory-exit.txt"
  if ! write_remote_byte_inventory; then
    inventory_status=1
    printf '%s\n' "$inventory_status" >"$RUN_ROOT/meta/inventory-exit.txt"
    write_remote_byte_inventory || true
  fi
  printf 'RUN_ROOT=%s\n' "$RUN_ROOT"
  if test "$status" -eq 0 && test "$inventory_status" -ne 0; then
    status=$inventory_status
  fi
  exit "$status"
}
trap on_runner_exit EXIT

git -C "$SOURCE_REPO" fetch origin codex/performance-cs2a-lifecycle
git -C "$SOURCE_REPO" cat-file -e "$CS2A_IMPLEMENTATION_SHA^{commit}"
git -C "$SOURCE_REPO" merge-base --is-ancestor "$CS2A_IMPLEMENTATION_SHA" \
  origin/codex/performance-cs2a-lifecycle
for checkout in "$HARNESS" "$CANDIDATE"; do
  git clone --no-hardlinks --quiet "$SOURCE_REPO" "$checkout"
  git -C "$checkout" checkout --detach "$CS2A_IMPLEMENTATION_SHA"
done
for checkout in "$BASELINE_A" "$BASELINE_B"; do
  git clone --no-hardlinks --quiet "$SOURCE_REPO" "$checkout"
  git -C "$checkout" checkout --detach "$BASELINE_SHA"
done
for checkout in "$HARNESS" "$BASELINE_A" "$BASELINE_B" "$CANDIDATE"; do
  test -z "$(git -C "$checkout" status --porcelain)"
  test -z "$(git -C "$checkout" symbolic-ref -q HEAD || true)"
done
test "$(git -C "$HARNESS" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test "$(git -C "$CANDIDATE" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test "$(git -C "$BASELINE_A" rev-parse HEAD)" = "$BASELINE_SHA"
test "$(git -C "$BASELINE_B" rev-parse HEAD)" = "$BASELINE_SHA"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh" "$RUN_ROOT/meta/"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh" "$RUN_ROOT/meta/"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-operator.sh" "$RUN_ROOT/meta/"
cp "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq" "$RUN_ROOT/meta/"
cp "$POLICY" "$RUN_ROOT/meta/controlled-host.json"
printf '%s\n' "$CS2A_AUTHENTICATED_UID" >"$RUN_ROOT/meta/controlled-uid.txt"
printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$RUN_ROOT/meta/implementation-sha.txt"
(cd "$RUN_ROOT/meta" && sha256sum cs2a-controlled-run.sh cs2a-governor-supervisor.sh \
  cs2a-operator.sh cs2a-validate-manifest.jq >operator-script-sha256sums.txt)
test "$(stat -c '%U:%G:%a' "$POLICY")" = root:root:444
test "$(sha256sum "$POLICY" | cut -d' ' -f1)" = "$EXPECTED_POLICY_SHA256"
jq -e --arg host "$EXPECTED_HOST_FINGERPRINT" '
  .schema == "revoman-controlled-host/v1" and
  .hostFingerprintSha256 == $host and
  .allowedGovernors == ["performance"] and
  .powerEvidenceRequirement == "FIXED_MAINS"
' "$POLICY" >/dev/null
printf '%s  %s\n' "$EXPECTED_POLICY_SHA256" "$POLICY" >"$RUN_ROOT/meta/policy-sha256.txt"
printf '%s\n' "$EXPECTED_POLICY_SEMANTIC_SHA256" >"$RUN_ROOT/meta/policy-semantic-sha256.txt"
printf '%s\n' "$RUN_ROOT" >"$RUN_ROOT/meta/run-root.txt"
: >"$RUN_ROOT/meta/commands.tsv"

run_logged() {
  local label=$1 status
  shift
  [[ "$label" =~ ^[a-z0-9][a-z0-9.-]*$ ]]
  test ! -e "$RUN_ROOT/logs/$label.stdout"
  test ! -e "$RUN_ROOT/logs/$label.stderr"
  test ! -e "$RUN_ROOT/logs/$label.exit"
  {
    printf '%s' "$label"
    printf '\t%q' "$@"
    printf '\n'
  } >>"$RUN_ROOT/meta/commands.tsv"
  if "$@" >"$RUN_ROOT/logs/$label.stdout" 2>"$RUN_ROOT/logs/$label.stderr"; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/logs/$label.exit"
  return "$status"
}

run_campaign() {
  local label=$1 output=$2 status
  shift 2
  if run_logged "$label" "$@"; then status=0; else status=$?; fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/$label-exit.txt"
  case "$status" in 0 | 1) test -s "$output" ;; *) return "$status" ;; esac
}

verify_controlled_result() {
  local label=$1 result=$2
  run_logged "verify-$label" "$DRIVER" verify --input "$result"
  jq -e --arg policy "$EXPECTED_POLICY_SEMANTIC_SHA256" \
    --arg host "$EXPECTED_HOST_FINGERPRINT" \
    '.environment.policySha256 == $policy and
     .environment.hostFingerprintSha256 == $host' "$result" >/dev/null
}

run_smoke_profile() {
  local label result comparison status
  run_campaign cold-aa "$RUN_ROOT/results/cold-aa.json" \
    "$DRIVER" run-paired --mode cold --intent smoke \
    --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
    --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
    --warmups 0 --iterations 1 --seed 5928239383101656625 \
    --metrics latency --host-policy "$POLICY" \
    --artifacts-dir "$RUN_ROOT/artifacts/cold-aa" \
    --output "$RUN_ROOT/results/cold-aa.json"
  run_campaign warm-aa "$RUN_ROOT/results/warm-aa.json" \
    "$DRIVER" run-paired --mode warm --intent smoke \
    --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
    --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
    --warmups 1 --iterations 3 --seed 5928239383101656625 \
    --metrics latency --host-policy "$POLICY" \
    --artifacts-dir "$RUN_ROOT/artifacts/warm-aa" \
    --output "$RUN_ROOT/results/warm-aa.json"
  run_campaign cold-candidate "$RUN_ROOT/results/cold-candidate.json" \
    "$DRIVER" run-paired --mode cold --intent smoke \
    --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
    --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
    --warmups 0 --iterations 1 --seed 5928239383101656625 \
    --metrics latency --host-policy "$POLICY" \
    --artifacts-dir "$RUN_ROOT/artifacts/cold-candidate" \
    --output "$RUN_ROOT/results/cold-candidate.json"
  run_campaign warm-candidate "$RUN_ROOT/results/warm-candidate.json" \
    "$DRIVER" run-paired --mode warm --intent smoke \
    --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
    --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
    --warmups 1 --iterations 3 --seed 5928239383101656625 \
    --metrics latency --host-policy "$POLICY" \
    --artifacts-dir "$RUN_ROOT/artifacts/warm-candidate" \
    --output "$RUN_ROOT/results/warm-candidate.json"
  printf '%s\n' smoke-captured >"$RUN_ROOT/meta/stage.txt"

  for label in aa-cold aa-warm candidate-cold candidate-warm; do
    case "$label" in
      aa-cold) result=cold-aa; comparison='comparison-aa-cold' ;;
      aa-warm) result=warm-aa; comparison='comparison-aa-warm' ;;
      candidate-cold) result=cold-candidate; comparison='comparison-candidate-cold' ;;
      candidate-warm) result=warm-candidate; comparison='comparison-candidate-warm' ;;
    esac
    verify_controlled_result "$label" "$RUN_ROOT/results/$result.json"
    if run_logged "$comparison" "$DRIVER" compare \
      --input "$RUN_ROOT/results/$result.json" \
      --output-json "$RUN_ROOT/results/$comparison.json" \
      --output-md "$RUN_ROOT/results/$comparison.md"; then
      status=0
    else
      status=$?
    fi
    printf '%s\n' "$status" >"$RUN_ROOT/meta/$comparison-exit.txt"
    test "$status" -eq 0
    test -s "$RUN_ROOT/results/$comparison.json"
    test -s "$RUN_ROOT/results/$comparison.md"
  done
  printf '%s\n' smoke-compared >"$RUN_ROOT/meta/stage.txt"
}

run_logged install-harness "$HARNESS/gradlew" -p "$HARNESS" \
  :benchmark-driver:installDist --no-daemon --console=plain
run_logged export-baseline-a "$BASELINE_A/gradlew" -p "$BASELINE_A" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/baseline-a.json" \
  -Pbenchmark.targetId=baseline-a-cs2a --no-daemon --console=plain
run_logged export-baseline-b "$BASELINE_B/gradlew" -p "$BASELINE_B" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/baseline-b.json" \
  -Pbenchmark.targetId=baseline-b-cs2a --no-daemon --console=plain
run_logged export-candidate "$CANDIDATE/gradlew" -p "$CANDIDATE" -I "$INIT" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$RUN_ROOT/manifests/candidate.json" \
  -Pbenchmark.targetId=candidate-cs2a --no-daemon --console=plain

for manifest_name in baseline-a baseline-b candidate; do
  manifest="$RUN_ROOT/manifests/$manifest_name.json"
  run_logged "verify-manifest-$manifest_name" \
    jq -e -f "$HARNESS/docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq" \
    "$manifest"
done

if test "$CS2A_PROFILE" = smoke; then
  run_smoke_profile
  exit 0
fi

run_campaign cold-aa "$RUN_ROOT/results/cold-aa.json" \
  "$DRIVER" capture-baseline --mode cold --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency,peak-rss,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/cold-aa" --output "$RUN_ROOT/results/cold-aa.json"
run_campaign warm-aa "$RUN_ROOT/results/warm-aa.json" \
  "$DRIVER" capture-baseline --mode warm --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 20 --iterations 100 --seed 5928239383101656625 \
  --metrics latency,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/warm-aa" --output "$RUN_ROOT/results/warm-aa.json"
printf '%s\n' aa-captured >"$RUN_ROOT/meta/stage.txt"
aa_failed=false
for mode in cold warm; do
  verify_controlled_result "aa-$mode" "$RUN_ROOT/results/$mode-aa.json"
  if run_logged "comparison-aa-$mode" "$DRIVER" compare \
    --input "$RUN_ROOT/results/$mode-aa.json" \
    --output-json "$RUN_ROOT/results/comparison-aa-$mode.json" \
    --output-md "$RUN_ROOT/results/comparison-aa-$mode.md" --enforce-release-gates; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/comparison-aa-$mode-exit.txt"
  test -s "$RUN_ROOT/results/comparison-aa-$mode.json"
  test -s "$RUN_ROOT/results/comparison-aa-$mode.md"
  if test "$status" -ne 0 \
    || ! jq -e '.overall == "PASS"' "$RUN_ROOT/results/comparison-aa-$mode.json" >/dev/null; then
    aa_failed=true
  fi
done
printf '%s\n' aa-compared >"$RUN_ROOT/meta/stage.txt"
if test "$aa_failed" = true; then exit 3; fi

run_campaign cold-candidate "$RUN_ROOT/results/cold-candidate.json" \
  "$DRIVER" run-paired --mode cold --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency,peak-rss,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/cold-candidate" \
  --output "$RUN_ROOT/results/cold-candidate.json"
run_campaign warm-candidate "$RUN_ROOT/results/warm-candidate.json" \
  "$DRIVER" run-paired --mode warm --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 20 --iterations 100 --seed 5928239383101656625 \
  --metrics latency,allocation --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/warm-candidate" \
  --output "$RUN_ROOT/results/warm-candidate.json"
run_campaign retained-candidate "$RUN_ROOT/results/retained-candidate.json" \
  "$DRIVER" run-paired --mode retained --intent controlled \
  --baseline "$RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$RUN_ROOT/manifests/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
  --warmups 0 --iterations 0 --seed 5928239383101656625 \
  --metrics retained --host-policy "$POLICY" \
  --artifacts-dir "$RUN_ROOT/artifacts/retained-candidate" \
  --output "$RUN_ROOT/results/retained-candidate.json"
printf '%s\n' candidate-captured >"$RUN_ROOT/meta/stage.txt"

candidate_status=0
candidate_failed=false
for mode in cold warm retained; do
  verify_controlled_result "candidate-$mode" "$RUN_ROOT/results/$mode-candidate.json"
  if run_logged "comparison-candidate-$mode" "$DRIVER" compare \
    --input "$RUN_ROOT/results/$mode-candidate.json" \
    --output-json "$RUN_ROOT/results/comparison-candidate-$mode.json" \
    --output-md "$RUN_ROOT/results/comparison-candidate-$mode.md" --enforce-release-gates; then
    status=0
  else
    status=$?
  fi
  printf '%s\n' "$status" >"$RUN_ROOT/meta/comparison-candidate-$mode-exit.txt"
  test -s "$RUN_ROOT/results/comparison-candidate-$mode.json"
  test -s "$RUN_ROOT/results/comparison-candidate-$mode.md"
  if test "$status" -ne 0 && test "$candidate_status" -eq 0; then candidate_status=$status; fi
  if test "$status" -ne 0 \
    || ! jq -e '.overall == "PASS"' \
      "$RUN_ROOT/results/comparison-candidate-$mode.json" >/dev/null; then
    candidate_failed=true
  fi
done
printf '%s\n' candidate-compared >"$RUN_ROOT/meta/stage.txt"
if test "$candidate_failed" = true && test "$candidate_status" -eq 0; then
  candidate_status=3
fi
exit "$candidate_status"
