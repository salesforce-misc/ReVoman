# Controlled Linux JDK 21 Performance Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a vendor-neutral Java 21 runtime-binding module and qualify this HP Z4 G4 Linux machine, using its exact installed Temurin JDK, as a controlled canonical performance host.

**Architecture:** Separate frozen-distribution compatibility from runtime observation: distributions declare exactly Java 21, while a `RuntimeBinder` is the sole creator of an unforgeable `BoundRuntime` proof. Add native Linux as a real execution environment behind that seam, run it in a locked `taskset`/user-namespace/Bubblewrap sandbox, and extend the existing evidence/finalization algebra so only fully qualified controlled campaigns can become claim-bearing.

**Tech Stack:** Kotlin/JVM 21, Jackson 3, JSON Schema, Gradle, Bash, Bubblewrap 0.9.0, util-linux 2.42.2, iproute2 6.1.0, Linux procfs/sysfs/PSI, Kotest/JUnit Platform.

**Spec:** `docs/superpowers/specs/2026-08-21-merge-first-performance-workflow-design.md`

## Global Constraints

- Start only from a fetched `origin/master` that contains the merged PR A SHA and `e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0` as ancestors.
- Never edit, switch, merge, reset, rebase, or commit in `/home/gopala.akshintala/code-clones/work/revoman-root`.
- Preserve root HEAD `47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e`, the sole `.idea/kotlinc.xml` modification, and its SHA-256 `c995703f125cf3ad057ffdd509b211bd3a5533c22307a17323fef86cb3c9b694`.
- Never rewrite a published branch or delete an existing worktree or performance artifact.
- The platform accepts any nonblank vendor only when the observed Java major version is exactly 21.
- Each capture binds the exact checked-in JDK profile and full JDK-home closure; comparisons require identity equality.
- The first host profile uses installed Temurin 21.0.12+8-LTS bytes but platform logic contains no Temurin/Adoptium allowlist.
- The native adapter performs no privileged tuning, process killing, sudo, or persistent host mutation.
- Structural canaries may run; formal treatment comparisons may not run in this plan.
- Use pnpm for every explicit npm package installation.
- Do not push, open, update, or merge a PR until the remote-state gate is explicitly authorized.

---

## File and Module Map

- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/JavaCompatibility.kt`: exact Java-major contract carried by distributions.
- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/RuntimeBinder.kt`: sole public binding operation and sealed proof/result types.
- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/RuntimeProfile.kt`: strict V2 profile decoder and public identity projection.
- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/JdkClosure.kt`: deterministic no-follow JDK tree manifest and digest.
- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/NativeLinuxRuntimeProbe.kt`: native observation adapter.
- `buildSrc/performance-runner/src/main/kotlin/performance/runtime/OciRuntimeProbe.kt`: current OCI behavior behind the same binder.
- `performance.distribution`, `performance.capture`, `performance.process`, `performance.compare`, `performance.model`, and `performance.finalize`: consume verified compatibility/binding types instead of paths or OCI-shaped fragments.
- `scripts/performance/run`: stable command router and lifecycle owner.
- `scripts/performance/lib/common.sh`: privacy-safe parsing, hashing, lock, trap, and publication primitives.
- `scripts/performance/lib/oci.sh`: existing Docker/hosted behavior.
- `scripts/performance/lib/native-linux.sh`: Linux preflight, sandbox, watcher, postflight, and restoration.
- `config/performance/runtime/temurin-21.0.12-linux-x86_64-v1.json`: exact installed JDK profile.
- `config/performance/hosts/hp-z4-g4-linux-x86_64-v1.json`: exact native substrate declaration.
- `config/performance/policies/hp-z4-g4-linux-x86_64-v1.json`: checked-in qualification thresholds.
- `config/performance/campaigns/bootstrap-fixes-v1.json`: immutable three-distribution/four-campaign suite definition.
- `scripts/performance/run-suite`: generic no-peeking suite executor.

### Task 1: Make Distribution Validation Runtime-Independent

**Files:**
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/JavaCompatibility.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/DistributionManifest.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/distribution/DistributionValidator.kt`
- Modify: `buildSrc/src/main/kotlin/performance/AssemblePerformanceDistributionTask.kt`
- Modify: `buildSrc/src/main/kotlin/performance/VerifyPerformanceDistributionTask.kt`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/distribution-classpath-v2.schema.json`
- Modify: `buildSrc/performance-runner/src/test/kotlin/performance/distribution/DistributionValidatorTest.kt`
- Modify: `buildSrc/src/test/kotlin/performance/DistributionFreezeContractTest.kt`

**Interfaces:**
- Consumes: frozen classpath entries and expected benchmark identities
- Produces: `JavaCompatibility(majorVersion: Int)` on `DistributionMetadata`; `DistributionValidator.validate(DistributionValidationRequest)` no longer consumes a selected JVM

- [ ] **Step 1: Create the isolated PR B worktree from merged master**

```bash
git -C /home/gopala.akshintala/code-clones/work/revoman-root fetch origin master
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor e96e6cbae05d57e4ce368c5d2cd31a85c6cf63f0 origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root merge-base --is-ancestor "${PR_A_MERGE_SHA}" origin/master
git -C /home/gopala.akshintala/code-clones/work/revoman-root worktree add -b codex/perf-controlled-linux-jdk21-2026-08-21 /home/gopala.akshintala/code-clones/work/revoman-perf-controlled-linux-jdk21-20260821 origin/master
```

Expected: both ancestry checks pass and the new worktree is clean at the fetched master SHA. Stop
without editing if either ancestry check fails.

- [ ] **Step 2: Write failing exact-major and path-independence tests**

```kotlin
test("Java 21 compatibility does not bind vendor path or ambient JVM") {
  val result = validator.validate(fixture.request(javaMajorVersion = 21))
  result.shouldBeInstanceOf<DistributionValidation.Valid>()
}

listOf(17, 20, 22, 25).forEach { major ->
  test("Java $major is incompatible") {
    validator.validate(fixture.request(javaMajorVersion = major))
      .shouldBeInvalidWith(DistributionProblem.JAVA_VERSION_UNSUPPORTED)
  }
}
```

Delete test helpers that pass an absolute executable, executable digest, or `Runtime.version()` into distribution validation.

- [ ] **Step 3: Run the tests and verify RED**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests performance.distribution.DistributionValidatorTest
```

Expected: FAIL because V1 still requires `JavaRuntimeIdentity` and accepts versions greater than 21.

- [ ] **Step 4: Add the exact compatibility value and simplify the request**

```kotlin
package performance.runtime

data class JavaCompatibility(val majorVersion: Int) {
  init {
    require(majorVersion == 21)
  }
}
```

```kotlin
data class DistributionValidationRequest(
  val root: Path,
  val expectedProtocolHash: Sha256? = null,
  val stagingOutput: Path? = null,
)
```

Replace `DeclaredJavaRuntime` in the classpath manifest with `JavaCompatibility`. Use
`metadata.classpath.javaCompatibility.majorVersion` for multi-release JAR resolution and return
`JAVA_VERSION_UNSUPPORTED` unless it equals 21. Remove every comparison with the executing JVM,
absolute executable, vendor, and launcher digest.

- [ ] **Step 5: Add the strict V2 classpath schema**

The V2 object must require exactly these Java fields:

```json
{
  "javaCompatibility": {
    "majorVersion": 21
  },
  "schemaVersion": "distribution-classpath-v2"
}
```

Keep all V1 classpath, ordering, hash, service-provider, benchmark-metadata, and test-leakage constraints. Reject documents containing V1 `javaRuntime`, `executable`, or `executableSha256` fields.

- [ ] **Step 6: Run focused tests and commit**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests 'performance.distribution.*' buildSrc:test --tests performance.DistributionFreezeContractTest
git add buildSrc/performance-runner/src/main/kotlin/performance/runtime/JavaCompatibility.kt buildSrc/performance-runner/src/main/kotlin/performance/distribution buildSrc/performance-runner/src/main/resources/performance/protocol/schemas buildSrc/performance-runner/src/test/kotlin/performance/distribution buildSrc/src/main/kotlin/performance buildSrc/src/test/kotlin/performance/DistributionFreezeContractTest.kt
git commit -m "refactor(perf): separate Java compatibility from runtime binding"
```

Expected: PASS; frozen metadata contains Java major 21 but no host path or vendor requirement.

### Task 2: Add the Runtime Binder and Full JDK Closure

**Files:**
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/RuntimeBinder.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/RuntimeProfile.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/JdkClosure.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/NativeLinuxRuntimeProbe.kt`
- Create: `buildSrc/performance-runner/src/main/kotlin/performance/runtime/OciRuntimeProbe.kt`
- Modify: `buildSrc/src/main/kotlin/performance/AssemblePerformanceDistributionTask.kt`
- Modify: `buildSrc/src/test/kotlin/performance/DistributionFreezeContractTest.kt`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/runtime-profile-v2.schema.json`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/private-runtime-binding-v2.schema.json`
- Create: `buildSrc/performance-runner/src/test/kotlin/performance/runtime/RuntimeBinderTest.kt`
- Create: `buildSrc/performance-runner/src/test/kotlin/performance/runtime/JdkClosureTest.kt`
- Modify: `buildSrc/src/test/kotlin/performance/adapter/PrivateRuntimeBindingContractTest.kt`

**Interfaces:**
- Consumes: `VerifiedDistribution`, runtime profile ID, and a private observation containing the
  adapter-selected JDK home
- Produces: `RuntimeBindingResult.Valid(runtime: BoundRuntime)` or ordered privacy-safe problems

- [ ] **Step 1: Write failing vendor-neutral and mutation tests**

```kotlin
listOf("Eclipse Adoptium", "Amazon.com Inc.").forEach { vendor ->
  test("accepts declared $vendor Java 21 bytes") {
    binder.bind(fixture.request(vendor = vendor, majorVersion = 21))
      .shouldBeInstanceOf<RuntimeBindingResult.Valid>()
  }
}

listOf(20, 22).forEach { major ->
  test("rejects declared Java $major") {
    binder.bind(fixture.request(majorVersion = major))
      .shouldBeInvalidWith(RuntimeBindingProblem.JAVA_MAJOR_MISMATCH)
  }
}

test("rejects a JDK file changed after observation") {
  val request = fixture.request(majorVersion = 21)
  fixture.mutateModulesAfterProbe()
  binder.bind(request).shouldBeInvalidWith(RuntimeBindingProblem.JDK_CLOSURE_MISMATCH)
}
```

Cover blank vendor/version/VM name, executable escape, symlink escape, special file, missing file,
mode change, byte change, profile hash mismatch, observation hash mismatch, and a post-run rebind
failure.

- [ ] **Step 2: Run the new tests and verify RED**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests 'performance.runtime.*'
```

Expected: FAIL because the runtime package and proof types do not exist.

- [ ] **Step 3: Define the only binding interface**

```kotlin
package performance.runtime

data class RuntimeBindingRequest(
  val distribution: VerifiedDistribution,
  val runtimeProfileId: String,
  val privateObservationPath: Path,
)

enum class RuntimeBindingProblem {
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

sealed interface BoundRuntime {
  val majorVersion: Int
  val javaExecutable: Path
  val identity: RuntimeIdentity
  val profileSha256: Sha256
  val observationSha256: Sha256
}

sealed interface RuntimeBindingResult {
  data class Valid(val runtime: BoundRuntime) : RuntimeBindingResult
  data class Invalid(val problems: List<RuntimeBindingProblem>) : RuntimeBindingResult
}

fun interface RuntimeBinder {
  fun bind(request: RuntimeBindingRequest): RuntimeBindingResult
}
```

Implement `BoundRuntime` with a file-private data class so no caller can forge it. Keep native and
OCI probing behind a registry selected by the profile's execution kind; do not select by vendor or
by `os.name` alone.

Change the generated Unix launcher to require the private adapter-provided bootstrap executable:

```kotlin
"""
runtime_java=${'$'}{REVOMAN_PERFORMANCE_JAVA:?missing private Java bootstrap}
unset REVOMAN_PERFORMANCE_JAVA
exec "${'$'}runtime_java" -cp "${runnerClasspath.joinToString(":") { "${'$'}root/$it" }}" performance.cli.PerformanceRunnerMainKt "${'$'}@"
"""
```

The adapter writes the selected Java home into `private-runtime-binding-v2.json`, sets the launcher
variable for this one runner process, and never publishes either value. The binder validates the
private observation before timing; every measured JMH child launches only from `BoundRuntime`.

- [ ] **Step 4: Implement the deterministic JDK closure**

`JdkClosure.compute(javaHome)` must normalize the real JDK root, never follow symlinks, reject
special files and any symlink whose normalized target escapes the root, and sort relative paths by
UTF-8 bytes with locale-independent ordering. Hash canonical JSON containing one entry for every
directory, regular file, and symlink:

```kotlin
sealed interface JdkEntry {
  val path: String
  val mode: String

  data class Directory(override val path: String, override val mode: String) : JdkEntry
  data class Regular(
    override val path: String,
    override val mode: String,
    val byteCount: Long,
    val sha256: Sha256,
  ) : JdkEntry
  data class Symlink(
    override val path: String,
    override val mode: String,
    val target: String,
  ) : JdkEntry
}

data class JdkClosure(
  val algorithm: String,
  val entries: List<JdkEntry>,
  val sha256: Sha256,
  val regularFileCount: Int,
  val regularFileByteCount: Long,
)
```

Set `algorithm` to `canonical-json-no-follow-v1`. The closure SHA is the SHA-256 of canonical JSON
for `{algorithm,entries}`; counts are verified projections, not additional hash inputs.

- [ ] **Step 5: Decode a vendor-neutral V2 runtime profile**

Require exactly these identity fields:

```kotlin
data class RuntimeProfile(
  val schemaVersion: String,
  val id: String,
  val javaMajorVersion: Int,
  val vendor: String,
  val runtimeVersion: String,
  val vmName: String,
  val architecture: String,
  val executable: String,
  val executableSha256: Sha256,
  val releaseSha256: Sha256,
  val modulesSha256: Sha256,
  val libjvmSha256: Sha256,
  val closureAlgorithm: String,
  val closureSha256: Sha256,
  val regularFileCount: Int,
  val regularFileByteCount: Long,
  val execution: RuntimeExecutionProfile,
)

sealed interface RuntimeExecutionProfile {
  data class Oci(val imageReference: String, val manifestDigest: String, val configDigest: String) : RuntimeExecutionProfile
  data class NativeLinux(val hostProfileId: String) : RuntimeExecutionProfile
}
```

Require `javaMajorVersion == 21`, nonblank observed strings, relative executable `bin/java`, and one
of the two execution variants. No schema field uses `const` for vendor, VM, Temurin, Adoptium, or
Amazon.

- [ ] **Step 6: Run focused tests and commit**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests 'performance.runtime.*' buildSrc:test --tests performance.adapter.PrivateRuntimeBindingContractTest
git add buildSrc/performance-runner/src/main/kotlin/performance/runtime buildSrc/performance-runner/src/main/resources/performance/protocol/schemas buildSrc/performance-runner/src/test/kotlin/performance/runtime buildSrc/src/test/kotlin/performance/adapter/PrivateRuntimeBindingContractTest.kt
git commit -m "feat(perf): bind exact vendor-neutral JDK 21 runtimes"
```

### Task 3: Require Bound Runtime Proofs Throughout Capture and Evidence

**Files:**
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/capture/CaptureProfile.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/capture/CaptureRunner.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/process/ProcessExecutor.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/process/JdkProcessExecutor.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/runner/OperationRequestFactory.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/model/EvidenceIdentity.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/model/HostDocuments.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/compare/CaptureCompatibility.kt`
- Modify: `buildSrc/performance-runner/src/main/kotlin/performance/finalize/CampaignFinalizer.kt`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/capture-v2.schema.json`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/preflight-v2.schema.json`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/watcher-v2.schema.json`
- Create: `buildSrc/performance-runner/src/main/resources/performance/protocol/schemas/postflight-v2.schema.json`
- Modify: corresponding capture/process/compare/finalize/schema tests

**Interfaces:**
- Consumes: `BoundRuntime` from Task 2
- Produces: V2 evidence with a sealed execution environment and controlled-host qualification shared by macOS and Linux

- [ ] **Step 1: Write proof-flow and controlled-Linux failure tests**

```kotlin
test("process invocation cannot select an executable independently") {
  ProcessInvocation(
    runtime = fixture.boundRuntime,
    arguments = listOf("-version"),
    classpath = emptyList(),
    workingDirectory = fixture.work,
    environment = emptyMap(),
    stdoutPath = fixture.stdout,
    stderrPath = fixture.stderr,
    resultPath = fixture.result,
  ).runtime shouldBe fixture.boundRuntime
}

test("controlled Linux finalization requires complete qualification") {
  finalizer.finalize(fixture.controlledLinuxCampaign(watcherPassed = false))
    .shouldBeInvalidWith(FinalizationProblem.HOST_QUALIFICATION_INVALID)
}
```

Also prove exact profile/closure mismatch makes captures incompatible and GitHub-hosted evidence
cannot construct controlled campaign qualification.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests 'performance.process.*' --tests 'performance.compare.*' --tests 'performance.finalize.*'
```

- [ ] **Step 3: Replace path fragments with the proof**

```kotlin
data class ProcessInvocation(
  val runtime: BoundRuntime,
  val arguments: List<String>,
  val classpath: List<Path>,
  val workingDirectory: Path,
  val environment: Map<String, String>,
  val stdoutPath: Path,
  val stderrPath: Path,
  val resultPath: Path,
  val rawProfilerPath: Path? = null,
)
```

`JdkProcessExecutor` launches only `spec.runtime.javaExecutable`. `CaptureProfile` stores
`runtime: BoundRuntime` and removes `selectedJavaExecutable`/`selectedJavaSha256`.
`OperationRequestFactory` asks `RuntimeBinder` once and never parses private runtime JSON itself.

- [ ] **Step 4: Generalize identity without fake OCI fields**

```kotlin
sealed interface ExecutionEnvironmentIdentity {
  data class Container(
    val oci: OciIdentity,
    val security: SecurityIdentity,
  ) : ExecutionEnvironmentIdentity

  data class NativeLinux(
    val toolManifestSha256: Sha256,
    val filesystemType: String,
    val atomicMoveDevice: String,
  ) : ExecutionEnvironmentIdentity
}

data class JdkIdentity(
  val majorVersion: Int,
  val vendor: String,
  val runtimeVersion: String,
  val vmName: String,
  val binarySha256: Sha256,
  val releaseSha256: Sha256,
  val modulesSha256: Sha256,
  val libjvmSha256: Sha256,
  val closureAlgorithm: String,
  val closureSha256: Sha256,
  val profileSha256: Sha256,
  val jvmArguments: List<String>,
)

sealed interface SubstrateIdentity {
  sealed interface Controlled : SubstrateIdentity
  data class ControlledMac(
    val macosVersion: String,
    val macosBuild: String,
    val hardwareModelClass: String,
    val dockerDesktopVersion: String,
    val dockerEngineVersion: String,
    val vmResources: AdvertisedResources,
  ) : Controlled
  data class ControlledLinux(
    val osReleaseSha256: Sha256,
    val kernel: String,
    val architecture: String,
    val hardwareModel: String,
    val biosVersion: String,
    val cpuModel: String,
    val cpuTopologySha256: Sha256,
    val microcode: String,
    val clocksource: String,
    val glibcVersion: String,
    val measuredCpuSet: String,
    val watchedSiblingCpuSet: String,
    val toolManifestSha256: Sha256,
  ) : Controlled
  data class GithubHosted(
    val runnerLabel: String,
    val runnerImageVersion: String,
    val kernel: String,
    val dockerEngineVersion: String,
    val advertisedResources: AdvertisedResources,
  ) : SubstrateIdentity
}

data class RuntimeIdentity(
  val jdk: JdkIdentity,
  val linux: LinuxIdentity,
  val limits: RuntimeLimits,
  val storage: StorageIdentity,
  val network: NetworkIdentity,
  val environment: Map<String, String>,
  val hostId: String,
  val substrate: SubstrateIdentity,
  val execution: ExecutionEnvironmentIdentity,
)

sealed interface QualificationEvidence {
  val policyHash: Sha256
  data class ControlledCampaign(
    override val policyHash: Sha256,
    val preflight: HostDocumentRef,
    val watcher: HostDocumentRef,
    val postflight: HostDocumentRef,
    val restoration: HostDocumentRef,
    val cleanupPassed: Boolean,
  ) : QualificationEvidence
  data class ControlledBoundedDiagnostic(
    override val policyHash: Sha256,
    val preflight: HostDocumentRef,
    val watcher: HostDocumentRef,
    val postflight: HostDocumentRef,
    val restoration: HostDocumentRef,
    val campaignFieldsInapplicableReason: String,
  ) : QualificationEvidence
  data class GithubHosted(
    override val policyHash: Sha256,
    val setup: HostDocumentRef,
    val cleanup: HostDocumentRef,
    val controlledFieldsInapplicableReason: String,
  ) : QualificationEvidence
}

enum class HostCheckStatus {
  PASS,
  FAIL,
  UNAVAILABLE,
  NOT_APPLICABLE,
}
```

Make observation availability explicit with `PASS`, `FAIL`, and `UNAVAILABLE`; each V2 policy
declares whether unavailable is permitted. The controlled Linux policy makes temperature, PSI,
swap, process scan, governor, EPP, turbo, topology, JDK closure, and tool identity required, so
`UNAVAILABLE` fails admission.

- [ ] **Step 5: Compare and finalize the proof projection**

Compatibility equality includes major version, profile SHA, JDK closure SHA, runtime identity,
host profile, execution identity, JVM arguments, protocol, and qualification policy. Only
`SubstrateIdentity.Controlled` plus `QualificationEvidence.ControlledCampaign` can produce
`claimEligible=true`; hosted and bounded diagnostic evidence remain false by construction.

- [ ] **Step 6: Run focused tests and commit**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test
git add buildSrc/performance-runner/src/main
git add buildSrc/performance-runner/src/test
git commit -m "refactor(perf): carry verified runtime proofs through evidence"
```

### Task 4: Implement the Controlled Native-Linux Adapter

**Files:**
- Modify: `scripts/performance/run`
- Create: `scripts/performance/lib/common.sh`
- Create: `scripts/performance/lib/oci.sh`
- Create: `scripts/performance/lib/native-linux.sh`
- Modify: `buildSrc/src/test/resources/performance/fake-host-command.sh`
- Create: `buildSrc/src/test/kotlin/performance/adapter/NativeLinuxAdapterContractTest.kt`
- Modify: all existing adapter contract tests

**Interfaces:**
- Consumes: `--runtime-profile`, `--java-home`, frozen distribution, profile, operation root
- Produces: private binding plus strict V2 preflight/watcher/postflight/restoration documents and a sandboxed runner process

- [ ] **Step 1: Write failing fake-host command and lifecycle matrices**

Test exact command order and arguments:

```text
flock -> preflight -> taskset 4-7 -> unshare user/mount/pid/net -> ip link set lo up
-> bwrap read-only JDK/distribution/private proc/empty env -> watcher -> postflight
-> JDK rehash -> finalizer -> atomic publication -> lock release
```

The negative matrix must cover missing `bwrap`, `unshare`, `ip`, `taskset`, or `flock`; namespace
failure; loopback failure; JDK/profile drift; lock contention; signal at every phase; watcher short
write; child failure; finalizer failure; cross-device publication; symlink operation root; secret
environment; forbidden process; CPU/power/thermal/topology drift; and cleanup failure.

- [ ] **Step 2: Run adapter tests and verify RED**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests 'performance.adapter.*'
```

- [ ] **Step 3: Split the adapter into private modules**

Keep `scripts/performance/run` as the only public entry point. It parses the operation once, loads
`common.sh`, selects `nativeLinux` or `oci` from the strict runtime profile, and delegates. The
common module owns one trap and this stable exit algebra:

```bash
readonly PERF_EXIT_SUCCESS=0
readonly PERF_EXIT_INPUT=2
readonly PERF_EXIT_PREFLIGHT=3
readonly PERF_EXIT_CAPTURE=4
readonly PERF_EXIT_AA_REJECTED=5
readonly PERF_EXIT_REGRESSION=6
readonly PERF_EXIT_INCONCLUSIVE=7
readonly PERF_EXIT_PUBLICATION=8
```

No module may print an absolute path, hostname, username, environment value, or private JSON.

- [ ] **Step 4: Implement the native sandbox command**

Build an argument array, never `eval` or a command string. The effective shape is:

```bash
taskset --cpu-list 4-7 \
  unshare --user --map-root-user --mount --pid --net --fork --mount-proc \
  /bin/sh -c 'ip link set lo up && exec "$@"' sh \
  bwrap --die-with-parent --new-session --share-net \
    --ro-bind /usr /usr --ro-bind /bin /bin --ro-bind /lib /lib --ro-bind /lib64 /lib64 \
    --ro-bind "$PERF_JAVA_HOME" /runtime/jdk \
    --ro-bind "$PERF_DISTRIBUTION" /runtime/distribution \
    --proc /proc --dev /dev --tmpfs /tmp \
    --bind "$PERF_OPERATION_ROOT" /operation \
    --chdir /operation --clearenv \
    /runtime/distribution/bin/performance-runner "$@"
```

Before executing, resolve and validate every host path with no-follow semantics. Pass only
allowlisted locale/timezone values plus the private bound executable. Do not mount the host home,
Git credentials, SSH agent, package caches, Docker socket, Gradle home, repository, or unrelated
temporary directories.

- [ ] **Step 5: Implement admission and continuous watching**

Use 12 preflight samples at five-second cadence. Admit only when median CPU idle is at least 90%,
every sample is at least 80%, package temperature from the `coretemp` `Package id 0` mapping is
below 70°C, swap-in/swap-out do not grow, PSI is below policy, CPUs 4-7 and siblings 12-15 are
quiet, and no prohibited Gradle, Java/JMH, Qodana, container, compiler, package-manager, or IDE
workload exists. Watch every five seconds until the last timed child exits and invalidate at 80°C,
thermal warning, three consecutive pressure breaches, process appearance, or identity drift.

- [ ] **Step 6: Run adapter tests and a diagnostic structural canary**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests 'performance.adapter.*'
./scripts/performance/run canary --runtime-profile temurin-21.0.12-linux-x86_64-v1 --java-home /home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem --distribution build/performance/distribution --output build/performance/native-linux-canary
```

Expected: tests pass. The canary is structurally valid, explicitly diagnostic, and
`ClaimEligible=false`; it contains no private path or identity leak.

- [ ] **Step 7: Commit the adapter**

```bash
git add scripts/performance buildSrc/src/test/kotlin/performance/adapter buildSrc/src/test/resources/performance
git commit -m "feat(perf): add controlled native Linux adapter"
```

### Task 5: Check In the Exact Host, JDK, and Qualification Profiles

**Files:**
- Create: `config/performance/runtime/temurin-21.0.12-linux-x86_64-v1.json`
- Create: `config/performance/hosts/hp-z4-g4-linux-x86_64-v1.json`
- Create: `config/performance/policies/hp-z4-g4-linux-x86_64-v1.json`
- Create: `config/performance/runtime/manifests/temurin-21.0.12-linux-x86_64-v1.json`
- Modify: `build.gradle.kts`
- Modify: `buildSrc/src/test/kotlin/performance/adapter/HostAdapterContractTest.kt`
- Create: `buildSrc/performance-runner/src/test/kotlin/performance/schema/RuntimeProfileV2ContractTest.kt`

**Interfaces:**
- Consumes: the runtime/closure/host schema from Tasks 2-4
- Produces: one reviewed exact runtime profile and one controlled host policy usable by dynamic qualification

- [ ] **Step 1: Generate the profile with the binder's production closure code**

Run a Gradle profile-generation task using exactly:

```text
/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem
```

The generated public profile must report major 21, vendor `Eclipse Adoptium`, runtime
`21.0.12+8-LTS`, VM `OpenJDK 64-Bit Server VM`, architecture `amd64`, executable `bin/java`, the
computed full closure, and these independent anchor hashes:

```text
bin/java                 11af352aa2c506c4123a4e4c19c187d59e06cd0dff317d54f5e6806e07c6715d
release                  95831ab52b5291e8df70cb96cd3693171d462f2740949f05f5c03a51eb3a92fa
lib/modules              886d2849c10dfa644012833a0a3f4f6a4d9f0e6e4af44e4a061bca305aa89f60
lib/server/libjvm.so     f39426e244432f68362215b167869cf26f570eff89514c94c128594531d87126
```

The absolute JDK path is an input to generation but must not appear in either checked-in file.

- [ ] **Step 2: Check in the exact host declaration**

Bind Ubuntu 24.04.4 LTS, kernel `6.8.0-138-generic`, glibc 2.39, x86_64, HP Z4 G4, Intel
i7-9800X, one socket, eight physical cores, 16 logical CPUs, one NUMA node, microcode
`0x2007108`, BIOS `P62 v02.96`, TSC clocksource, CPU topology, governor/EPP/turbo values, filesystem,
atomic-move device, Bubblewrap 0.9.0, util-linux 2.42.2, iproute2 6.1.0, and the exact hashes of all
executed tools. Declare measured CPUs `4-7`, watched SMT siblings `12-15`, and
`-XX:ActiveProcessorCount=4`.

- [ ] **Step 3: Check in fail-closed qualification thresholds**

Encode the exact thresholds from the spec and mark thermal, PSI, swap, CPU idle, process scan,
power policy, topology, tool identity, filesystem, JDK closure, lock, watcher completeness, and
postflight as required. Unknown or unavailable values fail.

- [ ] **Step 4: Prove reproducibility and vendor neutrality**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem generatePerformanceRuntimeProfile
git diff --exit-code -- config/performance/runtime config/performance/hosts
rg -n '/home/|gopala|hostname|user.name' config/performance
rg -n 'const.*(Temurin|Adoptium|Amazon)|vendor.*enum' config/performance buildSrc
```

Expected: regeneration is byte-identical; both searches produce no privacy leak or vendor
allowlist. Literal observed vendor data in the exact runtime profile is allowed and reviewed.

- [ ] **Step 5: Run schema and host tests, then commit**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem :buildSrc:performance-runner:test --tests 'performance.schema.*' buildSrc:test --tests 'performance.adapter.*'
git add config/performance build.gradle.kts buildSrc
git commit -m "feat(perf): qualify HP Z4 Linux with exact JDK 21 profile"
```

### Task 6: Add the Generic No-Peeking Campaign Suite

**Files:**
- Create: `config/performance/campaigns/campaign-suite-v1.schema.json`
- Create: `config/performance/campaigns/bootstrap-fixes-v1.json`
- Create: `config/performance/policies/bootstrap-fixes-5pct-v1.json`
- Create: `scripts/performance/run-suite`
- Create: `buildSrc/src/test/kotlin/performance/adapter/FormalSuiteContractTest.kt`
- Modify: `build.gradle.kts`
- Modify: `docs/modules/ROOT/pages/performance.adoc`

**Interfaces:**
- Consumes: ordered exact treatment SHAs, one merged-master harness, runtime/host profile, profile families, and a regression policy
- Produces: three chained role-neutral distributions and four result directories without reading result contents

- [ ] **Step 1: Write the failing suite contract**

```kotlin
test("bootstrap suite freezes three chained distributions and runs four campaigns") {
  val trace = fixture.runSuite("config/performance/campaigns/bootstrap-fixes-v1.json")
  trace.freezeEdges shouldBe
    listOf(
      FreezeEdge(null, "d343df32d0b258cd5f37ab2606eb773e55b0ea6d"),
      FreezeEdge("d343df32d0b258cd5f37ab2606eb773e55b0ea6d", "9439dc416ca7676c1f501a93924d7d3900f33e16"),
      FreezeEdge("9439dc416ca7676c1f501a93924d7d3900f33e16", "d42614fa4982d8f960354ba07a2027f84b5ef1bc"),
    )
  trace.campaignProfiles shouldBe listOf("request-cold", "request-warm", "lazy-ajv-cold", "lazy-ajv-warm")
}
```

Also test continuation on exits `0/5/6/7`, immediate abort on `2/3/4/8`, no file-content read
after campaign launch, no distribution rebuild, and D2 used in both roles.

- [ ] **Step 2: Run the contract and verify RED**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests performance.adapter.FormalSuiteContractTest
```

- [ ] **Step 3: Add the frozen policy and exact suite declaration**

The policy file is canonical JSON with exact content:

```json
{"maximumRegressionBudget":0.05,"schemaVersion":"regression-policy-v1"}
```

The suite declares D1/D2/D3 in the exact SHA order above and campaigns in this exact order:

```json
{
  "campaigns": [
    {"baseline":"D1","candidate":"D2","id":"request-cold","profile":"cold"},
    {"baseline":"D1","candidate":"D2","id":"request-warm","profile":"warm"},
    {"baseline":"D2","candidate":"D3","id":"lazy-ajv-cold","profile":"cold"},
    {"baseline":"D2","candidate":"D3","id":"lazy-ajv-warm","profile":"warm"}
  ],
  "distributions": [
    {"id":"D1","treatmentGitSha":"d343df32d0b258cd5f37ab2606eb773e55b0ea6d"},
    {"harnessFrom":"D1","id":"D2","treatmentGitSha":"9439dc416ca7676c1f501a93924d7d3900f33e16"},
    {"harnessFrom":"D2","id":"D3","treatmentGitSha":"d42614fa4982d8f960354ba07a2027f84b5ef1bc"}
  ],
  "policy":"config/performance/policies/bootstrap-fixes-5pct-v1.json",
  "schemaVersion":"campaign-suite-v1"
}
```

- [ ] **Step 4: Implement the generic executor**

`run-suite` strictly validates the suite, verifies clean exact treatment worktrees supplied by
explicit mapping, freezes each distribution once, validates all three, then invokes
`scripts/performance/run campaign` in declared order. It records only campaign ID, start/end time,
and exit code in suite state; it never opens a result document. Use a single suite lock and preserve
all completed, invalid, quarantined, and partial output directories.

Its complete public invocation is:

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

Require each `--treatment ID=PATH` exactly once, reject unknown IDs and duplicate paths, allow only
the checked-in output roots accepted by the adapter, and keep absolute treatment/JDK paths only in
private suite state excluded from publication.

- [ ] **Step 5: Run contracts and commit**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem buildSrc:test --tests performance.adapter.FormalSuiteContractTest
git add config/performance/campaigns config/performance/policies/bootstrap-fixes-5pct-v1.json scripts/performance/run-suite buildSrc/src/test/kotlin/performance/adapter/FormalSuiteContractTest.kt build.gradle.kts docs/modules/ROOT/pages/performance.adoc
git commit -m "feat(perf): preregister chained formal campaign suites"
```

### Task 7: Verify PR B and Hold the Merge-First Gate

**Files:**
- Inspect: complete branch diff
- Test: all Gradle, Qodana, privacy, adapter, schema, and structural-canary suites

**Interfaces:**
- Consumes: Tasks 1-6
- Produces: a locally reviewable PR B with no formal treatment observations

- [ ] **Step 1: Run all verification**

```bash
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem clean test integrationTest jmhTest buildSrc:test :buildSrc:performance-runner:test checkKotlinAbi verifyPerformanceDistribution
./gradlew --quiet --no-daemon -Dorg.gradle.java.home=/home/linuxbrew/.linuxbrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.12-tem qodanaScan
```

Expected: PASS with zero formal A/B campaign directories.

- [ ] **Step 2: Run live structural checks only**

Run runtime profile regeneration, native namespace preflight, distribution validation, and one
canary. Verify recursive checksums and scan every public file for absolute paths, usernames,
hostnames, environment values, credentials, operation paths, and raw profiler material. Record
`ClaimEligible=false` for the canary.

- [ ] **Step 3: Independently review architecture and failure closure**

The reviewer must prove:

```text
Distribution validation is vendor/path/ambient-JVM independent and requires exactly major 21.
Only RuntimeBinder can create BoundRuntime.
Every launched JVM consumes BoundRuntime.
Native Linux has no fake OCI fields and no privilege requirement.
All unavailable required telemetry fails closed.
Only Controlled + complete campaign qualification can mint claimEligible=true.
The suite freezes D1/D2/D3 once and does not inspect candidate results.
```

- [ ] **Step 4: Reverify protected root and stop at the remote gate**

Do not push or open PR B until explicitly authorized. When authorized, push the new branch, open
PR B only against a master containing PR A, merge with a normal merge commit, fetch, and prove the
PR B merge is an ancestor of `origin/master`. Only then may the formal campaign plan begin.
