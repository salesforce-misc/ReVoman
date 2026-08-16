# CS2a Native Entry and Admission Implementation Plan

> **Disposition (2026-08-15): Deferred with the high-assurance plan set.** Preserve this plan for
> history; do not execute any checkbox or use it as a prerequisite. Follow the active Task 8
> operator and measurement path instead.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the credential-safe native entry, fixed installer payload, and durable
claim/global-admission state that authorize exactly one host-neutral session token.

**Architecture:** One freestanding static binary owns all credential-bound and privilege-drop
boundaries. External `launch` creates the sealed pre-credential identity proof and execs the
attested privilege command; root modes authenticate that proof and hand off to one immutable Bash
payload. Internal child and guardian modes provide the only UID-transition and benchmark-lock
keeper. The payload uses append-only claim, ledger, lifecycle, policy, and bundle state; staging is
never authoritative.

**Tech Stack:** C17/Linux syscalls, static ELF, Bash privileged mode, OFD locks, memfd seals,
`openat2`, cgroup v2, seccomp, fsync/rename durability, Kotest, ASan/UBSan, and disposable root
namespaces.

## Global Constraints

- Depends on the release contracts from
  `2026-08-15-cs2a-native-bounded-primitives.md`.
- The only production additions are `cs2a-session-entry-v1.c`, its recipe, and
  `cs2a-session-installer-v1.sh`; do not add `runuser`, `setpriv`, a separate guardian, or another
  privileged helper.
- `launch` is the only unprivileged external mode. Controlled-UID `dzdo` authorizes only
  `auth-probe|run|recover`; `clear-*` is direct-root only; internal modes are supervisor-only.
- No mode accepts a caller path. All canonical paths, FD numbers, schemas, bounds, and process
  profiles are fixed by the approved design.
- The permanent claim burns the token. Neither process death, reboot, manual clearance, nor
  recovery can make it eligible for another `run`.

---

### Task 1: Freeze account, namespace, privilege-policy, and admission schemas

**Files:**
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aProtocolTestSupport.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aAdmissionStateContractTest.kt`
- Add: `src/test/resources/cs2a/protocol/admission-vectors.tsv`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`

**Interfaces:**
- Fixture schemas exactly model the design's controlled-account policy, normalized passwd/group
  records, namespace attestation/selection, versioned privilege policy/selection, probe
  lease-begin/probe/complete-or-abort records, claim header, global ledger generation, clearance
  request generation, and retirement/lifecycle generation.
- Test support publishes only hidden-candidate + no-clobber rename + file/directory fsync state and
  can inject a crash or fsync error at each barrier.
- IDs are losslessly parsed wide decimal values in `1..UID_MAX-1` and `1..GID_MAX-1`; zero,
  all-ones sentinel, overflow, privileged/extra groups, and resolver drift are rejected.
- The reapproved `recovery-current-only` namespace selection is a separate recovery-claim/token-bound kind.
  Its sole direct-root publisher follows admission/lifecycle/token lock order, may run only for the
  one already-active token after a boot change, or for the same token's one unique complete durable
  pre-ledger claim/header when no active generation or competing/partial claim exists. It does not
  create the missing active generation and cannot satisfy fresh-run, probe, runtime, or approval
  high-water.

- [ ] **Step 1: Add RED schema and transition fixtures**

Cover closed keys, derived names, canonical serialization, monotonic policy/retirement/clearance
generations, cumulative key revocation, exact lock order, active-token uniqueness, orphan claim
shapes, normal/manual clearance semantics, and power-loss adoption. Include the privilege-tool
same-process and direct-child relations as distinct attested values. Add active-token changed-boot
publication, wrong-token/same-boot/ordinary-updater rejection, recovery-only versus fresh selection,
and every candidate/rename/file-fsync/parent-fsync adoption boundary.

```bash
./gradlew :test --tests '*Cs2aAdmissionStateContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: RED because no publisher/parser under test exists.

- [ ] **Step 2: Implement only test-side canonical codecs and state oracle**

The oracle must reject every shape outside the approved state graph and produce byte fixtures for
the native tests. Do not add a production schema file outside the exact fourteen-source inventory.

- [ ] **Step 3: Commit the frozen protocol fixtures**

```bash
./gradlew :test --tests '*Cs2aAdmissionStateContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aProtocolTestSupport.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aAdmissionStateContractTest.kt \
  src/test/resources/cs2a/protocol/admission-vectors.tsv \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt
git commit -m "test: freeze CS2a admission protocol"
```

---

### Task 2: Implement external native launch and root-entry modes

**Files:**
- Add: `docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeEntryContractTest.kt`
- Add: `src/test/resources/cs2a/native/entry-mutations.tsv`
- Add: `src/test/resources/cs2a/native/entry-test-payload.sh`
- Modify: `build.gradle.kts`

**Interfaces:**
- External argv:
  `launch auth-probe NONCE`, `launch run TOKEN`, or `launch recover TOKEN`.
- Privileged argv:
  `auth-probe NONCE`, `run TOKEN`, `recover TOKEN`,
  `publish-recovery-namespace TOKEN ATTESTATION_SHA256 SELECTION_SHA256`,
  `clear-normal TOKEN REQUEST_SHA256`, or
  `clear-manual-safety TOKEN REQUEST_SHA256`.
- `launch` rejects inherited FD 3, validates live UID/GID/groups, TTY/session and namespace/rootfs,
  creates/seals `launch-account-proof/v1` at FD 3, discovers all privilege-command aliases from the
  fixed system path, validates equivalence, and `execve`s the selected command with the exact rule.
- Credential-root `auth-probe|run|recover` validates FD 3 and the attested
  same-process/direct-child relation. `auth-probe` alone validates the current highest
  policy/probe-lease state and exits without a claim or payload. Fresh `run`, while holding the
  native admission and shared lifecycle authorities in canonical order, selects the current eligible
  runtime/policy generation and authenticates the exact signer executable/recipe/provenance, signing
  key identity and private-path metadata, entry/payload/copier identities, account, namespace, and
  host policy **before** claim creation. It then creates and parent-fsyncs the permanent claim/header,
  obtains the canonical token OFD
  lock, and no-clobber appends/parent-fsyncs the first global `activeToken=TOKEN` generation before
  any payload handoff. `recover` acquires admission/lifecycle/token authority and either
  authenticates the existing active token plus immutable claim/header, or—only when no active
  generation exists—proves one unique complete durable claim/header, reconciles it by appending and
  parent-fsyncing that token's active generation, and then continues. Zero/multiple/partial
  candidates are manual fail-closed for recovery. Recovery uses only the claim/session-bound
  runtime/signer/key/policy identities, may not reinterpret fresh-run high-water, and still performs
  safety recovery when the bound signer/key later becomes unavailable. The direct-root
  `publish-recovery-namespace` profile forbids FD 3, consumes only the exact root-owned
  digest-derived candidates approved in Plan 1, and publishes/adopts a `recovery-current-only`
  attestation/selection solely for the matching active token—or the same token's unique complete
  durable pre-ledger claim/header with no active generation or competing/partial candidate—under
  admission/lifecycle/token lock order. It never appends the missing active generation; same-boot
  use, any other absent/different token, an ordinary update, or any fresh-run interpretation fails
  closed. Direct-root `clear-*` forbids FD 3, never enters the
  controlled-UID privilege rule, and validates the existing claim/ledger plus exact root-owned
  request. The reapproved partial/pre-ledger `clear-manual-safety` branch is the sole exception to an
  existing active generation: under admission-exclusive authority it authenticates the one exact
  partial claim inode and durable byte prefix, proves no complete header, no controller/child/guardian,
  no governor or benchmark-lock side effect, and no accepted global generation, binds direct
  remediation/reprovision proof, and appends the design-defined `activeToken=null` clearance shape.
  It never repairs, signs, or promotes the partial claim. Internal child/guardian profiles remain disjoint. Every profile validates the canonical
  entry/payload/copier identity and its own process/FD envelope before any Bash or child byte.

- [ ] **Step 1: Add RED argv, environment, process, and credential mutants**

Include missing/extra/reordered argv, slash-function/fake namespace prerequisite checks,
uid/gid/group overflow, stale login group, fork-vs-exec privilege transition, missing/replaced FD 3,
TTY discontinuity, alias mismatch, hostile loader/locale/Bash variables, inherited signals/mask/alt
stack, unsafe seccomp/personality/dumpability/cwd, cross-profile FDs, root direct `launch`, and
controlled-UID direct-privilege attempts to `publish-recovery-namespace`, `clear-*`, or internal
mode calls; each must fail at policy/profile validation before any candidate or state mutation. Add kill/adoption/interleaving mutants for native
claim `O_EXCL`, header write/file+parent fsync, token-lock acquisition, active-generation candidate/
rename/parent-fsync, payload handoff before any barrier, second token, and payload-created claim.
Exercise every changed-boot namespace candidate/open/parse/rename/file-fsync/parent-fsync boundary,
same-boot and wrong-token calls, updater races, selection replay/fork, and attempted use by fresh run.
Cover the pre-ledger case with zero, one complete, one partial, and multiple claim candidates; only
the unique complete claim may receive a recovery-only selection, and only later native `recover`
may append its active generation.
Exercise recovery with zero, one complete, one partial, and multiple claim candidates after every
claim-to-ledger kill boundary; only the unique complete claim may publish the missing active head.
For each partial-claim durability boundary, prove normal recovery and normal clearance refuse it,
then prove only the exact manual-safety request may close admission after owner/side-effect absence;
kill around request authentication, clearance candidate rename, file fsync, and parent fsync and
require adoption of the same generation rather than a second request.

- [ ] **Step 2: Add debug/sanitizer tasks and implement GREEN**

Add `cs2aNativeEntryDebug`, `cs2aNativeEntryAsan`, and `cs2aNativeEntryUbsan`. They embed only the
exact test payload for syscall/profile development. Do not add, name, or publish a v1 release recipe
or release binary yet: the production installer payload does not exist until Task 4.

- [ ] **Step 3: Verify external modes and commit**

```bash
./gradlew cs2aNativeEntryAsan cs2aNativeEntryUbsan \
  :test --tests '*Cs2aNativeEntryContractTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add build.gradle.kts docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeEntryContractTest.kt \
  src/test/resources/cs2a/native/entry-mutations.tsv \
  src/test/resources/cs2a/native/entry-test-payload.sh
git commit -m "feat: add credential-safe CS2a native entry"
```

Expected: every external-mode mutant fails before payload/claim creation and valid launch vectors
reach the exact mode-specific endpoint.

---

### Task 3: Implement controlled-child and lock-guardian native modes

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeChildContractTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeGuardianContractTest.kt`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeModesLinuxIntegrationTest.kt`

**Interfaces:**
- `controlled-child TOKEN` accepts only FDs 0-3 and 8-15 with the exact identities in the design.
  It verifies release framing and sealed launch record, performs the ordered irreversible
  UID/GID/group/capability/securebit/no-new-privileges transition, emits the exact FD-12 grammar,
  and execs only the opened Bash/runner endpoint.
- `lock-guardian TOKEN` accepts only FDs 0-8 in its guardian profile, owns the true lock FD 3,
  installs the syscall allowlist, never allocates/forks/execs, and implements
  prepare/register/activate/contain/release-ACK/exit.
- Controlled code receives only a distinct non-lock-owning read-only FD 9.

- [ ] **Step 1: Add RED descriptor, drop-order, framing, and guardian mutants**

Exercise every drop syscall failure, residual saved ID/capability/group, FD leak/crossover,
lock-owning FD 9, `flock -u`/truncate/dup, `FAIL_PRE+EOF`, `PROOF+EOF`,
`PROOF+FAIL_EXEC+EOF`, kill after proof, unexpected exec/cgroup member, guardian helper attempt,
pre-registration owner death, controller death, release before ACK, and exact post-ACK adoption.

- [ ] **Step 2: Implement the two internal state machines**

Use the pre-fork sealable memfd protocol and one-way release/exec-status/post-exec gates exactly as
designed. Guardian registration and release acknowledgement records must be durable before the
corresponding lock lifecycle edge.

- [ ] **Step 3: Run Linux integration and commit**

```bash
./gradlew cs2aNativeEntryAsan cs2aNativeEntryUbsan \
  :test --tests '*Cs2aNativeChildContractTest' \
  --tests '*Cs2aNativeGuardianContractTest' \
  :integrationTest --tests '*Cs2aNativeModesLinuxIntegrationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeChildContractTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeGuardianContractTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aNativeModesLinuxIntegrationTest.kt
git commit -m "feat: add CS2a child and guardian native modes"
```

Expected: no controlled process can release the true benchmark lock or execute before every durable
gate.

---

### Task 4: Implement installer, claim admission, bundle, and clearance state

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c`
- Add: `docs/superpowers/benchmarks/operators/cs2a-session-installer-v1.sh`
- Add: `docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.build.json`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aInstallerBundleTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteSessionStateTest.kt`
- Add: `src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aAdmissionLinuxRootIntegrationTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`
- Modify: `build.gradle.kts`

**Interfaces:**
- Payload argv is exactly `run|recover TOKEN` or
  `clear-normal|clear-manual-safety TOKEN REQUEST_SHA256`; it requires the entry's sealed handoff,
  inherited admission/lifecycle/claim FDs, root identity, `/bin/bash -p`, cwd `/`, umask `077`, and
  the four-name clean environment.
- `run` requires and byte-validates the already durable native-created claim/header, token lock, and
  active-ledger generation. It cannot create, replace, or repair them. It seals selectors through
  the copier, revalidates the selected approval and retirements, publishes/reuses the canonical
  five-asset bundle, creates/preopens the exact root-owned `0600` active controller transcript inode,
  binds that inode/owner epoch in genesis, and publishes `bundle-installed` only after the complete
  genesis/transcript file and parent fsync barrier.
- Claim-only `recover` never opens staging/bundle/workload; durable-state `recover` uses only sealed
  state. Clearance consumes an exact append-only request generation and closes admission only under
  normal or manual-safety rules. For the pre-ledger partial-claim shape, the native entry performs
  the admission-exclusive inode/prefix/owner/no-side-effect proof before handing the exact verified
  request and anchored claim FD to the payload; the payload may append/adopt only the corresponding
  manual-safety generation and may not create an active token, genesis, terminal artifact, or evidence
  authority.
- Claim-only recovery is wholly installer/header-bound. With the sealed signer/key usable it
  publishes or adopts the canonical signed claim-terminal envelope, then `claim.ready`, then the
  exact terminal-observed global generation. With that signer/key unavailable it performs safety
  recovery and publishes only bounded unsigned quarantine; it must not create/adopt either signed
  member or terminal-observed. Neither branch invokes `cs2a-remote-session.sh`, the supervisor,
  bundle, copier, runner, or workload. Plan 1 freezes claim-terminal, claim-ready, and READY as three
  distinct canonical signature domains.

- [ ] **Step 1: Add RED shell-context and durability tests**

Cover direct script invocation, poisoned environment/FDs, staged expected hash, approval
ambiguity, bundle collision/replay, retired copier sentinel, admission/run/recover races, every
claim/header/ledger fsync kill point, malformed orphan, no-genesis terminal publication,
lifecycle-lock inversion/removal race, normal/manual clearance, stale request generations, and
attempted token reuse. Include envelope-before-claim-ready and claim-ready-before-terminal-observed
reboots, same/changed-boot remap fields, signer-unavailable quarantine, and a sentinel proving the
orchestrator never executes on the claim-only branch. The quarantine sentinel also proves neither
signed pair member nor terminal-observed can be published/adopted. Include the direct-root
`recovery-current-only` publisher's changed-boot result in every remap/adoption test and prove it
cannot be used after clearance or by fresh run. Require the Bash payload to reject missing,
mutable, mismatched, or not-yet-durable inherited claim/header/ledger authority and prove it never
opens a claim path for creation. Kill around transcript create/open/file fsync, genesis candidate,
rename/parent fsync, and payload-to-orchestrator handoff; no post-genesis transcript creation or
activation is permitted.
Add partial-claim manual-clearance vectors for every write/file-fsync/parent-fsync boundary, changed
inode/prefix, live owner, any governor/lock/process side effect, missing remediation proof, competing
candidate, prepared-clearance adoption, and a second request after the first accepted generation.

- [ ] **Step 2: Implement the privileged payload and exact state graph**

Every privileged open is anchored beneath a prevalidated root directory. External command output
is never parsed as authority without status and fixed framing. Use the Task 1 canonical fixtures as
the byte oracle and preserve the inherited lifecycle FD through terminal publication. Only after
the payload bytes are final, update the native C source with the exact embedded payload identity,
add the v1 build recipe, embed the exact payload/copier/provenance
digests, and enable `cs2aNativeEntryRelease`. Prove no `PT_INTERP`, `DT_NEEDED`, constructor,
allocator, NSS, locale, or dynamic-loader path before scrub. No earlier native-entry build is
provisionable or release-eligible.

- [ ] **Step 3: Verify and commit Plan 2**

```bash
for script in docs/superpowers/benchmarks/operators/cs2a-session-installer-v1.sh; do
  /bin/bash -n "$script"
  shellcheck "$script"
done
./gradlew cs2aPrimitiveGate cs2aNativeEntryAsan cs2aNativeEntryUbsan \
  cs2aNativeEntryRelease \
  :test --tests '*Cs2aInstallerBundleTest' --tests '*Cs2aRemoteSessionStateTest' \
  :integrationTest --tests '*Cs2aAdmissionLinuxRootIntegrationTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add build.gradle.kts \
  docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.c \
  docs/superpowers/benchmarks/operators/cs2a-session-installer-v1.sh \
  docs/superpowers/benchmarks/operators/cs2a-session-entry-v1.build.json \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aInstallerBundleTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRemoteSessionStateTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/benchmark/Cs2aAdmissionLinuxRootIntegrationTest.kt
git commit -m "feat: add CS2a admission and bundle state"
```

Expected: one valid token reaches durable genesis; every failure either remains safely recoverable
or requires explicit manual-safety closure, never a fresh run.
