### Task 1: Freeze signature framing and verifier behavior

**Files:**
- Modify: `docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-native-entry-and-admission.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-remote-orchestration-and-receipts.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-host-neutral-operator-and-collection.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-security-release-gates.md`
- Modify: `docs/superpowers/plans/2026-08-15-cs2a-controlled-measurement-and-evidence.md`
- Add: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ReceiptSignatureVerifier.kt`
- Add: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ReceiptSignatureVerifierTest.kt`
- Add: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/Cs2aReleaseSetVerifierTest.kt`
- Add: `benchmark-driver/src/test/resources/cs2a/signatures/README.md`
- Add: `benchmark-driver/src/test/resources/cs2a/signatures/signature-vectors.tsv`
- Modify: `benchmark-driver/build.gradle.kts`

**Interfaces:**
- Main class:
  `com.salesforce.revoman.benchmark.driver.integrity.ReceiptSignatureVerifier`.
- Fixed invocation:
  `ReceiptSignatureVerifier ENVELOPE_PATH SIGNATURE_PATH PUBLIC_KEY_PATH`; the exact meaning and
  encoding of those three files is fixed by the required framing addendum below.
- Proposed input framing is fixed domain bytes, an unsigned 64-bit big-endian payload length,
  exact payload bytes, then a 64-byte detached Ed25519 signature. The approved design fixes the
  ordering but does not state the length width/endianness, so this proposal is not implementable
  until Step 1 records and reapproves that exact design addendum.
- Exit `0` means canonical signature verified; exit `2` means invalid invocation/input; exit `3`
  means cryptographic verification failed; any internal/provider failure exits `70`.
- The same approved Kotlin source also exposes the distinct fixed main class
  `com.salesforce.revoman.benchmark.driver.integrity.Cs2aReleaseSetVerifier`; no additional
  production source is added. It has only the reapproved closed modes
  `verify-design-approval`, `verify-fixed-range-review`, `verify-release-set`, and
  `verify-clearance-result`; each mode has an
  exact bounded argv schema and emits one exact bounded canonical result record on stdout only after
  every input is authenticated. `verify-release-set` receives
  `RELEASE_SET CERTIFICATION CERTIFICATION_SHA256 EXPECTED_IMPLEMENTATION_SHA DESIGN_APPROVAL DESIGN_APPROVAL_SHA256 BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256`.
  It no-follow verifies the reapproved design approval, release certification, signed fixed-range
  reviews, Qodana/VM/root-gate records, complete release-set inventory/types/modes/digests, and exact
  expected SHA before returning `0`; invalid input/authentication returns `2|3`, internal/provider
  failure `70`. The fixed result includes the authenticated release-manifest SHA-256, inventory
  digest, certification digest, implementation SHA, and anchored release-set identity; callers
  consume the proposed tab-separated order
  `revoman-cs2a-verified-release/v1, implementation, manifest, inventory, certification, setIdentity`
  only after Step 1 reapproves it, and never reopen certification bytes with `jq` to rediscover an
  authoritative value. Its standalone distribution/JDK/process-executor/argv/environment/inventory
  digests are independently pinned by a distinct post-build bootstrap approval record and it never
  runs through candidate Gradle during certification consumption.

- [ ] **Step 1: Close and reapprove every underspecified wire/local-evidence definition**

Amend the normative design to freeze, for the three remote terminal schemas and every approved
release/review signature schema, exact domain byte strings, signed-byte
concatenation, on-wire record bytes, the split between `ENVELOPE_PATH` and `SIGNATURE_PATH`, and an
eight-unsigned-big-endian-octet `payloadLength` bounded before allocation. Freeze the private key
as one canonical raw encoding, the public key as one canonical raw encoding, all byte bounds,
canonicality checks, public key ID derivation, and the exact OpenSSH-style fingerprint derivation
bytes.

The same design-only addendum must also close the currently named-but-undefined local setup-failure
archive and attempt-validation result: exact schemas/keys/enums/bounds/canonical serialization,
fixed paths, no-clobber/fsync/commit-tree ordering, status semantics, idempotent replay, and which
existing production file implements each. Freeze the read-only repeat-validation output/status
contract that distinguishes unchanged current trust from authenticated current revocation/drift and
from corrupt input without rewriting the immutable validation record. Its sole consumer is a
disjoint inspect-only `--read-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA ...` mode
that can never create/adopt/repair a result; the mutating validator is not re-entered merely to
inspect a failed invocation. Freeze an immutable parent-observed semantic-child wait-status record
inside the validation transaction and require the JSON `validatorExitStatus` and repeat result to
cross-link it. The outer runbook shell's ephemeral process status is diagnostic only and is never a
restart authority. Also freeze an adoption-only `--adopt-attempt-validation` mode that may complete
only an exact prepared result/commit/ref/worktree transaction without rerunning semantic validation;
the inspector returns `2` with no line only for a completely absent transaction and `75` only for
that one adoptable result/commit shape. Before semantic work, the parent publishes a
token/evidence/boot/owner-bound no-clobber validation-attempt record; terminal failure or
interruption without a result appends an immutable attempt outcome. The parent performs current
receipt-trust preflight only after that record is durable and before starting the semantic child;
authenticated revocation/drift appends immutable `preflight-current-revoked|preflight-current-drift`
with child status `-` and never executes semantic validation. If the owner dies after that
record's parent fsync but before any result/outcome, the inspector returns `77` with the exact
tab-separated `revoman-cs2a-validation-abandoned/v1, owner-abandoned, token, attemptSha256,
ownerAbsenceProofSha256` fields and no trailing field. The disjoint
`--finalize-abandoned-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA
ATTEMPT_SHA256 OWNER_ABSENCE_PROOF_SHA256 ...` mode revalidates that sole shape and liveness, appends
only an immutable `owner-abandoned` outcome, and never executes semantic validation. Inspection then
returns `6` with one exact `revoman-cs2a-validation-failed/v1` line: `semantic-failed|interrupted`
binds the real parent-observed child status, while `owner-abandoned` binds child status `-` plus the
recovery liveness proof and each `preflight-current-*` outcome binds child status `-` plus its exact
trust preflight proof. Every read-only status-`6` line then appends the independently rechecked
`unchanged|current-revoked|current-drift` report-time trust disposition and its exact policy/high-water
digests; this dynamic trust tail mutates no attempt/result byte and does not retry semantic validation.
The exact tab-separated order is `version, failureDisposition, childStatus, failureProofSha256,
currentTrustDisposition, currentPolicySha256, currentHighWaterSha256`, with no trailing field.
An immutable preflight-drift outcome permits a later dynamic tail of drift or revoked; a preflight-
revoked outcome permits only revoked. Trust can never regress or rewrite the immutable outcome.
Semantic validation is never retried for that evidence commit. Preserve
`provisioning-required` as a no-token,
no-archive prerequisite rather than setup evidence. It must freeze the sole output of
`--recover-remote-session`, an authoritative pre-claim retry procedure consistent with the rule
that controlled-UID remote absence is untrusted, and the changed-boot pre-publication branch. The
proposed branch returns status `76` with empty stdout and atomically publishes one token-derived
append-only `revoman-cs2a-recovery-disposition/v1` at a
token/sequence/current-boot/record-digest-derived path. Each record binds the previous record digest
or null; the positive sequence has an exact shell-lossless upper bound, so A-to-B recovery followed
by a crash and B-to-C recovery appends rather than collides. The
disjoint read-only `--read-recovery-disposition TOKEN 76` authenticates the complete contiguous
chain plus current local session/transport observation and emits exactly `version,
changed-boot-publication-required, token, sequence, previousRecordSha256, dispositionSha256,
sealedBootSha256, currentBootSha256, currentNamespaceObservationSha256` as one tab-separated line.
Any other status, line, gap, replay, or fork is a hard stop. It must freeze the authenticated collector-result
marker path/schema/status and the operator's single read-only, status-checked, exact-output mode that
are the only local source of the canonical attempt path, including terminal-marker idempotence and
the proposed sole marker-free retryable `active-uncertain` exit status `75`. It must freeze the
protected collected-stage identity and a separate `revoman-cs2a-persistence-result/v1`
transaction: collection may authenticate and durably stage signed bytes, but it does not publish an
untracked canonical evidence tree or claim persistence authority. The exact
`--persist-only STATUS --session-token TOKEN ...` invocation revalidates current trust, consumes
only that token's collector record/stage, and either atomically creates and adopts one scoped
evidence commit or publishes a nonauthoritative revoked/drift/quarantine result with no
attempt/evidence identity. Freeze its token-derived record path, closed dispositions, canonical
output fields—including exactly one authenticated `quarantine-sha256:DIGEST` or closed
`absence:REASON` incident provenance for every nonpersisted terminal result that lacks a signed
terminal, and the exact signed-terminal digest whenever one exists—exit/status agreement,
hidden-stage/quarantine bounds, commit/ref/worktree ordering,
and sole `--read-persistence-result TOKEN PERSIST_STATUS` consumer. A crash at any
commit/ref/result durability boundary must replay with the same inputs to adopt the exact prepared
transaction; it may never create a second commit, infer success from process status, or leave a
canonical untracked attempt. The collector record binds the intended canonical relative path and
stage identity, while only a verified `persisted-signed` result authorizes that path and evidence
SHA. It must also freeze the release certification,
Qodana/fixed-range review results, and multi-boot VM/root-gate result consumed as provisioning authority: exact
keys/enums/bounds/canonical serialization, content-derived IDs/paths, publisher identity, no-clobber
durability, fork/replay rules, and verifier exit/status contract. Fixed-range review records require
distinct approved reviewer trust anchors, exact signature domains, signed base/head/final-design/
artifact identities, and a concrete verifier; caller-authored PASS JSON is never sufficient.
Release certification requires its independent administrator approval digest/signature. Freeze a
separately administrator-signed `revoman-cs2a-clearance-result/v1` handoff and trust chain: exact
schema/domain/key/bounds/path/no-clobber rules, token, `normal|manual-safety|replacement` authority,
the closed clearance-time reason, clearance-time receipt-trust disposition and its authenticated
policy/high-water digests, and a component-compromise proof digest or null,
accepted request and parent-fsynced global generation digests when present, terminal-observed head,
implementation SHA, evidence commit or null, validation commit/result digest or null,
validation-failure proof and immutable failure disposition or null, signed-terminal digest or null,
incident provenance/remediation
identity, clearance sequence/previous head, and the proved `activeToken=null` state. Freeze a closed
nullability matrix for every report kind and clearance mode. The partial/pre-ledger claim branch
additionally binds the exact claim inode identity, durable-prefix length/digest, absence of a complete
header/owner/side effect/global generation, and the independent remediation authority.
The exact clearance-time reason enum contains `none`, `manual-safety`, `integrity-failure`,
`preclearance-current-revoked`, `preclearance-current-drift`,
`validation-failure-manual-safety`, `safety-only`, `external-admin-required`, `current-revoked`,
`current-drift`, `quarantined`, `partial-claim`, and `component-compromise`. Its separate
trust disposition is `unchanged|current-revoked|current-drift|not-applicable`: the two current-trust
reasons must match their disposition except that an earlier drift may advance monotonically to
revoked; preclearance revoked remains revoked, preclearance drift permits drift or later revocation,
and manual/integrity/validation-failure reasons permit any non-`not-applicable` trust disposition,
every non-`not-applicable` value carries two canonical 64-hex policy/high-water digests, and
`not-applicable` carries exactly `-,-`; safety-only, external-admin-required, quarantined,
partial-claim, and component-compromise require that value even when a component incident retains
historical signed-evidence identities. The approved nullability matrix maps `none` only to normal
clearance, manual/integrity/preclearance/validation-failure reasons only to manual-safety or
replacement, and nonpersisted current-trust plus safety/quarantine/partial/component reasons to their exact
manual-safety or replacement profile, except `component-compromise`, which is replacement-only and
may never invoke or authenticate the suspect clearance component. Component compromise takes
unconditional clearance-reason precedence when it coexists with any safety, trust, quarantine,
partial-claim, integrity, or validation failure; the result still
binds the evidence/validation-failure identities and uses the corresponding signed no-authority
report kind, but its reason remains `component-compromise`. A report-time trust change is not written back into this
already-signed clearance-time axis.
When a validation-failure proof is present, the signed result also carries exactly one immutable
`semantic-failed|interrupted|owner-abandoned|preflight-current-revoked|preflight-current-drift`
disposition; proof and disposition are both `-` when absent. Except for the dominant
`component-compromise` reason, semantic/interrupted/owner dispositions require clearance reason
`validation-failure-manual-safety`, while each preflight disposition requires the matching
`preclearance-current-*` reason. Relabeling either direction is invalid.
Freeze one independently authenticated `revoman-cs2a-component-compromise/v1` observation whose
bounded canonical bytes bind the token, suspect component identities/provenance, observation source,
time/boot/host scope, and replacement authority. Suspect entry/payload/clearance bytes never publish
or verify it. Its digest is mandatory exactly when clearance reason is `component-compromise` and is
`-` otherwise. When no terminal/quarantine/partial-claim diagnostic exists, exact incident provenance
is `component-compromise-sha256:DIGEST` with the same digest; remediation remains a separate field.
`verify-clearance-result` receives the record/digest, expected token, implementation SHA, report
kind, expected clearance-time reason/trust disposition/policy digest/high-water digest, expected
component-compromise proof digest, evidence SHA
or `-`, validation SHA or `-`, validation-result digest or `-`, validation-failure
proof digest and expected failure disposition or `-`, signed-terminal digest or `-`, expected
incident provenance or `-`, remediation
digest or `-`, administrator trust policy/high-water and their independently pinned digests, plus
the design/bootstrap approval pairs. Only after authenticating every cross-link does it emit one
closed canonical line containing all of those signed identities plus mode, request/generation,
terminal head, sequence, previous head, and active-token value. Step 1 must approve the exact
tab-separated order consumed by Plan 6:
`version, token, implementation, mode, clearanceReason, clearanceTrustDisposition,
clearancePolicySha256, clearanceHighWaterSha256, componentCompromiseProofSha256, requestSha256,
generationSha256, terminalHeadSha256,
evidenceSha, validationSha, validationResultSha256, validationFailureProofSha256,
validationFailureDisposition, signedTerminalSha256, remediationSha256, incidentProvenance, sequence,
previousHeadSha256,
activeToken`, with no trailing field. A caller-authored path, digest, mode, or environment variable
is never clearance authority. For a field whose first trusted source is
the signed clearance result—including the clearance-time axis/component-proof fields and an evidence-bearing
manual-safety branch with no separately pinned remediation record—the exact `@record` operand
replaces only that field's expectation; the
verifier returns the authenticated signed value and enforces the closed mode/report-kind null
matrix. `@record` is forbidden when an earlier authenticated value exists. For the signed
no-authority report kind, it is permitted for a nullable provenance/remediation expectation only
because the authenticated clearance-time reason determines whether the closed matrix requires a
value or null; the verifier still rejects every mismatched combination. It is never itself a value,
and remediation cannot substitute for a required terminal/
quarantine/partial-claim diagnostic or exact root publication-failure reason.
Separately,
freeze a post-build bootstrap approval schema for the final verifier distribution, JDK, and a
root-owned process-envelope executor that supplies a read-only cwd, closed FD set, clean environment,
and pinned executable/JDK identities. Its closed argv names only the approved verifier distribution,
JDK, verifier entry, mode, and mode operands; it is not a generic command executor. The pre-code design-closure approval must not contain or be
mutated to add those not-yet-built inventories. Do not add a new production schema/
helper or let the operator infer `pending-no-claim` unless those exact bytes and authority are
approved. Freeze the independently authenticated test-infrastructure attestation that pins the
external QEMU binary, VM image, runner, and their provenance/digests; caller-supplied paths or hashes
alone are never authority. Freeze the test-only syscall-barrier probe/fault protocol that observes
and stops the exact release process at each named rename/fsync entry/exit without modifying its
bytes, authenticates the process/executable/scenario/nonce to the host coordinator, and makes real
QEMU termination—not guest exit—the power cut. Also freeze every native internal ABI omitted by the
current design: entry/payload/copier/signer mode strings, fixed FD numbers/maps, sealed handoff and
result record bytes/bounds/statuses, including exact selector/stage/final-handoff copier operations.
Finally, reconcile changed-boot recovery with lifecycle publication:
freeze the narrow direct-root
`publish-recovery-namespace TOKEN ATTESTATION_SHA256 SELECTION_SHA256` authority, its fixed
digest-derived root-only candidate paths, admission/lifecycle/token lock order, and no-clobber
durability/adoption. Its new selection kind is `recovery-current-only`, must name the one active
token and new boot, or—only when no active generation exists—the one unique complete durable
claim/header and its token after rejecting every partial/competing candidate. It can authorize only
that token's native recovery and never appends the active generation itself; it cannot become a
fresh-run high-water or alter sealed old identities. Every ordinary exclusive updater remains
blocked by an active token, and fresh run remains impossible until clearance followed by the normal
probe/runtime/approval publication sequence.

Obtain explicit user approval, rerun the design security reviews, update the plan-set design digest,
and repair every dependent plan before writing production code. If another complete framing or
local-evidence/retry protocol is approved, update this task and its vectors in the same plan-only
commit.

Commit the reviewed design-only closure before any RED production test or source edit:

```bash
git diff --check
git add docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md \
  docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md \
  docs/superpowers/plans/2026-08-15-cs2a-native-bounded-primitives.md \
  docs/superpowers/plans/2026-08-15-cs2a-native-entry-and-admission.md \
  docs/superpowers/plans/2026-08-15-cs2a-remote-orchestration-and-receipts.md \
  docs/superpowers/plans/2026-08-15-cs2a-host-neutral-operator-and-collection.md \
  docs/superpowers/plans/2026-08-15-cs2a-security-release-gates.md \
  docs/superpowers/plans/2026-08-15-cs2a-controlled-measurement-and-evidence.md
git commit -m "docs: close CS2a protocol wire authorities"
CS2A_DESIGN_CLOSURE_COMMIT=$(git rev-parse HEAD)
CS2A_FINAL_DESIGN_SHA256=$(shasum -a 256 \
  docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md | awk '{print $1}')
test -z "$(git status --porcelain --untracked-files=all)"
```

Have the user explicitly approve that exact commit and design SHA-256 and have the separately
authenticated administrator publish the reapproved **pre-code design-closure** approval
record/digest. Record them as `CS2A_DESIGN_APPROVAL_RECORD` and
`CS2A_DESIGN_APPROVAL_RECORD_SHA256`. It binds the closure commit/design/interfaces, not future
artifact bytes. Step 2 is forbidden until the administrator authenticates and pins that exact record
through the pre-existing out-of-band approval procedure; the not-yet-written verifier is not used to
authorize its own creation. The implemented `verify-design-approval` mode later machine-revalidates
the already-approved bytes. A later review cannot retroactively approve code that began earlier. The separate bootstrap approval is created
only from Plan 5's final independently reproduced verifier/JDK/executor inventories.

- [ ] **Step 2: Add RED vectors and process-envelope tests**

Create vectors for one valid claim-terminal envelope, one valid `claim.ready`, one valid READY,
one valid release certification and one valid fixed-range review record, all cross-domain
substitutions, wrong domain, wrong key, cross-token replay, truncated/extended
length, noncanonical key/signature, unknown
trailing byte, oversize envelope, and correct-looking output with a nonzero helper status. Tests
must also reject ambient Java option variables, agents/provider overrides, extra FDs, writable cwd,
wrong Java/provider identity, and parsing before verification.
Add recovery-disposition vectors for unchanged-boot status `0` plus the sole launch line,
changed-boot status `76` plus empty stdout and one exact read-only result, wrong token/boot/namespace,
marker gap/fork/replay, extra output, a second `76` after the same recovery-only selection is durable,
active-token versus unique-complete-preledger-claim authority with zero/partial/multiple rejection,
and A-to-B-to-C reboots interrupted before each claim-ready/terminal-observed boundary.
Add validation-attempt vectors for death/reboot at every attempt-begin/child/result/outcome barrier,
status-`77` wrong/live/reused owner and proof, finalizer replay/fork, status-`6`
`owner-abandoned` with child status `-`, unchanged/revoked/drift dynamic trust tails and digest
mismatches, preflight revoked/drift with child status `-` and no semantic child, and proof that neither
finalizer nor adopter executes semantic validation.
Add release-set vectors for symlink/swap/type/mode/path/inventory/review/Qodana/root-result/design-
approval substitution, unsigned caller PASS records, wrong expected SHA, and ambient Gradle-free
direct-Java execution. `Cs2aReleaseSetVerifierTest` must cover every closed mode, wrong-mode argv,
bootstrap/design approval substitution, output-before-verification, extra output, and byte-for-byte
canonical verified-result framing. Add clearance-result vectors for wrong token/mode/request/
generation/terminal head/sequence/previous head/implementation/evidence/validation/result/
failure-proof/terminal/incident/remediation identity, non-null active token, unsigned or revoked
administrator key, component-compromise through manual-safety, replacement without remediation
authority, component compromise combined with integrity/validation/owner-abandoned failure, partial
claim without the exact
inode/prefix/no-side-effect proof, illegal `@record`, and normal-versus-manual cross-mode
substitution. Add every clearance-reason/trust-disposition/digest matrix row, drift-to-revoked
report evolution, restart without shell state, reason relabeling, wrong digest/nullability, and proof
that report-time trust never mutates the signed clearance-time axis. Add every failure-disposition to
clearance-reason mapping, proof/disposition partial nulls, semantic-as-preflight and preflight-as-
semantic relabeling, and the sole component override. Add component proof absent,
wrong-token/scope/authority, old-component-published, relabeled under every other reason, missing
replacement, and component-provenance fallback with and without a terminal diagnostic.

Run:

```bash
./gradlew :benchmark-driver:test \
  --tests '*ReceiptSignatureVerifierTest' \
  --tests '*Cs2aReleaseSetVerifierTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Expected: compilation fails because the verifier does not exist.

- [ ] **Step 3: Implement the smallest verifier and fixed distribution entry**

Implement strict length-first streaming reads with the design's numeric caps, checked arithmetic,
raw Ed25519 verification through the pinned JDK 21 provider, and a parser-free success path. Expose
exactly the two fixed main classes and their two generated distribution launchers; do not route
through `BenchmarkCli` or accept a caller-selected class/provider/algorithm. The release-set main's
closed mode selects a statically defined parser; it does not accept a class, provider, schema, or
algorithm name.

- [ ] **Step 4: Run GREEN and commit**

```bash
./gradlew :benchmark-driver:test :benchmark-driver:installDist \
  --tests '*ReceiptSignatureVerifierTest' \
  --tests '*Cs2aReleaseSetVerifierTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
git diff --check
git add benchmark-driver/build.gradle.kts \
  benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ReceiptSignatureVerifier.kt \
  benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ReceiptSignatureVerifierTest.kt \
  benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/Cs2aReleaseSetVerifierTest.kt \
  benchmark-driver/src/test/resources/cs2a/signatures/README.md \
  benchmark-driver/src/test/resources/cs2a/signatures/signature-vectors.tsv
git commit -m "feat: add CS2a receipt signature verifier"
```

Expected: all vectors pass and `installDist` contains exactly the receipt-signature and release-set
verification entry points with no new general benchmark command.

---

