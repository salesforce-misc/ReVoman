# Performance Change Set 1 — Benchmark Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible benchmark foundation that runs JMH without flattening multi-release dependencies, compares cold standalone and warm repeated execution against fixed revision `83f3cd70f78ad733412d10cbc8287aaabafe7aac`, and enforces only evidence-backed performance gates.

**Architecture:** A new deep `:benchmark-driver` module owns benchmark schemas, target isolation, versioned reflective adapters, process orchestration, metric collection, statistics, and comparison. It has no dependency on `project(":")`; supplied ReVoman distributions are loaded from their original JAR classpaths behind the small `TargetAdapter` interface. Existing JMH benchmarks move into the driver and call prepared target operations, while a thin generated-classes JAR plus original dependency JARs replaces the broken uber-JAR launch path.

**Tech Stack:** JDK 21, Kotlin 2.4.20-Beta2, Gradle wrapper 9.7.0-rc-2, JMH 1.37 with `me.champeau.jmh` 0.7.3, Moshi/MoshiX, Apache Commons Math 3.6.1, NetworkNT JSON Schema Validator 3.0.4, JFR, JUnit 5, Kotest assertions, JDK `HttpServer`.

## Global Constraints

- Do not change `src/main/**` in Change Set 1; benchmark correctness lands before runtime optimization.
- The fixed baseline is full commit `83f3cd70f78ad733412d10cbc8287aaabafe7aac`; changing it requires amending and re-approving the umbrella design.
- `:benchmark-driver` must have no compile-time or runtime dependency on `project(":")`.
- Target classpaths contain the target's normal JAR plus original dependency JARs. Never flatten dependencies into `*-jmh.jar` or another uber JAR.
- Baseline and candidate may use different versioned adapter IDs/hashes; comparability requires each role to match the two adapter identities pinned by the same harness manifest, not equality between the two adapters.
- JMH uses `shouldFailOnError(true)`, rejects empty result collections, and emits machine-readable JSON.
- Benchmark backend logging is disabled with an absolute Log4j configuration URI and `-Drevoman.banner=off`; do not bypass `RunLogSink.NoOp`, because its current supplier-evaluation cost is legitimate baseline behavior.
- All benchmark fixtures are checked in, versioned, network-independent, and served only through a deterministic `127.0.0.1` JDK `HttpServer` outside timed regions.
- Cold release evidence uses at least 50 fresh JVM processes. Warm release evidence uses at least five independent forks and retains per-execution samples after warmup.
- Cold non-regression limits are median `+5%`, p95 `+10%`, allocation `+5%`, and peak RSS `+5%`.
- Warm non-regression limits are median `+3%`, p95 `+5%`, and allocation `+3%`; retained-memory slope has an upper 95% endpoint of at most `1,024 bytes/execution`.
- A targeted improvement claim requires at least `15%` cold or `20%` warm improvement unless an exact structural invariant applies.
- Confidence intervals use 10,000 deterministic hierarchical bootstrap resamples: paired host blocks, then forks, then warm iterations.
- Timing and memory thresholds run only on the controlled self-hosted Linux benchmark host. Shared PR/push CI runs deterministic harness checks and makes no numeric performance assertion.
- Measure cold latency/RSS separately from JFR allocation so recording overhead cannot contaminate latency.
- Hash/validate target and harness artifacts before and after a campaign, never inside each timed cold child; timed workers perform only manifest-identity and cheap file-stat checks.
- Preserve raw observations; do not trim, winsorize, or remove an observation based on its benchmark value.
- Follow `STYLE.md`: four-space Kotlin indentation, natural-language backtick test names, immutable transformations where practical, and KDoc on public interfaces.
- Every implementation task finishes with its focused tests and a dedicated commit.

## Scope Boundary

Change Set 1 ships the harness, the versioned `lifecycle.no-script-one-step.v1` macro workload, the migrated five existing JMH workloads, deterministic fake-target coverage, and a controlled A/A baseline campaign. Workloads requiring `ExecutionSession`, `RunProgress`, journal counters, bounded polling, immutable codecs, or other future types are added by their owning later change-set plans through the already-fixed `TargetAdapter` and workload-manifest interfaces. CS1 must not introduce future runtime types merely to make the harness compile.

## File and Module Map

### Build and distribution

- Modify `settings.gradle.kts` — include `:benchmark-driver`.
- Modify `gradle/libs.versions.toml` — add direct `jmh-core` and `commons-math3` aliases; keep JMH/plugin versions unchanged.
- Modify `build.gradle.kts` — remove root JMH plugin/friend-path/configuration/Kover exclusion and add a root `jmh` lifecycle alias to `:benchmark-driver:benchmarkJmh`.
- Create `benchmark-driver/build.gradle.kts` — application/JMH/test suites, thin JMH classes JAR, real JavaExec launch, distribution layout, and harness self-test.

### Driver interface and target isolation

- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/BenchmarkDriverMain.kt` — CLI dispatch only.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/WorkerProtocol.kt` — parent/child process contract.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultV1.kt` — paired campaign result model.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/json/BenchmarkJson.kt` — strict Moshi read/write/validation.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapter.kt` — the small cross-version seam.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetRuntime.kt` — platform-parent isolated target loader and TCCL lifecycle.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/VerifiedTargetManifest.kt` — pre/post-campaign integrity token and file stamps.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapterRegistry.kt` — exact adapter IDs only.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/baseline/Baseline083f3cd70Adapter.kt` — current public/internal surface via cached reflection/method handles.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1BindingContract.kt` — exact planned lifecycle FQNs/method descriptors.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1Adapter.kt` — approved major surface, contract-tested against fake target classes until CS2a exists.

### Integrity, fixtures, execution, and metrics

- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ContentHasher.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/BuildIdentity.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixture.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/ProcessLauncher.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/JdkProcessLauncher.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/TargetForkMain.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/AlternatingBlockScheduler.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/ColdRunner.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmRunner.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmAllocationRunner.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/RetainedMemoryRunner.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JfrAllocationReader.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/PeakRssProvider.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/FullGcProtocol.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JmhGcResultImporter.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/ControlledHostPolicy.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/HostHealthGate.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/LinuxHostProbe.kt`.
- Create `benchmark-driver/src/main/resources/workloads/v1/lifecycle.no-script-one-step.v1/**` and its manifest.
- Create `benchmark-driver/src/main/resources/jfr/revoman-allocation-v1.jfc`.

### Statistics, gates, schemas, and output

- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/Statistics.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/HierarchicalBootstrap.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/TheilSen.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ResultCompatibility.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/RegressionPolicy.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ReleaseGateEvaluator.kt`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ComparisonReport.kt`.
- Create `benchmark-driver/src/main/resources/schema/revoman-benchmark-v1.schema.json`.
- Create `benchmark-driver/src/main/resources/schema/revoman-benchmark-comparison-v1.schema.json`.
- Create `benchmark-driver/src/main/resources/schema/revoman-target-manifest-v1.schema.json`.
- Create `benchmark-driver/src/main/resources/schema/revoman-controlled-host-v1.schema.json`.
- Create golden/failure fixtures under `benchmark-driver/src/test/resources/` mirroring those packages.

### JMH

- Move `src/jmh/kotlin/com/salesforce/revoman/benchmark/*.kt` to `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/` and adapt them to `TargetAdapter.operation(id)`.
- Create `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/jmh/JmhDriverMain.kt` and `JmhResultImporter.kt`.
- Create `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/HarnessSanityBenchmark.kt`.
- Create `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/HarnessFailureFixtureBenchmark.kt`.
- Create `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/WarmLifecycleAllocationBenchmark.kt`.
- Create `benchmark-driver/src/main/dist/conf/log4j2-benchmark.xml`.

### Target export, CI, and documentation

- Create `benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts` — inject target-manifest export into an unmodified checkout.
- Modify `.github/workflows/build.yml` and `.github/workflows/qodana.yml` — deterministic driver checks/generated sources only.
- Create `.github/workflows/benchmark.yml` — manual controlled-host campaign only.
- Modify `DEVELOPMENT.md`, `docs/superpowers/benchmarks/baseline.md`, and `docs/modules/ROOT/pages/performance.adoc`.
- Add v1 baseline artifacts under `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/`; retain the old smoke text only as explicitly legacy/non-comparable evidence.

---

### Task 1: Scaffold the deep benchmark-driver module and strict worker protocol

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `benchmark-driver/build.gradle.kts`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/BenchmarkDriverMain.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/WorkerProtocol.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/json/BenchmarkJson.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/json/WorkerProtocolJsonTest.kt`
- Test fixture: `benchmark-driver/src/test/resources/protocol/target-command-v1.json`

**Interfaces:**
- Consumes: no ReVoman classes; only JDK/Moshi types.
- Produces: `BenchmarkJson`, `TargetVerificationToken`, `TargetForkCommand`, `TargetForkResult`, `WorkloadRequest`, `ExecutionDigest`, and the `:benchmark-driver` application/test tasks used by every later task.

- [ ] **Step 1: Add the isolated subproject and its failing protocol round-trip test.**

  Add to `settings.gradle.kts`:

  ```kotlin
  include(":benchmark-driver")
  ```

  Add direct aliases to `gradle/libs.versions.toml`:

  ```toml
  commons-math3 = "3.6.1"
  json-schema-validator = "3.0.4"

  jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
  commons-math3 = { module = "org.apache.commons:commons-math3", version.ref = "commons-math3" }
  json-schema-validator = { module = "com.networknt:json-schema-validator", version.ref = "json-schema-validator" }
  ```

  Create `benchmark-driver/build.gradle.kts` with no `project(":")` dependency:

  ```kotlin
  plugins {
    id("revoman.kt-conventions")
    application
    alias(libs.plugins.moshix)
    alias(libs.plugins.jmh)
  }

  dependencies {
    implementation(libs.moshix.adapters)
    implementation(libs.jmh.core)
    implementation(libs.commons.math3)
    implementation(libs.json.schema.validator)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
  }

  application {
    mainClass.set("com.salesforce.revoman.benchmark.driver.BenchmarkDriverMainKt")
  }

  testing {
    suites {
      getByName<JvmTestSuite>("test") {
        useJUnitJupiter(libs.versions.junit.get())
      }
      register<JvmTestSuite>("integrationTest") {
        useJUnitJupiter(libs.versions.junit.get())
        dependencies {
          implementation(project())
          implementation(libs.bundles.kotest)
          implementation(libs.truth)
          implementation(libs.mockk)
        }
      }
    }
  }

  val benchmarkTargetManifest = providers.gradleProperty("benchmark.targetManifest")
  val benchmarkAdapter = providers.gradleProperty("benchmark.adapter")

  tasks.withType<Test>().configureEach {
    benchmarkTargetManifest.orNull?.let {
      systemProperty("revoman.benchmark.targetManifest", it)
    }
    benchmarkAdapter.orNull?.let {
      systemProperty("revoman.benchmark.adapter", it)
    }
  }

  kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
  }
  ```

  Keep `integrationTest` separate from `check`: real-target tests require an explicit target manifest, while ordinary `build` must remain self-contained. The explicit harness self-test in Task 12 supplies the manifest in CI. Never fall back to an ambient checkout or guess an adapter when either property is absent.

  The test must parse `target-command-v1.json`, assert every field, write it to a temporary file, and assert byte-stable canonical JSON after a second read/write. Construct semantically equal commands with parameter maps inserted in opposite orders and require identical bytes.

- [ ] **Step 2: Run the focused test and verify the protocol types are missing.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*WorkerProtocolJsonTest'
  ```

  Expected: compilation fails because `BenchmarkJson`, `TargetForkCommand`, and related model types do not exist.

- [ ] **Step 3: Implement the exact v1 worker protocol and strict codec.**

  `WorkerProtocol.kt` must define:

  ```kotlin
  enum class RunMode { COLD, WARM, RETAINED }
  enum class MetricPass { LATENCY, ALLOCATION, PEAK_RSS, RETAINED }

  @JsonClass(generateAdapter = true)
  data class WorkloadRequest(
    val id: String,
    val contractVersion: Int,
    val fixtureRoot: String,
    val baseUrl: String,
    val parameters: Map<String, String> = emptyMap(),
  )

  @JsonClass(generateAdapter = true)
  data class VerifiedArtifactStamp(
    val logicalId: String,
    val executionPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val fileKey: String?,
  )

  @JsonClass(generateAdapter = true)
  data class TargetVerificationToken(
    val targetManifest: String,
    val targetManifestSha256: String,
    val targetClasspathSha256: String,
    val artifactStamps: List<VerifiedArtifactStamp>,
  )

  @JsonClass(generateAdapter = true)
  data class TargetForkCommand(
    val protocolVersion: Int = 1,
    val verification: TargetVerificationToken,
    val adapterId: String,
    val mode: RunMode,
    val metricPass: MetricPass,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val resultFile: String,
  )

  @JsonClass(generateAdapter = true)
  data class ExecutionDigest(
    val checksum: Long,
    val executedSteps: Int,
    val failureCount: Int,
  )

  @JsonClass(generateAdapter = true)
  data class TargetSample(
    val iteration: Int,
    val latencyNanos: Long,
    val digest: ExecutionDigest,
  )

  @JsonClass(generateAdapter = true)
  data class TargetForkResult(
    val protocolVersion: Int = 1,
    val processId: Long,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val samples: List<TargetSample>,
  )
  ```

  `BenchmarkJson` uses generated adapters with `failOnUnknown()` and atomic replace-on-success writes. Before encoding, canonicalize every JSON object/map recursively by lexicographic key order; arrays keep their schema-defined order. This makes output independent of Kotlin `Map` implementation/insertion order:

  ```kotlin
  internal object BenchmarkJson {
    inline fun <reified T : Any> read(path: Path): T
    inline fun <reified T : Any> write(path: Path, value: T)
    fun validateSchema(path: Path, schemaResource: String)
  }
  ```

  Reject protocol versions other than `1`, malformed 64-character verification hashes, duplicate artifact-stamp logical IDs, negative iteration counts/latencies, blank IDs/paths, and a declared measurement count that differs from `samples.size`. JSON encoding/decoding remains structural and never rereads a mutable manifest path; worker reconstruction compares artifact stamps with the logical IDs/order from its one coherently parsed-and-hashed manifest byte snapshot.

  Until Task 11 replaces CLI dispatch, `BenchmarkDriverMain.kt` implements one real command so the distribution is executable:

  ```kotlin
  fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("version"))) { "Usage: benchmark-driver version" }
    println("revoman-benchmark/v1")
  }
  ```

- [ ] **Step 4: Run protocol tests and module compilation.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*WorkerProtocolJsonTest' :benchmark-driver:installDist
  ./gradlew :benchmark-driver:dependencies --configuration runtimeClasspath
  ```

  Expected: PASS; `benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver` exists, and `dependencies --configuration runtimeClasspath` contains no `revoman-root` project/artifact.

- [ ] **Step 5: Commit the module seam.**

  ```bash
  git add settings.gradle.kts gradle/libs.versions.toml benchmark-driver
  git commit -m "build: add isolated benchmark driver module"
  ```

### Task 2: Define the v1 campaign schema and content identities

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultV1.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/TargetManifest.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/WorkloadManifest.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ContentHasher.kt`
- Create: `benchmark-driver/src/main/resources/schema/revoman-benchmark-v1.schema.json`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultSchemaTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/ContentHasherTest.kt`
- Test fixture: `benchmark-driver/src/test/resources/results/v1/minimal-valid.json`
- Test fixture: `benchmark-driver/src/test/resources/results/v1/invalid-missing-hash.json`
- Test fixture: `benchmark-driver/src/test/resources/results/v1/invalid-unknown-field.json`
- Test fixture: `benchmark-driver/src/test/resources/results/v1/invalid-count.json`
- Test fixture: `benchmark-driver/src/test/resources/results/v1/invalid-both-sample-forms.json`

**Interfaces:**
- Consumes: `BenchmarkJson` from Task 1.
- Produces: `BenchmarkResultV1.validate()`, `TargetManifest.validate()`, `WorkloadManifest`, `ContentHasher.sha256`, `ContentHasher.treeSha256`, and the identity/result types consumed by runners and comparison.

- [ ] **Step 1: Write failing schema and hashing tests.**

  Cover these cases explicitly:

  - `valid v1 campaign round trips canonically`: read `minimal-valid.json`, write it twice, and compare bytes.
  - `unknown result property is rejected`: read `invalid-unknown-field.json` and assert `JsonDataException` names the field.
  - `missing 64 character hash is rejected`: read `invalid-missing-hash.json` and assert validation names the identity.
  - `declared sample count must equal observations`: read `invalid-count.json` and assert expected/actual counts.
  - `raw observations and exact histogram are mutually exclusive`: read `invalid-both-sample-forms.json` and assert the metric path.
  - `tree hash is path sorted and order independent`: hash the same two files in both input orders.
  - `tree hash distinguishes CRLF and LF bytes`: hash byte arrays differing only in line endings.
  - `one byte fixture change changes tree hash`: mutate one byte in a temporary copied fixture.
  - `artifact set identity ignores execution path but not logical id or bytes`: compare two checkout roots and then mutate one logical ID/hash.
  - `artifact set identity preserves executable classpath order`: swap two otherwise identical entries and assert a different hash.
  - `campaign assignments must match target and adapter identities`: change one configured ID and assert validation names it.

- [ ] **Step 2: Run the focused tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*BenchmarkResultSchemaTest' --tests '*ContentHasherTest'
  ```

  Expected: compilation fails because the v1 campaign and hashing types do not exist.

- [ ] **Step 3: Implement the paired campaign model.**

  Use one document for both targets so pairing cannot be reconstructed incorrectly:

  ```kotlin
  enum class RunIntent { CONTROLLED, SMOKE, CI_SELF_TEST }
  enum class TargetRole { BASELINE, CANDIDATE }
  enum class MetricId { LATENCY, ALLOCATED_BYTES, PEAK_RSS, RETAINED_BYTES, BYTES_PER_STEP }
  enum class MetricUnit { NANOSECONDS, NANOSECONDS_PER_OPERATION, BYTES, BYTES_PER_EXECUTION, BYTES_PER_OPERATION }
  enum class GateId {
    COLD_MEDIAN,
    COLD_P95,
    COLD_ALLOCATION,
    COLD_PEAK_RSS,
    WARM_MEDIAN,
    WARM_P95,
    WARM_ALLOCATION,
    RETAINED_SLOPE,
    PER_STEP_ALLOCATION_SPREAD,
  }

  @JsonClass(generateAdapter = true)
  data class BenchmarkResultV1(
    val schema: String = "revoman-benchmark/v1",
    val campaignId: String,
    val intent: RunIntent,
    val createdAt: String,
    val configuration: CampaignConfiguration,
    val harness: HarnessIdentity,
    val environment: EnvironmentIdentity,
    val targets: List<TargetIdentity>,
    val workloads: List<WorkloadResult>,
  ) {
    fun validate(): BenchmarkResultV1
  }

  @JsonClass(generateAdapter = true)
  data class TargetAssignment(
    val role: TargetRole,
    val targetId: String,
    val adapterId: String,
  )

  @JsonClass(generateAdapter = true)
  data class CampaignConfiguration(
    val mode: RunMode,
    val targets: List<TargetAssignment>,
    val metricPasses: List<MetricPass>,
    val seed: Long,
    val requestedAcceptedBlocks: Int,
    val forksPerBlock: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
  )

  @JsonClass(generateAdapter = true)
  data class AlternatingBlock(
    val blockId: Int,
    val targetOrder: List<String>,
    val healthBefore: HostHealthSnapshot,
    val healthDuring: List<HostHealthSnapshot>,
    val healthAfter: HostHealthSnapshot,
    val accepted: Boolean,
    val rejectionReasons: List<String>,
    val observations: List<MetricObservation>,
  )

  @JsonClass(generateAdapter = true)
  data class MetricObservation(
    val targetId: String,
    val metric: MetricId,
    val provider: String,
    val unit: MetricUnit,
    val fork: Int,
    val iteration: Int,
    val replicateGroup: Int? = null,
    val processId: Long,
    val value: Double,
    val retainedEvidence: RetainedEvidence? = null,
  )

  @JsonClass(generateAdapter = true)
  data class RetainedEvidence(
    val executionCount: Int,
    val completedGcCycles: Int,
    val weakReferences: List<WeakReferenceOutcome>,
  )

  @JsonClass(generateAdapter = true)
  data class WeakReferenceOutcome(
    val type: String,
    val created: Int,
    val cleared: Int,
  )

  @JsonClass(generateAdapter = true)
  data class TargetManifest(
    val schema: String = "revoman-target-manifest/v1",
    val targetId: String,
    val gitCommit: String,
    val gitTree: String,
    val dirty: Boolean,
    val gradleVersion: String,
    val wrapperSha256: String,
    val jdk: JdkIdentity,
    val classpath: List<HashedArtifact>,
  ) {
    fun validate(): TargetManifest
  }

  @JsonClass(generateAdapter = true)
  data class HashedArtifact(
    val logicalId: String,
    val executionPath: String,
    val sizeBytes: Long,
    val sha256: String,
  )

  @JsonClass(generateAdapter = true)
  data class JdkIdentity(
    val distribution: String,
    val vendor: String,
    val fullVersion: String,
    val javaHome: String,
    val jvmFlags: List<String>,
  )

  @JsonClass(generateAdapter = true)
  data class AdapterIdentity(val id: String, val sourceSha256: String)

  @JsonClass(generateAdapter = true)
  data class HarnessIdentity(
    val commit: String,
    val tree: String,
    val dirty: Boolean,
    val distributionSha256: String,
    val artifacts: List<HashedArtifact>,
    val workloadContractSha256: String,
    val fixtureSetSha256: String,
    val adapters: List<AdapterIdentity>,
  )

  @JsonClass(generateAdapter = true)
  data class EnvironmentIdentity(
    val jdk: JdkIdentity,
    val osName: String,
    val osVersion: String,
    val kernel: String,
    val cpuModel: String,
    val cpuCount: Int,
    val governor: String,
    val physicalMemoryBytes: Long,
    val hostFingerprintSha256: String,
    val policySha256: String?,
  )

  @JsonClass(generateAdapter = true)
  data class TargetIdentity(
    val id: String,
    val gitCommit: String,
    val gitTree: String,
    val dirty: Boolean,
    val gradleVersion: String,
    val wrapperSha256: String,
    val buildJdk: JdkIdentity,
    val manifestSha256: String,
    val classpathSha256: String,
    val adapter: AdapterIdentity,
  )

  @JsonClass(generateAdapter = true)
  data class HostHealthSnapshot(
    val capturedAtNanos: Long,
    val loadAverage: Double,
    val cpuBusyFraction: Double,
    val availableMemoryBytes: Long,
    val swapUsedBytes: Long,
    val thermalValue: Double,
    val onAcPower: Boolean,
    val governors: List<String>,
  )

  @JsonClass(generateAdapter = true)
  data class WorkloadResult(
    val id: String,
    val contractSha256: String,
    val fixtureSha256: String,
    val mode: RunMode,
    val metricSeries: List<MetricSeries>,
  )

  @JsonClass(generateAdapter = true)
  data class MetricSeries(
    val metric: MetricId,
    val provider: String,
    val providerConfigurationSha256: String,
    val unit: MetricUnit,
    val artifacts: List<HashedArtifact> = emptyList(),
    val blocks: List<AlternatingBlock>? = null,
    val histograms: List<ExactHistogram>? = null,
  ) {
    fun validate(): MetricSeries
  }

  @JsonClass(generateAdapter = true)
  data class ExactHistogram(
    val targetId: String,
    val buckets: List<HistogramBucket>,
  )

  @JsonClass(generateAdapter = true)
  data class HistogramBucket(
    val value: Double,
    val count: Long,
  )

  @JsonClass(generateAdapter = true)
  data class WorkloadManifest(
    val id: String,
    val contractVersion: Int,
    val files: List<HashedArtifact>,
    val fixtureTreeSha256: String,
    val operationIds: List<String>,
    val requiredGatesByMode: Map<RunMode, List<GateId>>,
    val expectedDigest: ExecutionDigest?,
  )
  ```

  `MetricSeries.validate()` requires exactly one of `blocks` or `histograms`; every configured target appears exactly once in the histogram form. Release comparisons require raw hierarchical `blocks`; exact per-target `{value,count}` histograms are valid only for archival/JMH evidence and cannot satisfy a release gate. `MetricObservation.value` is a finite non-negative `Double` so JMH raw values and `gc.alloc.rate.norm` are not truncated; byte-valued providers must still emit mathematically integral doubles. Every child observation must repeat its parent series' metric/provider/unit exactly. `retainedEvidence` and non-null `replicateGroup` are allowed only for `RETAINED_BYTES`; validation permits `0 <= cleared <= created` so leak evidence remains serializable, while equality is enforced only by the release gate. Canonicalize `metricPasses` and every `requiredGatesByMode` value as unique enum-order lists before writing; reject duplicates or noncanonical order on read. Add immutable identity/state models with every field required by design: commit/tree/dirty, adapter/workload/fixture/classpath hashes, JDK distribution/full version/JVM flags, OS/kernel/CPU/governor/memory, explicit role/mode/seed/counts/passes, provider, and samples.

- [ ] **Step 4: Implement unambiguous SHA-256 tree hashing.**

  `ContentHasher.treeSha256(root, files)` hashes:

  ```text
  revoman-benchmark-tree/v1\0
  [4-byte big-endian UTF-8 relative-path length][POSIX relative-path bytes]
  [8-byte big-endian file length][raw file bytes]
  ... repeated in sorted relative-path order
  ```

  Use `MessageDigest.getInstance("SHA-256")` plus `HexFormat.of().formatHex(...)`; require every file to be below `root`, reject duplicates, and never normalize line endings.

  Add `artifactSetSha256(artifacts)` for classpaths. It hashes ordered `logicalId + sizeBytes + sha256` records and deliberately excludes checkout-specific `executionPath`, so independently built A/A manifests compare while `TargetRuntime` can still open each local path.

- [ ] **Step 5: Add the strict Draft 2020-12 schema and golden files.**

  The schema must set:

  ```json
  {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "https://revoman.dev/schema/revoman-benchmark-v1.schema.json",
    "type": "object",
    "additionalProperties": false,
    "required": ["schema", "campaignId", "intent", "createdAt", "configuration", "harness", "environment", "targets", "workloads"],
    "properties": {
      "schema": { "const": "revoman-benchmark/v1" },
      "campaignId": { "type": "string", "minLength": 1 },
      "intent": { "enum": ["CONTROLLED", "SMOKE", "CI_SELF_TEST"] },
      "createdAt": { "type": "string", "format": "date-time" },
      "configuration": { "$ref": "#/$defs/configuration" },
      "harness": { "$ref": "#/$defs/harness" },
      "environment": { "$ref": "#/$defs/environment" },
      "targets": { "type": "array", "minItems": 1, "maxItems": 2, "items": { "$ref": "#/$defs/target" } },
      "workloads": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/workload" } }
    }
  }
  ```

  Complete `$defs` for every Kotlin model field, set `additionalProperties: false` at every object, require lowercase `[0-9a-f]{64}` SHA-256 values, positive block/fork counts, non-negative warmups, positive cold/warm measurement counts, zero retained measurement iterations, exactly one BASELINE and one CANDIDATE assignment for paired campaigns, and finite non-negative observations. The metric-sample definition uses JSON Schema `oneOf` to permit raw blocks or an exact histogram, never both/neither. `BenchmarkJson.validateSchema` loads Draft 2020-12 through NetworkNT 3.0.4 and must report every validation message; run every valid/invalid golden through the actual schema in addition to typed Moshi parsing.

- [ ] **Step 6: Run result/schema/hash tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*BenchmarkResultSchemaTest' --tests '*ContentHasherTest'
  ```

  Expected: PASS; valid JSON is byte-stable and every invalid fixture fails with its named invariant.

- [ ] **Step 7: Commit the machine contract.**

  ```bash
  git add benchmark-driver/src/main benchmark-driver/src/test
  git commit -m "feat: define benchmark result v1 contract"
  ```

### Task 3: Implement isolated target runtimes and the two versioned adapters

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapter.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetRuntime.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/VerifiedTargetManifest.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapterRegistry.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/ReflectiveTarget.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/baseline/Baseline083f3cd70Adapter.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1BindingContract.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target/major/MajorV1Adapter.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetRuntimeTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target/TargetAdapterContractTest.kt`
- Test utility: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target/FakeTargetJarBuilder.kt`

**Interfaces:**
- Consumes: `WorkloadRequest` and `ExecutionDigest` from Task 1. Task 4 hashes the completed adapter source trees into `HarnessIdentity`; runtime descriptors contain only stable ID/surface version.
- Produces: the only interface allowed to know target-version details:

  ```kotlin
  data class AdapterDescriptor(
    val id: String,
    val surfaceVersion: Int,
  )

  interface TargetAdapter {
    val descriptor: AdapterDescriptor
    fun prepare(runtime: TargetRuntime, request: WorkloadRequest): PreparedWorkload
  }

  interface PreparedWorkload : AutoCloseable {
    fun execute(): ExecutionDigest
    fun operation(id: String): TargetOperation
  }

  fun interface TargetOperation {
    fun invoke(): Long
  }
  ```

- [ ] **Step 1: Write failing target-runtime and adapter contract tests.**

  `FakeTargetJarBuilder` compiles tiny Java-only target surfaces into temporary JARs with `ToolProvider.getSystemJavaCompiler()`. Tests must prove:

  - `platform parent isolates target classes and restores context classloader`
  - `closing target runtime closes its URL classloader`
  - `adapter never returns a target class across the seam`
  - `registry requires exact baseline or major adapter id`
  - `baseline and major fake surfaces produce the same scalar digest`
  - `major adapter rejects unsupported component operation ids explicitly`
  - `target preflight rejects a classpath file whose bytes no longer match the manifest`
  - `timed open performs no content hashing after preflight verification`
  - `postflight byte change invalidates the whole campaign`

- [ ] **Step 2: Run target tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*TargetRuntimeTest' --tests '*TargetAdapterContractTest'
  ```

  Expected: compilation fails because the target seam and adapters do not exist.

- [ ] **Step 3: Implement the platform-parent isolated runtime and reflective lookup cache.**

  `VerifiedTargetManifest.preflight(manifest)` runs in the controller outside all timed blocks: validate canonical paths, size, and SHA-256 for every classpath entry, then retain the canonical manifest hash, ordered artifact-set hash, and cheap file stamps (path/file-key/size/mtime). The controller serializes the two hashes into every worker command. A worker re-parses the manifest, requires those identities and unchanged cheap stamps, but never rereads full JAR bytes. After the final block, `postflight()` rehashes every artifact; any content/path/stamp change invalidates the entire campaign. JMH performs the same controller preflight/fork-token/postflight sequence.

  `TargetRuntime.open(verifiedManifest)` derives URLs from `verifiedManifest.manifest.classpath.map { Path.of(it.executionPath).toUri().toURL() }`, then creates `URLClassLoader(urls, ClassLoader.getPlatformClassLoader())`, installs it as TCCL only inside `withTargetContext`, and restores the previous TCCL in `finally`:

  ```kotlin
  data class VerifiedTargetManifest internal constructor(
    val manifest: TargetManifest,
    val manifestSha256: String,
    val classpathSha256: String,
    val artifactStamps: List<VerifiedArtifactStamp>,
  ) {
    fun postflight()

    companion object {
      fun preflight(manifestPath: Path): VerifiedTargetManifest
      fun fromWorkerCommand(command: TargetForkCommand): VerifiedTargetManifest
    }
  }

  class TargetRuntime private constructor(private val loader: URLClassLoader) : AutoCloseable {
    fun loadClass(name: String): Class<*> = loader.loadClass(name)
    fun <T> withTargetContext(block: () -> T): T
    override fun close()

    companion object {
      fun open(manifest: VerifiedTargetManifest): TargetRuntime
    }
  }
  ```

  `ReflectiveTarget` caches `Class`, `Method`, `Field`, and `MethodHandle` lookups once during `prepare`; `TargetOperation.invoke()` must not repeat name lookup.

- [ ] **Step 4: Implement exact adapter registration and scalar-only results.**

  Registry IDs are exactly:

  ```kotlin
  object TargetAdapterRegistry {
    fun require(id: String): TargetAdapter = when (id) {
      "baseline-83f3cd70" -> Baseline083f3cd70Adapter
      "major-v1" -> MajorV1Adapter
      else -> error("Unknown target adapter: $id")
    }
  }
  ```

  The baseline adapter binds current `Kick.configure()`, builder methods, `ReVoman.revUp(Kick)`, `Rundown.stepReports`, current hooks/polling/sinks, and the five existing micro-operation surfaces. Both adapters reduce results to `ExecutionDigest`/`Long`, set local target objects to null before returning, and close all target-owned resources in `PreparedWorkload.close()`.

  `MajorV1BindingContract` makes the not-yet-implemented surface executable rather than aspirational. It pins the owners/member descriptors used by `lifecycle.no-script-one-step.v1`: `com.salesforce.revoman.input.config.Kick.configure`, the opaque builder's `templatePath(String)`, `dynamicEnvironment(String,Object)`, `insecureHttp(boolean)`, and `off()`, `com.salesforce.revoman.ReVoman.revUp(Kick)`, plus `com.salesforce.revoman.output.Rundown.executedStepCount(): int` and `unsuccessfulStepCount(): int`. Matching Java fake classes use those exact FQNs and descriptors. In CS1, `major-v1` supports only the lifecycle contract; baseline-only component operation IDs fail explicitly until their owning runtime change set extends this contract and changes the adapter source hash.

- [ ] **Step 5: Run target seam tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*TargetRuntimeTest' --tests '*TargetAdapterContractTest'
  ```

  Expected: PASS; a weak reference to a fake target classloader clears after `TargetRuntime.close()` and GC retry, and no model field has a class loaded by the target loader.

- [ ] **Step 6: Commit the version seam.**

  ```bash
  git add benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/target benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/target
  git commit -m "feat: isolate benchmark target versions"
  ```

### Task 4: Replace the broken JMH uber-JAR launch with strict classpath execution

**Files:**
- Modify: `build.gradle.kts`
- Modify: `benchmark-driver/build.gradle.kts`
- Move: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SmokeBenchmark.kt`
- Move: `src/jmh/kotlin/com/salesforce/revoman/benchmark/RegexVarBenchmark.kt`
- Move: `src/jmh/kotlin/com/salesforce/revoman/benchmark/MarshallingBenchmark.kt`
- Move: `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt`
- Move: `src/jmh/kotlin/com/salesforce/revoman/benchmark/EnvAccumBenchmark.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/jmh/JmhDriverMain.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/jmh/JmhResultImporter.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/JmhBenchmarkResultV1.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/BuildIdentity.kt`
- Create: `benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts`
- Create: `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/HarnessSanityBenchmark.kt`
- Create: `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/HarnessFailureFixtureBenchmark.kt`
- Create: `benchmark-driver/src/main/dist/conf/log4j2-benchmark.xml`
- Create: `benchmark-driver/src/main/resources/workloads/v1/jmh.component-operations.v1/manifest.json`
- Create: `benchmark-driver/src/main/resources/workloads/v1/jmh.component-operations.v1/composite-response.json`
- Create: `benchmark-driver/src/main/resources/workloads/v1/jmh.component-operations.v1/postman-test-script.js`
- Create: `benchmark-driver/src/main/resources/workloads/v1/jmh.component-operations.v1/regex-inputs.json`
- Create: `benchmark-driver/src/main/resources/schema/revoman-benchmark-jmh-v1.schema.json`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/jmh/JmhDriverMainTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/jmh/JmhResultImporterTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/BuildIdentityTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/model/JmhBenchmarkResultSchemaTest.kt`
- Test fixture: `benchmark-driver/src/test/resources/jmh/valid.json`
- Test fixture: `benchmark-driver/src/test/resources/jmh/empty.json`
- Test fixture: `benchmark-driver/src/test/resources/jmh/missing-raw-data.json`
- Test fixture: `benchmark-driver/src/test/resources/jmh-result/v1/minimal-valid.json`

**Interfaces:**
- Consumes: `TargetAdapter.operation(id)` from Task 3 and campaign models from Task 2.
- Produces: `runJmh(args, execute)`, `JmhResultImporter.import`, strict single-target
  `JmhBenchmarkResultV1` (`revoman-benchmark-jmh/v1`), `:benchmark-driver:benchmarkJmh`,
  `:benchmark-driver:benchmarkHarnessSelfTest`, and the root `jmh` alias.

- [ ] **Step 1: Write failing strict-runner and result-import tests.**

  Test the injectable entry directly:

  ```kotlin
  @Test fun `runner forces fail on error`() {
    var captured: Options? = null
    runJmh(arrayOf("SmokeBenchmark")) { options ->
      captured = options
      listOf(mockk<RunResult>())
    }
    captured!!.shouldFailOnError().orElse(false) shouldBe true
  }

  @Test fun `empty result collection fails`() {
    shouldThrow<IllegalStateException> { runJmh(emptyArray()) { emptyList() } }
  }

  @Test fun `runner exception propagates`() {
    shouldThrow<RunnerException> { runJmh(emptyArray()) { throw RunnerException("fork failed") } }
  }
  ```

  Import tests must reject `[]`, reject records without `primaryMetric.rawData`, and map raw fork arrays plus `gc.alloc.rate.norm` into `MetricObservation` without averaging away fork/iteration identity.

  `JmhBenchmarkResultSchemaTest` requires full harness/environment/single-target/workload identity,
  requested include/fork configuration, provider/unit metadata, and either exact raw
  fork/iteration observations or an exact histogram. Every object rejects unknown properties;
  canonical writes order unordered evidence deterministically.

  `BuildIdentityTest` proves source-file enumeration is path-sorted, one adapter/workload byte changes its hash, dirty Git state is recorded, runtime artifact-set hashing ignores installation paths, and the embedded source manifest never contains its own distribution hash.

- [ ] **Step 2: Run the JMH driver tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*JmhDriverMainTest' --tests '*JmhResultImporterTest' --tests '*BuildIdentityTest'
  ```

  Expected: compilation fails because the strict runner/importer do not exist.

- [ ] **Step 3: Implement pre-JMH harness identity, the strict entry, and importer.**

  ```kotlin
  internal fun runJmh(
    args: Array<String>,
    execute: (Options) -> Collection<RunResult> = { Runner(it).run() },
  ): Collection<RunResult> {
    val commandLine = CommandLineOptions(args)
    val options =
      OptionsBuilder()
        .parent(commandLine)
        .shouldFailOnError(true)
        .build()
    return execute(options).also { check(it.isNotEmpty()) { "JMH produced no result rows" } }
  }
  ```

  Before the first JMH result can be written, configure a generated `benchmark-harness-source-v1.json`. `BuildIdentity` records clean/dirty Git commit/tree plus path-sorted SHA-256 identities for both adapter source trees and every current/future workload, fixture, schema, JFC, logging, and benchmark-contract input. Gradle declares those trees as task inputs, so files added in later tasks automatically change the generated identity. The embedded source manifest deliberately contains no distribution hash. At runtime, `HarnessIdentity.distributionSha256` is computed from the declared runtime artifact set—all installed `lib/*.jar` files including original third-party dependencies and the thin benchmark JAR, plus schemas, workloads, JFC, logging config, and init script—using logical paths/sizes/bytes while ignoring installation roots. Because the hash lives only in the output result, it is not self-referential.

  `main` lets `RunnerException`, malformed options, and empty results escape so JavaExec exits nonzero. `JmhResultImporter` validates requested include regexes against returned benchmark names and preserves `rawData[fork][iteration]`; it never treats a human-readable header as a result row.

  After `Runner.run()` succeeds, invoke `JmhResultImporter` with harness/target/workload metadata
  and atomically write `build/results/jmh/revoman-benchmark-jmh-v1.json`; the raw JMH JSON remains
  alongside it. This document uses the strict single-target `revoman-benchmark-jmh/v1` schema.
  It must never masquerade as, weaken, or duplicate evidence into paired
  `revoman-benchmark/v1`. Task 11 alone imports these observations when assembling a real paired
  campaign.

- [ ] **Step 4: Move benchmarks and replace direct target imports with prepared operations.**

  Preserve class names, params, modes, warmup, and measurement settings. Move the current benchmark's composite JSON, sandbox script, and regex inputs byte-for-byte into the versioned `jmh.component-operations.v1` resources; its manifest hashes every file and the operation-ID contract and explicitly sets COLD, WARM, and RETAINED required-gate lists to canonical empty lists because this workload is archival/component evidence, not a macro release suite. The JMH controller preflights the supplied target once, writes the verified hashes/stamps to an immutable token JSON file, appends only that canonical token path and SHA-256 to fork JVM properties, and postflights in `finally`; never serialize the full classpath into JVM arguments. Each JMH state validates the small token hash, reconstructs `VerifiedTargetManifest` without target-JAR content hashing, selects the explicit adapter, creates a `WorkloadRequest` for that manifest (including JMH params such as step count), calls `prepare` in `@Setup(Level.Trial)`, caches one `TargetOperation`, and closes `PreparedWorkload` plus `TargetRuntime` in `@TearDown(Level.Trial)`. Use these stable operation IDs:

  ```text
  smoke.sum-range
  regex.mixed-strings
  regex.large-environment
  marshalling.composite-from-json
  marshalling.composite-to-json
  sandbox.postman-test-script
  environment.accumulate-and-snapshot
  graal.open-engine
  ```

  The failure fixture throws only when included explicitly as `HarnessFailureFixtureBenchmark`; exclude it from the default suite.

- [ ] **Step 5: Rewire Gradle to a thin generated-classes JAR and original JAR classpath.**

  Remove the JMH plugin alias, root `kotlin.target.compilations.named("jmh")`, root JMH block, and root Kover `jmh` exclusion from `build.gradle.kts`. Register a root lifecycle alias:

  ```kotlin
  tasks.register("jmh") {
    group = "benchmark"
    dependsOn(":benchmark-driver:benchmarkJmh")
  }
  ```

  In `benchmark-driver/build.gradle.kts`, set `jmh.includeTests = false`, create `benchmarkJmhClassesJar`, add that thin JAR to the application distribution under a fixed name, and launch a real JavaExec from the installed original-JAR classpath:

  ```kotlin
  val generatedClasses = layout.buildDirectory.dir("jmh-generated-classes")
  val generatedResources = layout.buildDirectory.dir("jmh-generated-resources")

  val benchmarkJmhClassesJar by tasks.registering(Jar::class) {
    dependsOn("jmhCompileGeneratedClasses")
    archiveClassifier.set("jmh-classes")
    from(sourceSets["jmh"].output)
    from(generatedClasses)
    from(generatedResources)
  }

  distributions.named("main") {
    contents {
      from(benchmarkJmhClassesJar) {
        into("lib")
        rename { "benchmark-driver-jmh-classes.jar" }
      }
    }
  }

  tasks.named("installDist") {
    dependsOn(benchmarkJmhClassesJar)
  }

  val benchmarkJmh by tasks.registering(JavaExec::class) {
    dependsOn("installDist")
    mainClass.set("com.salesforce.revoman.benchmark.driver.jmh.JmhDriverMainKt")
    val installedLib = layout.buildDirectory.dir("install/benchmark-driver/lib")
    classpath(
      providers.provider {
        installedLib.get().asFile.listFiles { file -> file.extension == "jar" }!!
          .sortedBy { it.name }
      },
    )
  }
  ```

  Before execution, delete prior JSON/human output, require the fixed thin JAR, reject any classpath filename ending `-jmh.jar`, and pass JMH JSON/result paths plus include/quick properties. This makes Gradle JMH and installed macro/JMH runners share the same runtime artifact-set identity. Disable the plugin's default `:benchmark-driver:jmh` and `jmhJar` tasks with a message directing callers to `benchmarkJmh`; neither may be in `benchmarkJmh --dry-run`.

  Map Gradle properties `benchmark.includes`, `benchmark.quick`, `benchmark.forks`, and `benchmark.profilers` to JMH options. `benchmark.rawJmhOutput` and `benchmark.resultOutput` select the raw JMH JSON and normalized single-target `revoman-benchmark-jmh/v1` paths; the normalized default is `benchmark-driver/build/results/jmh/revoman-benchmark-jmh-v1.json`. Delete prior files and require atomic successful replacements. Map `benchmark.targetManifest` and `benchmark.adapter` to controller system properties; `JmhDriverMain` appends these same values to every JMH fork:

  ```text
  revoman.benchmark.targetManifest = canonical absolute supplied manifest path
  revoman.benchmark.adapter = exact supplied adapter ID
  ```

- [ ] **Step 6: Add the unmodified-target manifest exporter needed by JMH and macro runs.**

  Add the distribution-owned init script now, before any target-backed JMH smoke. It is standalone Gradle/JDK Kotlin—no imports or classpath dependency on benchmark-driver or target classes—so the source copy can run before `installDist` and the installed copy can run against revision 83f3cd70. It injects `writeBenchmarkTargetManifest` into the selected checkout, requires explicit `benchmark.targetId` and `benchmark.targetManifest` properties, depends on that checkout's normal `jar`, and writes a validated `TargetManifest` using the normal target JAR first followed by original dependency JARs in Gradle's resolved `runtimeClasspath` order. Never alphabetize the executable classpath: order is part of identity and class-shadowing semantics. It records Git commit/tree/dirty, Gradle/wrapper/JDK identity, and SHA-256 for every classpath file; it rejects directories and `*-jmh.jar`. Build the ordered JSON object with only Gradle-bundled/JDK facilities; the driver performs strict schema/typed validation after export.

  Export the current target for subsequent steps:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ```

- [ ] **Step 7: Install benchmark-only logging for the controller and every fork.**

  `log4j2-benchmark.xml` has no appenders and root `OFF`. Add a `benchmark.logConfig` Gradle property. Source-checkout tasks default it to the canonical absolute URI of `benchmark-driver/src/main/dist/conf/log4j2-benchmark.xml`; installed CLI/controlled runs resolve the installed `conf/log4j2-benchmark.xml`; a supplied override must be an existing absolute file. `benchmarkJmh` passes the resolved URI into the controller and `JmhDriverMain` appends it to every fork:

  ```text
  -Dlog4j2.configurationFile=file:/opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/conf/log4j2-benchmark.xml
  -Drevoman.banner=off
  ```

  The pinned Log4j 3 beta also receives its global-context equivalent
  `-Dlog4j2.*.Configuration.file=<same URI>`, and
  `-Dkotlin-logging.logStartupMessage=false` suppresses its unconditional startup line. The
  required compatibility property above remains present for both controller and forks.

  Do not alter target `RunLogSink.NoOp` installation or target production logging files.

- [ ] **Step 8: Add deterministic JMH classpath and failure self-tests.**

  `benchmarkHarnessSelfTest` must:

  1. inspect the thin JAR and reject flattened `org/graalvm/**` entries;
  2. find the original `truffle-api-25.2.4.jar` in the supplied target manifest and assert its manifest has `Multi-Release: true`;
  3. run `HarnessSanityBenchmark` and require at least one JSON row;
  4. launch the intentional failure fixture in a child Java process and require a nonzero exit; and
  5. launch a definitely-unmatched include and require a nonzero exit.

  It also executes the standalone source init script against the current checkout and validates the result through `TargetManifestLoader`, proving the exporter does not accidentally rely on driver classes being present in the target build.

- [ ] **Step 9: Run the repaired JMH path.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test \
    :benchmark-driver:benchmarkHarnessSelfTest \
    :benchmark-driver:benchmarkJmh \
    -Pbenchmark.includes=HarnessSanityBenchmark \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.adapter=baseline-83f3cd70 \
    -Pbenchmark.quick=true
  ./gradlew :benchmark-driver:benchmarkJmh --dry-run
  ```

  Expected: tests and self-test PASS; JSON contains a row; dry-run contains `jmhCompileGeneratedClasses` and `benchmarkJmhClassesJar` but not `jmhJar`; no ReVoman INFO/banner output appears.

- [ ] **Step 10: Re-run one Graal-using benchmark as the original regression.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:benchmarkJmh \
    -Pbenchmark.includes='RegexVarBenchmark.replaceVariablesRecursivelyOverMixedStrings' \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.adapter=baseline-83f3cd70 \
    -Pbenchmark.quick=true
  ```

  Expected: PASS, non-empty JSON, and no `Multi-Release classes are not configured correctly` failure.

- [ ] **Step 11: Commit the JMH repair.**

  ```bash
  git add build.gradle.kts benchmark-driver src/jmh
  git commit -m "build: run JMH from original dependency jars"
  ```

### Task 5: Add the deterministic lifecycle workload and baseline adapter integration

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixture.kt`
- Create: `benchmark-driver/src/main/resources/workloads/v1/lifecycle.no-script-one-step.v1/manifest.json`
- Create: `benchmark-driver/src/main/resources/workloads/v1/lifecycle.no-script-one-step.v1/collection.postman_collection.json`
- Create: `benchmark-driver/src/main/resources/workloads/v1/lifecycle.no-script-one-step.v1/handler.json`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/fixture/DeterministicHttpFixtureTest.kt`
- Integration test: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/target/BaselineAdapterIntegrationTest.kt`

**Interfaces:**
- Consumes: baseline adapter/target runtime from Task 3 and identity hashing from Task 2.
- Produces: versioned workload `lifecycle.no-script-one-step.v1`, loopback fixture routes, and a verified baseline-compatible adapter path using `WorkloadManifest` from Task 2.

- [ ] **Step 1: Write failing deterministic-server and baseline-adapter tests.**

  Server tests assert binding to `127.0.0.1`, byte-identical response bodies/headers, per-execution request counters, and immediate failure for an unregistered route. The baseline integration test must execute:

  ```kotlin
  val request =
    WorkloadRequest(
      id = "lifecycle.no-script-one-step.v1",
      contractVersion = 1,
      fixtureRoot = materializedFixture.toString(),
      baseUrl = server.baseUrl,
    )
  val digest = Baseline083f3cd70Adapter.prepare(runtime, request).use { it.execute() }
  digest.executedSteps shouldBe 1
  digest.failureCount shouldBe 0
  ```


- [ ] **Step 2: Run the focused tests and verify failure.**

  Run:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ./gradlew :benchmark-driver:test --tests '*DeterministicHttpFixtureTest' \
    :benchmark-driver:integrationTest --tests '*BaselineAdapterIntegrationTest' \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  ```

  Expected: compilation or fixture lookup fails because the deterministic server/workload does not exist.

- [ ] **Step 3: Implement the loopback fixture outside timed regions.**

  `DeterministicHttpFixture.open(manifest)` starts one JDK `HttpServer` on `InetSocketAddress("127.0.0.1", 0)`. Register exact method/path pairs from `handler.json`; `/small` returns status 200, `Content-Type: application/json`, and the fixed body `{"ok":true}`. Unknown routes return 500 and record a contract violation that fails the campaign. Reset counters by execution ID before each measured call, not by restarting the server.

- [ ] **Step 4: Implement and verify the frozen workload manifest.**

  `manifest.json` contains exact workload ID, contract version, fixture file list, per-file SHA-256, aggregate tree hash, adapter operation IDs, expected digest, and canonical required gates. For `lifecycle.no-script-one-step.v1`, COLD requires `COLD_MEDIAN`, `COLD_P95`, `COLD_ALLOCATION`, and `COLD_PEAK_RSS`; WARM requires `WARM_MEDIAN`, `WARM_P95`, and `WARM_ALLOCATION`; RETAINED has no required gate in CS1. Tests materialize resources to a temporary directory, recompute all hashes, and reject an extra/missing/changed byte or reordered/duplicate gate.

  `collection.postman_collection.json` contains exactly one GET and no `event`/script field:

  ```json
  {
    "info": {
      "name": "lifecycle.no-script-one-step.v1",
      "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
    },
    "item": [
      {
        "name": "one-step",
        "request": {
          "method": "GET",
          "header": [],
          "url": {
            "raw": "{{baseUrl}}/small",
            "host": ["{{baseUrl}}"],
            "path": ["small"]
          }
        },
        "response": []
      }
    ]
  }
  ```

  `handler.json` declares GET `/small`, status `200`, header `Content-Type: application/json`, and UTF-8 body `{"ok":true}`.

- [ ] **Step 5: Complete baseline reflective preparation.**

  Baseline preparation must perform the equivalent of:

  ```kotlin
  Kick.configure()
    .templatePath(collectionPath)
    .dynamicEnvironment("baseUrl", baseUrl)
    .insecureHttp(true)
    .off()
  ReVoman.revUp(kick)
  ```

  It uses cached reflection/method handles, builds a fresh Kick inside every measured execution, reduces Rundown to report/failure counts, and drops the Rundown reference before returning. Fixture/server setup and target-class lookup happen in `prepare`, outside measurement.

- [ ] **Step 6: Run workload and integration tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*DeterministicHttpFixtureTest' \
    :benchmark-driver:integrationTest --tests '*BaselineAdapterIntegrationTest' \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  ```

  Expected: PASS; one request reaches `/small` and the digest is stable across ten executions.

- [ ] **Step 7: Commit the first macro workload.**

  ```bash
  git add benchmark-driver/src/main benchmark-driver/src/test benchmark-driver/src/integrationTest
  git commit -m "feat: add deterministic lifecycle benchmark workload"
  ```

### Task 6: Implement fresh-process cold and forked warm runners

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/ProcessLauncher.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/JdkProcessLauncher.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/TargetForkMain.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/ColdRunner.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmRunner.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/VerifiedLoggingConfiguration.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/ColdRunnerTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmRunnerTest.kt`
- Integration test: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/process/RunnerIntegrationTest.kt`
- Integration test: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/process/LoggingSuppressionIntegrationTest.kt`

**Interfaces:**
- Consumes: worker protocol and `MetricPass` from Task 1, target adapter seam from Task 3, workload from Task 5.
- Produces:

  ```kotlin
  data class JavaCommand(
    val executable: Path,
    val jvmArgs: List<String>,
    val classpath: List<Path>,
    val mainClass: String,
    val programArgs: List<String>,
    val workingDirectory: Path,
    val timeout: Duration,
  )

  data class ProcessObservation(
    val exitCode: Int,
    val processId: Long,
    val elapsedNanos: Long,
    val stdoutTail: String,
    val stderrTail: String,
    val result: TargetForkResult,
  )

  data class ColdPlan(
    val intent: RunIntent,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val sampleCount: Int,
    val metricPass: MetricPass,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
  )

  data class WarmPlan(
    val intent: RunIntent,
    val target: TargetManifest,
    val targetManifestPath: Path,
    val adapterId: String,
    val workload: WorkloadRequest,
    val expectedDigest: ExecutionDigest?,
    val forksPerBlock: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val metricPass: MetricPass,
    val timeout: Duration,
    val loggingConfiguration: VerifiedLoggingConfiguration,
  )

  fun interface ProcessLauncher {
    fun launch(command: JavaCommand): ProcessObservation
  }

  class ColdRunner(private val launcher: ProcessLauncher) {
    fun run(plan: ColdPlan): List<MetricObservation>
  }

  class WarmRunner(private val launcher: ProcessLauncher) {
    fun run(plan: WarmPlan): List<MetricObservation>
  }
  ```

- [ ] **Step 1: Write failing fake-launcher structure tests.**

  Prove:

  - `fifty controlled cold samples request fifty distinct processes`
  - `warm mode launches one process per fork`
  - `warmups never appear in measured observations`
  - `nonzero exit timeout malformed output and empty output fail the run`
  - `stdout and stderr tails are bounded and unexpected output invalidates the sample`
  - `benchmark log configuration leaves target stdout and stderr empty`
  - `wrong checksum executedSteps or failureCount invalidates cold and warm observations`
  - `warmup digest failure exits before any measurement or result publication`
  - `same-classpath manifest and quiet logging changes invalidate all observations`
  - `recursive cleanup preserves the primary launch or timeout failure`

- [ ] **Step 2: Run runner tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*ColdRunnerTest' --tests '*WarmRunnerTest'
  ```

  Expected: compilation fails because runner/process types do not exist.

- [ ] **Step 3: Implement argument-list-only child JVM launching.**

  `JavaCommand` holds executable, JVM args, classpath entries, main class, program args, timeout, and working directory as lists/paths—never a shell string. `JdkProcessLauncher` drains stdout/stderr concurrently, caps retained diagnostic tails at 64 KiB each, measures parent wall time with `System.nanoTime`, kills the process tree on timeout, and rejects stale or in-place writes with a guarded-inode replacement check. The trusted `TargetForkMain` publisher uses `BenchmarkJson.write`'s same-directory `ATOMIC_MOVE`; the guard is deliberately not presented as proof of the underlying filesystem event.

- [ ] **Step 4: Implement TargetForkMain.**

  It reads `TargetForkCommand`, reconstructs the preflight token from one coherently parsed-and-hashed manifest byte snapshot, checks cheap artifact stamps without target-JAR content hashing, opens `TargetRuntime`, prepares one workload, validates every unrecorded warmup against `expectedDigest`, then records and validates every execution separately:

  ```kotlin
  val expected = requireNotNull(command.expectedDigest)
  repeat(command.warmupIterations) { iteration ->
    requireExpectedExecutionDigest(prepared.execute(), expected, "warmup[$iteration]")
  }
  val samples =
    List(command.measurementIterations) { iteration ->
      var digest: ExecutionDigest? = null
      val nanos = measureNanoTime { digest = prepared.execute() }
      val validated =
        requireExpectedExecutionDigest(requireNotNull(digest), expected, "measurement[$iteration]")
      TargetSample(iteration, nanos, validated)
    }
  ```

  Write `TargetForkResult` only after every sample succeeds; emit no stdout/stderr.

- [ ] **Step 5: Implement cold and warm structure.**

  The campaign/controller performs one full target preflight before scheduling. Manifest parsing and hashing use the same captured bytes in controller, worker, and postflight; postflight also rehashes the manifest source itself. Each plan carries its canonical `targetManifestPath` and a `VerifiedLoggingConfiguration` containing the canonical source plus its exact byte hash; the core runner never consults ambient benchmark target/logging properties. It materializes one private immutable logging snapshot for every fork, exposes the verified hash for Task 11 provider/result identity, and postflights both source and snapshot. Cold launches one child with one measured execution per observation and uses parent process duration as the primary end-to-end latency. Warm launches one child per fork, uses target-reported per-execution samples after warmup, and records PID/fork/iteration. The parent independently validates every returned digest before reducing it to a `MetricObservation`; no execution with `failureCount > 0` is accepted. Cold/warm macro plans reject a null oracle for every intent; nullable worker commands remain only for component/JMH preparation, which does not call macro `execute()`. After all processes—even on failure—the controller runs target/logging postflight hashing and recursive campaign cleanup with primary-failure preservation; an integrity mismatch invalidates all observations. A `CONTROLLED` cold plan requires `sampleCount >= 50`; every `WarmPlan` requires only `forksPerBlock > 0` because it models one block. Task 11 owns the aggregate controlled-warm minimum of five independent forks and the CS1 shape of five blocks times one fork per block. Smoke cold plans may use smaller counts but carry `RunIntent.SMOKE`.

- [ ] **Step 6: Run fake and real-process integration tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*ColdRunnerTest' --tests '*WarmRunnerTest' \
    :benchmark-driver:integrationTest --tests '*RunnerIntegrationTest' --tests '*LoggingSuppressionIntegrationTest' \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  ```

  Expected: PASS; three smoke cold observations have three unique PIDs; two smoke warm forks have exactly two PIDs; no warmup is serialized as a measurement.

- [ ] **Step 7: Commit process runners.**

  ```bash
  git add benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run benchmark-driver/src/test benchmark-driver/src/integrationTest
  git commit -m "feat: add cold and warm benchmark runners"
  ```

### Task 7: Add allocation, peak-RSS, and retained-memory metric providers

**Files:**
- Modify: `benchmark-driver/build.gradle.kts`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/WorkerProtocol.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/model/BenchmarkResultV1.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/JdkProcessLauncher.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/process/TargetForkMain.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/ColdRunner.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmRunner.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JfrAllocationReader.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/PeakRssProvider.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/FullGcProtocol.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JmhGcResultImporter.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmAllocationRunner.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/RetainedMemoryRunner.kt`
- Create: `benchmark-driver/src/jmh/kotlin/com/salesforce/revoman/benchmark/WarmLifecycleAllocationBenchmark.kt`
- Create: `benchmark-driver/src/main/resources/jfr/revoman-allocation-v1.jfc`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JfrAllocationReaderTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/metrics/PeakRssProviderTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/metrics/FullGcProtocolTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/metrics/JmhGcResultImporterTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/WarmAllocationRunnerTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/RetainedMemoryRunnerTest.kt`
- Test fixture: `benchmark-driver/src/test/resources/metrics/gnu-time-linux.txt`
- Test fixture: `benchmark-driver/src/test/resources/metrics/bsd-time-macos.txt`
- Test fixture: `benchmark-driver/src/test/resources/metrics/jmh-gc.txt`

**Interfaces:**
- Consumes: `ProcessLauncher`/observations from Task 6, worker protocol from Task 1, and metric/retained-evidence models from Task 2.
- Produces: runnable cold JFR/peak-RSS, warm JMH-allocation, and retained-memory passes with immutable provider identity/configuration hashes.

- [ ] **Step 1: Write failing metric parser and pass-separation tests.**

  Cover exact units and contamination rules:

  - `JFR sums in-TLAB and outside-TLAB allocation sizes`
  - `GNU time converts maximum RSS KiB to bytes`
  - `macOS time keeps maximum RSS bytes`
  - `JMH importer requires gc alloc rate norm`
  - `cold latency and JFR allocation use distinct child processes`
  - `time provider output never appears in target stderr`
  - `warm allocation launches the lifecycle JMH benchmark with five controlled forks`
  - `two full GC acknowledgements are required before retained sample`
  - `retained checkpoints preserve execution count replicate and weak reference outcomes`

- [ ] **Step 2: Run metric tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*JfrAllocationReaderTest' --tests '*PeakRssProviderTest' --tests '*FullGcProtocolTest' --tests '*JmhGcResultImporterTest' --tests '*WarmAllocationRunnerTest' --tests '*RetainedMemoryRunnerTest'
  ```

  Expected: compilation fails because provider types do not exist.

- [ ] **Step 3: Implement JFR allocation as a separate cold pass.**

  The checked-in JFC enables `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB`. `JfrAllocationReader` uses `RecordingFile` and follows the JDK 21 allocation view: sum `tlabSize` for new-TLAB events and `allocationSize` for outside-TLAB events with checked `Long` arithmetic. Name the provider `jdk21-jfr-tlab-reserved-plus-outside/v1`; this is allocated/reserved TLAB bytes plus outside-TLAB object bytes, not live or retained bytes. Reject missing events, overflow, or a recording whose configuration hash differs from result metadata. `ColdRunner` creates separate LATENCY/RSS and ALLOCATION campaigns; it never enables JFR for the latency process.

  Extend `TargetForkCommand` with nullable `jfrConfigurationFile` and `jfrRecordingFile`, required only for the cold ALLOCATION pass. `TargetForkMain` loads the checked-in JFC with the JFR API, starts a programmatic `Recording` before opening `TargetRuntime`/preparing the workload, stops/dumps it after the measured execution, and closes it in `finally`. Do not use `-XX:StartFlightRecording`, whose startup narration would violate the empty-output contract. The campaign's `MetricSeries.artifacts` records each JFR file logical ID/size/SHA-256 while compatibility ignores its host-specific execution path.

- [ ] **Step 4: Implement platform-specific peak RSS parsing.**

  ```kotlin
  interface PeakRssProvider {
    val id: String
    fun wrap(javaCommand: List<String>, providerOutput: Path): List<String>
    fun parse(providerOutput: Path): Long
  }
  ```

  Linux uses `/usr/bin/time -v -o <provider-file>` and multiplies `Maximum resident set size (kbytes)` by 1,024 with checked arithmetic. macOS smoke mode uses `/usr/bin/time -l -o <provider-file>`, whose maximum resident size is already bytes. The provider file is separate from child stdout/stderr; target stderr must remain empty. Controlled mode later accepts Linux/GNU-time only.

- [ ] **Step 5: Implement the two-GC retained-memory protocol without future runtime types.**

  Extend `TargetForkCommand` with nullable `retainedExecutionCount` (required only for `MetricPass.RETAINED`) and extend `TargetForkResult` with nullable `retainedCheckpoint`. Define `RetainedCheckpoint(executionCount, usedHeapBytes, completedGcCycles, weakReferences)` using the Task 2 evidence types. Ordinary modes require `retainedCheckpoint == null` and `samples.size == measurementIterations`; retained mode requires zero warmup/measurement iterations, an empty sample list, a positive retained execution count, and exactly one checkpoint. `TargetForkMain` rejects every other field/mode combination.

  ```kotlin
  @JsonClass(generateAdapter = true)
  data class RetainedCheckpoint(
    val executionCount: Int,
    val usedHeapBytes: Long,
    val completedGcCycles: Int,
    val weakReferences: List<WeakReferenceOutcome>,
  )
  ```

  `FullGcProtocol` records aggregate `GarbageCollectorMXBean.collectionCount`, requests `System.gc()`, waits for one bounded count increase, requests a second `System.gc()`, waits for a second bounded increase, and only then reads `MemoryMXBean.heapMemoryUsage.used`. Unsupported/disabled explicit GC or either timeout invalidates the sample. `RetainedMemoryRunner` schedules at least five accepted paired host blocks. Within each block and target order it assigns a stable `replicateGroup`, launches three separate fresh JVMs for 1,000, 2,000, and 4,000 executions, and records all three observations with the enclosing block/group identities; it never measures counts cumulatively in one JVM. Host health rejection discards the entire baseline/candidate block and all six processes from statistics. In CS1 the worker creates/drops fake weak-reference tokens to prove the protocol. CS2a replaces/adds outcomes named `ExecutionSession` and `KickExecution`; no release retained-memory claim is permitted until those real outcomes are present and all clear.

- [ ] **Step 6: Run warm allocation through a distributed lifecycle JMH benchmark.**

  `WarmLifecycleAllocationBenchmark` prepares `lifecycle.no-script-one-step.v1` once per trial and invokes exactly one `prepared.execute()` per benchmark operation. Within each randomized accepted host block, `WarmAllocationRunner` launches the strict JMH controller once for each target in scheduled order, using the thin benchmark classes JAR, supplied original target classpath, `-prof gc`, and exactly one JMH fork per launch. Five accepted warm blocks therefore produce five independent forks per role without running all baseline forks before all candidate forks. `JmhGcResultImporter` requires secondary metric `gc.alloc.rate.norm`, preserves each raw fork value as a finite `Double` with its enclosing block/fork identity, and rejects aggregate-only data for release gates. Task 11 packages the thin benchmark JAR and wires `MetricPass.ALLOCATION` in warm campaigns to this runner; it never substitutes the ordinary warm latency loop.

- [ ] **Step 7: Run provider tests and structural integration smoke.**

  Run:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ./gradlew :benchmark-driver:test --tests '*JfrAllocationReaderTest' --tests '*PeakRssProviderTest' --tests '*FullGcProtocolTest' --tests '*JmhGcResultImporterTest' --tests '*WarmAllocationRunnerTest' --tests '*RetainedMemoryRunnerTest' \
    :benchmark-driver:integrationTest --tests '*RunnerIntegrationTest' \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  ./gradlew :benchmark-driver:benchmarkJmh \
    -Pbenchmark.includes=WarmLifecycleAllocationBenchmark \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.adapter=baseline-83f3cd70 \
    -Pbenchmark.profilers=gc \
    -Pbenchmark.quick=true
  ```

  Expected: PASS; live integration asserts only that provider output is structurally valid/non-negative, never that it meets a timing or memory threshold.

- [ ] **Step 8: Commit metric providers.**

  ```bash
  git add benchmark-driver/build.gradle.kts benchmark-driver/src/main benchmark-driver/src/jmh benchmark-driver/src/test benchmark-driver/src/integrationTest
  git commit -m "feat: collect benchmark resource metrics"
  ```

### Task 8: Add controlled-host policy and paired alternating blocks

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/ControlledHostPolicy.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/LinuxHostProbe.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/host/HostHealthGate.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/AlternatingBlockScheduler.kt`
- Create: `benchmark-driver/src/main/resources/schema/revoman-controlled-host-v1.schema.json`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/host/HostHealthGateTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/run/AlternatingBlockSchedulerTest.kt`
- Test fixture: `benchmark-driver/src/test/resources/host/valid.json`
- Test fixture: `benchmark-driver/src/test/resources/host/high-load.json`
- Test fixture: `benchmark-driver/src/test/resources/host/wrong-governor.json`
- Test fixture: `benchmark-driver/src/test/resources/host/on-battery.json`
- Test fixture: `benchmark-driver/src/test/resources/host/thermal.json`
- Test fixture: `benchmark-driver/src/test/resources/host/swap-growth.json`

**Interfaces:**
- Consumes: runner plans from Task 6 and host fields in `BenchmarkResultV1`.
- Produces:

  ```kotlin
  @JsonClass(generateAdapter = true)
  data class ControlledHostPolicy(
    val schema: String = "revoman-controlled-host/v1",
    val hostFingerprintSha256: String,
    val cpuModel: String,
    val cpuCount: Int,
    val allowedGovernors: Set<String>,
    val requireAcPower: Boolean,
    val maximumLoadAverage: Double,
    val maximumCpuBusyFraction: Double,
    val minimumAvailableMemoryBytes: Long,
    val maximumSwapDeltaBytes: Long,
    val maximumThermalValue: Double,
    val probeIntervalMillis: Long,
    val maximumReplacementBlocks: Int,
  )

  data class HealthDecision(val accepted: Boolean, val reasons: List<String>)
  data class TargetOrder(val blockId: Int, val targetIds: List<String>)

  fun interface HostHealthProbe { fun sample(): HostHealthSnapshot }

  class HostHealthGate(private val policy: ControlledHostPolicy) {
    fun assess(before: HostHealthSnapshot, during: List<HostHealthSnapshot>, after: HostHealthSnapshot): HealthDecision
  }

  class AlternatingBlockScheduler(private val seed: Long) {
    fun schedule(blocks: Int, baselineId: String, candidateId: String): List<TargetOrder>
  }
  ```

- [ ] **Step 1: Write failing policy/gate/scheduler tests.**

  Require deterministic balanced ordering and health-only rejection:

  - `same seed produces byte identical balanced target order`
  - `changing measured latency cannot change health decision`
  - `high load thermal pressure swap growth battery and wrong governor reject whole pair`
  - `insufficient accepted replacement blocks is inconclusive`
  - `controlled mode has no permissive default policy`

- [ ] **Step 2: Run host tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*HostHealthGateTest' --tests '*AlternatingBlockSchedulerTest'
  ```

  Expected: compilation fails because policy/probe/scheduler types do not exist.

- [ ] **Step 3: Implement a fail-closed Linux controlled policy.**

  The v1 policy requires explicit expected host fingerprint, CPU model/count, allowed governors, AC-power requirement, load and CPU-busy limits, minimum available memory, maximum swap delta, maximum thermal value, probe interval, and maximum replacement blocks. `LinuxHostProbe` reads `/proc`, `/sys/devices/system/cpu/**/scaling_governor`, power-supply sysfs, and thermal zones. Missing/unsupported controlled probes reject the campaign; smoke mode records `unknown` without claiming release eligibility.

- [ ] **Step 4: Implement paired block scheduling and rejection.**

  Use a pinned `Well19937c(seed)` from Commons Math. Each block contains both target runs in randomized order and shared before/during/after health samples. Rejecting health discards both target observations but serializes the rejected block and reasons. Schedule replacement pairs until the accepted count is met or `maximumReplacementBlocks` is exceeded; then return INCONCLUSIVE/nonzero.

- [ ] **Step 5: Run host-policy tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*HostHealthGateTest' --tests '*AlternatingBlockSchedulerTest'
  ```

  Expected: PASS; the scheduler is deterministic and no benchmark observation is an input to `HostHealthGate.assess`.

- [ ] **Step 6: Commit controlled-host structure.**

  ```bash
  git add benchmark-driver/src/main benchmark-driver/src/test
  git commit -m "feat: add controlled benchmark host policy"
  ```

### Task 9: Implement deterministic statistics over the full sampling hierarchy

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/Statistics.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/HierarchicalBootstrap.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats/TheilSen.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/stats/StatisticsTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/stats/HierarchicalBootstrapTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/stats/TheilSenTest.kt`
- Golden: `benchmark-driver/src/test/resources/stats/hierarchical-bootstrap-seed-5245564f4d414e31.json`

**Interfaces:**
- Consumes: accepted paired blocks/raw observations from Tasks 2 and 8.
- Produces:

  ```kotlin
  enum class Statistic { MEDIAN, P95, MEAN }

  data class ForkSeries(val fork: Int, val values: List<Double>)
  data class PairedBlockSamples(
    val blockId: Int,
    val baselineForks: List<ForkSeries>,
    val candidateForks: List<ForkSeries>,
  )
  data class PairedHierarchy(val blocks: List<PairedBlockSamples>)
  data class RetainedPoint(val executionCount: Int, val retainedBytes: Double)
  data class RetainedReplicate(
    val blockId: Int,
    val targetId: String,
    val replicateGroup: Int,
    val points: List<RetainedPoint>,
    val weakReferences: List<WeakReferenceOutcome>,
  )
  data class RetainedBlockSamples(
    val blockId: Int,
    val baselineReplicates: List<RetainedReplicate>,
    val candidateReplicates: List<RetainedReplicate>,
  )
  data class RetainedHierarchy(val blocks: List<RetainedBlockSamples>)

  data class RatioInterval(
    val pointEstimate: Double,
    val lower95: Double,
    val upper95: Double,
  )

  data class SlopeInterval(
    val pointEstimateBytesPerExecution: Double,
    val lower95BytesPerExecution: Double,
    val upper95BytesPerExecution: Double,
  )

  fun hierarchicalRatioInterval(
    samples: PairedHierarchy,
    statistic: Statistic,
    resamples: Int = 10_000,
    seed: Long = 0x5245564F4D414E31L,
  ): RatioInterval

  fun theilSen(points: List<RetainedPoint>): Double

  fun retainedSlopeInterval(
    samples: RetainedHierarchy,
    targetRole: TargetRole,
    resamples: Int = 10_000,
    seed: Long = 0x5245564F4D414E31L,
  ): SlopeInterval
  ```

- [ ] **Step 1: Write failing quantile, hierarchy, determinism, and slope tests.**

  Include exact vectors:

  - `R7 median and p95 match hand calculated values`
  - `bootstrap resamples blocks then forks then warm iterations`
  - `same seed produces byte identical interval`
  - `an extreme valid observation is retained`
  - `zero baseline statistic is rejected before ratio construction`
  - `Theil Sen is median of all pairwise slopes`
  - `retained slope bootstrap resamples host blocks then replicate slopes and preserves weak reference failures`
  - `cold hierarchy treats every fresh process as one fork observation`

- [ ] **Step 2: Run statistics tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*StatisticsTest' --tests '*HierarchicalBootstrapTest' --tests '*TheilSenTest'
  ```

  Expected: compilation fails because statistics functions do not exist.

- [ ] **Step 3: Implement R7 quantiles and Theil–Sen.**

  For sorted `x` of size `n`, R7 uses `h = 1 + (n - 1) * p`, `j = floor(h)`, and linear interpolation between one-based `x[j]`/`x[j+1]`. Reject empty/non-finite inputs. `theilSen` computes every `(y2-y1)/(x2-x1)` for distinct execution counts and returns the R7 median; duplicate x values are invalid. Each retained replicate group contains exactly three separate-process points at 1,000/2,000/4,000 executions. `retainedSlopeInterval` accepts the paired retained hierarchy and one role, requires at least five independent replicate groups for that role, computes one Theil–Sen slope per group, then deterministically resamples accepted host blocks first and replicate slopes within each selected block. Its point estimate is the R7 median original replicate slope and its interval is p2.5/p97.5 over 10,000 resamples. Mismatched block/target/group IDs are invalid. Any `cleared != created` outcome remains valid serialized evidence and later forces the gate to fail regardless of slope.

- [ ] **Step 4: Implement the exact hierarchical bootstrap.**

  For each of exactly 10,000 resamples:

  1. sample accepted paired host blocks with replacement;
  2. within each selected block, sample forks with replacement for each role;
  3. within each selected warm fork, sample measured iterations with replacement;
  4. compute `candidateStatistic / baselineStatistic`;
  5. retain the ratio without trimming.

  The point estimate uses original samples. Reject a zero/non-finite baseline statistic or non-finite ratio rather than inventing an epsilon. The interval is R7 p2.5/p97.5 over bootstrap ratios. Use `Well19937c` with the exact seed; do not use global/default randomness.

- [ ] **Step 5: Run statistics tests and update the deterministic golden.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*StatisticsTest' --tests '*HierarchicalBootstrapTest' --tests '*TheilSenTest'
  ```

  Expected: PASS; rerunning the test writes no diff and the exact golden interval is stable.

- [ ] **Step 6: Commit statistics.**

  ```bash
  git add benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/stats benchmark-driver/src/test
  git commit -m "feat: add hierarchical benchmark statistics"
  ```

### Task 10: Add compatibility checks and exact release-gate decisions

**Files:**
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ResultCompatibility.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/RegressionPolicy.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ReleaseGateEvaluator.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare/ComparisonReport.kt`
- Create: `benchmark-driver/src/main/resources/schema/revoman-benchmark-comparison-v1.schema.json`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/compare/ResultCompatibilityTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/compare/ReleaseGateEvaluatorTest.kt`
- Golden fixture: `benchmark-driver/src/test/resources/compare/pass.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/exact-boundary.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/regression.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/improvement.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/retained-failure.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/incompatible.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/smoke.json`
- Golden fixture: `benchmark-driver/src/test/resources/compare/inconclusive.json`

**Interfaces:**
- Consumes: v1 campaign from Task 2 and statistical intervals/slopes from Task 9.
- Produces: `ComparisonReport` plus process exit semantics: PASS `0`, FAIL/INCONCLUSIVE/INCOMPATIBLE nonzero.

  ```kotlin
  enum class GateDecision { PASS, FAIL, INCONCLUSIVE, INCOMPATIBLE }
  enum class ClaimKind { NON_REGRESSION, TARGETED_IMPROVEMENT, STRUCTURAL }

  data class MetricDecision(
    val gate: GateId?,
    val claimKind: ClaimKind,
    val mode: RunMode,
    val metric: MetricId,
    val statistic: Statistic?,
    val interval: RatioInterval?,
    val slopeInterval: SlopeInterval?,
    val observedValue: Double?,
    val limit: Double,
    val decision: GateDecision,
    val reason: String,
  )

  data class RejectedBlockEvidence(
    val workloadId: String,
    val metric: MetricId,
    val blockId: Int,
    val reasons: List<String>,
  )

  data class ComparisonReport(
    val schema: String = "revoman-benchmark-comparison/v1",
    val campaignId: String,
    val compatibilityErrors: List<String>,
    val metrics: List<MetricDecision>,
    val rejectedBlocks: List<RejectedBlockEvidence>,
    val overall: GateDecision,
  )
  ```

- [ ] **Step 1: Write failing compatibility and threshold-edge tests.**

  Test every incompatibility independently: fixed BASELINE commit, harness commit/tree/artifact, each role's pinned adapter hash, workload contract, fixture tree, a target classpath that changes within its role or no longer matches its manifest, dirty flag, Gradle version/wrapper hash, JDK distribution/full version/flags, host fingerprint/policy, metric provider/configuration/unit, and pairing. Candidate and baseline classpath hashes may differ from each other because the candidate itself is the treatment. Add exact gate cases:

  - `cold median exact upper ratio 1_05 passes`
  - `cold p95 upper ratio above 1_10 fails`
  - `warm allocation exact upper ratio 1_03 passes`
  - `cold improvement requires upper ratio at most 0_85`
  - `warm improvement requires upper ratio at most 0_80`
  - `two targeted metrics have distinct composite decision keys`
  - `retained slope upper endpoint over 1024 fails`
  - `per step allocation spread max over min above 1_10 fails`
  - `lifecycle workload does not require retained or per step gates`
  - `smoke evidence cannot pass release gates`
  - `insufficient accepted samples is inconclusive`

- [ ] **Step 2: Run comparison tests and verify failure.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*ResultCompatibilityTest' --tests '*ReleaseGateEvaluatorTest'
  ```

  Expected: compilation fails because compatibility/policy/evaluator types do not exist.

- [ ] **Step 3: Implement strict compatibility before statistics.**

  `ResultCompatibility.requireComparable(result)` must reject incompatible metadata before calculating a ratio. Controlled results require two clean targets; BASELINE must be exactly `83f3cd70f78ad733412d10cbc8287aaabafe7aac`; harness/workload/fixture/provider/JDK/host inputs must match; actual Gradle version and wrapper SHA-256 must match across roles; each role's adapter must match its separately pinned harness adapter identity; blocks must be paired; cold needs at least 50 unique fresh PIDs per role; warm needs at least five independent forks per role. Baseline and candidate adapter/classpath hashes need not equal each other. Rejected blocks remain auditable but never enter statistics. `compare --enforce-release-gates` repeats the full baseline-commit check even if the producing command was `run-paired`, so another CLI path cannot bypass the fixed baseline.

  The versioned workload manifest declares required `GateId`s per mode. `lifecycle.no-script-one-step.v1` requires cold median/p95/allocation/peak-RSS and warm median/p95/allocation only. Retained slope and per-step spread become required only for later retained/report workloads that produce those measurements. Missing a declared gate is INCONCLUSIVE; omitting a gate that the workload does not declare is valid.

- [ ] **Step 4: Encode the asymmetric policy exactly.**

  ```kotlin
  data class RegressionPolicy(
    val coldMedianUpper: Double = 1.05,
    val coldP95Upper: Double = 1.10,
    val coldAllocationUpper: Double = 1.05,
    val coldPeakRssUpper: Double = 1.05,
    val warmMedianUpper: Double = 1.03,
    val warmP95Upper: Double = 1.05,
    val warmAllocationUpper: Double = 1.03,
    val coldImprovementUpper: Double = 0.85,
    val warmImprovementUpper: Double = 0.80,
    val retainedSlopeUpperBytes: Double = 1_024.0,
    val perStepAllocationSpreadUpper: Double = 1.10,
  )
  ```

  `ReleaseGateEvaluator` keys decisions uniquely by `(claimKind, mode, metric, statistic)`; normative workload gates also carry their `GateId`, while an ad hoc targeted-improvement claim has `gate = null`. Ratio gates populate only `interval`; retained slope populates only `slopeInterval`; per-step spread populates only `observedValue`, and model/schema validation rejects any other combination. Non-regression compares the ratio interval's upper endpoint with its limit. An explicitly requested targeted-improvement claim compares the selected cold/warm metric/statistic ratio interval's upper endpoint with 0.85/0.80, so multiple claimed metrics never collide. The retained absolute gate uses the CANDIDATE role's slope and requires candidate outcomes named `ExecutionSession` and `KickExecution` with every `cleared == created`, plus `upper95BytesPerExecution <= 1_024`. The fixed baseline's fake-token outcomes remain valid paired statistical/host context and are not required to expose future runtime types; a candidate with fake-only CS1 evidence is INCONCLUSIVE for a release claim. Per-step allocation uses `max(bytes(n)/n) / min(bytes(n)/n)` across 800, 1,600, and 3,200.

- [ ] **Step 5: Produce machine and Markdown reports.**

  `ComparisonReport` lists compatibility, every metric's point/CI/limit/decision, typed rejected-block evidence, and overall PASS/FAIL/INCONCLUSIVE. Canonically sort rejected evidence by workload/metric/block and preserve each gate-produced reason list. Add a strict Draft 2020-12 schema with `additionalProperties: false` for this exact model and validate every comparison golden through it. Markdown is a pure rendering of the machine result and cannot alter decisions.

- [ ] **Step 6: Run comparator tests.**

  Run:

  ```bash
  ./gradlew :benchmark-driver:test --tests '*ResultCompatibilityTest' --tests '*ReleaseGateEvaluatorTest'
  ```

  Expected: PASS for exact boundaries; every one-unit/epsilon breach fails; incompatible/smoke/insufficient evidence never reports PASS.

- [ ] **Step 7: Commit release gates.**

  ```bash
  git add benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/compare benchmark-driver/src/test
  git commit -m "feat: enforce benchmark release gates"
  ```

### Task 11: Complete the CLI, target-manifest export, and application distribution

**Files:**
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/BenchmarkDriverMain.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/cli/BenchmarkCli.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/run/BenchmarkCampaign.kt`
- Create: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/TargetManifestLoader.kt`
- Modify: `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/BuildIdentity.kt`
- Create: `benchmark-driver/src/main/resources/schema/revoman-target-manifest-v1.schema.json`
- Modify: `benchmark-driver/src/main/resources/schema/revoman-benchmark-jmh-v1.schema.json`
- Modify: `benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts`
- Modify: `benchmark-driver/build.gradle.kts`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/cli/BenchmarkCliTest.kt`
- Modify test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/BuildIdentityTest.kt`
- Test: `benchmark-driver/src/test/kotlin/com/salesforce/revoman/benchmark/driver/integrity/TargetManifestLoaderTest.kt`
- Integration test: `benchmark-driver/src/integrationTest/kotlin/com/salesforce/revoman/benchmark/driver/cli/BenchmarkDriverIntegrationTest.kt`

**Interfaces:**
- Consumes: all modules from Tasks 1–10.
- Produces stable commands: `list-workloads`, `run-paired`, `compare`, `verify`, and `capture-baseline`.

  ```kotlin
  data class CampaignRequest(
    val intent: RunIntent,
    val mode: RunMode,
    val baseline: TargetManifest,
    val baselineAdapterId: String,
    val candidate: TargetManifest,
    val candidateAdapterId: String,
    val workloadId: String,
    val blocks: Int,
    val forksPerBlock: Int,
    val warmups: Int,
    val iterations: Int,
    val seed: Long,
    val metricPasses: Set<MetricPass>,
    val artifactDirectory: Path,
    val hostPolicy: ControlledHostPolicy?,
  )

  class BenchmarkCampaign {
    fun run(request: CampaignRequest): BenchmarkResultV1
  }
  ```

- [ ] **Step 1: Write failing CLI parse and end-to-end smoke tests.**

  Cover exact command validation, no shell parsing, atomic output, nonzero gate status, controlled-policy requirement, and full baseline pinning:

  - `capture baseline rejects abbreviated or different commit`
  - `run paired requires baseline candidate workload mode and output`
  - `artifact producing passes atomically create an explicit absent child under a writable parent`
  - `controlled run requires an explicit policy file`
  - `verify rejects a dirty target manifest`
  - `target manifest export requires an explicit nonblank target id`
  - `target manifest rejects a changed classpath byte`
  - `build identity hashes source trees without timestamps`
  - `smoke campaign runs two targets without enforcing numeric gates`

- [ ] **Step 2: Run CLI tests and verify failure.**

  Run:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ./gradlew :benchmark-driver:test --tests '*BenchmarkCliTest' --tests '*BuildIdentityTest' --tests '*TargetManifestLoaderTest' \
    :benchmark-driver:integrationTest --tests '*BenchmarkDriverIntegrationTest' \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  ```

  Expected: failures because commands/campaign orchestration are incomplete.

- [ ] **Step 3: Complete and verify the target-manifest init script without modifying targets.**

  Complete the standalone distribution-owned Kotlin init script without adding benchmark-driver/target imports. It injects `writeBenchmarkTargetManifest` into any target checkout, requires a nonblank explicit `benchmark.targetId`, depends on that checkout's normal `jar`, resolves `runtimeClasspath` as original files, and writes `revoman-target-manifest/v1` containing:

  - the exact supplied target ID;
  - full Git commit/tree and dirty flag;
  - Gradle version and wrapper SHA-256;
  - JDK vendor/distribution/full version and JVM flags;
  - normal target JAR first plus original runtime dependency JARs in Gradle's resolved order, with Maven/project logical IDs, canonical absolute execution paths, sizes, and SHA-256;
  - no exploded directory and no filename ending `-jmh.jar`.

  Use argument lists for Git; fail if any artifact is missing/non-file or has a duplicate logical ID. Classpath identity excludes absolute execution paths and uses ordered logical ID/size/hash records. The exact command is:

  ```bash
  ./gradlew :benchmark-driver:installDist
  DRIVER_DIR="$PWD/benchmark-driver/build/install/benchmark-driver"
  TARGET_MANIFEST="$PWD/build/benchmark-target-current.json"
  ./gradlew -I "$DRIVER_DIR/libexec/benchmark-target.init.gradle.kts" \
    clean writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest="$TARGET_MANIFEST" \
    -Pbenchmark.targetId=current
  ```

- [ ] **Step 4: Implement CLI commands and campaign orchestration.**

  `run-paired` requires the parent of `--artifacts-dir` to exist/be writable, requires the requested child not to exist, and creates that child atomically before launching anything; an existing directory is rejected so stale files can never satisfy a run. It starts the deterministic server, verifies both manifests/hashes, schedules paired blocks, runs separate metric passes, and writes one atomic `BenchmarkResultV1`. Cold ALLOCATION routes to the separate JFR pass; warm ALLOCATION routes to `WarmAllocationRunner`/the distributed lifecycle JMH benchmark; PEAK_RSS is cold-only; RETAINED routes to independent checkpoint processes. For the CS1 protocol, `--forks-per-block 1` means one independent fork per role inside every accepted paired block; five warm blocks therefore produce five total forks per role. Task 11 is the sole owner of importing strict single-target `revoman-benchmark-jmh/v1` observations and attaching them to their actual alternating block/role before paired `revoman-benchmark/v1` assembly; it never duplicates one JMH target as both roles. It merges only compatible provider results without discarding fork/block identity. `compare` accepts only paired campaign evidence and rejects a single-target JMH document; `verify` dispatches strict schema/hash/count validation for both schemas. `capture-baseline` requires both roles to be clean commit `83f3cd70f78ad733412d10cbc8287aaabafe7aac` using adapter `baseline-83f3cd70`.

  Supported syntax:

  ```text
  benchmark-driver list-workloads
  benchmark-driver run-paired --mode cold --intent smoke
      --baseline build/manifests/baseline.json --baseline-adapter baseline-83f3cd70
      --candidate build/manifests/candidate.json --candidate-adapter baseline-83f3cd70
      --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1
      --warmups 0 --iterations 1 --seed 5928239383101656625
      --metrics latency --artifacts-dir build/results/smoke-artifacts
      --output build/results/smoke.json
  benchmark-driver compare --input build/results/smoke.json
      --output-json build/results/comparison.json --output-md build/results/comparison.md
  benchmark-driver verify --input build/results/smoke.json
  benchmark-driver capture-baseline --mode cold --intent controlled
      --baseline "$RUN_ROOT/manifests/baseline-a.json"
      --baseline-adapter baseline-83f3cd70
      --candidate "$RUN_ROOT/manifests/baseline-b.json"
      --candidate-adapter baseline-83f3cd70
      --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1
      --warmups 0 --iterations 1 --seed 5928239383101656625
      --metrics latency,peak-rss,allocation
      --host-policy /opt/revoman-benchmark/controlled-host.json
      --artifacts-dir "$RUN_ROOT/jfr/cold-aa"
      --output "$RUN_ROOT/results/cold-aa.json"
  ```

- [ ] **Step 5: Complete immutable harness/adapter/workload identities in the distribution.**

  Complete the Task 4 generated source manifest so it covers the final baseline/major adapter trees, workload contracts, fixtures, JFR, logging, init script, and controlled-policy schema. Controlled execution refuses a dirty/missing source identity. At runtime, compute `distributionSha256` from the declared installed artifact set and place it only in the result; never embed that value back into an artifact being hashed. Do not use a runtime timestamp or absolute installation path in any content hash.

- [ ] **Step 6: Package logging and init-script assets.**

  Configure the application distribution so installed paths are stable:

  ```text
  bin/benchmark-driver
  lib/*.jar
  lib/benchmark-driver-jmh-classes.jar
  conf/log4j2-benchmark.xml
  libexec/benchmark-target.init.gradle.kts
  schema/*.json
  workloads/v1/**
  jfr/revoman-allocation-v1.jfc
  ```

  Preserve Task 4's `installDist` dependency/fixed thin-JAR name and include that file in runtime artifact identity. Distribution tests open the JAR, require the lifecycle benchmark/`BenchmarkList`/`CompilerHints`, and reject flattened target or Graal classes.

- [ ] **Step 7: Run CLI/unit/integration/distribution checks.**

  Run:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ./gradlew :benchmark-driver:test --tests '*BenchmarkCliTest' --tests '*BuildIdentityTest' --tests '*TargetManifestLoaderTest' \
    :benchmark-driver:integrationTest :benchmark-driver:installDist \
    -Pbenchmark.targetManifest="$PWD/build/benchmark-target-current.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver list-workloads
  ```

  Expected: PASS; list output includes `lifecycle.no-script-one-step.v1`; distribution has all fixed paths; a two-sample smoke A/A campaign verifies structurally but cannot pass release gates.

- [ ] **Step 8: Commit the executable driver.**

  ```bash
  git add benchmark-driver
  git commit -m "feat: add benchmark campaign CLI"
  ```

### Task 12: Wire deterministic CI checks, controlled-host workflow, and documentation

**Files:**
- Modify: `.github/workflows/build.yml`
- Modify: `.github/workflows/qodana.yml`
- Create: `.github/workflows/benchmark.yml`
- Modify: `DEVELOPMENT.md`
- Replace: `docs/superpowers/benchmarks/baseline.md`
- Modify: `docs/modules/ROOT/pages/performance.adoc`

**Interfaces:**
- Consumes: installed driver/tasks from Task 11.
- Produces: ordinary deterministic CI, manual protected controlled campaigns, and operator documentation.

- [ ] **Step 1: Add the fast deterministic harness self-test to ordinary CI.**

  In `.github/workflows/build.yml`, keep `./gradlew build` and add:

  ```yaml
  - name: Export current benchmark target
    run: >-
      ./gradlew
      -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts
      writeBenchmarkTargetManifest
      -Pbenchmark.targetManifest=build/benchmark-target-current.json
      -Pbenchmark.targetId=current

  - name: Benchmark harness self-test
    run: >-
      ./gradlew :benchmark-driver:check :benchmark-driver:benchmarkHarnessSelfTest
      -Pbenchmark.targetManifest=build/benchmark-target-current.json
      -Pbenchmark.adapter=baseline-83f3cd70
  ```

  It may assert fork failure propagation, non-empty rows, classpath integrity, unique smoke PIDs, schema/hash validity, and deterministic math. It must not compare elapsed time, allocation, RSS, retained memory, or improvement thresholds.

- [ ] **Step 2: Include generated driver sources in Qodana bootstrap.**

  Update the generated-sources command to:

  ```yaml
  run: ./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin :benchmark-driver:classes
  ```

  Keep `qodanaScan` opt-in locally and unchanged as a non-timing static-analysis gate.

- [ ] **Step 3: Add manual-only protected controlled workflow.**

  `.github/workflows/benchmark.yml` has only `workflow_dispatch`, uses a protected `performance` environment and `runs-on: [self-hosted, linux, revoman-controlled-benchmark]`. The benchmark harness is a separate full-SHA input and is never inferred from the candidate. No `push` or `pull_request` trigger is allowed.

  Start the workflow with this exact trigger/job contract:

  ```yaml
  name: Controlled performance benchmark

  on:
    workflow_dispatch:
      inputs:
        harness_ref:
          description: Full clean CS1 harness commit SHA
          required: true
        candidate_ref:
          description: Candidate commit to benchmark
          required: true
          default: master
        candidate_adapter:
          description: Exact versioned adapter for the candidate
          required: true
          type: choice
          options:
            - major-v1
            - baseline-83f3cd70
        host_policy_path:
          description: Absolute policy path on the protected runner
          required: true
          default: /opt/revoman-benchmark/controlled-host.json

  permissions:
    contents: read

  jobs:
    benchmark:
      environment: performance
      runs-on: [self-hosted, linux, revoman-controlled-benchmark]
      timeout-minutes: 180
      env:
        CANDIDATE_ADAPTER: ${{ inputs.candidate_adapter }}
        HOST_POLICY_PATH: ${{ inputs.host_policy_path }}
  ```

  Implement these steps in this order:

  1. reject `harness_ref` unless it is a full 40-character commit and check out that exact ref into `harness`; check out fixed baseline `83f3cd70f78ad733412d10cbc8287aaabafe7aac` independently into `baseline-a` and `baseline-b`; check out `candidate_ref` into `candidate`;
  2. use the repository's existing `actions/setup-java@main` JetBrains JDK 21 and `gradle/actions/setup-gradle@main` conventions;
  3. require the administrator-provisioned `host_policy_path` to be an absolute readable file—workflow code never generates or relaxes host policy—and require `/opt/revoman-benchmark/runs` to be pre-provisioned/writable by the runner account; create an initially absent `RUN_ROOT=/opt/revoman-benchmark/runs/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}` with `manifests`, `results`, and `jfr` children, failing if it already exists, then persist `RUN_ROOT` through `GITHUB_ENV`;
  4. run `harness/gradlew -p harness :benchmark-driver:installDist`, then export `baseline-a`, `baseline-b`, and `candidate` manifests with their own wrappers, the installed init script, and explicit matching `benchmark.targetId` values;
  5. run cold and warm A/A via `capture-baseline`, compare both with release gates, and stop candidate claims as INCONCLUSIVE if either A/A comparison fails;
  6. run baseline-a (`baseline-83f3cd70`) versus candidate (`candidate_adapter`) cold/warm via `run-paired`, using 50 cold blocks and five warm accepted blocks with one fork per block; write distinct `cold-candidate.json`, `warm-candidate.json`, `comparison-candidate-cold.*`, and `comparison-candidate-warm.*`, then compare both with `--enforce-release-gates`; and
  7. upload `${RUN_ROOT}/results/**` and `${RUN_ROOT}/jfr/**` using `if: ${{ always() }}` so failed/inconclusive evidence remains diagnosable; never glob a prior run directory.

  The manifest-export shell for each target is exact except for its three explicit values:

  ```bash
  TARGET_ROOT="$GITHUB_WORKSPACE/baseline-a"
  TARGET_ID=baseline-a
  TARGET_MANIFEST="$RUN_ROOT/manifests/baseline-a.json"
  "$TARGET_ROOT/gradlew" -p "$TARGET_ROOT" \
    -I "$GITHUB_WORKSPACE/harness/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
    clean writeBenchmarkTargetManifest \
    -Pbenchmark.targetId="$TARGET_ID" \
    -Pbenchmark.targetManifest="$TARGET_MANIFEST"
  ```

  Repeat with `baseline-b` and `candidate`; do not reuse or edit one manifest. Pass `host_policy_path` through a quoted environment variable to every controlled command, never substitute the default path when the input differs. Candidate commands use Task 13's counts/seed but explicitly set `--candidate <candidate.json>`, `--candidate-adapter "${CANDIDATE_ADAPTER}"`, and the distinct candidate output/artifact paths above; they never reuse A/A filenames. Add a workflow self-test fixture that renders the job and asserts all three target IDs, the fixed baseline SHA, distinct harness/candidate refs, selected candidate adapter, supplied host-policy path, distinct A/A/candidate outputs, A/A-before-candidate ordering, and `always()` artifact upload.

- [ ] **Step 4: Replace obsolete benchmark documentation.**

  `DEVELOPMENT.md` documents build/install, target-manifest export, quick JMH, smoke, controlled cold/warm, result verification, and why the fixed baseline is rerun in every alternating campaign. Replace `baseline.md`'s claim that end-to-end measurement is not repeatable with the loopback-fixture protocol, full SHA pin, A/A requirement, hashes/providers, and controlled-host rules. Update `performance.adoc` to remove “internally very light-weight” and link only to v1 reproducible evidence.

  Label `docs/superpowers/benchmarks/results/491ea968-smoke.txt` as legacy human output contaminated by the old harness; never convert it into v1 evidence.

- [ ] **Step 5: Run ordinary CI-equivalent checks locally.**

  Run:

  ```bash
  ./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
    writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.targetId=current
  ./gradlew :benchmark-driver:check :benchmark-driver:benchmarkHarnessSelfTest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.adapter=baseline-83f3cd70
  ./gradlew test integrationTest \
    -Pbenchmark.targetManifest=build/benchmark-target-current.json \
    -Pbenchmark.adapter=baseline-83f3cd70
  ./gradlew build
  ```

  Expected: PASS. No controlled timing threshold runs in these commands.

- [ ] **Step 6: Run formatting and static analysis before pushing.**

  Run:

  ```bash
  ./gradlew spotlessApply
  ./gradlew spotlessCheck
  ./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin :benchmark-driver:classes
  ./gradlew qodanaScan
  ```

  Expected: formatting and Qodana complete without new findings.

- [ ] **Step 7: Commit CI and documentation.**

  ```bash
  git add .github/workflows/build.yml .github/workflows/qodana.yml .github/workflows/benchmark.yml \
    DEVELOPMENT.md docs/superpowers/benchmarks/baseline.md \
    docs/modules/ROOT/pages/performance.adoc benchmark-driver
  git commit -m "ci: verify benchmark foundation"
  ```

### Task 13: Capture and commit the fixed controlled A/A baseline

**Files:**
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/cold-aa.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/warm-aa.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/revoman-benchmark-jmh-v1.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/jmh-raw.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison.md`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison-warm.json`
- Create: `docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison-warm.md`
- Modify: `docs/superpowers/benchmarks/baseline.md`

**Interfaces:**
- Consumes: final clean CS1 driver distribution and controlled self-hosted Linux host.
- Produces: first auditable v1 baseline evidence; historical values are audit evidence, not a reusable denominator for future candidates.

- [ ] **Step 1: Create two independent clean detached baseline checkouts.**

  Use `superpowers:using-git-worktrees` at execution time. Both targets must resolve to full commit:

  ```text
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
  ```

  Use these fixed locations on the controlled host:

  ```text
  /opt/revoman-benchmark/checkouts/baseline-a
  /opt/revoman-benchmark/checkouts/baseline-b
  /opt/revoman-benchmark/checkouts/harness-cs1
  ```

  Build each target with its own wrapper/JDK inputs and refuse any dirty status. Build and `installDist` the final driver from the clean `harness-cs1` checkout at its final CS1 commit, not from an in-progress tree:

  ```bash
  test -d /opt/revoman-benchmark/runs
  test -w /opt/revoman-benchmark/runs
  REVOMAN_BENCH_RUN_ROOT=$(mktemp -d /opt/revoman-benchmark/runs/cs1-aa.XXXXXXXX)
  export REVOMAN_BENCH_RUN_ROOT
  mkdir "$REVOMAN_BENCH_RUN_ROOT/manifests" "$REVOMAN_BENCH_RUN_ROOT/results" "$REVOMAN_BENCH_RUN_ROOT/jfr"
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 \
    :benchmark-driver:installDist
  ```

  Keep the exported `REVOMAN_BENCH_RUN_ROOT` in the operator shell for Steps 2–9, run those steps with `harness-cs1` as the working directory unless a command names another project directory explicitly, and record the unique run root in `baseline.md`. Never rerun into an existing evidence directory.

- [ ] **Step 2: Export both unmodified baseline target manifests.**

  From each checkout run the installed init script:

  ```bash
  /opt/revoman-benchmark/checkouts/baseline-a/gradlew \
    -p /opt/revoman-benchmark/checkouts/baseline-a \
    -I /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts \
    clean writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest="$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" \
    -Pbenchmark.targetId=baseline-a
  /opt/revoman-benchmark/checkouts/baseline-b/gradlew \
    -p /opt/revoman-benchmark/checkouts/baseline-b \
    -I /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts \
    clean writeBenchmarkTargetManifest \
    -Pbenchmark.targetManifest="$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-b.json" \
    -Pbenchmark.targetId=baseline-b
  ```

  Verify target IDs are exactly `baseline-a`/`baseline-b`, both manifests have the fixed commit, clean state, identical target artifact hashes, original `truffle-api` JAR with `Multi-Release: true`, and no `*-jmh.jar`.

- [ ] **Step 3: Run controlled cold A/A in randomized paired blocks.**

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver capture-baseline --mode cold --intent controlled \
    --baseline "$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
    --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 \
    --warmups 0 --iterations 1 --seed 5928239383101656625 \
    --metrics latency,peak-rss,allocation \
    --host-policy /opt/revoman-benchmark/controlled-host.json \
    --artifacts-dir "$REVOMAN_BENCH_RUN_ROOT/jfr/cold-aa" \
    --output "$REVOMAN_BENCH_RUN_ROOT/results/cold-aa.json"
  ```

  Require at least 50 accepted paired blocks/100 unique target PIDs for each requested pass. The driver records one campaign document but launches the JFR allocation pass in separate child processes from latency/RSS.

- [ ] **Step 4: Run controlled warm A/A.**

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver capture-baseline --mode warm --intent controlled \
    --baseline "$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" --baseline-adapter baseline-83f3cd70 \
    --candidate "$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-b.json" --candidate-adapter baseline-83f3cd70 \
    --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 \
    --warmups 20 --iterations 100 --seed 5928239383101656625 \
    --metrics latency,allocation \
    --host-policy /opt/revoman-benchmark/controlled-host.json \
    --artifacts-dir "$REVOMAN_BENCH_RUN_ROOT/jfr/warm-aa" \
    --output "$REVOMAN_BENCH_RUN_ROOT/results/warm-aa.json"
  ```

  Require five independent forks per role and raw per-execution samples; no warmup observation appears.

- [ ] **Step 5: Capture clean JMH foundation results.**

  Run the migrated mean-only component suite with at least five forks and the GC profiler:

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 \
    :benchmark-driver:benchmarkJmh \
    -Pbenchmark.includes='RegexVarBenchmark|MarshallingBenchmark|SandboxBenchmark|EnvAccumBenchmark' \
    -Pbenchmark.targetManifest="$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" \
    -Pbenchmark.adapter=baseline-83f3cd70 \
    -Pbenchmark.forks=5 \
    -Pbenchmark.profilers=gc \
    -Pbenchmark.rawJmhOutput="$REVOMAN_BENCH_RUN_ROOT/results/jmh-raw.json" \
    -Pbenchmark.resultOutput="$REVOMAN_BENCH_RUN_ROOT/results/revoman-benchmark-jmh-v1.json"
  ```

  Run SampleTime only for a percentile claim. Normalize the JMH JSON through
  `JmhResultImporter`, save it as `revoman-benchmark-jmh-v1.json`, require schema
  `revoman-benchmark-jmh/v1`, and verify no logging or multi-release warning appears. This is
  single-target archival/component evidence, not a paired campaign and not input to release gates
  without Task 11's explicit block/role assembly.

- [ ] **Step 6: Compare A/A and reject a noisy harness.**

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver compare \
    --input "$REVOMAN_BENCH_RUN_ROOT/results/cold-aa.json" \
    --output-json "$REVOMAN_BENCH_RUN_ROOT/results/comparison.json" \
    --output-md "$REVOMAN_BENCH_RUN_ROOT/results/comparison.md" \
    --enforce-release-gates
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver compare \
    --input "$REVOMAN_BENCH_RUN_ROOT/results/warm-aa.json" \
    --output-json "$REVOMAN_BENCH_RUN_ROOT/results/comparison-warm.json" \
    --output-md "$REVOMAN_BENCH_RUN_ROOT/results/comparison-warm.md" \
    --enforce-release-gates
  ```

  Expected: A/A confidence upper bounds remain inside all non-regression limits. If not, investigate host health/sample count/provider contamination; do not loosen thresholds or delete unfavorable observations.

- [ ] **Step 7: Validate and document captured evidence.**

  Run:

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify \
    --input "$REVOMAN_BENCH_RUN_ROOT/results/cold-aa.json"
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify \
    --input "$REVOMAN_BENCH_RUN_ROOT/results/warm-aa.json"
  /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify \
    --input "$REVOMAN_BENCH_RUN_ROOT/results/revoman-benchmark-jmh-v1.json"
  mkdir -p docs/superpowers/benchmarks/results/v1/baseline-83f3cd70
  cp "$REVOMAN_BENCH_RUN_ROOT/results/cold-aa.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/cold-aa.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/warm-aa.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/warm-aa.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/revoman-benchmark-jmh-v1.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/revoman-benchmark-jmh-v1.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/jmh-raw.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/jmh-raw.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/comparison.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/comparison.md" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison.md
  cp "$REVOMAN_BENCH_RUN_ROOT/results/comparison-warm.json" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison-warm.json
  cp "$REVOMAN_BENCH_RUN_ROOT/results/comparison-warm.md" \
    docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/comparison-warm.md
  git diff --check
  ```

  Record harness commit/tree, both target manifests, policy hash, JDK/host/provider identities, accepted/rejected block counts, result hashes, and exact commands in `baseline.md`.

- [ ] **Step 8: Re-run final repository gates.**

  Run:

  ```bash
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 \
    :benchmark-driver:check :benchmark-driver:benchmarkHarnessSelfTest \
    -Pbenchmark.targetManifest="$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 test integrationTest \
    -Pbenchmark.targetManifest="$REVOMAN_BENCH_RUN_ROOT/manifests/baseline-a.json" \
    -Pbenchmark.adapter=baseline-83f3cd70
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 build
  /opt/revoman-benchmark/checkouts/harness-cs1/gradlew \
    -p /opt/revoman-benchmark/checkouts/harness-cs1 qodanaScan
  ```

  Expected: all deterministic/build/static gates pass; controlled A/A results remain separately verified. This post-copy harness self-test runs with `CI_SELF_TEST` intent and may record the now-dirty documentation checkout; it must not rewrite or relabel the already captured controlled results, whose embedded harness identity came from the clean pre-capture distribution.

- [ ] **Step 9: Commit baseline evidence.**

  ```bash
  git add docs/superpowers/benchmarks
  git commit -m "perf: capture controlled benchmark baseline"
  ```

## Completion Criteria

Change Set 1 is complete only when:

- `:benchmark-driver` has no ReVoman project/artifact dependency;
- target manifests come from unmodified clean checkouts and retain original dependency JARs;
- JMH no longer builds or executes a dependency-flattened uber JAR;
- intentional fork failure and unmatched selection both fail the harness;
- empty result files cannot pass;
- benchmark backend logging/banner output is absent while current `RunLogSink.NoOp` cost remains measurable;
- cold/warm runner structure, schema, hashes, providers, statistics, and gate boundaries have deterministic tests;
- ordinary CI runs no timing/memory threshold;
- the controlled A/A baseline at the fixed full commit is captured, validates, and stays inside non-regression gates; and
- `./gradlew test integrationTest` with the explicit current target manifest/adapter, `./gradlew build`, and `./gradlew qodanaScan` pass.
