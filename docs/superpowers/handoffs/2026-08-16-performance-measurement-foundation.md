# Fresh Implementation Handoff — Performance Measurement Foundation

This document is the prompt for a fresh implementation session. The coordinating agent must treat
the committed design and implementation plan named below—not prior chat—as the source of truth.
Do not redesign the approved system unless implementation evidence proves the design impossible;
raise that as a decision gate before changing the specification.

## 1. Repository and provenance

- Working directory: `/Users/gopala.akshintala/orca/workspaces/revoman-root/perf`
- Branch: `overfullstack/perf`
- Starting code SHA: `debc503e015e6e724933ab5b24900d4167cabe59`
  (`docs: approve performance design and add implementation plan`)
- Root-cause audit SHA: `009bc8f4c1fe9fb7d393036616a3c3b6cd787aca`
- Expected implementation-session worktree status: clean; after this handoff is committed,
  `git status --short` must print no entries.

The handoff document itself is a documentation-only descendant of the starting code SHA. At the
beginning of the implementation session, require all of the following:

```bash
cd /Users/gopala.akshintala/orca/workspaces/revoman-root/perf
test "$(git branch --show-current)" = overfullstack/perf
git merge-base --is-ancestor debc503e015e6e724933ab5b24900d4167cabe59 HEAD
test "$(git rev-list --count debc503e015e6e724933ab5b24900d4167cabe59..HEAD)" -eq 1
test "$(git diff --name-only debc503e015e6e724933ab5b24900d4167cabe59..HEAD)" = \
  docs/superpowers/handoffs/2026-08-16-performance-measurement-foundation.md
test -z "$(git status --short)"
shasum -a 256 \
  docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md \
  docs/superpowers/plans/2026-08-16-performance-measurement-foundation.md
```

The required hashes are:

```text
811b59a8354806b59af1004646da309b6ec68766c913cd6a0b2a4fde4d957558  docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md
ebff56fa23d72af6cd9f76fecdcc7435bf1fadf52d04097d0cbd39b590a49540  docs/superpowers/plans/2026-08-16-performance-measurement-foundation.md
```

Those are the Wave 0 starting hashes. The authorized 2026-08-17 container-finalizer protocol
revision replaces them for continued execution with:

```text
8b813ce56549a1515a6d4a03c320b07db6e406ccebd246095bef330db3b35c18  docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md
2458bb077b63c8384556c7ba20058be87c07d7808f1ff0c0a103012da552c886  docs/superpowers/plans/2026-08-16-performance-measurement-foundation.md
```

Stop if the branch, ancestry, cleanliness, or document hashes do not match. Preserve any unexpected
user changes and ask for direction; do not reset or overwrite them.

## 2. Required reading and operating skills

Before editing, read these files completely:

1. `AGENTS.md`
2. `DEVELOPMENT.md`
3. `STYLE.md`
4. `docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md`
5. `docs/superpowers/plans/2026-08-16-performance-measurement-foundation.md`

Use these skills and tools during implementation:

- `superpowers:executing-plans` for the approved checkpointed plan.
- `orchestration` for the supervised Run/Task/Dispatch DAG described below.
- `superpowers:test-driven-development` for every feature or fix.
- `codebase-design` for module and ownership decisions already bounded by the spec.
- `ide-index-mcp` with the `idea` and `intellij-index` MCP servers for symbols, definitions,
  references, navigation, rename, and refactoring. Do not replace indexed navigation with broad
  text search when the IDE index can answer the question.
- `superpowers:systematic-debugging` and `jetbrains-debugger` whenever runtime behavior, a test
  failure, a benchmark failure, or an unexpected result must be investigated. Do not guess at a
  runtime cause.
- `superpowers:verification-before-completion` before every completion claim.
- `superpowers:requesting-code-review` and `code-review` at the final review gate.

Read each selected skill's current instructions before acting. If an expected IDE/debugger MCP is
unavailable, report the limitation and use the safest read-only fallback; do not silently pretend
the requested tool was used.

## 3. Objective and authority boundary

Implement the approved 17-task plan in full. First make performance measurement trustworthy, then
freeze a pre-fix baseline, write failing ownership tests, make the one approved breaking production
fix, and measure the compatible candidate. Stop after ranking the next measured hotspot; do not
implement a second optimization.

Backward compatibility is **not required** for the resource-ownership cleanup. Remove these
ambiguous public raw-source APIs outright, update the API record, and add no deprecated aliases or
adapter layer:

- `bufferFile(String)`
- `bufferFile(File)`
- `bufferInputStream(InputStream)`
- `bufferV3Definition(String)`

Retain value-reading APIs, keep caller-supplied `InputStream` values caller-owned, close every
library-owned per-read source, and preserve the process-lifetime cached ZipFS contract.

The only production optimization in this tranche is that resource-ownership correction. Do not
change progress synchronization, environment/scope synchronization, polling, logging, reporting,
or file sinks. Those are hypotheses to measure or defer, not authorization to edit them.

Do not push, open a PR, dispatch GitHub workflows, change repository settings, or publish external
artifacts without a new explicit user request.

## 4. Confirmed root causes and reproduction

The following are confirmed at audit SHA
`009bc8f4c1fe9fb7d393036616a3c3b6cd787aca`:

1. The generated JMH artifact is a flattened jar of roughly 92 MiB. It omits
   `Multi-Release: true`, includes test classes and test dependencies, breaks Graal/Truffle and
   Log4j provider semantics, and fails `jar --validate`.
2. A broken JMH invocation can emit no result rows while Gradle reports `BUILD SUCCESSFUL` and exits
   zero. JMH's default fail-open behavior and the lack of explicit row validation make the result
   unusable as evidence.
3. The current JMH Gradle configuration does not set a fail-closed contract or exclude tests.
4. Library-owned path reads leak file descriptors until GC. In the verified probe, 200 reads moved
   the count from 10 to 210; it returned to 10 only after explicit GC.
5. The other audited paths are plausible bottlenecks only. They may be ranked only if the frozen
   workload and GC/JFR summaries exercise and support them.

The canonical exact reproduction commands and expected outputs are frozen in the approved design:

- `Reproduction commands` → `Invalid, fail-open JMH artifact`
- `Reproduction commands` → `File-descriptor leak`

Retrieve the exact committed text without copying or paraphrasing it:

```bash
git show debc503e015e6e724933ab5b24900d4167cabe59:docs/superpowers/specs/2026-08-16-performance-measurement-foundation-design.md \
  | sed -n '1489,1679p'
```

If live reproduction is needed, run those commands verbatim from a disposable detached worktree at
the audit SHA, never by moving or dirtying the implementation checkout:

```bash
AUDIT_SHA=009bc8f4c1fe9fb7d393036616a3c3b6cd787aca
REPRO_PARENT="$(mktemp -d "${TMPDIR:-/tmp}/revoman-audit.XXXXXX")"
REPRO_WORKTREE="$REPRO_PARENT/repo"
git worktree add --detach "$REPRO_WORKTREE" "$AUDIT_SHA"
cd "$REPRO_WORKTREE"
```

Expected evidence is exact in kind even when absolute descriptor counts vary:

- Gradle/JMH default path exits `0`, prints `BUILD SUCCESSFUL`, and yields zero measurement rows.
- The direct default JMH run exits `0` with JSON `[]`; `-foe true` exits nonzero.
- `jar --validate` exits nonzero and the manifest lacks `Multi-Release: true`.
- Repeated reads produce approximately one additional descriptor per read until GC.

Do not use the broken JMH output as a baseline, comparator input, or optimization claim.

## 5. Runtime and privilege contract

The claim-bearing V1 profile is the M4 Max Mac running Docker Desktop through the explicit
`desktop-linux` context and a pinned `linux/arm64` child image:

```text
docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e
```

The authorized pin has this verified identity:

- platform `linux/arm64/v8`, with `uname -m` reporting `aarch64`;
- OCI config digest
  `sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c`;
- `openjdk version "21.0.11" 2026-04-21 LTS`,
  `OpenJDK Runtime Environment Temurin-21.0.11+10 (build 21.0.11+10-LTS)`, and
  `OpenJDK 64-Bit Server VM Temurin-21.0.11+10 (build 21.0.11+10-LTS, mixed mode, sharing)`;
- Java executable `/opt/java/openjdk/bin/java`, SHA-256
  `1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b`;
- `/usr/bin/sh`, `/usr/bin/tar` (GNU tar 1.35), `/usr/bin/sha256sum`
  (GNU coreutils 9.4), and `/usr/bin/mv` (GNU coreutils 9.4); and
- source Dockerfile
  `https://github.com/adoptium/containers/blob/df6138afaf1b564116e895b0acd51d70e11cd996/21/jdk/ubuntu/noble/Dockerfile`.

The image entrypoint and identity probe both pass with network disabled, a read-only root,
UID/GID `10001:10001`, all capabilities dropped, `no-new-privileges`, CPUs `0-3`, memory and
memory-swap `6 GiB`, and PID limit `512`.

The frozen measured limits are CPUs `0-3`, memory `6 GiB`, memory-swap `6 GiB`, PID limit `512`, a
declared non-root UID/GID, read-only root, `cap-drop=ALL`, `no-new-privileges`, and only declared
write points. Only image acquisition and `freeze` may use network access. The preparation, timed,
scrubber, and finalizer containers are networkless and must use `--network none --pull=never` after
digest verification.

The authorized finalizer revision validates arguments/output shape without writes or Docker,
validates adapter provenance before Docker, verifies the exact context/runtime/runner/finalizer and
`/usr/bin/mv` inventory, and only then reserves output. Publication runs inside that constrained
finalizer as `/usr/bin/mv -nT --no-copy -- SOURCE DEST`, with postconditions that turn a no-clobber
skip into nonzero failure. Failures before finalizer verification publish no artifact; failures
after verification and reservation retain the full sanitized `INVALID`-bundle contract.

GitHub `ubuntu-24.04-arm` runs the same ARM child image for structural canaries and optional manual
diagnostics. Hosted timings are never claim-bearing. Native macOS, `gopalaaksh-wsl3`, x86, a future
VM, Colima, a persistent self-hosted runner, and amd64 emulation are not dependencies.

Before the containerized adapter exists, the real JDK 21 on this Mac is:

```text
/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn
```

Bare `java` may resolve to a corporate guidance wrapper. Export the directory above as `JAVA_HOME`
and pass it as `-Dorg.gradle.java.home` exactly as the plan specifies.

The supported performance `freeze`, `canary`, `capture`, `compare`, and `campaign` commands must not
require a password, `sudo`, `dzdo`, `osascript`, host-native Java or Gradle, a Docker socket mount, a
home mount, secrets, or privilege escalation. The repository's separate developer-side Gradle and
Qodana gates continue to use the pinned host JDK above. If an unplanned privileged action appears
necessary, stop promptly and give the user the exact command and reason; do not wait on an
interactive password prompt. Never weaken Docker, workflow, action, image, token, or Qodana
security to make a gate pass.

Do not treat earlier conversational permission to kill arbitrary interference as authority to bypass
the approved design. The adapter may stop only explicitly allowlisted, user-owned processes or
containers and must restore only the state it recorded. An unknown process, container, VM, adapter,
or sustained prohibited resource event fails qualification or invalidates the campaign according to
the frozen policy.

### CI and public-repository trust boundary

- Automatic CI uses GitHub-hosted `ubuntu-24.04-arm` for correctness and the structural canary,
  uploads sanitized diagnostics unconditionally, discards numeric timing, and has a 90-minute job
  bound.
- The manual hosted diagnostic is `workflow_dispatch` only, has a 240-minute bound, and must fail in
  its first step unless it is running the exact trusted `master` ref with full reachable baseline
  and candidate SHAs. It freezes both, runs exactly one sealed canary against the candidate through
  `finalize-diagnostic`, rejects PR refs, and never runs a hosted campaign or uses
  `pull_request_target`.
- Every changed workflow sets explicit least-privilege permissions. Checkout is credentialless with
  `persist-credentials: false`; benchmark build/timed containers receive no GitHub token, secret,
  OIDC credential, Docker socket, or home mount.
- Action references are reviewed full commit SHAs. Qodana's OCI index and selected platform child
  are immutable digests. PR Qodana is secretless and read-only; a trusted `master` push scopes the
  Cloud token to the scan step and grants only the required SARIF permission.
- Neither automatic CI nor the manual hosted canary may publish a Mac performance claim.

## 6. Recommended orchestration model

Yes: use one main coordinating agent with bounded subagents. This change is broad enough to benefit
from specialists, but the measurement chain is too stateful for a large parallel implementation.
Use one coordinator, at most two concurrent write workers, and at most three concurrent read-only
reviewers. Most waves need only one writer plus one reviewer.

The coordinator is the sole owner of:

- the active branch, integration order, plan checkboxes, and final commit graph;
- cross-cutting Gradle wiring and any file not explicitly delegated;
- protocol-freeze identity and the baseline artifact ledger;
- Task 13 baseline/calibration and Task 16 A1/A2/B campaign execution;
- quiet-host reservation, Docker campaign lifecycle, evidence promotion, and final publication;
- decisions to invalidate/reacquire evidence;
- GitHub authorization boundaries and the final user report.

Workers receive one bounded plan task, an exact base commit, an allowed path set, forbidden paths,
required RED/GREEN commands, and a completion contract. Workers must not edit the spec, plan,
protocol-freeze ledger, baseline evidence, or another worker's paths. Every worker reports files,
commands and statuses, commit SHA when applicable, deviations, and blockers.

### Execution waves

| Wave | Plan tasks | Ownership and concurrency | Gate |
|---|---|---|---|
| 0. Orient | Read-only repository and tool verification | Coordinator plus up to three read-only specialists; no edits | Clean checkout, exact document hashes, JDK/IDE/Orca availability |
| 1. Foundation | Task 1 → Task 2 | One runner writer, serial; settle CLI, exit, JSON, schema, and identity interfaces | Focused runner/schema tests green; coordinator review and commit |
| 2. Build boundary | Tasks 3 and 4, then Task 5 | Tasks 3 and 4 may run in two isolated child worktrees because their path sets are mostly disjoint; coordinator integrates both and owns serial Task 5 | Adapter and validator suites rerun together; classpath-preserving **fixture** distributions verify. Do not assemble the real root distribution yet |
| 3. Evidence engine | Task 6 → Task 7 → Task 8 → Task 9A → Task 9B → Task 9C | Serial, one writer at a time. Task 9A seals private diagnostic captures, 9B computes the private campaign, and 9C owns publication/recovery/integration. Each slice commits and receives an independent read-only review before the next writer starts | Capture fail-closed, comparator vectors deterministic, campaign order and atomic finalization green; no final Task 9 behavior is deferred |
| 4. Protocol completion | Tasks 10 and 11, then Task 12 | Tasks 10 and 11 may be authored in isolated child worktrees; integrate Task 10 first and Task 11 second. The coordinator—not the worker—runs Task 11's first live root-distribution canary after both are integrated, then owns cross-cutting Task 12 | Mac policy, V3 canary, CI/security, ABI scaffolding, and all Tasks 1-12 green at one clean SHA; independent protocol/evidence/CI reviews are clean before Task 13 |
| 5. Baseline freeze | Task 13 | Coordinator only; no other agents run builds, Docker, Qodana, profilers, or host-heavy commands | Frozen protocol and baseline sealed. Structural/calibration errors stop; a schema-valid 40-fork A/A miss is retained as diagnostic-only and the correctness/TDD tranche continues without a claim for that profile |
| 6. Production TDD | Task 14 → Task 15 | One ownership writer. Task 14 is a combined test-only RED commit; Task 15 is the single breaking implementation | Expected RED proven, then focused/full correctness and exact ABI removal green |
| 7. Claims | Task 16 | Coordinator only during acquisition. Read-only evidence reviewers start only after bundles seal | Candidate reuses baseline harness; one uninterrupted A1→A2→B campaign; evidence and ranking sealed |
| 8. Acceptance | Task 17 | Coordinator runs serial host-heavy gates; up to three independent read-only reviewers inspect the final SHA/evidence | Every Task 17 command and all approved acceptance criteria pass; no second optimization |

### Why this split

- Tasks 1 and 2 define types used everywhere; parallel implementation before those interfaces settle
  creates churn rather than speed.
- Tasks 3 and 4 are the first safe parallel pair. Task 5 is their integration boundary.
- Tasks 6 through 9 form one evidence DAG: capture precedes comparison, comparison precedes campaign,
  and campaign precedes finalization. Keep them serial. Within Task 9, keep 9A, 9B, and 9C as
  separate single-writer slices with a writer/reviewer handoff after each commit: 9A returns only
  verifier-accepted private diagnostic paths, 9B must reverify those paths before arithmetic and is
  the only canonical-strength computation slice, and 9C publishes immutable outputs without
  recomputing evidence or choosing strength.
- Tasks 10 and 11 are the second safe parallel pair. Task 12 deliberately rejoins runtime, workload,
  Gradle, CI, security, documentation, and ABI concerns.
- Tasks 13 and 16 are experiments, not ordinary builds. Splitting their steps across workers would
  destroy the same-session and atomic-publication guarantees.
- Tasks 14 and 15 are one RED/GREEN ownership seam. A single writer avoids partial lifecycle rules
  across loaders.

### Worktree and command isolation

Agents in the same worktree share source files and generated build state. Therefore:

- Use `--worktree current` only for one exclusive writer or read-only reviewers.
- Use Orca child worktrees for the two explicitly parallel write pairs, Tasks 3/4 and Tasks 10/11.
  Each starts from the coordinator's clean integration commit, runs setup, owns disjoint paths, and
  produces one reviewable task commit.
- The coordinator integrates child commits one at a time, resolves no speculative overlap, and
  reruns the combined gate on the integrated SHA.
- Never run Gradle, Docker, Qodana, JFR, or other host-heavy commands concurrently with a controlled
  campaign. From quiet-host lock acquisition through final publication, every other worker must be
  idle.
- Only the campaign owner writes a staging/output target. Finalization, recovery, and atomic rename
  are single-writer operations.

### Orca supervision protocol

Use Orca's real Run/Task/Dispatch lifecycle so task ownership and completion are durable. Generic
chat-only subagents do not count as Orca orchestration provenance.

At the fresh session start:

```bash
orca status --json
orca skills get orchestration
orca orchestration run-create \
  --objective "Implement the approved ReVoman performance measurement foundation plan through Task 17" \
  --json
```

Create only the active wave's tasks; do not pre-create a 17-deep dependency chain. Start all
independent tasks in a wave before waiting. Prefer the composed worker command:

```bash
orca orchestration task-create --spec "<bounded task brief>" --json
orca orchestration worker-start \
  --task <task_id> \
  --worktree current \
  --agent codex \
  --json
```

For one of the two approved parallel write pairs, use a child worktree and setup:

```bash
orca orchestration worker-start \
  --task <task_id> \
  --worktree new-child \
  --name <bounded-task-name> \
  --agent codex \
  --setup run \
  --json
```

Wait for `worker_done`, `escalation`, or `question`, process the complete delivery, then either reuse
the exact worker for an immediate follow-up or release it before acknowledging and waiting again:

```bash
orca orchestration check \
  --wait \
  --types worker_done,escalation,question \
  --timeout-ms 900000 \
  --json
orca orchestration worker-release --dispatch <dispatch_id> --json
orca orchestration check \
  --ack <delivery_id> \
  --wait \
  --types worker_done,escalation,question \
  --timeout-ms 900000 \
  --json
```

A timeout is a liveness checkpoint, not a failure. Inspect the tracked dispatch before retrying.
Never stop or replace a worker solely because it is busy or has not completed within one wait
window.

Use this worker-brief shape for each plan task:

```text
Implement approved plan Task <N> only.

Base commit: <exact 40-hex SHA>
Read completely: AGENTS.md, relevant spec sections, and the full Task <N> plan section.
Allowed paths: <exact path list from the plan and coordinator>
Forbidden paths: spec, plan, baseline/evidence ledger, protocol-owned paths outside this task,
and all unrelated production code.

Use ide-index-mcp with idea/intellij-index for navigation and refactoring. Use test-driven-
development. If any failure or runtime behavior is unexpected, use systematic-debugging and the
JetBrains debugger before proposing a fix.

Run the task's exact RED command and record the expected failure. Implement only the approved
scope, run the exact GREEN/focused/broader commands, inspect git diff, and commit one coherent task
checkpoint. Do not push or dispatch workflows.

Report: outcome; commit SHA; files changed; RED and GREEN commands with statuses; broader gate
statuses; deviations; blockers; and whether any protocol-owned byte changed.
```

The coordinator must verify each report against the diff and rerun the task's combined gate before
marking its plan checkbox complete. A worker's success statement is not verification evidence.

## 7. Mandatory checkpoint and invalidation rules

1. **Foundation gate:** Tasks 1-2 are committed and green before dependent worker APIs are frozen.
2. **Distribution gate:** Tasks 3-5 are integrated and the classpath-preserving TestKit fixture
   distributions are structurally valid before capture work is accepted. Task 5 must not assemble
   or validate a real root distribution; the first real root distribution gate belongs to the
   committed Task 11 implementation.
3. **Evidence gate:** Tasks 6-9 pass fail-closed, known-answer, ordering, privacy, checksum, and
   crash-recovery tests before host qualification or real workload publication.
4. **Protocol gate:** Tasks 1-12 are committed and green at one clean SHA before any baseline is
   frozen. At that SHA, obtain independent architecture/spec, measurement/evidence-lifecycle, and
   CI/runtime-security reviews. Resolve every verified P1/P2 finding and rerun affected gates before
   recording the protocol SHA or starting Task 13.
5. **Baseline gate:** Task 13 is coordinator-only. Record the protocol SHA and hashes, validate and
   seal all A/A evidence, and preserve the pre-fix distribution before any production edit. A
   non-`5` error or structurally invalid calibration blocks the tranche. A valid miss after the
   declared 40-fork escalation makes that profile diagnostic-only but does not block the ownership
   fix or correctness acceptance.
6. **Protocol invalidation:** After Task 13, any change to
   `buildSrc/**/performance*`, `scripts/performance/run`, `config/performance/**`, `src/jmh/**`, the
   Gradle wrapper, benchmark/runtime dependencies, or JMH/Kotlin/JDK versions invalidates all
   baseline evidence. Discard it, commit the protocol revision, and restart Task 13.
7. **RED gate:** Task 14 may modify tests and test infrastructure only. The combined deterministic
   ownership suite must fail for the intended reason on the pre-fix implementation.
8. **GREEN gate:** Task 15 makes the one approved production change and exact API break. All focused
   ownership tests, ordinary tests, integration tests, API/ABI checks, JMH compilation, and build
   gates must pass.
9. **Candidate identity gate:** Candidate freeze must use `--harness-from` the preserved baseline
   distribution. Rebuilding runner, benchmark, schemas, profiles, policies, or dependencies makes
   the comparison invalid.
10. **Campaign gate:** One coordinator owns A1, A2, preconditioning, B, comparison, finalization, and
    publication in one uninterrupted qualified session. If A/A fails, do not run B and do not make
    a claim.
11. **Acceptance gate:** Run Task 17 exactly. If a fix changes a protocol-owned byte or candidate jar
    after measurement, invalidate and reacquire the affected evidence before claiming completion.

Maintain distinct ledger entries for the protocol SHA, pre-fix production SHA, baseline-evidence
commit, RED-test commit, candidate production SHA, and final-evidence commit. Never infer an earlier
identity from the later `HEAD`.

The preserved baseline and candidate distributions are under ignored build output and are therefore
vulnerable to ordinary cleanup. From Task 13 through completion of Task 17, do not run
`git clean -fdx`, `./gradlew clean`, a workspace replacement, or cleanup automation that can remove
them. Verify the baseline recursive checksum before and after Tasks 14 and 15, then verify both
distribution checksums before and after Tasks 16 and 17. If the baseline is lost or changes, restart
Task 13 from the recorded pre-fix commit; never rebuild it from the candidate checkout. If the
candidate is lost or changes after evidence acquisition, refreeze it only through the approved
`--harness-from` path and reacquire every affected campaign before making a claim.

## 8. Verification and acceptance

Every task contains its own exact RED, GREEN, focused, and broader commands. Run them as written and
record command, SHA, exit status, and relevant artifact hashes. In addition, final acceptance must
include all commands in plan Task 17, notably:

```bash
/bin/bash -n scripts/performance/run

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  spotlessCheck

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  -p buildSrc test

./gradlew --no-daemon \
  -Dorg.gradle.java.home=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn \
  test fdProbeTest integrationTest checkKotlinAbi jmhTest \
  compileJmhKotlin jmhClasses build
```

Also require:

- `performanceBenchmarkJar`, `assemblePerformanceDistribution`, and
  `verifyPerformanceDistribution` pass.
- Direct `jmh` and `jmhJar` fail with migration guidance.
- The packaged V3 structural canary passes and the intentionally failing benchmark yields a
  nonzero outer status plus sanitized `INVALID` evidence.
- Privacy, schema, checksum, clean-provenance, exact runtime/JDK/image, and no-privilege gates pass.
- The descriptor probe no longer grows linearly and deterministic ownership tests are green.
- `docker --context desktop-linux info` and Qodana through Docker Desktop pass without Colima or a
  privilege prompt.
- No raw `.jfr` is published or retained.
- Three independent final reviews cover architecture/spec conformance, measurement/evidence
  validity, and CI/runtime security. The coordinator fixes only verified in-scope defects and
  repeats affected gates.
- All 23 acceptance criteria in the approved design are explicitly accounted for.
- Final `git diff --check` passes and the worktree is clean after the last evidence/document commit.

Do not describe a benchmark as improved merely because a point estimate is lower. Use only the
approved comparator classification and conditional uncertainty wording. Hosted numbers are
diagnostic, not comparable to the controlled Mac campaign.

## 9. Relevant source locations

The authoritative full list is `Relevant source locations` in the approved design. Start with:

- `build.gradle.kts` and `gradle/libs.versions.toml`
- `buildSrc/settings.gradle.kts`, `buildSrc/build.gradle.kts`, and proposed
  `buildSrc/performance-runner/**`
- `scripts/performance/run` and `config/performance/**`
- `src/jmh/kotlin/com/salesforce/revoman/benchmark/**` and proposed `src/jmhTest/**`
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt`
- `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt`
- `src/main/kotlin/com/salesforce/revoman/input/ClasspathResolver.kt`
- `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt`
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt`
- `src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpServer.kt`
- `.github/workflows/build.yml`, `.github/workflows/qodana.yml`, proposed
  `.github/workflows/performance-campaign.yml`, and `qodana.yaml`
- `api/revoman-root.api`
- `docs/superpowers/benchmarks/**`

Use indexed definition/reference navigation before changing a public or shared symbol. Confirm every
caller before removing the four raw-source APIs.

## 10. Deferred work and non-goals

Do not add or implement:

- DuckDB, a dashboard, database, result service, automatic baseline promotion, or history service;
- numeric PR gates, scheduled campaigns, paid larger runners, persistent/self-hosted runners, or a
  local polling daemon;
- native-macOS, x86, amd64-emulated, temporary-remote-host, or future-VM claim profiles;
- privileged cache dropping, host tuning, Docker resource reconfiguration, or disabling corporate
  management software;
- progress, environment/scope, polling, logging, report, or file-sink optimizations;
- a general benchmark-fixture framework, public mock-server reset API, shared integration fixture
  source set, ZipFS eviction registry, Shadow jar, repaired flattened fat jar, or alternate evidence
  store;
- compatibility aliases for the removed source-opening APIs;
- claims that Mac Docker results generalize to GitHub, native macOS, x86, production traffic, or a
  later runtime fingerprint.

Step-count scaling, polling, file sinks, and any unexercised audit hypotheses remain `UNMEASURED`.
The final ranking may recommend at most one future optimization; implementing it requires a new
approved design/plan.

## 11. Completion report

At the end of the implementation session, report:

- final branch, SHA, and worktree status;
- task/commit sequence and any approved deviations;
- protocol-freeze SHA and baseline/candidate distribution identities;
- RED and GREEN resource-ownership evidence and the exact public ABI removals;
- cold/warm comparator classifications, conditional uncertainty, and evidence hashes;
- A/A calibration outcome and whether the claim was eligible;
- GC/JFR-derived hotspot ranking with capture IDs/hashes and every `UNMEASURED` hypothesis;
- all verification commands and exit statuses, including Qodana;
- independent review findings and resolutions;
- deferred work and the single optional next optimization;
- any step requiring explicit user authorization.

Stop after that report. Do not push, open a PR, dispatch a workflow, or begin another optimization
unless the user explicitly asks.

## 12. First action in the fresh session

Verify Section 1, read every required document and skill, create one Orca Run, and execute only
Wave 0. Present any genuine drift or blocker before creating implementation tasks. If Wave 0 is
clean, start Wave 1 and follow the approved plan task-by-task.
