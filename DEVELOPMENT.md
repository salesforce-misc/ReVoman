# ReVoman Development Guide

## Commands to Build and Verify

```bash
# Full build including tests
./gradlew build

# Build without tests
./gradlew assemble

# Run unit tests
./gradlew test

# Run all tests
./gradlew test integrationTest

# Run specific test class
./gradlew test integrationTest --tests "com.salesforce.revoman.integration.pokemon.PokemonTest"

# Run specific test method (unit test -> `test`; integration test -> `integrationTest`)
./gradlew test --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest"

# Run the `integration.core.*` tests (WFS/PQ/BT2BS — need a real Salesforce/core org). These are
# EXCLUDED from aggregate runs (`build`, `integrationTest`) by default; opt them in with -PincludeCoreIT.
./gradlew integrationTest -PincludeCoreIT --tests "*WfsSeedE2ETest"

# Compile test classes only (for faster iteration)
./gradlew testClasses

# Fix code formatting
./gradlew spotlessApply
```

## Static Analysis (Qodana)

ReVoman uses the [JetBrains Qodana](https://www.jetbrains.com/qodana/) Gradle plugin
(`org.jetbrains.qodana`) for static analysis. Run it **locally before pushing** — it's the
primary quality gate; CI (`.github/workflows/qodana.yml`) is only a backstop.

```bash
colima start                              # Qodana runs its linter in Docker; start the daemon first
# Pre-generate root and driver kapt/compiled sources with JDK 21 so Qodana resolves references.
# This compilation is not run in the container (see qodana.yaml).
./gradlew kaptKotlin classes \
  :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes
./gradlew qodanaScan                      # downloads the Qodana CLI + community linter, then scans
```

- Results (including `qodana.sarif.json`) land in `build/qodana/results`; the linter
  image/cache is kept in `.qodana/cache` so `clean` doesn't force a re-pull.
- The **free** `jetbrains/qodana-jvm-community` linter is used (configured in `qodana.yaml`).
  The paid Ultimate/Ultimate-Plus linters add Spring/SQL/taint/dependency-vulnerability
  inspections — not used here (no license; there is no free Ultimate for open source).
- `qodanaScan` is **opt-in** — it is NOT part of `./gradlew build` (which stays Docker-free),
  the same way the `integration.core.*` org tests are opt-in via `-PincludeCoreIT`.
- Docker needs ≥4 GB memory for the linter. If colima's VM is smaller, recreate it larger
  (e.g. `colima start --memory 6`).

## Continuous Integration

- `.github/workflows/build.yml` runs `./gradlew build` on every push/PR to `master` —
  full coverage: unit (`test`) + integration (`integrationTest`) + `spotlessCheck` + `kover`.
- The same workflow exports the current checkout as an explicit benchmark target and runs
  `:benchmark-driver:check :benchmark-driver:integrationTest
  :benchmark-driver:benchmarkHarnessSelfTest`. These are structural harness checks; ordinary CI
  never evaluates timing, allocation, RSS, retained-memory, or release thresholds.
- **Org tests** (`integration.core.*`) skip-loud on CI (no org creds); see `-PincludeCoreIT` above.
- **Flaky external-API tests** (pokeapi.co, restful-api.dev, apigee, beeceptor) are retried via the
  `org.gradle.test-retry` plugin — but ONLY on CI (`CI` env var set). Locally `maxRetries=0`, so
  flakes surface immediately. A test failing every attempt still fails the build (no masking).

## Benchmark Driver

The benchmark driver is a standalone installed application. It measures the normal ReVoman JAR
and original runtime dependency JARs described by an explicit target manifest; it does not flatten
the target into a benchmark uber-JAR.

### Build, install, and export targets

```bash
./gradlew :benchmark-driver:installDist
./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-smoke-baseline.json \
  -Pbenchmark.targetId=smoke-baseline
./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-smoke-candidate.json \
  -Pbenchmark.targetId=smoke-candidate
```

The installed CLI is
`benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver`. The export command must
receive both properties explicitly. The harness self-test consumes the supplied manifest and never
replaces it:

```bash
./gradlew :benchmark-driver:check :benchmark-driver:benchmarkHarnessSelfTest \
  -Pbenchmark.targetManifest=build/benchmark-target-smoke-baseline.json \
  -Pbenchmark.adapter=baseline-83f3cd70
```

### Quick JMH and structural smoke checks

Use quick JMH only to validate harness mechanics. It is not release evidence and does not enforce a
numeric threshold:

```bash
./gradlew :benchmark-driver:benchmarkJmh \
  -Pbenchmark.includes=HarnessSanityBenchmark \
  -Pbenchmark.targetManifest=build/benchmark-target-smoke-baseline.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  -Pbenchmark.quick=true
```

A two-block smoke run exercises the installed CLI against the deterministic loopback workload.
Every run uses a fresh parent so the driver can reserve absent output and artifact paths:

```bash
SMOKE_ROOT=$(mktemp -d build/benchmark-smoke.XXXXXXXX)
DRIVER=benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver
"$DRIVER" run-paired --mode cold --intent smoke \
  --baseline build/benchmark-target-smoke-baseline.json --baseline-adapter baseline-83f3cd70 \
  --candidate build/benchmark-target-smoke-candidate.json --candidate-adapter baseline-83f3cd70 \
  --workload lifecycle.no-script-one-step.v1 --blocks 2 --forks-per-block 1 \
  --warmups 0 --iterations 1 --seed 5928239383101656625 \
  --metrics latency \
  --artifacts-dir "$SMOKE_ROOT/artifacts" \
  --output "$SMOKE_ROOT/smoke.json"
"$DRIVER" verify --input "$SMOKE_ROOT/smoke.json"
```

Smoke output is structural evidence only. Its synthetic host samples record `UNAVAILABLE` power
evidence, and it cannot pass release gates because it has no verified controlled-host policy and
does not meet the release sample counts.

### Controlled cold and warm campaigns

Release evidence is captured only by the manual `Controlled performance benchmark` workflow. It
runs on the protected `performance` environment with the
`[self-hosted, linux, revoman-controlled-benchmark]` labels. An administrator must provision a
readable absolute host-policy file and writable `/opt/revoman-benchmark/runs`; workflow code does
not create or relax policy.

Observed external power is runtime Linux power-supply sysfs evidence: the policy records whether
an external source is online or offline, and `REQUIRE_EXTERNAL_POWER` rejects an offline sample.
`FIXED_MAINS` is instead an administrator-owned, host-specific attestation that runtime power
telemetry is not applicable. It requires an existing empty `/sys/class/power_supply` directory;
any entry makes the controlled probe fail rather than fabricating an online observation.

The workflow builds the driver from a separately pinned full harness SHA, exports three independent
clean manifests (`baseline-a`, `baseline-b`, and `candidate`), then runs these campaign shapes:

- cold: 50 accepted paired blocks, one fork per role and block, zero warmups, one iteration, and
  latency/peak-RSS/JFR-allocation passes;
- warm: five accepted paired blocks, one fork per role and block, 20 warmups, 100 measured
  iterations, and latency/normalized-JMH-allocation passes.

Both shapes use seed `5928239383101656625` and pass the selected policy as
`--host-policy "$HOST_POLICY_PATH"`. Cold and warm A/A are captured and compared with
`--enforce-release-gates` before candidate execution. If either A/A comparison is not `PASS`, the
workflow records `INCONCLUSIVE`, uploads available result/JFR evidence, and makes no candidate
claim.

For an accepted campaign, validate every machine-readable result before citing it:

```bash
DRIVER=benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver
"$DRIVER" verify --input "$RUN_ROOT/results/cold-aa.json"
"$DRIVER" verify --input "$RUN_ROOT/results/warm-aa.json"
"$DRIVER" verify --input "$RUN_ROOT/results/cold-candidate.json"
"$DRIVER" verify --input "$RUN_ROOT/results/warm-candidate.json"
```

The fixed baseline is
`83f3cd70f78ad733412d10cbc8287aaabafe7aac`. It is rebuilt and rerun in every alternating campaign;
an old result is never reused as the denominator. That keeps machine load, JDK/Gradle state,
provider configuration, and other time-local host effects paired with the candidate. Historical
captures are audit records, not portable performance constants. See the
[v1 baseline protocol](docs/superpowers/benchmarks/baseline.md) for the evidence and identity rules.

## Building the jar for Salesforce Core consumption

Salesforce Core consumes ReVoman as a **prebuilt jar** through a bazel `java_import`
(`com.salesforce.revoman:revoman`). A `java_import` provides **no transitive dependencies** —
Core only gets the classes physically inside the jar plus whatever Core itself already has on
its classpath. Build the consumable jar (and its sources jar) with:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn   # any JDK 21; the build needs 21 (detekt breaks on 25)
./gradlew spotlessApply                                        # format first, else spotlessCheck fails the build
./gradlew jar sourcesJar -x detekt -x test --rerun-tasks       # the consumable jar + sources jar
# → build/libs/revoman-<version>.jar  and  build/libs/revoman-<version>-sources.jar
```

`-x detekt -x test` skips the JDK-sensitive static analysis and the slow integration tests when
you only need a consumable jar; `--rerun-tasks` defeats Gradle's up-to-date cache so the jar is
actually rebuilt.

### The kotlinx-collections-immutable fat-jar bundle

The `jar` task **bundles `kotlinx-collections-immutable` INTO the jar** (see the
`bundledRuntime` configuration in `build.gradle.kts`). This is deliberate and required:
`PersistentBackedMutableMap` (perf PR #401) uses that library, but it is not on Core's classpath,
and the `java_import` supplies no transitive deps — so a *plain* jar throws
`NoClassDefFoundError: kotlinx/collections/immutable/ExtensionsKt` on every `revUp` inside the
Core server. Only that one artifact's **classes** are bundled (`isTransitive = false`, so
`kotlin-stdlib` — which Core already has — is not duplicated). Every other `implementation` dep
(graal/okio/snakeyaml/spring) is already on Core's classpath, which is why pre-#401 jars worked
unbundled.

**Verify the bundle is present** after building (expect a non-zero count, ~130 classes):

```bash
unzip -l build/libs/revoman-*.jar | grep -c 'kotlinx/collections/immutable'
```

Core *also* pins `kotlinx-collections-immutable:0.4.0` in its own maven graph (via
`graph-tool add-dependency`, so `@org_jetbrains_kotlinx_kotlinx_collections_immutable` resolves
natively). The fat-jar bundle and the Core-graph dep are **belt-and-suspenders** — keep both. Do
NOT additionally wire the dep as a `runtime_deps` on the revoman `java_import`: with the fat-jar
already bundling the classes, that would double-supply them (duplicate classes on the classpath).
The graph dep stays available for a future switch to a pure Core-native path (drop the fat-jar
bundle, then add the `runtime_deps`), but today the fat jar is the live supply.

### JPMS module name (`Automatic-Module-Name`)

The `jar` task stamps `Automatic-Module-Name: com.salesforce.revoman` into the manifest (see
`build.gradle.kts`). This is a **stable JPMS module name** for consumers on the Java module path —
nothing more. It is deliberately **NOT** a full `module-info.java`, and it should stay that way:

- **Core doesn't see it anyway.** Core consumes revoman via a bazel `java_import` → the
  **classpath** → the *unnamed module*, where a `module-info.class` is ignored entirely. Full
  JPMS would encapsulate against a module path Core never uses.
- **Reflection would force opening `internal`.** Moshi (moshix codegen), kapt/Immutables, and
  Spring `BeanUtils` reflect into revoman's own types across `input`, `output`, **and**
  `internal`. A real `module-info` would need broad `opens` — including `opens ...internal` —
  defeating the encapsulation that would be the only reason to add it.
- **Deps aren't module-ready.** Several runtime deps (http4k, moshi, snakeyaml, underscore,
  pprint, kotlinx-collections-immutable, kotlin-logging) are plain jars with no
  `Automatic-Module-Name`, so `requires` clauses would bind to fragile filename-derived names.

Because the bundled `kotlinx-collections-immutable` is a **multi-release jar**, the `jar` task
must exclude its *versioned* descriptor (`META-INF/versions/*/module-info.class`) on top of the
top-level `module-info.class` — otherwise that surviving descriptor makes revoman resolve as the
explicit module `kotlinx.collections.immutable` on the module path and the `Automatic-Module-Name`
is ignored. Keep both excludes (see the `jar` block in `build.gradle.kts`).

Rationale and rejected alternatives (full `module-info`, multi-release modular jar) are recorded
in `docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md`. Verify the attribute
after building:

```bash
unzip -p build/libs/revoman-*.jar META-INF/MANIFEST.MF | grep 'Automatic-Module-Name'
# → Automatic-Module-Name: com.salesforce.revoman
```

### How Core picks up a locally-built jar

Core's `.bazelrc-local` overrides the `com_salesforce_revoman_revoman` repository to a local
checkout, and that repo's `BUILD.bazel` globs `build/libs/revoman-*.jar`. So a rebuilt jar here
is picked up by Core on its next **server restart** (a `java_import` jar is not hot-reloaded — the
running server holds the old bytecode until it restarts). ReVoman-library change → rebuild the jar
here → restart the Core server.

### Core Maven-graph exclusions on the `revoman` dependency (why they exist)

When ReVoman is consumed through Core's Maven graph (`third_party/dependencies/com_salesforce_revoman.bzl`),
the `com.salesforce.revoman:revoman` artifact carries these `exclusions`, which **graph-tool strips
the explaining comments from on every version bump** (the file header says "Formatting and comments
will not be preserved"), so the rationale is recorded HERE instead:

- `org.apache.logging.log4j:*` — Core supplies its own logging stack.
- `org.hamcrest:*` — Core supplies its own test matchers.
- `org.graalvm.truffle:truffle-runtime` and `org.graalvm.truffle:truffle-compiler` — Core already
  provides a coherent GraalVM 25.0.3 stack (truffle-api, js-language, polyglot, …). ReVoman
  transitively drags `truffle-runtime`/`truffle-compiler` **25.1.3** (a `runtimeOnly` optimizing-
  compiler substitution on ReVoman's side); pulling those into Core would skew Truffle against Core's
  25.0.3 `truffle-api`. Excluding them makes ReVoman fall back to Core's coherent stack — GraalJS
  still runs on the interpreter runtime, exactly as every other Core GraalJS consumer.

If a future ReVoman release changes its GraalVM/Truffle floor, revisit these two truffle exclusions.

### Silencing the startup banner (embedded/server use)

ReṼoman prints a one-per-JVM ASCII banner on the first `revUp` and a one-per-JVM "star us" line on JVM shutdown. Delightful in a test run, noise in the Core server — so it is **on by default, suppressible**. Silence both with either lever (system property wins):

- `-Drevoman.banner=off` (JVM arg), or
- `REVOMAN_BANNER=off` (env var).

`off` / `false` / `0` / `no` all silence it. Core sets one of these once in server bootstrap. Since the banner is emitted as a `com.salesforce.revoman` INFO log event, raising that logger's level also hides it.

### Propagating a release into Core

`scripts/release.sh <version>` bumps the version, publishes to Maven Central, waits for the jar to
go live, then runs `graph-tool` **from the Core checkout** to bump the revoman dep and re-pin. revoman
is a Maven-coord dep, so bump it with **`set-dependency-version <group:artifact>`**, then re-pin:

```bash
# from the Core checkout root
bazel run //:graph-tool -- set-dependency-version com.salesforce.revoman:revoman --new-version=<version>
bazel run //:graph-tool -- pin-dependencies
```

`set-dependency-version <group:artifact>` bumps a Maven-coord dep; `set-version-variable
--variable-name=<VAR>` is for named version variables (e.g. `_HTTP4K_VERSION`). Handy zsh wrappers:
`graph-set-dep-version <group:artifact> <version>` and `graph-set-version-variable <VAR> <version>`.

## Development Environment

- **JDK**: 21+ required for JVM target
- **Targets**: JVM

## Gradle Wrapper & Offline Builds

- Always prefer `./gradlew` — the Gradle version is whatever `gradle/wrapper/gradle-wrapper.properties` declares (do not hardcode it here).
- The repo bundles only the wrapper bootstrap (`gradle/wrapper/*`), NOT the
  ~150MB distribution. A fresh machine downloads the distribution once to
  `~/.gradle/wrapper/dists/`, then reuses it — the download is expected, not a bug.
- **Fallback:** if `./gradlew` can't fetch the distribution (offline, or
  services.gradle.org is unreachable — e.g. behind the SFDC workspace proxy),
  use the machine's installed `gradle` instead. Note the local version may
  differ from the wrapper's, so build behavior can vary — use only as a last resort.
- **Blocked plugin portal (SFDC workspace):** `plugins.gradle.org` is unreachable
  behind the proxy, so `settings.gradle.kts` and `buildSrc` add an internal Nexus
  plugin mirror as a fallback. It is driven entirely by three Gradle properties in
  `~/.gradle/gradle.properties` — `nexusGradlePluginsUrl`, `nexusUsername`,
  `nexusPassword` — and is a no-op when they are unset (CI / public machines resolve
  from the public repos as before). Nothing SFDC-internal is checked in.
