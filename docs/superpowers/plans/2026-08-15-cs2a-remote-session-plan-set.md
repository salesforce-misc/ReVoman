# CS2a Host-Neutral Remote Session Implementation Plan Set

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved host-neutral CS2a remote-session protocol, prove its security and
recovery properties, and only then run the controlled baseline-versus-candidate measurement.

**Architecture:** The work is split at durable trust boundaries. Native bounded primitives come
first; the native entry and admission ledger consume them; the remote controller adds the workload,
recovery, and signed receipt state machine; the local operator treats SSH as an untrusted byte
channel and verifies signed evidence; release gates freeze one implementation SHA; the final plan
provisions and measures without changing that SHA.

**Tech Stack:** Kotlin 2.4.20-Beta2, Java 21, Gradle 9.7, Bash, POSIX/Linux C, Ed25519, OpenSSH,
Kotest/JUnit 5, jq, ShellCheck, ASan, UBSan, libFuzzer-compatible property corpora, Qodana, Antora,
and disposable Linux/root integration tests.

## Global Constraints

- The normative design is
  `docs/superpowers/specs/2026-08-15-cs2a-remote-session-design.md`, approved at source SHA-256
  `74ebc845eeff35d95fe2b7fa8f49f7d72aa4f6cd98795b63b1a11788d6cc2333`. If implementation
  reveals a design defect, stop, amend and reapprove the design, and regenerate affected plan
  steps; do not silently weaken an invariant.
- The user approved those exact bytes on 2026-08-15. Their embedded pre-approval status line is
  historical metadata in the frozen snapshot; this plan-set record is the approval record, so do
  not edit the design merely to change that line and thereby invalidate its reviewed digest.
- Preserve the failed attempt at commit `a18ab6f8c805dddaec684f5b4bd3fdc23c4af183` byte-for-byte.
  Never rewrite, delete, or reinterpret its evidence.
- Do not change workloads, baseline SHA `83f3cd70`, adapters, sample counts, statistical algorithms,
  thresholds, or production API behavior in this plan set.
- Exactly fourteen checked-in files form the security-critical source inventory: the thirteen
  operator/native/recipe files listed in the design plus
  `ReceiptSignatureVerifier.kt`. Adding another production helper or schema requires a design
  amendment; test fixtures and corpora remain outside the production inventory.
- Checked-in production files contain no host name, account name, home, UID/GID, repository path,
  JDK path, SSH key/fingerprint, policy digest, or approval value. Host facts enter only through the
  closed CLIs or provisioned root-owned configuration defined by the design.
- No staged or controlled-UID-writable byte may solicit or receive a privilege credential. The
  human invokes only the separately provisioned root-owned native launch entry from the approved
  clean console/TTY/namespace context.
- SSH authenticates transport, not root evidence. Collection trusts only a locally verified,
  domain-separated Ed25519 envelope and its exact commit record.
- Every mutation slice starts RED, implements the smallest GREEN behavior, runs the focused gate,
  and commits separately. Keep the worktree clean between tasks and never mix implementation,
  provisioning, measurement, evidence, or report commits.
- Run all native/root scenarios on disposable Linux. Do not attempt them on macOS and do not use a
  real benchmark host until the exact implementation SHA passes Plan 5.
- A Critical or Important review finding blocks the dependent plan. Fix it in a new commit and
  repeat all affected fixed-range reviews and gates.

## Execution Granularity

The task headings are ownership and commit boundaries, not single worker-sized actions. Before
dispatching a task, expand each comma-separated mutant, schema field group, durability barrier, and
closed status combination into one 2-5 minute micro-cycle in the task checklist or its TSV corpus:

1. name the exact test file, case ID/parameter ID, production member, and focused command;
2. add one failing assertion and run that focused command to record RED;
3. implement only the behavior needed by that case and rerun the same command to GREEN;
4. run the owning task's aggregate gate after the last micro-cycle; and
5. review and commit only the file list named by that task.

Do not dispatch a whole task with “implement the design” as its working instruction. A worker must
receive the relevant frozen schema/FD map/state row from the approved design, the concrete test
case IDs it owns, and the exact command that proves completion. `TBD`, placeholder schemas, inferred
FDs, or one test name standing in for multiple unexecuted parameter IDs block implementation.

## Dependency Order

| Order | Plan | Produces | May run in parallel |
|---|---|---|---|
| 1 | [Native bounded primitives](2026-08-15-cs2a-native-bounded-primitives.md) | Copier, signer, verifier, deterministic recipes | No |
| 2 | [Native entry and admission](2026-08-15-cs2a-native-entry-and-admission.md) | Credential-safe entry, installer, claim/global state | No |
| 3 | [Remote orchestration and receipts](2026-08-15-cs2a-remote-orchestration-and-receipts.md) | Controlled workload, recovery, receipt/READY fixtures | No |
| 4 | [Host-neutral operator and collection](2026-08-15-cs2a-host-neutral-operator-and-collection.md) | Prepare/collect/recover/archive CLI | Fixture work may start after Plan 1; integration waits for Plan 3 |
| 5 | [Security and release gates](2026-08-15-cs2a-security-release-gates.md) | Reviewed immutable implementation SHA | No |
| 6 | [Controlled measurement and evidence](2026-08-15-cs2a-controlled-measurement-and-evidence.md) | Immutable A/A and before/after decision | No |

## Design Coverage Map

| Design section | Owning plan |
|---|---|
| Goal, trigger, scope, host-neutral interfaces | Plans 4 and 6 |
| Authenticated host-runtime configuration | Plans 2 and 3 |
| Root trust anchor, preparation, bundle publication | Plans 1, 2, and 4 |
| Root remote-session orchestration | Plan 3 |
| Supervisor finalization and recovery | Plan 3 |
| Immutable completion receipt | Plans 1 and 3 |
| Privilege-free collection and persistence | Plan 4 |
| Validation and performance classification | Plans 4 and 6 |
| Failure, recovery, and security invariants | Plans 2, 3, and 5 |
| TDD and mutation contract | Plan 5, with focused subsets in Plans 1-4 |
| Verification and controlled measurement | Plans 5 and 6 |

## Plan-Set Acceptance

- [ ] Each plan commit exists in dependency order and its focused commands report success.
- [ ] The exact fourteen-source inventory is machine-checked from a detached implementation
  checkout before preparation.
- [ ] Plan 5 records one clean reviewed implementation SHA and proves all release binaries derive
  from it reproducibly.
- [ ] Plan 6 uses that unchanged SHA, creates one unique token per attempt, never reuses a claimed
  token, permits a prepared/unclaimed same-token retry only through the reapproved native authority,
  preserves every result, and stops after non-PASS A/A.
- [ ] No performance conclusion is written until signed archive integrity, terminal-matrix
  eligibility, and the existing classifier all succeed.

---

After this plan set is committed, choose one execution mode:

1. **Subagent-Driven (recommended):** use `superpowers:subagent-driven-development`, one fresh
   worker per task, with review after each commit.
2. **Inline Execution:** use `superpowers:executing-plans` and execute the plans serially with the
   same commit and review boundaries.
