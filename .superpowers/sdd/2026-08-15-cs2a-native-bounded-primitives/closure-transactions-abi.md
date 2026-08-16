# CS2a local transactions, recovery, and native ABI closure

Status: normative design-closure proposal. It uses only the existing cs2a-operator.sh, native entry, payload, copier, signer, verifier, and scoped commit-tree code inventory; no helper or schema source is added.

## Common authority and selected variant

All JSON is UTF-8 compact ordered JSON with exactly the fields in its table. Unknown, duplicate, reordered, escaped/non-ASCII, or noncanonical numeric values fail. TOKEN is 32 lowercase hex; SHA256 is 64 lowercase hex; SEQUENCE is canonical decimal 1..9007199254740991. SHA256 is over exact bytes. Records are no-follow regular nlink==1: hidden write, fsync(file), no-clobber rename/link, fsync(parent). Complete visible pre-parent-fsync bytes are adopted only byte-for-byte.

| Variant | State location | Publication | Decision |
|---|---|---|---|
| A | Mutable begin/outcome/prepared files inside canonical evidence. | Repeated commits or untracked evidence. | Reject: callers must parse mutable evidence. |
| B | Protected verifier-CAS transaction root; only adopted result/attempt reaches evidence. | Existing scoped commit-tree, ref CAS, worktree validation. | **Select:** deepest local module, best locality, one evidence seam. |
| C | One build journal. | Shell-status replay. | Reject: shell becomes cross-subsystem authority. |

Control root is LOCAL_VERIFIER_CAS/cs2a-local-transactions/v1/; children are 0700 and records 0600. Canonical evidence is only docs/superpowers/benchmarks/results/v1/cs2a-IMPLEMENTATION_SHA/. Readers emit exactly one TSV line only for accepted rows; extra stdout, malformed/replayed/forked state, or status disagreement is status 70/no stdout. Existing hook-disabled persistence creates one nonmerge direct-child commit, ref-CASes it, proves a clean tracked worktree, then publishes the result record.

## 1. Local setup failure

provisioning-required is excluded: status 76 and exactly revoman-cs2a-provisioning-required/v1<TAB>approvalPath<TAB>approvalSha256. It creates no token, stage, archive, marker, transaction, or commit.

| Schema/path | Ordered compact fields | Cap/status/durability |
|---|---|---|
| revoman-cs2a-local-setup-failure/v1 | schema, implementationSha, runtimeConfigSha256, transportIdentitySha256, failureCode, failureProofSha256, remoteEvidencePresent, launchInstructionPublished, remoteClaimAuthorityPresent | 4096. Code: detached-source-invalid, critical-source-mismatch, verifier-build-failed, verifier-cas-failed, transport-preflight-failed, runtime-config-mismatch, approval-mismatch, preparation-io-failed. Last three booleans false. |
| Canonical archive | meta/revoman-cs2a-local-setup-failure-v1.json plus meta/failure-proof.sha256 | docs/superpowers/benchmarks/results/v1/cs2a-IMPLEMENTATION_SHA/local-setup-failure.RUNTIME_CONFIG_SHA256.TRANSPORT_IDENTITY_SHA256.RECORD_SHA256; exactly those files. |
| Adoption | revoman-cs2a-local-setup-adoption/v1: schema, setupFailureSha256, evidenceSha | 1024 at setup/RECORD_SHA256.result in control root. |

| State | Rule |
|---|---|
| Conclusive pre-launch failure | Build fixed hidden archive, validate, scoped commit-tree, direct-parent ref CAS, clean-worktree check, then publish adoption. Return 70/no stdout; archive is authority. |
| Existing exact final-name/pre-fsync state | Fsync/adopt the identical archive/commit/adoption only. |
| Launch instruction, token, stage, or claim authority exists | Local setup fallback is permanently forbidden; return 70/no stdout. |

## 2. Validation begin/wait/outcome/result/adoption

Control path is validation/TOKEN/EVIDENCE_SHA/IMPLEMENTATION_SHA/ under control root. TOKEN is read from persisted evidence. Outcome and result are exclusive and either permanently bars semantic validation.

| Name/schema | Ordered fields | Cap/final name |
|---|---|---|
| Attempt | revoman-cs2a-validation-attempt/v1: schema, token, attemptRel, implementationSha, evidenceSha, bootSha256, ownerPid, ownerStartTime, ownerExecutableSha256, semanticChildExecutableSha256, createdMonotonicNs | 4096; attempt.json; publish before preflight or child. |
| Wait | revoman-cs2a-validation-wait-status/v1: schema, attemptSha256, token, ownerPid, ownerStartTime, childPid, childStartTime, childExitStatus, waitDisposition | 4096; wait.json; exited or signaled, parent-observed 1..255. |
| Result | revoman-cs2a-attempt-validation/v1: schema, attemptRel, token, implementationSha, evidenceSha, signedTerminalSha256, runtimeConfigSha256, verifierSha256, verifierJdkSha256, policySha256, highWaterSha256, archiveIntegrity, terminalEligibility, performanceDecision, classifierArtifactsSha256, parentWaitStatusSha256, validatorExitStatus, validationCommitParentSha | 16384; ATTEMPT/local-validation/revoman-cs2a-attempt-validation-v1.json; validatorExitStatus equals wait status; parent EVIDENCE_SHA. |
| Outcome | revoman-cs2a-validation-attempt-outcome/v1: schema, attemptSha256, token, implementationSha, evidenceSha, failureDisposition, childStatus, failureProofSha256 | 4096; outcome.FAILURE_PROOF_SHA256.json; one maximum. |
| Preflight | revoman-cs2a-validation-trust-preflight/v1: schema, attemptSha256, token, trustDisposition, policySha256, highWaterSha256 | 2048; preflight.POLICY_SHA256.HIGH_WATER_SHA256.json; current-revoked or current-drift only. |

archiveIntegrity is PASS|FAIL; terminalEligibility is eligible|ineligible; performanceDecision is PASS|FAIL|INCONCLUSIVE|not-applicable; classifierArtifactsSha256 is - iff ineligible. Owner-abandon proof is deterministic revoman-cs2a-validation-owner-absence/v1: schema, attemptSha256, token, ownerBootSha256, ownerPid, ownerStartTime, childPid, childStartTime, ownerPresent, childPresent. Both booleans false; inspector derives it from immutable identity plus boot/PID/start-time checks without a clock; finalizer recomputes exact bytes.

| Immutable disposition | childStatus | proof | Dynamic trust tail |
|---|---:|---|---|
| semantic-failed | 1..255 | wait SHA | unchanged|current-drift|current-revoked |
| interrupted | 1..255 | signaled wait SHA | unchanged|current-drift|current-revoked |
| owner-abandoned | - | absence proof SHA | unchanged|current-drift|current-revoked |
| preflight-current-revoked | - | preflight SHA | current-revoked only |
| preflight-current-drift | - | preflight SHA | current-drift|current-revoked |

| Crash/read state | Reader status/output | Sole writer |
|---|---|---|
| Completely absent transaction | 2, no output | --validate-attempt |
| Begin only, owner and child absent | 77; revoman-cs2a-validation-abandoned/v1<TAB>owner-abandoned<TAB>TOKEN<TAB>ATTEMPT_SHA256<TAB>OWNER_ABSENCE_PROOF_SHA256 | --finalize-abandoned-attempt-validation only |
| Exact prepared result/tree/commit/ref/worktree prefix | 75, no output | --adopt-attempt-validation only |
| Complete direct child and clean tracked result | Repeat TSV | None |
| Exact outcome, no result/validation commit | 6, failure TSV | None |
| Live identity, fork, wrong parent, multi-commit, dirty result, bad cross-link | 70, no output | None |

Result transaction: hidden candidate; validate result/wait; no-clobber canonical result; add only it; write tree; direct-child commit of EVIDENCE_SHA; ref CAS; clean tracked worktree validation; adoption record. Every incomplete prefix is the sole 75 state; adoption performs only missing steps on identical bytes and never invokes semantic validation.

| Mode | Prefix plus existing policy/high-water/CAS/JDK flags | Accepted contract |
|---|---|---|
| Mutator | --validate-attempt ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA | Process exit diagnostic only. |
| Inspector | --read-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA | 0/4/5: revoman-cs2a-repeat-validation/v1<TAB>unchanged|current-revoked|current-drift<TAB>validatorExitStatus<TAB>currentPolicySha256<TAB>currentHighWaterSha256. |
| Adopter | --adopt-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA | No accepted stdout; only exact 75. |
| Finalizer | --finalize-abandoned-attempt-validation ATTEMPT IMPLEMENTATION_SHA EVIDENCE_SHA ATTEMPT_SHA256 OWNER_ABSENCE_PROOF_SHA256 | No accepted stdout; append owner-abandoned only. |

Status 6 is exactly revoman-cs2a-validation-failed/v1<TAB>failureDisposition<TAB>childStatus<TAB>failureProofSha256<TAB>currentTrustDisposition<TAB>currentPolicySha256<TAB>currentHighWaterSha256. The tail is report-time-only and cannot rewrite immutable bytes.

## 3. Recovery disposition and same-token retry

Recovery chain is recovery/TOKEN/ under control root. Name each record SEQUENCE.CURRENT_BOOT_SHA256.RECORD_SHA256.json; no latest pointer.

| Schema | Ordered fields | Cap/invariant |
|---|---|---|
| revoman-cs2a-recovery-disposition/v1 | schema, token, sequence, previousRecordSha256, sealedBootSha256, currentBootSha256, currentNamespaceObservationSha256, localSessionSha256, transportObservationSha256, disposition | 4096; disposition changed-boot-publication-required; first previous null, later exact predecessor SHA; boots differ. |
| revoman-cs2a-pre-claim-retry/v1 | schema, token, runtimeConfigSha256, entrySha256, signerKeyId, claimAbsent, sideEffectAbsent, issuedBootSha256 | Signed terminal-style envelope, 4096 payload; both booleans true; root observation only. |

| Observation | Status/output | Procedure |
|---|---|---|
| Same boot with authenticated session/transport/key | 0 and only /opt/revoman-benchmark/cs2a-session-installer-v1 launch recover TOKEN | Human byte-compares/types in new approved TTY. |
| Authenticated changed boot | 76, empty stdout | Append/adopt one next record. --read-recovery-disposition TOKEN 76 verifies chain and prints version, changed-boot-publication-required, token, sequence, previousRecordSha256, dispositionSha256, sealedBootSha256, currentBootSha256, currentNamespaceObservationSha256. |
| Changed-boot root publication | No instruction | Direct-root publish-recovery-namespace TOKEN ATTESTATION_SHA256 SELECTION_SHA256 holds admission/lifecycle/token order and parent-fsyncs recovery-current-only selection for active token or unique complete pre-ledger claim/header. No active generation or fresh-run high-water change. |
| Exact signed pre-claim retry record | 0 and only /opt/revoman-benchmark/cs2a-session-installer-v1 launch run TOKEN | One new-TTY retry; root run is sole O_EXCL claimant. |
| Unsigned absence, no retry record, mismatch, gap/fork/replay, same-boot publication | 70, empty stdout | No launch, token replacement, or inferred absence. |

## 4. Collector, persistence, and canonical attempt

Collector control is collection/TOKEN/ under control root; stage.COLLECTOR_SHA256 is 0700; immutable marker is collector.COLLECTOR_SHA256.json. Stage identity is never argv or reader output.

| Schema | Ordered fields | Cap/closed values |
|---|---|---|
| revoman-cs2a-collector-result/v1 | schema, token, localSessionSha256, implementationSha, effectiveStatus, returnStatus, disposition, protectedStageSha256, intendedAttemptRel, archiveTreeSha256, signedTerminalSha256, incidentProvenance | 8192; signed-staged|safety-only|external-admin-required. Only signed-staged has stage/path/tree and terminal-sha256:SHA256 provenance. |
| revoman-cs2a-persistence-result/v1 | schema, collectorSha256, token, implementationSha, effectiveStatus, returnStatus, policySha256, highWaterSha256, disposition, attemptRel, evidenceSha, signedTerminalSha256, incidentProvenance | 8192; persisted-signed|current-revoked|current-drift|quarantined. |

Nonpersisted terminal has null attempt/evidence and exactly one terminal-sha256:SHA256, quarantine-sha256:SHA256, absence:root-quarantine-publication-failed, or absence:root-signer-unavailable-before-terminal. admin-inspection-required is collector-only and never authorizes absence.

| Operation | Contract |
|---|---|
| active-uncertain | 75, no stdout/marker. |
| Collector reader | --read-collector-result TOKEN COLLECT_STATUS checks returnStatus; emits revoman-cs2a-collector-result/v1<TAB>disposition<TAB>intendedAttemptRel-or--<TAB>archiveTreeSha256-or--<TAB>incidentProvenance. |
| Persist return | persisted-signed returns effective status; current-revoked 4; current-drift 5; quarantined 70. Process status is never authority. |
| Persistence reader | --read-persistence-result TOKEN PERSIST_STATUS checks returnStatus; emits revoman-cs2a-persistence-result/v1<TAB>disposition<TAB>attemptRel-or--<TAB>evidenceSha-or--<TAB>incidentProvenance; 75/no output only exact prepared transaction. |

| Point | Required transaction/replay |
|---|---|
| Preflight | Reopen marker/stage no-follow; recheck session, terminal, inventory, trust. Publish no-authority result before removing stage; no attempt. |
| Prepared tree | Signed-staged only: marker-derived attempt, materialize stage, validate inventory, add only scope, frozen tree. Untracked attempt only exact recorded 75. |
| Commit/ref/worktree | One nonmerge direct child, same-ref CAS, clean tracked byte-equal worktree. Adopt exact prefix only; never second commit. |
| Result | Publish persistence.RESULT_SHA256.json after all barriers. Different parent/scope/collision is 70. |

## 5. Complete native ABI

All native binaries are static and validate argc, identities, exact FD profile, empty signal mask/default handlers/no alt stack, CWD slash, and clean environment before side effect. Extra descriptors, path operands, environment authority, shell input, NSS, and network are forbidden.

| Executable | Exact argv | Authority |
|---|---|---|
| Entry | launch auth-probe NONCE; launch run TOKEN; launch recover TOKEN; auth-probe NONCE; run TOKEN; recover TOKEN; publish-recovery-namespace TOKEN ATTESTATION_SHA256 SELECTION_SHA256; clear-normal TOKEN REQUEST_SHA256; clear-manual-safety TOKEN REQUEST_SHA256; controlled-child TOKEN; lock-guardian TOKEN | Launch controlled UID; auth/run/recover credential-root; publish/clear direct root; child/guardian supervisor. |
| Payload | run TOKEN; recover TOKEN; clear-normal TOKEN REQUEST_SHA256; clear-manual-safety TOKEN REQUEST_SHA256 | Exact entry handoff and Bash -p only. |
| Copier | seal-selectors; seal-stage; seal-final-handoff; verify-gradle-seed-copy | Fixed FDs, no path/flag. |
| Signer | no args | Fixed FDs only. |

| Profile | FD 0-2 | Fixed FDs | Others |
|---|---|---|---|
| launch | validate TTY then 0 dev-null, 1/2 terminal | 3 sealed launch-account-proof/v1 | Close |
| root auth-probe | dev-null, terminal out/err | 3 proof | Close |
| root run/recover and payload | dev-null, terminal out/err | 3 proof, 4 sealed handoff, 5 admission OFD, 6 lifecycle OFD, 7 token claim OFD | Close |
| direct root publication/clearance | all dev-null | no 3; 5 admission, 6 lifecycle, 7 claim | Close |
| controlled-child | 0 dev-null, 1/2 bounded pipes | 3 launch memfd, 8 token lock, 9 readonly nonlocking authorization, 10 run-root dir, 11 release, 12 CLOEXEC status, 13 post-exec gate, 14 seed-ready, 15 seed-release | Close |
| guardian | all dev-null | 3 true lock, 4 cgroup dir, 5 state dir, 6 registration/release read, 7 ack write, 8 liveness read | Close |

FD9 is distinct readonly lock description: it may demonstrate contention but cannot unlock, write, truncate, duplicate authority, or outlive guardian FD3. FD4-7 never reach controlled code.

| Copier mode | FD3 | FD4 | FD5 | FD6 | Bound |
|---|---|---|---|---|---|
| seal-selectors | stage dir | controlled hidden selector dir | sealed selector spec | result write | exact 3 selectors; <=16384 result |
| seal-stage | stage dir | controlled hidden bundle candidate | sealed approval/inventory spec | result write | exact 5 assets/inventory; <=16384 |
| seal-final-handoff | run-root dir | controlled hidden final candidate | sealed run-root/transition/approval spec | result write | existing handoff caps, 5m/source, 30m total; <=65536 |
| verify-gradle-seed-copy | run-root dir | dev-null | sealed seed/transition spec | result write | existing 3GiB/16000-entry; <=16384 |

Copier 0-2 are dev-null. It validates FD5, permanently drops UID/GID, clears groups/capabilities/securebits, sets no-new-privs, and opens only below FD3 with RESOLVE_BENEATH, RESOLVE_NO_SYMLINKS, RESOLVE_NO_XDEV. Parent reopens/validates FD4 before root state.

| Channel | Exact frame/cap/EOF |
|---|---|
| signer FD3 | DOMAIN then u64be payloadLength then PAYLOAD; domain is exact ASCII schema string without terminator; READY/claim <=65536, other <=16384; exact EOF. Signer FD4 exactly 64 raw Ed25519 bytes then EOF; FD5 canonical root-only private key. |
| entry handoff/copier FD6 | u32be JSON length then ordered compact JSON; one <=65536 frame then EOF; parent accepts only exit 0. |
| child FD11 | u32be JSON length then revoman-cs2a-child-release/v1 JSON <=16384 then EOF. |
| child FD12 | u8 kind, u32be length, body: 1 PROOF post-drop-proof/v1 JSON <=16384; 2 FAIL_PRE and 3 FAIL_EXEC are exactly u16be stage plus u16be errno. Only FAIL_PRE+EOF, PROOF+EOF, PROOF+FAIL_EXEC+EOF. |
| child FD13/15 | Exactly 32 raw digest bytes then EOF; short/extra/mismatch prevents work/Gradle. |
| child FD14/guardian FD6/7 | u32be JSON length then ordered JSON <=16384 then EOF: seed-copy-ready or guardian registration/release/ack. |

| Native exit | Meaning |
|---:|---|
| 0 | Exact mode completed; exec success still requires endpoint/EOF observation. |
| 64 | argv/mode/token/SHA grammar or FD-number violation. |
| 65 | FD identity, credential/profile, seal, namespace, process state, or provenance violation. |
| 66 | Bounded source/inventory/signature/cross-link rejection. |
| 70 | Syscall, fsync, timeout, or kernel-primitive failure. |

Payload terminal status is never remapped: after claim/session, durable transition/receipt status remains authority.

## Closure

Unresolved semantic decisions: **0**. Remaining explicit approval of these bytes, paths, caps, and statuses is governance, not a TBD. No tracked file or Git state changed.
