# Merge-First Formal Performance Workflow Design

**Date:** 2026-08-21

**Status:** Approved, with Java-vendor clarification

**Scope:** Architectural performance infrastructure, canonical-host qualification, and formal
evidence acquisition for two completed sandbox-bootstrap optimizations.

## Objective

Land ReVoman's formal performance platform on the current master lineage, add this quiet native
Linux machine as a checked-in canonical measurement host, and then use only the merged platform to
measure these exact treatment edges:

1. Request bootstrap:
   `d343df32d0b258cd5f37ab2606eb773e55b0ea6d` to
   `9439dc416ca7676c1f501a93924d7d3900f33e16`.
2. Lazy Ajv:
   `9439dc416ca7676c1f501a93924d7d3900f33e16` to
   `d42614fa4982d8f960354ba07a2027f84b5ef1bc`.

The formal platform and all necessary measurement support must be reviewed and merged into master
before any claim-bearing comparison runs. Treatment commits do not need the performance
infrastructure in their ancestry.

## Approved Decisions

- Preserve `origin/overfullstack/perf` with a true merge, not a squash or selective replay.
- Keep infrastructure and treatment lineages separate. Build treatment jars from detached exact
  commits and combine them with a harness frozen from merged master.
- Support Java 21 as the platform invariant. Do not hard-code or allowlist a Java vendor.
- Bind every measurement to the complete exact JDK identity declared by its runtime profile.
- Use the installed Temurin 21 distribution for this host's first canonical profile because those
  are the bytes available here, not because Temurin is required by the platform.
- Keep all existing measurements diagnostic with `ClaimEligible=false`.
- Do not describe either optimization as a speedup without new finalized canonical campaigns.
- Preserve every existing worktree and performance artifact.
- Use pnpm for any explicit npm package installation performed as part of this work.

## Verified Starting Topology

```text
009bc8f4c1fe9fb7d393036616a3c3b6cd787aca
├── 40 commits ── e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0
│                 origin/overfullstack/perf
└── master lineage ── a3fb25e95a3d8ef2c7930821073a7214437ab984
    ├── 50ded34b78b416a5523ebb57d1ce77a03e691912
    │   origin/master
    └── 92b5dae92bdb7b08bdefe59bdc2cfd6b65be00c8
        └── d343df32d0b258cd5f37ab2606eb773e55b0ea6d
            └── 9439dc416ca7676c1f501a93924d7d3900f33e16
                └── d42614fa4982d8f960354ba07a2027f84b5ef1bc
```

The protected root worktree remains outside the integration lineage:

```text
HEAD: 47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e
.idea/kotlinc.xml: modified, preserved
SHA-256: c995703f125cf3ad057ffdd509b211bd3a5533c22307a17323fef86cb3c9b694
```

Only these paths changed on both the current-master and formal-platform lineages:

- `DEVELOPMENT.md`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt`

The platform branch makes no changes under `src/main`, `src/test`, or `src/integrationTest` from
its merge base.

## Architecture

### Platform integration

PR A starts from the latest fetched `origin/master` and contains a true two-parent merge whose
second parent is `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0`. The merge preserves the 40-commit
platform history. Follow-up integration commits reconcile current master without adding treatment
code.

The merge keeps master-only V1 performance files automatically. PR A must deliberately retire the
old GitHub timing gate, Docker wrapper, and Python comparator as supported claim paths. Historical
reports and artifacts remain unchanged. Existing component and lifecycle benchmarks remain
diagnostic-only unless named by `config/performance/expected-cells.json`.

Conflict resolution policy:

| Path | Resolution |
|---|---|
| `DEVELOPMENT.md` | Preserve current development guidance and document one formal platform with canonical and diagnostic lanes. |
| `build.gradle.kts` | Preserve current production build behavior and add the formal performance convention, strict JMH gates, ABI validation, and formal tasks. |
| `gradle/libs.versions.toml` | Preserve current-master versions unless a reviewed formal-runner dependency is absent; never downgrade implicitly. |
| `SandboxBenchmark.kt` | Adopt the formal `SandboxCanaryBenchmark`; keep other component/lifecycle benchmarks diagnostic-only. |

`api/revoman-root.api` is regenerated from the merged source and reviewed. The old platform
branch's dump is not accepted merely because it merged cleanly.

### Runtime-binding seam

The current implementation makes OCI fields mandatory and routes host behavior by Mac versus
GitHub-hosted identity. Native Linux creates a second real adapter, so runtime execution needs one
deep module at the adapter/runner seam.

The module's interface is one verified binding. Distribution validation proves only that the
frozen classpath is compatible with Java 21; it never inspects the ambient JVM or stores an
absolute JDK path:

```kotlin
internal data class JavaCompatibility(val majorVersion: Int)

internal data class RuntimeBindingRequest(
  val distribution: VerifiedDistribution,
  val runtimeProfileId: String,
  val privateObservationPath: Path,
)

internal enum class RuntimeBindingProblem {
  PROFILE_NOT_FOUND,
  PROFILE_INVALID,
  PROFILE_HASH_MISMATCH,
  PRIVATE_OBSERVATION_INVALID,
  JAVA_MAJOR_MISMATCH,
  JAVA_IDENTITY_INVALID,
  EXECUTABLE_HASH_MISMATCH,
  RELEASE_HASH_MISMATCH,
  MODULES_HASH_MISMATCH,
  LIBJVM_HASH_MISMATCH,
  JDK_CLOSURE_MISMATCH,
  EXECUTION_IDENTITY_MISMATCH,
  POST_EXECUTION_DRIFT,
  INTERNAL_FAILURE,
}

internal sealed interface BoundRuntime {
  val majorVersion: Int
  val javaExecutable: Path
  val identity: RuntimeIdentity
  val profileSha256: Sha256
  val observationSha256: Sha256
}

internal sealed interface RuntimeBindingResult {
  data class Valid(val runtime: BoundRuntime) : RuntimeBindingResult
  data class Invalid(val problems: List<RuntimeBindingProblem>) : RuntimeBindingResult
}

internal interface RuntimeBinder {
  fun bind(request: RuntimeBindingRequest): RuntimeBindingResult
}
```

`CaptureProfile`, operation construction, process invocation, compatibility checks, and
finalization consume `BoundRuntime`; they cannot reconstruct or partially validate runtime
identity. `NativeLinuxRuntimeProbe` and `OciRuntimeProbe` are internal adapters behind the binder.

The distribution schema replaces the old exact runtime-path declaration with
`JavaCompatibility(majorVersion = 21)`. The private binding file is
`private-runtime-binding-v2.json`. It contains the locally resolved Java executable only inside the
private operation area. Public evidence contains the exact runtime identity but never the absolute
Java path. The binder hashes the pre-launch observation and repeats the profile/closure checks
after execution so a time-of-check/time-of-use change invalidates the operation.

Execution-specific identity is sealed:

```kotlin
sealed interface ExecutionEnvironmentIdentity {
  data class Container(
    val oci: OciIdentity,
    val security: ContainerSecurityIdentity,
  ) : ExecutionEnvironmentIdentity

  data class NativeLinux(
    val toolManifestSha256: Sha256,
    val filesystemType: String,
    val atomicMoveDevice: String,
  ) : ExecutionEnvironmentIdentity
}
```

`RuntimeIdentity` owns a `JdkIdentity`, `LinuxIdentity`, limits, storage, network, environment,
host ID, substrate, and `ExecutionEnvironmentIdentity`. Runtime-profile, distribution, private
binding, capture, qualification, and watcher documents move to explicit V2 schemas rather than
weakening the existing V1 contracts. Callers do not branch on Java vendor or container/native
details; validation and serialization stay behind the runtime-binding module.

The frozen distribution launcher requires a private `REVOMAN_PERFORMANCE_JAVA` bootstrap value
instead of a hard-coded `/opt/java/openjdk/bin/java`, unsets it before starting the runner JVM, and
never logs it. The adapter writes the corresponding Java home only to the private observation; the
runtime binder reads and validates it against the checked-in profile before timing. Every measured
JMH child then receives its executable only from `BoundRuntime`.

### Java 21 contract

The platform contract is:

```text
major version = exactly 21
vendor = any nonblank declared value
exact runtime identity = profile-bound and comparison-compatible
```

No schema may use `const` for `Eclipse Adoptium`, `Temurin`, or an Adoptium source URL. A canonical
runtime profile records:

- reported feature version and complete `java -version` output;
- reported vendor and VM name;
- architecture;
- `bin/java`, `release`, `lib/modules`, and `lib/server/libjvm.so` hashes;
- a canonical full-tree manifest hash covering every regular file and symlink target;
- the declared JVM arguments; and
- the exact tool manifest used to launch and finalize the operation.

The checked-in profile for this machine uses the installed Temurin 21.0.12+8-LTS identity:

| Item | SHA-256 |
|---|---|
| `bin/java` | `11af352aa2c506c4123a4e4c19c187d59e06cd0dff317d54f5e6806e07c6715d` |
| `release` | `95831ab52b5291e8df70cb96cd3693171d462f2740949f05f5c03a51eb3a92fa` |
| `lib/modules` | `886d2849c10dfa644012833a0a3f4f6a4d9f0e6e4af44e4a061bca305aa89f60` |
| `lib/server/libjvm.so` | `f39426e244432f68362215b167869cf26f570eff89514c94c128594531d87126` |

The full-tree manifest is generated, reviewed, and committed in PR B. A different JDK 21
distribution is supported by adding and reviewing another profile; platform code does not change.
Comparisons require exact JDK identity equality even though the platform supports multiple vendors.

### Controlled Linux substrate

`SubstrateIdentity` gains a controlled-host subtype rather than duplicating campaign behavior:

```kotlin
sealed interface SubstrateIdentity {
  sealed interface Controlled : SubstrateIdentity
  data class ControlledMac(/* existing fields */) : Controlled
  data class ControlledLinux(/* checked-in Linux fields */) : Controlled
  data class GithubHosted(/* existing fields */) : SubstrateIdentity
}
```

`QualificationEvidence.ControlledCampaign` and
`QualificationEvidence.ControlledBoundedDiagnostic` apply to either controlled adapter.
`CampaignFinalizer` accepts only `ControlledCampaign`. GitHub-hosted evidence remains incapable of
entering that type.

The first Linux substrate profile binds:

- Ubuntu 24.04.4 LTS and kernel `6.8.0-138-generic`;
- x86_64, Intel Core i7-9800X, one socket, eight cores, sixteen logical CPUs, one NUMA node;
- exact CPU model, topology, and microcode;
- no detected virtualization;
- measured CPU set `4-7`, one logical CPU on each of physical cores 4-7, while sibling CPUs
  `12-15` are watched and must remain quiet;
- `-XX:ActiveProcessorCount=4`;
- exact governor, energy-performance preference, and turbo state;
- native filesystem and same-device atomic-publication identity;
- exact `taskset`, `flock`, `mv`, `tar`, and `sha256sum` identities; and
- the JDK profile above.

The native adapter requires explicit runtime-profile and Java-home inputs. It performs no
privileged tuning and never kills an unrelated process. It launches the frozen runner and JMH
forks through `taskset`, `unshare`, and Bubblewrap 0.9.0 with an isolated network namespace,
loopback only, an empty environment, read-only distribution and JDK trees, a private `/proc`, no
host home or credential mounts, and only operation-private writable paths. It fails closed when
the declared quiet state or sandbox capability is absent.

Initial checked-in qualification thresholds are:

- twelve preflight samples at five-second cadence;
- median CPU idle at least 90%, with no sample below 80%;
- package temperature from the checked-in `coretemp` hwmon mapping below 70 degrees Celsius at
  admission;
- immediate invalidation at 80 degrees Celsius or a thermal warning;
- zero swap-in/swap-out growth and no memory-pressure failure;
- no sustained CPU, memory, or I/O pressure breach for three watcher samples;
- no prohibited Gradle, Java/JMH, Qodana, container, compiler, package-manager, or IDE workload;
- exact power/governor/EPP/turbo state throughout;
- one atomic host/profile lock held until publication or quarantine; and
- five-second watcher cadence from before A1 until the last timed child exits.

A branch-time structural canary may test the adapter but remains diagnostic. Canonical A/A
admission runs only after PR B is merged. If post-merge A/A shows the checked-in policy is not
viable, change it in another reviewed protocol PR and restart before observing B.

### Workload

No new claim cell is added initially. The formal V3 real-wire workload already runs a pre-request
script and a post-response script while deliberately avoiding both `pm.request.json()` and
`jsonSchema`. It therefore exercises the unused-bootstrap costs targeted by both completed fixes.

Formal expected cells remain:

- cold: `RevUpV3ColdBenchmark.revUp`;
- warm: `RevUpV3WarmBenchmark.revUp`.

The only new benchmark-facing support is a generic checked-in campaign-suite definition and
executor. It freezes an ordered treatment chain once, invokes existing single-campaign operations
in a declared order, records every result exit without reading result contents, continues on
result exits `0`, `5`, `6`, and `7`, and aborts on infrastructure exits `2`, `3`, `4`, and `8`.
It adds no benchmark cell and no estimator.

The integration tests must freeze and structurally validate the harness against all three exact
treatment commits. A new benchmark is permitted only through a new reviewed protocol revision,
followed by complete reacquisition; an inconclusive candidate result is not permission to tune the
workload.

## Formal Campaign Protocol

Three chained, role-neutral distributions are built from a clean merged-master harness:

| Distribution | Production commit | Harness source |
|---|---|---|
| D1 | `d343df32d0b258cd5f37ab2606eb773e55b0ea6d` | clean merged-master runner |
| D2 | `9439dc416ca7676c1f501a93924d7d3900f33e16` | D1 via `--harness-from` |
| D3 | `d42614fa4982d8f960354ba07a2027f84b5ef1bc` | D2 via `--harness-from` |

D2 is the candidate for the request-bootstrap pair and the baseline for the lazy-Ajv pair. The
distribution is role-neutral, so this reuse proves that both adjacent comparisons share the same
frozen harness without creating two independently assembled `9439dc416ca7676c1f501a93924d7d3900f33e16` artifacts. Campaign
state and A/A evidence are not reused across pairs.

Predeclared order:

1. Request bootstrap cold.
2. Request bootstrap warm.
3. Lazy Ajv cold.
4. Lazy Ajv warm.

Each campaign performs:

1. Fresh baseline A1 and A2 at 10 forks.
2. Fresh pairs at 20 and then 40 forks only when the prior A/A misses.
3. A/A admission only when the ratio interval contains 1.0, the point ratio is within
   `[0.95, 1.05]`, and interval width is at most 0.10.
4. Immediate candidate B after the first passing A/A in the same locked, watched session.
5. Candidate comparison against A2.
6. No B when 40-fork calibration fails.

The frozen maximum-regression budget is 5%:

- pass when the upper ratio bound is at most 1.05;
- fail when the lower ratio bound is greater than 1.05;
- otherwise policy-inconclusive.

Directional classification remains independent:

- improvement when the upper 95% ratio bound is below 1.0;
- regression when the lower 95% ratio bound is above 1.0;
- inconclusive otherwise.

An unqualified speedup statement requires improvement in both cold and warm campaigns for the same
treatment edge. Mixed outcomes are reported by profile. A policy pass proves only the reviewed 5%
bound; it never proves improvement.

These campaigns establish only the two incremental commit edges named in the objective. They do
not gate the current master-based PR #414, a combined master-to-`d42614fa4982d8f960354ba07a2027f84b5ef1bc` change, or another
base/candidate pair. Those claims require additional preregistered campaigns with matching exact
bases.

## Evidence Strength

| Evidence | Strength |
|---|---|
| Existing hotspot and local reports | Diagnostic, `ClaimEligible=false` |
| Old GitHub performance workflow output | Diagnostic, `ClaimEligible=false` |
| GitHub-hosted structural/manual canary | Diagnostic, `ClaimEligible=false` |
| Structural canary on the controlled host | Diagnostic, `ClaimEligible=false` |
| Standalone capture or comparison | Diagnostic, `ClaimEligible=false` |
| GC/JFR capture and summaries | Diagnostic, `ClaimEligible=false` |
| A/A comparison | Calibration/admission only |
| Finalized cold/warm A1/A2/B campaign on an exact controlled profile | Claim-bearing when every gate passes |

New public evidence is immutable, schema-valid, recursively checksummed, privacy-safe, and
atomically published. Raw JFR, private bindings, absolute paths, hostnames, usernames, worktree
paths, reservation state, and operation volumes are never committed.

## Merge-First and Review Gates

### PR A

- Exact `origin/master` base is recorded before the merge.
- The integration commit has `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0` as a parent and ancestor.
- GitHub merges the PR with merge-commit mode.
- No diff under `src/main`, `src/test`, or `src/integrationTest` relative to its master base.
- The four overlapping paths receive explicit review.
- Legacy V1 claim paths are retired; artifacts remain preserved.
- ABI, build logic, runner, schemas, workflows, JMH tests, full build, and Qodana pass.

### PR B

- Branch starts only from a fetched master containing PR A.
- Java-vendor-neutral tests cover at least two synthetic vendor strings at major version 21.
- Java 17, Java 22, blank vendor, an undeclared ambient runtime, and a mismatched JDK closure are
  rejected.
- Linux identity, qualification, watcher, lock, signal, privacy, and publication failure matrices
  pass.
- Structural canaries are diagnostic and run before merge; no formal comparison runs.
- Full verification and Qodana pass.

### Campaign admission

- Fresh fetch proves both infrastructure merge SHAs are ancestors of `origin/master`.
- Runner worktree is clean and detached at that exact master SHA.
- All treatment worktrees are clean and detached at exact full SHAs.
- Every distribution validates immediately before timing.
- Harness, dependency, profile, policy, adapter, schema, comparator, renderer, and JDK identities
  match within each campaign pair.
- Dynamic host qualification and A/A pass before B.
- No profile, threshold, treatment, or protocol change occurs after candidate observation.

### Evidence publication

- An independent review recomputes schemas, recursive checksums, A1/A2/B order, compatibility,
  bootstrap estimates, classifications, and policy outcomes.
- Evidence is submitted in an evidence-only PR with no production or protocol change.
- Treatment PR wording cites exact campaign IDs and hashes and preserves profile scope.

## Orca Orchestration

The work is coordinated as a dependency graph, not as one long mutable branch:

```text
three read-only planning reviews
  -> specification and three implementation plans
  -> PR A implementation
  -> PR A independent review and merge gate
  -> PR B implementation
  -> PR B independent review and merge gate
  -> four formal campaigns
  -> independent evidence review and publication gate
```

Parallelism is allowed only for tasks without shared writable state. PR B cannot start until a
fetch proves PR A is on master. Campaigns cannot start until a fetch proves PR B is on master.

## Safety Invariants

- Never switch, merge, reset, rebase, commit, or edit in the protected root worktree.
- Verify the protected HEAD and `.idea/kotlinc.xml` hash before and after each mutating tranche.
- Use new isolated worktrees for every branch and exact treatment checkout.
- Never rewrite a published branch.
- Never delete an existing worktree or performance artifact.
- Never push, open a PR, merge, dispatch a workflow, or publish evidence without its explicit
  remote-state approval gate.
- Never run formal comparisons before both infrastructure PRs are merged into master.

## Acceptance

This design is complete when:

1. The formal platform history is on master with no unrelated production change.
2. The platform accepts any exact-profile Java 21 runtime without vendor logic.
3. This Linux machine has a reviewed canonical profile bound to its exact installed JDK and host
   identities.
4. All structural, negative, qualification, and publication gates pass.
5. Four post-merge campaigns produce either valid classifications or preserved invalid evidence.
6. Claims, if any, use only independently reviewed finalized campaign bundles and the wording rules
   above.
