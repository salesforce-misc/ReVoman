# CS2a Host-Neutral Operator and Collection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard-coded, staged-privilege, unsigned remote flow with explicit host-neutral
preparation, privilege-free signed collection, immutable persistence, and trustworthy validation.

**Architecture:** One local Bash operator owns a closed CLI and hermetic OpenSSH transport. Prepare
authenticates a detached checkout, all fourteen critical sources, host/runtime prerequisites, and a
reproducible local verifier CAS before staging exactly five data assets. Collect treats every remote
byte and metadata claim as hostile until the signed causal envelope verifies locally, then copies a
bounded receipt into hidden local state and publishes one immutable archive.

**Tech Stack:** Bash, OpenSSH/SFTP, jq, SHA-256, Git detached checkouts, Kotlin/JDK 21 verifier,
append-only local CAS/high-water state, Gradle benchmark driver, Kotest, and fake transport
processes.

## Global Constraints

- Fixture-driven work may begin after Plan 1; final integration depends on Plan 3's exact signed
  fixtures.
- The only production file changed in this plan is `cs2a-operator.sh` (and the existing jq
  validator if a strict additive receipt cross-check is required). Do not add a sourced shell
  helper or production schema outside the exact fourteen-source inventory.
- Preparation stages exactly five named assets. It writes only a token/instruction marker; that
  marker is data and never calls `dzdo` or any root entry.
- Every SSH-family call uses the identical selected executables, explicit user/host/port/identity,
  pinned known-hosts file, config-free closed option set, clean environment, bounded hidden
  stdout/stderr, deadlines, and no connection reuse.
- Collection/recovery never installs, mutates, or invokes a privileged helper. The only human
  credential action is the printed absolute native-entry command performed outside the script.
- Archive integrity and performance classification are separate. An authentic failure is retained
  even when it is ineligible for a performance decision.

---

### Task 1: Replace the CLI and freeze local trust/high-water state

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-operator.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorCliTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptTrustPolicyTest.kt`
- Split/modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt`
- Add: `src/test/resources/cs2a/operator/trust-policy-vectors.tsv`

**Interfaces:**
- Implement the exact prepare/collect/recover/archive-only invocations in the approved design,
  including mandatory `USER@HOST`, canonical port, identity, host and receipt fingerprints,
  policy/high-water paths and digests, verifier CAS/JDK, runtime-config digest, and token.
- Keep `--persist-only` and `--validate-attempt` local-only and retain their positional leading
  forms. Freeze the extensions as `--persist-only STATUS`, required `--session-token TOKEN`, then
  the policy/high-water paths/digests and verifier CAS/JDK flags; freeze the read-only
  `--read-persistence-result TOKEN PERSIST_STATUS` companion. Freeze
  `--validate-attempt ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA` followed by those same trust flags,
  plus the disjoint inspect-only
  `--read-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA` and adoption-only
  `--adopt-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA` forms with those trust
  flags. Inspection never creates/adopts/repairs/rewrites; adoption may only finish an exact
  prepared transaction and never reruns semantic validation.
  Persistence obtains the intended attempt path and protected collected stage only from the
  authenticated token-bound collector marker; the shell obtains an actual attempt path/evidence SHA
  only from the authenticated persistence-result reader. Neither mode accepts a remote host.
  `--archive-only` is a fixed pre-protocol allowlist and always emits nonauthoritative legacy
  quarantine.
- Trust policy is a closed, increasing, cumulative-revocation chain. Its accepted no-clobber
  high-water registry lives under the explicit protected verifier CAS; no implicit initialization,
  rollback, fork, removal, reactivation, or active/revoked overlap is accepted.

- [ ] **Step 1: Add RED parser and forbidden-literal tests**

Cover every missing/extra/reordered/duplicate option; omitted user/local fallback; invalid host,
port, path, token, SHA, and fingerprint grammars; future archive-only SHA; implicit CAS/JDK;
hard-coded prior host/account/home/JDK/UID; and every trust-policy/high-water fork/regression or
cumulative-revocation violation.

```bash
./gradlew :test --tests '*Cs2aOperatorCliTest' \
  --tests '*Cs2aReceiptTrustPolicyTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: RED against the old hard-coded operator modes.

- [ ] **Step 2: Implement the closed parser and local registry**

Parse once into immutable shell variables, reject leading options and shell/control characters,
canonicalize every local path no-follow, and never concatenate remote operands into a shell
command. Preserve the old local no-clobber archive/persistence primitives only after their inputs
are adapted to signed evidence.

- [ ] **Step 3: Run GREEN and commit**

```bash
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-operator.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-operator.sh
./gradlew :test --tests '*Cs2aOperatorCliTest' \
  --tests '*Cs2aReceiptTrustPolicyTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorCliTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aReceiptTrustPolicyTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt \
  src/test/resources/cs2a/operator/trust-policy-vectors.tsv
git commit -m "refactor: make CS2a operator host neutral"
```

---

### Task 2: Implement hermetic preparation and exact five-asset staging

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-operator.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aPreparationTransportTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aVerifierCasTest.kt`
- Add: `src/test/resources/cs2a/operator/transport-vectors.tsv`
- Modify: `src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt`

**Interfaces:**
- Host-key acquisition accepts at most 16 supported records, 8 KiB each, 128 KiB total, with
  30-second object and 60-second total deadlines. Other pre-auth observations use their schema cap
  plus 1 MiB/256-record diagnostic limits and fixed deadlines.
- OpenSSH uses no ambient config, `BatchMode=yes`, `IdentitiesOnly=yes`, strict host-key checking,
  one session known-hosts file, disabled proxy/jump/local/forwarding/update/multiplex behavior, and
  clean environment without HOME/agent/askpass.
- Preparation proves a clean detached implementation checkout, byte-compares the exact fourteen
  critical sources, deterministically derives the exact five-asset manifest, runs read-only remote
  preflight, builds/authenticates the verifier distribution twice, publishes it no-clobber to the
  explicit CAS, creates one unique token/stage, and prints only the absolute native launch command.
- Before any launch instruction is published, a conclusive preparation failure other than the
  design's exact `provisioning-required` disposition publishes one
  no-clobber local setup archive with schema `revoman-cs2a-local-setup-failure/v1`,
  `remote-evidence-present=false`, the attempted implementation/runtime/transport identities, a
  closed failure code, and proof that no human-launch instruction or remote claim authority exists.
  Preparation atomically persists that archive through the scoped no-clobber `commit-tree`
  transaction before returning the failure; this local-only profile does not require or pretend to
  have the verifier/policy inputs whose validation may itself have failed. Once the launch
  instruction exists, this fallback is forever forbidden for that token; unsigned remote absence
  leaves the same token pending instead.
  `provisioning-required` prints only the expected approval path/bytes identity, creates no token,
  stage, archive, marker, or evidence commit, and remains an external provisioning prerequisite
  rather than a measurement attempt.

- [ ] **Step 1: Add RED transport and preparation mutants**

Cover hostile user/system ssh config, proxy/local-command sentinels, alternate identities/trust
stores, host/user/port rewrite, host-key duplicate/mismatch/rotation, changed identity metadata,
infinite/slow/oversize output, ANSI/OSC/fake-password text, dirty checkout, source substitution,
five-asset omission/addition/order/alias, runtime/approval/preflight mismatch, verifier
nonreproducibility, any staged file that invokes privilege, conclusive failure before instruction,
failure after instruction, forged unsigned absence, and attempted local-only fallback after launch.

- [ ] **Step 2: Implement bounded transport and preparation**

Capture raw remote stdout/stderr only to hidden user-owned quota-limited files and render locally
generated escaped summaries. Publish the verifier CAS only after two complete inventories equal the
recipe's reviewed digest. The authenticated local session record binds transport, identity,
receipt key/policy, verifier/JDK, implementation, manifest, runtime config, token, and remote stage.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :test --tests '*Cs2aPreparationTransportTest' \
  --tests '*Cs2aVerifierCasTest' --tests '*Cs2aStructuralInvariantTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-operator.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-operator.sh
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aPreparationTransportTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aVerifierCasTest.kt \
  src/test/kotlin/com/salesforce/revoman/compat/Cs2aStructuralInvariantTest.kt \
  src/test/resources/cs2a/operator/transport-vectors.tsv
git commit -m "feat: prepare authenticated CS2a remote sessions"
```

Expected: preparation performs no privilege action and yields one exact instruction for the human.

---

### Task 3: Implement signed privilege-free collection and archive publication

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-operator.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aCollectionArchiveTest.kt`
- Add: `src/test/resources/cs2a/operator/collection-vectors.tsv`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aManifestValidatorTest.kt`

**Interfaces:**
- Collection first downloads bounded envelope/key/signature/commit bytes into a hidden stage,
  verifies the signature through the exact CAS/JDK process envelope, and only then parses fields or
  trusts remote metadata.
- It next downloads the signed inventory under exact byte/time caps, verifies signed
  length/digest before parse, checks file/directory/object counts, path bytes/depth, per-file and
  overflow-safe total sizes and local quota, then fetches each exact-size member.
- Collection publication is a protected hidden collected stage plus a no-clobber, file/parent-fsynced
  collector record; it does not place an untracked attempt in the canonical evidence tree. The
  staged archive includes
  the signed pair, receipt, verifier distribution/JDK/provenance identities, policy/high-water,
  transport identities, local session record, and collection transcript.
- Only a terminal collector disposition publishes the reapproved bounded local collector-result
  record at the token-derived fixed path. `active-uncertain` returns the reapproved retry status `75` and
  publishes no marker, so same-token collection after native recovery remains possible. A repeated
  terminal collection may only byte-compare/adopt the identical durable record; it cannot replace it.
  The record binds token, local
  session digest, implementation SHA, signed terminal identity when present, effective status,
  disposition, protected stage identity, intended canonical attempt path, and archive-tree digest
  only for a signed staged archive. A safety-only result binds the actual authenticated quarantine
  digest or exact root publication-failure/absence proof when available; otherwise it says only
  `admin-inspection-required` and cannot itself authorize a manual-clearance absence claim. The
  caller supplies no result path. Consumers require
  zero/closed status agreement, no-follow open the exact file, canonicalize the intended path
  beneath the evidence root, and reject
  a preseeded-before-publication, conflicting, mismatched, symlinked, or path-escaping marker. The only consumer interface
  is the reapproved local `--read-collector-result SESSION_TOKEN COLLECT_STATUS` mode, which emits one
  bounded canonical line after validation and never accepts an attempt path argument.

- [ ] **Step 1: Add RED forgery, replay, resource, and race tests**

Cover forged shell/stat/SFTP/rsync output, valid-looking unsigned receipt, wrong key/domain/token,
claim-vs-READY substitution, missing/mismatched commit, cross-token replay, signature checked after
parse, infinite/short/extra streams, huge count/path/depth/size/overflow/sparse/quota, directory
prefix explosion, source race, symlink/hardlink/device, READY/receipt publication races, CAS/JDK
substitution, local archive collision, forged/preseeded collector marker, marker/status mismatch,
wrong token/session/implementation, marker-selected path escape, and
`active-uncertain -> recover -> signed-staged` retry without a marker collision.

- [ ] **Step 2: Implement verify-first bounded collection**

Invoke the verifier only through the recorded absolute Java and explicit classpath with the exact
clean env/cwd/FD/provider envelope. Never use remote `stat` as provenance. A revoked key may produce
incident bytes but can never publish an authoritative archive.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :test --tests '*Cs2aCollectionArchiveTest' \
  --tests '*Cs2aManifestValidatorTest' \
  :benchmark-driver:test --tests '*ReceiptSignatureVerifierTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-operator.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-operator.sh
git diff --check
git add docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aCollectionArchiveTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aManifestValidatorTest.kt \
  src/test/resources/cs2a/operator/collection-vectors.tsv
git commit -m "feat: collect signed CS2a receipts"
```

Expected: no unverified remote field influences path selection, allocation, archive publication, or
status reporting.

---

### Task 4: Integrate recovery, persistence, validation, classification, and clearance requests

**Files:**
- Modify: `docs/superpowers/benchmarks/operators/cs2a-operator.sh`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRecoveryCoordinatorTest.kt`
- Add: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aArchiveClassificationTest.kt`
- Add: `src/test/resources/cs2a/operator/attempt-validation-vectors.tsv`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt`
- Modify: `src/test/kotlin/com/salesforce/revoman/benchmark/BenchmarkWorkflowTest.kt`
- Modify: `.github/workflows/benchmark.yml`

**Interfaces:**
- Recover uses the original token and authenticated local session/transport prerequisites. Active
  key runs normal root recovery; locally revoked/unavailable evidence paths are `safety-only` and
  cannot accept a signature or performance result.
- `--recover-remote-session` performs no root action. Unchanged-boot recovery returns `0` and prints
  only the design-approved, locally generated
  `/opt/revoman-benchmark/cs2a-session-installer-v1 launch recover TOKEN`. Authenticated changed boot
  returns `76`, prints nothing, and appends the token/sequence/current-boot/content-derived
  `revoman-cs2a-recovery-disposition/v1`; the read-only
  `--read-recovery-disposition TOKEN 76` verifies the contiguous previous-digest chain and emits only
  the frozen sequence/record identity, old/new boot, and current-namespace observation line. The caller must never `eval` output; a human byte-compares and types a launch
  line in a new approved TTY only after any required administrator publication. Any same-token
  pre-claim `launch run` retry follows only the exact authority/procedure added and reapproved in
  Plan 1's design closure; the local operator never infers it from `stat`, SSH output, or absence.
- `--persist-only` revalidates the complete collector record/stage and current policy/high-water,
  then persists the signed artifact's effective terminal status; preserved original status is
  context only. It constructs the scoped commit from the hidden stage, updates/adopts the exact ref,
  and materializes the canonical attempt only as one transaction. Before any durable evidence
  commit, authenticated current revocation/drift instead publishes the closed nonauthoritative
  persistence result and preserves only the bounded quarantine digest outside the evidence tree.
  Its token-derived `revoman-cs2a-persistence-result/v1` binds the collector digest, token,
  implementation, effective status, trust digests, disposition, and exactly either
  `persisted-signed + attempt path + evidence SHA` or a revoked/drift/quarantine reason with those
  identities absent. Every nonpersisted terminal result also carries exactly one authenticated
  signed-terminal digest when available, otherwise `quarantine-sha256:DIGEST` or closed
  `absence:REASON` provenance for the administrator's manual-safety request; a bare placeholder is
  invalid. Process status is never authority. Startup
  and the read-only result mode
  inspect every prepared commit/ref/result boundary; exact state is adopted with identical inputs,
  while a fork/collision is safety-only. No branch leaves an untracked canonical attempt.
  `--validate-attempt` first revalidates verifier/policy/archive integrity,
  then applies the exact terminal matrix, and only eligible `run-finalized` evidence reaches the
  existing benchmark classifier.
- Successful preparation creates no evidence commit. A conclusive local setup failure before the
  launch instruction uses Task 2's atomic local-only archive commit; after the
  instruction, collection/recovery may never reinterpret unsigned absence as local setup evidence.
- `--validate-attempt ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA ...` resolves `EVIDENCE_SHA` as the
  first immutable commit containing the attempt, never the newest commit touching it. It publishes
  exactly one no-clobber
  `ATTEMPT/local-validation/revoman-cs2a-attempt-validation-v1.json`, then uses the existing scoped
  `commit-tree`/direct-parent mechanism to create one validation commit without rewriting the
  evidence commit. The closed JSON binds the attempt relative path, token, implementation SHA,
  evidence SHA, signed terminal and approved runtime/provenance digests, verifier/JDK identity,
  current policy and
  high-water digests, `archiveIntegrity`, `terminalEligibility`, `performanceDecision`, classifier
  artifact digests, validator exit status, and validation-commit parent. The three result fields
  have separate closed enums; an archive-integrity-valid FAIL/INCONCLUSIVE is retained rather than
  converted to a tool failure. A repeat invocation is validation-only and byte-compares the existing
  object instead of creating another commit.
  Result publication follows the same hidden-result/scoped-commit/adoption rule: an accepted repeat
  line is emitted only after validating the complete closed JSON, its immutable parent-observed
  semantic-child wait-status evidence and `validatorExitStatus` cross-link, and
  exactly one direct-child validation commit. A crash cannot leave a canonical uncommitted result;
  the inspector returns `2` with no line only for a completely absent transaction and `75` only for
  one exact adoptable transaction, which the disjoint adopter may finish. Partial, multi-commit,
  wrong-parent, or malformed state emits no accepted line and exits `70`. The outer shell status is
  never needed after restart. Before semantic work, the parent publishes an exact hidden no-clobber
  attempt record binding boot and owner/child identity. It then performs current receipt-trust
  preflight; revoked/drift appends the immutable `preflight-current-*`, child-status-`-` outcome and
  starts no semantic child. Failure or interruption without a result
appends its immutable parent-observed outcome and makes the inspector return `6` with the exact
`revoman-cs2a-validation-failed/v1` proof line plus a read-only current receipt-trust disposition and
policy/high-water proof. Re-reading may change only that dynamic trust tail; it never changes the
attempt/outcome or reruns semantic validation. Death after attempt-begin but before result/outcome
  instead returns `77` with the authenticated abandoned-attempt/owner-absence line. Only the
  disjoint `--finalize-abandoned-attempt-validation` mode may revalidate liveness and append the
  `owner-abandoned`, child-status-`-` outcome; it never runs semantic validation, after which the
  inspector returns `6`. That evidence commit can never enter semantic validation again.
- For a completed validation transaction, `--read-attempt-validation` remains read-only and emits
  the reapproved exact
  `revoman-cs2a-repeat-validation/v1` line. `unchanged` exits `0` and binds the original result/status
  plus current policy/high-water digests; authenticated `current-revoked` exits `4` and
  `current-drift` exits `5`, each binding the exact current policy/high-water proof. Corrupt
  signature/commit/tree/input emits no accepted line and exits `70`. It never rewrites the validation
  object, and callers may not infer the branch from status without parsing and authenticating the
  matching exact line. `--validate-attempt` may byte-compare an existing result for idempotence but
  is never used as an inspector after a failed first invocation.
- Recovery/collection never exports a shell variable. Its only local handoff is the exact collector
  result schema/path frozen by Plan 1's reapproved addendum; the runbook derives `ATTEMPT` from that
  status-checked, no-follow record and never accepts a caller-preseeded `CS2A_ATTEMPT_DIR`.
- The local operator never emits or publishes a clearance proposal/request and never invokes a
  clear mode. A separately authenticated administrator independently reads the immutable archive and
  validation record, materializes the exact design-defined `revoman-cs2a-global-clearance/v1`
  request at its fixed root-owned digest-derived path, and directly invokes
  `clear-normal|clear-manual-safety TOKEN REQUEST_SHA256`. Stale or malformed append-only requests
  remain inert and do not wedge the token.
- The old self-hosted direct-execution workflow is disabled as authoritative evidence or rewritten
  to call only the new protocol; it must never run the old `dzdo`/rsync path.

- [ ] **Step 1: Add RED recovery and terminal-matrix tests**

Cover post-launch unsigned absence, new token while pending, claim-terminal collection, offline
revocation, missing identity/host key, safety-only recovery, effective finalizer status, authentic
FAIL/INCONCLUSIVE preservation, invalid partial evidence, A/A cutoff, archive-before-classify,
revocation/drift between collection and persistence, process death before/after commit creation/ref
update/worktree materialization/persistence-result rename and parent fsync, exact adoption versus
forked persistence state, absence of an untracked canonical attempt on every failure,
first-evidence-versus-newest-validation commit selection, validation result no-clobber/idempotence,
repeat-validation unchanged/current-revoked/current-drift/corrupt output and status,
inspect-only validation with missing/partial/uncommitted input and proof that it creates no bytes,
adoption-only completion at each result/commit/ref/worktree barrier without semantic re-execution,
creator death/failure after attempt-begin, status-`77` abandoned finalization, status-`6`
owner-abandoned proof, status-`6` unchanged/revoked/drift trust tails with exact digests, trust change
between failure and report without any semantic retry, preflight revoked/drift before the first
semantic child with durable no-retry outcomes, every owner/child liveness and reboot boundary,
and no retry after restart,
unchanged-boot status-`0` instruction versus changed-boot status-`76` empty stdout, recovery-
disposition replay/fork/wrong-token/old-new-boot/namespace mismatch and post-publication reread,
contiguous A-to-B-to-C records with interruption before claim-ready and terminal-observed,
normal/manual administrator separation, stale request retry, legacy quarantine, and old-workflow
rejection. Parameterize the exact validation JSON cases from
`attempt-validation-vectors.tsv` so every closed enum and forbidden combination executes.

- [ ] **Step 2: Implement recovery and local evidence flow**

Reuse the existing no-clobber `commit-tree` persistence only after signed archive validation and
current-trust recheck. Publish/read the exact persistence transaction result described above; a
nonzero or interrupted process must be reconciled from durable state, never guessed from status.
Implement the abandoned-validation finalizer as a disjoint transaction that reauthenticates the
attempt, recorded boot and owner/child absence, appends only the immutable `owner-abandoned`
outcome, and never enters the semantic validator; restart then observes the ordinary authenticated
status-`6` failure-proof line.
Return the signed effective terminal status. Keep archive integrity, semantic validity, and
performance decision as three distinct fields and exit paths. After the durable trust preflight
passes, the semantic child always leaves one machine-readable result or immutable failure outcome
for a well-formed persisted attempt even when its process status is nonzero. Preflight revocation/
drift leaves only its immutable no-semantic outcome; malformed input creates neither authority.
Repeat validation authenticates the entire
closed object and its sole direct-child commit before emitting its line. The operator has no
clearance-request mode.

- [ ] **Step 3: Verify and commit Plan 4**

```bash
./gradlew :test --tests '*Cs2aOperator*' --tests '*Cs2aRecoveryCoordinatorTest' \
  --tests '*Cs2aArchiveClassificationTest' --tests '*BenchmarkWorkflowTest' \
  :benchmark-driver:test \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
/bin/bash -n docs/superpowers/benchmarks/operators/cs2a-operator.sh
shellcheck docs/superpowers/benchmarks/operators/cs2a-operator.sh
git diff --check
git add .github/workflows/benchmark.yml \
  docs/superpowers/benchmarks/operators/cs2a-operator.sh \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aRecoveryCoordinatorTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aArchiveClassificationTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/Cs2aOperatorScriptTest.kt \
  src/test/kotlin/com/salesforce/revoman/benchmark/BenchmarkWorkflowTest.kt \
  src/test/resources/cs2a/operator/attempt-validation-vectors.tsv
git commit -m "feat: validate and persist signed CS2a attempts"
```

Expected: the old direct workflow cannot produce authoritative evidence, and every authentic
terminal outcome is preserved before any optional classification or clearance request.
