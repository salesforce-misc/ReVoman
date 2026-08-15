# CS2a Remote Orchestration and Receipts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the existing controlled benchmark behind the durable root session state machine and
publish exactly one recoverable terminal shape for every admitted outcome: a signed terminal pair
when the session-bound signer/key is usable, otherwise the design's unsigned safety quarantine.

**Architecture:** The canonical bundle's remote-session script dispatches only sealed run/recover
state. The supervisor owns transitions, transcripts, cgroup, guardian, governor lifecycle, prepared
run root, native child, and finalization. The controlled runner retains the proven A/A and
baseline/candidate commands but receives all authority through fixed FDs and closed environments.
Root publishes a bounded immutable receipt and then a separately signed READY causal commit.

**Tech Stack:** Bash privileged mode, native entry/copier/signer from Plans 1-2, cgroup v2, OFD
locks, Linux `/proc`, fsync/rename durability, jq, Gradle benchmark driver, Kotest, and disposable
Linux/root fault injection.

## Global Constraints

- Depends on Plans 1 and 2. Use their exact release binaries and protocol fixtures; do not emulate
  native behavior with test-only shell helpers in Linux/root acceptance.
- The supervisor remains the only launcher and lifecycle owner of the controlled runner. The
  remote-session orchestrator never launches Gradle/Java directly.
- Preserve the exact A/A cutoff, cold/warm/retained command sequence, seed, sample counts, workload,
  adapters, and comparator behavior already present in `cs2a-controlled-run.sh`.
- The runner never creates/selects its authoritative run root, owns the true benchmark-lock OFD,
  writes a root transcript, or publishes terminal authority.
- Every phase/marker/process/terminal **transition-state update** is one hash-linked complete
  transition snapshot. Preserve the design's independently ordered durable raw wait-status,
  transcript, guardian ACK, receipt, READY, and global-ledger transactions; each gains authority only
  at its design-specific durability barrier and cross-link and is never collapsed into a transition
  snapshot. In particular, the parent-fsynced guardian ACK authorizes its FD-7 notification, true-lock
  close, and exit before the later `guardian-release` transition can bind that completed edge.
- Once `RUN_ROOT` exists, malformed/missing final handoff terminates as the closed
  `run-finalization-failure` receipt; receipt/READY publication failures do not create a substitute
  terminal receipt.

---

### Task 1: Add the closed remote-session dispatcher and transition machine

**Files:**
- Add: `docs/superpowers/benchmarks/operators/cs2a-remote-session.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteOrchestratorTest.kt`
- Add: `src/test/resources/cs2a/protocol/transition-vectors.tsv`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteSessionStateTest.kt`

**Interfaces:**
- Exact argv: `run TOKEN` or `recover TOKEN`; root identity, sealed entry handoff, the inherited
  lock-owning admission OFD, token/claim and lifecycle locks, and canonical bundle/session FDs are
  mandatory. The admission OFD remains in every privileged orchestrator/supervisor/finalizer/
  publisher lineage through durable terminal-observed publication and is closed before any
  controlled-child/workload exec.
- `run` accepts only an existing durable genesis whose installer-owned phase is
  `bundle-installed`, validates and uses the installer-precreated already-active controller
  transcript inode/owner epoch, advances to `supervisor-starting`, authenticates the sealed
  bundle/session/handoff, and invokes the canonical supervisor. The
  supervisor, not the orchestrator, owns host/JDK/seed/account/process/cgroup preflight.
- `recover` distinguishes claim-only state (installer-owned, so orchestrator forbidden), active
  authenticated controller, orphan controlled group, post-receipt causal completion, and
  stateful recovery. It never enters fresh-run code.
- Transition events, allowed same-phase events, owner epochs, markers, terminal fields, namespace
  remaps, and parent-fsync adoption exactly match the design's closed graph.

- [ ] **Step 1: Add RED state-machine and owner/transcript tests**

Create fixtures for every valid phase/marker combination plus gapped/forked chains, stale owner,
wrong token, standalone status, unsealed transcript, changed-boot remap, pre-genesis dispatch,
receipt-before-READY, READY-before-terminal-observed, and every transition rename/fsync boundary.
Mutate missing/substituted/unlocked/prematurely closed admission OFDs, leakage into the controlled
child, and a second admission acquirer before versus after terminal-observed parent fsync.

```bash
./gradlew :test --tests '*Cs2aRemoteOrchestratorTest' \
  --tests '*Cs2aRemoteSessionStateTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: RED because the orchestrator does not exist.

- [ ] **Step 2: Implement strict run/recover dispatch and durability**

The script validates all inherited authorities before any external command and uses only the sealed
bundle/state. A recovery activation atomically seals the abandoned transcript and commits the new
owner; changed-boot namespace remap is part of that first activation before containment,
restoration, signing, or publication.

- [ ] **Step 3: Run GREEN and commit**

```bash
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-remote-session.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-remote-session.sh
./gradlew :test --tests '*Cs2aRemoteOrchestratorTest' \
  --tests '*Cs2aRemoteSessionStateTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-remote-session.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteOrchestratorTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteSessionStateTest.kt \
  src/test/resources/cs2a/protocol/transition-vectors.tsv
git commit -m "feat: add CS2a remote session dispatcher"
```

---

### Task 2: Refit the controlled runner to FD and environment authority

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aControlledRunnerGateTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt`

**Interfaces:**
- Exact argv: `--run-root-fd 10`.
- Initial FDs are canonical `/dev/null` FD 0, bounded diagnostics 1/2, non-lock-owning authorization
  FD 9, run-root FD 10, post-exec handoff gate FD 13, seed-copy-ready FD 14, and seed-verification gate FD 15. The
  runner's first instruction blocks on FD 13 until it receives the exact durable
  `process-identity-handoff` digest; after that it validates/records the observed environment and
  enters the seed-copy protocol. After the seed gate only 0/1/2/9/10 remain.
- The runner validates `nativeEnv/v1`, records the normalized Bash-created
  `runnerObservedEnv/v1`, canonical token-derived run-root child paths, absolute sealed Java,
  source repository, immutable Gradle seed, and exact lock contention before any tool work.
- It never uses `/proc/self/fd/10/...` as a descendant path; FD 10 authenticates the runner's
  initial anchored derivation, and descendants receive canonical transition-bound paths.

- [ ] **Step 1: Add RED direct-runner, env, FD, and seed-gate tests**

Cover direct invocation, caller-selected run root, missing/wrong/non-`/dev/null` FD 0,
wrong/lock-owning FD 9, missing/reused FD 10, extra/leaked FD, early Gradle, wrong FD-14/15 framing, injected Java/Gradle/Bash variables, colon or
empty PATH segment, ambient Gradle home/init/daemon/network, poisoned seed path, source drift, and
normal writable Gradle-home mutation after the verified copy.

- [ ] **Step 2: Replace only runner bootstrap and preserve workload commands**

Move the existing A/A and baseline/candidate command block intact behind the new gates. Root owns
stdout/stderr framing; runner output is child diagnostic data and can never be parsed as a control
record. Keep the existing A/A non-PASS stop exactly where it is.

- [ ] **Step 3: Verify command preservation and commit**

```bash
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh
./gradlew :test --tests '*Cs2aControlledRunnerGateTest' \
  --tests '*Cs2aOperatorScriptTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aControlledRunnerGateTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt
git commit -m "refactor: gate CS2a runner with sealed authority"
```

Expected: existing benchmark command/order snapshots remain unchanged after the new pre-work gate.

---

### Task 3: Rebuild supervisor lifecycle, guardian, and recovery

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh`
- Split/modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aSupervisorAtomicHandoffTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aGovernorRecoveryTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aGuardianLifecycleTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRunRootAndQuotaTest.kt`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aSupervisorLinuxRootIntegrationTest.kt`

**Interfaces:**
- Exact root-only supervisor argv has three disjoint forms: zero arguments for the exactly-once fresh
  run selected by the authenticated orchestrator, `--finalize-session TOKEN`, or
  `--recover-session TOKEN`. Fresh mode obtains the token only from inherited sealed state/FDs; it
  rejects a caller token argument. No form accepts a caller path, status, phase, PID, run root,
  governor state, or policy operand. The orchestrator authenticates state and chooses the form; the
  supervisor never infers fresh versus recovery from mutable caller data.
- Supervisor root preflight seals runtime/JDK/seed/account/host/process/cgroup identities and
  reserves the design's exact metadata, object, directory, transition, transcript, projection,
  workspace, and terminal headroom before side effects.
- It durably seals the complete governor snapshot before mutation; creates/registers/activates the
  native guardian; creates and fsyncs the root-parent-owned run-root entry; commits the native child
  launch; performs post-drop/process handoff; gates seed copy; and only then allows workload.
- Recovery distinguishes a healthy live guardian from unexpected guardian death. With a healthy
  guardian it authenticates the contended true lock, waits for the guardian's bounded containment,
  restores only the durable governor snapshot while that guardian still owns exclusion, commits the
  restoration/terminal transition, obtains the durable release ACK, and only then acquires/probes the
  released benchmark lock. After unexpected guardian death it first marks clean-host failure and
  contains the cgroup, then acquires the released lock and restores. Both branches complete terminal
  publication and never rerun workload.
- After controlled-group absence and before terminal publication, finalization commits the
  pre-seal process/cgroup/host-policy snapshot and exact `cleanHostPrerequisite`, performs and
  commits `jdkPostGroupWalkState`, rewalks the immutable Gradle-seed source, and forbids a
  performance decision on any drift/failure. Destination Gradle-home mutation remains historical
  copy evidence and is not compared to the immutable seed inventory.
- The kernel wait result is first preserved as bounded immutable raw wait-status evidence. The
  terminal transition embeds its exact path/size/digest/bytes and status/source interpretation;
  the raw file is never independently current or authoritative.

- [ ] **Step 1: Add RED power-loss, quota, cgroup, and recovery mutants**

Cover every governor snapshot/mutation/restoration barrier, guardian register/activate/release ACK,
controller/guardian death, child-survives-controller, cgroup membership/kill/empty transitions,
run-root create/chown/quota/fsync/rename/reopen, same-boot and separate-`/opt` reboot remap,
transition/object/unique-directory/path/byte quota maxima, transcript framing, signals/statuses
0/3/70/143, and two-token exclusion. Include missing/extra supervisor argv, legacy path/status
operands, wrong orchestrator dispatch, torn/altered/uncommitted raw wait status, omitted/failing JDK
post-group walk, immutable seed-source drift, and detected clean-host drift after an otherwise
complete candidate run.
Assert the healthy-guardian ordering `contain -> restore -> durable release/ACK -> lock acquire` and
the dead-guardian ordering `mark failure -> contain -> lock acquire -> restore`; every cross-order
mutation must fail without a performance decision.
Add a positive zero-argument fresh invocation and reject `TOKEN`, `--run-session`, finalizer, or
recovery cross-mode substitution; prove the orchestrator invokes fresh mode exactly once and never
during recovery.

- [ ] **Step 2: Implement the exact supervisor state machine**

Use separate length-prefixed root-control, child-stdout, and child-stderr transcript records. The
child never inherits a transcript FD. Reserve at least the final eight complete transition
snapshots (16 base objects), 24 branch members, eight transcripts, and every receipt wrapper before
mutation/release. If terminal headroom cannot be proven, fail before side effects.

- [ ] **Step 3: Run root recovery GREEN and commit**

```bash
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh
./gradlew :test --tests '*Cs2aSupervisor*' --tests '*Cs2aGovernorRecoveryTest' \
  --tests '*Cs2aGuardianLifecycleTest' --tests '*Cs2aRunRootAndQuotaTest' \
  :integrationTest --tests '*Cs2aSupervisorLinuxRootIntegrationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aSupervisorAtomicHandoffTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aGovernorRecoveryTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aGuardianLifecycleTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRunRootAndQuotaTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aSupervisorLinuxRootIntegrationTest.kt
git commit -m "feat: harden CS2a supervisor recovery"
```

Expected: every admitted scenario ends with restored governors, empty controlled cgroup, released
true lock, and either a recoverable terminal path or explicit manual-safety requirement.

---

### Task 4: Publish immutable durable-session receipts and signed READY commits

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh`
- Modify: `docs/superpowers/benchmarks/operators/cs2a-remote-session.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptPublicationTest.kt`
- Add: `src/test/resources/cs2a/protocol/receipt-vectors.tsv`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptLinuxRootIntegrationTest.kt`

**Interfaces:**
- Closed receipt kinds: `prelaunch-failure`, `supervisor-finalized-no-run`, `run-finalized`, and
  `run-finalization-failure`, with the exact phase/field matrix from the design.
- Durable state publishes one root receipt directory plus signed READY. The pair binds token,
  identities, current post-receipt remap where allowed, transition head and the exact already-durable
  active-generation sequence/digest/previous-head/admission-lock identity, inventory byte
  length/count/object counts/total, terminal status/source, and signer/key identity. Receipt/READY
  never bind a later terminal-observed or clearance head; those records point forward to the signed
  pair after publication.
- READY is a no-clobber causal commit and the receipt is immutable before signing. Recovery may
  adopt or publish only the missing next object; it never rewrites a prior member. Claim-terminal
  and `claim.ready` publication remains exclusively in Plan 2's installer-owned no-genesis branch.
- The privileged terminal publisher validates and retains the inherited admission OFD until the
  terminal-observed generation's parent fsync. Receipt/READY durability alone never authorizes its
  close, and no controlled child or receipt member inherits that descriptor.
- Receipt and local terminal-matrix fixtures carry exact `cleanHostPrerequisite`,
  `jdkPrelaunchWalkState`, `jdkPostGroupWalkState`, immutable seed source/copy/post-group states,
  controlled-child/post-drop states, guardian state, and raw wait-status cross-link. Any detected
  clean-host or post-group failure may be an integrity-valid failure receipt but can never reach the
  performance classifier.

- [ ] **Step 1: Add RED terminal-matrix and publication-race tests**

Cover every valid/invalid field combination, truthful not-started/failed/verified child/guardian/
JDK/seed/env states, handoff failure codes, prior 0/3/70 propagation to effective finalizer 70,
inventory limits/overflow, source race/type/link errors, receipt rename/fsync, READY rename/fsync,
changed-boot post-receipt/commit remap, remote signer/key unavailability, and terminal-observed
completion. A still-usable remotely sealed key signs unchanged bytes; offline revocation is a local
Plan 4 acceptance/quarantine test and is not interpreted by root publication.

- [ ] **Step 2: Implement bounded publication and signing**

Use only root-owned hidden receipt state and the native copier's sealed result. Validate projected
files and directories before copy, reserve quotas, sign canonical envelopes through fixed FDs, and
publish terminal-observed only after the exact signed commit is durable. Child/copier text never
enters control fields.

- [ ] **Step 3: Verify and commit Plan 3**

```bash
./gradlew cs2aPrimitiveGate \
  :test --tests '*Cs2aReceiptPublicationTest' --tests '*Cs2aRemote*' \
  --tests '*Cs2aSupervisor*' --tests '*Cs2aControlledRunnerGateTest' \
  :integrationTest --tests '*Cs2aReceiptLinuxRootIntegrationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
for script in \
  docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh \
  docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  docs/superpowers/benchmarks/operators/cs2a-remote-session.sh; do
  /bin/bash -n "$script"
  shellcheck "$script"
done
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  docs/superpowers/benchmarks/operators/cs2a-remote-session.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptPublicationTest.kt \
  src/test/resources/cs2a/protocol/receipt-vectors.tsv \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptLinuxRootIntegrationTest.kt
git commit -m "feat: publish signed CS2a terminal receipts"
```

Expected: exactly one closed terminal shape is recoverably derivable for every outcome; signer/key
unavailability yields only unsigned safety quarantine, while usable keys yield the appropriate
signed pair. Only `run-finalized` with all required verified states can later reach performance
classification.
