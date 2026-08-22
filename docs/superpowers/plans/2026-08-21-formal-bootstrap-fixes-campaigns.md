# Formal Bootstrap Fixes Campaigns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use only the reviewed platform merged to master to produce defensible cold/warm formal evidence for the request-bootstrap and lazy-Ajv commit edges.

**Architecture:** Freeze one three-distribution chain from a clean detached merged-master harness, then let the checked-in no-peeking suite execute four ordered same-session A/A-qualified campaigns on the exact controlled Linux/JDK profile. Preserve all outputs, independently recompute the evidence, and publish only immutable evidence and scope-correct conclusions.

**Tech Stack:** Git worktrees, Gradle/JMH, checked-in Kotlin performance runner, controlled native-Linux adapter, deterministic bootstrap comparator, SHA-256/JSON Schema, GitHub pull requests.

**Spec:** `docs/superpowers/specs/2026-08-21-merge-first-performance-workflow-design.md`

## Global Constraints

- Do not begin until a fresh fetch proves both infrastructure merge SHAs are ancestors of `origin/master`.
- The runner worktree is detached, clean, and exactly at the fetched merged-master SHA.
- Treatment worktrees are detached, clean, and exactly at `d343df32d0b258cd5f37ab2606eb773e55b0ea6d`, `9439dc416ca7676c1f501a93924d7d3900f33e16`, and `d42614fa4982d8f960354ba07a2027f84b5ef1bc`.
- Use installed Temurin 21.0.12+8-LTS through its exact checked-in profile; Temurin is the selected runtime, not a platform requirement.
- Use the canonical policy bytes `{"maximumRegressionBudget":0.05,"schemaVersion":"regression-policy-v1"}`.
- Do not alter a profile, threshold, policy, workload, treatment, harness, estimator, or campaign order after candidate observation begins.
- Do not inspect result contents until all four campaigns settle or the suite aborts on an infrastructure exit.
- Continue on result exits `0`, `5`, `6`, and `7`; abort on infrastructure exits `2`, `3`, `4`, and `8`.
- Preserve every valid, invalid, diagnostic, quarantined, partial, and prior artifact; never rerun over an existing output path.
- Existing measurements remain diagnostic and `ClaimEligible=false`.
- No speedup claim is allowed unless new formal evidence satisfies the exact directional gates.
- These incremental campaigns do not gate PR #414, a master-to-`d42614fa4982d8f960354ba07a2027f84b5ef1bc` comparison, or any different commit edge.
- Never change the protected root worktree or its `.idea/kotlinc.xml` modification.
- Use pnpm for every explicit npm package installation.
- Do not push evidence, update treatment PRs, or publish claims until the remote-state gate is explicitly authorized.

---

## File and Artifact Map

- Clean runner worktree: new detached worktree at merged `origin/master`.
- Clean treatment worktrees: new detached worktrees for D1, D2, and D3.
- `config/performance/campaigns/bootstrap-fixes-v1.json`: authoritative chain and campaign order.
- `config/performance/policies/bootstrap-fixes-5pct-v1.json`: authoritative 5% budget.
- `build/performance/formal/bootstrap-fixes-20260821/distributions/{D1,D2,D3}`: role-neutral frozen distributions.
- `build/performance/formal/bootstrap-fixes-20260821/campaigns/{request-cold,request-warm,lazy-ajv-cold,lazy-ajv-warm}`: finalized or preserved campaign outputs.
- `build/performance/formal/bootstrap-fixes-20260821/suite-state.json`: command-order and exit-only state; no result interpretation.
- `build/performance/admission/bootstrap-fixes-20260821/`: separate diagnostic distribution and canary output.
- `docs/superpowers/benchmarks/formal/bootstrap-fixes-20260821/`: reviewed public evidence copied only after independent validation.
- `docs/superpowers/benchmarks/formal/bootstrap-fixes-20260821/REPORT.md`: scope-limited evidence narrative generated from verified bundles.

### Task 1: Admit the Merged Harness and Exact Topology

**Files:**
- Create through Git: four new detached isolated worktrees
- Inspect: merged platform and host-profile commits
- Inspect: protected root worktree

**Interfaces:**
- Consumes: fetched `origin/master`, exact treatment SHAs, PR A/PR B merge SHAs
- Produces: four clean immutable source roots and an admission record

- [ ] **Step 1: Verify protected state and fetch refs**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root rev-parse HEAD
git -C /home/gopala.akshintala/code-clones/work/revoman-root status --short
sha256sum /home/gopala.akshintala/code-clones/work/revoman-root/.idea/kotlinc.xml
git -C /home/gopala.akshintala/code-clones/work/revoman-root fetch origin master overfullstack/perf codex/scripted-lifecycle-request-json-bootstrap-2026-08-21 codex/scripted-lifecycle-lazy-ajv-bootstrap-2026-08-21
```

Expected: protected values match the global constraints and all four exact commits resolve.

- [ ] **Step 2: Prove merge-first ancestry**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0 origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor "${PR_A_MERGE_SHA}" origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor "${PR_B_MERGE_SHA}" origin/master
```

Expected: all commands exit 0. If any fails, stop; no distribution or timing process may run.

- [ ] **Step 3: Create detached worktrees without reusing prior worktrees**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add --detach /home/gopala.akshintala/code-clones/work/revoman-perf-formal-runner-20260821 origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add --detach /home/gopala.akshintala/code-clones/work/revoman-perf-formal-d343-20260821 d343df32d0b258cd5f37ab2606eb773e55b0ea6d
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add --detach /home/gopala.akshintala/code-clones/work/revoman-perf-formal-9439-20260821 9439dc416ca7676c1f501a93924d7d3900f33e16
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add --detach /home/gopala.akshintala/code-clones/work/revoman-perf-formal-d426-20260821 d42614fa4982d8f960354ba07a2027f84b5ef1bc
```

If a proposed path already exists, choose a new timestamped path; never delete or reuse it.

- [ ] **Step 4: Verify exact cleanliness and treatment topology**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-perf-formal-runner-20260821 status --porcelain=v1
git -C /home/gopala.akshintala/code-clones/work/revoman-perf-formal-d343-20260821 status --porcelain=v1
git -C /home/gopala.akshintala/code-clones/work/revoman-perf-formal-9439-20260821 status --porcelain=v1
git -C /home/gopala.akshintala/code-clones/work/revoman-perf-formal-d426-20260821 status --porcelain=v1
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor d343df32d0b258cd5f37ab2606eb773e55b0ea6d 9439dc416ca7676c1f501a93924d7d3900f33e16
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor 9439dc416ca7676c1f501a93924d7d3900f33e16 d42614fa4982d8f960354ba07a2027f84b5ef1bc
```

Expected: all status outputs are empty and both ancestry checks pass.

- [ ] **Step 5: Verify the policy and suite bytes**

```bash
sha256sum config/performance/campaigns/bootstrap-fixes-v1.json config/performance/policies/bootstrap-fixes-5pct-v1.json
test "$(tr -d '\n' < config/performance/policies/bootstrap-fixes-5pct-v1.json)" = '{"maximumRegressionBudget":0.05,"schemaVersion":"regression-policy-v1"}'
```

Record both hashes, the runner SHA, the three treatment SHAs, the runtime-profile SHA, host-profile
SHA, adapter SHA, schema closure SHA, and comparator SHA before proceeding.

### Task 2: Validate the Host Without Observing a Candidate

**Files:**
- Create: a fresh diagnostic qualification/canary output under `build/performance/admission/bootstrap-fixes-20260821/`
- Inspect: exact JDK and host declarations

**Interfaces:**
- Consumes: merged runner, exact runtime/host profiles, installed JDK
- Produces: structural admission evidence only; never a treatment result

- [ ] **Step 1: Recompute the installed JDK closure and anchors**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem verifyPerformanceRuntimeProfile
sha256sum \
  /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem/bin/java \
  /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem/release \
  /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem/lib/modules \
  /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem/lib/server/libjvm.so
```

Expected: the profile regenerates byte-identically and anchor hashes match the spec.

- [ ] **Step 2: Freeze a diagnostic merged-master distribution**

```bash
./scripts/performance/run freeze \
  --treatment-source . \
  --output build/performance/admission/bootstrap-fixes-20260821/distribution
```

Expected: the clean runner worktree is both treatment and harness for this diagnostic-only
distribution. This does not observe either optimization edge.

- [ ] **Step 3: Run the native structural canary**

```bash
./scripts/performance/run canary \
  --runtime-profile temurin-21.0.12-linux-x86_64-v1 \
  --java-home /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem \
  --distribution build/performance/admission/bootstrap-fixes-20260821/distribution \
  --host-id hp-z4-g4-linux-x86_64-v1 \
  --output build/performance/admission/bootstrap-fixes-20260821/structural-canary
```

Expected: exit 0, complete V2 qualification documents, valid checksums, no privacy leak, and
`ClaimEligible=false`. A failed canary blocks the suite but remains preserved.

- [ ] **Step 4: Confirm the machine is quiet and the lock is exclusive**

Review only the preflight/qualification outcome, not any treatment result. Require all checked-in
host gates to pass and prove a second synthetic admission attempt fails on lock contention. Release
the synthetic attempt and confirm no unrelated process was stopped or tuned.

### Task 3: Execute the Frozen Suite Without Peeking

**Files:**
- Create: `build/performance/formal/bootstrap-fixes-20260821/`
- Preserve: every suite output and private state until independent review finishes

**Interfaces:**
- Consumes: clean worktrees from Task 1 and admitted host/runtime from Task 2
- Produces: three distributions and four settled campaign states, or a preserved infrastructure-aborted suite

- [ ] **Step 1: Start one supervised suite command**

```bash
./scripts/performance/run-suite \
  --suite config/performance/campaigns/bootstrap-fixes-v1.json \
  --treatment D1=/home/gopala.akshintala/code-clones/work/revoman-perf-formal-d343-20260821 \
  --treatment D2=/home/gopala.akshintala/code-clones/work/revoman-perf-formal-9439-20260821 \
  --treatment D3=/home/gopala.akshintala/code-clones/work/revoman-perf-formal-d426-20260821 \
  --runtime-profile temurin-21.0.12-linux-x86_64-v1 \
  --java-home /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem \
  --host-id hp-z4-g4-linux-x86_64-v1 \
  --output build/performance/formal/bootstrap-fixes-20260821
```

Do not open campaign JSON, Markdown, JMH, stdout, stderr, or profiler output while this command is
active. Monitor only liveness, current campaign ID, watcher health, and exit state exposed by the
suite supervisor.

- [ ] **Step 2: Let the declared state machine decide every campaign**

For each campaign the existing runner performs fresh A1/A2 at 10 forks, escalates to fresh 20 and
40 fork pairs only when the prior A/A misses, admits when the ratio interval contains 1.0, point
ratio is in `[0.95,1.05]`, and interval width is at most 0.10, and immediately runs B against A2 in
the same lock/watcher session. If 40-fork A/A fails, no B is run.

- [ ] **Step 3: Apply only exit-level continuation rules**

```text
0 = settled result; continue
5 = A/A rejected; preserve and continue
6 = policy regression; preserve and continue
7 = policy inconclusive; preserve and continue
2 = invalid input; preserve and abort suite
3 = preflight/qualification invalid; preserve and abort suite
4 = capture/infrastructure failure; preserve and abort suite
8 = finalization/publication failure; preserve and abort suite
```

Do not interpret whether a settled result is faster or slower until every declared campaign has a
terminal state.

- [ ] **Step 4: Seal the acquisition record**

After the suite exits, make the artifact root read-only to the review process, record recursive
checksums and the exact suite exit/status vector, and copy the complete tree to a second preserved
location if the host policy requires redundancy. Never rerun with the same output path.

### Task 4: Independently Verify Every Evidence Bundle

**Files:**
- Read: sealed suite output
- Create: independent recomputation notes outside the sealed tree
- Do not modify: any sealed distribution or campaign bundle

**Interfaces:**
- Consumes: settled sealed suite
- Produces: an accept/reject decision for each campaign and scoped claim text for accepted evidence

- [ ] **Step 1: Recompute structural integrity**

For D1, D2, and D3, independently validate recursive checksums, canonical JSON, strict schemas,
classpath order, treatment SHA, tree-clean provenance, harness SHA, protocol closure, expected
cells, runtime/profile/policy hashes, and privacy. Prove D2 is the same directory and digest used as
request candidate and lazy-Ajv baseline.

- [ ] **Step 2: Recompute qualification and order**

For each campaign, prove A1 then A2 then B order; correct 10/20/40 escalation; no B after failed
40-fork A/A; complete preflight/watcher/postflight/restoration; unchanged host, JDK, tool, power,
thermal, topology, filesystem, and policy identities; and immediate B in the same locked session.

- [ ] **Step 3: Recompute statistics from per-fork medians**

Run the frozen comparator's known-answer vector, then independently execute its 20,000
deterministic conditional fork-resampling replicates. Recompute point ratio, lower/upper 95% ratio
bounds, interval width, point gain, directional classification, and 5% policy outcome. Require
byte-identical results to the finalized comparison.

- [ ] **Step 4: Apply the claim algebra**

```text
Improvement: upper95Ratio < 1.0
Regression: lower95Ratio > 1.0
Directional inconclusive: otherwise
5% policy PASS: upper95Ratio <= 1.05
5% policy FAIL: lower95Ratio > 1.05
5% policy INCONCLUSIVE: otherwise
```

A profile-level statement requires qualified, canonical, checksum-valid,
`claimEligible=true` evidence. An unqualified optimization speedup statement requires improvement
for both cold and warm campaigns on that same edge. A 5% regression-budget statement requires
policy PASS for both profiles. Mixed outcomes must be reported separately and must not be collapsed
into a speedup or no-regression claim.

- [ ] **Step 5: Use exact scope-correct wording**

For each accepted profile use:

```text
On hp-z4-g4-linux-x86_64-v1 with runtime profile temurin-21.0.12-linux-x86_64-v1,
candidate <FULL_SHA> had candidate/baseline point ratio <R> for <CELL>
(95% conditional fork-resampling ratio interval [<L>, <U>]), corresponding to point gain <G>%,
versus baseline <FULL_SHA> in one same-session A/A-qualified <cold|warm> campaign;
the pre-registered 5% maximum-regression policy outcome was <PASS|FAIL|INCONCLUSIVE>.
```

Replace every bracketed token only with a value from independently verified evidence. If evidence
is invalid or A/A-rejected, report that status and its stable reason; do not substitute diagnostic
numbers.

### Task 5: Prepare an Evidence-Only Review and Hold Publication

**Files:**
- Create after validation: `docs/superpowers/benchmarks/formal/bootstrap-fixes-20260821/REPORT.md`
- Create after validation: immutable public campaign bundles under the same directory
- Modify only after explicit authorization: treatment PR descriptions or comments

**Interfaces:**
- Consumes: independently accepted evidence from Task 4
- Produces: a local evidence-only commit and publication packet; no production/protocol change

- [ ] **Step 1: Create a fresh evidence-only worktree**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root fetch origin master
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add -b codex/perf-bootstrap-formal-evidence-2026-08-21 /home/gopala.akshintala/code-clones/work/revoman-perf-bootstrap-evidence-20260821 origin/master
```

Record `EVIDENCE_BASE_SHA` as this worktree's initial `HEAD`. If the branch or path exists, use a new
timestamped name and preserve the existing one.

- [ ] **Step 2: Copy only public finalized evidence**

Exclude raw JFR, private runtime bindings, absolute paths, hostnames, usernames, worktree paths,
reservation/lock files, operation volumes, credentials, environment dumps, and unpublished
provisional output. Recompute recursive checksums after the copy and require equality with the
public portion of the sealed source.

- [ ] **Step 3: Write the report with explicit evidence classes**

The report lists existing hotspot/local/hosted measurements under `Diagnostic,
ClaimEligible=false`; A/A under `Calibration only`; each accepted finalized campaign under
`Claim-bearing`; and each failed/inconclusive/invalid campaign with its exact status. It states that
the results cover only `d343df32d0b258cd5f37ab2606eb773e55b0ea6d -> 9439dc416ca7676c1f501a93924d7d3900f33e16` and `9439dc416ca7676c1f501a93924d7d3900f33e16 -> d42614fa4982d8f960354ba07a2027f84b5ef1bc`.

- [ ] **Step 4: Commit and prove the evidence change is evidence-only**

```bash
git add docs/superpowers/benchmarks/formal/bootstrap-fixes-20260821
git commit -m "perf(evidence): publish formal bootstrap campaigns"
```

```bash
git diff --exit-code "${EVIDENCE_BASE_SHA}" -- src/main src/test src/integrationTest src/jmh buildSrc scripts config .github
git diff --name-only "${EVIDENCE_BASE_SHA}..HEAD"
```

Expected: only `docs/superpowers/benchmarks/formal/bootstrap-fixes-20260821/` and related report
index documentation differ.

- [ ] **Step 5: Request independent evidence review**

The reviewer redoes schema/checksum/privacy/compatibility/order/statistical/policy checks without
trusting `REPORT.md`, then compares the report wording to the verified values.

- [ ] **Step 6: Stop at the remote publication gate**

Do not push the evidence branch, open an evidence PR, update PR #414, or publish a performance
claim until explicitly authorized. When authorized, publish an evidence-only PR, require review,
and preserve the exact campaign/profile scope in every downstream statement.
