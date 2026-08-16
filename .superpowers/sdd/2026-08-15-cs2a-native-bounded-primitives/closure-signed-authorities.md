# CS2a signed authorities, release, clearance, and test-infrastructure closure

Status: normative design closure. This file closes the authority package required by the frozen
design and Plans 1, 2, 5, and 6. It adds no production source. The existing
`ReceiptSignatureVerifier.kt` remains the sole production implementation of
`Cs2aReleaseSetVerifier`; the bootstrap executor and syscall probe are independently approved
external/test artifacts.

## 1. Chosen architecture

The words **MUST**, **MUST NOT**, **SHALL**, and **SHALL NOT** are normative. All digest comparison
is constant-time after bounded decoding. A path never authenticates bytes.

| Variant | Shape | Security and implementation consequence | Decision |
|---|---|---|---|
| A — common envelope, role-specific payloads | One detached Ed25519 frame, one canonical JSON codec, one protected CAS protocol, a distinct offline root/policy/high-water chain per role, and closed payload schemas per authority | Small cryptographic seam; cross-role replay is rejected by both domain and role; four verifier modes remain sufficient; common negative tests have high leverage | **Chosen** |
| B — bespoke gate formats | A different signature container, serializer, trust store, and verifier entry point for each gate | More parsers and bootstrap paths; policy rollback and durability rules can diverge; would require additional production entry points | Rejected |
| C — one universal signing role | Variant A storage with one root and one high-water for every gate | Compact, but a Qodana or test-infrastructure compromise could mint design, release, or clearance authority | Rejected |

The chosen module boundary is: common framing/serialization/CAS code supplies mechanism; every
payload table below supplies policy. No gate may infer policy from a filename, environment variable,
Gradle result, process status, chat response, or caller-authored `PASS`.

## 2. Common canonical form, trust, and durability

| Name | Exact rule |
|---|---|
| `h256` | Exactly 64 lowercase hexadecimal characters; SHA-256 over the referenced exact bytes. |
| `git40` | Exactly 40 lowercase hexadecimal characters and must resolve to the expected Git commit object. |
| `token` | Exactly 32 lowercase hexadecimal characters. |
| `keyId` | `h256 = SHA-256(raw 32-byte Ed25519 public key)`. |
| `uint` | JSON integer and TSV decimal `0` or `[1-9][0-9]*`, range `0..9007199254740991`. |
| `puint` | `uint` restricted to `1..9007199254740991`. |
| JSON bytes | One UTF-8 JSON object, no BOM, no insignificant whitespace, no trailing newline. Keys occur exactly in each table's stated order. Values in this package are ASCII. Unknown, duplicate, omitted, or reordered keys are invalid. Integers use the `uint` form; booleans are `true`/`false`; null is literal `null`. |
| Arrays | Compact JSON arrays in the order stated by the schema. Set-valued arrays are strictly bytewise-ascending, unique, and bounded by the table. |
| Detached signature | The common approved Ed25519 framing signs the exact JSON bytes under the table's exact domain. The payload and signature are separate regular files. Signature key role must equal the schema role. |
| Null in JSON / TSV | JSON uses `null`; canonical verifier TSV uses one ASCII hyphen `-`. Empty string is never null. |
| Record bound | Payload `1..65536` bytes; detached frame and signature use the smaller common-frame bound. Evidence bodies are referenced by `h256`, never embedded. |
| TSV | One ASCII line, fields separated by one TAB, terminated by one LF. Fields contain neither TAB nor CR/LF. No extra field, prefix, suffix, or stdout byte is allowed. |

Every authority has its own trust namespace. A root public key is pinned independently of the
repository, release set, candidate process, and record it authenticates.

| Role literal | May sign | Separate root/policy/high-water required |
|---|---|---|
| `design-approver` | design approval | yes |
| `review-standards` | Standards fixed-range review | yes |
| `review-spec` | Spec fixed-range review | yes |
| `review-security` | security fixed-range review | yes |
| `qodana-publisher` | Qodana range result | yes |
| `bootstrap-approver` | bootstrap approval | yes |
| `vm-infrastructure-approver` | VM/test-infrastructure attestation | yes |
| `root-gate-publisher` | root-gate result | yes |
| `release-certifier` | release certification | yes |
| `clearance-administrator` | clearance result and partial-claim proof | yes |
| `component-incident-authority` | component-compromise proof | yes |

Each role uses these two common signed payloads. Their domain strings are respectively
`revoman-cs2a-role-trust-policy/v1` and `revoman-cs2a-role-trust-high-water/v1`, further separated
by the signed `role` value.

| Schema | Ordered keys and exact constraints |
|---|---|
| `revoman-cs2a-role-trust-policy/v1` | `schema` (literal), `role` (one row above), `generation` (`puint`), `previousPolicySha256` (`null` iff generation 1, else `h256`), `activeKeyIds` (1..16 sorted unique `keyId`), `revokedKeyIds` (0..64 sorted unique `keyId`), `issuedAtEpochSeconds` (`uint`). Revocation is cumulative; active and revoked are disjoint. Signed by that role's offline root. |
| `revoman-cs2a-role-trust-high-water/v1` | `schema`, `role`, `generation`, `policySha256`, `previousHighWaterSha256` (`null` iff generation 1, else `h256`). Signed by the same role root. Generation and policy digest must match the policy; the complete contiguous chain from generation 1 is verified. |
| `revoman-cs2a-authority-root-inventory/v1` | `schema`, `roots`: exactly eleven objects sorted by `role`, each with ordered keys `role`, `rootKeyId`, `publicKeyHex` (exactly 64 lowercase hex encoding 32 raw bytes and hashing to `rootKeyId`). Its digest is independently pinned in the executor and identically bound by design and bootstrap approvals; it is not self-authenticated by one of its members. |
| `revoman-cs2a-authority-trust-inventory/v1` | `schema`, `rootInventorySha256`, `entries`: 1..11 objects sorted by `role`, each with ordered keys `role`, `policySha256`, `highWaterSha256`. Its digest is signed by release certification; each referenced policy/high-water remains independently signature-verified under its role root. |

An authority record is accepted only when its signer is active and nonrevoked in the exact policy,
the supplied high-water is the highest independently pinned generation, and both policy chains are
contiguous and role-matching. Cross-role key reuse is invalid even if raw key bytes match.

| CAS/durability element | Exact rule |
|---|---|
| Root | Canonical pre-opened protected root, owned by the expected administrative UID, directory mode `0700`, no symlink in any component, same filesystem for candidate and target. |
| Payload target | `records/SCHEMA/sha256/HH/DIGEST.json`, where `SCHEMA` is the literal with `/` encoded as `_`, `DIGEST` is payload `h256`, and `HH` is its first two characters. |
| Signature target | Payload target plus `.sig`; it contains only the common detached frame. |
| Publication | Create deterministic hidden same-directory `.DIGEST.json.candidate` and `.DIGEST.json.sig.candidate` with `O_CREAT\|O_EXCL\|O_NOFOLLOW`, mode `0400`; bounded full write; `fsync` each; no-clobber rename signature then payload; `fsync` target directory. The payload is the commit marker. |
| Adoption | Payload+signature is accepted only after no-follow regular-file/type/owner/mode/size checks, exact digest, canonical decode, and signature verification. Signature target plus exact durable hidden payload candidate is the sole prepared shape: verify both, rename payload, and parent-fsync. A complete pair lacking only observed parent fsync is reverified and parent-fsynced. Any other partial/mismatch is a hard stop. |
| Fork/replay | A content-addressed duplicate is idempotent. Trust policy/high-water generations may have exactly one digest and predecessor; a competing digest is a fork. Records may not replace mutable aliases. |
| Reader | Pre-open root; `openat2` beneath/no-magiclinks/no-symlinks or equivalent component walk; read each inode once into a bounded buffer; verify digest and signature before parsing policy fields. |

## 3. Authority records

Every table lists JSON keys in wire order. `authorityRole`, `authorityPolicyGeneration`, and
`authorityPolicySha256` must match the signer and supplied current policy.

| Record | Domain | Ordered keys and exact constraints |
|---|---|---|
| Design approval | `revoman-cs2a-design-approval/v1` | `schema` (same literal), `authorityRole`=`design-approver`, `authorityPolicyGeneration` (`puint`), `authorityPolicySha256` (`h256`), `authorityHighWaterSha256` (`h256`), `authorityRootInventorySha256` (`h256`), `closureCommitSha` (`git40`), `designSha256` (`h256`), `planSetCommitSha` (`git40`), `planSetSha256` (`h256` over the ordered plan-set inventory), `interfaceInventorySha256` (`h256` over the closure inventory), `decision`=`approved`, `issuedAtEpochSeconds` (`uint`). It is published and authenticated before any production edit. |
| Fixed-range review | `revoman-cs2a-fixed-range-review/v1` | `schema`, `authorityRole` (exactly role selected by `reviewKind`), `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `reviewKind`=`standards\|spec\|security`, `baseCommitSha` (`git40`), `headCommitSha` (`git40`, strict descendant), `designClosureCommitSha` (`git40`), `designSha256` (`h256`), `releaseManifestSha256` (`h256`), `releaseInventorySha256` (`h256`), `qodanaResultSha256` (`h256`), `rootGateResultSha256` (`h256`), `reviewedSourceInventorySha256` (`h256`), `reviewedArtifactInventorySha256` (`h256`), `findingsSha256` (`h256` over canonical ordered findings), `criticalCount`=0, `importantCount`=0, `decision`=`pass`, `issuedAtEpochSeconds`. Each kind uses a distinct role chain. |
| Qodana range result | `revoman-cs2a-qodana-range-result/v1` | `schema`, `authorityRole`=`qodana-publisher`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `baseCommitSha`, `headCommitSha`, `designClosureCommitSha`, `designSha256`, `qodanaDistributionSha256`, `qodanaConfigurationSha256`, `changedPathInventorySha256`, `sarifSha256`, `normalizedFindingInventorySha256`, `criticalCount`=0, `highCount`=0, `decision`=`pass`, `issuedAtEpochSeconds`. A Gradle exit or caller JSON is not this record. |
| Bootstrap approval | `revoman-cs2a-bootstrap-approval/v1` | `schema`, `authorityRole`=`bootstrap-approver`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `authorityRootInventorySha256`, `implementationSha` (`git40`), `designApprovalSha256`, `releaseManifestSha256`, `releaseInventorySha256`, `verifierSourceSha256`, `verifierDistributionInventorySha256`, `verifierLauncherSha256`, `jdkInventorySha256`, `jdkJavaSha256`, `providerInventorySha256`, `executorSha256`, `executorProvenanceSha256`, `processProfileSha256`, `reproductionOneInventorySha256`, `reproductionTwoInventorySha256`, `decision`=`approved`, `issuedAtEpochSeconds`. Root-inventory digest must equal design approval; the two reproduction inventories and approved distribution inventory must be equal. |
| VM/test-infrastructure attestation | `revoman-cs2a-vm-infrastructure-attestation/v1` | `schema`, `authorityRole`=`vm-infrastructure-approver`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `implementationSha`, `releaseSetIdentitySha256`, `qemuBinarySha256`, `qemuProvenanceSha256`, `imageSha256`, `imageProvenanceSha256`, `kernelSha256`, `guestRunnerSha256`, `hostCoordinatorSha256`, `syscallProbeSha256`, `syscallProbeBuildSha256`, `scenarioInventorySha256`, `processProfileSha256`, `privateNamespaceProfileSha256`, `decision`=`approved`, `issuedAtEpochSeconds`. Caller-supplied binary/image hashes must equal this signed record. |
| Root-gate result | `revoman-cs2a-root-gate-result/v1` | `schema`, `authorityRole`=`root-gate-publisher`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `implementationSha`, `releaseSetIdentitySha256`, `vmInfrastructureAttestationSha256`, `scenarioInventorySha256`, `bootChainSha256`, `guestAttestationInventorySha256`, `syscallBarrierInventorySha256`, `resultInventorySha256`, `powerCutCount` (`puint`), `freshVmCount` (`puint`), `failedScenarioCount`=0, `hostLeakCount`=0, `decision`=`pass`, `issuedAtEpochSeconds`. Every scenario binds a fresh VM/disk identity and the boot/phase predecessor. |
| Release certification | `revoman-cs2a-release-certification/v1` | `schema`, `authorityRole`=`release-certifier`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `implementationSha`, `planSetCommitSha`, `designClosureCommitSha`, `designSha256`, `designApprovalSha256`, `authorityTrustInventorySha256`, `releaseManifestSha256`, `releaseInventorySha256`, `releaseSetIdentitySha256`, `bootstrapApprovalSha256`, `qodanaResultSha256`, `vmInfrastructureAttestationSha256`, `rootGateResultSha256`, `standardsReviewSha256`, `standardsReviewVerifiedResultSha256`, `specReviewSha256`, `specReviewVerifiedResultSha256`, `securityReviewSha256`, `securityReviewVerifiedResultSha256`, `decision`=`certified`, `issuedAtEpochSeconds`. All children are reopened and reverified; the independent certification digest is additionally supplied out of band. |

The release-set identity is `SHA-256("revoman-cs2a-release-set-identity/v1\0" ||
implementationShaASCII || manifestSha256ASCII || inventorySha256ASCII)`. The manifest is canonical
JSON; the inventory is ASCII lines sorted by relative path, each exactly
`TYPE<TAB>MODE4<TAB>SIZE<TAB>SHA256<TAB>RELATIVE_PATH<LF>`. `TYPE` is `f|d`, directories use size
`0` and digest `-`, paths are normalized relative ASCII without empty, dot, dot-dot, absolute, TAB,
CR/LF, or duplicate components. Symlinks, devices, sockets, FIFOs, hard-link aliases, writable
files, and unlisted entries are invalid.

## 4. `Cs2aReleaseSetVerifier` closed CLI

The executable has exactly four modes. `argc` must equal the table. Every operand is a nonempty
ASCII path, enum, `h256`, or `git40` according to its name; paths are canonical absolute paths with
length `1..4096` and are opened no-follow. No option syntax, response file, glob, or environment
fallback exists.

| Mode | Exact operands after mode, in order | `argc` including program and mode |
|---|---|---|
| `verify-design-approval` | `DESIGN_APPROVAL DESIGN_APPROVAL_SHA256 EXPECTED_CLOSURE_COMMIT EXPECTED_DESIGN_SHA256 DESIGN_TRUST_POLICY DESIGN_TRUST_POLICY_SHA256 DESIGN_TRUST_HIGH_WATER DESIGN_TRUST_HIGH_WATER_SHA256 BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256` | 12 |
| `verify-fixed-range-review` | `REVIEW_KIND REVIEW REVIEW_SHA256 REVIEW_TRUST_POLICY REVIEW_TRUST_POLICY_SHA256 REVIEW_TRUST_HIGH_WATER REVIEW_TRUST_HIGH_WATER_SHA256 BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256 EXPECTED_BASE_COMMIT EXPECTED_HEAD_COMMIT EXPECTED_DESIGN_CLOSURE_COMMIT EXPECTED_DESIGN_SHA256 EXPECTED_RELEASE_MANIFEST_SHA256 EXPECTED_RELEASE_INVENTORY_SHA256 EXPECTED_QODANA_RESULT_SHA256 EXPECTED_ROOT_GATE_RESULT_SHA256` | 19 |
| `verify-release-set` | `RELEASE_SET CERTIFICATION CERTIFICATION_SHA256 EXPECTED_IMPLEMENTATION_SHA DESIGN_APPROVAL DESIGN_APPROVAL_SHA256 BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256` | 10 |
| `verify-clearance-result` | `CLEARANCE_RESULT CLEARANCE_RESULT_SHA256 EXPECTED_TOKEN EXPECTED_IMPLEMENTATION_SHA EXPECTED_REPORT_KIND EXPECTED_REASON EXPECTED_CLEARANCE_TRUST_DISPOSITION EXPECTED_CLEARANCE_POLICY_SHA256 EXPECTED_CLEARANCE_HIGH_WATER_SHA256 EXPECTED_COMPONENT_COMPROMISE_PROOF_SHA256 EXPECTED_EVIDENCE_SHA EXPECTED_VALIDATION_SHA EXPECTED_VALIDATION_RESULT_SHA256 EXPECTED_VALIDATION_FAILURE_PROOF_SHA256 EXPECTED_VALIDATION_FAILURE_DISPOSITION EXPECTED_SIGNED_TERMINAL_SHA256 EXPECTED_INCIDENT_PROVENANCE EXPECTED_REMEDIATION_SHA256 CLEARANCE_TRUST_POLICY CLEARANCE_TRUST_POLICY_SHA256 CLEARANCE_TRUST_HIGH_WATER CLEARANCE_TRUST_HIGH_WATER_SHA256 DESIGN_APPROVAL DESIGN_APPROVAL_SHA256 BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256` | 28 |

| Mode | Exact success TSV fields, in order |
|---|---|
| `verify-design-approval` | `revoman-cs2a-verified-design-approval/v1`, `closureCommitSha`, `designSha256`, `planSetCommitSha`, `planSetSha256`, `interfaceInventorySha256`, `authorityRootInventorySha256`, `designApprovalSha256`, `signerKeyId`, `authorityPolicySha256`, `authorityHighWaterSha256` |
| `verify-fixed-range-review` | `revoman-cs2a-verified-review/v1`, `reviewKind`, `baseCommitSha`, `headCommitSha`, `designClosureCommitSha`, `designSha256`, `releaseManifestSha256`, `releaseInventorySha256`, `qodanaResultSha256`, `rootGateResultSha256`, `reviewSha256`, `findingsSha256`, `signerKeyId`, `authorityPolicySha256`, `authorityHighWaterSha256` |
| `verify-release-set` | `revoman-cs2a-verified-release/v1`, `implementationSha`, `releaseManifestSha256`, `releaseInventorySha256`, `certificationSha256`, `releaseSetIdentitySha256` |
| `verify-clearance-result` | The exact 23 fields in Section 8, with no additional field. |

| Exit | Meaning | Stdout | Stderr |
|---|---|---|---|
| `0` | All bytes, signatures, role policy/high-water, child cross-links, expectations, inventory, and nullability checks succeeded | Exactly one mode success TSV line | Empty |
| `2` | Invalid invocation, malformed/noncanonical/bounds/type/path/input, digest mismatch, expectation mismatch, unknown field/value, inventory mismatch, or invalid state/nullability combination | Empty | Exactly `revoman-cs2a-error/v1<TAB>invalid-input<LF>` |
| `3` | Signature failure, wrong role/domain/key, inactive/revoked signer, trust rollback/gap/fork, or unauthenticated authority child | Empty | Exactly `revoman-cs2a-error/v1<TAB>authentication-failed<LF>` |
| `70` | JCA/provider/I/O/resource/internal invariant failure not attributable to bounded invalid input | Empty | Exactly `revoman-cs2a-error/v1<TAB>internal-failure<LF>` |

All input is consumed before any success byte is written. A caught exception cannot emit a partial
line. Exit `0` is not itself authority: consumers must parse the exact field count and compare all
expected fields.

## 5. Bootstrap executor

The executor is not produced by candidate Gradle. Its exact invocation is:

```text
CS2A_BOOTSTRAP_EXECUTOR BOOTSTRAP_APPROVAL BOOTSTRAP_APPROVAL_SHA256 VERIFIER_HOME JAVA_HOME cs2a-release-set-verifier MODE MODE_OPERANDS...
```

| Executor concern | Exact contract |
|---|---|
| Bootstrap authentication | Before JVM launch, no-follow read the approval/signature and bootstrap role policy/high-water from its sealed configuration; require the argv record digest, executor digest/provenance, verifier/JDK inventories, launcher, provider, and process-profile digests to match. The executor cannot approve its own bytes. |
| Paths | `VERIFIER_HOME` and `JAVA_HOME` are absolute, canonical, root/administrator-owned, nonwritable trees whose complete inventories equal the approval. Main selector is the literal `cs2a-release-set-verifier`. |
| Environment | `env -i` equivalent with exactly `PATH=/usr/bin:/bin`, `LC_ALL=C`, `TZ=UTC`; no `HOME`, Java options, agent, locale/provider override, or inherited secret. |
| Cwd/umask | Preapproved empty read-only directory, owner expected administrator, mode `0555`; `umask 077`. |
| Descriptors | FD 0 is read-only `/dev/null`; FD 1 and 2 are the caller's distinct pipe or regular capture FDs; exactly FDs 0,1,2 survive. No socket, terminal, directory, or writable input FD. |
| JVM | Exact `JAVA_HOME/bin/java` digest; argv inventory digest from bootstrap record; fixed heap/encoding/locale/timezone/provider/classpath/main; no wildcard classpath or dynamic lookup. |
| Signals/process | Reset dispositions and mask to the approved profile, no tracer, no parent-death race, no supplementary groups outside profile, and resource limits equal `processProfileSha256`. |
| Result | Preserve verifier exit and exact stdout/stderr. Executor-local input/auth/internal failures use `2/3/70` and the same empty-stdout error table. |

The bootstrap approval may be replaced only by a new higher role-policy generation and new
content-addressed record. It never replaces the pre-code design approval.

## 6. VM lifecycle and syscall barrier

The host coordinator accepts only the exact infrastructure attestation and root-gate schemas above.
Each run uses `create -> boot -> runGuestPhase -> killPower -> boot -> runGuestPhase -> collect ->
destroy`; a reused disk/VM, container, macOS guest, or real host `/opt` is invalid. Each guest phase
emits a root-owned canonical attestation binding image, VM/disk ID, scenario, boot ID, phase,
previous phase digest, command inventory, release identity, and result digest. The coordinator's
hash-linked boot chain covers all phase attestations and barrier events.

The test-only syscall probe uses a private bidirectional virtio-serial channel and `ptrace` syscall
stops. Messages use canonical TSV and are bounded to 2048 bytes.

| Message | Exact fields |
|---|---|
| Probe `READY` | `revoman-cs2a-syscall-barrier-ready/v1`, `scenarioId` (1..64 `[a-z0-9-]`), `nonce` (64 lowercase hex), `traceePid` (`puint`), `traceeStartTicks` (`puint`), `traceeExeSha256` (`h256`), `fdMapSha256` (`h256`) |
| Probe `STOP` | `revoman-cs2a-syscall-barrier-stop/v1`, `scenarioId`, `nonce`, `sequence` (`puint`), `traceePid`, `traceeStartTicks`, `traceeExeSha256`, `fdMapSha256`, `barrier` (one enum below), `syscallNumber` (`uint`), `objectRole`=`file\|parent`, `objectDev` (`uint`), `objectIno` (`puint`), `returnValue` (`-` at entry; `0` at success exit) |
| Host `ACK` | `revoman-cs2a-syscall-barrier-ack/v1`, `scenarioId`, `nonce`, `sequence`, `stopSha256` (`h256`), `action`=`power-cut` |

| Barrier enum | Required observation |
|---|---|
| `rename.entry` | Entry to the scenario-selected no-clobber rename of the authenticated candidate inode. |
| `rename.exit-success` | Syscall exit with return value zero and the same authenticated object identities. |
| `file-fsync.entry` | Entry to `fsync` for the scenario-selected regular file FD. |
| `file-fsync.exit-success` | Successful exit from that file `fsync`. |
| `parent-fsync.entry` | Entry to `fsync` for the authenticated parent-directory FD. |
| `parent-fsync.exit-success` | Successful exit from that parent-directory `fsync`. |

Protocol order is exact: the probe authenticates scenario/nonce, PID plus `/proc/PID/stat`
start-time, executable inode/digest, syscall architecture, and complete FD/object map; attaches with
`PTRACE_SEIZE|PTRACE_O_TRACESYSGOOD`; emits `READY`; observes the selected entry/exit without
`POKEDATA`, register writes, syscall substitution, memory/file writes, or return-value change; emits
one `STOP`; and blocks. The host validates the hash-linked event, durably records it, returns the
matching `ACK`, then sends `SIGKILL` to the authenticated QEMU PID and waits for that exact PID to
exit. The guest never receives a continue after ACK. EOF, timeout, duplicate/out-of-order message,
PID/start-time/FD drift, wrong syscall/object, failed syscall for an exit barrier, or probe death is
a failed root-gate scenario, never a simulated crash success.

## 7. Clearance, component, and partial-claim records

| Record | Domain | Ordered JSON keys and exact constraints |
|---|---|---|
| Clearance result | `revoman-cs2a-clearance-result/v1` | `schema`, `authorityRole`=`clearance-administrator`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `token`, `implementationSha`, `reportKind`, `mode`, `clearanceReason`, `clearanceTrustDisposition`, `clearancePolicySha256`, `clearanceHighWaterSha256`, `componentCompromiseProofSha256`, `requestSha256`, `generationSha256`, `terminalHeadSha256`, `evidenceSha`, `validationSha`, `validationResultSha256`, `validationFailureProofSha256`, `validationFailureDisposition`, `signedTerminalSha256`, `remediationSha256`, `incidentProvenance`, `sequence`, `previousHeadSha256`, `activeToken`. Nullable digest/provenance fields use JSON `null`; `activeToken` must be null. All matrices below are enforced before signature acceptance. |
| Component-compromise proof | `revoman-cs2a-component-compromise/v1` | `schema`, `authorityRole`=`component-incident-authority`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `token`, `implementationSha`, `observedAtEpochSeconds`, `bootSha256`, `hostScopeSha256`, `suspectComponents` (1..8 sorted unique values from `entry\|payload\|clearance\|copier\|signer\|verifier`), `suspectInventorySha256`, `observationSourceSha256`, `observationEvidenceSha256`, `replacementAuthoritySha256`, `replacementInventorySha256`, `decision`=`compromised`. It is published and verified only by independent replacement tooling. |
| Partial-claim proof | `revoman-cs2a-partial-claim/v1` | `schema`, `authorityRole`=`clearance-administrator`, `authorityPolicyGeneration`, `authorityPolicySha256`, `authorityHighWaterSha256`, `token`, `implementationSha`, `bootSha256`, `claimDev` (`uint`), `claimIno` (`puint`), `durablePrefixLength` (`puint`, at most the design claim bound), `durablePrefixSha256`, `completeHeaderAbsent`=true, `ownerAbsent`=true, `controllerAbsent`=true, `guardianAbsent`=true, `governorAbsent`=true, `benchmarkLockAbsent`=true, `globalGenerationAbsent`=true, `competingClaimAbsent`=true, `remediationSha256`. Its digest appears only as `partial-claim-sha256:DIGEST`. |

The clearance reason enum is exactly:
`none|manual-safety|integrity-failure|preclearance-current-revoked|preclearance-current-drift|validation-failure-manual-safety|safety-only|external-admin-required|current-revoked|current-drift|quarantined|partial-claim|component-compromise`.
The mode enum is exactly `normal|manual-safety|replacement`; report kind is exactly
`signed-evidence|signed-evidence-no-authority|signed-evidence-unvalidated-no-authority|incident-no-authority`.

In the next table `D` means one authenticated `h256`, `-` means null, `P` means one closed
provenance value, and `R` means an independently authenticated remediation `h256`. `U/D/R` in a
trust pair means monotonic compatibility: `unchanged` may advance to any; `current-drift` may remain
drift or advance to revoked; `current-revoked` remains revoked. Rows are the exhaustive accepted
report/reason profiles.

| Report kind | Clearance reason | Mode | Clearance trust | Report-time trust | E | V | VR | Failure proof/disposition | Signed terminal | Component proof | Provenance | Remediation |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `signed-evidence` | `none` | `normal` | `unchanged` | `unchanged` | D | D | D | `-/-` | D | - | - | - |
| `signed-evidence-no-authority` | `none` | `normal` | `unchanged` | `current-drift\|current-revoked` | D | D | D | `-/-` | D | - | - | - |
| same | `preclearance-current-revoked` | `manual-safety\|replacement` | `current-revoked` | `current-revoked` | D | D | D | `-/-` | D | - | P | R |
| same | `preclearance-current-drift` | `manual-safety\|replacement` | `current-drift\|current-revoked` | compatible D/R | D | D | D | `-/-` | D | - | P | R |
| same | `manual-safety\|integrity-failure` | `manual-safety\|replacement` | U/D/R | monotonic U/D/R | D | D | D | `-/-` | D | - | P | R |
| same | `component-compromise` | `replacement` | `not-applicable` | U/D/R | D | D | D | `-/-` | D | D | P | R |
| `signed-evidence-unvalidated-no-authority` | `validation-failure-manual-safety` | `manual-safety\|replacement` | U/D/R | monotonic U/D/R | D | - | - | D + `semantic-failed\|interrupted\|owner-abandoned` | D | - | P | R |
| same | `preclearance-current-revoked` | `manual-safety\|replacement` | `current-revoked` | `current-revoked` | D | - | - | D + `preflight-current-revoked` | D | - | P | R |
| same | `preclearance-current-drift` | `manual-safety\|replacement` | `current-drift\|current-revoked` | compatible D/R | D | - | - | D + `preflight-current-drift` | D | - | P | R |
| same | `component-compromise` | `replacement` | `not-applicable` | U/D/R | D | - | - | D + any closed failure disposition | D | D | P | R |
| `incident-no-authority` | `safety-only\|external-admin-required` | `manual-safety\|replacement` | `not-applicable` | `not-applicable` | - | - | - | `-/-` | - | - | closed `absence:*` P | R |
| same | `quarantined` | `manual-safety\|replacement` | `not-applicable` | `not-applicable` | - | - | - | `-/-` | - | - | `quarantine-sha256:D` | R |
| same | `current-revoked` | `manual-safety\|replacement` | `current-revoked` | `not-applicable` | - | - | - | `-/-` | `D\|-` | - | P | R |
| same | `current-drift` | `manual-safety\|replacement` | `current-drift\|current-revoked` | `not-applicable` | - | - | - | `-/-` | `D\|-` | - | P | R |
| same | `partial-claim` | `manual-safety\|replacement` | `not-applicable` | `not-applicable` | - | - | - | `-/-` | - | - | `partial-claim-sha256:D` | R |
| same | `component-compromise` | `replacement` | `not-applicable` | `not-applicable` | - | - | - | `-/-` | `D\|-` | D | P | R |

For every row, non-`not-applicable` clearance trust carries `clearancePolicySha256=D` and
`clearanceHighWaterSha256=D`; `not-applicable` carries `-/-`. Report-time trust is independently
authenticated and is never written back into the clearance result. `D|-` is exhaustive shorthand
for two otherwise identical rows; when terminal is `D`, provenance must be
`terminal-sha256:D`; when terminal is `-`, provenance must be the row's quarantine, partial,
component, or closed absence value. Component rows may use any valid diagnostic provenance; if none
exists they must use `component-compromise-sha256:COMPONENT_PROOF_DIGEST`.

| Clearance transaction profile | Request | Generation | Terminal head | Sequence | Previous head | Active token |
|---|---|---|---|---|---|---|
| `normal` | D | D | D | `puint` | `-` iff sequence 1, otherwise D | `null` |
| `manual-safety`, ordinary active token | D | D | `D\|-` according to authenticated terminal-observed state | `puint` | `-` iff sequence 1, otherwise D | `null` |
| `manual-safety`, partial pre-ledger claim | D | D | - | `puint` | `-` iff sequence 1, otherwise D | `null` |
| `replacement` through admitted global generation | D | D | `D\|-` | `puint` | `-` iff sequence 1, otherwise D | `null` |
| `replacement` by external reprovision/reset | - | - | - | `0` | - | `null` |

| Failure disposition | Child status | Required clearance reason, unless component dominates | Clearance trust constraint |
|---|---|---|---|
| absent | `-` | Any row not requiring a failure | Per reason matrix |
| `semantic-failed` | decimal `1..255` | `validation-failure-manual-safety` | U/D/R |
| `interrupted` | decimal `1..255` | `validation-failure-manual-safety` | U/D/R |
| `owner-abandoned` | `-` | `validation-failure-manual-safety` | U/D/R; failure proof binds owner/child absence and finalizer liveness check |
| `preflight-current-revoked` | `-` | `preclearance-current-revoked` | `current-revoked` |
| `preflight-current-drift` | `-` | `preclearance-current-drift` | `current-drift\|current-revoked` |

Failure proof and disposition are both null or both nonnull. The proof is the digest of the
authenticated immutable validation-attempt outcome, including attempt/token/evidence/boot/owner,
parent-observed child wait status when present, and trust proof for preflight outcomes. A semantic
child is never retried after any nonnull outcome.

| Precedence rank | Rule |
|---|---|
| 1 | Any independently proven suspect `entry\|payload\|clearance` (or other listed component) forces reason `component-compromise`, mode `replacement`, component proof D, and forbids the suspect bytes from publishing or verifying proof/clearance. This dominates every row below while retaining applicable evidence/failure identities and selecting the corresponding signed or incident report kind. |
| 2 | Immutable preflight trust outcome forces matching `preclearance-current-*`; later revocation may advance only the trust axis, never relabel the reason. |
| 3 | Authenticated validation failure forces `validation-failure-manual-safety`; authenticated validation with non-PASS archive integrity forces `integrity-failure`. |
| 4 | Existing authenticated preclearance/integrity reason outranks caller-selected `manual-safety`; manual safety cannot erase it. |
| 5 | In an archive-absent incident, exact `partial-claim`, `quarantined`, `current-*`, root publication failure, or `safety-only` reason is preserved; generic absence cannot replace a stronger diagnostic. |

| Provenance / `@record` item | Exact rule |
|---|---|
| Closed provenance values | `terminal-sha256:H`, `quarantine-sha256:H`, `partial-claim-sha256:H`, `component-compromise-sha256:H`, `absence:root-quarantine-publication-failed`, or `absence:root-signer-unavailable-before-terminal`, where `H` is `h256`. No other string is accepted. |
| Diagnostic priority | Existing authenticated terminal, then quarantine, then partial-claim proof; component proof is used only when none exists. The two absence literals apply only to their exact independently proven publication failures. Remediation is never provenance. |
| Component equality | `componentCompromiseProofSha256` is D iff reason is component; otherwise null. A component provenance digest must equal it. |
| Partial equality | `partial-claim-sha256:H` requires an authenticated partial-claim record whose payload digest is H and whose remediation equals the clearance result. |
| `@record` allowed | Only expectations for `clearanceReason`, clearance trust disposition/policy/high-water, component proof, and—only in the three no-authority report kinds—nullable provenance/remediation. It tells the verifier to derive and return the signed value after enforcing the matrix. |
| `@record` forbidden | Record/digest, token, implementation, report kind, evidence, validation, validation result, failure proof/disposition, signed terminal, all trust inputs, design/bootstrap approvals, and any field with an earlier authenticated source. It is never serialized or returned. |

## 8. Exact clearance verifier output and failure closure

| Position | Field | Exact value |
|---:|---|---|
| 1 | `version` | `revoman-cs2a-verified-clearance/v1` |
| 2 | `token` | `token` |
| 3 | `implementation` | `git40` |
| 4 | `mode` | exact mode enum |
| 5 | `clearanceReason` | exact reason enum |
| 6 | `clearanceTrustDisposition` | `unchanged\|current-revoked\|current-drift\|not-applicable` |
| 7 | `clearancePolicySha256` | `h256\|-` per matrix |
| 8 | `clearanceHighWaterSha256` | `h256\|-` per matrix |
| 9 | `componentCompromiseProofSha256` | `h256\|-` per matrix |
| 10 | `requestSha256` | `h256\|-` per transaction profile |
| 11 | `generationSha256` | `h256\|-` per transaction profile |
| 12 | `terminalHeadSha256` | `h256\|-` per transaction profile |
| 13 | `evidenceSha` | `git40\|-` per report matrix |
| 14 | `validationSha` | `git40\|-` per report matrix |
| 15 | `validationResultSha256` | `h256\|-` per report matrix |
| 16 | `validationFailureProofSha256` | `h256\|-` paired with field 17 |
| 17 | `validationFailureDisposition` | exact failure enum or `-` |
| 18 | `signedTerminalSha256` | `h256\|-` per report matrix |
| 19 | `remediationSha256` | `h256\|-` per report matrix |
| 20 | `incidentProvenance` | closed provenance or `-` |
| 21 | `sequence` | `0` only for external replacement, else `puint` |
| 22 | `previousHeadSha256` | `h256\|-` per transaction profile |
| 23 | `activeToken` | literal `null` |

| Failure condition | Mandatory disposition |
|---|---|
| Bad argv/path/canonical JSON/digest/enum/nullability/cross-link/inventory/expected value | Verifier exit 2, empty stdout; no report and no authority transition. |
| Bad signature/domain/role/key/policy/high-water/revocation or unauthenticated child | Verifier exit 3, empty stdout; no report and no authority transition. |
| Provider/I/O/internal failure | Verifier exit 70, empty stdout; preserve inputs and require operator repair. |
| Valid clearance record but `activeToken` nonnull, missing parent-fsynced generation, ambiguous fork, or incomplete transaction | Exit 2; reporting is forbidden and token remains active until administrator recovery/adoption. |
| Report kind/reason/mode/trust cross-product absent from the exhaustive matrix | Exit 2; it may not be relabeled into a nearby row. |
| Component suspicion with missing/invalid independent proof or attempted suspect-tool verification | Exit 3; only independent replacement/reprovision authority may continue. |
| Partial claim without exact inode/prefix/absence/remediation proof | Exit 3; no evidence, validation, clearance, or new token is authorized. |
| Valid signed evidence whose report-time trust advanced | Preserve the signed clearance-time axis; select the compatible no-authority report row. Never overwrite the clearance result or regain performance authority. |

## 9. Irreducible decisions fixed by this closure

| # | Fixed decision |
|---:|---|
| 1 | Variant A: common framing/JSON/CAS mechanism with role-specific schemas. |
| 2 | Eleven authority roles have eleven independent offline roots and monotonic high-water chains. |
| 3 | Payload digest, not pathname or signature bytes, is the content address; payload rename is the pair commit marker. |
| 4 | Design approval is pre-code and bootstrap approval is post-build; neither can substitute for the other. |
| 5 | Standards, Spec, and security reviews use separate roles, while all bind the same exact base/head/design/release/root-gate identities. |
| 6 | Qodana, VM infrastructure, root-gate, and release certification are signed first-class records rather than process-status assertions. |
| 7 | `Cs2aReleaseSetVerifier` has exactly four modes and the argv/output/status contracts above. |
| 8 | Bootstrap execution uses an external independently approved executor, clean three-FD process, fixed environment/cwd/JDK/provider/inventory, and no candidate Gradle. |
| 9 | Syscall crash barriers observe but never modify the tracee; only the host kills authenticated QEMU after a durable ACK event. |
| 10 | Clearance has two independent trust axes: immutable clearance-time trust in the signed result and freshly authenticated report-time trust. |
| 11 | The report/reason/mode/nullability table is closed; absent rows fail rather than inherit caller intent. |
| 12 | Component compromise has unconditional precedence and requires independent replacement authority/proof. |
| 13 | Partial claims require inode, durable-prefix, owner/side-effect/generation absence, and remediation proof and create no evidence identity. |
| 14 | `@record` is a narrowly allowed expectation sentinel, never data or ambient authority. |
| 15 | The clearance verifier emits exactly 23 TSV fields and nothing on failure. |

There are no unresolved protocol decisions in this package.
