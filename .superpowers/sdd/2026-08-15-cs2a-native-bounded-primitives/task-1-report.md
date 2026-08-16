# Task 1 Step 1 design-closure report

## Status

`NEEDS_CONTEXT`

The design-only closure cannot be written from the supplied authority without making new protocol
decisions. The dispatch explicitly forbids inventing such decisions. I stopped before editing any
tracked file, staging any path, or creating the requested closure commit.

## Starting state

- Worktree:
  `/Users/gopala.akshintala/code-clones/work/revoman-root/.worktrees/performance-cs2a-controlled-measurement`
- Required base HEAD: `ba94260b0b29d275dda1e872d1d1184721a9800d`
- Observed base HEAD: `ba94260b0b29d275dda1e872d1d1184721a9800d`
- Required frozen-design SHA-256:
  `74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333`
- Observed frozen-design SHA-256:
  `74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333`
- Starting tracked and untracked status: clean

## Files read

The requirements brief, normative design, and only the Step 1 dependent plans were read end to
end:

- `.superpowers/sdd/2026-08-15-cs2a-native-bounded-primitives/task-1-brief.md`
- `docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md`
- `docs/superpowers/plans/2026-08-15-cs2a-remote-session-plan-set.md`
- `docs/superpowers/plans/2026-08-15-cs2a-native-bounded-primitives.md`
- `docs/superpowers/plans/2026-08-15-cs2a-native-entry-and-admission.md`
- `docs/superpowers/plans/2026-08-15-cs2a-remote-orchestration-and-receipts.md`
- `docs/superpowers/plans/2026-08-15-cs2a-host-neutral-operator-and-collection.md`
- `docs/superpowers/plans/2026-08-15-cs2a-security-release-gates.md`
- `docs/superpowers/plans/2026-08-15-cs2a-controlled-measurement-and-evidence.md`

The task ledger was also read to confirm that production RED/source work remains blocked pending
explicit user and administrator approval.

## Exact files changed

Tracked files changed: **none**.

The only file created is this required, gitignored coordination report:

- `.superpowers/sdd/2026-08-15-cs2a-native-bounded-primitives/task-1-report.md`

No production source, test source, Gradle logic, vector, recipe, benchmark evidence, failed-attempt
archive, or dependent plan was changed.

## Commit and digest

- Closure commit: **none**
- Requested subject `docs: close CS2a protocol wire authorities`: **not used**
- Resulting new design SHA-256: **none**
- Current unchanged design SHA-256:
  `74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333`

The current digest must not be presented as the digest of a completed closure. No user or
administrator approval is claimed.

## Blocking authority gaps

The brief identifies what must be frozen, and fixes several mode names, status values, enums, and
TSV orders, but it does not supply all bytes, keys, bounds, paths, schemas, or matrices needed to
freeze an implementable interface. The following choices are materially security-relevant and
cannot be inferred from a dependent plan or selected by the implementer.

### 1. Signature domains, framing, and file split

Missing normative choices:

- the literal domain byte sequence for each of the three remote terminal schemas:
  `revoman-cs2a-claim-terminal/v1`, `revoman-cs2a-claim-terminal-ready/v1`, and
  `revoman-cs2a-ready/v1`;
- the literal domains for design approval, bootstrap approval, fixed-range review, Qodana result,
  VM/test-infrastructure attestation, root-gate result, release certification, clearance result,
  and component-compromise proof;
- whether a domain is its displayed ASCII schema string, a different literal, and whether it has a
  delimiter or terminator;
- whether `ENVELOPE_PATH` contains payload bytes only, `domain || payloadLength || payload`, or a
  complete on-wire record;
- whether the remote terminal file is one combined
  `domain || u64be(payloadLength) || payload || signature` record or two separately published
  files;
- if the remote record is combined, the exact extraction rule that produces `ENVELOPE_PATH` and
  `SIGNATURE_PATH` without accepting a trailing byte;
- whether the existing 64 KiB cap applies to payload bytes, framed envelope bytes, or the complete
  on-wire record, and the corresponding exact per-domain maximum payload length; and
- exact bounds for every signed release/review/approval record.

The eight-octet unsigned big-endian `payloadLength`, concatenation order, 64-byte Ed25519 signature,
and exit values `0|2|3|70` are fixed by the brief, but those facts do not resolve the items above.

Safe proposal requiring approval: make each domain the exact non-NUL ASCII schema literal; make
`ENVELOPE_PATH` exactly `domain || u64be(payloadLength) || payload`; make `SIGNATURE_PATH` exactly
64 bytes; and make each remote single-file record `ENVELOPE_PATH || SIGNATURE_PATH`. This matches
the current single terminal paths and prevents payload parsing before verification. The tradeoff is
that the complete-record versus payload cap must be chosen explicitly, and the generic verifier
must recognize a closed domain table without treating an attacker-selected domain as caller
authority.

### 2. Ed25519 private/public key bytes and canonicality

Missing normative choices:

- whether the private file is a 32-byte Ed25519 seed, a 64-byte expanded/private-plus-public form,
  or another raw form;
- whether the public file is the 32-byte compressed Edwards-Y value or a DER/SPKI encoding;
- exact rejection rules for noncanonical public encodings, small-order public points, noncanonical
  signature `R`, and signature scalar `S >= L`;
- whether those checks are performed before the JDK provider call, by the provider, or both;
- the exact byte string hashed for `receiptSigningKeyId`;
- the exact SSH binary string hashed for the OpenSSH-style fingerprint, including the two
  four-octet length prefixes and `ssh-ed25519` algorithm string; and
- whether the fingerprint base64 omits padding and which exact grammar/case rules apply.

Safe proposal requiring approval: use a 32-byte seed as the only private representation and the
canonical 32-byte compressed public point as the only public representation; define key ID as
lowercase hex SHA-256 of those 32 public bytes; and define the fingerprint as `SHA256:` plus
unpadded RFC 4648 base64 of SHA-256 over the standard SSH wire blob
`string("ssh-ed25519") || string(rawPublicKey)`. Explicitly reject noncanonical/small-order public
points and require canonical `R` and `S < L` before accepting provider success. This is the most
interoperable choice, but it is still a protocol decision that changes provisioned key files.

### 3. One canonical serialization profile

Every new local/release record is required to have canonical bytes, but the brief does not choose:

- UTF-8/BOM rules;
- fixed schema order versus lexicographic order;
- compact JSON versus allowed whitespace;
- trailing-LF presence;
- string escaping and Unicode normalization;
- whether decimal values are JSON numbers or canonical decimal strings; or
- a common maximum nesting/key/string/record size.

Without one common profile, digest-derived paths, signatures, no-clobber replay, and independent
producer/consumer implementations are ambiguous.

Safe proposal requiring approval: use UTF-8 without BOM, ASCII-only schema keys/enums, a prescribed
schema key order, compact JSON with no insignificant whitespace, canonical decimal strings for
wide integers, and exactly one trailing LF. A standards-based alternative is RFC 8785 JCS without a
trailing LF. JCS reduces bespoke encoder rules but introduces number/Unicode behavior that is not
needed by the mostly ASCII/string schemas.

### 4. Local setup-failure authority

The brief does not supply:

- the complete ordered key set and exact key names for
  `revoman-cs2a-local-setup-failure/v1`;
- the closed preparation failure-code enum;
- exact optionality for transport, runtime, verifier, policy, high-water, and implementation
  identities when validation of one of those inputs is the failure itself;
- the content-derived setup-attempt ID and canonical evidence-relative path;
- record/archive byte and object bounds;
- the exact hidden-stage path, scoped ref name, commit author/tree metadata, direct parent, and
  worktree materialization order; or
- the idempotent replay/adoption result and status contract.

The existing plans state only a conceptual schema and that `cs2a-operator.sh` is the existing
production implementation. That is insufficient for a second implementation or a byte oracle.

Safe proposal requiring approval: keep implementation in `cs2a-operator.sh`, use a content-derived
setup transaction ID and deterministic evidence-relative directory, build the tree through a
temporary index outside the worktree, create the scoped commit before any canonical materialization,
atomically update the one scoped ref, then materialize only the exact committed tree. The failure
enum and nullable fields still need an explicit authority table.

### 5. Validation-attempt, outcome, result, adoption, and repeat schemas

The brief fixes important statuses and two output orders, but leaves these byte authorities open:

- complete schemas, ordered keys, paths, modes, and caps for validation-attempt, parent-observed
  child wait status, trust-preflight proof, owner-absence proof, immutable outcome, prepared result,
  and adoption state;
- the exact representation of a POSIX wait status versus normalized child exit status and the rule
  separating `semantic-failed` from `interrupted`;
- owner identity/liveness fields, boot handling, PID/start-time reuse rejection, and the proof
  publisher;
- exact successful result keys and closed enum/nullability matrix for `archiveIntegrity`,
  `terminalEligibility`, `performanceDecision`, classifier artifacts, and
  `validatorExitStatus`;
- candidate paths/ref names and the exact result/commit/ref/worktree crash states accepted by
  `--adopt-attempt-validation`;
- the exact complete-success repeat line order. A dependent plan assumes
  `version, disposition, originalStatus, policySha256, highWaterSha256`, but the brief does not make
  that order authoritative;
- producer statuses for the mutating validator, adopter, and abandoned finalizer; and
- record and line byte bounds.

The fixed read-only outcomes are understood: absent is status `2` with empty stdout; exactly
adoptable is `75` with empty stdout; abandoned is status `77` with the specified five-field line;
terminal validation failure is status `6` with the specified seven-field line; current repeat
trust is status `0|4|5`; corrupt/ambiguous is status `70` with no accepted line. Those values alone
do not close the schemas above.

Safe proposal requiring approval: use distinct content-addressed attempt/outcome/result files under
a protected local transaction root, keep only the final validation JSON in the canonical attempt
tree, store both raw wait status and normalized exit status, and let the parent be the sole outcome
publisher. Use a temporary index plus one direct-child commit and adopt only byte-identical prepared
states. This preserves the stated retry prohibition, but exact field names, paths, and status maps
must be approved.

### 6. Pre-claim same-token retry and recovery-disposition chain

The brief requires an authoritative pre-claim retry procedure while also stating that
controlled-UID remote absence is untrusted. It does not identify the authority that proves the
no-claim state, and explicitly prohibits inventing a `pending-no-claim` schema/helper.

Also missing are:

- the root and exact filename grammar for `revoman-cs2a-recovery-disposition/v1`;
- its complete ordered keys and record cap;
- the positive sequence maximum described only as “shell-lossless”;
- whether `previousRecordSha256` is JSON null or the literal string `null` in the record;
- exact local namespace-observation bytes and authentication source;
- retry/adoption behavior at record-file fsync, rename, and parent-fsync boundaries; and
- the exact publisher status for same-boot replay versus a genuinely later boot.

Safe pre-claim proposal requiring approval: do not claim remote absence at all. Permit the human to
repeat only the exact original `launch run TOKEN` command for the same still-pending token. Native
`run` remains the authority: its permanent `O_EXCL` claim and admission/global-ledger checks either
admit the first root reach or reject an already consumed token before payload/measurement. The
operator must not mint a new token or publish evidence for the retry. This avoids a new absence
record, but the procedure and its local pending-state checks still need explicit approval.

For the sequence maximum, a conservative proposal is `2147483647`, which is lossless in Bash,
common JSON tooling, and integer comparisons. A larger `2^53-1` bound has more headroom but depends
on every shell/tool path preserving that value exactly.

### 7. Collector-result and protected collected stage

Missing normative choices:

- exact token-derived marker and protected-stage roots/path grammar;
- complete canonical collector schema and ordered keys;
- complete disposition enum and producer-status mapping;
- the exact five-field reader order currently assumed by Plan 6;
- whether effective terminal statuses `1..255` are returned directly by collection and how
  `safety-only|external-admin-required` map to statuses;
- the marker/stage byte, file, object, and lifetime bounds;
- the exact stage identity and archive-tree digest algorithm;
- all closed `absence:REASON` values and the circumstances that authenticate each; and
- the exact rules for identical terminal replay, preseed rejection, and file/parent-fsync adoption.

The brief fixes only that `active-uncertain` is the sole marker-free retryable disposition with
status `75`, and that a terminal result is consumed through
`--read-collector-result TOKEN COLLECT_STATUS`.

Safe proposal requiring approval: place stages and markers beneath the protected verifier/evidence
CAS rather than `build/`; derive names from token plus local-session digest; cap a collected stage
at the already approved receipt/local-stage maxima; make the reader output exactly
`version, disposition, intendedAttemptRel, archiveTreeSha256, incidentProvenance`; and give every
terminal disposition a fixed producer-status row. This makes restart independent of a worktree,
but changes the dependent plan's illustrative `build/` marker check.

### 8. Persistence-result transaction

Missing normative choices:

- exact token-derived record/stage/quarantine/ref paths;
- complete canonical `revoman-cs2a-persistence-result/v1` keys/order/cap;
- complete dispositions and producer/read status table;
- the exact five-field reader order assumed by Plan 6;
- the hidden commit-tree/ref/worktree transaction states and which single state yields reader status
  `75`;
- deterministic commit metadata, scoped ref, expected parent, and prepared commit identity;
- exact canonical-attempt materialization/adoption ordering;
- quarantine byte/object/lifetime bounds; and
- the full authenticated incident-provenance enum when no signed terminal exists.

Safe proposal requiring approval: construct a deterministic tree and commit from the protected
stage using a temporary index, update one token-derived scoped ref with compare-and-swap, then
materialize the canonical attempt from that exact commit. Publish the persistence result last;
on restart, adopt only the uniquely prepared byte-identical commit/ref/tree state. Never create an
untracked canonical directory. This satisfies the requested ordering but still needs exact paths,
metadata, bounds, and status rows.

### 9. Signed design/review/release/test-infrastructure authorities

The brief names the records but does not provide implementable schemas for:

- pre-code design-closure approval;
- post-build bootstrap approval;
- Standards, Spec, and security fixed-range reviews;
- Qodana fixed-range result;
- VM/test-infrastructure attestation;
- multi-boot root-gate result; and
- release certification.

For each, the complete ordered keys, canonical bytes, signature domain, role-specific trust anchor,
key/high-water schema, record cap, content-derived ID/path, sequence/previous-link rules, publisher
identity, no-clobber/adoption behavior, and verifier status/output contract are missing.

Only `verify-release-set` has a complete operand list and final six-field TSV order in the brief.
The exact operand lists and output orders for `verify-design-approval` and
`verify-fixed-range-review` appear only in dependent Plan 5 examples; they are not established by
the brief as normative authority. The design also does not close the complete release-set
inventory encoding or anchored set-identity derivation.

Safe proposal requiring approval: use independent role keys/high-water chains for design admin,
reviewers, release admin, test-infrastructure admin, and clearance admin; sign every record with the
common framing; place records in protected content-addressed namespaces; and make all verifier
success results bounded TSV records emitted only after complete authentication. Distinct role keys
reduce cross-role substitution, at the cost of more externally managed trust policies.

### 10. Clearance-result schema and nullability matrix

The brief fixes the clearance reason enum, trust-disposition enum, validation-failure enum,
component-compromise precedence, `@record` concept, and the 23-field verified-result TSV order. It
does not close:

- the complete signed clearance-result key set/order/types/caps;
- the exact record path and content-derived ID;
- clearance administrator key/trust-policy/high-water schemas and signature domain;
- a literal row-by-row matrix for all four report kinds, three modes, thirteen reasons, and every
  nullable request/generation/terminal/evidence/validation/failure/terminal/provenance/remediation
  field;
- the exact manual-safety versus replacement mode assigned to each of `safety-only`,
  `external-admin-required`, `current-revoked`, `current-drift`, `quarantined`, and `partial-claim`;
- exact request/generation nullability for replacement mode;
- exact terminal-head/sequence/previous-head rules for pre-ledger partial claims;
- all closed root publication-failure absence reasons;
- the full `revoman-cs2a-component-compromise/v1` key set, time format, scope encoding, path, cap,
  publisher, and replacement-authority proof; and
- the exact partial-claim inode/prefix/absence-proof and remediation schemas.

A prose rule that a category uses its “exact manual-safety or replacement profile” is not a closed
matrix. Plan 6 contains proposed shell cases, but using a dependent consumer to select missing
authority would invert the producer/consumer relationship.

Safe proposal requiring approval: add a literal matrix with one row per accepted
`reportKind, mode, clearanceReason` triple and explicit `required|-` for every field; reject every
unlisted row. Keep `component-compromise` replacement-only and dominant exactly as the brief says.
This is larger than prose but is machine-translatable and reviewable.

### 11. Bootstrap process-envelope executor

The brief does not supply:

- the executor record's exact schema/domain/path/key/cap;
- exact JDK and verifier-distribution inventory encodings;
- the executor executable/provenance identity format;
- fixed cwd path and metadata;
- exact FD numbers/types/modes (the plans mention a “three-FD envelope” without defining it);
- complete clean environment names/values;
- the closed argv grammar for every verifier mode; or
- executor result/status and output-forwarding behavior.

Safe proposal requiring approval: FDs 0, 1, and 2 only, with FD 0 canonical `/dev/null` and 1/2
bounded pipes; no FD `>=3`; read-only root-owned cwd; exact environment
`PATH=/usr/bin:/bin, LC_ALL=C, TZ=UTC`; and argv consisting only of the approved verifier home,
approved JDK home, fixed launcher, fixed mode, and that mode's fixed operands. This is simple and
auditable, but exact cwd, output caps, and each mode schema remain decisions.

### 12. VM/root-gate and syscall-barrier protocol

Missing normative choices:

- the complete VM/test-infrastructure attestation and root-gate schemas;
- exact QEMU/image/runner provenance inventory and trust anchor;
- VM/disk/scenario/boot/phase ID derivations and path roots;
- exact result enums, bounds, publisher status, chain/fork/replay rules;
- the complete named rename/fsync barrier list;
- syscall-entry versus successful-exit notification record bytes;
- probe/coordinator authentication frame, nonce size, FD map, timeout, and acknowledgement bytes;
- how the exact release PID/start-time/executable/FD-to-object map is encoded; and
- the durable proof that QEMU process termination, not guest exit, performed the power cut.

Safe proposal requiring approval: keep the probe test-only, bind it to a separately signed
infrastructure attestation, use a closed scenario/barrier table, and make the host kill proof bind
QMP/QEMU PID/start-time plus observed process termination before the next boot. Do not accept guest
status as a substitute. Exact records still need to be supplied.

### 13. Native entry/payload/copier/signer internal ABI

The current design fixes several child/guardian FD maps, but Step 1 requires every native ABI and
the following remain open:

- exact entry mode strings and argv for all external, credential-root, direct-root, and internal
  profiles in one closed table;
- FD numbers/types/ownership/modes for the native-to-Bash handoff, admission, lifecycle, claim,
  signer, and every copier operation;
- sealed handoff bytes and maximum sizes;
- signer message/key/output result framing and statuses;
- copier input manifest/result record bytes, per-mode FD maps, bounds, and statuses for
  `seal-selectors`, `seal-stage`, `seal-final-handoff`, and `verify-gradle-seed-copy`;
- selector/stage/final-handoff operation-specific record schemas; and
- EOF/short/extra-byte behavior for every channel not already fixed by the child/guardian protocol.

Safe proposal requiring approval: add one normative ABI table per mode listing `argc/argv`, every
open FD, expected type/access/mode/owner, exact input and output record schema/cap, success status,
and all closed failure statuses. Reuse no FD meaning across simultaneously live authorities. The
specific missing FD numbers cannot safely be selected piecemeal because they must compose with the
already frozen child/guardian maps.

### 14. Changed-boot recovery namespace publication

The brief fixes the direct-root mode name, three operands, lock order, selection kind
`recovery-current-only`, and high-level admission rule. It does not supply:

- the exact digest-derived attestation and selection candidate roots/filenames;
- complete attestation/selection/result schemas and caps;
- whether and how a result distinct from the selection is published;
- publisher exit/status/output contract;
- exact same-boot, already-published, cleared-token, partial-candidate, and competing-candidate
  result rows;
- parent/file metadata and adoption at every durability boundary; or
- the signed/local cross-link from recovery-disposition sequence/digest to the root selection.

Safe proposal requiring approval: publish only content-addressed candidate bytes under the existing
protected namespace parent, append one hash-linked `recovery-current-only` selection under
admission -> lifecycle -> token lock order, and make an identical complete selection validation-only.
Do not publish a mutable current pointer or append the missing global generation. The exact schemas,
paths, and statuses remain authority decisions.

## Closure self-review

### Placeholder and ambiguity scan

The frozen design still contains named-but-undefined interfaces for every grouped item above. A new
addendum written now would necessarily introduce unapproved literals, byte layouts, fields, paths,
bounds, or status rows. No amount of cross-link repair in the dependent plans can make those choices
authoritative.

### Internal consistency

Several dependent plans already call proposed interfaces “reapproved” and parse proposed TSV
orders, while the plan-set still says the old `74ebc845...` design was approved. Editing those
consumers before the missing producer definitions are approved would falsely present proposals as
authority. They were intentionally left unchanged.

### Scope check

The blocker is wholly inside Step 1. No production/test/Gradle/vector/recipe work is needed or
authorized to resolve it. The next action is an authority response that selects the missing values,
followed by one docs-only closure and dependent-plan reconciliation.

### Producer-consumer cross-link audit

Result: **failed by missing producer authority**, not by a discovered implementation defect.

- Remote verifier consumers lack exact signed bytes and key encodings.
- Operator readers lack exact collector, persistence, recovery, and validation producer schemas.
- Plan 5 certification/review consumers lack exact signed record schemas/domains and two verifier
  mode contracts.
- Plan 6 clearance consumer has a fixed output order but no complete signed input schema/nullability
  table.
- Native plans name modes but lack complete fixed-FD and record ABIs.
- VM/root-gate plans name attestations/results but lack their canonical bytes and signature chain.

## Verification commands and outputs

### Base and clean state

```text
$ git rev-parse HEAD
ba94260b0b29d275dda1e872d1d1184721a9800d

$ git status --short --untracked-files=all
<empty>

$ shasum -a 256 docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md
74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333  docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md
```

### Worktree isolation

```text
git_dir=/Users/gopala.akshintala/code-clones/work/revoman-root/.git/worktrees/performance-cs2a-controlled-measurement
git_common=/Users/gopala.akshintala/code-clones/work/revoman-root/.git
branch=codex/performance-cs2a-controlled-measurement
```

The differing git/common directories prove this is the supplied linked worktree.

### No tracked mutation or staged scope

```text
$ git diff --check
<empty; exit 0>

unstaged_changed_files=0
staged_changed_files=0
production_or_test_changes=0
changed_plan_files=0
changed_spec_files=0
```

`git check-ignore -v` confirms this report is ignored by `.superpowers/sdd/.gitignore`.

### Conditional Step 1 verifications not run

The changed-plan Bash-fence parser, changed-document relative-link checker, docs allowlist gate, and
clean staged-scope commit gate are conditional on a completed docs edit. There are zero changed
spec/plan files, so there are no changed Bash fences or links to validate and no closure to stage.
No commit was attempted.

The repository baseline recorded in the task ledger remains:

```text
./gradlew test :benchmark-driver:test --no-build-cache --no-configuration-cache --console=plain
PASS (841 tests, 0 failures, 7m34s)
```

That result was not rerun because this dispatch is docs-only and made no tracked change; it is not
used as evidence that the missing design interfaces are closed.

## Required context to resume

Provide an approved authority addendum that supplies, at minimum, the exact choices listed in
Sections 1-14. A compact acceptable form would be:

1. one common framing/key/canonical-serialization table;
2. one table per local transaction with path, ordered fields, types, nullability, bounds, status
   map, durability/adoption order, and sole reader output;
3. one table per signed approval/review/release/clearance/test record with domain, trust role, path,
   ordered fields, bounds, chain rules, verifier argv, output, and statuses;
4. a literal clearance nullability matrix;
5. complete native mode/FD/record ABI tables; and
6. the pre-claim retry and changed-boot namespace publication procedures, including every durable
   record byte and result row.

After those values are approved, Step 1 can amend the normative design, reconcile every dependent
plan, compute the new design digest, run the requested docs-only verification suite, and create the
exact `docs: close CS2a protocol wire authorities` commit. User and administrator approval of that
result must still occur afterward; Step 2 remains forbidden until then.
