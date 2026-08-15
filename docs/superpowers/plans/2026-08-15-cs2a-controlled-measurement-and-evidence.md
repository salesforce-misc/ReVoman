# CS2a Controlled Measurement and Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provision the reviewed protocol externally, run one authentic controlled CS2a session,
persist every outcome, and report the baseline-versus-candidate decision only when A/A and signed
archive gates permit it.

**Architecture:** The repository supplies reviewed immutable bytes but never provisions root trust
anchors. An administrator provisions the exact Plan 5 artifacts and policy, preparation stages one
token, and the human invokes the root-owned entry once. The controlled runner performs A/A first and
continues to cold/warm/retained candidate capture only on A/A PASS. Local collection immediately
verifies and commits signed evidence before semantic selection or reporting.

**Tech Stack:** The reviewed CS2a operator, native release artifacts, OpenSSH, root host policy,
Gradle benchmark driver, Git append-only evidence commits, jq, and independent fixed-evidence
review.

## Global Constraints

- Depends on the clean implementation SHA produced by Plan 5 and external administrator approval.
  No implementation edit, recipe update, policy relaxation, or regenerated artifact is allowed in
  this plan.
- `CS2A_RELEASE_SET` is the protected immutable Plan 5 CAS directory and
  `CS2A_RELEASE_CERTIFICATION` is Plan 5's no-clobber certification record outside that set. The
  separately authenticated administrator supplies its out-of-band
  `CS2A_RELEASE_CERTIFICATION_SHA256`. The reviewed release-set verifier derives
  `CS2A_RELEASE_MANIFEST_SHA256` only after that digest matches; no file or pointer under `build/` is
  an artifact authority.
- Fixed baseline is `83f3cd70f78ad733412d10cbc8287aaabafe7aac`. Candidate/harness is the
  exact reviewed implementation SHA. Use detached, clean checkouts for all roles.
- Preselected optional wins are unchanged: cold allocated-bytes upper-95 ratio `<= 0.85`; warm
  median-latency upper-95 ratio `<= 0.80`. They never replace normative release decisions.
- A consumed/claimed token is never reused. The sole exception is the design's pre-claim branch:
  when authenticated recovery proves the original human invocation never reached a root claim, the
  same prepared token remains pending and may receive another fresh human launch; minting a new token
  is forbidden. Any defect or objectively corrected host condition after claim requires a new
  implementation/config approval as applicable and a new token; preserve the old attempt first.
- A/A non-PASS is an authentic INCONCLUSIVE CS2a result and stops candidate capture. Do not rerun to
  seek PASS.
- Persist the authentic terminal outcome before semantic validation, review, clearance, retry, or
  source correction. Never amend an evidence commit.
- A performance claim requires signed archive-integrity PASS, terminal-matrix eligibility,
  recomputed classifier decisions, immutable evidence, and independent review.

---

### Task 1: Freeze the measured SHA and run proportional local smoke

**Files:**
- No tracked modifications.

**Interfaces:**
- Required shell inputs: `CS2A_IMPLEMENTATION_SHA`, `CS2A_RELEASE_SET`,
  `CS2A_RELEASE_CERTIFICATION`, `CS2A_RELEASE_CERTIFICATION_SHA256`, `CS2A_SMOKE_ROOT`, and
  `CS2A_SMOKE_CHECKOUT_ROOT`, all explicit absolute paths/values except the two digests.
- Release verification also requires the independently administrator-pinned root-owned
  `CS2A_BOOTSTRAP_VERIFIER_HOME`, `CS2A_BOOTSTRAP_JAVA_HOME`, and
  `CS2A_BOOTSTRAP_EXECUTOR`, the executor's out-of-band digest, the distinct post-build
  `CS2A_BOOTSTRAP_APPROVAL_RECORD`/digest, and the pre-code
  `CS2A_DESIGN_APPROVAL_RECORD`/digest. The post-build record binds the finished
  verifier/JDK/executor inventories; the pre-code record binds the prior design closure and is never
  mutated to name future artifacts. Candidate Gradle/build logic is never executed to authenticate
  itself.
- Smoke uses two detached baseline exports and detached candidate/harness exports, the existing
  `lifecycle.no-script-one-step.v1` workload, seed `5928239383101656625`, cold `2x1`, warm `2x1`
  with `1/3` warmup/iterations, plus the real retained-worker integration tests. It is plumbing
  evidence, not release performance evidence.

- [ ] **Step 1: Authenticate the implementation and release manifest**

```bash
: "${CS2A_IMPLEMENTATION_SHA:?set reviewed Plan 5 SHA}"
: "${CS2A_RELEASE_SET:?set immutable Plan 5 release-set directory}"
: "${CS2A_RELEASE_CERTIFICATION:?set immutable Plan 5 certification record}"
: "${CS2A_RELEASE_CERTIFICATION_SHA256:?set administrator-recorded certification SHA-256}"
: "${CS2A_BOOTSTRAP_VERIFIER_HOME:?set administrator-pinned verifier distribution}"
: "${CS2A_BOOTSTRAP_JAVA_HOME:?set administrator-pinned verifier JDK}"
: "${CS2A_BOOTSTRAP_EXECUTOR:?set administrator-pinned process-envelope executor}"
: "${CS2A_BOOTSTRAP_EXECUTOR_SHA256:?set its out-of-band SHA-256}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD:?set post-build bootstrap approval record}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256:?set its out-of-band SHA-256}"
: "${CS2A_DESIGN_APPROVAL_RECORD:?set approved design-closure record}"
: "${CS2A_DESIGN_APPROVAL_RECORD_SHA256:?set its out-of-band SHA-256}"
: "${CS2A_SMOKE_ROOT:?set an absolute empty smoke output directory}"
: "${CS2A_SMOKE_CHECKOUT_ROOT:?set an absolute empty checkout parent}"
test "${#CS2A_IMPLEMENTATION_SHA}" -eq 40
test -z "$(git status --porcelain --untracked-files=all)"
test "$(git rev-parse HEAD)" = "$CS2A_IMPLEMENTATION_SHA"
test "$(shasum -a 256 "$CS2A_BOOTSTRAP_EXECUTOR" | awk '{print $1}')" = \
  "$CS2A_BOOTSTRAP_EXECUTOR_SHA256"
set +e
CS2A_RELEASE_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-release-set \
  "$CS2A_RELEASE_SET" "$CS2A_RELEASE_CERTIFICATION" \
  "$CS2A_RELEASE_CERTIFICATION_SHA256" "$CS2A_IMPLEMENTATION_SHA" \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_RELEASE_VERIFY_STATUS=$?
set -e
test "$CS2A_RELEASE_VERIFY_STATUS" -eq 0
IFS=$'\t' read -r CS2A_RELEASE_RESULT_VERSION CS2A_RELEASE_RESULT_IMPLEMENTATION \
  CS2A_RELEASE_MANIFEST_SHA256 CS2A_RELEASE_INVENTORY_SHA256 \
  CS2A_RELEASE_RESULT_CERTIFICATION_SHA256 CS2A_RELEASE_SET_IDENTITY \
  CS2A_RELEASE_RESULT_EXTRA <<<"$CS2A_RELEASE_VERIFY_RESULT"
test "$CS2A_RELEASE_RESULT_VERSION" = revoman-cs2a-verified-release/v1
test "$CS2A_RELEASE_RESULT_IMPLEMENTATION" = "$CS2A_IMPLEMENTATION_SHA"
test "$CS2A_RELEASE_RESULT_CERTIFICATION_SHA256" = \
  "$CS2A_RELEASE_CERTIFICATION_SHA256"
test -z "$CS2A_RELEASE_RESULT_EXTRA"
test "${#CS2A_RELEASE_MANIFEST_SHA256}" -eq 64
test "${#CS2A_RELEASE_INVENTORY_SHA256}" -eq 64
```

Expected: clean reviewed HEAD and the exact complete immutable release set certified by Plan 5;
the verifier, not shell pathname checks, authenticates all types/modes/digests and anchored identity.

- [ ] **Step 2: Export detached checkouts and run smoke**

Use `git worktree add --detach` for two baseline checkouts at the full fixed SHA and harness plus
candidate checkouts at `CS2A_IMPLEMENTATION_SHA`. Assert clean detached state, create target
manifests with the checked-in init script, install the driver from the harness checkout, then run:

```bash
CS2A_BASELINE_SHA=83f3cd70f78ad733412d10cbc8287aaabafe7aac
CS2A_BASELINE_A="$CS2A_SMOKE_CHECKOUT_ROOT/baseline-a"
CS2A_BASELINE_B="$CS2A_SMOKE_CHECKOUT_ROOT/baseline-b"
CS2A_HARNESS="$CS2A_SMOKE_CHECKOUT_ROOT/harness"
CS2A_CANDIDATE="$CS2A_SMOKE_CHECKOUT_ROOT/candidate"
git worktree add --detach "$CS2A_BASELINE_A" "$CS2A_BASELINE_SHA"
git worktree add --detach "$CS2A_BASELINE_B" "$CS2A_BASELINE_SHA"
git worktree add --detach "$CS2A_HARNESS" "$CS2A_IMPLEMENTATION_SHA"
git worktree add --detach "$CS2A_CANDIDATE" "$CS2A_IMPLEMENTATION_SHA"
for pair in \
  "$CS2A_BASELINE_A:$CS2A_BASELINE_SHA" \
  "$CS2A_BASELINE_B:$CS2A_BASELINE_SHA" \
  "$CS2A_HARNESS:$CS2A_IMPLEMENTATION_SHA" \
  "$CS2A_CANDIDATE:$CS2A_IMPLEMENTATION_SHA"; do
  CS2A_CHECKOUT=${pair%%:*}
  CS2A_EXPECTED_SHA=${pair#*:}
  test "$(git -C "$CS2A_CHECKOUT" rev-parse HEAD)" = "$CS2A_EXPECTED_SHA"
  test -z "$(git -C "$CS2A_CHECKOUT" status --porcelain)"
  test -z "$(git -C "$CS2A_CHECKOUT" symbolic-ref -q HEAD || true)"
done
CS2A_SMOKE_INIT="$CS2A_HARNESS/benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts"
"$CS2A_HARNESS/gradlew" -p "$CS2A_HARNESS" \
  :benchmark-driver:installDist --no-daemon --console=plain
for target in baseline-a baseline-b candidate; do
  case "$target" in
    baseline-a) CS2A_TARGET_CHECKOUT=$CS2A_BASELINE_A ;;
    baseline-b) CS2A_TARGET_CHECKOUT=$CS2A_BASELINE_B ;;
    candidate) CS2A_TARGET_CHECKOUT=$CS2A_CANDIDATE ;;
  esac
  "$CS2A_TARGET_CHECKOUT/gradlew" -p "$CS2A_TARGET_CHECKOUT" \
    -I "$CS2A_SMOKE_INIT" clean writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest="$CS2A_SMOKE_ROOT/$target.json" \
    -Pbenchmark.targetId="$target-cs2a-smoke" --no-daemon --console=plain
done
CS2A_SMOKE_DRIVER="$CS2A_HARNESS/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver"
"$CS2A_SMOKE_DRIVER" run-paired --mode cold --intent smoke \
  --baseline "$CS2A_SMOKE_ROOT/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$CS2A_SMOKE_ROOT/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$CS2A_SMOKE_ROOT/cold-artifacts" \
  --output "$CS2A_SMOKE_ROOT/cold.json"
"$CS2A_SMOKE_DRIVER" run-paired --mode warm --intent smoke \
  --baseline "$CS2A_SMOKE_ROOT/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$CS2A_SMOKE_ROOT/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 1 --iterations 3 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$CS2A_SMOKE_ROOT/warm-artifacts" \
  --output "$CS2A_SMOKE_ROOT/warm.json"
"$CS2A_SMOKE_DRIVER" run-paired --mode cold --intent smoke \
  --baseline "$CS2A_SMOKE_ROOT/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$CS2A_SMOKE_ROOT/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$CS2A_SMOKE_ROOT/candidate-cold-artifacts" \
  --output "$CS2A_SMOKE_ROOT/candidate-cold.json"
"$CS2A_SMOKE_DRIVER" run-paired --mode warm --intent smoke \
  --baseline "$CS2A_SMOKE_ROOT/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
  --candidate "$CS2A_SMOKE_ROOT/candidate.json" --candidate-adapter major-v1 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 1 --iterations 3 --seed 5928239383101656625 --metrics latency \
  --artifacts-dir "$CS2A_SMOKE_ROOT/candidate-warm-artifacts" \
  --output "$CS2A_SMOKE_ROOT/candidate-warm.json"
"$CS2A_SMOKE_DRIVER" verify --input "$CS2A_SMOKE_ROOT/cold.json"
"$CS2A_SMOKE_DRIVER" verify --input "$CS2A_SMOKE_ROOT/warm.json"
"$CS2A_SMOKE_DRIVER" verify --input "$CS2A_SMOKE_ROOT/candidate-cold.json"
"$CS2A_SMOKE_DRIVER" verify --input "$CS2A_SMOKE_ROOT/candidate-warm.json"
for result in cold warm candidate-cold candidate-warm; do
  "$CS2A_SMOKE_DRIVER" compare \
    --input "$CS2A_SMOKE_ROOT/$result.json" \
    --output-json "$CS2A_SMOKE_ROOT/$result-comparison.json" \
    --output-md "$CS2A_SMOKE_ROOT/$result-comparison.md"
done
"$CS2A_HARNESS/gradlew" -p "$CS2A_HARNESS" :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle retained campaign preserves v2 series identity*' \
  -Pbenchmark.targetManifest="$CS2A_SMOKE_ROOT/candidate.json" \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: both A/A and both baseline-versus-candidate capture files verify, all four non-enforcing
smoke comparison commands produce valid reports, and retained integration passes. Tiny smoke
decisions are plumbing only and are never reported as release evidence. Any command failure blocks
remote use.

---

### Task 2: Complete external provisioning and prepare one token

**Files:**
- No tracked modifications. Administrator records live outside the repository; preparation writes
  ignored local state only.

**Interfaces:**
- Required explicit inputs are those of `--prepare-remote-session`: remote user/host, port, identity,
  host-key fingerprint, receipt-key fingerprint, trust policy and high-water paths/digests,
  verifier CAS/JDK, and runtime-config digest.
- The administrator installs only bytes opened from `CS2A_RELEASE_SET` after validating the complete
  release manifest, inventory, modes, implementation SHA, and manifest SHA-256. Provisioning records
  bind that release-set manifest digest; no rebuild, copy from `build/`, or same-named local binary
  may substitute for the CAS objects.
- External administrator action follows the design's exact order: install immutable entry/payload,
  copier, signer, and their provenance from the certified release set; separately generate or select
  the administrator-owned Ed25519 key pair outside the repository/release set/transport, retaining
  the private key only at its root-owned path and reconciling public-key identity/fingerprint;
  publish forced-reauthorization privilege policy/selection; publish namespace and
  controlled-account records; begin/probe/cache-test/invalidate/publish probe+complete; publish the
  dependency-only Gradle seed; publish runtime configuration; publish approval. Every publication
  participates in the lifecycle lock.

- [ ] **Step 1: Verify administrator provisioning evidence read-only**

Run the operator's preparation preflight only after the administrator confirms the exact release
set and its separate Plan 5 certification record, no active global token, no active probe lease, and
retained transport/key prerequisites. Do not add a release-manifest field to the design's closed
root approval schema. Instead, require every existing installer/copier/signer/source/recipe/
provenance/binary field in that approval to reconcile byte-for-byte with the certified release-set
inventory. Repository code must not install or repair a failed prerequisite.

- [ ] **Step 2: Prepare the remote session**

```bash
set +e
/bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  --prepare-remote-session \
  --remote-host "$CS2A_REMOTE_USER_HOST" \
  --ssh-port "$CS2A_SSH_PORT" \
  --ssh-identity-file "$CS2A_SSH_IDENTITY" \
  --ssh-host-key-fingerprint "$CS2A_SSH_HOST_KEY_FINGERPRINT" \
  --receipt-signing-key-fingerprint "$CS2A_RECEIPT_KEY_FINGERPRINT" \
  --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
  --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
  --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
  --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
  --local-verifier-cas "$CS2A_VERIFIER_CAS" \
  --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME" \
  --runtime-config-sha256 "$CS2A_RUNTIME_CONFIG_SHA256"
CS2A_PREPARE_STATUS=$?
set -e
```

Capture the preparation status without `set -e`. Status zero must yield one ignored authenticated
local session record, one new token, exactly five staged assets, and the literal instruction
`/opt/revoman-benchmark/cs2a-session-installer-v1 launch run TOKEN`. No password prompt occurs.

If preparation returns exact `provisioning-required`, require no token/stage/archive/commit, give the
administrator only the expected approval identity, complete provisioning, and restart Task 2; this
is not a measurement attempt. If another conclusive preparation failure occurs before publishing
the instruction, require the reviewed
operator's no-clobber `revoman-cs2a-local-setup-failure/v1` archive with
`remote-evidence-present=false` and its atomic scoped evidence commit, record that commit, and stop
this plan. This local-only transaction does not require the failed signed-evidence prerequisites.
If any launch instruction was already published, the branch is forbidden: retain the same pending
token and proceed only through native launch/collection/recovery.

- [ ] **Step 3: Freeze the token and preparation record**

Read the token only from the authenticated local record, set `CS2A_SESSION_TOKEN` to that exact
32-lowercase-hex value, and no-follow revalidate the record, transport identity, policy/high-water,
verifier CAS/JDK, implementation SHA, manifest, and runtime digest before human launch.

---

### Task 3: Perform the credential-bearing launch, recover if needed, collect, and persist immediately

**Files:**
- Add through the operator only:
  `docs/superpowers/benchmarks/results/v1/cs2a-$CS2A_IMPLEMENTATION_SHA/$CS2A_SESSION_TOKEN/**`

**Interfaces:**
- The human enters the exact absolute native command in the approved clean console/TTY/namespace
  context. The clean account/host prerequisite continues through receipt, READY, and
  terminal-observed publication.
- Collection is privilege-free and may be retried for the same token. If the launch outcome is
  uncertain, run token recovery; never prepare another token while the global ledger is active.
- Local `--recover-remote-session` only authenticates/classifies and prints an instruction. It cannot
  recover root state. Any printed native instruction is typed by the human in a new approved
  interactive TTY and is never piped, sourced, or evaluated.
- Loss/change/suspected compromise of the bound SSH host key or identity produces no recovery
  instruction and no authoritative attempt archive. Repository automation stops; a separately
  trusted administrator performs direct host containment/restoration/remediation and the design's
  manual-safety clearance, with persisted evidence commit `null` when no signed archive exists.
- Suspected defect or compromise of the installed native entry, payload, or its clearance path is a
  disjoint hard stop: do not execute that component for recovery or `clear-manual-safety`, and do
  not prepare a replacement token. A separately trusted administrator must use the design's
  independently reviewed replacement-clearance tool or external reprovision/reset procedure,
  authenticate the resulting cleared ledger/admission state, and only then permit incident
  reporting or a later implementation attempt.

- [ ] **Step 1: Human invokes the native entry once**

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 launch run CS2A_SESSION_TOKEN
```

Replace only the final token operand with the exact prepared token. Do not execute a staged script,
shell function, copied command file, `dzdo -S`, or password helper. Record the native status without
interpreting it as archive validity.

- [ ] **Step 2: Collect the signed terminal pair**

```bash
test -z "${CS2A_ATTEMPT_DIR-}"
test -z "${CS2A_ATTEMPT_REL-}"
test -z "${CS2A_COLLECT_INTENDED_REL-}"
unset CS2A_ATTEMPT_DIR CS2A_ATTEMPT_REL CS2A_COLLECT_INTENDED_REL
set +e
/bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  --collect-remote-session \
  --remote-host "$CS2A_REMOTE_USER_HOST" \
  --ssh-port "$CS2A_SSH_PORT" \
  --ssh-identity-file "$CS2A_SSH_IDENTITY" \
  --ssh-host-key-fingerprint "$CS2A_SSH_HOST_KEY_FINGERPRINT" \
  --receipt-signing-key-fingerprint "$CS2A_RECEIPT_KEY_FINGERPRINT" \
  --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
  --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
  --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
  --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
  --local-verifier-cas "$CS2A_VERIFIER_CAS" \
  --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME" \
  --session-token "$CS2A_SESSION_TOKEN"
CS2A_COLLECT_STATUS=$?
set -e
if test "$CS2A_COLLECT_STATUS" -eq 75; then
  CS2A_COLLECT_DISPOSITION=active-uncertain
  test ! -e "build/cs2a-collector-result.$CS2A_SESSION_TOKEN.json"
else
  set +e
  CS2A_COLLECTOR_RESULT_LINE=$(/bin/bash \
    docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --read-collector-result "$CS2A_SESSION_TOKEN" "$CS2A_COLLECT_STATUS")
  CS2A_COLLECTOR_READ_STATUS=$?
  set -e
  test "$CS2A_COLLECTOR_READ_STATUS" -eq 0
  IFS=$'\t' read -r CS2A_COLLECTOR_RESULT_VERSION CS2A_COLLECT_DISPOSITION \
    CS2A_COLLECT_INTENDED_REL CS2A_ARCHIVE_TREE_SHA256 \
    CS2A_COLLECT_INCIDENT_PROVENANCE CS2A_COLLECTOR_EXTRA \
    <<<"$CS2A_COLLECTOR_RESULT_LINE"
  test "$CS2A_COLLECTOR_RESULT_VERSION" = revoman-cs2a-collector-result/v1
  test -z "$CS2A_COLLECTOR_EXTRA"
  case "$CS2A_COLLECT_DISPOSITION" in
    signed-staged)
      test "$CS2A_COLLECT_INTENDED_REL" != -
      test "${#CS2A_ARCHIVE_TREE_SHA256}" -eq 64
      case "$CS2A_COLLECT_INCIDENT_PROVENANCE" in
        terminal-sha256:*)
          CS2A_SIGNED_TERMINAL_SHA256=${CS2A_COLLECT_INCIDENT_PROVENANCE#terminal-sha256:}
          test "${#CS2A_SIGNED_TERMINAL_SHA256}" -eq 64
          case "$CS2A_SIGNED_TERMINAL_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
          ;;
        *) exit 70 ;;
      esac
      case "$CS2A_COLLECT_INTENDED_REL" in
        docs/superpowers/benchmarks/results/v1/cs2a-"$CS2A_IMPLEMENTATION_SHA"/"$CS2A_SESSION_TOKEN"/*) ;;
        *) exit 70 ;;
      esac
      ;;
    safety-only|external-admin-required)
      test "$CS2A_COLLECT_INTENDED_REL" = -
      test "$CS2A_ARCHIVE_TREE_SHA256" = -
      unset CS2A_COLLECT_INTENDED_REL
      unset CS2A_ATTEMPT_DIR CS2A_ATTEMPT_REL CS2A_EVIDENCE_SHA \
        CS2A_VALIDATION_SHA CS2A_VALIDATION_REL
      CS2A_REPORT_KIND=incident-no-authority
      CS2A_NO_AUTHORITY_REASON=$CS2A_COLLECT_DISPOSITION
      case "$CS2A_COLLECT_INCIDENT_PROVENANCE" in
        quarantine-sha256:*|absence:root-quarantine-publication-failed|\
        absence:root-signer-unavailable-before-terminal)
          CS2A_INCIDENT_PROVENANCE=$CS2A_COLLECT_INCIDENT_PROVENANCE ;;
        admin-inspection-required) unset CS2A_INCIDENT_PROVENANCE ;;
        *) exit 70 ;;
      esac
      ;;
    *) exit 70 ;;
  esac
fi
```

Status `75` is the design-closure's sole retryable `active-uncertain` status and has no durable
collector marker. The read-only mode itself no-follow opens the token-derived terminal result marker, verifies its canonical
bytes and cross-links to the local session/implementation/token and captured collector status, and
emits exactly one line. A `signed-staged` line names only the intended canonical relative path and
the authenticated protected-stage tree digest; no canonical attempt exists yet. The shell never
reads marker JSON or accepts an attempt path from the caller. Raw path existence is not a
precondition on restart: it may belong to the exact prepared persistence transaction, and only the
token-bound persistence inspector/adopter may distinguish that state from a conflict.

- [ ] **Step 3: Complete the explicit human recovery branch when collection is not terminal**

Only when Step 2 returns the closed active/uncertain disposition, run:

```bash
set +e
CS2A_RECOVERY_INSTRUCTION=$(/bin/bash \
  docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  --recover-remote-session \
  --remote-host "$CS2A_REMOTE_USER_HOST" \
  --ssh-port "$CS2A_SSH_PORT" \
  --ssh-identity-file "$CS2A_SSH_IDENTITY" \
  --ssh-host-key-fingerprint "$CS2A_SSH_HOST_KEY_FINGERPRINT" \
  --receipt-signing-key-fingerprint "$CS2A_RECEIPT_KEY_FINGERPRINT" \
  --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
  --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
  --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
  --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
  --local-verifier-cas "$CS2A_VERIFIER_CAS" \
  --session-token "$CS2A_SESSION_TOKEN")
CS2A_RECOVERY_COORDINATOR_STATUS=$?
set -e
```

No shell executes the captured value. Branch on the closed producer status before displaying
anything:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 launch recover CS2A_SESSION_TOKEN
```

```bash
case "$CS2A_RECOVERY_COORDINATOR_STATUS" in
  0)
    CS2A_EXPECTED_RECOVERY_INSTRUCTION="/opt/revoman-benchmark/cs2a-session-installer-v1 launch recover $CS2A_SESSION_TOKEN"
    test "$CS2A_RECOVERY_INSTRUCTION" = "$CS2A_EXPECTED_RECOVERY_INSTRUCTION"
    printf '%s\n' "$CS2A_EXPECTED_RECOVERY_INSTRUCTION"
    unset CS2A_CHANGED_BOOT_RECOVERY_REQUIRED
    ;;
  76)
    test -z "$CS2A_RECOVERY_INSTRUCTION"
    set +e
    CS2A_RECOVERY_DISPOSITION_LINE=$(/bin/bash \
      docs/superpowers/benchmarks/operators/cs2a-operator.sh \
      --read-recovery-disposition "$CS2A_SESSION_TOKEN" 76)
    CS2A_RECOVERY_DISPOSITION_STATUS=$?
    set -e
    test "$CS2A_RECOVERY_DISPOSITION_STATUS" -eq 0
    IFS=$'\t' read -r CS2A_RECOVERY_DISPOSITION_VERSION \
      CS2A_RECOVERY_DISPOSITION CS2A_RECOVERY_DISPOSITION_TOKEN \
      CS2A_RECOVERY_DISPOSITION_SEQUENCE CS2A_RECOVERY_PREVIOUS_RECORD_SHA256 \
      CS2A_RECOVERY_DISPOSITION_SHA256 \
      CS2A_RECOVERY_SEALED_BOOT_SHA256 CS2A_RECOVERY_CURRENT_BOOT_SHA256 \
      CS2A_RECOVERY_NAMESPACE_OBSERVATION_SHA256 CS2A_RECOVERY_DISPOSITION_EXTRA \
      <<<"$CS2A_RECOVERY_DISPOSITION_LINE"
    test "$CS2A_RECOVERY_DISPOSITION_VERSION" = revoman-cs2a-recovery-disposition/v1
    test "$CS2A_RECOVERY_DISPOSITION" = changed-boot-publication-required
    test "$CS2A_RECOVERY_DISPOSITION_TOKEN" = "$CS2A_SESSION_TOKEN"
    test -z "$CS2A_RECOVERY_DISPOSITION_EXTRA"
    case "$CS2A_RECOVERY_DISPOSITION_SEQUENCE" in ''|*[!0-9]*) exit 70 ;; esac
    if test "$CS2A_RECOVERY_PREVIOUS_RECORD_SHA256" != null; then
      test "${#CS2A_RECOVERY_PREVIOUS_RECORD_SHA256}" -eq 64
      case "$CS2A_RECOVERY_PREVIOUS_RECORD_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
    fi
    for CS2A_RECOVERY_DIGEST in "$CS2A_RECOVERY_DISPOSITION_SHA256" \
      "$CS2A_RECOVERY_SEALED_BOOT_SHA256" \
      "$CS2A_RECOVERY_CURRENT_BOOT_SHA256" \
      "$CS2A_RECOVERY_NAMESPACE_OBSERVATION_SHA256"; do
      test "${#CS2A_RECOVERY_DIGEST}" -eq 64
      case "$CS2A_RECOVERY_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
    done
    test "$CS2A_RECOVERY_SEALED_BOOT_SHA256" != "$CS2A_RECOVERY_CURRENT_BOOT_SHA256"
    if test -n "${CS2A_LAST_RECOVERY_DISPOSITION_SEQUENCE-}"; then
      test "$CS2A_RECOVERY_DISPOSITION_SEQUENCE" -eq \
        $((CS2A_LAST_RECOVERY_DISPOSITION_SEQUENCE + 1))
      test "$CS2A_RECOVERY_PREVIOUS_RECORD_SHA256" = \
        "$CS2A_LAST_RECOVERY_DISPOSITION_SHA256"
      test "$CS2A_RECOVERY_CURRENT_BOOT_SHA256" != \
        "$CS2A_LAST_RECOVERY_CURRENT_BOOT_SHA256"
    fi
    CS2A_LAST_RECOVERY_DISPOSITION_SEQUENCE=$CS2A_RECOVERY_DISPOSITION_SEQUENCE
    CS2A_LAST_RECOVERY_DISPOSITION_SHA256=$CS2A_RECOVERY_DISPOSITION_SHA256
    CS2A_LAST_RECOVERY_CURRENT_BOOT_SHA256=$CS2A_RECOVERY_CURRENT_BOOT_SHA256
    CS2A_CHANGED_BOOT_RECOVERY_REQUIRED=true
    ;;
  *) exit 70 ;;
esac
```

If `CS2A_CHANGED_BOOT_RECOVERY_REQUIRED=true`, stop before printing any launch line. A separately trusted
administrator first stages the exact root-owned digest-derived namespace candidates and invokes the
reapproved direct-root command in a clean root context:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 publish-recovery-namespace CS2A_SESSION_TOKEN ATTESTATION_SHA256 SELECTION_SHA256
```

The administrator substitutes only the authenticated token and candidate digests, verifies the
parent-fsynced `recovery-current-only` selection/result for that same active token or its unique
complete durable pre-ledger claim/header, and records its
identity plus the exact disposition sequence/digest/previous link for recovery evidence. Repository automation never publishes it. Same-boot use or any
attempt to make it fresh-run eligible is fatal. Only after that durable result does the coordinator
rerun this entire step. It may return another `76` only for a genuinely new boot and the reader must
prove the next contiguous sequence and previous-record digest; publish that next recovery-only
selection and repeat. Each iteration is bounded by the frozen record limits and corresponds to one
authenticated boot change—same-boot replay, a gap/fork, or any other result is a hard stop. Status
`0` finally authenticates the unchanged local session plus the latest published recovery selection
and prints the sole `launch recover TOKEN` instruction.

The human types it in a **new** approved interactive TTY and records its status; only then repeat
Step 2. A locally revoked key makes this safety-only and no resulting signature can become
authoritative. If the native/admission path reaches the design's true no-root-claim case, unsigned
local or SSH absence still closes nothing: do not persist it or prepare a new token. Follow only the
exact same-token retry authority/procedure reapproved in Plan 1's design closure; absent that
addendum, stop with the token pending. Any other/missing/multiple line blocks.

If the coordinator cannot authenticate the exact retained host key/identity, it must return no
instruction. Stop automated recovery/collection, preserve only bounded incident diagnostics outside
the authoritative evidence tree, have the separate administrator prove direct containment,
governor/lock restoration and host repair/reprovision, then use Task 4's manual-safety branch and end
this attempt with no archive-integrity or performance claim. Set
`CS2A_REPORT_KIND=incident-no-authority`, but leave incident provenance unset until that direct
administrator authenticates the actual terminal/quarantine digest or exact root publication-
failure/absence proof. A transport-authentication failure is not an absence proof. Never set
attempt/evidence/validation variables.

- [ ] **Step 4: Persist before validation or review**

Run this step only when the collector marker authenticates a signed staged claim-terminal/READY
pair. Collection has not published a canonical attempt. Persistence rechecks current policy and
high-water, then either atomically adopts one evidence commit or emits a terminal
nonauthoritative revoked/drift/quarantine result. Its manual request may bind that quarantine digest
or the design-permitted exact absence reason and `persisted evidence commit=null`.

```bash
: "${CS2A_COLLECT_DISPOSITION:?must come from collection result}"
test "$CS2A_COLLECT_DISPOSITION" = signed-staged
: "${CS2A_COLLECT_INTENDED_REL:?must come from collector-result read mode}"
unset CS2A_ATTEMPT_DIR CS2A_ATTEMPT_REL CS2A_EVIDENCE_SHA
for CS2A_PERSIST_PASS in 1 2; do
  set +e
  /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --persist-only "$CS2A_COLLECT_STATUS" \
    --session-token "$CS2A_SESSION_TOKEN" \
    --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
    --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
    --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
    --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
    --local-verifier-cas "$CS2A_VERIFIER_CAS" \
    --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME"
  CS2A_PERSIST_STATUS=$?
  CS2A_PERSISTENCE_RESULT_LINE=$(/bin/bash \
    docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --read-persistence-result "$CS2A_SESSION_TOKEN" "$CS2A_PERSIST_STATUS")
  CS2A_PERSISTENCE_READ_STATUS=$?
  set -e
  if test "$CS2A_PERSISTENCE_READ_STATUS" -eq 75 && \
     test "$CS2A_PERSIST_PASS" -eq 1; then
    continue
  fi
  if test "$CS2A_PERSISTENCE_READ_STATUS" -ne 0; then
    exit 70
  fi
  IFS=$'\t' read -r CS2A_PERSISTENCE_VERSION CS2A_PERSISTENCE_DISPOSITION \
    CS2A_PERSISTENCE_ATTEMPT_REL CS2A_PERSISTENCE_EVIDENCE_SHA \
    CS2A_PERSISTENCE_INCIDENT_PROVENANCE CS2A_PERSISTENCE_EXTRA \
    <<<"$CS2A_PERSISTENCE_RESULT_LINE"
  test "$CS2A_PERSISTENCE_VERSION" = revoman-cs2a-persistence-result/v1
  test -z "$CS2A_PERSISTENCE_EXTRA"
  case "$CS2A_PERSISTENCE_DISPOSITION" in
    persisted-signed)
      test "$CS2A_PERSISTENCE_ATTEMPT_REL" = "$CS2A_COLLECT_INTENDED_REL"
      case "$CS2A_PERSISTENCE_INCIDENT_PROVENANCE" in
        terminal-sha256:*)
          CS2A_SIGNED_TERMINAL_SHA256=${CS2A_PERSISTENCE_INCIDENT_PROVENANCE#terminal-sha256:}
          test "${#CS2A_SIGNED_TERMINAL_SHA256}" -eq 64
          case "$CS2A_SIGNED_TERMINAL_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
          ;;
        *) exit 70 ;;
      esac
      CS2A_ATTEMPT_REL=$CS2A_PERSISTENCE_ATTEMPT_REL
      CS2A_ATTEMPT_DIR="$PWD/$CS2A_ATTEMPT_REL"
      CS2A_EVIDENCE_SHA=$CS2A_PERSISTENCE_EVIDENCE_SHA
      test "$(git rev-parse "$CS2A_EVIDENCE_SHA^{commit}")" = "$CS2A_EVIDENCE_SHA"
      test "$(git rev-list --reverse HEAD -- "$CS2A_ATTEMPT_REL" | sed -n '1p')" = \
        "$CS2A_EVIDENCE_SHA"
      test -d "$CS2A_ATTEMPT_DIR"
      test -z "$(git status --porcelain --untracked-files=all)"
      CS2A_REPORT_KIND=signed-evidence
      ;;
    current-revoked|current-drift|quarantined)
      test "$CS2A_PERSISTENCE_ATTEMPT_REL" = -
      test "$CS2A_PERSISTENCE_EVIDENCE_SHA" = -
      case "$CS2A_PERSISTENCE_INCIDENT_PROVENANCE" in
        terminal-sha256:*)
          CS2A_PERSISTENCE_TERMINAL_SHA256=${CS2A_PERSISTENCE_INCIDENT_PROVENANCE#terminal-sha256:}
          test "${#CS2A_PERSISTENCE_TERMINAL_SHA256}" -eq 64
          case "$CS2A_PERSISTENCE_TERMINAL_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
          test "$CS2A_PERSISTENCE_TERMINAL_SHA256" = "$CS2A_SIGNED_TERMINAL_SHA256"
          ;;
        *) exit 70 ;;
      esac
      test ! -e "$CS2A_COLLECT_INTENDED_REL"
      unset CS2A_ATTEMPT_DIR CS2A_ATTEMPT_REL CS2A_EVIDENCE_SHA \
        CS2A_VALIDATION_SHA CS2A_VALIDATION_REL
      CS2A_REPORT_KIND=incident-no-authority
      CS2A_NO_AUTHORITY_REASON=$CS2A_PERSISTENCE_DISPOSITION
      CS2A_INCIDENT_PROVENANCE=$CS2A_PERSISTENCE_INCIDENT_PROVENANCE
      ;;
    *) exit 70 ;;
  esac
  break
done
```

The first retry is allowed only when the read-only inspector returns the reapproved `75` for one
exact prepared/adoptable transaction. A second incomplete or any forked/corrupt shape is
a forensic hard stop: this plan neither clears nor reports from the ambient tree until a separately
approved procedure preserves and reconciles that exact state. A durable terminal result is
authoritative regardless of the producer's process status. Only `persisted-signed` permits Task 4
Steps 1-2; every other authenticated terminal disposition keeps evidence and validation identities
absent and proceeds to manual safety. A successful result names one ordinary non-merge evidence
commit changing only the canonical attempt directory, which is clean/tracked afterward. Authentic
nonzero benchmark outcomes are preserved identically.

---

### Task 4: Review archive integrity, classify when eligible, and request clearance

**Files:**
- No source modifications. Validation may add derived results only through the reviewed operator's
  no-clobber evidence protocol.

**Interfaces:**
- `CS2A_EVIDENCE_SHA` is the first commit containing the attempt directory. Validation rechecks
  signed pair, verifier closure, policy/high-water, all inventories and manifests, immutable commit
  ancestry, terminal matrix, and existing benchmark classifier.
- `CS2A_VALIDATION_PATH` is exactly
  `CS2A_ATTEMPT_DIR/local-validation/revoman-cs2a-attempt-validation-v1.json`;
  `CS2A_VALIDATION_SHA` is the direct-child scoped commit that first adds it. The record and commit
  remain authoritative machine handoff even when validator status is nonzero.
- A/A PASS is required for candidate stage/results. A/A non-PASS produces no candidate decision and
  no retry. Normative cold/warm/retained thresholds remain those in `RegressionPolicy.kt`.
- With no authoritative signed archive, Steps 1-2 are forbidden. The separately authenticated
  administrator performs only the manual-safety half of Step 3 after direct remediation; the report
  is an incident/no-authority report and contains no validation SHA or performance decision.

- [ ] **Step 1: Independently review the immutable archive before selection**

Review the exact evidence commit for source/implementation identities, signature/commit pair,
terminal kind/status, clean-host result, transition/global heads, command/status logs, quota and
inventory totals, manifests, rejected blocks, and absence of unlisted bytes. Do not rely on a
publication-time Boolean.

- [ ] **Step 2: Run the sole authoritative semantic validation**

```bash
: "${CS2A_EVIDENCE_SHA:?must come from authenticated persistence result}"
CS2A_ATTEMPT_REL=${CS2A_ATTEMPT_DIR#"$PWD"/}
test "$CS2A_ATTEMPT_REL" != "$CS2A_ATTEMPT_DIR"
CS2A_FIRST_EVIDENCE_SHA=$(git rev-list --reverse HEAD -- "$CS2A_ATTEMPT_REL" | sed -n '1p')
test "$CS2A_FIRST_EVIDENCE_SHA" = "$CS2A_EVIDENCE_SHA"
CS2A_VALIDATION_PATH="$CS2A_ATTEMPT_DIR/local-validation/revoman-cs2a-attempt-validation-v1.json"
CS2A_VALIDATION_CREATED=false
CS2A_VALIDATION_ADOPTION_ATTEMPTED=false
CS2A_VALIDATION_ABANDONED_FINALIZATION_ATTEMPTED=false
CS2A_VALIDATION_REPEAT_LINE=
CS2A_VALIDATION_REPEAT_STATUS=70
for CS2A_VALIDATION_PASS in 1 2 3 4; do
  set +e
  CS2A_VALIDATION_REPEAT_LINE=$(/bin/bash \
    docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --read-attempt-validation "$CS2A_ATTEMPT_DIR" \
    "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
    --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
    --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
    --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
    --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
    --local-verifier-cas "$CS2A_VERIFIER_CAS" \
    --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME")
  CS2A_VALIDATION_REPEAT_STATUS=$?
  set -e
  case "$CS2A_VALIDATION_REPEAT_STATUS" in
    0|4|5|6) break ;;
    2)
      test -z "$CS2A_VALIDATION_REPEAT_LINE"
      if test "$CS2A_VALIDATION_CREATED" = false; then
        set +e
        /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
          --validate-attempt "$CS2A_ATTEMPT_DIR" \
          "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
          --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
          --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
          --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
          --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
          --local-verifier-cas "$CS2A_VERIFIER_CAS" \
          --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME"
        CS2A_VALIDATE_PROCESS_STATUS=$?
        set -e
        CS2A_VALIDATION_CREATED=true
        continue
      fi
      break
      ;;
    75)
      test -z "$CS2A_VALIDATION_REPEAT_LINE"
      if test "$CS2A_VALIDATION_ADOPTION_ATTEMPTED" = false; then
        set +e
        /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
          --adopt-attempt-validation "$CS2A_ATTEMPT_DIR" \
          "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
          --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
          --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
          --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
          --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
          --local-verifier-cas "$CS2A_VERIFIER_CAS" \
          --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME"
        CS2A_VALIDATION_ADOPT_PROCESS_STATUS=$?
        set -e
        CS2A_VALIDATION_ADOPTION_ATTEMPTED=true
        continue
      fi
      break
      ;;
    77)
      IFS=$'\t' read -r CS2A_VALIDATION_ABANDONED_VERSION \
        CS2A_VALIDATION_ABANDONED_DISPOSITION CS2A_VALIDATION_ABANDONED_TOKEN \
        CS2A_VALIDATION_ATTEMPT_SHA256 CS2A_VALIDATION_OWNER_ABSENCE_PROOF_SHA256 \
        CS2A_VALIDATION_ABANDONED_EXTRA <<<"$CS2A_VALIDATION_REPEAT_LINE"
      test "$CS2A_VALIDATION_ABANDONED_VERSION" = revoman-cs2a-validation-abandoned/v1
      test "$CS2A_VALIDATION_ABANDONED_DISPOSITION" = owner-abandoned
      test "$CS2A_VALIDATION_ABANDONED_TOKEN" = "$CS2A_SESSION_TOKEN"
      test -z "$CS2A_VALIDATION_ABANDONED_EXTRA"
      for CS2A_VALIDATION_ABANDONED_DIGEST in "$CS2A_VALIDATION_ATTEMPT_SHA256" \
        "$CS2A_VALIDATION_OWNER_ABSENCE_PROOF_SHA256"; do
        test "${#CS2A_VALIDATION_ABANDONED_DIGEST}" -eq 64
        case "$CS2A_VALIDATION_ABANDONED_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
      done
      if test "$CS2A_VALIDATION_ABANDONED_FINALIZATION_ATTEMPTED" = false; then
        set +e
        /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
          --finalize-abandoned-attempt-validation "$CS2A_ATTEMPT_DIR" \
          "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
          "$CS2A_VALIDATION_ATTEMPT_SHA256" \
          "$CS2A_VALIDATION_OWNER_ABSENCE_PROOF_SHA256" \
          --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
          --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
          --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
          --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
          --local-verifier-cas "$CS2A_VERIFIER_CAS" \
          --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME"
        CS2A_VALIDATION_ABANDONED_FINALIZE_PROCESS_STATUS=$?
        set -e
        CS2A_VALIDATION_ABANDONED_FINALIZATION_ATTEMPTED=true
        continue
      fi
      break
      ;;
    *) break ;;
  esac
done
IFS=$'\t' read -r CS2A_VALIDATION_REPEAT_VERSION \
  CS2A_VALIDATION_REPEAT_DISPOSITION CS2A_VALIDATION_ORIGINAL_STATUS \
  CS2A_VALIDATION_CURRENT_POLICY_SHA256 CS2A_VALIDATION_CURRENT_HIGH_WATER_SHA256 \
  CS2A_VALIDATION_REPEAT_EXTRA <<<"$CS2A_VALIDATION_REPEAT_LINE"
CS2A_VALIDATION_PROTOCOL_OK=false
CS2A_VALIDATION_REPORT_KIND=
CS2A_VALIDATION_NO_AUTHORITY_REASON=
if test "$CS2A_VALIDATION_REPEAT_VERSION" = revoman-cs2a-repeat-validation/v1 && \
   test -z "$CS2A_VALIDATION_REPEAT_EXTRA"; then
  case "$CS2A_VALIDATION_REPEAT_DISPOSITION" in
    unchanged)
      if test "$CS2A_VALIDATION_REPEAT_STATUS" -eq 0 && \
         test "$CS2A_VALIDATION_CURRENT_POLICY_SHA256" = \
           "$CS2A_RECEIPT_TRUST_POLICY_SHA256" && \
         test "$CS2A_VALIDATION_CURRENT_HIGH_WATER_SHA256" = \
           "$CS2A_RECEIPT_HIGH_WATER_SHA256"; then
        CS2A_VALIDATION_PROTOCOL_OK=true
        CS2A_VALIDATION_REPORT_KIND=signed-evidence
      fi
      ;;
    current-revoked|current-drift)
      CS2A_EXPECTED_REPEAT_STATUS=4
      if test "$CS2A_VALIDATION_REPEAT_DISPOSITION" = current-drift; then
        CS2A_EXPECTED_REPEAT_STATUS=5
      fi
      if test "$CS2A_VALIDATION_REPEAT_STATUS" -eq "$CS2A_EXPECTED_REPEAT_STATUS" && \
         test "${#CS2A_VALIDATION_CURRENT_POLICY_SHA256}" -eq 64 && \
         test "${#CS2A_VALIDATION_CURRENT_HIGH_WATER_SHA256}" -eq 64; then
        CS2A_VALIDATION_PROTOCOL_OK=true
        CS2A_VALIDATION_REPORT_KIND=signed-evidence-no-authority
        CS2A_VALIDATION_NO_AUTHORITY_REASON=preclearance-$CS2A_VALIDATION_REPEAT_DISPOSITION
        : "${CS2A_SIGNED_TERMINAL_SHA256:?bind authenticated signed terminal}"
        CS2A_INCIDENT_PROVENANCE=terminal-sha256:$CS2A_SIGNED_TERMINAL_SHA256
      fi
      ;;
  esac
fi
CS2A_VALIDATION_COMMIT_OK=false
if test "$CS2A_VALIDATION_PROTOCOL_OK" = true && test -f "$CS2A_VALIDATION_PATH"; then
  CS2A_VALIDATION_REL=${CS2A_VALIDATION_PATH#"$PWD"/}
  CS2A_VALIDATION_COMMITS=$(git rev-list --reverse \
    "$CS2A_EVIDENCE_SHA..HEAD" -- "$CS2A_VALIDATION_REL")
  CS2A_VALIDATION_COMMIT_COUNT=$(printf '%s\n' "$CS2A_VALIDATION_COMMITS" | \
    sed '/^$/d' | wc -l | tr -d ' ')
  if test "$CS2A_VALIDATION_COMMIT_COUNT" = 1; then
    CS2A_VALIDATION_CANDIDATE_SHA=$CS2A_VALIDATION_COMMITS
    CS2A_VALIDATION_PARENT=$(git rev-parse \
      "$CS2A_VALIDATION_CANDIDATE_SHA^" 2>/dev/null || true)
    if test "$CS2A_VALIDATION_PARENT" = "$CS2A_EVIDENCE_SHA"; then
      CS2A_VALIDATION_COMMIT_OK=true
    fi
  fi
fi
CS2A_VALIDATION_FAILURE_OK=false
if test "$CS2A_VALIDATION_REPEAT_STATUS" -eq 6; then
  IFS=$'\t' read -r CS2A_VALIDATION_FAILURE_VERSION \
    CS2A_VALIDATION_FAILURE_DISPOSITION CS2A_VALIDATION_FAILURE_CHILD_STATUS \
    CS2A_VALIDATION_FAILURE_PROOF_SHA256 \
    CS2A_VALIDATION_FAILURE_CURRENT_TRUST_DISPOSITION \
    CS2A_VALIDATION_FAILURE_CURRENT_POLICY_SHA256 \
    CS2A_VALIDATION_FAILURE_CURRENT_HIGH_WATER_SHA256 \
    CS2A_VALIDATION_FAILURE_EXTRA \
    <<<"$CS2A_VALIDATION_REPEAT_LINE"
  if test "$CS2A_VALIDATION_FAILURE_VERSION" = revoman-cs2a-validation-failed/v1 && \
     test -z "$CS2A_VALIDATION_FAILURE_EXTRA" && \
     test "${#CS2A_VALIDATION_FAILURE_PROOF_SHA256}" -eq 64 && \
     test "${#CS2A_VALIDATION_FAILURE_CURRENT_POLICY_SHA256}" -eq 64 && \
     test "${#CS2A_VALIDATION_FAILURE_CURRENT_HIGH_WATER_SHA256}" -eq 64 && \
     test ! -e "$CS2A_VALIDATION_PATH" && \
     test "$(git rev-parse HEAD)" = "$CS2A_EVIDENCE_SHA" && \
     test -z "$(git status --porcelain --untracked-files=all)"; then
    case "$CS2A_VALIDATION_FAILURE_CURRENT_TRUST_DISPOSITION" in
      unchanged|current-revoked|current-drift) ;;
      *) exit 70 ;;
    esac
    for CS2A_VALIDATION_FAILURE_DIGEST in \
      "$CS2A_VALIDATION_FAILURE_PROOF_SHA256" \
      "$CS2A_VALIDATION_FAILURE_CURRENT_POLICY_SHA256" \
      "$CS2A_VALIDATION_FAILURE_CURRENT_HIGH_WATER_SHA256"; do
      case "$CS2A_VALIDATION_FAILURE_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
    done
    case "$CS2A_VALIDATION_FAILURE_DISPOSITION" in
      semantic-failed|interrupted)
        if test "$CS2A_VALIDATION_FAILURE_CHILD_STATUS" -ge 1 2>/dev/null && \
           test "$CS2A_VALIDATION_FAILURE_CHILD_STATUS" -le 255; then
          CS2A_VALIDATION_FAILURE_OK=true
        fi
        ;;
      owner-abandoned)
        if test "$CS2A_VALIDATION_FAILURE_CHILD_STATUS" = -; then
          CS2A_VALIDATION_FAILURE_OK=true
        fi
        ;;
      preflight-current-revoked)
        if test "$CS2A_VALIDATION_FAILURE_CHILD_STATUS" = - && \
           test "$CS2A_VALIDATION_FAILURE_CURRENT_TRUST_DISPOSITION" = current-revoked; then
          CS2A_VALIDATION_FAILURE_OK=true
        fi
        ;;
      preflight-current-drift)
        if test "$CS2A_VALIDATION_FAILURE_CHILD_STATUS" = -; then
          case "$CS2A_VALIDATION_FAILURE_CURRENT_TRUST_DISPOSITION" in
            current-drift|current-revoked) CS2A_VALIDATION_FAILURE_OK=true ;;
          esac
        fi
        ;;
    esac
  fi
fi
if test "$CS2A_VALIDATION_PROTOCOL_OK" = true && \
   test "$CS2A_VALIDATION_COMMIT_OK" = true; then
  CS2A_VALIDATION_SHA=$CS2A_VALIDATION_CANDIDATE_SHA
  CS2A_VALIDATION_SHA256=$(shasum -a 256 "$CS2A_VALIDATION_PATH" | awk '{print $1}')
  CS2A_REPORT_KIND=$CS2A_VALIDATION_REPORT_KIND
  if test -n "$CS2A_VALIDATION_NO_AUTHORITY_REASON"; then
    CS2A_NO_AUTHORITY_REASON=$CS2A_VALIDATION_NO_AUTHORITY_REASON
  fi
elif test "$CS2A_VALIDATION_FAILURE_OK" = true; then
  unset CS2A_VALIDATION_REL CS2A_VALIDATION_SHA CS2A_VALIDATION_SHA256 \
    CS2A_VALIDATION_CANDIDATE_SHA
  CS2A_REPORT_KIND=signed-evidence-unvalidated-no-authority
  case "$CS2A_VALIDATION_FAILURE_DISPOSITION" in
    preflight-current-revoked|preflight-current-drift)
      CS2A_NO_AUTHORITY_REASON=preclearance-${CS2A_VALIDATION_FAILURE_DISPOSITION#preflight-}
      ;;
    *) CS2A_NO_AUTHORITY_REASON=validation-failure ;;
  esac
  : "${CS2A_SIGNED_TERMINAL_SHA256:?signed terminal must come from persistence/collection record}"
  CS2A_INCIDENT_PROVENANCE=terminal-sha256:$CS2A_SIGNED_TERMINAL_SHA256
else
  exit 70
fi
```

Expected outcomes are distinct: authentic execution/security failure with no performance decision;
A/A INCONCLUSIVE with candidate stage absent; or eligible cold/warm/retained PASS, FAIL, or
INCONCLUSIVE decisions. The command may return nonzero for these authentic outcomes. Only the
inspect/adopt state machine's accepted exact line proves the complete closed JSON, its immutable
parent-observed status evidence and `.validatorExitStatus` cross-link, and its one direct-child
commit. Outer process statuses are diagnostic only. Interpret its
separate `archiveIntegrity`, `terminalEligibility`, and `performanceDecision` fields rather than
flattening them into one exit message. Only the authenticated status-`6` attempt outcome with no
canonical result/commit enters the signed-unvalidated incident branch and can never retry semantic
validation. That includes either the original parent's immutable semantic-failure/interruption
outcome, or the `owner-abandoned` outcome created without semantic execution only after status `77`
and the disjoint finalizer revalidate the recorded owner/child-absence proof. Malformed, partial,
wrong-parent, multi-commit, or otherwise corrupt validation state is
a hard stop that must be preserved for a separately approved forensic procedure; it cannot be
silently labeled unvalidated, cleared normally, or reported by this runbook.
It does not erase the signed archive: select manual-safety after direct remediation, retain only
`CS2A_EVIDENCE_SHA` as historical incident context, leave every validation identity absent, and use
the `signed-evidence-unvalidated-no-authority` report branch.

- [ ] **Step 3: Publish the appropriate clearance request**

For `archiveIntegrity=PASS`, a separately authenticated administrator independently reviews the
exact evidence and validation commits, then materializes one exact bounded
`revoman-cs2a-global-clearance/v1` `normal` record as `root:root 0400` at:

```text
/opt/revoman-benchmark/cs2a-global-clearances/cs2a.TOKEN.REQUEST_SHA256.clear.json
```

`REQUEST_SHA256` is the SHA-256 of those exact no-clobber bytes. From an administrator-only direct
root context—not `launch`, the controlled UID, or the local operator—invoke:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 clear-normal TOKEN REQUEST_SHA256
```

The request must bind the exact signed terminal, persisted evidence commit, current nonrevoked
policy/high-water, fixed host/ledger heads, administrator identity, and independent
archive-integrity approval required by the design. A stale request remains inert and is replaced by
a new digest-derived generation only when no matching clearance generation was ever admitted.

Before publishing a normal request, the administrator must verify the exact signed pair's
terminal-observed generation exists. A collectable READY/claim pair whose terminal-observed append
was interrupted is not clearable: the human runs the same token's exact native
`launch recover TOKEN` in a new approved TTY, collection authenticates the **unchanged** signed pair
byte-for-byte (or the administrator skips redundant collection), and the administrator directly
no-follow reads/authenticates the new/adopted terminal-observed ledger head. The existing archive,
evidence commit, and one no-clobber validation result remain unchanged; only then is a request
published against the new ledger head. Repeated clearance requests cannot perform this causal
completion, and the idempotent validator must not create a second result.

For unavailable signer, quarantine, or remediated ambiguous state,
the separately authenticated administrator may instead publish the exact `manual-safety` record
only after direct containment/restoration/reprovision proof and invoke
`clear-manual-safety TOKEN REQUEST_SHA256`. Manual safety closes admission only, never produces or
upgrades evidence, and never authorizes a performance claim.
This invocation is forbidden when the installed entry/payload/clearance implementation itself is
suspect. In that case stop all old-component execution and use only the design-approved independent
replacement-clearance or external reprovision/reset authority; bind that authority and resulting
cleared admission proof into the incident report before any new token. That independent authority
publishes/authenticates the frozen no-clobber component-compromise observation and binds its digest
into the replacement result; the suspect component never does. `component-compromise` takes
clearance-reason precedence over every simultaneous safety/trust/quarantine/partial-claim/integrity/
validation outcome, while the signed result still retains every applicable evidence and failure-proof
identity. If no terminal/quarantine/partial-claim diagnostic exists, use the observation digest as
`component-compromise-sha256:DIGEST` incident provenance, never remediation or an invented absence.
The pre-ledger partial-claim branch is also disjoint: the administrator binds the native proof of the
exact claim inode, durable prefix, absent complete header/owner/side effect/global generation, and
remediation into `partial-claim-sha256:DIGEST`, then uses only the approved manual-safety or
replacement authority. It creates no evidence or validation identity and cannot be collapsed into a
generic local absence assertion.
An authenticated validation record whose `archiveIntegrity` is not `PASS` is also a manual-safety
case, not an unvalidated case: retain its validation/evidence SHAs as historical incident context,
set `CS2A_REPORT_KIND=signed-evidence-no-authority`,
`CS2A_NO_AUTHORITY_REASON=integrity-failure`, and
`CS2A_INCIDENT_PROVENANCE=terminal-sha256:$CS2A_SIGNED_TERMINAL_SHA256`, then require the exact
manual/replacement clearance and remediation chain. A/A or performance INCONCLUSIVE with
`archiveIntegrity=PASS` remains eligible for normal clearance even though it makes no improvement
claim.
If a signed evidence/validation pair already exists, selecting manual-safety changes
`CS2A_REPORT_KIND` to `signed-evidence-no-authority`. Set
`CS2A_NO_AUTHORITY_REASON=manual-safety` only when no earlier authenticated preclearance or integrity
reason exists; otherwise preserve that earlier reason unchanged. Retain those SHAs only as historical
incident context and set `CS2A_INCIDENT_PROVENANCE=terminal-sha256:$CS2A_SIGNED_TERMINAL_SHA256`, then
bind the accepted request/clearance/remediation chain. If signed evidence exists without an
authenticated validation object, keep `signed-evidence-unvalidated-no-authority`, change its reason
to `validation-failure-manual-safety` unless the immutable no-semantic outcome already authenticates
`preclearance-current-revoked|preclearance-current-drift`; preserve that preclearance reason and bind
the same chain. With no signed archive, keep
`incident-no-authority` and all evidence/validation variables absent.

Capture the native clear status, but inspect durable state first for **every** status. Clearance is
accepted whenever the separately trusted administrator no-follow reads the exact complete
parent-fsynced global generation and verifies its
schema, token, sequence/previous-head digest, accepted `REQUEST_SHA256`, terminal-observed head when
present, and `activeToken=null`; a process death/nonzero after parent fsync does not undo it. Never
infer the ledger shape from status zero or nonzero. If inspection
finds the exact accepted clearance generation renamed/file-fsynced but not yet parent-fsynced, reuse
the **same** request and invoke recovery/adoption for that prepared generation; a later request ID is
invalid. Publish a corrected digest-derived request only after proving the prior request was
stale/malformed before any matching generation was accepted. Any mismatched/forked/ambiguous shape
remains active and requires direct administrator recovery, not another blind request.
Do not continue to reporting while clearance state is ambiguous or still active. After exact
inspection, the separately authenticated administrator publishes the reapproved signed
`revoman-cs2a-clearance-result/v1` record and records its digest out of band. It binds the token,
normal/manual/replacement authority, request and parent-fsynced generation when present,
terminal-observed head, implementation/evidence/validation/result or validation-failure identities,
signed-terminal identity, the exact clearance-time reason and receipt-trust disposition with its
policy/high-water proof, validation-failure disposition or null, component-compromise proof or null,
incident/remediation provenance,
sequence/previous head, and proved
`activeToken=null`. A partial-claim result additionally binds its inode/prefix/no-owner/no-side-effect
proof. Set `CS2A_CLEARANCE_RESULT`, `CS2A_CLEARANCE_RESULT_SHA256`, and the approved administrator
clearance trust-policy/high-water inputs for Task 5; Task 5 reconstructs the clearance-time axis only
from that signed result, and no ambient reason field is authority.

---

### Task 5: Write the report and land evidence separately

**Files:**
- Add: `docs/superpowers/reports/2026-08-15-cs2a-controlled-measurement.md`
- Modify: `docs/superpowers/benchmarks/baseline.md`

**Interfaces:**
- A signed-evidence report identifies implementation SHA, release-certification digest, evidence SHA, validation SHA
  and result digest, token (nonsecret), terminal kind/status, archive-integrity result, A/A decisions,
  candidate eligibility, exact accepted/rejected sample counts, all normative confidence
  intervals/thresholds, retained weak-reference result, and the two preselected optional-win
  intervals. It links rather than duplicates large evidence.
- Every report records the authenticated clearance-result digest, mode, request/generation identities
  or replacement authority, terminal head, sequence/previous head, active-token result, and exact
  incident/remediation provenance where applicable.
- A signed no-authority report records the clearance-time reason and the report-time current-trust
  disposition as separate authenticated axes. A later revocation never overwrites an earlier drift,
  manual-safety, or integrity-failure reason, and every closed combination remains nonauthoritative.
- Authentic failures/inconclusive runs receive a truthful report and remain evidence; no
  performance improvement language is allowed without an eligible passing archive.
- Reporting revalidates the current explicit policy/high-water and verifier CAS/JDK immediately
  before reading results. A newly revoked key or drift changes the report to an incident/no-authority
  result; an earlier validation PASS never overrides current revocation.
- An unsigned/quarantine/manual-safety attempt uses a disjoint incident branch. It requires the
  authenticated manual-safety or replacement clearance result, remediation/reprovision proof, and
  exact authenticated incident provenance (`terminal-sha256:DIGEST`,
  `quarantine-sha256:DIGEST`, `partial-claim-sha256:DIGEST`,
  `component-compromise-sha256:DIGEST`, or one design-closed root
  publication-failure reason). It requires `CS2A_EVIDENCE_SHA`,
  `CS2A_VALIDATION_SHA`, attempt path, archive-integrity, and every performance field to be absent;
  it never invokes `--validate-attempt` and never describes the incident as benchmark evidence.
- A signed attempt that becomes revoked or otherwise distrustful **after** validation, or whose host
  is closed through manual-safety despite a persisted archive, uses
  `CS2A_REPORT_KIND=signed-evidence-no-authority`. It retains evidence/validation identities only as
  historical incident context, binds the exact current revocation/drift proof or
  request/clearance/remediation chain, and suppresses every archive-authority/performance claim; it
  does not pretend those identities were absent.
- A signed archive whose validator failed before publishing an authenticated validation object uses
  `CS2A_REPORT_KIND=signed-evidence-unvalidated-no-authority`. It retains only the evidence SHA as
  historical incident context, requires manual-safety remediation/clearance proof, forbids a
  validation SHA/result and every performance field, rechecks current receipt trust through the
  read-only status-`6` proof line, and never retries validation while reporting.
- Every report branch consumes the separately authenticated clearance state recorded at Task 4:
  exact request/generation or replacement authority, token, mode, terminal-observed head when
  applicable, parent-fsynced generation digest, and `activeToken=null`. A prior normal clearance is
  still valid if report-time trust later becomes revoked/drifted; no branch may omit clearance.

- [ ] **Step 1A: Revalidate signed immutable report inputs in a detached checkout**

Run this substep for `CS2A_REPORT_KIND=signed-evidence` and
`CS2A_REPORT_KIND=signed-evidence-no-authority`, and for a previously signed attempt that may
transition to the latter during current-trust revalidation.

```bash
: "${CS2A_RELEASE_CERTIFICATION:?set the immutable Plan 5 certification record}"
CS2A_REPORT_INPUT=$(mktemp -d "$PWD/build/cs2a-report-input.XXXXXXXX")
git worktree add --detach "$CS2A_REPORT_INPUT/repo" "$CS2A_VALIDATION_SHA"
test "$(git -C "$CS2A_REPORT_INPUT/repo" rev-parse HEAD)" = "$CS2A_VALIDATION_SHA"
test -z "$(git -C "$CS2A_REPORT_INPUT/repo" status --porcelain --untracked-files=all)"
git diff --exit-code "$CS2A_EVIDENCE_SHA" "$CS2A_VALIDATION_SHA" -- \
  "$CS2A_ATTEMPT_REL" ':!**/local-validation/**'
set +e
CS2A_REPORT_RELEASE_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-release-set \
  "$CS2A_RELEASE_SET" "$CS2A_RELEASE_CERTIFICATION" \
  "$CS2A_RELEASE_CERTIFICATION_SHA256" "$CS2A_IMPLEMENTATION_SHA" \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_REPORT_RELEASE_VERIFY_STATUS=$?
set -e
test "$CS2A_REPORT_RELEASE_VERIFY_STATUS" -eq 0
IFS=$'\t' read -r CS2A_REPORT_RELEASE_VERSION CS2A_REPORT_RELEASE_IMPLEMENTATION \
  CS2A_REPORT_RELEASE_MANIFEST_SHA256 CS2A_REPORT_RELEASE_INVENTORY_SHA256 \
  CS2A_REPORT_RELEASE_CERTIFICATION_SHA256 CS2A_REPORT_RELEASE_SET_IDENTITY \
  CS2A_REPORT_RELEASE_EXTRA <<<"$CS2A_REPORT_RELEASE_VERIFY_RESULT"
test "$CS2A_REPORT_RELEASE_VERSION" = revoman-cs2a-verified-release/v1
test "$CS2A_REPORT_RELEASE_IMPLEMENTATION" = "$CS2A_IMPLEMENTATION_SHA"
test "$CS2A_REPORT_RELEASE_CERTIFICATION_SHA256" = \
  "$CS2A_RELEASE_CERTIFICATION_SHA256"
test -z "$CS2A_REPORT_RELEASE_EXTRA"
set +e
CS2A_REPEAT_VALIDATION_RESULT=$(cd "$CS2A_REPORT_INPUT/repo" && \
  /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --read-attempt-validation "$CS2A_REPORT_INPUT/repo/$CS2A_ATTEMPT_REL" \
    "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
    --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
    --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
    --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
    --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
    --local-verifier-cas "$CS2A_VERIFIER_CAS" \
    --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME")
CS2A_REPORT_REVALIDATE_STATUS=$?
set -e
test -z "$(git -C "$CS2A_REPORT_INPUT/repo" status --porcelain --untracked-files=all)"
test "$(shasum -a 256 \
  "$CS2A_REPORT_INPUT/repo/$CS2A_VALIDATION_REL" | awk '{print $1}')" = \
  "$CS2A_VALIDATION_SHA256"
IFS=$'\t' read -r CS2A_REPEAT_VERSION CS2A_REPEAT_DISPOSITION \
  CS2A_REPEAT_ORIGINAL_STATUS CS2A_REPEAT_CURRENT_POLICY_SHA256 \
  CS2A_REPEAT_CURRENT_HIGH_WATER_SHA256 \
  CS2A_REPEAT_EXTRA <<<"$CS2A_REPEAT_VALIDATION_RESULT"
test "$CS2A_REPEAT_VERSION" = revoman-cs2a-repeat-validation/v1
test -z "$CS2A_REPEAT_EXTRA"
CS2A_REPORT_TRUST_DISPOSITION=$CS2A_REPEAT_DISPOSITION
CS2A_REPORT_CURRENT_POLICY_SHA256=$CS2A_REPEAT_CURRENT_POLICY_SHA256
CS2A_REPORT_CURRENT_HIGH_WATER_SHA256=$CS2A_REPEAT_CURRENT_HIGH_WATER_SHA256
case "$CS2A_REPEAT_DISPOSITION" in
  unchanged)
    test "$CS2A_REPORT_REVALIDATE_STATUS" -eq 0
    test "$CS2A_REPEAT_ORIGINAL_STATUS" = \
      "$(jq -r .validatorExitStatus "$CS2A_REPORT_INPUT/repo/$CS2A_VALIDATION_REL")"
    test "$CS2A_REPEAT_CURRENT_POLICY_SHA256" = \
      "$(jq -r .receiptKeyTrustPolicySha256 "$CS2A_REPORT_INPUT/repo/$CS2A_VALIDATION_REL")"
    test "$CS2A_REPEAT_CURRENT_HIGH_WATER_SHA256" = \
      "$(jq -r .receiptKeyTrustHighWaterSha256 "$CS2A_REPORT_INPUT/repo/$CS2A_VALIDATION_REL")"
    ;;
  current-revoked|current-drift)
    if test "$CS2A_REPEAT_DISPOSITION" = current-revoked; then
      test "$CS2A_REPORT_REVALIDATE_STATUS" -eq 4
    else
      test "$CS2A_REPORT_REVALIDATE_STATUS" -eq 5
    fi
    test "${#CS2A_REPEAT_CURRENT_POLICY_SHA256}" -eq 64
    test "${#CS2A_REPEAT_CURRENT_HIGH_WATER_SHA256}" -eq 64
    CS2A_REPORT_KIND=signed-evidence-no-authority
    ;;
  *) exit 70 ;;
esac
```

The repeat result, not status alone, distinguishes unchanged current trust from authenticated
revocation or drift. Byte/commit/signature corruption emits no accepted line and is a hard stop rather than a
reportable revocation. The no-authority branch keeps the immutable evidence/validation SHAs but must
not reuse their former archive-integrity/performance authority. Step 1A produces only the
report-time trust axis; common Step 1D authenticates and produces the separate clearance-time reason,
trust disposition/digests, terminal provenance, and remediation from the signed clearance result.
For example, pre-clearance drift followed by report-time revocation is valid but never regains
authority. For `current-revoked` or `current-drift`, both repeat-result trust digests are mandatory.

- [ ] **Step 1B: Authenticate manual-safety incident inputs without evidence validation**

Run this substep instead for `CS2A_REPORT_KIND=incident-no-authority`:

```bash
test -z "${CS2A_EVIDENCE_SHA-}"
test -z "${CS2A_VALIDATION_SHA-}"
test -z "${CS2A_ATTEMPT_REL-}"
CS2A_REPORT_TRUST_DISPOSITION=not-applicable
CS2A_REPORT_CURRENT_POLICY_SHA256=-
CS2A_REPORT_CURRENT_HIGH_WATER_SHA256=-
set +e
CS2A_INCIDENT_RELEASE_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-release-set \
  "$CS2A_RELEASE_SET" "$CS2A_RELEASE_CERTIFICATION" \
  "$CS2A_RELEASE_CERTIFICATION_SHA256" "$CS2A_IMPLEMENTATION_SHA" \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_INCIDENT_RELEASE_VERIFY_STATUS=$?
set -e
test "$CS2A_INCIDENT_RELEASE_VERIFY_STATUS" -eq 0
IFS=$'\t' read -r CS2A_INCIDENT_RELEASE_VERSION CS2A_INCIDENT_RELEASE_IMPLEMENTATION \
  CS2A_INCIDENT_RELEASE_MANIFEST_SHA256 CS2A_INCIDENT_RELEASE_INVENTORY_SHA256 \
  CS2A_INCIDENT_RELEASE_CERTIFICATION_SHA256 CS2A_INCIDENT_RELEASE_SET_IDENTITY \
  CS2A_INCIDENT_RELEASE_EXTRA <<<"$CS2A_INCIDENT_RELEASE_VERIFY_RESULT"
test "$CS2A_INCIDENT_RELEASE_VERSION" = revoman-cs2a-verified-release/v1
test "$CS2A_INCIDENT_RELEASE_IMPLEMENTATION" = "$CS2A_IMPLEMENTATION_SHA"
test "$CS2A_INCIDENT_RELEASE_CERTIFICATION_SHA256" = \
  "$CS2A_RELEASE_CERTIFICATION_SHA256"
test -z "$CS2A_INCIDENT_RELEASE_EXTRA"
CS2A_REPORT_INPUT=$(mktemp -d "$PWD/build/cs2a-incident-report-input.XXXXXXXX")
git worktree add --detach "$CS2A_REPORT_INPUT/repo" "$CS2A_IMPLEMENTATION_SHA"
test -z "$(git -C "$CS2A_REPORT_INPUT/repo" status --porcelain --untracked-files=all)"
CS2A_REPORT_BASE_SHA=$CS2A_IMPLEMENTATION_SHA
```

Common Step 1D validates the request/generation or replacement chain, remediation/provenance, and
`activeToken=null`. No raw administrator path is opened here. A quarantine object is optional only
where the signed clearance result carries one of the design's exact root publication-failure reasons
or the independently authenticated component-compromise observation digest.

- [ ] **Step 1C: Authenticate signed but unvalidated incident inputs**

Run this substep instead for `CS2A_REPORT_KIND=signed-evidence-unvalidated-no-authority`:

```bash
: "${CS2A_EVIDENCE_SHA:?set immutable signed-evidence commit}"
: "${CS2A_ATTEMPT_REL:?set canonical signed attempt path}"
test -z "${CS2A_VALIDATION_SHA-}"
test -z "${CS2A_VALIDATION_REL-}"
CS2A_REPORT_INPUT=$(mktemp -d "$PWD/build/cs2a-unvalidated-report-input.XXXXXXXX")
git worktree add --detach "$CS2A_REPORT_INPUT/repo" "$CS2A_EVIDENCE_SHA"
test "$(git -C "$CS2A_REPORT_INPUT/repo" rev-parse HEAD)" = "$CS2A_EVIDENCE_SHA"
test -z "$(git -C "$CS2A_REPORT_INPUT/repo" status --porcelain --untracked-files=all)"
test -d "$CS2A_REPORT_INPUT/repo/$CS2A_ATTEMPT_REL"
set +e
CS2A_REPORT_VALIDATION_FAILURE_LINE=$(cd "$CS2A_REPORT_INPUT/repo" && \
  /bin/bash docs/superpowers/benchmarks/operators/cs2a-operator.sh \
    --read-attempt-validation "$CS2A_REPORT_INPUT/repo/$CS2A_ATTEMPT_REL" \
    "$CS2A_IMPLEMENTATION_SHA" "$CS2A_EVIDENCE_SHA" \
    --receipt-key-trust-policy "$CS2A_RECEIPT_TRUST_POLICY" \
    --receipt-key-trust-policy-sha256 "$CS2A_RECEIPT_TRUST_POLICY_SHA256" \
    --receipt-key-trust-high-water "$CS2A_RECEIPT_HIGH_WATER" \
    --receipt-key-trust-high-water-sha256 "$CS2A_RECEIPT_HIGH_WATER_SHA256" \
    --local-verifier-cas "$CS2A_VERIFIER_CAS" \
    --local-verifier-java-home "$CS2A_VERIFIER_JAVA_HOME")
CS2A_REPORT_VALIDATION_FAILURE_STATUS=$?
set -e
test "$CS2A_REPORT_VALIDATION_FAILURE_STATUS" -eq 6
IFS=$'\t' read -r CS2A_REPORT_VALIDATION_FAILURE_VERSION \
  CS2A_REPORT_VALIDATION_FAILURE_DISPOSITION CS2A_REPORT_VALIDATION_CHILD_STATUS \
  CS2A_REPORT_VALIDATION_FAILURE_PROOF_SHA256 \
  CS2A_REPORT_TRUST_DISPOSITION CS2A_REPORT_CURRENT_POLICY_SHA256 \
  CS2A_REPORT_CURRENT_HIGH_WATER_SHA256 CS2A_REPORT_VALIDATION_FAILURE_EXTRA \
  <<<"$CS2A_REPORT_VALIDATION_FAILURE_LINE"
test "$CS2A_REPORT_VALIDATION_FAILURE_VERSION" = revoman-cs2a-validation-failed/v1
case "$CS2A_REPORT_VALIDATION_FAILURE_DISPOSITION" in
  semantic-failed|interrupted)
    test "$CS2A_REPORT_VALIDATION_CHILD_STATUS" -ge 1
    test "$CS2A_REPORT_VALIDATION_CHILD_STATUS" -le 255
    ;;
  owner-abandoned)
    test "$CS2A_REPORT_VALIDATION_CHILD_STATUS" = -
    ;;
  preflight-current-revoked)
    test "$CS2A_REPORT_VALIDATION_CHILD_STATUS" = -
    test "$CS2A_REPORT_TRUST_DISPOSITION" = current-revoked
    ;;
  preflight-current-drift)
    test "$CS2A_REPORT_VALIDATION_CHILD_STATUS" = -
    case "$CS2A_REPORT_TRUST_DISPOSITION" in
      current-drift|current-revoked) ;;
      *) exit 70 ;;
    esac
    ;;
  *) exit 70 ;;
esac
test "${#CS2A_REPORT_VALIDATION_FAILURE_PROOF_SHA256}" -eq 64
case "$CS2A_REPORT_TRUST_DISPOSITION" in
  unchanged|current-revoked|current-drift) ;;
  *) exit 70 ;;
esac
for CS2A_REPORT_VALIDATION_DIGEST in \
  "$CS2A_REPORT_VALIDATION_FAILURE_PROOF_SHA256" \
  "$CS2A_REPORT_CURRENT_POLICY_SHA256" "$CS2A_REPORT_CURRENT_HIGH_WATER_SHA256"; do
  test "${#CS2A_REPORT_VALIDATION_DIGEST}" -eq 64
  case "$CS2A_REPORT_VALIDATION_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
done
test -z "$CS2A_REPORT_VALIDATION_FAILURE_EXTRA"
CS2A_REPORT_BASE_SHA=$CS2A_EVIDENCE_SHA
```

Execute the exact direct release-verifier checks from Step 1B against this branch too; common Step 1D
authenticates clearance and remediation. Do **not** invoke `--validate-attempt`; the missing or
unpublished semantic result, authenticated attempt outcome/status, and failure-proof digest are
incident facts, not validation authority. Carry the failure disposition, child status, proof digest,
and separately rechecked report-time trust disposition/policy/high-water digests through the report
and final review.

- [ ] **Step 1D: Authenticate the common clearance result**

Run this after exactly one of Steps 1A-1C and before generating any report. The administrator-signed
record is the only durable handoff from Task 4; raw request/generation paths or shell variables are
not authority.

```bash
: "${CS2A_CLEARANCE_RESULT:?set administrator-signed clearance result}"
: "${CS2A_CLEARANCE_RESULT_SHA256:?set its out-of-band authenticated digest}"
: "${CS2A_CLEARANCE_TRUST_POLICY:?set administrator trust policy}"
: "${CS2A_CLEARANCE_TRUST_POLICY_SHA256:?set its out-of-band digest}"
: "${CS2A_CLEARANCE_TRUST_HIGH_WATER:?set administrator trust high-water}"
: "${CS2A_CLEARANCE_TRUST_HIGH_WATER_SHA256:?set its out-of-band digest}"
case "$CS2A_REPORT_TRUST_DISPOSITION" in
  unchanged|current-revoked|current-drift)
    for CS2A_REPORT_TRUST_DIGEST in \
      "$CS2A_REPORT_CURRENT_POLICY_SHA256" "$CS2A_REPORT_CURRENT_HIGH_WATER_SHA256"; do
      test "${#CS2A_REPORT_TRUST_DIGEST}" -eq 64
      case "$CS2A_REPORT_TRUST_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
    done
    ;;
  not-applicable)
    test "$CS2A_REPORT_CURRENT_POLICY_SHA256:$CS2A_REPORT_CURRENT_HIGH_WATER_SHA256" = -:-
    ;;
  *) exit 70 ;;
esac
CS2A_EXPECTED_CLEARANCE_EVIDENCE_SHA=${CS2A_EVIDENCE_SHA--}
CS2A_EXPECTED_CLEARANCE_VALIDATION_SHA=${CS2A_VALIDATION_SHA--}
CS2A_EXPECTED_CLEARANCE_VALIDATION_RESULT_SHA256=${CS2A_VALIDATION_SHA256--}
CS2A_EXPECTED_CLEARANCE_FAILURE_PROOF_SHA256=-
CS2A_EXPECTED_CLEARANCE_FAILURE_DISPOSITION=-
if test -n "${CS2A_REPORT_VALIDATION_FAILURE_PROOF_SHA256-}"; then
  CS2A_EXPECTED_CLEARANCE_FAILURE_PROOF_SHA256=$CS2A_REPORT_VALIDATION_FAILURE_PROOF_SHA256
  CS2A_EXPECTED_CLEARANCE_FAILURE_DISPOSITION=$CS2A_REPORT_VALIDATION_FAILURE_DISPOSITION
elif test -n "${CS2A_VALIDATION_FAILURE_PROOF_SHA256-}"; then
  CS2A_EXPECTED_CLEARANCE_FAILURE_PROOF_SHA256=$CS2A_VALIDATION_FAILURE_PROOF_SHA256
  CS2A_EXPECTED_CLEARANCE_FAILURE_DISPOSITION=$CS2A_VALIDATION_FAILURE_DISPOSITION
fi
CS2A_EXPECTED_CLEARANCE_TERMINAL_SHA256=${CS2A_SIGNED_TERMINAL_SHA256--}
CS2A_EXPECTED_CLEARANCE_REASON=@record
CS2A_EXPECTED_CLEARANCE_TRUST_DISPOSITION=@record
CS2A_EXPECTED_CLEARANCE_POLICY_SHA256=@record
CS2A_EXPECTED_CLEARANCE_HIGH_WATER_SHA256=@record
CS2A_EXPECTED_COMPONENT_COMPROMISE_PROOF_SHA256=@record
case "$CS2A_REPORT_KIND" in
  signed-evidence)
    CS2A_EXPECTED_CLEARANCE_PROVENANCE=-
    CS2A_EXPECTED_CLEARANCE_REMEDIATION_SHA256=-
    ;;
  signed-evidence-no-authority|signed-evidence-unvalidated-no-authority|incident-no-authority)
    CS2A_EXPECTED_CLEARANCE_PROVENANCE=@record
    CS2A_EXPECTED_CLEARANCE_REMEDIATION_SHA256=@record
    ;;
  *) exit 70 ;;
esac
set +e
CS2A_CLEARANCE_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-clearance-result \
  "$CS2A_CLEARANCE_RESULT" "$CS2A_CLEARANCE_RESULT_SHA256" \
  "$CS2A_SESSION_TOKEN" "$CS2A_IMPLEMENTATION_SHA" "$CS2A_REPORT_KIND" \
  "$CS2A_EXPECTED_CLEARANCE_REASON" \
  "$CS2A_EXPECTED_CLEARANCE_TRUST_DISPOSITION" \
  "$CS2A_EXPECTED_CLEARANCE_POLICY_SHA256" \
  "$CS2A_EXPECTED_CLEARANCE_HIGH_WATER_SHA256" \
  "$CS2A_EXPECTED_COMPONENT_COMPROMISE_PROOF_SHA256" \
  "$CS2A_EXPECTED_CLEARANCE_EVIDENCE_SHA" \
  "$CS2A_EXPECTED_CLEARANCE_VALIDATION_SHA" \
  "$CS2A_EXPECTED_CLEARANCE_VALIDATION_RESULT_SHA256" \
  "$CS2A_EXPECTED_CLEARANCE_FAILURE_PROOF_SHA256" \
  "$CS2A_EXPECTED_CLEARANCE_FAILURE_DISPOSITION" \
  "$CS2A_EXPECTED_CLEARANCE_TERMINAL_SHA256" \
  "$CS2A_EXPECTED_CLEARANCE_PROVENANCE" \
  "$CS2A_EXPECTED_CLEARANCE_REMEDIATION_SHA256" \
  "$CS2A_CLEARANCE_TRUST_POLICY" "$CS2A_CLEARANCE_TRUST_POLICY_SHA256" \
  "$CS2A_CLEARANCE_TRUST_HIGH_WATER" "$CS2A_CLEARANCE_TRUST_HIGH_WATER_SHA256" \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_CLEARANCE_VERIFY_STATUS=$?
set -e
test "$CS2A_CLEARANCE_VERIFY_STATUS" -eq 0
IFS=$'\t' read -r CS2A_CLEARANCE_RESULT_VERSION CS2A_CLEARANCE_RESULT_TOKEN \
  CS2A_CLEARANCE_RESULT_IMPLEMENTATION CS2A_CLEARANCE_MODE \
  CS2A_CLEARANCE_REASON CS2A_CLEARANCE_TRUST_DISPOSITION \
  CS2A_CLEARANCE_POLICY_SHA256 CS2A_CLEARANCE_HIGH_WATER_SHA256 \
  CS2A_CLEARANCE_COMPONENT_COMPROMISE_PROOF_SHA256 \
  CS2A_CLEARANCE_REQUEST_SHA256 \
  CS2A_CLEARANCE_GENERATION_SHA256 CS2A_CLEARANCE_TERMINAL_HEAD_SHA256 \
  CS2A_CLEARANCE_EVIDENCE_SHA CS2A_CLEARANCE_VALIDATION_SHA \
  CS2A_CLEARANCE_VALIDATION_RESULT_SHA256 \
  CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256 \
  CS2A_CLEARANCE_VALIDATION_FAILURE_DISPOSITION \
  CS2A_CLEARANCE_SIGNED_TERMINAL_SHA256 CS2A_CLEARANCE_REMEDIATION_SHA256 \
  CS2A_CLEARANCE_RESULT_PROVENANCE CS2A_CLEARANCE_SEQUENCE \
  CS2A_CLEARANCE_PREVIOUS_HEAD_SHA256 CS2A_CLEARANCE_ACTIVE_TOKEN \
  CS2A_CLEARANCE_RESULT_EXTRA <<<"$CS2A_CLEARANCE_VERIFY_RESULT"
test "$CS2A_CLEARANCE_RESULT_VERSION" = revoman-cs2a-verified-clearance/v1
test "$CS2A_CLEARANCE_RESULT_TOKEN" = "$CS2A_SESSION_TOKEN"
test "$CS2A_CLEARANCE_RESULT_IMPLEMENTATION" = "$CS2A_IMPLEMENTATION_SHA"
test "$CS2A_CLEARANCE_EVIDENCE_SHA" = "$CS2A_EXPECTED_CLEARANCE_EVIDENCE_SHA"
test "$CS2A_CLEARANCE_VALIDATION_SHA" = "$CS2A_EXPECTED_CLEARANCE_VALIDATION_SHA"
test "$CS2A_CLEARANCE_VALIDATION_RESULT_SHA256" = \
  "$CS2A_EXPECTED_CLEARANCE_VALIDATION_RESULT_SHA256"
test "$CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256" = \
  "$CS2A_EXPECTED_CLEARANCE_FAILURE_PROOF_SHA256"
test "$CS2A_CLEARANCE_VALIDATION_FAILURE_DISPOSITION" = \
  "$CS2A_EXPECTED_CLEARANCE_FAILURE_DISPOSITION"
case "$CS2A_CLEARANCE_VALIDATION_FAILURE_DISPOSITION" in
  -)
    test "$CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256" = -
    ;;
  semantic-failed|interrupted|owner-abandoned)
    test "$CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256" != -
    case "$CS2A_CLEARANCE_REASON" in
      validation-failure-manual-safety|component-compromise) ;;
      *) exit 70 ;;
    esac
    ;;
  preflight-current-revoked)
    test "$CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256" != -
    case "$CS2A_CLEARANCE_REASON" in
      preclearance-current-revoked|component-compromise) ;;
      *) exit 70 ;;
    esac
    ;;
  preflight-current-drift)
    test "$CS2A_CLEARANCE_VALIDATION_FAILURE_PROOF_SHA256" != -
    case "$CS2A_CLEARANCE_REASON" in
      preclearance-current-drift|component-compromise) ;;
      *) exit 70 ;;
    esac
    ;;
  *) exit 70 ;;
esac
test "$CS2A_CLEARANCE_SIGNED_TERMINAL_SHA256" = \
  "$CS2A_EXPECTED_CLEARANCE_TERMINAL_SHA256"
test "$CS2A_CLEARANCE_ACTIVE_TOKEN" = null
test -z "$CS2A_CLEARANCE_RESULT_EXTRA"
case "$CS2A_CLEARANCE_TRUST_DISPOSITION" in
  unchanged|current-revoked|current-drift)
    for CS2A_CLEARANCE_TRUST_DIGEST in \
      "$CS2A_CLEARANCE_POLICY_SHA256" "$CS2A_CLEARANCE_HIGH_WATER_SHA256"; do
      test "${#CS2A_CLEARANCE_TRUST_DIGEST}" -eq 64
      case "$CS2A_CLEARANCE_TRUST_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
    done
    ;;
  not-applicable)
    test "$CS2A_CLEARANCE_POLICY_SHA256:$CS2A_CLEARANCE_HIGH_WATER_SHA256" = -:-
    ;;
  *) exit 70 ;;
esac
case "$CS2A_CLEARANCE_REASON" in
  none)
    test "$CS2A_CLEARANCE_TRUST_DISPOSITION" = unchanged
    ;;
  manual-safety|integrity-failure|validation-failure-manual-safety)
    test "$CS2A_CLEARANCE_TRUST_DISPOSITION" != not-applicable
    ;;
  preclearance-current-revoked|current-revoked)
    test "$CS2A_CLEARANCE_TRUST_DISPOSITION" = current-revoked
    ;;
  preclearance-current-drift|current-drift)
    case "$CS2A_CLEARANCE_TRUST_DISPOSITION" in
      current-drift|current-revoked) ;;
      *) exit 70 ;;
    esac
    ;;
  safety-only|external-admin-required|quarantined|partial-claim|component-compromise)
    test "$CS2A_CLEARANCE_TRUST_DISPOSITION" = not-applicable
    ;;
  *) exit 70 ;;
esac
if test "$CS2A_CLEARANCE_REASON" = component-compromise; then
  test "${#CS2A_CLEARANCE_COMPONENT_COMPROMISE_PROOF_SHA256}" -eq 64
  case "$CS2A_CLEARANCE_COMPONENT_COMPROMISE_PROOF_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
else
  test "$CS2A_CLEARANCE_COMPONENT_COMPROMISE_PROOF_SHA256" = -
fi
CS2A_REPORT_PRECLEARANCE_REASON=$CS2A_CLEARANCE_REASON
if test "$CS2A_REPORT_TRUST_DISPOSITION" != not-applicable; then
  case "$CS2A_CLEARANCE_TRUST_DISPOSITION" in
    unchanged|not-applicable) ;;
    current-drift)
      case "$CS2A_REPORT_TRUST_DISPOSITION" in
        current-drift|current-revoked) ;;
        *) exit 70 ;;
      esac
      ;;
    current-revoked)
      test "$CS2A_REPORT_TRUST_DISPOSITION" = current-revoked
      ;;
  esac
fi
case "$CS2A_CLEARANCE_SEQUENCE" in ''|*[!0-9]*) exit 70 ;; esac
case "$CS2A_CLEARANCE_MODE" in
  normal|manual-safety)
    for CS2A_CLEARANCE_REQUIRED_DIGEST in \
      "$CS2A_CLEARANCE_REQUEST_SHA256" "$CS2A_CLEARANCE_GENERATION_SHA256"; do
      test "${#CS2A_CLEARANCE_REQUIRED_DIGEST}" -eq 64
      case "$CS2A_CLEARANCE_REQUIRED_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
    done
    ;;
  replacement)
    case "$CS2A_CLEARANCE_REQUEST_SHA256:$CS2A_CLEARANCE_GENERATION_SHA256" in
      -:-) ;;
      *)
        for CS2A_CLEARANCE_REPLACEMENT_DIGEST in \
          "$CS2A_CLEARANCE_REQUEST_SHA256" "$CS2A_CLEARANCE_GENERATION_SHA256"; do
          test "${#CS2A_CLEARANCE_REPLACEMENT_DIGEST}" -eq 64
          case "$CS2A_CLEARANCE_REPLACEMENT_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
        done
        ;;
    esac
    ;;
  *) exit 70 ;;
esac
for CS2A_CLEARANCE_OPTIONAL_DIGEST in \
  "$CS2A_CLEARANCE_TERMINAL_HEAD_SHA256" "$CS2A_CLEARANCE_PREVIOUS_HEAD_SHA256"; do
  if test "$CS2A_CLEARANCE_OPTIONAL_DIGEST" != -; then
    test "${#CS2A_CLEARANCE_OPTIONAL_DIGEST}" -eq 64
    case "$CS2A_CLEARANCE_OPTIONAL_DIGEST" in *[!0-9a-f]*) exit 70 ;; esac
  fi
done
if test "$CS2A_EXPECTED_CLEARANCE_PROVENANCE" != @record; then
  test "$CS2A_CLEARANCE_RESULT_PROVENANCE" = "$CS2A_EXPECTED_CLEARANCE_PROVENANCE"
fi
if test "$CS2A_EXPECTED_CLEARANCE_REMEDIATION_SHA256" != @record; then
  test "$CS2A_CLEARANCE_REMEDIATION_SHA256" = \
    "$CS2A_EXPECTED_CLEARANCE_REMEDIATION_SHA256"
fi
case "$CS2A_REPORT_KIND" in
  signed-evidence)
    test "$CS2A_CLEARANCE_REASON" = none
    test "$CS2A_REPORT_TRUST_DISPOSITION" = unchanged
    test "$CS2A_CLEARANCE_MODE" = normal
    test "$CS2A_CLEARANCE_RESULT_PROVENANCE" = -
    test "$CS2A_CLEARANCE_REMEDIATION_SHA256" = -
    unset CS2A_NO_AUTHORITY_REASON
    ;;
  signed-evidence-no-authority)
    case "$CS2A_CLEARANCE_REASON" in
      none)
        case "$CS2A_REPORT_TRUST_DISPOSITION" in
          current-revoked|current-drift) ;;
          *) exit 70 ;;
        esac
        test "$CS2A_CLEARANCE_MODE" = normal
        test "$CS2A_CLEARANCE_RESULT_PROVENANCE" = -
        test "$CS2A_CLEARANCE_REMEDIATION_SHA256" = -
        CS2A_NO_AUTHORITY_REASON=$CS2A_REPORT_TRUST_DISPOSITION
        ;;
      preclearance-current-revoked|preclearance-current-drift|manual-safety|integrity-failure)
        case "$CS2A_REPORT_TRUST_DISPOSITION" in
          unchanged|current-revoked|current-drift) ;;
          *) exit 70 ;;
        esac
        case "$CS2A_CLEARANCE_MODE" in manual-safety|replacement) ;; *) exit 70 ;; esac
        test "$CS2A_CLEARANCE_RESULT_PROVENANCE" != -
        test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -
        CS2A_NO_AUTHORITY_REASON=$CS2A_CLEARANCE_REASON
        ;;
      component-compromise)
        test "$CS2A_CLEARANCE_MODE" = replacement
        test "$CS2A_CLEARANCE_TRUST_DISPOSITION" = not-applicable
        test "$CS2A_CLEARANCE_RESULT_PROVENANCE" != -
        test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -
        CS2A_NO_AUTHORITY_REASON=$CS2A_CLEARANCE_REASON
        ;;
      *) exit 70 ;;
    esac
    ;;
  signed-evidence-unvalidated-no-authority)
    case "$CS2A_REPORT_TRUST_DISPOSITION" in
      unchanged|current-revoked|current-drift) ;;
      *) exit 70 ;;
    esac
    case "$CS2A_CLEARANCE_REASON" in
      validation-failure-manual-safety)
        case "$CS2A_CLEARANCE_MODE" in manual-safety|replacement) ;; *) exit 70 ;; esac
        ;;
      preclearance-current-revoked|preclearance-current-drift)
        case "$CS2A_CLEARANCE_MODE" in manual-safety|replacement) ;; *) exit 70 ;; esac
        ;;
      component-compromise)
        test "$CS2A_CLEARANCE_MODE" = replacement
        test "$CS2A_CLEARANCE_TRUST_DISPOSITION" = not-applicable
        ;;
      *) exit 70 ;;
    esac
    test "$CS2A_CLEARANCE_RESULT_PROVENANCE" != -
    test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -
    CS2A_NO_AUTHORITY_REASON=$CS2A_CLEARANCE_REASON
    ;;
  incident-no-authority)
    test "$CS2A_REPORT_TRUST_DISPOSITION" = not-applicable
    case "$CS2A_CLEARANCE_REASON" in
      safety-only|external-admin-required|current-revoked|current-drift|quarantined|\
      partial-claim|component-compromise) ;;
      *) exit 70 ;;
    esac
    if test "$CS2A_CLEARANCE_REASON" = component-compromise; then
      test "$CS2A_CLEARANCE_MODE" = replacement
    else
      case "$CS2A_CLEARANCE_MODE" in manual-safety|replacement) ;; *) exit 70 ;; esac
    fi
    test "$CS2A_CLEARANCE_RESULT_PROVENANCE" != -
    test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -
    CS2A_NO_AUTHORITY_REASON=$CS2A_CLEARANCE_REASON
    ;;
  *) exit 70 ;;
esac
case "$CS2A_CLEARANCE_RESULT_PROVENANCE" in
  -) ;;
  terminal-sha256:*|quarantine-sha256:*|partial-claim-sha256:*|\
  component-compromise-sha256:*)
    CS2A_CLEARANCE_PROVENANCE_SHA256=${CS2A_CLEARANCE_RESULT_PROVENANCE#*:}
    test "${#CS2A_CLEARANCE_PROVENANCE_SHA256}" -eq 64
    case "$CS2A_CLEARANCE_PROVENANCE_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
    ;;
  absence:root-quarantine-publication-failed|\
  absence:root-signer-unavailable-before-terminal) ;;
  *) exit 70 ;;
esac
if test "${CS2A_CLEARANCE_RESULT_PROVENANCE%%:*}" = component-compromise-sha256; then
  test "${CS2A_CLEARANCE_RESULT_PROVENANCE#*:}" = \
    "$CS2A_CLEARANCE_COMPONENT_COMPROMISE_PROOF_SHA256"
fi
if test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -; then
  test "${#CS2A_CLEARANCE_REMEDIATION_SHA256}" -eq 64
  case "$CS2A_CLEARANCE_REMEDIATION_SHA256" in *[!0-9a-f]*) exit 70 ;; esac
fi
if test "$CS2A_CLEARANCE_RESULT_PROVENANCE" != -; then
  CS2A_INCIDENT_PROVENANCE=$CS2A_CLEARANCE_RESULT_PROVENANCE
else
  unset CS2A_INCIDENT_PROVENANCE
fi
if test "$CS2A_CLEARANCE_REMEDIATION_SHA256" != -; then
  CS2A_REMEDIATION_PROOF_SHA256=$CS2A_CLEARANCE_REMEDIATION_SHA256
else
  unset CS2A_REMEDIATION_PROOF_SHA256
fi
```

The verifier authenticates request/generation schemas, ownership captured by the administrator,
sequence/previous head, terminal-observed and evidence/validation cross-links, clearance mode,
clearance-time reason/trust proof, incident/remediation provenance, and parent-fsynced
`activeToken=null`; the shell does not reparse those records. For report-time
`current-revoked|current-drift`, `normal` is accepted only when the signed result
proves that normal clearance completed under the earlier nonrevoked validation head before the
report-time trust change. Pre-clearance revocation/drift always requires manual/replacement
authority. `@record` is accepted only for a clearance-time field whose first durable authority or
nullability comes from this signed handoff; it is replaced by the canonical output before report
generation and is never written to a report.

- [ ] **Step 2: Generate the report from immutable machine results**

For signed evidence, copy no value by hand when it can be read from signed receipt/comparison JSON.
Cross-check every reported count, ratio, lower/upper 95% bound, threshold, and decision independently
from the detached `CS2A_VALIDATION_SHA` checkout. For an incident, read only the authenticated
manual-safety/remediation/current-trust inputs from Step 1B and assert the generated report contains
no archive-integrity result, sample count, comparison, interval, decision, or improvement language.
For `signed-evidence-no-authority`, retain the historical signed identities and prior machine result only
as incident context, bind the current revocation/drift proof, and apply the same no-authority/no-
performance-language assertions.
For `signed-evidence-unvalidated-no-authority`, use only the evidence and manual-safety incident
facts, state that no authenticated semantic result exists, and apply the same suppression.
Every generated report embeds `CS2A_CLEARANCE_RESULT_SHA256`, mode, request/generation or replacement
identity, terminal head, sequence/previous head, active-token value, and the verified
clearance-time reason/trust disposition/policy/high-water proof, provenance/remediation digests, and
component-compromise proof or null, validation-failure disposition/proof or null, and the separate
`CS2A_REPORT_TRUST_DISPOSITION` plus report-time policy/high-water digests. It must
byte-match the Step 1D canonical output and Step 1A or Step 1C current-trust result; omission or
collapsing the two trust axes is a report failure.

- [ ] **Step 3: Commit the report separately**

```bash
git diff --check
case "$CS2A_REPORT_KIND" in
  signed-evidence|signed-evidence-no-authority)
    git diff --exit-code "$CS2A_VALIDATION_SHA" -- "$CS2A_ATTEMPT_REL"
    CS2A_REPORT_BASE_SHA=$CS2A_VALIDATION_SHA
    ;;
  incident-no-authority)
    test -z "${CS2A_ATTEMPT_REL-}"
    test "$CS2A_REPORT_BASE_SHA" = "$CS2A_IMPLEMENTATION_SHA"
    ;;
  signed-evidence-unvalidated-no-authority)
    test -z "${CS2A_VALIDATION_SHA-}"
    git diff --exit-code "$CS2A_EVIDENCE_SHA" -- "$CS2A_ATTEMPT_REL"
    test "$CS2A_REPORT_BASE_SHA" = "$CS2A_EVIDENCE_SHA"
    ;;
  *) exit 70 ;;
esac
git add docs/superpowers/reports/2026-08-15-cs2a-controlled-measurement.md \
  docs/superpowers/benchmarks/baseline.md
git commit -m "docs: report CS2a controlled measurement"
CS2A_REPORT_SHA=$(git rev-parse HEAD)
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 4: Render and review the exact committed report**

```bash
if test "$CS2A_REPORT_KIND" = signed-evidence || \
   test "$CS2A_REPORT_KIND" = signed-evidence-no-authority; then
  git diff --exit-code "$CS2A_VALIDATION_SHA" "$CS2A_REPORT_SHA" -- "$CS2A_ATTEMPT_REL"
elif test "$CS2A_REPORT_KIND" = signed-evidence-unvalidated-no-authority; then
  git diff --exit-code "$CS2A_EVIDENCE_SHA" "$CS2A_REPORT_SHA" -- "$CS2A_ATTEMPT_REL"
fi
CS2A_REPORT_CLONE=$(mktemp -d "$PWD/build/cs2a-report-antora.XXXXXXXX")
git clone --no-local "$PWD" "$CS2A_REPORT_CLONE/repo"
git -C "$CS2A_REPORT_CLONE/repo" checkout --detach "$CS2A_REPORT_SHA"
(cd "$CS2A_REPORT_CLONE/repo" && npm ci && \
  npx --no-install antora antora-playbook.yml)
test -z "$(git status --porcelain --untracked-files=all)"
```

Run independent Standards+Spec review over the release certification, current trust-policy/high-water,
the exact signed clearance result/digest and canonical verified output, and `CS2A_REPORT_SHA`; both signed branches additionally cover `CS2A_EVIDENCE_SHA` and
`CS2A_VALIDATION_SHA`, with the no-authority branch also covering current revocation/drift or
manual-safety proof and
absence of authority claims. The unvalidated signed branch covers `CS2A_EVIDENCE_SHA`, validator
failure, and absence of any validation identity. The unsigned incident branch covers the exact
clearance/remediation/provenance identities and asserts evidence/validation identities are absent. A report
defect gets a report-only correction followed by another detached Antora/review pass; an
implementation/evidence defect remains preserved and restarts at the appropriate prior plan with a
new SHA/token only after the current token is authentically cleared. A defect implicating the
installed entry/payload/clearance path first follows the independent replacement/reprovision hard
stop above; it never invokes the suspected old component to make room for that new token.
