# CS2a Security and Release Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the approved mutation contract into one complete release block and freeze a clean,
independently reviewed implementation SHA that is eligible for external provisioning.

**Architecture:** Focused tests from Plans 1-4 feed a single manifest-driven security gate. The gate
authenticates the fourteen-source inventory, reproduces all native/verifier artifacts, runs the
entire JVM/documentation/static-analysis suite, then exercises the exact release binaries through a
disposable Linux/root fault matrix. Fixed-range Standards, Spec, and security reviews run only
after every deterministic gate passes.

**Tech Stack:** Gradle, Kotest/JUnit, native release/ASan/UBSan/fuzz builds, Bash/ShellCheck/jq,
disposable Linux/root, Qodana, Kover, Kotlin ABI/API fixtures, Antora, Git detached checkouts, and
independent code-review agents.

## Global Constraints

- Depends on Plans 1-4 and starts from a clean worktree. It changes tests, gate wiring, workflows,
  Detekt metadata, and documentation only; protocol corrections return to the owning plan and
  produce a new implementation commit.
- The exact release binaries used in root E2E must be the same bytes whose digests appear in
  recipes, provenance, approval fixtures, and the later prepared session.
- The approved design's complete TDD/mutation list is normative. Every bullet maps to a named test
  case or a generated corpus row; an unowned row is a release failure.
- Qodana command success is insufficient. Review all findings in the fixed implementation range and
  update the baseline only for independently justified unchanged debt.
- No real host provisioning or measurement occurs in this plan.

---

### Task 1: Make the mutation matrix and fourteen-source ledger executable

**Files:**
- Add: `src/test/resources/cs2a/security-mutation-matrix.tsv`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aSecurityMutationMatrixTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/DetektBaselineIntegrityTest.kt`
- Modify: `detekt/baseline.xml`
- Modify: `detekt/baseline-source-sha256sums.txt`
- Modify: `build.gradle.kts`

**Interfaces:**
- TSV columns: `id`, `designLine`, `domain`, `testClass`, `testName`, `parameterId`, `gate`, and
  `expectedInvariant`. First extract one reviewed stable ID for every atomic mutant, including each
  comma-separated item and sentence-level case rather than one row per prose bullet. Every ID maps
  to one unique row. Rows may share a parameterized test method only when their distinct
  `parameterId` values appear in that test's machine-readable execution report.
- `Cs2aStructuralInvariantTest` freezes the exact fourteen relative paths, five staged assets,
  allowed production source extensions, forbidden host literals, and source/recipe hash ledger.
- `cs2aMutationGate` runs every mapped focused test, collects the emitted parameter IDs, and fails
  on stale/missing/duplicate rows or a row whose exact parameter did not execute.

- [ ] **Step 1: Add RED coverage accounting**

Populate the matrix from every design mutation category: CLI/transport; policy/account/namespace;
native profiles/drop; copier/signer/verifier; admission/lifecycle/clearance; transition/transcript;
guardian/cgroup/governor; runner/env/seed/JDK; finalization/receipt; collection/resource bounds;
recovery/reboot; legacy quarantine; and performance terminal matrix.

Run:

```bash
./gradlew :test --tests '*Cs2aSecurityMutationMatrixTest' \
  --tests '*Cs2aStructuralInvariantTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: RED until every matrix row resolves to an existing test and gate.

- [ ] **Step 2: Reconcile coverage and Detekt without inventing a late protocol seam**

If a matrix row has no executing test, stop and return it to the owning task in Plans 1-4; do not
patch a protocol seam inside the release-gate task. Once all rows resolve, update Detekt's source
hash ledger in the same commit. Do not suppress findings or inflate the legacy giant script tests.

- [ ] **Step 3: Run the mutation gate and commit**

```bash
./gradlew cs2aMutationGate \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add build.gradle.kts src/test/resources/cs2a/security-mutation-matrix.tsv \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aSecurityMutationMatrixTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/DetektBaselineIntegrityTest.kt \
  detekt/baseline.xml detekt/baseline-source-sha256sums.txt
git commit -m "test: execute the CS2a security mutation matrix"
```

---

### Task 2: Add the deterministic native and verifier release block

**Files:**
- Modify: `build.gradle.kts`
- Modify: `benchmark-driver/build.gradle.kts`
- Modify: `docs/superpowers/benchmarks/operators/cs2a-local-verifier-v1.build.json`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aReleaseArtifactIntegrationTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReleaseSetVerifierTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aQodanaRangeGateTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/DetektBaselineIntegrityTest.kt`
- Modify: `detekt/baseline-source-sha256sums.txt`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- `cs2aReleaseArtifactDevelopmentTest` exercises substitution/reproducibility logic from a dirty
  development tree but never publishes or certifies an artifact. `cs2aReleaseArtifactGate` refuses
  a dirty/mismatched HEAD, performs two clean release builds of all three native binaries, requires
  byte identity and exact recipe/provenance/ELF properties, runs ASan and UBSan suites plus the
  fixed fuzz/property corpus, and performs two hermetic verifier install-distribution builds whose
  inventories equal the reviewed recipe digest.
- The clean gate requires explicit `-Pcs2a.releaseCas=ABSOLUTE_PATH`. It no-follow validates a
  user-owned `0700` CAS outside repository/worktree/build/temp/evidence roots and publishes one
  no-clobber content-derived `0555` release set containing all three native binaries, sources,
  recipes, provenance, the complete verifier distribution, and
  `release-artifacts.json`. Regular data is `0444`, executables `0555`; the manifest binds HEAD and
  every complete inventory/digest. Only an ignored
  `build/cs2a-release-set-path.txt` pointer is written in the worktree.
- `cs2aReleaseSetInventoryVerify` is the read-only pre-certification consumer: it requires the
  explicit release-set path, manifest SHA-256, and expected implementation SHA and authenticates the
  complete set. `cs2aReleaseSetVerify` adds the required final certification-record cross-check.
  The certified gate is only a development/CI wrapper around the fixed two-entry verifier
  distribution from Plan 1; Plan 6 invokes the release-set entry directly and
  never loads candidate Gradle/build logic. It additionally requires the independently approved exact certification SHA-256;
  a CAS path or owner-writable mode is never authority by itself. Both no-follow open the canonical
  set and every member; verify set identity, owner/group/modes,
  exact manifest bytes, complete sorted type/path/size/digest inventory, and manifest/inventory
  SHA-256; and keep anchored handles through verification so a symlink/swap cannot pass. Plan 6 uses
  the same fixed certified verifier entry directly rather than candidate Gradle or hand-written
  `test`/`stat` checks.
- `cs2aReleaseCertificationGate` is a clean-tree no-clobber publisher used only after the final
  reviews/root gate. It writes one bounded `revoman-cs2a-release-certification/v1` record beneath
  the protected CAS's separate `certifications/` namespace, never into the immutable release set.
  The record binds final HEAD, the derived plan-set base, canonical release-set identity, manifest
  and full-inventory digests, the pre-code approved design closure/approval, the distinct post-build
  bootstrap approval, exact review artifacts, Qodana range result, VM lifecycle/image
  attestations, root-gate result digest, and publisher identity. It writes only an ignored path
  pointer in `build/`.
- `cs2aFixedRangeReviewVerify` accepts only a reapproved signed review envelope/commit pair plus an
  explicit reviewer trust-policy/high-water and their out-of-band digests. It verifies through the
  same hermetic fixed-domain verifier, requires the exact plan base, final HEAD, final approved
  design digest, release/Qodana/root-result inventories, review kind, and no Critical/Important
  findings, and returns a verified record digest. `cs2aReleaseCertificationGate` accepts only these
  verified digests, never caller-authored unsigned PASS files.
- This task also registers `cs2aQodanaRangeGate` and `cs2aLinuxRootGate`. The latter depends on
  `cs2aMutationGate` and the read-only `cs2aReleaseSetInventoryVerify`, never on the publishing
  `cs2aReleaseArtifactGate`; it consumes the explicit existing set/manifest digest, uses disjoint
  outputs, and must run with `--no-parallel`. Its implementation/scenarios are completed in Task 4.
- Finalize `cs2a-local-verifier-v1.build.json` only after this task's root/driver build wiring is
  complete. Recompute its reviewed build-input and expected-distribution inventories and rerun all
  Plan 1 verifier plus Plan 4 preparation/CAS tests. No earlier candidate recipe is release-eligible.
  This task does not mutate the pre-code design approval. After the final Task 5 release set exists,
  a separately authenticated administrator independently reproduces and approves the exact verifier
  distribution, JDK, and process-envelope executor in a distinct immutable bootstrap approval
  record; only that post-build record may authorize direct certification consumption.
- `cs2aQodanaRangeGate` parses only the explicit SARIF, filters every result whose normalized path is
  changed in the authenticated plan-set-base-to-HEAD range, requires zero such findings, and
  publishes a no-clobber review-CAS record binding both SHAs, SARIF digest, normalized changed paths,
  extracted results, and the final reapproved design SHA-256 at HEAD. It independently derives the base as the unique add-commit of
  `2026-08-15-cs2a-remote-session-plan-set.md`, proves that commit contains the approved
  `74ebc845...` design bytes, proves its parent is exactly the pre-plan clean repository SHA
  `56092029b97c15bddac30354b51601be2a477d3c`, proves the add-commit itself changes only the declared
  plan documents, and proves it is a strict ancestor of HEAD. It rejects any supplied base that
  differs. Qodana's configured global finding threshold cannot satisfy this gate.
  On success it writes only `build/cs2a-qodana-review-path.txt` pointing at the no-clobber review
  record.

- [ ] **Step 1: Add RED artifact substitution and reproducibility tests**

Mutate each source/recipe/toolchain/static library, compiler flag, ELF property, expected digest,
verifier dependency/JDK/provider, and one byte in each produced artifact. Require detection before
the root harness starts.

- [ ] **Step 2: Implement the aggregate tasks, CAS publication, and CI wiring**

Run every build in independent temporary roots with clean environments and no caches/network except
the already authenticated read-only dependency materialization. Add the task to build CI only on a
Linux executor that supports the pinned toolchain; all other platforms explicitly skip rather than
substitute binaries.

- [ ] **Step 3: Run the dirty-safe development test and commit the gate implementation**

```bash
./gradlew cs2aReleaseArtifactDevelopmentTest \
  :test --tests '*Cs2aVerifierCasTest' --tests '*Cs2aPreparationTransportTest' \
  :test --tests '*Cs2aReleaseSetVerifierTest' --tests '*Cs2aQodanaRangeGateTest' \
  :test --tests '*Cs2aStructuralInvariantTest' --tests '*DetektBaselineIntegrityTest' \
  :benchmark-driver:test --tests '*VerifierDistributionReproducibilityTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add build.gradle.kts benchmark-driver/build.gradle.kts \
  docs/superpowers/benchmarks/operators/cs2a-local-verifier-v1.build.json \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aReleaseArtifactIntegrationTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReleaseSetVerifierTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aQodanaRangeGateTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/DetektBaselineIntegrityTest.kt \
  detekt/baseline-source-sha256sums.txt \
  .github/workflows/build.yml
git commit -m "build: reproduce CS2a release artifacts"
```

- [ ] **Step 4: Certify and publish the committed artifact set**

```bash
: "${CS2A_RELEASE_CAS:?set an absolute protected release CAS outside the checkout}"
test -z "$(git status --porcelain --untracked-files=all)"
./gradlew cs2aReleaseArtifactGate \
  -Pcs2a.releaseCas="$CS2A_RELEASE_CAS" \
  --no-parallel --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
test -z "$(git status --porcelain --untracked-files=all)"
CS2A_RELEASE_SET=$(cat build/cs2a-release-set-path.txt)
test -d "$CS2A_RELEASE_SET"
test "$(jq -r .implementationSha "$CS2A_RELEASE_SET/release-artifacts.json")" = \
  "$(git rev-parse HEAD)"
```

Expected: one immutable CAS release set and no tracked/untracked worktree change.

---

### Task 3: Run and document the complete repository gate

**Files:**
- Modify: `DEVELOPMENT.md`
- Modify: `docs/superpowers/benchmarks/baseline.md`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/BenchmarkWorkflowTest.kt`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Document one ordered clean-SHA block that includes Gradle/ABI/API/unit/integration/Kover/Spotless,
  driver integration, all custom harness/JMH structure tasks, native release/mutation gates,
  Bash/ShellCheck/jq, Qodana with triage, and Antora.
- The old self-hosted benchmark workflow cannot be cited as controlled evidence. If retained for
  smoke, label it nonauthoritative and prevent it from invoking the retired remote path.

- [ ] **Step 1: Update the gate documentation/workflow, test it, and commit before certification**

```bash
./gradlew :test --tests '*BenchmarkWorkflowTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add DEVELOPMENT.md docs/superpowers/benchmarks/baseline.md \
  .github/workflows/build.yml src/test/kotlin/com/salesforce/revoman/benchmark/BenchmarkWorkflowTest.kt
git commit -m "docs: require complete CS2a release gates"
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 2: Run the full JVM and harness block at committed HEAD**

```bash
./gradlew :benchmark-driver:installDist \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.targetId=current-cs2a \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew checkKotlinAbi apiCompatibilityTestClasses :test :integrationTest \
  :benchmark-driver:test \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_SELFTEST_ROOT=$(mktemp -d "$PWD/build/cs2a-selftest.XXXXXXXX")
git worktree add --detach "$CS2A_SELFTEST_ROOT/baseline" \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
test -z "$(git -C "$CS2A_SELFTEST_ROOT/baseline" status --porcelain)"
test "$(git -C "$CS2A_SELFTEST_ROOT/baseline" rev-parse HEAD)" = \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
"$CS2A_SELFTEST_ROOT/baseline/gradlew" -p "$CS2A_SELFTEST_ROOT/baseline" \
  -I "$PWD/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-baseline-selftest.json" \
  -Pbenchmark.targetId=baseline-selftest-83f3cd70 --no-daemon --console=plain
./gradlew :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle retained campaign preserves v2 series identity*' \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew \
  :benchmark-driver:benchmarkCleanInstallTaskGraphTest \
  :benchmark-driver:benchmarkJmhTaskSerializationTest \
  :benchmark-driver:benchmarkJmhFreshnessTest \
  :benchmark-driver:benchmarkJmhOutputCollisionTest \
  :benchmark-driver:benchmarkHarnessSelfTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:benchmarkJmh \
  -Pbenchmark.includes=HarnessSanityBenchmark \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 -Pbenchmark.quick=true
./gradlew build spotlessCheck detekt koverVerify koverHtmlReport \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: every command succeeds with the exact current/baseline manifest-adapter pair.

- [ ] **Step 3: Run script, Qodana range, and committed documentation gates**

```bash
for script in \
  docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh \
  docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  docs/superpowers/benchmarks/operators/cs2a-session-installer-v1.sh \
  docs/superpowers/benchmarks/operators/cs2a-remote-session.sh; do
  /bin/bash -n "$script"
  shellcheck "$script"
done
jq -e -f docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq \
  build/benchmark-target-current.json >/dev/null
jq -e -f docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq \
  build/benchmark-target-baseline-selftest.json >/dev/null
./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes --no-configuration-cache --console=plain
./gradlew qodanaScan --no-configuration-cache --console=plain
: "${CS2A_RELEASE_CAS:?set the protected release CAS used by Task 2}"
CS2A_PLAN_SET_PATH=docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md
test "$(git log --diff-filter=A --format=%H -- "$CS2A_PLAN_SET_PATH" | wc -l | tr -d ' ')" = 1
CS2A_PLAN_SET_SHA=$(git log --diff-filter=A --reverse --format=%H -- \
  "$CS2A_PLAN_SET_PATH" | sed -n '1p')
git merge-base --is-ancestor "$CS2A_PLAN_SET_SHA" HEAD
test "$CS2A_PLAN_SET_SHA" != "$(git rev-parse HEAD)"
test "$(git show \
  "$CS2A_PLAN_SET_SHA:docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md" | \
  shasum -a 256 | awk '{print $1}')" = \
  74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333
CS2A_FINAL_DESIGN_SHA256=$(shasum -a 256 \
  docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md | awk '{print $1}')
rg -F "$CS2A_FINAL_DESIGN_SHA256" \
  docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md
./gradlew cs2aQodanaRangeGate \
  -Pcs2a.reviewBase="$CS2A_PLAN_SET_SHA" \
  -Pcs2a.finalDesignSha256="$CS2A_FINAL_DESIGN_SHA256" \
  -Pcs2a.qodanaSarif="$PWD/build/qodana/results/qodana.sarif.json" \
  -Pcs2a.reviewCas="$CS2A_RELEASE_CAS/reviews" \
  --no-parallel --no-build-cache --no-configuration-cache --console=plain
CS2A_QODANA_REVIEW=$(cat build/cs2a-qodana-review-path.txt)
test -f "$CS2A_QODANA_REVIEW"
CS2A_DOC_SHA=$(git rev-parse HEAD)
CS2A_DOC_CLONE=$(mktemp -d "$PWD/build/cs2a-antora.XXXXXXXX")
git clone --no-local "$PWD" "$CS2A_DOC_CLONE/repo"
git -C "$CS2A_DOC_CLONE/repo" checkout --detach "$CS2A_DOC_SHA"
(cd "$CS2A_DOC_CLONE/repo" && npm ci && npx --no-install antora antora-playbook.yml)
test -z "$(git status --porcelain --untracked-files=all)"
```

Expected: scripts parse/lint, both real manifests pass jq, the extracted fixed-range Qodana result
contains zero findings and is immutably stored in the review CAS, and Antora builds from an ordinary
detached clone of the exact committed HEAD.

---

### Task 4: Execute the disposable Linux/root end-to-end matrix

**Files:**
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aLinuxRootSessionIntegrationTest.kt`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aDisposableVmLifecycleIntegrationTest.kt`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aQemuVmLifecycleAdapter.kt`
- Add: `src/integrationTest/resources/cs2a/linux-root/cs2a-syscall-barrier-probe.c`
- Add: `src/test/resources/cs2a/linux-root/scenarios.tsv`
- Add: `src/test/resources/cs2a/linux-root/vm-lifecycle.tsv`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- `cs2aLinuxRootGate` is a checked-in host-side QEMU lifecycle coordinator, not a single in-guest
  Gradle process. `Cs2aQemuVmLifecycleAdapter` has a closed API
  `create -> boot -> runGuestPhase -> killPower -> boot -> runGuestPhase -> collect -> destroy`,
  selects only an explicit canonical root-owned nonwritable QEMU binary/image whose SHA-256 values
  equal the reapproved, independently authenticated test-infrastructure attestation (never merely
  caller-supplied hashes), and uses a private QMP/control socket under its fresh scenario root. It
  creates one VM/durable disk, mounts the exact release set read-only, invokes only
  `cs2aLinuxRootGuestPhase` as guest UID 0, kills the QEMU process without a guest shutdown at named
  durable barriers, reboots the same disk, refreshes the reapproved current-boot namespace
  attestation/selection under its narrow recovery rule, resumes the exact scenario, exports bounded
  result objects, and destroys the VM. A guest exit/exception is not accepted as power loss.
- Each guest phase's root-owned bounded attestation binds the reviewed image SHA-256, VM/disk/scenario
  IDs, current and previous boot IDs, cgroup-v2 mount/controllers, kernel features, disposable
  workspace root, release manifest, and phase nonce. The host coordinator hash-links every pre/post
  boot attestation and result. It rejects macOS as the guest, containers/hosts without the adapter
  attestation, a reused VM, real host `/opt`, missing private mount/PID/user namespaces, or
  unavailable `openat2`, seccomp, OFD-lock, cgroup-kill/events, and abrupt-power-cut capability. CI
  uses only a separately provisioned `cs2a-disposable-linux-root-v1` QEMU runner with the reviewed
  QEMU/image digests; `ubuntu-latest` is not a substitute.
- Required scenarios: normal completion; A/A cutoff; nonzero finalization; benchmark-lock
  contention; HUP/INT/TERM; controller and guardian death; early and late recovery; reboot/power-loss
  at every publication barrier; canonical bundle reuse; signed claim/receipt collection; a second
  launch blocked by ticket/token/global state; normal and manual clearance; and two-token exclusion.
- On success the host coordinator no-clobber publishes the exact reapproved bounded VM/root-gate
  result beneath `CS2A_RELEASE_CAS/reviews/` and writes only
  `build/cs2a-linux-root-result-path.txt`. The result binds every boot/phase/scenario digest, QEMU and
  image identities, release manifest, result inventory, and zero leaked host/guest resources; it is
  an input to final release certification.

- [ ] **Step 1: Add RED root scenarios with exact expected terminal shapes**

Each scenario declares initial host state, kill/fault point, expected durable head, expected
containment/governor/lock state, signed or quarantine artifact kind, local collection eligibility,
and fresh-admission result. A mere process exit code is not sufficient.

- [ ] **Step 2: Implement the single release-binary root gate**

Implement the host coordinator and guest phase as separate test entry points. The coordinator must
refuse a substituted QEMU binary/image, missing real process-kill/reboot/resume capability,
non-fresh VM/disk, or incomplete pre/post boot chain. The guest phase must refuse non-Linux, missing cgroup
v2/openat2/seccomp/OFD capabilities, substituted artifacts, or insufficient disposable isolation.
Neither may target the real host `/opt` tree.
Compile the reapproved test-only static syscall-barrier probe independently of the release set. It
starts the exact digest-verified release executable under `ptrace` syscall stops, authenticates the
scenario/nonce/PID/starttime/executable and anchored FD-to-object map, and emits the fixed host
notification only at the named rename/fsync entry or successful exit. It may pause/observe/kill but
never patch memory, replace a file, inject a return value, or become a production dependency. The
host acknowledges the exact notification and kills QEMU; guest EOF/exit never counts as a power cut.
Tests substitute the probe, executable, syscall, FD/object identity, nonce, and notification order.

- [ ] **Step 3: Compile the harness and commit before root certification**

```bash
./gradlew :integrationTestClasses \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add .github/workflows/build.yml \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aDisposableVmLifecycleIntegrationTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aLinuxRootSessionIntegrationTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aQemuVmLifecycleAdapter.kt \
  src/integrationTest/resources/cs2a/linux-root/cs2a-syscall-barrier-probe.c \
  src/test/resources/cs2a/linux-root/scenarios.tsv \
  src/test/resources/cs2a/linux-root/vm-lifecycle.tsv
git commit -m "test: run CS2a disposable Linux root matrix"
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 4: Run the committed host coordinator across real VM boot epochs**

The Task 2 release set is bound to the earlier Task 2 commit and is intentionally stale after this
task's harness commit. Rebuild and publish a new set for the now-clean committed HEAD before starting
the VM; never pass the old set with a new expected SHA.

```bash
: "${CS2A_RELEASE_CAS:?set protected release CAS}"
: "${CS2A_QEMU_BINARY:?set canonical root-owned QEMU binary path}"
: "${CS2A_QEMU_BINARY_SHA256:?set reviewed QEMU binary digest}"
: "${CS2A_DISPOSABLE_LINUX_IMAGE:?set canonical reviewed VM image path}"
: "${CS2A_DISPOSABLE_LINUX_IMAGE_SHA256:?set the reviewed VM image SHA-256}"
: "${CS2A_VM_INFRA_ATTESTATION:?set independently authenticated infrastructure record}"
: "${CS2A_VM_INFRA_ATTESTATION_SHA256:?set its out-of-band approved SHA-256}"
test -z "$(git status --porcelain --untracked-files=all)"
./gradlew cs2aReleaseArtifactGate \
  -Pcs2a.releaseCas="$CS2A_RELEASE_CAS" \
  --no-parallel --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_RELEASE_SET=$(cat build/cs2a-release-set-path.txt)
test "$(jq -r .implementationSha "$CS2A_RELEASE_SET/release-artifacts.json")" = \
  "$(git rev-parse HEAD)"
CS2A_RELEASE_MANIFEST_SHA256=$(shasum -a 256 \
  "$CS2A_RELEASE_SET/release-artifacts.json" | awk '{print $1}')
./gradlew cs2aLinuxRootGate \
  -Pcs2a.releaseSet="$CS2A_RELEASE_SET" \
  -Pcs2a.releaseManifest="$CS2A_RELEASE_SET/release-artifacts.json" \
  -Pcs2a.releaseManifestSha256="$CS2A_RELEASE_MANIFEST_SHA256" \
  -Pcs2a.expectedImplementationSha="$(git rev-parse HEAD)" \
  -Pcs2a.qemuBinary="$CS2A_QEMU_BINARY" \
  -Pcs2a.qemuBinarySha256="$CS2A_QEMU_BINARY_SHA256" \
  -Pcs2a.disposableLinuxImage="$CS2A_DISPOSABLE_LINUX_IMAGE" \
  -Pcs2a.disposableLinuxImageSha256="$CS2A_DISPOSABLE_LINUX_IMAGE_SHA256" \
  -Pcs2a.vmInfrastructureAttestation="$CS2A_VM_INFRA_ATTESTATION" \
  -Pcs2a.vmInfrastructureAttestationSha256="$CS2A_VM_INFRA_ATTESTATION_SHA256" \
  -Pcs2a.reviewCas="$CS2A_RELEASE_CAS/reviews" \
  --no-parallel --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_ROOT_GATE_RESULT=$(cat build/cs2a-linux-root-result-path.txt)
test -f "$CS2A_ROOT_GATE_RESULT"
test -z "$(git status --porcelain --untracked-files=all)"
```

Expected: every scenario reaches its exact durable and local-validation state with no leaked
process, lock, governor mutation, or temporary root.

---

### Task 5: Freeze and independently review the implementation SHA

**Files:**
- Modify only if review finds a documentation-only defect; code findings return to the owning plan.

**Interfaces:**
- Fixed base is the plan-set commit. Fixed head is the clean implementation commit after Tasks 1-4.
- Three independent reviews: repository Standards, approved Spec, and security/adversarial temporal
  review. Each reads the exact same immutable range and all executing artifacts.

- [ ] **Step 1A: Recreate the final clean-SHA artifact set serially**

```bash
: "${CS2A_RELEASE_CAS:?set the protected release CAS}"
: "${CS2A_APPROVED_DESIGN_CLOSURE_COMMIT:?set the pre-code approved closure commit}"
: "${CS2A_APPROVED_DESIGN_SHA256:?set the out-of-band approved design digest}"
: "${CS2A_DESIGN_APPROVAL_RECORD:?set the immutable pre-code approval record}"
: "${CS2A_DESIGN_APPROVAL_RECORD_SHA256:?set its out-of-band approved digest}"
test -z "$(git status --porcelain --untracked-files=all)"
CS2A_FINAL_SHA=$(git rev-parse HEAD)
CS2A_PLAN_SET_PATH=docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md
test "$(git log --diff-filter=A --format=%H -- "$CS2A_PLAN_SET_PATH" | wc -l | tr -d ' ')" = 1
CS2A_PLAN_SET_SHA=$(git log --diff-filter=A --reverse --format=%H -- \
  "$CS2A_PLAN_SET_PATH" | sed -n '1p')
CS2A_PLAN_SET_PARENT_SHA=56092029b97c15bddac30354b51601be2a477d3c
test "$(git rev-parse "$CS2A_PLAN_SET_SHA^")" = "$CS2A_PLAN_SET_PARENT_SHA"
test -z "$(git diff --name-only "$CS2A_PLAN_SET_PARENT_SHA" \
  "$CS2A_PLAN_SET_SHA" | \
  awk '!/^docs\/superpowers\/plans\// {print}')"
test "$(git rev-parse "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT^{commit}")" = \
  "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT"
git merge-base --is-ancestor "$CS2A_PLAN_SET_SHA" "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT"
git merge-base --is-ancestor "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" "$CS2A_FINAL_SHA"
test "$CS2A_PLAN_SET_SHA" != "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT"
test "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" != "$CS2A_FINAL_SHA"
test "$(git rev-parse "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT^")" = \
  "$CS2A_PLAN_SET_SHA"
test -z "$(git diff-tree --no-commit-id --name-only -r \
  "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" | \
  awk '!/^docs\/superpowers\/(plans|specs)\// {print}')"
test -z "$(git diff --name-only "$CS2A_PLAN_SET_SHA" \
  "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" | \
  awk '!/^docs\/superpowers\/(plans|specs)\// {print}')"
test "$(git show \
  "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT:docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md" | \
  shasum -a 256 | awk '{print $1}')" = "$CS2A_APPROVED_DESIGN_SHA256"
CS2A_FINAL_DESIGN_SHA256=$(shasum -a 256 \
  docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md | awk '{print $1}')
test "$CS2A_FINAL_DESIGN_SHA256" = "$CS2A_APPROVED_DESIGN_SHA256"
test "$(shasum -a 256 "$CS2A_DESIGN_APPROVAL_RECORD" | awk '{print $1}')" = \
  "$CS2A_DESIGN_APPROVAL_RECORD_SHA256"
./gradlew cs2aMutationGate --no-parallel \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew cs2aReleaseArtifactGate -Pcs2a.releaseCas="$CS2A_RELEASE_CAS" \
  --no-parallel --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_RELEASE_SET=$(cat build/cs2a-release-set-path.txt)
test "$(jq -r .implementationSha "$CS2A_RELEASE_SET/release-artifacts.json")" = \
  "$CS2A_FINAL_SHA"
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 1B: Approve the final bootstrap verifier independently**

A separately authenticated administrator independently rebuilds the verifier
distribution, audits the exact JDK and design-approved process-envelope executor, and publishes the
distinct post-build bootstrap approval record/digest frozen by Plan 1. Record its independently
authenticated values as `CS2A_BOOTSTRAP_VERIFIER_HOME`, `CS2A_BOOTSTRAP_JAVA_HOME`,
`CS2A_BOOTSTRAP_EXECUTOR`, `CS2A_BOOTSTRAP_EXECUTOR_SHA256`,
`CS2A_BOOTSTRAP_APPROVAL_RECORD`, and `CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256`. The record must bind
this final release-set verifier inventory; it neither changes nor replaces the pre-code design
approval.

```bash
: "${CS2A_BOOTSTRAP_VERIFIER_HOME:?set approved final verifier distribution}"
: "${CS2A_BOOTSTRAP_JAVA_HOME:?set approved verifier JDK}"
: "${CS2A_BOOTSTRAP_EXECUTOR:?set approved clean process-envelope executor}"
: "${CS2A_BOOTSTRAP_EXECUTOR_SHA256:?set executor digest from the administrator}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD:?set immutable post-build bootstrap approval}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256:?set its out-of-band approved digest}"
test "$(shasum -a 256 "$CS2A_BOOTSTRAP_EXECUTOR" | awk '{print $1}')" = \
  "$CS2A_BOOTSTRAP_EXECUTOR_SHA256"
test "$(shasum -a 256 "$CS2A_BOOTSTRAP_APPROVAL_RECORD" | awk '{print $1}')" = \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256"
set +e
CS2A_DESIGN_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-design-approval \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" "$CS2A_APPROVED_DESIGN_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_DESIGN_VERIFY_STATUS=$?
set -e
test "$CS2A_DESIGN_VERIFY_STATUS" -eq 0
case "$CS2A_DESIGN_VERIFY_RESULT" in
  "revoman-cs2a-verified-design-approval/v1"$'\t'*) ;;
  *) exit 70 ;;
esac
test -z "$(git status --porcelain --untracked-files=all)"
```

The administrator's procedure and record schema are the ones reapproved in Plan 1; an
implementation-generated inventory, self-signed record, or mutable pointer does not satisfy this
checkpoint.

- [ ] **Step 2: Re-run the full JVM, driver, harness, ABI, Kover, Detekt, and Spotless block**

```bash
./gradlew :benchmark-driver:installDist \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.targetId=current-cs2a \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_FINAL_SELFTEST=$(mktemp -d "$PWD/build/cs2a-final-selftest.XXXXXXXX")
git worktree add --detach "$CS2A_FINAL_SELFTEST/baseline" \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
test -z "$(git -C "$CS2A_FINAL_SELFTEST/baseline" status --porcelain)"
"$CS2A_FINAL_SELFTEST/baseline/gradlew" -p "$CS2A_FINAL_SELFTEST/baseline" \
  -I "$PWD/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-baseline-selftest.json" \
  -Pbenchmark.targetId=baseline-selftest-83f3cd70 --no-daemon --console=plain
./gradlew checkKotlinAbi apiCompatibilityTestClasses :test :integrationTest \
  :benchmark-driver:test \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle retained campaign preserves v2 series identity*' \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:benchmarkCleanInstallTaskGraphTest \
  :benchmark-driver:benchmarkJmhTaskSerializationTest \
  :benchmark-driver:benchmarkJmhFreshnessTest \
  :benchmark-driver:benchmarkJmhOutputCollisionTest \
  :benchmark-driver:benchmarkHarnessSelfTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew :benchmark-driver:benchmarkJmh \
  -Pbenchmark.includes=HarnessSanityBenchmark \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 -Pbenchmark.quick=true \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
./gradlew build spotlessCheck detekt koverVerify koverHtmlReport \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 3: Re-run scripts, real-manifest jq, Qodana range, and committed Antora**

```bash
for script in \
  docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh \
  docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  docs/superpowers/benchmarks/operators/cs2a-session-installer-v1.sh \
  docs/superpowers/benchmarks/operators/cs2a-remote-session.sh; do
  /bin/bash -n "$script"
  shellcheck "$script"
done
jq -e -f docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq \
  build/benchmark-target-current.json >/dev/null
jq -e -f docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq \
  build/benchmark-target-baseline-selftest.json >/dev/null
./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes --no-configuration-cache --console=plain
./gradlew qodanaScan --no-configuration-cache --console=plain
git merge-base --is-ancestor "$CS2A_PLAN_SET_SHA" "$CS2A_FINAL_SHA"
test "$CS2A_PLAN_SET_SHA" != "$CS2A_FINAL_SHA"
test "$(git show \
  "$CS2A_PLAN_SET_SHA:docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md" | \
  shasum -a 256 | awk '{print $1}')" = \
  74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333
./gradlew cs2aQodanaRangeGate \
  -Pcs2a.reviewBase="$CS2A_PLAN_SET_SHA" \
  -Pcs2a.designClosureCommit="$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" \
  -Pcs2a.finalDesignSha256="$CS2A_FINAL_DESIGN_SHA256" \
  -Pcs2a.designApprovalRecord="$CS2A_DESIGN_APPROVAL_RECORD" \
  -Pcs2a.designApprovalRecordSha256="$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  -Pcs2a.qodanaSarif="$PWD/build/qodana/results/qodana.sarif.json" \
  -Pcs2a.reviewCas="$CS2A_RELEASE_CAS/reviews" \
  --no-parallel --no-build-cache --no-configuration-cache --console=plain
CS2A_QODANA_REVIEW=$(cat build/cs2a-qodana-review-path.txt)
test -f "$CS2A_QODANA_REVIEW"
CS2A_FINAL_DOC_CLONE=$(mktemp -d "$PWD/build/cs2a-final-antora.XXXXXXXX")
git clone --no-local "$PWD" "$CS2A_FINAL_DOC_CLONE/repo"
git -C "$CS2A_FINAL_DOC_CLONE/repo" checkout --detach "$CS2A_FINAL_SHA"
(cd "$CS2A_FINAL_DOC_CLONE/repo" && npm ci && \
  npx --no-install antora antora-playbook.yml)
test -z "$(git status --porcelain --untracked-files=all)"
```

- [ ] **Step 4: Re-run the exact final release set through a fresh authenticated VM lifecycle**

From the authenticated host coordinator with a fresh VM/disk satisfying Task 4's contract:

```bash
: "${CS2A_QEMU_BINARY:?set canonical root-owned QEMU binary path}"
: "${CS2A_QEMU_BINARY_SHA256:?set reviewed QEMU binary digest}"
: "${CS2A_DISPOSABLE_LINUX_IMAGE:?set canonical reviewed VM image path}"
: "${CS2A_DISPOSABLE_LINUX_IMAGE_SHA256:?set reviewed VM image SHA-256}"
: "${CS2A_VM_INFRA_ATTESTATION:?set independently authenticated infrastructure record}"
: "${CS2A_VM_INFRA_ATTESTATION_SHA256:?set its out-of-band approved SHA-256}"
CS2A_RELEASE_MANIFEST_SHA256=$(shasum -a 256 \
  "$CS2A_RELEASE_SET/release-artifacts.json" | awk '{print $1}')
./gradlew cs2aLinuxRootGate \
  -Pcs2a.releaseSet="$CS2A_RELEASE_SET" \
  -Pcs2a.releaseManifest="$CS2A_RELEASE_SET/release-artifacts.json" \
  -Pcs2a.releaseManifestSha256="$CS2A_RELEASE_MANIFEST_SHA256" \
  -Pcs2a.expectedImplementationSha="$CS2A_FINAL_SHA" \
  -Pcs2a.qemuBinary="$CS2A_QEMU_BINARY" \
  -Pcs2a.qemuBinarySha256="$CS2A_QEMU_BINARY_SHA256" \
  -Pcs2a.disposableLinuxImage="$CS2A_DISPOSABLE_LINUX_IMAGE" \
  -Pcs2a.disposableLinuxImageSha256="$CS2A_DISPOSABLE_LINUX_IMAGE_SHA256" \
  -Pcs2a.vmInfrastructureAttestation="$CS2A_VM_INFRA_ATTESTATION" \
  -Pcs2a.vmInfrastructureAttestationSha256="$CS2A_VM_INFRA_ATTESTATION_SHA256" \
  -Pcs2a.reviewCas="$CS2A_RELEASE_CAS/reviews" \
  --no-parallel --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
CS2A_ROOT_GATE_RESULT=$(cat build/cs2a-linux-root-result-path.txt)
test -f "$CS2A_ROOT_GATE_RESULT"
test -z "$(git status --porcelain --untracked-files=all)"
```

Expected: every design-required gate has run against the same final committed SHA and immutable
release set.

- [ ] **Step 5: Run fixed-range reviews and repair until all pass**

Use `code-review` for Standards+Spec and a separate adversarial security review against the exact
base/head SHAs. Any Critical/Important finding creates a corrective commit, invalidates the frozen
head and release set, and repeats Steps 1-5.

Each approved reviewer signs one no-clobber reapproved `revoman-cs2a-fixed-range-review/v1`
envelope/commit pair beneath `CS2A_RELEASE_CAS/reviews/`, binding `CS2A_PLAN_SET_SHA`,
`CS2A_FINAL_SHA`, final approved design digest, review kind, reviewer/tool identity, reviewed
source/artifact inventories, findings (empty for Critical/Important), and verdict. Verify each pair
with `cs2aFixedRangeReviewVerify` and the explicit reviewer trust-policy/high-water before setting
`CS2A_STANDARDS_REVIEW`, `CS2A_SPEC_REVIEW`, and `CS2A_SECURITY_REVIEW` to the verified paths; caller
prose, an unsigned JSON file, or a chat verdict is never a certification input. Execute the
reapproved verifier immediately for all three pairs and retain only its captured canonical results:

```bash
: "${CS2A_STANDARDS_REVIEW:?set signed Standards review pair}"
: "${CS2A_SPEC_REVIEW:?set signed Spec review pair}"
: "${CS2A_SECURITY_REVIEW:?set signed security review pair}"
: "${CS2A_REVIEW_TRUST_POLICY:?set approved reviewer trust policy}"
: "${CS2A_REVIEW_TRUST_POLICY_SHA256:?set its out-of-band digest}"
: "${CS2A_REVIEW_TRUST_HIGH_WATER:?set reviewer trust high-water}"
: "${CS2A_REVIEW_TRUST_HIGH_WATER_SHA256:?set its out-of-band digest}"
CS2A_QODANA_REVIEW_SHA256=$(shasum -a 256 "$CS2A_QODANA_REVIEW" | awk '{print $1}')
CS2A_ROOT_GATE_RESULT_SHA256=$(shasum -a 256 "$CS2A_ROOT_GATE_RESULT" | awk '{print $1}')
test "${#CS2A_QODANA_REVIEW_SHA256}" -eq 64
test "${#CS2A_ROOT_GATE_RESULT_SHA256}" -eq 64
: "${CS2A_BOOTSTRAP_VERIFIER_HOME:?set approved verifier distribution}"
: "${CS2A_BOOTSTRAP_JAVA_HOME:?set approved verifier JDK}"
: "${CS2A_BOOTSTRAP_EXECUTOR:?set approved clean process-envelope executor}"
: "${CS2A_BOOTSTRAP_EXECUTOR_SHA256:?set its out-of-band digest}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD:?set post-build bootstrap approval}"
: "${CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256:?set its out-of-band digest}"
test "$(shasum -a 256 "$CS2A_BOOTSTRAP_EXECUTOR" | awk '{print $1}')" = \
  "$CS2A_BOOTSTRAP_EXECUTOR_SHA256"
for CS2A_REVIEW_KIND in standards spec security; do
  case "$CS2A_REVIEW_KIND" in
    standards) CS2A_REVIEW_PATH=$CS2A_STANDARDS_REVIEW ;;
    spec) CS2A_REVIEW_PATH=$CS2A_SPEC_REVIEW ;;
    security) CS2A_REVIEW_PATH=$CS2A_SECURITY_REVIEW ;;
  esac
  CS2A_REVIEW_SHA256=$(shasum -a 256 "$CS2A_REVIEW_PATH" | awk '{print $1}')
  set +e
  CS2A_REVIEW_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
    "$CS2A_BOOTSTRAP_EXECUTOR" \
    "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
    cs2a-release-set-verifier verify-fixed-range-review \
    "$CS2A_REVIEW_KIND" "$CS2A_REVIEW_PATH" "$CS2A_REVIEW_SHA256" \
    "$CS2A_REVIEW_TRUST_POLICY" "$CS2A_REVIEW_TRUST_POLICY_SHA256" \
    "$CS2A_REVIEW_TRUST_HIGH_WATER" "$CS2A_REVIEW_TRUST_HIGH_WATER_SHA256" \
    "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256" \
    "$CS2A_PLAN_SET_SHA" "$CS2A_FINAL_SHA" \
    "$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" "$CS2A_FINAL_DESIGN_SHA256" \
    "$CS2A_RELEASE_MANIFEST_SHA256" "$CS2A_QODANA_REVIEW_SHA256" \
    "$CS2A_ROOT_GATE_RESULT_SHA256")
  CS2A_REVIEW_VERIFY_STATUS=$?
  set -e
  test "$CS2A_REVIEW_VERIFY_STATUS" -eq 0
  case "$CS2A_REVIEW_RESULT" in
    "revoman-cs2a-verified-review/v1"$'\t'"$CS2A_REVIEW_KIND"$'\t'*) ;;
    *) exit 70 ;;
  esac
  case "$CS2A_REVIEW_KIND" in
    standards)
      CS2A_STANDARDS_REVIEW_SHA256=$CS2A_REVIEW_SHA256
      CS2A_STANDARDS_REVIEW_RESULT=$CS2A_REVIEW_RESULT ;;
    spec)
      CS2A_SPEC_REVIEW_SHA256=$CS2A_REVIEW_SHA256
      CS2A_SPEC_REVIEW_RESULT=$CS2A_REVIEW_RESULT ;;
    security)
      CS2A_SECURITY_REVIEW_SHA256=$CS2A_REVIEW_SHA256
      CS2A_SECURITY_REVIEW_RESULT=$CS2A_REVIEW_RESULT ;;
  esac
done
```

The executor supplies the approved read-only cwd, exact three-FD envelope, clean environment, and
no ambient agent/provider state. Step 6 passes both each immutable record digest and its verified
result; the certification publisher and final release verifier reopen and reverify the exact bytes,
so pathname substitution between review and certification fails.

- [ ] **Step 6: Record the eligible SHA and release set without provisioning**

```bash
test "$(git rev-parse HEAD)" = "$CS2A_FINAL_SHA"
test -z "$(git status --porcelain --untracked-files=all)"
: "${CS2A_QODANA_REVIEW:?set exact Qodana range review record}"
: "${CS2A_ROOT_GATE_RESULT:?set exact multi-boot root-gate result}"
: "${CS2A_STANDARDS_REVIEW:?set Standards PASS record}"
: "${CS2A_SPEC_REVIEW:?set Spec PASS record}"
: "${CS2A_SECURITY_REVIEW:?set adversarial security PASS record}"
: "${CS2A_REVIEW_TRUST_POLICY:?set approved reviewer trust policy}"
: "${CS2A_REVIEW_TRUST_POLICY_SHA256:?set its out-of-band digest}"
: "${CS2A_REVIEW_TRUST_HIGH_WATER:?set reviewer high-water record}"
: "${CS2A_REVIEW_TRUST_HIGH_WATER_SHA256:?set its out-of-band digest}"
: "${CS2A_STANDARDS_REVIEW_SHA256:?set verified Standards record digest}"
: "${CS2A_SPEC_REVIEW_SHA256:?set verified Spec record digest}"
: "${CS2A_SECURITY_REVIEW_SHA256:?set verified security record digest}"
: "${CS2A_STANDARDS_REVIEW_RESULT:?set canonical verifier result}"
: "${CS2A_SPEC_REVIEW_RESULT:?set canonical verifier result}"
: "${CS2A_SECURITY_REVIEW_RESULT:?set canonical verifier result}"
test -f "$CS2A_RELEASE_SET/release-artifacts.json"
CS2A_RELEASE_MANIFEST_SHA256=$(shasum -a 256 \
  "$CS2A_RELEASE_SET/release-artifacts.json" | awk '{print $1}')
./gradlew cs2aReleaseCertificationGate \
  -Pcs2a.releaseCas="$CS2A_RELEASE_CAS" \
  -Pcs2a.releaseSet="$CS2A_RELEASE_SET" \
  -Pcs2a.releaseManifestSha256="$CS2A_RELEASE_MANIFEST_SHA256" \
  -Pcs2a.implementationSha="$CS2A_FINAL_SHA" \
  -Pcs2a.planSetSha="$CS2A_PLAN_SET_SHA" \
  -Pcs2a.designClosureCommit="$CS2A_APPROVED_DESIGN_CLOSURE_COMMIT" \
  -Pcs2a.finalDesignSha256="$CS2A_FINAL_DESIGN_SHA256" \
  -Pcs2a.designApprovalRecord="$CS2A_DESIGN_APPROVAL_RECORD" \
  -Pcs2a.designApprovalRecordSha256="$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  -Pcs2a.qodanaReview="$CS2A_QODANA_REVIEW" \
  -Pcs2a.qodanaReviewSha256="$CS2A_QODANA_REVIEW_SHA256" \
  -Pcs2a.rootGateResult="$CS2A_ROOT_GATE_RESULT" \
  -Pcs2a.rootGateResultSha256="$CS2A_ROOT_GATE_RESULT_SHA256" \
  -Pcs2a.standardsReview="$CS2A_STANDARDS_REVIEW" \
  -Pcs2a.standardsReviewSha256="$CS2A_STANDARDS_REVIEW_SHA256" \
  -Pcs2a.standardsReviewResult="$CS2A_STANDARDS_REVIEW_RESULT" \
  -Pcs2a.specReview="$CS2A_SPEC_REVIEW" \
  -Pcs2a.specReviewSha256="$CS2A_SPEC_REVIEW_SHA256" \
  -Pcs2a.specReviewResult="$CS2A_SPEC_REVIEW_RESULT" \
  -Pcs2a.securityReview="$CS2A_SECURITY_REVIEW" \
  -Pcs2a.securityReviewSha256="$CS2A_SECURITY_REVIEW_SHA256" \
  -Pcs2a.securityReviewResult="$CS2A_SECURITY_REVIEW_RESULT" \
  -Pcs2a.reviewTrustPolicy="$CS2A_REVIEW_TRUST_POLICY" \
  -Pcs2a.reviewTrustPolicySha256="$CS2A_REVIEW_TRUST_POLICY_SHA256" \
  -Pcs2a.reviewTrustHighWater="$CS2A_REVIEW_TRUST_HIGH_WATER" \
  -Pcs2a.reviewTrustHighWaterSha256="$CS2A_REVIEW_TRUST_HIGH_WATER_SHA256" \
  -Pcs2a.vmInfrastructureAttestation="$CS2A_VM_INFRA_ATTESTATION" \
  -Pcs2a.vmInfrastructureAttestationSha256="$CS2A_VM_INFRA_ATTESTATION_SHA256" \
  -Pcs2a.bootstrapApprovalRecord="$CS2A_BOOTSTRAP_APPROVAL_RECORD" \
  -Pcs2a.bootstrapApprovalRecordSha256="$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256" \
  -Pcs2a.bootstrapExecutorSha256="$CS2A_BOOTSTRAP_EXECUTOR_SHA256" \
  --no-parallel --no-build-cache --no-configuration-cache --console=plain
CS2A_RELEASE_CERTIFICATION=$(cat build/cs2a-release-certification-path.txt)
CS2A_RELEASE_CERTIFICATION_CANDIDATE_SHA256=$(shasum -a 256 \
  "$CS2A_RELEASE_CERTIFICATION" | awk '{print $1}')
```

Pause here. A separately authenticated administrator reviews those exact no-clobber bytes and
records their digest as `CS2A_RELEASE_CERTIFICATION_SHA256`; a shell-computed candidate digest is
not the trust anchor. Then authenticate the final handoff without loading candidate Gradle:

```bash
: "${CS2A_RELEASE_CERTIFICATION_SHA256:?set administrator-approved certification digest}"
test "$CS2A_RELEASE_CERTIFICATION_CANDIDATE_SHA256" = \
  "$CS2A_RELEASE_CERTIFICATION_SHA256"
set +e
CS2A_RELEASE_VERIFY_RESULT=$(env -i PATH=/usr/bin:/bin LC_ALL=C TZ=UTC \
  "$CS2A_BOOTSTRAP_EXECUTOR" \
  "$CS2A_BOOTSTRAP_VERIFIER_HOME" "$CS2A_BOOTSTRAP_JAVA_HOME" \
  cs2a-release-set-verifier verify-release-set \
  "$CS2A_RELEASE_SET" "$CS2A_RELEASE_CERTIFICATION" \
  "$CS2A_RELEASE_CERTIFICATION_SHA256" "$CS2A_FINAL_SHA" \
  "$CS2A_DESIGN_APPROVAL_RECORD" "$CS2A_DESIGN_APPROVAL_RECORD_SHA256" \
  "$CS2A_BOOTSTRAP_APPROVAL_RECORD" "$CS2A_BOOTSTRAP_APPROVAL_RECORD_SHA256")
CS2A_RELEASE_VERIFY_STATUS=$?
set -e
test "$CS2A_RELEASE_VERIFY_STATUS" -eq 0
IFS=$'\t' read -r CS2A_RELEASE_RESULT_VERSION CS2A_RELEASE_RESULT_IMPLEMENTATION \
  CS2A_RELEASE_RESULT_MANIFEST_SHA256 CS2A_RELEASE_RESULT_INVENTORY_SHA256 \
  CS2A_RELEASE_RESULT_CERTIFICATION_SHA256 CS2A_RELEASE_RESULT_SET_IDENTITY \
  CS2A_RELEASE_RESULT_EXTRA <<<"$CS2A_RELEASE_VERIFY_RESULT"
test "$CS2A_RELEASE_RESULT_VERSION" = revoman-cs2a-verified-release/v1
test "$CS2A_RELEASE_RESULT_IMPLEMENTATION" = "$CS2A_FINAL_SHA"
test "$CS2A_RELEASE_RESULT_CERTIFICATION_SHA256" = \
  "$CS2A_RELEASE_CERTIFICATION_SHA256"
test -z "$CS2A_RELEASE_RESULT_EXTRA"
test -z "$(git status --porcelain --untracked-files=all)"
git diff --check
```

Expected: one clean reviewed implementation SHA, its content-addressed release set, and one exact
certification record/digest. A separately authenticated administrator records
`CS2A_RELEASE_CERTIFICATION_SHA256` out of band before Plan 6; CAS ownership/mode is not the trust
anchor. Do not provision, push measurement inputs, or run the benchmark in this task.
