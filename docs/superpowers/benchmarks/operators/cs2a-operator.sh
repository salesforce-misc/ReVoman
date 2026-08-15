#!/usr/bin/env bash
# Newly authored CS2a local operator and deterministic archive validator. No Task 13 source exists
# to preserve; this security-critical file requires fixed-range review and mutation testing.
set -Eeuo pipefail

readonly REMOTE_HOST=gopalaaksh-wsl3
readonly BASELINE_SHA=83f3cd70f78ad733412d10cbc8287aaabafe7aac
OPERATOR_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
readonly OPERATOR_DIR
WORKSPACE_ROOT=$(pwd -P)
readonly WORKSPACE_ROOT
readonly VALIDATOR="$OPERATOR_DIR/cs2a-validate-manifest.jq"
readonly CONTROLLED_RUNNER="$OPERATOR_DIR/cs2a-controlled-run.sh"
readonly SUPERVISOR="$OPERATOR_DIR/cs2a-governor-supervisor.sh"
readonly CONTROLLED_UID_FILE=/opt/revoman-benchmark/controlled-uid
readonly CONTROLLED_UID_POLICY_SHA256=abc4307b6eb40577163790a0c453ece3ff4bff8620c85471a35a1bd3a1aea44b
readonly IMPLEMENTATION_FILE="$WORKSPACE_ROOT/build/cs2a-implementation-sha"
readonly EVIDENCE_ROOT="$WORKSPACE_ROOT/docs/superpowers/benchmarks/results/v1"
readonly EXPECTED_POLICY_SHA256=7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79
readonly EXPECTED_POLICY_SEMANTIC_SHA256=48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60
readonly EXPECTED_HOST_FINGERPRINT=12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44
PUBLICATION_MV=
PUBLICATION_STAT=

fail() {
  printf 'cs2a-operator: %s\n' "$*" >&2
  return 70
}

sha256_of() {
  sha256sum "$1" | cut -d' ' -f1
}

git_no_hooks() {
  git -c core.hooksPath=/dev/null "$@"
}

require_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

controlled_uid_policy_is_provisioned() {
  [[ "$CONTROLLED_UID_POLICY_SHA256" =~ ^[0-9a-f]{64}$ ]]
}

benchmark_profile_is_valid() {
  case "$1" in full | smoke) ;; *) return 1 ;; esac
}

operator_failure_phase_is_valid() {
  case "$1" in install | supervisor | markers | post-status | final-handoff | archive) ;;
    *) return 1 ;;
  esac
}

safe_attempt_path() {
  local attempt=$1 implementation=$2 basename
  require_sha "$implementation" || return 1
  test "$(dirname "$attempt")" = "$EVIDENCE_ROOT/cs2a-$implementation" || return 1
  basename=$(basename "$attempt") || return 1
  [[ "$basename" =~ ^(cs2a|operator-failure)\.[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
}

archive_path_type_is_safe() {
  local path=$1
  test ! -L "$path" || return 1
  test -f "$path" || test -d "$path"
}

validate_archive_safety() {
  local root=$1 path
  test -d "$root" && test ! -L "$root" || return 1
  while IFS= read -r -d '' path; do
    archive_path_type_is_safe "$path" || return 1
  done < <(find "$root" -mindepth 1 -print0)
}

publish_local_authority_file() {
  local source=$1 destination=$2 parent candidate
  test -f "$source" && test ! -L "$source" || return 1
  parent=$(dirname "$destination") || return 1
  test -d "$parent" && test ! -L "$parent" || return 1
  test ! -e "$destination" && test ! -L "$destination" || return 1
  candidate=$(mktemp "$parent/.cs2a-local-authority.XXXXXXXX") || return 1
  if ! cp -- "$source" "$candidate" || ! chmod 0600 "$candidate" \
    || ! ln "$candidate" "$destination"; then
    rm -f -- "$candidate"
    return 1
  fi
  rm -f -- "$candidate" || return 1
  test -f "$destination" && test ! -L "$destination"
}

publish_local_authority_value() {
  local destination=$1 value=$2 parent candidate
  parent=$(dirname "$destination") || return 1
  test -d "$parent" && test ! -L "$parent" || return 1
  test ! -e "$destination" && test ! -L "$destination" || return 1
  candidate=$(mktemp "$parent/.cs2a-local-authority.XXXXXXXX") || return 1
  if ! printf '%s\n' "$value" >"$candidate" || ! chmod 0600 "$candidate" \
    || ! ln "$candidate" "$destination"; then
    rm -f -- "$candidate"
    return 1
  fi
  rm -f -- "$candidate" || return 1
  test -f "$destination" && test ! -L "$destination"
}

validate_remote_byte_inventory() {
  local root=$1 inventoried_paths actual_paths
  validate_sha_inventory "$root" meta/remote-byte-sha256sums.txt \
    '^(manifests|results|logs|meta)/[A-Za-z0-9._/-]+$' true || return 1
  inventoried_paths=$(awk '{print substr($0, 67)}' \
    "$root/meta/remote-byte-sha256sums.txt" | LC_ALL=C sort) || return 1
  actual_paths=$(cd "$root" && \
    find manifests results logs meta -type f \
      ! -path 'meta/remote-byte-sha256sums.txt' -print | awk '
        /^meta\/supervisor\// { next }
        /^meta\/supervisor-core\// { next }
        /^meta\/operator-supervisor\.log$/ { next }
        /^meta\/operator-supervisor-exit\.txt$/ { next }
        /^meta\/operator-post-supervisor-exit\.txt$/ { next }
        /^meta\/operator-resume-validation-exit\.txt$/ { next }
        /^meta\/operator-final-exit\.txt$/ { next }
        /^meta\/local-validation-passed\.txt$/ { next }
        { print }
      ' | LC_ALL=C sort) || return 1
  test "$inventoried_paths" = "$actual_paths"
}

validate_command_prefix() {
  local root=$1 base_results=$2 base_statuses=$3
  shift 3
  local allowed_results="$base_results" allowed_statuses="$base_statuses"
  local spec outputs status output result actual_results actual_statuses
  local incomplete=false missing_output=false

  for spec in "$@"; do
    outputs=${spec%%:*}
    status=${spec#*:}
    allowed_results="$allowed_results ${outputs//,/ }"
    allowed_statuses="$allowed_statuses $status"
  done
  actual_results=$(find "$root/results" -maxdepth 1 -type f -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  actual_statuses=$(find "$root/meta" -maxdepth 1 -type f -name '*-exit.txt' -print \
    | sed 's#^.*/##' | awk '
        /^(cold-aa|warm-aa|cold-candidate|warm-candidate|retained-candidate|comparison-aa-cold|comparison-aa-warm|comparison-candidate-cold|comparison-candidate-warm|comparison-candidate-retained)-exit\.txt$/
      ' | LC_ALL=C sort) || return 1
  while IFS= read -r result; do
    test -z "$result" || [[ " $allowed_results " = *" $result "* ]] || return 1
  done <<<"$actual_results"
  while IFS= read -r status; do
    test -z "$status" || [[ " $allowed_statuses " = *" $status "* ]] || return 1
  done <<<"$actual_statuses"
  for result in $base_results; do test -f "$root/results/$result" || return 1; done
  for status in $base_statuses; do
    test -f "$root/meta/$status" || return 1
    [[ "$(tr -d '\r\n' <"$root/meta/$status")" =~ ^[0-9]+$ ]] || return 1
  done
  for spec in "$@"; do
    outputs=${spec%%:*}
    status=${spec#*:}
    if test "$incomplete" = true; then
      for output in ${outputs//,/ }; do test ! -e "$root/results/$output" || return 1; done
      test ! -e "$root/meta/$status" || return 1
      continue
    fi
    missing_output=false
    for output in ${outputs//,/ }; do
      if test "$missing_output" = true; then
        test ! -e "$root/results/$output" || return 1
      elif test ! -f "$root/results/$output"; then
        test ! -e "$root/results/$output" || return 1
        missing_output=true
      fi
    done
    if test -e "$root/meta/$status"; then
      test -f "$root/meta/$status" && test ! -L "$root/meta/$status" || return 1
      [[ "$(tr -d '\r\n' <"$root/meta/$status")" =~ ^[0-9]+$ ]] || return 1
    else
      test ! -L "$root/meta/$status" || return 1
      incomplete=true
    fi
    if test "$missing_output" = true; then incomplete=true; fi
  done
}

validate_status_namespace() {
  local root=$1 path name
  test -d "$root/meta" && test ! -L "$root/meta" || return 1
  while IFS= read -r path; do
    name=${path##*/}
    case "$name" in
      *status*.txt | *exit*.txt)
        case "$name" in
          runner-exit.txt | inventory-exit.txt | \
          operator-failure-source-exit.txt | operator-supervisor-exit.txt | \
          operator-original-post-supervisor-exit.txt | \
          operator-recorded-post-supervisor-exit.txt | \
          operator-post-supervisor-exit.txt | operator-resume-validation-exit.txt | \
          operator-final-exit.txt | \
          cold-aa-exit.txt | warm-aa-exit.txt | \
          cold-candidate-exit.txt | warm-candidate-exit.txt | \
          retained-candidate-exit.txt | \
          comparison-aa-cold-exit.txt | comparison-aa-warm-exit.txt | \
          comparison-candidate-cold-exit.txt | \
          comparison-candidate-warm-exit.txt | \
          comparison-candidate-retained-exit.txt) ;;
          *) return 1 ;;
        esac
        test -f "$path" && test ! -L "$path" || return 1
        ;;
    esac
  done < <(find "$root/meta" -mindepth 1 -maxdepth 1 -print | LC_ALL=C sort)
}

validate_stage_schema() {
  local root=$1 stage manifests
  test -f "$root/meta/stage.txt" || return 1
  stage=$(tr -d '\r\n' <"$root/meta/stage.txt") || return 1
  case "$stage" in setup | aa-captured | aa-compared | candidate-captured | candidate-compared) ;;
    *) return 1 ;;
  esac
  for directory in manifests results logs meta; do
    test -d "$root/$directory" && test ! -L "$root/$directory" || return 1
  done
  validate_status_namespace "$root" || return 1
  validate_command_protocol "$root" || return 1
  manifests=$(find "$root/manifests" -maxdepth 1 -type f -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  if test "$stage" = setup; then
    # A setup failure may leave any exact prefix of the three manifest exports, but no other name.
    case "$manifests" in
      '' | baseline-a.json | "$(printf '%s\n' baseline-a.json baseline-b.json)" | \
      "$(printf '%s\n' baseline-a.json baseline-b.json candidate.json)") ;;
      *) return 1 ;;
    esac
  else
    test "$manifests" = \
      "$(printf '%s\n' baseline-a.json baseline-b.json candidate.json)" || return 1
  fi
  case "$stage" in
    setup)
      validate_command_prefix "$root" '' '' \
        'cold-aa.json:cold-aa-exit.txt' \
        'warm-aa.json:warm-aa-exit.txt' || return 1
      if test -n "$(find "$root/results" -maxdepth 1 -type f -print -quit)" \
        || test -e "$root/meta/cold-aa-exit.txt" \
        || test -e "$root/meta/warm-aa-exit.txt"; then
        test "$manifests" = \
          "$(printf '%s\n' baseline-a.json baseline-b.json candidate.json)" || return 1
      fi
      ;;
    aa-captured)
      validate_command_prefix "$root" \
        'cold-aa.json warm-aa.json' 'cold-aa-exit.txt warm-aa-exit.txt' \
        'comparison-aa-cold.json,comparison-aa-cold.md:comparison-aa-cold-exit.txt' \
        'comparison-aa-warm.json,comparison-aa-warm.md:comparison-aa-warm-exit.txt'
      ;;
    aa-compared)
      validate_command_prefix "$root" \
        'cold-aa.json warm-aa.json comparison-aa-cold.json comparison-aa-cold.md comparison-aa-warm.json comparison-aa-warm.md' \
        'cold-aa-exit.txt warm-aa-exit.txt comparison-aa-cold-exit.txt comparison-aa-warm-exit.txt' \
        'cold-candidate.json:cold-candidate-exit.txt' \
        'warm-candidate.json:warm-candidate-exit.txt' \
        'retained-candidate.json:retained-candidate-exit.txt'
      ;;
    candidate-captured)
      validate_command_prefix "$root" \
        'cold-aa.json warm-aa.json comparison-aa-cold.json comparison-aa-cold.md comparison-aa-warm.json comparison-aa-warm.md cold-candidate.json warm-candidate.json retained-candidate.json' \
        'cold-aa-exit.txt warm-aa-exit.txt comparison-aa-cold-exit.txt comparison-aa-warm-exit.txt cold-candidate-exit.txt warm-candidate-exit.txt retained-candidate-exit.txt' \
        'comparison-candidate-cold.json,comparison-candidate-cold.md:comparison-candidate-cold-exit.txt' \
        'comparison-candidate-warm.json,comparison-candidate-warm.md:comparison-candidate-warm-exit.txt' \
        'comparison-candidate-retained.json,comparison-candidate-retained.md:comparison-candidate-retained-exit.txt'
      ;;
    candidate-compared)
      validate_command_prefix "$root" \
        'cold-aa.json warm-aa.json comparison-aa-cold.json comparison-aa-cold.md comparison-aa-warm.json comparison-aa-warm.md cold-candidate.json warm-candidate.json retained-candidate.json comparison-candidate-cold.json comparison-candidate-cold.md comparison-candidate-warm.json comparison-candidate-warm.md comparison-candidate-retained.json comparison-candidate-retained.md' \
        'cold-aa-exit.txt warm-aa-exit.txt comparison-aa-cold-exit.txt comparison-aa-warm-exit.txt cold-candidate-exit.txt warm-candidate-exit.txt retained-candidate-exit.txt comparison-candidate-cold-exit.txt comparison-candidate-warm-exit.txt comparison-candidate-retained-exit.txt'
      ;;
  esac
}

validate_sha_inventory() {
  local root=$1 inventory=$2 allowed_regex=$3 verify_bytes=${4:-false}
  test -f "$root/$inventory" || return 1
  awk -v allowed="$allowed_regex" '
    {
      hash = substr($0, 1, 64)
      separator = substr($0, 65, 2)
      path = substr($0, 67)
      if (length(hash) != 64 || hash !~ /^[0-9a-f]+$/ || separator != "  " ||
          path !~ allowed || path ~ /(^|\/)\.\.(\/|$)/ || path ~ /^\// || seen[path]++) exit 1
    }
    END { if (NR == 0) exit 1 }
  ' "$root/$inventory" || return 1
  if test "$verify_bytes" = true; then
    (cd "$root" && sha256sum -c "$inventory") || return 1
  fi
}

validate_artifact_inventories() {
  local root=$1 inventory hashes inventory_paths hash_paths
  local inventory_count hash_count
  inventory="$root/meta/artifact-inventory.tsv"
  hashes="$root/meta/artifact-sha256sums.txt"
  test -f "$inventory" && test -f "$hashes" || return 1
  awk -F '\t' '
    NF != 2 { exit 1 }
    $1 !~ /^artifacts\/[A-Za-z0-9._\/-]+$/ || $1 ~ /(^|\/)\.\.(\/|$)/ || $1 ~ /^\// { exit 1 }
    $2 !~ /^[0-9]+$/ || seen[$1]++ { exit 1 }
    END { if (NR == 0) exit 1 }
  ' "$inventory" || return 1
  validate_sha_inventory "$root" meta/artifact-sha256sums.txt \
    '^artifacts/[A-Za-z0-9._/-]+$' false || return 1
  inventory_paths=$(awk -F '\t' '{print $1}' "$inventory" | LC_ALL=C sort)
  hash_paths=$(awk '{print substr($0, 67)}' "$hashes" | LC_ALL=C sort)
  test "$inventory_paths" = "$hash_paths" || return 1
  inventory_count=$(wc -l <"$inventory" | tr -d ' ')
  hash_count=$(wc -l <"$hashes" | tr -d ' ')
  test "$inventory_count" = "$hash_count"
}

validate_manifest_copy() {
  local path=$1 expected_id=$2 expected_commit=$3
  test -f "$path" || return 1
  jq -e -f "$VALIDATOR" "$path" >/dev/null || return 1
  jq -e --arg id "$expected_id" --arg commit "$expected_commit" \
    '.targetId == $id and .gitCommit == $commit and .dirty == false' "$path" >/dev/null
}

validate_manifest_set() {
  local root=$1 implementation=$2 names
  test -d "$root/manifests" || return 1
  names=$(find "$root/manifests" -type f -maxdepth 1 -print \
    | sed 's#^.*/##' | LC_ALL=C sort)
  test "$names" = "$(printf '%s\n' baseline-a.json baseline-b.json candidate.json)" || return 1
  validate_manifest_copy "$root/manifests/baseline-a.json" baseline-a-cs2a "$BASELINE_SHA" || return 1
  validate_manifest_copy "$root/manifests/baseline-b.json" baseline-b-cs2a "$BASELINE_SHA" || return 1
  validate_manifest_copy "$root/manifests/candidate.json" candidate-cs2a "$implementation"
}

manifest_hash() {
  sha256_of "$1"
}

validate_campaign_identity() {
  local root=$1 result=$2 mode=$3 candidate_id=$4 candidate_adapter=$5 candidate_commit=$6
  local implementation=$7 baseline_hash candidate_hash candidate_manifest implementation_tree
  local metric_passes blocks warmups iterations expected_series maximum_replacements
  test -f "$root/$result" || return 1
  baseline_hash=$(manifest_hash "$root/manifests/baseline-a.json") || return 1
  case "$candidate_id" in
    baseline-b-cs2a) candidate_manifest="$root/manifests/baseline-b.json" ;;
    candidate-cs2a) candidate_manifest="$root/manifests/candidate.json" ;;
    *) return 1 ;;
  esac
  candidate_hash=$(manifest_hash "$candidate_manifest") || return 1
  implementation_tree=$(git rev-parse "$implementation^{tree}") || return 1
  maximum_replacements=$(jq -er '.maximumReplacementBlocks' \
    "$root/meta/controlled-host.json") || return 1
  case "$mode" in
    COLD)
      metric_passes='["LATENCY","ALLOCATION","PEAK_RSS"]'
      blocks=50; warmups=0; iterations=1
      expected_series='[
        {"metric":"LATENCY","provider":"parent-process-wall-time/v1","unit":"NANOSECONDS"},
        {"metric":"ALLOCATED_BYTES","provider":"jdk21-jfr-tlab-reserved-plus-outside/v1","unit":"BYTES"},
        {"metric":"PEAK_RSS","provider":"gnu-time-v-maximum-resident-set-kib/v1","unit":"BYTES"}
      ]'
      ;;
    WARM)
      metric_passes='["LATENCY","ALLOCATION"]'
      blocks=5; warmups=20; iterations=100
      expected_series='[
        {"metric":"LATENCY","provider":"target-nano-time/v1","unit":"NANOSECONDS"},
        {"metric":"ALLOCATED_BYTES","provider":"jmh:gc.alloc.rate.norm:com.salesforce.revoman.benchmark.WarmLifecycleAllocationBenchmark.execute","unit":"BYTES_PER_OPERATION"}
      ]'
      ;;
    RETAINED)
      metric_passes='["RETAINED"]'
      blocks=5; warmups=0; iterations=0
      expected_series='[
        {"metric":"RETAINED_BYTES","provider":"revoman-retained-two-phase-weak-proof-final-heap/v2","unit":"BYTES"}
      ]'
      ;;
    *) return 1 ;;
  esac
  jq -e \
    --arg mode "$mode" \
    --arg policy 48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60 \
    --arg host 12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44 \
    --arg baselineCommit "$BASELINE_SHA" \
    --arg baselineManifest "$baseline_hash" \
    --arg candidateId "$candidate_id" \
    --arg candidateAdapter "$candidate_adapter" \
    --arg candidateCommit "$candidate_commit" \
    --arg candidateManifest "$candidate_hash" \
    --arg implementation "$implementation" \
    --arg implementationTree "$implementation_tree" \
    --arg javaHome /home/gopala.akshintala/core-public/tools/Linux/jdk/sfdc-jdk-zulu-21.helium_x64 \
    --arg jvmFlag -Dsun.net.httpserver.nodelay=true \
    --arg workload lifecycle.no-script-one-step.v1 \
    --arg fixture 31af0229163ef1ed544189f9b1f1dbd9a80607ffd024a2e5bd09cddfae919c92 \
    --argjson metricPasses "$metric_passes" \
    --argjson expectedSeries "$expected_series" \
    --argjson blocks "$blocks" \
    --argjson maximumReplacements "$maximum_replacements" \
    --argjson warmups "$warmups" \
    --argjson iterations "$iterations" '
      .schema == "revoman-benchmark/v1" and .intent == "CONTROLLED" and
      .configuration.mode == $mode and .configuration.metricPasses == $metricPasses and
      .configuration.seed == 5928239383101656625 and
      .configuration.requestedAcceptedBlocks == $blocks and
      .configuration.forksPerBlock == 1 and
      .configuration.warmupIterations == $warmups and
      .configuration.measurementIterations == $iterations and
      .environment.policySha256 == $policy and
      .environment.hostFingerprintSha256 == $host and
      .environment.governor == "performance" and
      .environment.jdk.javaHome == $javaHome and
      .environment.jdk.jvmFlags == [$jvmFlag] and
      .harness.commit == $implementation and .harness.tree == $implementationTree and
      .harness.dirty == false and
      (.harness.distributionSha256 | test("^[0-9a-f]{64}$")) and
      (.harness.artifacts | length > 0) and
      (.harness.adapters | map(.id)) == ["baseline-83f3cd70","major-v1"] and
      .configuration.targets == [
        {"role":"BASELINE","targetId":"baseline-a-cs2a","adapterId":"baseline-83f3cd70"},
        {"role":"CANDIDATE","targetId":$candidateId,"adapterId":$candidateAdapter}
      ] and
      (.targets | length == 2) and
      (.targets | map(.id)) == ["baseline-a-cs2a",$candidateId] and
      ([.targets[] | select(
        .id == "baseline-a-cs2a" and .gitCommit == $baselineCommit and
        .dirty == false and .manifestSha256 == $baselineManifest and
        .adapter.id == "baseline-83f3cd70"
      )] | length == 1) and
      ([.targets[] | select(
        .id == $candidateId and .gitCommit == $candidateCommit and
        .dirty == false and .manifestSha256 == $candidateManifest and
        .adapter.id == $candidateAdapter
      )] | length == 1) and
      (.workloads | length == 1) and
      .workloads[0].id == $workload and
      .workloads[0].contractSha256 == .harness.workloadContractSha256 and
      .workloads[0].fixtureSha256 == $fixture and .workloads[0].mode == $mode and
      ([.workloads[0].metricSeries[] | {metric,provider,unit}] == $expectedSeries) and
      all(.workloads[0].metricSeries[];
        (.blocks | length) as $attempted |
        $attempted >= $blocks and $attempted <= ($blocks + $maximumReplacements) and
        ([.blocks[] | select(.accepted)] | length) == $blocks and
        ([.blocks[].blockId] == [range(0; $attempted)]) and
        all(.blocks[];
          ((.targetOrder == ["baseline-a-cs2a",$candidateId]) or
           (.targetOrder == [$candidateId,"baseline-a-cs2a"])) and
          all(.observations[]; .fork == 0)
        ) and
        (([.blocks[] | select(.accepted and
          .targetOrder[0] == "baseline-a-cs2a")] | length) as $baselineFirst |
         ([.blocks[] | select(.accepted and
          .targetOrder[0] == $candidateId)] | length) as $candidateFirst |
         (($baselineFirst - $candidateFirst) | fabs) <= 1)
      )
    ' "$root/$result" >/dev/null
}

validate_target_projection() {
  local result=$1 target_id=$2 manifest=$3 adapter_id=$4
  jq -e --arg targetId "$target_id" --arg adapterId "$adapter_id" \
    --slurpfile manifest "$manifest" '
      ($manifest[0]) as $m |
      [.targets[] | select(.id == $targetId)] as $matches |
      ($matches | length) == 1 and
      ($matches[0].gitCommit == $m.gitCommit and
       $matches[0].gitTree == $m.gitTree and
       $matches[0].dirty == $m.dirty and
       $matches[0].gradleVersion == $m.gradleVersion and
       $matches[0].wrapperSha256 == $m.wrapperSha256 and
       $matches[0].buildJdk == $m.jdk and
       $matches[0].classpath == [$m.classpath[] | {logicalId,sizeBytes,sha256}] and
       $matches[0].adapter.id == $adapterId and
       $matches[0].adapter == ([.harness.adapters[] | select(.id == $adapterId)][0]))
    ' "$result" >/dev/null
}

validate_campaign_artifact_shape() {
  local root=$1 result=$2 mode=$3 artifact_set=$4 run_root
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  case "$mode" in
    COLD)
      jq -e --arg runRoot "$run_root" --arg artifactSet "$artifact_set" '
        (.workloads[0].metricSeries[] | select(.metric == "ALLOCATED_BYTES")) as $allocation |
        ([ $allocation.blocks[].blockId as $block |
           ["baseline","candidate"][] as $role |
           "cold-allocation-block-\($block)-role-\($role)-fork-0.jfr" ] | sort) as $expected |
        ($allocation.artifacts | map(.logicalId) | sort) == $expected and
        all(.workloads[0].metricSeries[] | select(.metric != "ALLOCATED_BYTES");
          .artifacts == []) and
        all($allocation.artifacts[];
          .logicalId as $logical |
          ($logical | capture("^cold-allocation-block-(?<block>[0-9]+)-role-(?<role>baseline|candidate)-fork-0\\.jfr$")) as $identity |
          .executionPath == ($runRoot + "/artifacts/" + $artifactSet +
            "/pass-allocation/block-" + $identity.block + "/role-" + $identity.role +
            "/fork-0/" + $logical))
      ' "$root/$result" >/dev/null
      ;;
    WARM)
      jq -e --arg runRoot "$run_root" --arg artifactSet "$artifact_set" '
        (.workloads[0].metricSeries[] | select(.metric == "ALLOCATED_BYTES")) as $allocation |
        ([ $allocation.blocks[].blockId as $block |
           ["baseline","candidate"][] as $role |
           ["raw.json","normalized.json","output.txt"][] as $suffix |
           "warm-allocation-block-\($block)-role-\($role)-fork-0-\($suffix)" ] | sort) as $expected |
        ($allocation.artifacts | map(.logicalId) | sort) == $expected and
        all(.workloads[0].metricSeries[] | select(.metric != "ALLOCATED_BYTES");
          .artifacts == []) and
        all($allocation.artifacts[];
          .logicalId as $logical |
          ($logical | capture("^warm-allocation-block-(?<block>[0-9]+)-role-(?<role>baseline|candidate)-fork-0-(?<suffix>raw\\.json|normalized\\.json|output\\.txt)$")) as $identity |
          (if $identity.suffix == "raw.json" then "jmh-raw.json"
           elif $identity.suffix == "normalized.json" then "revoman-benchmark-jmh-v1.json"
           else "jmh-output.txt" end) as $file |
          .executionPath == ($runRoot + "/artifacts/" + $artifactSet +
            "/pass-allocation/block-" + $identity.block + "/role-" + $identity.role +
            "/fork-0/" + $file))
      ' "$root/$result" >/dev/null
      ;;
    RETAINED)
      jq -e 'all(.workloads[0].metricSeries[]; .artifacts == [])' \
        "$root/$result" >/dev/null
      ;;
    *) return 1 ;;
  esac
}

validate_campaign_set_identity() {
  local root=$1 first result selector
  first=$root/results/cold-aa.json
  for result in warm-aa.json cold-candidate.json warm-candidate.json retained-candidate.json; do
    for selector in .harness .environment '.targets[] | select(.id == "baseline-a-cs2a")'; do
      test "$(jq -Sc "$selector" "$first")" = \
        "$(jq -Sc "$selector" "$root/results/$result")" || return 1
    done
  done
  test "$(jq -Sc '.targets[] | select(.id == "baseline-b-cs2a")' \
    "$root/results/cold-aa.json")" = \
    "$(jq -Sc '.targets[] | select(.id == "baseline-b-cs2a")' \
      "$root/results/warm-aa.json")" || return 1
  first=$root/results/cold-candidate.json
  for result in warm-candidate.json retained-candidate.json; do
    test "$(jq -Sc '.targets[] | select(.id == "candidate-cs2a")' "$first")" = \
      "$(jq -Sc '.targets[] | select(.id == "candidate-cs2a")' \
        "$root/results/$result")" || return 1
  done
}

validate_campaign_artifact_inventory() {
  local root=$1 run_root inventory hashes result execution size hash relative
  local result_paths expected_paths inventory_paths parent
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  inventory="$root/meta/artifact-inventory.tsv"
  hashes="$root/meta/artifact-sha256sums.txt"
  result_paths=$(mktemp "${TMPDIR:-/tmp}/cs2a-result-artifacts.XXXXXXXX") || return 1
  expected_paths=$(mktemp "${TMPDIR:-/tmp}/cs2a-expected-artifacts.XXXXXXXX") \
    || { rm -f -- "$result_paths"; return 1; }
  : >"$result_paths"; : >"$expected_paths"
  for result in cold-aa.json warm-aa.json cold-candidate.json warm-candidate.json \
    retained-candidate.json; do
    while IFS="$(printf '\t')" read -r execution size hash; do
      test -n "$execution" || continue
      case "$execution" in "$run_root"/artifacts/*) ;; *) rm -f -- "$result_paths" "$expected_paths"; return 1 ;; esac
      relative=${execution#"$run_root/"}
      printf '%s\n' "$relative" >>"$result_paths"
      printf '%s\n' "$relative" >>"$expected_paths"
      awk -F '\t' -v path="$relative" -v bytes="$size" \
        '$1 == path && $2 == bytes { count++ } END { exit count != 1 }' \
        "$inventory" || { rm -f -- "$result_paths" "$expected_paths"; return 1; }
      awk -v path="$relative" -v digest="$hash" \
        'substr($0,1,64) == digest && substr($0,67) == path { count++ }
         END { exit count != 1 }' "$hashes" \
        || { rm -f -- "$result_paths" "$expected_paths"; return 1; }
      case "$result" in
        warm-*)
          parent=${relative%/*}
          printf '%s\n' "$parent/target-verification-token.json" \
            "$parent/campaign-jmh-context.json" >>"$expected_paths"
          ;;
      esac
    done < <(jq -r '.workloads[].metricSeries[].artifacts[] |
      [.executionPath, (.sizeBytes|tostring), .sha256] | @tsv' "$root/results/$result")
  done
  test "$(LC_ALL=C sort "$result_paths" | wc -l | tr -d ' ')" = \
    "$(LC_ALL=C sort -u "$result_paths" | wc -l | tr -d ' ')" \
    || { rm -f -- "$result_paths" "$expected_paths"; return 1; }
  inventory_paths=$(awk -F '\t' '{print $1}' "$inventory" | LC_ALL=C sort) || return 1
  test "$inventory_paths" = "$(LC_ALL=C sort -u "$expected_paths")" \
    || { rm -f -- "$result_paths" "$expected_paths"; return 1; }
  rm -f -- "$result_paths" "$expected_paths"
}

validate_commands_bijection() {
  local root=$1 commands label command_rows log_rows suffix
  commands="$root/meta/commands.tsv"
  test -f "$commands" || return 1
  awk -F '\t' '
    NF < 2 || $1 !~ /^[a-z0-9][a-z0-9.-]*$/ || seen[$1]++ { exit 1 }
    END { if (NR == 0) exit 1 }
  ' "$commands" || return 1
  command_rows=$(awk -F '\t' '{print $1}' "$commands" | LC_ALL=C sort)
  for suffix in stdout stderr exit; do
    log_rows=$(find "$root/logs" -maxdepth 1 -type f -name "*.$suffix" -print \
      | sed "s#^.*/##; s/\\.$suffix\$//" | LC_ALL=C sort) || return 1
    test "$command_rows" = "$log_rows" || return 1
  done
  test -z "$(find "$root/logs" -maxdepth 1 -type f \
    ! -name '*.stdout' ! -name '*.stderr' ! -name '*.exit' -print -quit)" || return 1
  while IFS="$(printf '\t')" read -r label _; do
    test -f "$root/logs/$label.stdout" || return 1
    test -f "$root/logs/$label.stderr" || return 1
    test -f "$root/logs/$label.exit" || return 1
    [[ "$(cat "$root/logs/$label.exit")" =~ ^[0-9]+$ ]] || return 1
  done <"$commands"
}

append_expected_command() {
  local destination=$1 label=$2
  shift 2
  {
    printf '%s' "$label"
    printf '\t%q' "$@"
    printf '\n'
  } >>"$destination"
}

append_expected_campaign() {
  local destination=$1 label=$2 subcommand=$3 mode=$4 candidate_manifest=$5
  local candidate_adapter=$6 blocks=$7 warmups=$8 iterations=$9 metrics=${10}
  local artifact_name=${11} run_root=${12} driver=${13} policy=${14}
  append_expected_command "$destination" "$label" "$driver" "$subcommand" \
    --mode "$mode" --intent controlled \
    --baseline "$run_root/manifests/baseline-a.json" \
    --baseline-adapter baseline-83f3cd70 \
    --candidate "$run_root/manifests/$candidate_manifest" \
    --candidate-adapter "$candidate_adapter" \
    --workload lifecycle.no-script-one-step.v1 --blocks "$blocks" \
    --forks-per-block 1 --warmups "$warmups" --iterations "$iterations" \
    --seed 5928239383101656625 --metrics "$metrics" --host-policy "$policy" \
    --artifacts-dir "$run_root/artifacts/$artifact_name" \
    --output "$run_root/results/$label.json"
}

append_expected_verify_and_compare() {
  local destination=$1 verify_label=$2 result_label=$3 comparison_label=$4
  local run_root=$5 driver=$6
  append_expected_command "$destination" "verify-$verify_label" \
    "$driver" verify --input "$run_root/results/$result_label.json"
  append_expected_command "$destination" "$comparison_label" \
    "$driver" compare --input "$run_root/results/$result_label.json" \
    --output-json "$run_root/results/$comparison_label.json" \
    --output-md "$run_root/results/$comparison_label.md" --enforce-release-gates
}

append_expected_smoke_campaign() {
  local destination=$1 label=$2 mode=$3 candidate_manifest=$4 candidate_adapter=$5
  local warmups=$6 iterations=$7 artifact_name=$8 run_root=$9 driver=${10} policy=${11}
  append_expected_command "$destination" "$label" "$driver" run-paired \
    --mode "$mode" --intent smoke \
    --baseline "$run_root/manifests/baseline-a.json" \
    --baseline-adapter baseline-83f3cd70 \
    --candidate "$run_root/manifests/$candidate_manifest" \
    --candidate-adapter "$candidate_adapter" \
    --workload lifecycle.no-script-one-step.v1 --blocks 2 \
    --forks-per-block 1 --warmups "$warmups" --iterations "$iterations" \
    --seed 5928239383101656625 --metrics latency --host-policy "$policy" \
    --artifacts-dir "$run_root/artifacts/$artifact_name" \
    --output "$run_root/results/$label.json"
}

append_expected_smoke_verify_and_compare() {
  local destination=$1 verify_label=$2 result_label=$3 comparison_label=$4
  local run_root=$5 driver=$6
  append_expected_command "$destination" "verify-$verify_label" \
    "$driver" verify --input "$run_root/results/$result_label.json"
  append_expected_command "$destination" "$comparison_label" \
    "$driver" compare --input "$run_root/results/$result_label.json" \
    --output-json "$run_root/results/$comparison_label.json" \
    --output-md "$run_root/results/$comparison_label.md"
}

write_expected_smoke_command_protocol() {
  local root=$1 destination=$2 run_root harness baseline_a baseline_b candidate
  local driver init validator policy manifest name checkout target_id
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  harness="$run_root/checkouts/harness"
  baseline_a="$run_root/checkouts/baseline-a"
  baseline_b="$run_root/checkouts/baseline-b"
  candidate="$run_root/checkouts/candidate"
  driver="$harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
  init="$harness/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts"
  validator="$harness/docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq"
  policy=/opt/revoman-benchmark/controlled-host.json
  : >"$destination" || return 1
  append_expected_command "$destination" install-harness "$harness/gradlew" \
    -p "$harness" :benchmark-driver:installDist --no-daemon --console=plain
  for name in baseline-a baseline-b candidate; do
    case "$name" in
      baseline-a) checkout=$baseline_a; target_id=baseline-a-cs2a ;;
      baseline-b) checkout=$baseline_b; target_id=baseline-b-cs2a ;;
      candidate) checkout=$candidate; target_id='candidate-cs2a' ;;
    esac
    manifest="$run_root/manifests/$name.json"
    append_expected_command "$destination" "export-$name" "$checkout/gradlew" \
      -p "$checkout" -I "$init" clean writeBenchmarkTargetManifest \
      "-Pbenchmark.targetManifest=$manifest" "-Pbenchmark.targetId=$target_id" \
      --no-daemon --console=plain
  done
  for name in baseline-a baseline-b candidate; do
    append_expected_command "$destination" "verify-manifest-$name" \
      jq -e -f "$validator" "$run_root/manifests/$name.json"
  done
  append_expected_smoke_campaign "$destination" cold-aa cold baseline-b.json \
    baseline-83f3cd70 0 1 cold-aa "$run_root" "$driver" "$policy"
  append_expected_smoke_campaign "$destination" warm-aa warm baseline-b.json \
    baseline-83f3cd70 1 3 warm-aa "$run_root" "$driver" "$policy"
  append_expected_smoke_campaign "$destination" cold-candidate cold candidate.json \
    major-v1 0 1 cold-candidate "$run_root" "$driver" "$policy"
  append_expected_smoke_campaign "$destination" warm-candidate warm candidate.json \
    major-v1 1 3 warm-candidate "$run_root" "$driver" "$policy"
  append_expected_smoke_verify_and_compare "$destination" aa-cold cold-aa \
    comparison-aa-cold "$run_root" "$driver"
  append_expected_smoke_verify_and_compare "$destination" aa-warm warm-aa \
    comparison-aa-warm "$run_root" "$driver"
  append_expected_smoke_verify_and_compare "$destination" candidate-cold cold-candidate \
    comparison-candidate-cold "$run_root" "$driver"
  append_expected_smoke_verify_and_compare "$destination" candidate-warm warm-candidate \
    comparison-candidate-warm "$run_root" "$driver"
  test "$(wc -l <"$destination" | tr -d ' ')" = 19
}

write_expected_command_protocol() {
  local root=$1 destination=$2 run_root harness baseline_a baseline_b candidate
  local driver init validator policy manifest name checkout target_id
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  harness="$run_root/checkouts/harness"
  baseline_a="$run_root/checkouts/baseline-a"
  baseline_b="$run_root/checkouts/baseline-b"
  candidate="$run_root/checkouts/candidate"
  driver="$harness/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
  init="$harness/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts"
  validator="$harness/docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq"
  policy=/opt/revoman-benchmark/controlled-host.json
  : >"$destination" || return 1
  append_expected_command "$destination" install-harness "$harness/gradlew" \
    -p "$harness" :benchmark-driver:installDist --no-daemon --console=plain
  for name in baseline-a baseline-b candidate; do
    case "$name" in
      baseline-a) checkout=$baseline_a; target_id=baseline-a-cs2a ;;
      baseline-b) checkout=$baseline_b; target_id=baseline-b-cs2a ;;
      candidate) checkout=$candidate; target_id='candidate-cs2a' ;;
    esac
    manifest="$run_root/manifests/$name.json"
    append_expected_command "$destination" "export-$name" "$checkout/gradlew" \
      -p "$checkout" -I "$init" clean writeBenchmarkTargetManifest \
      "-Pbenchmark.targetManifest=$manifest" "-Pbenchmark.targetId=$target_id" \
      --no-daemon --console=plain
  done
  for name in baseline-a baseline-b candidate; do
    append_expected_command "$destination" "verify-manifest-$name" \
      jq -e -f "$validator" "$run_root/manifests/$name.json"
  done
  append_expected_campaign "$destination" cold-aa capture-baseline cold baseline-b.json \
    baseline-83f3cd70 50 0 1 latency,peak-rss,allocation cold-aa \
    "$run_root" "$driver" "$policy"
  append_expected_campaign "$destination" warm-aa capture-baseline warm baseline-b.json \
    baseline-83f3cd70 5 20 100 latency,allocation warm-aa \
    "$run_root" "$driver" "$policy"
  append_expected_verify_and_compare "$destination" aa-cold cold-aa comparison-aa-cold \
    "$run_root" "$driver"
  append_expected_verify_and_compare "$destination" aa-warm warm-aa comparison-aa-warm \
    "$run_root" "$driver"
  append_expected_campaign "$destination" cold-candidate run-paired cold candidate.json \
    major-v1 50 0 1 latency,peak-rss,allocation cold-candidate \
    "$run_root" "$driver" "$policy"
  append_expected_campaign "$destination" warm-candidate run-paired warm candidate.json \
    major-v1 5 20 100 latency,allocation warm-candidate \
    "$run_root" "$driver" "$policy"
  append_expected_campaign "$destination" retained-candidate run-paired retained candidate.json \
    major-v1 5 0 0 retained retained-candidate "$run_root" "$driver" "$policy"
  append_expected_verify_and_compare "$destination" candidate-cold cold-candidate \
    comparison-candidate-cold "$run_root" "$driver"
  append_expected_verify_and_compare "$destination" candidate-warm warm-candidate \
    comparison-candidate-warm "$run_root" "$driver"
  append_expected_verify_and_compare "$destination" candidate-retained retained-candidate \
    comparison-candidate-retained "$run_root" "$driver"
  test "$(wc -l <"$destination" | tr -d ' ')" = 22
}

validate_command_protocol() {
  local root=$1 commands stage actual_count expected minimum maximum
  commands="$root/meta/commands.tsv"
  stage=$(tr -d '\r\n' <"$root/meta/stage.txt") || return 1
  test -f "$commands" && test ! -L "$commands" || return 1
  expected=$(mktemp "${TMPDIR:-/tmp}/cs2a-command-protocol.XXXXXXXX") || return 1
  if ! write_expected_command_protocol "$root" "$expected"; then
    rm -f -- "$expected"
    return 1
  fi
  actual_count=$(wc -l <"$commands" | tr -d ' ') || return 1
  case "$stage" in
    setup) minimum=0; maximum=9 ;;
    aa-captured) minimum=9; maximum=13 ;;
    aa-compared) minimum=13; maximum=16 ;;
    candidate-captured) minimum=16; maximum=22 ;;
    candidate-compared) minimum=22; maximum=22 ;;
    *) rm -f -- "$expected"; return 1 ;;
  esac
  if test "$actual_count" -lt "$minimum" || test "$actual_count" -gt "$maximum"; then
    rm -f -- "$expected"
    return 1
  fi
  if test "$actual_count" -eq 0; then
    test ! -s "$commands" || { rm -f -- "$expected"; return 1; }
  elif ! sed -n "1,${actual_count}p" "$expected" | cmp -s - "$commands"; then
    rm -f -- "$expected"
    return 1
  fi
  rm -f -- "$expected"
}

validate_smoke_artifact_inventories() {
  local root=$1 inventory hashes
  inventory="$root/meta/artifact-inventory.tsv"
  hashes="$root/meta/artifact-sha256sums.txt"
  test -f "$inventory" && test ! -L "$inventory" \
    && test -f "$hashes" && test ! -L "$hashes" || return 1
  if test ! -s "$inventory" && test ! -s "$hashes"; then return 0; fi
  validate_artifact_inventories "$root"
}

validate_smoke_campaign_identity() {
  local root=$1 result=$2 mode=$3 candidate_id=$4 candidate_adapter=$5
  local candidate_commit=$6 implementation=$7 warmups=$8 iterations=$9
  local candidate_manifest baseline_hash candidate_hash
  case "$candidate_id" in
    baseline-b-cs2a) candidate_manifest="$root/manifests/baseline-b.json" ;;
    candidate-cs2a) candidate_manifest="$root/manifests/candidate.json" ;;
    *) return 1 ;;
  esac
  baseline_hash=$(sha256_of "$root/manifests/baseline-a.json") || return 1
  candidate_hash=$(sha256_of "$candidate_manifest") || return 1
  jq -e --arg mode "$mode" --arg candidateId "$candidate_id" \
    --arg candidateAdapter "$candidate_adapter" --arg candidateCommit "$candidate_commit" \
    --arg implementation "$implementation" --arg baselineHash "$baseline_hash" \
    --arg candidateHash "$candidate_hash" --arg policy "$EXPECTED_POLICY_SEMANTIC_SHA256" \
    --arg host "$EXPECTED_HOST_FINGERPRINT" --argjson warmups "$warmups" \
    --argjson iterations "$iterations" '
      .schema == "revoman-benchmark/v1" and .intent == "SMOKE" and
      .configuration.mode == $mode and .configuration.metricPasses == ["LATENCY"] and
      .configuration.seed == 5928239383101656625 and
      .configuration.requestedAcceptedBlocks == 2 and
      .configuration.forksPerBlock == 1 and
      .configuration.warmupIterations == $warmups and
      .configuration.measurementIterations == $iterations and
      .configuration.targets == [
        {"role":"BASELINE","targetId":"baseline-a-cs2a","adapterId":"baseline-83f3cd70"},
        {"role":"CANDIDATE","targetId":$candidateId,"adapterId":$candidateAdapter}
      ] and
      .harness.commit == $implementation and .harness.dirty == false and
      .environment.policySha256 == $policy and
      .environment.hostFingerprintSha256 == $host and
      (.targets | length) == 2 and
      (.targets[] | select(.id == "baseline-a-cs2a") |
        .gitCommit == "83f3cd70f78ad733412d10cbc8287aaabafe7aac" and
        .manifestSha256 == $baselineHash and .adapter.id == "baseline-83f3cd70") and
      (.targets[] | select(.id == $candidateId) |
        .gitCommit == $candidateCommit and .manifestSha256 == $candidateHash and
        .adapter.id == $candidateAdapter) and
      (.workloads | length) == 1 and
      .workloads[0].id == "lifecycle.no-script-one-step.v1" and
      .workloads[0].mode == $mode and
      (.workloads[0].metricSeries | map(.metric)) == ["LATENCY"] and
      ([.workloads[0].metricSeries[0].blocks[] | select(.accepted == true)] | length) == 2
    ' "$root/$result" >/dev/null
}

validate_smoke_terminal_crosslinks() {
  local root=$1 run_root child supervisor_exit runner_exit post supervisor_post capture
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/run-root.txt")" = "$run_root" || return 1
  child=$(tr -d '\r\n' <"$root/meta/supervisor/child-or-supervisor-status.txt") || return 1
  supervisor_exit=$(tr -d '\r\n' <"$root/meta/operator-supervisor-exit.txt") || return 1
  runner_exit=$(tr -d '\r\n' <"$root/meta/runner-exit.txt") || return 1
  post=$(tr -d '\r\n' <"$root/meta/operator-post-supervisor-exit.txt") || return 1
  supervisor_post=$(tr -d '\r\n' \
    <"$root/meta/supervisor/operator-post-supervisor-exit.txt") || return 1
  test "$child" = 0 && test "$supervisor_exit" = 0 && test "$runner_exit" = 0 || return 1
  test "$post" = 0 && test "$supervisor_post" = 0 || return 1
  test "$(tr -d '\r\n' <"$root/meta/operator-resume-validation-exit.txt")" = 0 \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/inventory-exit.txt")" = 0 || return 1
  for capture in cold-aa warm-aa cold-candidate warm-candidate; do
    test "$(tr -d '\r\n' <"$root/meta/$capture-exit.txt")" = 0 || return 1
  done
  test "$(tr -d '\r\n' <"$root/meta/supervisor/restoration-failed.txt")" = false \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/containment-failed.txt")" = false \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/lock-released.txt")" = true || return 1
  [[ "$(tr -d '\r\n' <"$root/meta/supervisor/lock-provenance.txt")" \
    =~ ^0:0:600:[0-9]+:[0-9]+$ ]] || return 1
  cmp -s "$root/meta/supervisor/original-governors.tsv" \
    "$root/meta/supervisor/restored-governors.tsv"
}

validate_smoke_archive() {
  local root=$1 implementation=$2 driver=$3
  local expected_policy_sha=${4:-$EXPECTED_POLICY_SHA256}
  local expected actual_manifests actual_results expected_results status_file log_exit result
  case "$#" in 3 | 4) ;; *) return 1 ;; esac
  test -d "$root" && require_sha "$implementation" && test -x "$driver" || return 1
  validate_archive_safety "$root" || return 1
  test -f "$root/meta/profile.txt" && test ! -L "$root/meta/profile.txt" || return 1
  test "$(tr -d '\r\n' <"$root/meta/profile.txt")" = smoke || return 1
  test "$(tr -d '\r\n' <"$root/meta/stage.txt")" = smoke-compared || return 1
  test "$(tr -d '\r\n' <"$root/meta/runner-exit.txt")" = 0 || return 1
  test "$(tr -d '\r\n' <"$root/meta/inventory-exit.txt")" = 0 || return 1
  expected=$(mktemp "${TMPDIR:-/tmp}/cs2a-smoke-command-protocol.XXXXXXXX") || return 1
  if ! write_expected_smoke_command_protocol "$root" "$expected" \
    || ! cmp -s "$expected" "$root/meta/commands.tsv"; then
    rm -f -- "$expected"
    return 1
  fi
  rm -f -- "$expected"
  validate_commands_bijection "$root" || return 1
  actual_manifests=$(find "$root/manifests" -mindepth 1 -maxdepth 1 -type f -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  test "$actual_manifests" = \
    "$(printf '%s\n' baseline-a.json baseline-b.json candidate.json)" || return 1
  expected_results=$(printf '%s\n' \
    cold-aa.json warm-aa.json cold-candidate.json warm-candidate.json \
    comparison-aa-cold.json comparison-aa-cold.md \
    comparison-aa-warm.json comparison-aa-warm.md \
    comparison-candidate-cold.json comparison-candidate-cold.md \
    comparison-candidate-warm.json comparison-candidate-warm.md | LC_ALL=C sort)
  actual_results=$(find "$root/results" -mindepth 1 -maxdepth 1 -type f -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  test "$actual_results" = "$expected_results" || return 1
  for result in cold-aa.json warm-aa.json cold-candidate.json warm-candidate.json \
    comparison-aa-cold.json comparison-aa-cold.md \
    comparison-aa-warm.json comparison-aa-warm.md \
    comparison-candidate-cold.json comparison-candidate-cold.md \
    comparison-candidate-warm.json comparison-candidate-warm.md; do
    test -s "$root/results/$result" || return 1
  done
  for status_file in cold-aa warm-aa cold-candidate warm-candidate \
    comparison-aa-cold comparison-aa-warm \
    comparison-candidate-cold comparison-candidate-warm; do
    test "$(tr -d '\r\n' <"$root/meta/$status_file-exit.txt")" = 0 || return 1
  done
  while IFS= read -r log_exit; do
    test "$(tr -d '\r\n' <"$log_exit")" = 0 || return 1
  done < <(find "$root/logs" -mindepth 1 -maxdepth 1 -type f -name '*.exit' \
    | LC_ALL=C sort)
  test ! -e "$root/meta/retained-candidate-exit.txt" \
    && test ! -L "$root/meta/retained-candidate-exit.txt" \
    && test ! -e "$root/meta/comparison-candidate-retained-exit.txt" \
    && test ! -L "$root/meta/comparison-candidate-retained-exit.txt" || return 1
  validate_status_namespace "$root" || return 1
  validate_semantic_required_files "$root" || return 1
  test "$(tr -d '\r\n' <"$root/meta/implementation-sha.txt")" = "$implementation" \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/implementation-sha.txt")" = \
    "$implementation" || return 1
  validate_manifest_set "$root" "$implementation" || return 1
  validate_supervisor_handoff_crosslink "$root" || return 1
  validate_executed_provenance "$root" "$implementation" || return 1
  validate_remote_byte_inventory "$root" || return 1
  validate_sha_inventory "$root" meta/evidence-sha256sums.txt \
    '^(manifests|results)/[A-Za-z0-9._/-]+$' true || return 1
  validate_inventory_path_set "$root" meta/evidence-sha256sums.txt \
    'manifests results' || return 1
  validate_sha_inventory "$root" meta/command-output-sha256sums.txt \
    '^logs/[A-Za-z0-9._/-]+$' true || return 1
  validate_inventory_path_set "$root" meta/command-output-sha256sums.txt logs || return 1
  validate_smoke_artifact_inventories "$root" || return 1
  (cd "$root/meta" && sha256sum -c operator-script-sha256sums.txt) || return 1
  verify_result_files "$root" "$driver" || return 1
  validate_smoke_campaign_identity "$root" results/cold-aa.json COLD \
    baseline-b-cs2a baseline-83f3cd70 "$BASELINE_SHA" "$implementation" 0 1 || return 1
  validate_smoke_campaign_identity "$root" results/warm-aa.json WARM \
    baseline-b-cs2a baseline-83f3cd70 "$BASELINE_SHA" "$implementation" 1 3 || return 1
  validate_smoke_campaign_identity "$root" results/cold-candidate.json COLD \
    candidate-cs2a major-v1 "$implementation" "$implementation" 0 1 || return 1
  validate_smoke_campaign_identity "$root" results/warm-candidate.json WARM \
    candidate-cs2a major-v1 "$implementation" "$implementation" 1 3 || return 1
  validate_policy_provenance "$root" "$expected_policy_sha" || return 1
  validate_smoke_terminal_crosslinks "$root"
}

authenticated_archive_profile() {
  local root=$1 implementation=$2 profile
  validate_executed_provenance "$root" "$implementation" || return 1
  if test -f "$root/meta/profile.txt" && test ! -L "$root/meta/profile.txt"; then
    profile=$(tr -d '\r\n' <"$root/meta/profile.txt") || return 1
    benchmark_profile_is_valid "$profile" || return 1
    printf '%s\n' "$profile"
  else
    printf '%s\n' legacy
  fi
}

validate_executed_provenance() {
  local root=$1 implementation=$2 rows authenticated controlled_uid profile expected_rows
  rows="$root/meta/supervisor/executed-script-sha256sums.tsv"
  authenticated="$root/meta/supervisor/authenticated-handoff.tsv"
  test -f "$rows" && test -f "$authenticated" \
    && test -f "$root/meta/controlled-uid.txt" || return 1
  awk -F '\t' '
    NF != 2 { exit 1 }
    $1 != "runner" && $1 != "supervisor" { exit 1 }
    length($2) != 64 || $2 !~ /^[0-9a-f]+$/ || seen[$1]++ { exit 1 }
    END { exit !(NR == 2 && seen["runner"] && seen["supervisor"]) }
  ' "$rows" || return 1
  if test -f "$root/meta/profile.txt" && test ! -L "$root/meta/profile.txt"; then
    profile=$(tr -d '\r\n' <"$root/meta/profile.txt") || return 1
    benchmark_profile_is_valid "$profile" || return 1
    expected_rows=5
  else
    profile=full
    expected_rows=4
  fi
  awk -F '\t' -v expected_rows="$expected_rows" '
    NF != 2 { exit 1 }
    $1 != "implementation" && $1 != "uid" && $1 != "runner" &&
      $1 != "supervisor" && $1 != "profile" { exit 1 }
    { seen[$1]++; total++ }
    END {
      exit !(total == expected_rows && seen["implementation"] == 1 &&
        seen["uid"] == 1 && seen["runner"] == 1 && seen["supervisor"] == 1 &&
        ((expected_rows == 4 && !seen["profile"]) ||
          (expected_rows == 5 && seen["profile"] == 1)))
    }
  ' "$authenticated" || return 1
  controlled_uid=$(tr -d '\r\n' <"$root/meta/controlled-uid.txt")
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || return 1
  test "$(awk -F '\t' '$1 == "uid" {print $2}' "$authenticated")" = \
    "$controlled_uid" || return 1
  test "$(awk -F '\t' '$1 == "runner" {print $2}' "$rows")" = \
    "$(sha256_of "$root/meta/cs2a-controlled-run.sh")" || return 1
  test "$(awk -F '\t' '$1 == "supervisor" {print $2}' "$rows")" = \
    "$(sha256_of "$root/meta/cs2a-governor-supervisor.sh")" || return 1
  test "$(awk -F '\t' '$1 == "implementation" {print $2}' "$authenticated")" = \
    "$implementation" || return 1
  test "$(awk -F '\t' '$1 == "runner" {print $2}' "$authenticated")" = \
    "$(sha256_of "$root/meta/cs2a-controlled-run.sh")" || return 1
  test "$(awk -F '\t' '$1 == "supervisor" {print $2}' "$authenticated")" = \
    "$(sha256_of "$root/meta/cs2a-governor-supervisor.sh")" || return 1
  if test "$expected_rows" = 5; then
    test "$(awk -F '\t' '$1 == "profile" {print $2}' "$authenticated")" = "$profile"
  fi
}

validate_supervisor_handoff_crosslink() {
  local root=$1 core final core_names final_names required
  core="$root/meta/supervisor-core"
  final="$root/meta/supervisor"
  test -d "$core" && test ! -L "$core" && test -d "$final" && test ! -L "$final" \
    || return 1
  core_names=$(find "$core" -mindepth 1 -maxdepth 1 -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  final_names=$(find "$final" -mindepth 1 -maxdepth 1 -print \
    | sed 's#^.*/##' | LC_ALL=C sort) || return 1
  test "$core_names" = "$(printf '%s\n' authenticated-handoff.tsv \
    child-or-supervisor-status.txt containment-failed.txt \
    executed-script-sha256sums.tsv finished-at.txt implementation-sha.txt \
    lock-provenance.txt original-governors.tsv restoration-failed.txt \
    restored-governors.tsv run-root.txt | LC_ALL=C sort)" || return 1
  test "$final_names" = "$(printf '%s\n' authenticated-handoff.tsv \
    child-or-supervisor-status.txt containment-failed.txt \
    executed-script-sha256sums.tsv finished-at.txt implementation-sha.txt \
    lock-provenance.txt lock-released.txt operator-post-supervisor-exit.txt \
    original-governors.tsv restoration-failed.txt restored-governors.tsv run-root.txt \
    | LC_ALL=C sort)" || return 1
  while IFS= read -r required; do
    cmp -s "$core/$required" "$final/$required" || return 1
  done <<<"$core_names"
}

validate_stage_files() {
  local root=$1 stage required mode
  stage=$(cat "$root/meta/stage.txt") || return 1
  case "$stage" in setup | aa-captured | aa-compared | candidate-captured | candidate-compared) ;;
    *) return 1 ;;
  esac
  for required in \
    meta/cs2a-controlled-run.sh meta/cs2a-governor-supervisor.sh meta/cs2a-operator.sh \
    meta/cs2a-validate-manifest.jq meta/operator-script-sha256sums.txt \
    meta/operator-post-supervisor-exit.txt meta/operator-resume-validation-exit.txt \
    meta/implementation-sha.txt meta/controlled-host.json meta/controlled-uid.txt \
    meta/policy-sha256.txt meta/policy-semantic-sha256.txt \
    meta/run-root.txt meta/commands.tsv meta/runner-exit.txt meta/inventory-exit.txt \
    meta/evidence-sha256sums.txt meta/artifact-inventory.tsv \
    meta/artifact-sha256sums.txt meta/command-output-sha256sums.txt \
    meta/operator-supervisor.log meta/operator-supervisor-exit.txt \
    meta/supervisor/child-or-supervisor-status.txt meta/supervisor/restoration-failed.txt \
    meta/supervisor/containment-failed.txt meta/supervisor/finished-at.txt \
    meta/supervisor/original-governors.tsv meta/supervisor/run-root.txt \
    meta/supervisor/operator-post-supervisor-exit.txt \
    meta/supervisor/executed-script-sha256sums.tsv \
    meta/supervisor/authenticated-handoff.tsv; do
    test -f "$root/$required" || return 1
  done
  case "$stage" in
    aa-captured | aa-compared | candidate-captured | candidate-compared)
      for required in cold-aa warm-aa; do test -f "$root/results/$required.json" || return 1; done
      ;;
  esac
  case "$stage" in
    aa-compared | candidate-captured | candidate-compared)
      for mode in cold warm; do
        test -f "$root/results/comparison-aa-$mode.json" || return 1
        test -f "$root/results/comparison-aa-$mode.md" || return 1
      done
      ;;
  esac
  case "$stage" in
    candidate-captured | candidate-compared)
      for mode in cold warm retained; do
        test -f "$root/results/$mode-candidate.json" || return 1
      done
      ;;
  esac
  if test "$stage" = candidate-compared; then
    for mode in cold warm retained; do
      test -f "$root/results/comparison-candidate-$mode.json" || return 1
      test -f "$root/results/comparison-candidate-$mode.md" || return 1
    done
  fi
}

verify_result_files() {
  local root=$1 driver=$2 result found=false
  test -x "$driver" || return 1
  for result in "$root"/results/*-aa.json "$root"/results/*-candidate.json; do
    test -f "$result" || continue
    found=true
    "$driver" verify --input "$result" || return 1
  done
  test "$found" = true
}

recompare_if_present() {
  local root=$1 label=$2 input=$3 archived_json=$4 archived_md=$5 exit_file=$6
  local driver=$7 scratch=$8 status archived_status
  if test ! -e "$root/$input" && test ! -e "$root/$archived_json" \
    && test ! -e "$root/$archived_md" && test ! -e "$root/$exit_file"; then
    return 0
  fi
  test -f "$root/$input" && test -f "$root/$archived_json" \
    && test -f "$root/$archived_md" && test -f "$root/$exit_file" || return 1
  if "$driver" compare --input "$root/$input" \
    --output-json "$scratch/$label.json" --output-md "$scratch/$label.md" \
    --enforce-release-gates >/dev/null 2>&1; then status=0; else status=$?; fi
  archived_status=$(cat "$root/$exit_file") || return 1
  test "$status" = "$archived_status" || return 1
  cmp -s "$scratch/$label.json" "$root/$archived_json" || return 1
  cmp -s "$scratch/$label.md" "$root/$archived_md"
}

validate_recomparisons() {
  local root=$1 driver=$2 scratch mode
  test -x "$driver" || return 1
  scratch=$(mktemp -d "$PWD/build/cs2a-recompare.XXXXXXXX") || return 1
  case "$scratch" in "$PWD"/build/cs2a-recompare.*) ;; *) return 1 ;; esac
  for mode in cold warm; do
    recompare_if_present "$root" "comparison-aa-$mode" "results/$mode-aa.json" \
      "results/comparison-aa-$mode.json" "results/comparison-aa-$mode.md" \
      "meta/comparison-aa-$mode-exit.txt" "$driver" "$scratch" || return 1
  done
  for mode in cold warm retained; do
    recompare_if_present "$root" "comparison-candidate-$mode" \
      "results/$mode-candidate.json" "results/comparison-candidate-$mode.json" \
      "results/comparison-candidate-$mode.md" "meta/comparison-candidate-$mode-exit.txt" \
      "$driver" "$scratch" || return 1
  done
}

validate_inventory_path_set() {
  local root=$1 inventory=$2 directories=$3 inventoried actual directory
  inventoried=$(awk '{print substr($0, 67)}' "$root/$inventory" | LC_ALL=C sort) || return 1
  actual=$(
    cd "$root" || exit 1
    for directory in $directories; do
      find "$directory" -type f -print
    done | LC_ALL=C sort
  ) || return 1
  test "$inventoried" = "$actual"
}

validate_semantic_required_files() {
  local root=$1 required
  for required in \
    meta/cs2a-controlled-run.sh meta/cs2a-governor-supervisor.sh meta/cs2a-operator.sh \
    meta/cs2a-validate-manifest.jq meta/operator-script-sha256sums.txt \
    meta/operator-post-supervisor-exit.txt meta/operator-resume-validation-exit.txt \
    meta/implementation-sha.txt meta/controlled-host.json meta/controlled-uid.txt \
    meta/policy-sha256.txt meta/policy-semantic-sha256.txt meta/run-root.txt \
    meta/commands.tsv meta/runner-exit.txt meta/inventory-exit.txt \
    meta/evidence-sha256sums.txt meta/remote-byte-sha256sums.txt \
    meta/artifact-inventory.tsv meta/artifact-sha256sums.txt \
    meta/command-output-sha256sums.txt meta/operator-supervisor.log \
    meta/operator-supervisor-exit.txt \
    meta/supervisor/child-or-supervisor-status.txt \
    meta/supervisor/restoration-failed.txt meta/supervisor/containment-failed.txt \
    meta/supervisor/finished-at.txt meta/supervisor/original-governors.tsv \
    meta/supervisor/restored-governors.tsv meta/supervisor/run-root.txt \
    meta/supervisor/implementation-sha.txt \
    meta/supervisor/operator-post-supervisor-exit.txt \
    meta/supervisor/lock-released.txt \
    meta/supervisor/lock-provenance.txt \
    meta/supervisor/executed-script-sha256sums.tsv \
    meta/supervisor/authenticated-handoff.tsv; do
    test -f "$root/$required" && test ! -L "$root/$required" || return 1
  done
}

validate_policy_provenance() {
  local root=$1 expected_policy_sha=${2:-$EXPECTED_POLICY_SHA256} actual
  [[ "$expected_policy_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  actual=$(sha256_of "$root/meta/controlled-host.json") || return 1
  test "$actual" = "$expected_policy_sha" || return 1
  test "$(cat "$root/meta/policy-sha256.txt")" = \
    "$actual  /opt/revoman-benchmark/controlled-host.json" || return 1
  test "$(cat "$root/meta/policy-semantic-sha256.txt")" = \
    "$EXPECTED_POLICY_SEMANTIC_SHA256"
}

validate_terminal_crosslinks() {
  local root=$1 run_root child supervisor_exit runner_exit post supervisor_post capture
  run_root=$(tr -d '\r\n' <"$root/meta/run-root.txt") || return 1
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/run-root.txt")" = "$run_root" || return 1
  child=$(tr -d '\r\n' <"$root/meta/supervisor/child-or-supervisor-status.txt") || return 1
  supervisor_exit=$(tr -d '\r\n' <"$root/meta/operator-supervisor-exit.txt") || return 1
  runner_exit=$(tr -d '\r\n' <"$root/meta/runner-exit.txt") || return 1
  post=$(tr -d '\r\n' <"$root/meta/operator-post-supervisor-exit.txt") || return 1
  supervisor_post=$(tr -d '\r\n' \
    <"$root/meta/supervisor/operator-post-supervisor-exit.txt") || return 1
  test "$child" = 0 && test "$supervisor_exit" = "$child" \
    && test "$runner_exit" = "$child" || return 1
  test "$post" = 0 && test "$supervisor_post" = "$post" || return 1
  test "$(tr -d '\r\n' <"$root/meta/operator-resume-validation-exit.txt")" = 0 \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/inventory-exit.txt")" = 0 || return 1
  for capture in cold-aa warm-aa cold-candidate warm-candidate retained-candidate; do
    test "$(tr -d '\r\n' <"$root/meta/$capture-exit.txt")" = 0 || return 1
  done
  test "$(tr -d '\r\n' <"$root/meta/supervisor/restoration-failed.txt")" = false \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/containment-failed.txt")" = false \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/lock-released.txt")" = true \
    || return 1
  [[ "$(tr -d '\r\n' <"$root/meta/supervisor/lock-provenance.txt")" \
    =~ ^0:0:600:[0-9]+:[0-9]+$ ]] || return 1
  cmp -s "$root/meta/supervisor/original-governors.tsv" \
    "$root/meta/supervisor/restored-governors.tsv"
}

validate_comparison_passes() {
  local root=$1 label mode
  for mode in cold warm; do
    label=comparison-aa-$mode
    test "$(tr -d '\r\n' <"$root/meta/$label-exit.txt")" = 0 || return 1
    jq -e '.overall == "PASS"' "$root/results/$label.json" >/dev/null || return 1
  done
  for mode in cold warm retained; do
    label=comparison-candidate-$mode
    test "$(tr -d '\r\n' <"$root/meta/$label-exit.txt")" = 0 || return 1
    jq -e '.overall == "PASS"' "$root/results/$label.json" >/dev/null || return 1
  done
}

validate_archive_semantics() {
  local root=$1 implementation=$2 driver=$3
  local expected_policy_sha=${4:-$EXPECTED_POLICY_SHA256}
  test -d "$root" && require_sha "$implementation" && test -x "$driver" || return 1
  validate_archive_safety "$root" || return 1
  if test -e "$root/meta/profile.txt" || test -L "$root/meta/profile.txt"; then
    test -f "$root/meta/profile.txt" && test ! -L "$root/meta/profile.txt" || return 1
    test "$(tr -d '\r\n' <"$root/meta/profile.txt")" = full || return 1
  fi
  validate_stage_schema "$root" || return 1
  test "$(tr -d '\r\n' <"$root/meta/stage.txt")" = candidate-compared || return 1
  validate_semantic_required_files "$root" || return 1
  test "$(tr -d '\r\n' <"$root/meta/implementation-sha.txt")" = "$implementation" \
    || return 1
  test "$(tr -d '\r\n' <"$root/meta/supervisor/implementation-sha.txt")" = \
    "$implementation" || return 1
  validate_manifest_set "$root" "$implementation" || return 1
  validate_commands_bijection "$root" || return 1
  validate_command_protocol "$root" || return 1
  validate_supervisor_handoff_crosslink "$root" || return 1
  validate_executed_provenance "$root" "$implementation" || return 1
  validate_remote_byte_inventory "$root" || return 1
  validate_sha_inventory "$root" meta/evidence-sha256sums.txt \
    '^(manifests|results)/[A-Za-z0-9._/-]+$' true || return 1
  validate_inventory_path_set "$root" meta/evidence-sha256sums.txt \
    'manifests results' || return 1
  validate_sha_inventory "$root" meta/command-output-sha256sums.txt \
    '^logs/[A-Za-z0-9._/-]+$' true || return 1
  validate_inventory_path_set "$root" meta/command-output-sha256sums.txt logs || return 1
  validate_artifact_inventories "$root" || return 1
  (cd "$root/meta" && sha256sum -c operator-script-sha256sums.txt) || return 1
  verify_result_files "$root" "$driver" || return 1
  validate_campaign_identity "$root" results/cold-aa.json COLD \
    baseline-b-cs2a baseline-83f3cd70 "$BASELINE_SHA" "$implementation" || return 1
  validate_campaign_identity "$root" results/warm-aa.json WARM \
    baseline-b-cs2a baseline-83f3cd70 "$BASELINE_SHA" "$implementation" || return 1
  validate_campaign_identity "$root" results/cold-candidate.json COLD \
    candidate-cs2a major-v1 "$implementation" "$implementation" || return 1
  validate_campaign_identity "$root" results/warm-candidate.json WARM \
    candidate-cs2a major-v1 "$implementation" "$implementation" || return 1
  validate_campaign_identity "$root" results/retained-candidate.json RETAINED \
    candidate-cs2a major-v1 "$implementation" "$implementation" || return 1
  for result in cold-aa.json warm-aa.json cold-candidate.json warm-candidate.json \
    retained-candidate.json; do
    validate_target_projection "$root/results/$result" baseline-a-cs2a \
      "$root/manifests/baseline-a.json" baseline-83f3cd70 || return 1
  done
  for result in cold-aa.json warm-aa.json; do
    validate_target_projection "$root/results/$result" baseline-b-cs2a \
      "$root/manifests/baseline-b.json" baseline-83f3cd70 || return 1
  done
  for result in cold-candidate.json warm-candidate.json retained-candidate.json; do
    validate_target_projection "$root/results/$result" candidate-cs2a \
      "$root/manifests/candidate.json" major-v1 || return 1
  done
  validate_campaign_set_identity "$root" || return 1
  validate_campaign_artifact_shape "$root" results/cold-aa.json COLD cold-aa || return 1
  validate_campaign_artifact_shape "$root" results/warm-aa.json WARM warm-aa || return 1
  validate_campaign_artifact_shape "$root" results/cold-candidate.json COLD \
    cold-candidate || return 1
  validate_campaign_artifact_shape "$root" results/warm-candidate.json WARM \
    warm-candidate || return 1
  validate_campaign_artifact_shape "$root" results/retained-candidate.json RETAINED \
    retained-candidate || return 1
  validate_campaign_artifact_inventory "$root" || return 1
  validate_recomparisons "$root" "$driver" || return 1
  validate_comparison_passes "$root" || return 1
  validate_policy_provenance "$root" "$expected_policy_sha" || return 1
  validate_terminal_crosslinks "$root"
}

validate_archive() {
  local root=$1 implementation=$2 driver=${3:-}
  test -n "$driver" || return 1
  validate_archive_semantics "$root" "$implementation" "$driver" || return 1
  test "$(tr -d '\r\n' <"$root/meta/operator-final-exit.txt")" = 0
}

write_root_checksum_inventory() {
  local root=$1
  (cd "$root" &&
    find . -type f ! -path './evidence-sha256sums.txt' -print0 \
      | LC_ALL=C sort -z | xargs -0 -r sha256sum >evidence-sha256sums.txt &&
    sha256sum -c evidence-sha256sums.txt)
}

validate_root_checksum_inventory() {
  local root=$1 inventory actual_paths inventoried_paths
  inventory="$root/evidence-sha256sums.txt"
  validate_archive_safety "$root" || return 1
  test -f "$inventory" && test ! -L "$inventory" || return 1
  awk '
    {
      hash = substr($0, 1, 64)
      separator = substr($0, 65, 2)
      path = substr($0, 67)
      if (length(hash) != 64 || hash !~ /^[0-9a-f]+$/ || separator != "  " ||
          path !~ /^\.\/[A-Za-z0-9._\/-]+$/ || path == "./evidence-sha256sums.txt" ||
          path ~ /(^|\/)\.\.(\/|$)/ || seen[path]++) exit 1
    }
    END { if (NR == 0) exit 1 }
  ' "$inventory" || return 1
  inventoried_paths=$(awk '{print substr($0, 67)}' "$inventory" | LC_ALL=C sort) || return 1
  actual_paths=$(cd "$root" && find . -type f \
    ! -path './evidence-sha256sums.txt' -print | LC_ALL=C sort) || return 1
  test "$inventoried_paths" = "$actual_paths" || return 1
  (cd "$root" && sha256sum -c evidence-sha256sums.txt)
}

recover_publication_marker() {
  local canonical=$1 marker=$2
  if test -e "$canonical" || test -L "$canonical"; then
    test -d "$canonical" && test ! -L "$canonical" || return 1
    validate_root_checksum_inventory "$canonical" || return 1
    if test -e "$marker" || test -L "$marker"; then
      test -f "$marker" && test ! -L "$marker" || return 1
      test "$(tr -d '\r\n' <"$marker")" = "$canonical" || return 1
    else
      publish_archive_marker "$marker" "$canonical" || return 1
    fi
  elif test -e "$marker" || test -L "$marker"; then
    return 1
  fi
}

publish_archive_marker() {
  local marker=$1 canonical=$2 parent candidate
  parent=$(dirname "$marker") || return 1
  test -d "$parent" && test ! -L "$parent" || return 1
  candidate=$(mktemp "$parent/.cs2a-publication-marker.XXXXXXXX") || return 1
  if ! printf '%s\n' "$canonical" >"$candidate" || ! chmod 0600 "$candidate"; then
    rm -f -- "$candidate"
    return 1
  fi
  before_archive_marker_publish
  if ! ln "$candidate" "$marker"; then
    rm -f -- "$candidate"
    return 1
  fi
  rm -f -- "$candidate" || return 1
  test -f "$marker" && test ! -L "$marker" \
    && test "$(tr -d '\r\n' <"$marker")" = "$canonical"
}

before_archive_marker_publish() { :; }
before_archive_directory_publish() { :; }

discover_publication_tools() {
  local os mv_name stat_name mv_path stat_path probe tmp_base mv_status
  os=$(uname -s) || return 1
  case "$os" in
    Darwin) mv_name='gmv'; stat_name='gstat' ;;
    Linux) mv_name='mv'; stat_name='stat' ;;
    *) return 1 ;;
  esac
  mv_path=$(command -v "$mv_name") || return 1
  stat_path=$(command -v "$stat_name") || return 1
  test -x "$mv_path" && test -x "$stat_path" || return 1
  "$mv_path" --version 2>/dev/null | sed -n '1p' \
    | grep -Eq '^mv \(GNU coreutils\) [0-9]+' || return 1
  "$stat_path" --version 2>/dev/null | sed -n '1p' \
    | grep -Eq '^stat \(GNU coreutils\) [0-9]+' || return 1
  tmp_base=${TMPDIR:-/tmp}
  tmp_base=${tmp_base%/}
  probe=$(mktemp -d "$tmp_base/cs2a-publication-tools.XXXXXXXX") || return 1
  case "$probe" in "$tmp_base"/cs2a-publication-tools.*) ;; *) return 1 ;; esac
  mkdir "$probe/source" "$probe/destination" || return 1
  if "$mv_path" -Tn -- "$probe/source" "$probe/destination"; then
    mv_status=0
  else
    mv_status=$?
  fi
  { test "$mv_status" -eq 0 || test "$mv_status" -eq 1; } \
    && test -d "$probe/source" && test ! -L "$probe/source" \
    && test -d "$probe/destination" && test ! -L "$probe/destination" \
    && [[ "$("$stat_path" -c '%d' "$probe")" =~ ^[0-9]+$ ]]
  mv_status=$?
  rm -rf -- "$probe"
  test "$mv_status" -eq 0 || return 1
  PUBLICATION_MV=$mv_path
  PUBLICATION_STAT=$stat_path
}

publish_archive_directory() {
  local stage=$1 canonical=$2
  test -n "$PUBLICATION_MV" || return 1
  before_archive_directory_publish
  "$PUBLICATION_MV" -Tn -- "$stage" "$canonical" || return 1
  test -d "$canonical" && test ! -L "$canonical" || return 1
  test ! -e "$stage" && test ! -L "$stage"
}

publish_archive() {
  local stage=$1 canonical=$2 marker=$3 implementation=$4
  case "$stage" in "$EVIDENCE_ROOT"/cs2a-*/.cs2a-archive-stage.*) ;; *) return 1 ;; esac
  safe_attempt_path "$canonical" "$implementation" || return 1
  recover_publication_marker "$canonical" "$marker" || return 1
  if test -d "$canonical"; then
    test "$(cat "$marker")" = "$canonical" || return 1
    return 0
  fi
  discover_publication_tools || return 1
  test "$("$PUBLICATION_STAT" -c '%d' "$stage")" = \
    "$("$PUBLICATION_STAT" -c '%d' "$(dirname "$canonical")")" \
    || return 1
  validate_archive_safety "$stage" || return 1
  write_root_checksum_inventory "$stage" || return 1
  publish_archive_directory "$stage" "$canonical" || return 1
  validate_root_checksum_inventory "$canonical" || return 1
  publish_archive_marker "$marker" "$canonical"
}

copy_local_failure_file() {
  local source=$1 destination=$2
  if test -e "$source" || test -L "$source"; then
    test -f "$source" && test ! -L "$source" || return 1
    cp -- "$source" "$destination" || return 1
  fi
}

publish_local_operator_failure() {
  local phase=$1 source_status=$2 parent attempt stage canonical marker timestamp
  operator_failure_phase_is_valid "$phase" || return 1
  [[ "$source_status" =~ ^[0-9]+$ ]] || return 1
  require_sha "$CS2A_IMPLEMENTATION_SHA" || return 1
  mkdir -p "$PWD/build" || return 1
  parent="$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA"
  mkdir -p "$parent" || return 1
  timestamp=$(date -u +%Y%m%dT%H%M%SZ) || return 1
  attempt="operator-failure.$timestamp.$$"
  canonical="$parent/$attempt"
  marker="$PWD/build/cs2a-local-evidence-dir.txt"
  test ! -e "$canonical" && test ! -L "$canonical" || return 1
  stage=$(mktemp -d "$parent/.cs2a-archive-stage.XXXXXXXX") || return 1
  case "$stage" in "$parent"/.cs2a-archive-stage.*) ;; *) return 1 ;; esac
  mkdir "$stage/meta" || return 1
  printf '%s\n' setup >"$stage/meta/stage.txt" || return 1
  printf '%s\n' "$phase" >"$stage/meta/operator-failure-phase.txt" || return 1
  printf '%s\n' "$source_status" >"$stage/meta/operator-failure-source-exit.txt" || return 1
  printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$stage/meta/implementation-sha.txt" || return 1
  printf '%s\n' false >"$stage/meta/remote-evidence-present.txt" || return 1
  printf '%s\n' false >"$stage/meta/local-validation-passed.txt" || return 1
  printf '%s\n' 70 >"$stage/meta/operator-final-exit.txt" || return 1
  copy_local_failure_file "$PWD/build/cs2a-supervisor.log" \
    "$stage/meta/operator-supervisor.log" || return 1
  copy_local_failure_file "$PWD/build/cs2a-supervisor-exit.txt" \
    "$stage/meta/operator-supervisor-exit.txt" || return 1
  copy_local_failure_file "$PWD/build/cs2a-original-post-supervisor-exit.txt" \
    "$stage/meta/operator-original-post-supervisor-exit.txt" || return 1
  copy_local_failure_file "$PWD/build/cs2a-recorded-post-supervisor-exit.txt" \
    "$stage/meta/operator-recorded-post-supervisor-exit.txt" || return 1
  publish_archive "$stage" "$canonical" "$marker" "$CS2A_IMPLEMENTATION_SHA"
}

validate_attempt_history() {
  local attempt=$1 implementation=$2 evidence_sha=$3 rel parent_sha parent_record
  require_sha "$implementation" && require_sha "$evidence_sha" || return 1
  safe_attempt_path "$attempt" "$implementation" || return 1
  rel=${attempt#"$PWD/"}
  test "$rel" != "$attempt" || return 1
  git_no_hooks cat-file -e "$implementation^{commit}" || return 1
  git_no_hooks cat-file -e "$evidence_sha^{commit}" || return 1
  parent_record=$(git_no_hooks rev-list --parents -n 1 "$evidence_sha") || return 1
  test "$(printf '%s\n' "$parent_record" | awk '{print NF}')" = 2 || return 1
  parent_sha=$(git_no_hooks rev-parse "$evidence_sha^") || return 1
  git_no_hooks merge-base --is-ancestor "$implementation" "$parent_sha" || return 1
  git_no_hooks merge-base --is-ancestor "$implementation" "$evidence_sha" || return 1
  test "$(git_no_hooks --literal-pathspecs log -1 --format=%H -- "$rel")" = "$evidence_sha" \
    || return 1
  test -z "$(git_no_hooks --literal-pathspecs ls-tree -r --name-only "$parent_sha" -- "$rel")" \
    || return 1
  test -z "$(git_no_hooks diff --name-only "$parent_sha..$evidence_sha" -- . \
    ":(exclude)$rel")" || return 1
  test -n "$(git_no_hooks --literal-pathspecs diff --name-only \
    "$parent_sha..$evidence_sha" -- "$rel")"
}

validate_committed_attempt_tree() {
  local attempt=$1 evidence_sha=$2 rel committed_paths filesystem_paths
  local metadata path mode remainder type filesystem_path committed_sha filesystem_sha
  require_sha "$evidence_sha" || return 1
  rel=${attempt#"$PWD/"}
  test "$rel" != "$attempt" || return 1
  committed_paths=$(git_no_hooks --literal-pathspecs ls-tree -r --name-only \
    "$evidence_sha" -- "$rel" \
    | LC_ALL=C sort) || return 1
  filesystem_paths=$(
    cd "$attempt" || exit 1
    find . -type f -print | sed 's#^\./##' | sed "s#^#$rel/#" | LC_ALL=C sort
  ) || return 1
  test -n "$committed_paths" && test "$committed_paths" = "$filesystem_paths" || return 1
  while IFS="$(printf '\t')" read -r metadata path; do
    test -n "$metadata" && test -n "$path" || return 1
    mode=${metadata%% *}
    remainder=${metadata#* }
    type=${remainder%% *}
    case "$mode:$type" in 100644:blob | 100755:blob) ;; *) return 1 ;; esac
    filesystem_path=${path#"$rel/"}
    test "$filesystem_path" != "$path" || return 1
    test -f "$attempt/$filesystem_path" && test ! -L "$attempt/$filesystem_path" \
      || return 1
    committed_sha=$(git_no_hooks show "$evidence_sha:$path" | sha256sum | cut -d' ' -f1) \
      || return 1
    filesystem_sha=$(sha256_of "$attempt/$filesystem_path") || return 1
    test "$committed_sha" = "$filesystem_sha" || return 1
  done < <(git_no_hooks --literal-pathspecs ls-tree -r "$evidence_sha" -- "$rel")
}

validate_staged_attempt_tree() {
  local attempt=$1 rel staged_paths filesystem_paths metadata path mode remainder object stage
  local filesystem_path staged_sha filesystem_sha
  rel=${attempt#"$PWD/"}
  test "$rel" != "$attempt" || return 1
  staged_paths=$(git_no_hooks --literal-pathspecs ls-files --stage -- "$rel" | awk -F '\t' '{print $2}' \
    | LC_ALL=C sort) || return 1
  filesystem_paths=$(
    cd "$attempt" || exit 1
    find . -type f -print | sed 's#^\./##' | sed "s#^#$rel/#" | LC_ALL=C sort
  ) || return 1
  test -n "$staged_paths" && test "$staged_paths" = "$filesystem_paths" || return 1
  while IFS="$(printf '\t')" read -r metadata path; do
    test -n "$metadata" && test -n "$path" || return 1
    mode=${metadata%% *}
    remainder=${metadata#* }
    object=${remainder%% *}
    stage=${remainder##* }
    test "$metadata" = "$mode $object $stage" || return 1
    case "$mode" in 100644 | 100755) ;; *) return 1 ;; esac
    test "$stage" = 0 || return 1
    [[ "$object" =~ ^[0-9a-f]{40}$|^[0-9a-f]{64}$ ]] || return 1
    filesystem_path=${path#"$rel/"}
    test "$filesystem_path" != "$path" || return 1
    test -f "$attempt/$filesystem_path" && test ! -L "$attempt/$filesystem_path" \
      || return 1
    staged_sha=$(git_no_hooks cat-file blob "$object" | sha256sum | cut -d' ' -f1) || return 1
    filesystem_sha=$(sha256_of "$attempt/$filesystem_path") || return 1
    test "$staged_sha" = "$filesystem_sha" || return 1
  done < <(git_no_hooks --literal-pathspecs ls-files --stage -- "$rel")
}

validate_frozen_attempt_tree() {
  local attempt=$1 tree=$2 rel tree_paths filesystem_paths metadata path mode remainder type
  local filesystem_path object tree_sha filesystem_sha
  [[ "$tree" =~ ^[0-9a-f]{40}$|^[0-9a-f]{64}$ ]] || return 1
  rel=${attempt#"$PWD/"}
  test "$rel" != "$attempt" || return 1
  tree_paths=$(git_no_hooks --literal-pathspecs ls-tree -r --name-only "$tree" -- "$rel" \
    | LC_ALL=C sort) || return 1
  filesystem_paths=$(
    cd "$attempt" || exit 1
    find . -type f -print | sed 's#^\./##' | sed "s#^#$rel/#" | LC_ALL=C sort
  ) || return 1
  test -n "$tree_paths" && test "$tree_paths" = "$filesystem_paths" || return 1
  while IFS="$(printf '\t')" read -r metadata path; do
    test -n "$metadata" && test -n "$path" || return 1
    mode=${metadata%% *}
    remainder=${metadata#* }
    type=${remainder%% *}
    object=${remainder##* }
    case "$mode:$type" in 100644:blob | 100755:blob) ;; *) return 1 ;; esac
    filesystem_path=${path#"$rel/"}
    test "$filesystem_path" != "$path" || return 1
    test -f "$attempt/$filesystem_path" && test ! -L "$attempt/$filesystem_path" \
      || return 1
    tree_sha=$(git_no_hooks cat-file blob "$object" | sha256sum | cut -d' ' -f1) || return 1
    filesystem_sha=$(sha256_of "$attempt/$filesystem_path") || return 1
    test "$tree_sha" = "$filesystem_sha" || return 1
  done < <(git_no_hooks --literal-pathspecs ls-tree -r \
    --format='%(objectmode) %(objecttype) %(objectname)%x09%(path)' "$tree" -- "$rel")
}

validate_frozen_tree_scope() {
  local old_head=$1 frozen_tree=$2 evidence_rel=$3 path saw=false
  require_sha "$old_head" || return 1
  [[ "$frozen_tree" =~ ^[0-9a-f]{40}$|^[0-9a-f]{64}$ ]] || return 1
  test -n "$evidence_rel" || return 1
  while IFS= read -r -d '' path; do
    saw=true
    case "$path" in "$evidence_rel"/*) ;; *) return 1 ;; esac
  done < <(git_no_hooks --literal-pathspecs diff-tree \
    --no-commit-id --name-only -r -z "$old_head" "$frozen_tree" --)
  test "$saw" = true
}

unstage_new_attempt() {
  local evidence_rel=$1
  test -n "$evidence_rel" || return 1
  git_no_hooks --literal-pathspecs restore \
    --staged -- "$evidence_rel" || return 1
  test -z "$(git_no_hooks --literal-pathspecs diff --cached --name-only -- "$evidence_rel")"
}

validate_persisted_publication() {
  local attempt=$1 implementation=$2 evidence_sha=$3 rel
  validate_attempt_history "$attempt" "$implementation" "$evidence_sha" || return 1
  rel=${attempt#"$PWD/"}
  test "$rel" != "$attempt" || return 1
  test -z "$(git_no_hooks --literal-pathspecs status --porcelain --untracked-files=all -- "$rel")" \
    || return 1
  git_no_hooks --literal-pathspecs diff --no-ext-diff --quiet "$evidence_sha" -- "$rel" \
    || return 1
  git_no_hooks --literal-pathspecs diff --cached --no-ext-diff --quiet \
    "$evidence_sha" -- "$rel" || return 1
  validate_root_checksum_inventory "$attempt" || return 1
  validate_committed_attempt_tree "$attempt" "$evidence_sha"
}

validate_persisted_attempt() {
  local attempt=$1 implementation=$2 evidence_sha=$3 driver=${4:-}
  validate_persisted_publication "$attempt" "$implementation" "$evidence_sha" || return 1
  validate_archive "$attempt" "$implementation" "$driver"
}

persist_attempt() {
  local operator_status=$1 recorded_status evidence_dir evidence_rel evidence_sha
  local old_head branch_ref frozen_tree commit_message
  [[ "$operator_status" =~ ^[0-9]+$ ]] || return 70
  recorded_status=$(cat "$PWD/build/cs2a-operator-status.txt") || return 70
  test "$operator_status" = "$recorded_status" || return 70
  evidence_dir=$(cat "$PWD/build/cs2a-local-evidence-dir.txt") || return 70
  safe_attempt_path "$evidence_dir" "$CS2A_IMPLEMENTATION_SHA" || return 70
  evidence_rel=${evidence_dir#"$PWD/"}
  validate_root_checksum_inventory "$evidence_dir" || return 70
  git_no_hooks diff --no-ext-diff --quiet HEAD -- . || return 70
  git_no_hooks diff --cached --no-ext-diff --quiet HEAD -- . || return 70
  git_no_hooks ls-files --others --exclude-standard -- . \
    | awk -v prefix="$evidence_rel/" 'index($0, prefix) != 1 { exit 1 }' || return 70
  if test -n "$(git_no_hooks --literal-pathspecs ls-files -- "$evidence_rel")"; then
    test -z "$(git_no_hooks --literal-pathspecs status --porcelain -- "$evidence_rel")" || return 70
    evidence_sha=$(git_no_hooks --literal-pathspecs log -1 --format=%H -- "$evidence_rel") || return 70
  else
    old_head=$(git_no_hooks rev-parse HEAD) || return 70
    branch_ref=$(git_no_hooks symbolic-ref -q HEAD) || return 70
    git_no_hooks --literal-pathspecs add -f -- "$evidence_rel" \
      || return 70
    frozen_tree=$(git_no_hooks write-tree) \
      || { unstage_new_attempt "$evidence_rel" || true; return 70; }
    if ! validate_staged_attempt_tree "$evidence_dir" \
      || ! validate_frozen_attempt_tree "$evidence_dir" "$frozen_tree" \
      || ! validate_frozen_tree_scope "$old_head" "$frozen_tree" "$evidence_rel" \
      || ! git_no_hooks --literal-pathspecs diff --cached --check \
      || test "$(git_no_hooks write-tree)" != "$frozen_tree"; then
      unstage_new_attempt "$evidence_rel" || return 70
      return 70
    fi
    commit_message="perf: archive CS2a attempt $(basename "$evidence_dir")"
    evidence_sha=$(printf '%s\n' "$commit_message" \
      | git_no_hooks commit-tree "$frozen_tree" -p "$old_head") \
      || { unstage_new_attempt "$evidence_rel" || true; return 70; }
    if ! git_no_hooks update-ref -m "commit: $commit_message" \
      "$branch_ref" "$evidence_sha" "$old_head"; then
      unstage_new_attempt "$evidence_rel" || return 70
      return 70
    fi
    test "$(git_no_hooks rev-parse HEAD)" = "$evidence_sha" || return 70
  fi
  validate_persisted_publication "$evidence_dir" "$CS2A_IMPLEMENTATION_SHA" "$evidence_sha" \
    || return 70
  printf '%s\n' "$evidence_sha" >"$PWD/build/cs2a-attempt-evidence-sha.txt"
}

persist_original_post_status() {
  local governor_state=$1 status=$2 candidate recorded
  case "$governor_state" in /run/revoman-cs2a/governor-state.*) ;; *) return 1 ;; esac
  [[ "$status" =~ ^[0-9]+$ ]] || return 1
  candidate="$PWD/build/cs2a-original-post-supervisor-exit.txt"
  printf '%s\n' "$status" >"$candidate" || return 1
  scp "$candidate" "$REMOTE_HOST:/opt/revoman-benchmark/runs/.cs2a-post-status.upload" \
    || return 1
  ssh -tt "$REMOTE_HOST" \
    "dzdo /bin/bash -c 'set -Eeuo pipefail; umask 077; \
       destination=\"$governor_state/operator-post-supervisor-exit.txt\"; \
       candidate=\$(mktemp \"$governor_state/.operator-post-supervisor-exit.XXXXXXXX\"); \
       trap \"rm -f -- \\\"\$candidate\\\"\" EXIT; \
       printf \"%s\\n\" \"$status\" >\"\$candidate\"; \
       chmod 0400 \"\$candidate\"; \
       ln \"\$candidate\" \"\$destination\" || \
         { test -f \"\$destination\" && test ! -L \"\$destination\"; }' && \
     dzdo test ! -L '$governor_state/operator-post-supervisor-exit.txt' && \
     dzdo test \"\$(dzdo stat -c '%u:%g:%a' \
       '$governor_state/operator-post-supervisor-exit.txt')\" = 0:0:400" || return 1
  recorded=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$governor_state/operator-post-supervisor-exit.txt'" | tr -d '\r\n') \
    || return 1
  [[ "$recorded" =~ ^[0-9]+$ ]] || return 1
  test "$recorded" = "$status" || return 1
  printf '%s\n' "$recorded" >"$PWD/build/cs2a-recorded-post-supervisor-exit.txt"
}

prepare_operator_source() {
  local root detached_operator_dir asset
  mkdir -p "$PWD/build"
  root=$(mktemp -d "$PWD/build/cs2a-operator-source.XXXXXXXX") || return 1
  git_no_hooks worktree add --detach "$root/source" "$CS2A_IMPLEMENTATION_SHA"
  test "$(git -C "$root/source" rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
  test -z "$(git -C "$root/source" status --porcelain)"
  detached_operator_dir="$root/source/docs/superpowers/benchmarks/operators"
  for asset in cs2a-controlled-run.sh cs2a-governor-supervisor.sh cs2a-operator.sh \
    cs2a-validate-manifest.jq; do
    cmp -s "$OPERATOR_DIR/$asset" "$detached_operator_dir/$asset" || return 1
  done
  AUTHENTICATED_SOURCE_ROOT="$root/source"
  readonly AUTHENTICATED_SOURCE_ROOT
}

prepare_local_driver() {
  test -n "${AUTHENTICATED_SOURCE_ROOT:-}" || return 1
  LOCAL_DRIVER="$AUTHENTICATED_SOURCE_ROOT/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
  "$AUTHENTICATED_SOURCE_ROOT/gradlew" -p "$AUTHENTICATED_SOURCE_ROOT" \
    :benchmark-driver:installDist \
    --no-daemon --console=plain >"$PWD/build/cs2a-local-validation-driver.log" 2>&1
  test -x "$LOCAL_DRIVER"
}

install_remote_bundle() {
  test "$#" = 1 || return 1
  local profile=$1 runner_sha supervisor_sha operator_sha validator_sha
  local installed_operator_sha installed_validator_sha
  benchmark_profile_is_valid "$profile" || return 1
  controlled_uid_policy_is_provisioned || {
    fail "controlled UID policy hash is unprovisioned; administrator review is required"
    return 70
  }
  runner_sha=$(sha256_of "$CONTROLLED_RUNNER") || return 1
  supervisor_sha=$(sha256_of "$SUPERVISOR") || return 1
  operator_sha=$(sha256_of "$0") || return 1
  validator_sha=$(sha256_of "$VALIDATOR") || return 1
  scp "$CONTROLLED_RUNNER" "$SUPERVISOR" "$0" "$VALIDATOR" \
    "$IMPLEMENTATION_FILE" "$REMOTE_HOST:/opt/revoman-benchmark/runs/" || return 1
  ssh -tt "$REMOTE_HOST" \
    "dzdo install -o root -g root -m 0555 \
       /opt/revoman-benchmark/runs/cs2a-controlled-run.sh \
       /opt/revoman-benchmark/cs2a-controlled-run.sh && \
     dzdo install -o root -g root -m 0555 \
       /opt/revoman-benchmark/runs/cs2a-governor-supervisor.sh \
       /opt/revoman-benchmark/cs2a-governor-supervisor.sh && \
     dzdo install -o root -g root -m 0444 \
       /opt/revoman-benchmark/runs/cs2a-implementation-sha \
       /opt/revoman-benchmark/cs2a-implementation-sha && \
     dzdo test -f '$CONTROLLED_UID_FILE' && \
     dzdo test ! -L '$CONTROLLED_UID_FILE' && \
     dzdo test \"\$(stat -c '%u:%g:%a' '$CONTROLLED_UID_FILE')\" = 0:0:444 && \
     dzdo test \"\$(dzdo sha256sum '$CONTROLLED_UID_FILE' | awk '{print \$1}')\" = \
       '$CONTROLLED_UID_POLICY_SHA256' && \
     printf 'implementation\\t%s\\nuid\\t%s\\nrunner\\t%s\\nsupervisor\\t%s\\nprofile\\t%s\\n' \
       '$CS2A_IMPLEMENTATION_SHA' \"\$(dzdo cat '$CONTROLLED_UID_FILE')\" \
       '$runner_sha' '$supervisor_sha' '$profile' \
       | dzdo tee /opt/revoman-benchmark/cs2a-operator-handoff.tsv >/dev/null && \
     dzdo chown root:root /opt/revoman-benchmark/cs2a-operator-handoff.tsv && \
     dzdo chmod 0400 /opt/revoman-benchmark/cs2a-operator-handoff.tsv" || return 1
  installed_operator_sha=$(sha256_of "$OPERATOR_DIR/cs2a-operator.sh") || return 1
  test "$operator_sha" = "$installed_operator_sha" || return 1
  installed_validator_sha=$(sha256_of "$OPERATOR_DIR/cs2a-validate-manifest.jq") || return 1
  test "$validator_sha" = "$installed_validator_sha" || return 1
  verify_remote_bundle || return 1
}

verify_remote_bundle() {
  local runner_sha supervisor_sha remote_runner_sha remote_supervisor_sha
  local installed controlled_uid controlled_uid_metadata controlled_uid_policy_sha
  controlled_uid_policy_is_provisioned || {
    fail "controlled UID policy hash is unprovisioned; administrator review is required"
    return 70
  }
  runner_sha=$(sha256_of "$CONTROLLED_RUNNER") || return 1
  supervisor_sha=$(sha256_of "$SUPERVISOR") || return 1
  installed=$(ssh -tt "$REMOTE_HOST" \
    'dzdo cat /opt/revoman-benchmark/cs2a-implementation-sha' | tr -d '\r\n') || return 1
  test "$installed" = "$CS2A_IMPLEMENTATION_SHA" || return 1
  controlled_uid_metadata=$(ssh -tt "$REMOTE_HOST" \
    "dzdo test -f '$CONTROLLED_UID_FILE' && \
     dzdo test ! -L '$CONTROLLED_UID_FILE' && \
     dzdo stat -c '%u:%g:%a' '$CONTROLLED_UID_FILE'" | tr -d '\r\n') || return 1
  test "$controlled_uid_metadata" = 0:0:444 || return 1
  controlled_uid=$(ssh -tt "$REMOTE_HOST" "dzdo cat '$CONTROLLED_UID_FILE'" \
    | tr -d '\r\n') || return 1
  [[ "$controlled_uid" =~ ^[1-9][0-9]*$ ]] || return 1
  controlled_uid_policy_sha=$(ssh -tt "$REMOTE_HOST" \
    "dzdo sha256sum '$CONTROLLED_UID_FILE'" | tr -d '\r' | awk '{print $1}') || return 1
  test "$controlled_uid_policy_sha" = "$CONTROLLED_UID_POLICY_SHA256" || return 1
  remote_runner_sha=$(ssh -tt "$REMOTE_HOST" \
    'dzdo sha256sum /opt/revoman-benchmark/cs2a-controlled-run.sh' \
    | tr -d '\r' | awk '{print $1}') || return 1
  test "$remote_runner_sha" = "$runner_sha" || return 1
  remote_supervisor_sha=$(ssh -tt "$REMOTE_HOST" \
    'dzdo sha256sum /opt/revoman-benchmark/cs2a-governor-supervisor.sh' \
    | tr -d '\r' | awk '{print $1}') || return 1
  test "$remote_supervisor_sha" = "$supervisor_sha" || return 1
}

run_remote_supervisor() {
  local status
  set +e
  ssh -tt "$REMOTE_HOST" 'dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh' \
    | tee "$PWD/build/cs2a-supervisor.log"
  status=${PIPESTATUS[0]}
  set -e
  printf '%s\n' "$status" >"$PWD/build/cs2a-supervisor-exit.txt"
  return "$status"
}

validate_resume_paths() {
  local run_root=$1 governor_state=$2
  [[ "$run_root" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
  [[ "$governor_state" =~ ^/run/revoman-cs2a/governor-state\.[A-Za-z0-9]+$ ]] || return 1
}

extract_supervisor_marker() {
  local log=$1 name=$2 values count value
  test -f "$log" && test ! -L "$log" || return 1
  case "$name" in RUN_ROOT | GOVERNOR_STATE) ;; *) return 1 ;; esac
  values=$(tr -d '\r' <"$log" | sed -n "s/^$name=//p") || return 1
  count=$(printf '%s\n' "$values" | awk 'NF { count++ } END { print count + 0 }') || return 1
  test "$count" = 1 || return 1
  value=$(printf '%s\n' "$values" | awk 'NF { print }') || return 1
  case "$name" in
    RUN_ROOT)
      [[ "$value" =~ ^/opt/revoman-benchmark/runs/cs2a\.[A-Za-z0-9]+$ ]] || return 1
      ;;
    GOVERNOR_STATE)
      [[ "$value" =~ ^/run/revoman-cs2a/governor-state\.[A-Za-z0-9]+$ ]] || return 1
      ;;
  esac
  printf '%s\n' "$value"
}

refresh_remote_final_handoff() {
  local run_root=$1 governor_state=$2
  validate_resume_paths "$run_root" "$governor_state" || return 1
  # shellcheck disable=SC2029 # both interpolated paths passed the exact absolute-path grammar.
  ssh -tt "$REMOTE_HOST" \
    "dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh \
       --publish-final-handoff '$run_root' '$governor_state'"
}

validate_remote_final_handoff() {
  local run_root=$1 governor_state=$2
  validate_resume_paths "$run_root" "$governor_state" || return 1
  # shellcheck disable=SC2029 # both interpolated paths passed the exact absolute-path grammar.
  ssh -tt "$REMOTE_HOST" \
    "dzdo /opt/revoman-benchmark/cs2a-governor-supervisor.sh --validate-final-handoff '$run_root' '$governor_state'" \
    >/dev/null
}

report_archive_stage_failure() {
  local stage=$1
  case "$stage" in
    "$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA"/.cs2a-archive-stage.*) ;;
    *) return 1 ;;
  esac
  test -d "$stage" && test ! -L "$stage" || return 1
  printf 'LOCAL_EVIDENCE_STAGE=%s\n' "$stage"
}

archive_remote_attempt() {
  local run_root=$1 governor_state=$2
  local run_real recorded_run implementation
  local attempt stage canonical marker post_status=70 final_status=70
  local profile=legacy local_validation=false
  validate_resume_paths "$run_root" "$governor_state" || return 70
  # shellcheck disable=SC2029 # validated absolute path is intentionally expanded for remote argv
  run_real=$(ssh "$REMOTE_HOST" "readlink -f -- '$run_root'") || return 70
  test "$run_real" = "$run_root" || return 70
  recorded_run=$(ssh -tt "$REMOTE_HOST" "dzdo cat '$governor_state/run-root.txt'" \
    | tr -d '\r\n') || return 70
  test "$recorded_run" = "$run_root" || return 70
  implementation=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$governor_state/implementation-sha.txt'" | tr -d '\r\n') || return 70
  test "$implementation" = "$CS2A_IMPLEMENTATION_SHA" || return 70
  test "$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$governor_state/executed-script-sha256sums.tsv'" | tr -d '\r')" = \
    "$(printf 'runner\t%s\nsupervisor\t%s' \
      "$(sha256_of "$CONTROLLED_RUNNER")" "$(sha256_of "$SUPERVISOR")")" || return 70
  post_status=$(ssh -tt "$REMOTE_HOST" \
    "dzdo cat '$governor_state/operator-post-supervisor-exit.txt'" | tr -d '\r\n') \
    || post_status=70
  [[ "$post_status" =~ ^[0-9]+$ ]] || post_status=70
  mkdir -p "$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA" || return 70
  attempt=$(basename "$run_root")
  canonical="$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA/$attempt"
  marker="$PWD/build/cs2a-local-evidence-dir.txt"
  recover_publication_marker "$canonical" "$marker" && test -d "$canonical" \
    && return "$(cat "$canonical/meta/operator-final-exit.txt")"
  test ! -e "$canonical" || return 70
  stage=$(mktemp -d \
    "$EVIDENCE_ROOT/cs2a-$CS2A_IMPLEMENTATION_SHA/.cs2a-archive-stage.XXXXXXXX") \
    || return 70
  case "$stage" in "$EVIDENCE_ROOT"/cs2a-*/.cs2a-archive-stage.*) ;; *) return 70 ;; esac
  mkdir "$stage/manifests" "$stage/results" "$stage/logs" "$stage/meta" \
    >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  for directory in manifests results logs meta; do
    rsync -a "$REMOTE_HOST:$run_root/$directory/" "$stage/$directory/" \
      >/dev/null 2>&1 || {
        report_archive_stage_failure "$stage" || :
        return 70
      }
  done
  validate_archive_safety "$stage" >/dev/null 2>&1 || {
    report_archive_stage_failure "$stage" || :
    return 70
  }
  publish_local_authority_file "$PWD/build/cs2a-supervisor.log" \
    "$stage/meta/operator-supervisor.log" >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  publish_local_authority_file "$PWD/build/cs2a-supervisor-exit.txt" \
    "$stage/meta/operator-supervisor-exit.txt" >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  publish_local_authority_value "$stage/meta/operator-post-supervisor-exit.txt" \
    "$post_status" >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  publish_local_authority_value "$stage/meta/operator-resume-validation-exit.txt" 0 \
    >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  profile=$(authenticated_archive_profile "$stage" "$CS2A_IMPLEMENTATION_SHA") \
    || profile=invalid
  case "$profile" in
    smoke)
      if test "$post_status" -eq 0 && prepare_local_driver \
        && validate_smoke_archive \
          "$stage" "$CS2A_IMPLEMENTATION_SHA" "$LOCAL_DRIVER"; then
        final_status=0
        local_validation=true
      fi
      ;;
    full)
      if test "$post_status" -eq 0 \
        && test "$(tr -d '\r\n' <"$stage/meta/profile.txt")" = full; then
        final_status=0
      fi
      ;;
    legacy)
      if test "$post_status" -eq 0; then final_status=0; fi
      ;;
    *) final_status=70 ;;
  esac
  publish_local_authority_value "$stage/meta/local-validation-passed.txt" "$local_validation" \
    >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  publish_local_authority_value "$stage/meta/operator-final-exit.txt" "$final_status" \
    >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  publish_archive "$stage" "$canonical" "$marker" "$CS2A_IMPLEMENTATION_SHA" \
    >/dev/null 2>&1 || {
      report_archive_stage_failure "$stage" || :
      return 70
    }
  printf 'LOCAL_EVIDENCE_DIR=%s\n' "$canonical"
  return "$final_status"
}

operator_main() {
  local mode=run profile=full attempt implementation evidence_sha resume_run resume_state status
  case "$#" in
    0) ;;
    1)
      test "$1" = --smoke || fail "invalid mode"
      profile=smoke
      ;;
    2)
      test "$1" = --persist-only || fail "invalid mode"
      mode=persist
      ;;
    4)
      test "$1" = --validate-attempt || fail "invalid mode"
      mode=validate
      attempt=$2
      implementation=$3
      evidence_sha=$4
      ;;
    3)
      test "$1" = --archive-only || fail "invalid mode"
      mode=archive
      resume_run=$2
      resume_state=$3
      ;;
    *) fail 'usage: cs2a-operator.sh [--smoke | --archive-only RUN_ROOT GOVERNOR_STATE | --persist-only STATUS | --validate-attempt ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA]' ;;
  esac
  CS2A_IMPLEMENTATION_SHA=$(tr -d '\r\n' <"$IMPLEMENTATION_FILE")
  require_sha "$CS2A_IMPLEMENTATION_SHA" || fail "invalid implementation SHA"
  readonly CS2A_IMPLEMENTATION_SHA
  if test "$mode" = persist; then persist_attempt "$2"; return; fi
  if test "$mode" = validate; then
    if test "$implementation" != "$CS2A_IMPLEMENTATION_SHA"; then
      fail "selection implementation does not match authenticated source"
      return 70
    fi
  fi
  if ! prepare_operator_source; then
    publish_local_operator_failure install 70 \
      || fail "unable to preserve install failure"
    return 70
  fi
  if test "$mode" = validate; then
    prepare_local_driver || return 70
    validate_persisted_attempt "$attempt" "$implementation" "$evidence_sha" "$LOCAL_DRIVER"
    return
  fi
  if test "$mode" = run; then
    if ! install_remote_bundle "$profile"; then
      publish_local_operator_failure install 70 \
        || fail "unable to preserve install failure"
      return 70
    fi
    if run_remote_supervisor; then status=0; else status=$?; fi
    if ! resume_run=$(extract_supervisor_marker \
      "$PWD/build/cs2a-supervisor.log" RUN_ROOT) \
      || ! resume_state=$(extract_supervisor_marker \
        "$PWD/build/cs2a-supervisor.log" GOVERNOR_STATE); then
      publish_local_operator_failure markers "$status" \
        || fail "unable to preserve markers failure"
      return 70
    fi
    if ! persist_original_post_status "$resume_state" "$status"; then
      publish_local_operator_failure post-status "$status" \
        || fail "unable to preserve post-status failure"
      return 70
    fi
    if refresh_remote_final_handoff "$resume_run" "$resume_state"; then
      :
    else
      status=$?
      publish_local_operator_failure final-handoff "$status" \
        || fail "unable to preserve final-handoff failure"
      return 70
    fi
  else
    verify_remote_bundle || return 70
    if validate_remote_final_handoff "$resume_run" "$resume_state"; then
      :
    else
      status=$?
      publish_local_operator_failure archive "$status" \
        || fail "unable to preserve archive validation failure"
      return 70
    fi
  fi
  archive_remote_attempt "$resume_run" "$resume_state"
}

if test "${BASH_SOURCE[0]}" = "$0"; then
  operator_main "$@"
fi
