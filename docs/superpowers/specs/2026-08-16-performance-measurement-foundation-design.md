# Performance Measurement Foundation — Design

- Status: Proposed — design direction approved; committed text awaiting review
- Date: 2026-08-16
- Branch: `overfullstack/perf`
- Starting SHA: `009bc8f4c1fe9fb7d393036616a3c3b6cd787aca`

## Decision summary

ReVoman will first make performance measurement trustworthy, then fix one confirmed resource-
ownership defect, measure the result, and stop before starting another optimization.

The first tranche has five deliverables:

1. A classpath-preserving, fail-closed JMH distribution.
2. Deterministic cold and warm real-wire benchmarks of the public `ReVoman.revUp` interface,
   using a minimal V3 collection.
3. Versioned JSON evidence, a focused baseline/candidate comparator, and Markdown summaries.
4. A test-driven breaking cleanup of path-resource ownership that removes the confirmed file-
   descriptor leaks.
5. An A/A calibration, baseline/candidate comparison, and evidence-based ranking of the next
   hotspot. No second optimization is included.

Backward compatibility is not required. Where the current public API encodes ambiguous ownership,
the implementation will replace it directly rather than add a compatibility layer.

DuckDB, dashboards, numeric timing gates in ordinary CI, and a general-purpose performance data
platform are intentionally deferred.

## Context and confirmed failures

### The current JMH result is not evidence

At the starting SHA, the standard `jmhJar` task flattens production and benchmark dependencies into
one 92 MiB jar. The jar:

- omits the `Multi-Release: true` manifest attribute required by Graal/Truffle;
- combines service metadata in a way that prevents Log4j from loading its real provider;
- includes test classes plus JUnit, Kotest, MockK, and ByteBuddy because `includeTests` defaults to
  `true`;
- fails `jar --validate` with thousands of diagnostics; and
- can execute a failing benchmark, emit no measurement row, and still make Gradle report
  `BUILD SUCCESSFUL` because JMH is fail-open by default.

`build.gradle.kts` currently configures only the JMH version and optional include pattern. A result
file containing only a header, or JSON `[]`, is therefore currently indistinguishable from a
successful Gradle invocation unless a human inspects the output.

Adding only `Multi-Release: true` does not repair the artifact. A bounded diagnostic repack moved
execution past the Truffle error but then exposed broken Log4j provider discovery and missing Graal
JavaScript options. The root cause is dependency flattening, not one missing manifest line.

### Path-based reads leak descriptors until GC

The current path-reading helpers return `BufferedSource` values without a binding ownership
contract, and internal callers consume them without `use`/`close`:

- `FileUtils.readFileToString` and `readGzippedFileToString`;
- V2 collection loading in `ReVoman.revUp`;
- V2 environment and JSON POJO loading;
- V3 environment loading; and
- V3 request and definition reads during directory walking.

A live JDK 21 probe performed 200 absolute-path reads in one JVM. Open descriptors rose from roughly
10 to roughly 210 and returned to the starting level only after an explicit GC. This is a
correctness and long-lived-JVM resource problem, regardless of whether closing improves latency.

### Other plausible bottlenecks remain outside this tranche

The audit also found:

- successful-step progress synchronization repeatedly copies prior progress, producing quadratic
  growth with step count;
- environment replacement creates multiple whole-map copies per executed step;
- each nonblank script snapshots, proxies, serializes, and diffs all scalar scopes;
- polling retains every response and cannot preempt a synchronous HTTP call or its final sleep;
- no-op logging still eagerly builds messages, while real sinks can render full HTTP messages even
  when their output capability does not require them; and
- the file sink flushes every write and can read/rewrite the complete output on close.

These findings are inputs to later prioritization, not authorization to change them in the first
tranche. Several earlier hot paths already have fixes at the starting SHA, including shared HTTP
clients, shared Graal engines, lazy sandbox resources, persistent environment snapshots, and a regex
fast path. They must not be reimplemented.

## Goals

1. Make an empty, failed, malformed, or packaging-corrupted benchmark run fail the outer Gradle
   invocation.
2. Run JMH from an artifact that preserves the dependency semantics used by a normal production
   classpath.
3. Measure the public `ReVoman.revUp(Kick)` lifecycle over deterministic loopback HTTP.
4. Distinguish first-use-in-a-fresh-fork behavior from warmed in-process behavior.
5. Bind every result to the code, workload, artifact, runtime, host, settings, and logs that produced
   it.
6. Compare an explicitly selected baseline and candidate and report both gain and uncertainty.
7. Fix the confirmed path-resource leak with failing tests written first.
8. Leave behind a repeatable one-hotspot-at-a-time workflow for later optimizations.

## Non-goals

- JVM launcher or operating-system process-start time.
- Duration-driven throughput or request-rate claims.
- Percentile latency claims; the V1 protocol estimates a central end-to-end cost.
- Timing enforcement on shared or hosted CI runners.
- DuckDB as authoritative storage, a dashboard, or a benchmark-results service.
- External-network benchmarks.
- A full Cartesian workload matrix.
- V2-versus-V3 performance claims.
- Parsed-collection caching.
- Redesigning the process-lifetime ZipFS registry in `ClasspathResolver`.
- Changes to `StepReport.exeTimings` or treating it as release-gating evidence.
- Progress, scope synchronization, polling, logging, or file-sink optimization.
- Expanding the public mock-server API solely for benchmark convenience.
- Preserving source or binary compatibility for the resource-ownership API cleanup.

## Terms

- **Canary**: a short structural execution proving that packaging, provider discovery, benchmark
  discovery, result production, and failure propagation work. Its score is not performance evidence.
- **Cold operation**: the first `revUp` invocation in a fresh JMH fork. JVM launcher startup and
  JMH harness startup are outside the timed operation.
- **Warm operation**: a `revUp` invocation after a fixed number of untimed `revUp` warmups in the
  same fork, retaining process-wide Graal and HTTP-client state.
- **Capture**: one validated, immutable bundle of canonicalized JMH output, normalized metadata,
  sanitized logs, and checksums.
- **Cell**: one benchmark plus its exact parameter set, mode, unit, threads, and profile.
- **Campaign**: an explicit baseline/candidate comparison across one or more required cells.
- **Canonical capture**: a valid capture made from a clean, committed tree using a checked-in
  profile on a declared controlled host.
- **A/A comparison**: two independent captures of the same SHA and treatment, used only to calibrate
  noise and sample counts. It cannot substantiate a gain claim.

## Architecture

One internal performance-measurement module in `buildSrc` owns the full evidence boundary:

```text
performanceCanary ─┐
performanceCapture ├─> classpath distribution -> JMH process -> validator -> capture bundle
performanceCompare┘                                             |
                                                                v
                                                      JSON + Markdown comparison
```

Its interface is three Gradle entry points:

```text
./gradlew performanceCanary
./gradlew performanceCapture \
  -Pperformance.profile=<cold|warm> \
  -Pperformance.hostId=<opaque-controlled-host-id> \
  -Pperformance.sessionId=<opaque-session-id> \
  -Pperformance.sequence=<positive-integer> \
  [-Pperformance.distribution=<frozen-distribution-directory>] \
  -Pperformance.output=<directory>
./gradlew performanceCompare \
  -Pperformance.kind=<calibration|candidate> \
  -Pperformance.baseline=<capture-directory> \
  -Pperformance.candidate=<capture-directory> \
  [-Pperformance.calibration=<passing-A1-A2-comparison-directory>] \
  [-Pperformance.policy=<policy-file>] \
  -Pperformance.output=<comparison-directory>
```

The implementation behind that interface owns:

- JMH compilation and generated benchmark metadata;
- construction and validation of the classpath-preserving distribution;
- the exact JDK executable, JVM arguments, JMH arguments, and logging profile;
- process exit handling and expected-row validation;
- capture metadata, privacy filtering, checksums, staging, and atomic publication;
- compatibility checks and statistical comparison; and
- machine-readable and human-readable reports.

If `performance.distribution` is omitted, capture builds the current clean commit first. If supplied,
it runs that frozen self-contained distribution after verifying its embedded build/protocol manifest.
This permits a pre-fix baseline artifact to be rerun beside a later candidate without checking out or
rebuilding either treatment during the controlled timing session.

This is a deep module: deleting it would scatter artifact assembly, process execution, validation,
metadata capture, compatibility rules, and comparison logic across Gradle tasks, shell scripts, CI,
and documentation. Splitting those responsibilities into provider abstractions in V1 would add
interfaces without a second implementation and is rejected.

Proposed locations:

```text
buildSrc/src/main/kotlin/performance/**
buildSrc/src/test/kotlin/performance/**
config/performance/profiles/*.json
config/performance/policies/*.json
src/jmh/kotlin/com/salesforce/revoman/benchmark/scenario/**
src/jmh/kotlin/com/salesforce/revoman/benchmark/*Benchmark.kt
src/jmh/resources/performance/**
docs/superpowers/benchmarks/**
```

## Authoritative benchmark artifact

### Classpath-preserving distribution

The authoritative artifact is a versioned directory/archive containing untouched dependency jars:

```text
revoman-performance-<version>/
├── bin/                         # generated launcher metadata/scripts
├── app/revoman.jar              # production classes/resources
├── benchmark/revoman-jmh.jar    # benchmark + generated JMH classes/resources
├── lib/*.jar                    # exact resolved dependency jars, never unzipped
└── metadata/
    ├── classpath.json           # ordered effective entries, coordinates, sizes, hashes
    ├── protocol.json            # measurement-protocol closure
    └── distribution.sha256      # every other distribution file; never hashes itself
```

The benchmark jar contains generated `META-INF/BenchmarkList` and `META-INF/CompilerHints`. Test
output and test dependencies are excluded. Dependency jars retain their own manifests,
multi-release classes, service descriptors, module descriptors, and signatures.

`includeTests=false` is set at the JMH plugin configuration seam, before bytecode generation and
generated-metadata compilation. Filtering the final distribution is not sufficient because test
benchmarks could already have contaminated `BenchmarkList`.

The production jar intentionally embeds `kotlinx-collections-immutable`. Its standalone resolved
jar is therefore recorded in dependency metadata with placement `embedded:app/revoman.jar` and is
not also placed under `lib/`. All other dependency jars are copied untouched. The classpath uses an
explicit ordered entry list—never a wildcard. Duplicate validation computes each loadable
binary-class identity for the selected recorded JVM feature version after applying each jar's own
multi-release rules, ignores per-jar
`module-info.class` descriptors on the classpath, and rejects any remaining identity supplied by
more than one effective entry. The forked JVM command line must match that ordered list exactly.

The standard flattened `jmh` and `jmhJar` tasks are disabled with an explanatory `GradleException`.
They cannot remain as an easy fail-open alternative. Documentation and CI use only the three
performance tasks. JMH compilation, bytecode generation, and metadata tasks that do not flatten or
run the artifact remain private implementation dependencies.

### Artifact validation

Before launching JMH, the module must verify:

1. Production and benchmark jars validate individually.
2. No test class or test-only dependency is present in the ordered classpath.
3. No duplicate effective loadable binary class exists across classpath entries after the stated
   multi-release/module-descriptor rules.
4. Generated benchmark metadata exists and lists the expected benchmark set.
5. Every effective classpath entry matches `metadata/classpath.json`, and every distribution file
   except the checksum manifest itself matches `metadata/distribution.sha256`.
6. The fork command uses that exact ordered classpath with no wildcard or extra entry.
7. The selected Java executable is JDK 21 or newer and is exactly the executable recorded later.
8. The output/staging directory is new; stale result files cannot satisfy the run.

The launcher invokes JMH with `failOnError=true`/`-foe true` and JSON output. The outer Gradle task
must also fail if JMH exits nonzero, the result is absent or malformed, or row validation fails.

`metadata/protocol.json` defines the exact measurement-protocol closure. Its canonical hash covers:

- all `buildSrc/.../performance` implementation and schema sources;
- benchmark/scenario sources and packaged resources;
- launcher templates and expected-cell declarations;
- benchmark-only logging configuration;
- the selected checked-in capture profile; and
- Gradle wrapper, JMH Gradle-plugin, JMH core, Kotlin compiler, and Java toolchain identities.

The distribution also records hashes of the compiled benchmark jar and launcher implementation.
Those compiled hashes and the protocol hash must match between baseline and candidate. A/A further
requires every artifact hash, including the production jar, to match.

Shadow and a manually repaired fat jar are rejected for V1. Shadow would be the fallback only if a
single-file executable became a binding requirement; hand-maintained service merging would create a
repository-owned shading engine with poor locality and ongoing dependency-upgrade risk.

## Benchmark protocol

### Structural canary

`performanceCanary` builds and runs the authoritative distribution with one fork, zero warmups, one
short measurement, and no timing threshold. It requires exactly the declared canary rows.

The sandbox canary:

- initializes Log4j and asserts that the real production provider/context factory is active;
- rejects SimpleLogger fallback or Log4j provider/status initialization errors;
- evaluates a minimal `PmSandbox` JavaScript expression so Graal/Truffle multi-release loading and
  JavaScript option discovery are exercised; and
- produces one finite nonempty raw measurement.

The V3 real-wire canary executes one bounded operation and validates its functional invariants. Its
score is discarded; it proves that the end-to-end scenario is runnable from the packaged
distribution.

A Gradle TestKit test supplies a deliberately failing benchmark setup to the same runner used by the
canary. The expected result is a nonzero outer Gradle invocation and an invalid diagnostic bundle,
never a successful empty table. The production canary itself contains no intentionally failing row.

### Primary V3 real-wire scenario

The benchmark owns a minimal V3 fixture rather than borrowing the broader, stateful integration
fixture:

```text
performance/revup-v3/
├── .resources/definition.yaml
├── benchmark.request.yaml
└── benchmark.environment.yaml
```

The request is `GET {{baseUrl}}/benchmark`.

- The V3 environment defines `markerSeed: fixture-marker`.
- A deterministic pre-request script reads that value and sets a derived marker consumed by the
  outgoing request. This makes V3 environment loading observable on the wire.
- A strict loopback handler accepts only the expected method, path, and derived marker and returns
  the exact bytes `{"id":42}` with `Content-Type: application/json; charset=utf-8`.
- A deterministic response script parses the body, records one passing `pm.test`, and writes the ID
  into the environment.
- Only `baseUrl` is dynamically overlaid with the server's ephemeral origin.

V3 is the primary format because `revUp` reloads and normalizes it on every invocation. V3 therefore
exercises the public production path, including classpath/JAR directory resolution, definition and
environment reads, YAML parsing, source hashing, V3-to-V2 normalization, scripts, HTTP, environment
synchronization, and report creation. V2 still performs parsing and would not isolate the runtime;
it would only omit V3-specific work and the exact ownership defects in scope.

The fixture identity is not `Item.sourceHash`. Build creates a canonical JSON tree manifest over
every regular file under the source fixture root. Entries are sorted by relative UTF-8 path and
contain exactly `{path, byteLength, sha256}`; canonical JSON is UTF-8 with lexicographically ordered
object keys, no insignificant whitespace, and a trailing newline. The tree hash is SHA-256 of those
canonical bytes. Symlinks and unexpected files are rejected, and the environment file is included.
Artifact preflight recomputes every entry hash from the bytes packaged inside the benchmark jar and
requires exact equality with the source manifest before execution.

### Scenario lifecycle

A shared benchmark scenario hides server startup, handler behavior, `Kick` construction, counters,
validation, and teardown. Cold and warm JMH benchmark classes are thin adapters over it.

- The mock server is trial-scoped and uses an ephemeral loopback port.
- Trial setup is transactional: resources remain local until acquisition is complete, and any setup
  failure eagerly closes the server because JMH may skip teardown for an unready trial state.
- The immutable `Kick` is reused within a fork.
- `Rundown.learnedLedger` is never fed into the next run, so each operation performs real HTTP.
- Warm operations reuse the same server origin, HTTP connection pool, and process-wide Graal engine.
- The existing request ledger remains bounded because every profile has fixed operation counts.
- No duration-driven benchmark is permitted for this scenario.
- Handler matching and request-ledger capture occur inside the HTTP round trip and are classified as
  fixed measured fixture overhead.
- Post-operation assertions run in `@TearDown(Level.Invocation)`, outside the timed method, and any
  assertion or teardown exception fails the fork.
- Each invocation verifies one successful report, HTTP 200, exact JSON content type/body, expected
  assertion, expected final environment ID, and a one-request handler-count delta.
- Trial teardown verifies the retained request total and wire shape, then closes the server while
  preserving both verification and close failures.
- A measured `revUp` failure or invocation-validation failure eagerly closes the trial resource via
  an idempotent failure path; cleanup cost in an already-invalid invocation is irrelevant, while
  primary and close failures are both preserved.

The benchmark reuses public `MockHttpServer`, `MockHttpHandler`, `ReVoman.revUp`, `Kick`, and
`Rundown`. It does not promote `DeterministicMockApi`, add a reset method to `MockHttpServer`, or
create a shared fixture source set for one tiny scenario.

### Cold and warm profiles

Both profiles use JMH `SingleShotTime`, one operation per iteration, one thread, and fixed counts.

| Profile | Forks | Warmup iterations | Measurement iterations | Meaning |
|---|---:|---:|---:|---|
| Canary | 1 | 0 | 1 | Structural validity only |
| Cold calibration seed | 10 | 0 | 1 | First `revUp` in each fresh fork |
| Warm calibration seed | 10 | 5 | 10 | Initial A/A profile; may be increased before baseline |

Cold and warm counts are finalized independently in checked-in profiles after A/A calibration. If
counts change, calibration is rerun from fresh captures. Once a baseline artifact is frozen, its
profile is immutable; changing counts creates a new protocol/profile identity and requires a new
baseline.

JVM launcher startup is explicitly excluded. If it becomes important, it will be a separate
external-process benchmark rather than being mislabeled as JMH cold behavior.

The cold operation is specifically the first `revUp` after JMH state setup, server startup, and
Log4j provider preflight. Log4j initialization and fixture acquisition are excluded and kept
identical across profiles; “cold” does not mean first use of every library in the process.

### Logging profile

Benchmarks use a uniquely named benchmark-only Log4j configuration selected in fork JVM arguments.
The real Log4j provider must initialize, while application output is set to `OFF`. The run uses
`RunLogSink.NoOp`, and the banner is disabled before the first `revUp`.

This profile intentionally measures current eager no-op message construction but excludes terminal
or file I/O. Console logging, file logging, and alternate sink capabilities are later independent
profiles, not parameters mixed into the primary cell.

GC profiling is allowed for warm diagnostic captures. JFR is diagnostic-only and cannot replace
the canonical primary metric in V1.

## Capture evidence

### Bundle layout

Each attempted capture is written to a new sibling staging directory under the destination parent,
so publication can use one same-filesystem atomic rename. A valid capture is renamed to:

```text
<UTC timestamp>-<full SHA>-<profile>/
├── capture.json
├── jmh-result.json
├── stdout.log
├── stderr.log
└── checksums.sha256
```

`jmh-result.json` is a canonical privacy-filtered projection of JMH output, not a byte-for-byte copy
of the temporary child-process file. Measurement arrays and semantic values are preserved, while
absolute executable paths are replaced with stable tokens. `capture.json` records the SHA-256 of
the temporary raw input and the sanitizer version. The unsanitized file is deleted after validation
and is never published.

`checksums.sha256` covers every other published file in the bundle. Failed attempts receive the same
privacy treatment, are retained separately as explicit `INVALID` diagnostic bundles, and can never
appear in the valid-capture namespace.

### Required metadata

`capture.json` is a strict, versioned schema with capture-global sections for run identity,
environment, artifacts, protocol, and a `cells[]` array. Global metadata contains:

- schema and benchmark-protocol versions;
- capture ID, process-run ID, performance-session ID, session sequence, status, UTC timestamps, and
  process exit;
- measured-distribution Git SHA/build-clean assertion plus capture-runner Git SHA/tree-clean
  assertion;
- benchmark source/protocol hash and canonical workload-tree hash;
- production, benchmark, distribution, and ordered-classpath hashes;
- dependency coordinates and hashes;
- Gradle, JMH Gradle-plugin, JMH core, Kotlin compiler, and schema/sanitizer versions;
- JDK binary SHA-256, vendor, complete version, and JVM arguments, but not its absolute path;
- OS, kernel, architecture, CPU model, logical CPU count, and an operator-supplied opaque host ID;
- logging profile; and
- profile identity plus declared forks, warmup, measurement, and profilers.

Every `cells[]` entry contains benchmark name, parameters, mode, unit, threads, batch size, primary
metric name/direction, an exact JSON pointer and hash for its authoritative `jmh-result.json` row,
declared sample dimensions, and derived per-fork summaries. Raw observations occur only in
`jmh-result.json`; validation recomputes every summary and requires exact agreement. Diagnostic
secondary metrics such as GC profiler output are identified separately and never become required
comparison cells implicitly.

The bundle must not contain usernames, home/workspace paths, hostnames, IP addresses, environment
variables, command-line secrets, or raw machine IDs. JMH's absolute `jvm` field, human `VM invoker`
line, commands, and logs are sanitized before publication. The opaque host ID is supplied explicitly
to `performanceCapture` by the operator and contains no personal information.

### Validity rules

A capture is valid only if all of these hold:

1. The capture runner tree is clean, and the measured distribution proves it was built from its
   recorded clean full SHA.
2. Artifact preflight passed.
3. The child process exited zero with fail-on-error enabled.
4. Log scanning found no known provider fallback, multi-release, interpreter-fallback, or benchmark
   failure signature.
5. JMH JSON is present, parseable, and nonempty.
6. The exact expected benchmark/parameter matrix occurs once—no missing, duplicate, or unexpected
   row.
7. Every controlled primary raw observation is finite and strictly positive, and its dimensions
   match the declared forks and iterations. Derived JMH `scoreError`/`scoreConfidence` values may be
   `NaN` for the one-sample canary and are discarded rather than treated as comparator inputs.
8. Functional scenario verification and teardown succeeded.
9. Every output validates against its schema and checksum manifest.

No later normalization step may turn an invalid run into a valid capture.

## Comparator

### Explicit selection and compatibility

The comparator receives an explicit comparison kind plus baseline and candidate paths. Baseline and
candidate are relational roles written into comparison evidence; they are not mutable labels stored
inside capture bundles. The comparator never guesses from `latest`, Git ancestry, timestamps,
filenames, or a database query.

A performance-claim comparison requires distinct baseline and candidate SHAs. An A/A comparison
requires the same SHA and every artifact hash to match, and is labeled calibration-only. Both kinds
require distinct capture IDs, process-run IDs, and directories; supplying one capture twice is
`INVALID`.

`kind=candidate` additionally requires the passing A1/A2 calibration comparison directory. The
candidate comparator verifies its checksum/schema, that its A2 capture ID exactly equals the selected
performance baseline, that A1/A2 calibration passed for every required cell, and that A1, A2, and B
share the same session ID with consecutive sequence values inside the declared duration. Missing,
failed, stale, or unrelated calibration evidence makes the candidate comparison `INVALID`.

For each cell, the following identities must match exactly:

- capture schema and benchmark-protocol version;
- benchmark name and parameter set;
- benchmark source/protocol hash and V3 workload-tree hash;
- compiled benchmark jar, launcher implementation, and logging-configuration hashes;
- mode, unit, threads, batch size, forks, warmup, measurement, and profiler identity;
- Gradle, JMH Gradle-plugin, JMH core, Kotlin compiler, and sanitizer versions;
- JDK binary SHA-256, vendor, complete version, and JVM arguments;
- logging profile;
- opaque host ID, OS/kernel/architecture, and CPU model/count; and
- the complete dependency graph and dependency hashes.

Production artifact and Git hashes are recorded and are expected to differ. A dependency change
creates a new protocol identity and requires a new baseline in V1; it is never silently treated as
compatible. Incompatible captures produce an `INCOMPATIBLE` result, not a warning followed by
arithmetic.

### Estimator and uncertainty

Only the declared primary `SingleShotTime` wall-time metric is compared in V1, and lower is better.
GC-profiler and other secondary metrics remain diagnostic. Each primary cell requires at least ten
independent forks.

For each compatible cell:

1. Reduce every fork independently to the median of its measurement iterations. Cold forks contain
   one observation. For an even count, median is the arithmetic mean of the two middle sorted
   values.
2. Compute the point ratio as candidate median fork-summary divided by baseline median fork-summary.
3. Report percentage gain as `(1 - ratio) * 100`; positive is faster for a lower-is-better metric.
4. Independently bootstrap baseline and candidate fork summaries with replacement 20,000 times.
5. Seed the bootstrap with the first eight bytes, interpreted as an unsigned big-endian integer, of
   SHA-256 over canonical length-prefixed UTF-8 baseline capture ID, candidate capture ID, and cell
   identity.
6. Sort bootstrap ratios and report the 2.5th and 97.5th percentiles using Hyndman-Fan type 7 linear
   interpolation.
7. Use unrounded values for every classification; round only display fields.

Canonicalized JMH JSON remains authoritative input; normalized values never replace it. The interval
describes conditional fork-resampling uncertainty within the captured session. It does not estimate
between-day or between-session host drift, and every Markdown report states that limitation.

### A/A calibration and session order

Each profile starts with two consecutive, independent captures of the same frozen distribution at
ten forks. Calibration succeeds only when:

- the 95% ratio interval contains `1.0`;
- the point ratio lies in `[0.95, 1.05]`; and
- the ratio interval width is at most `0.10`.

If calibration fails, fork count doubles to 20 and then 40, with two fresh captures and a new profile
identity each time. If the 40-fork profile still fails, that profile is too noisy for a V1
performance claim; the result remains diagnostic and no baseline is approved. A count change is
never made after inspecting candidate results.

Canonical candidate acquisition occurs in one declared performance session using previously frozen
self-contained distributions: two baseline A/A captures followed immediately by the candidate
capture. The second A/A capture is the comparison baseline. The profile declares a maximum session
duration, initially two hours; exceeding it invalidates the campaign. Capture metadata records the
session ID and monotonically increasing sequence. This ordering bounds—but does not statistically
model—temporal drift.

### Outcomes

Direction and policy are separate:

- `IMPROVEMENT`: the interval's upper bound is strictly below `1.0`.
- `REGRESSION`: the interval's lower bound is strictly above `1.0`.
- `INCONCLUSIVE`: otherwise, including equality with `1.0`.

If no checked-in policy is supplied, policy outcome is `NOT_ENFORCED` while gain and uncertainty are
still reported. With a maximum regression budget `b`:

- `PASS`: the interval's upper bound is at most `1 + b`;
- `FAIL`: the interval's lower bound is above `1 + b`; and
- `INCONCLUSIVE`: otherwise. Equality of the upper bound with `1 + b` passes; equality of only the
  lower bound is inconclusive.

An enforced campaign passes only when every required cell passes. Cells are never averaged together
to hide a regression. Invalid, incompatible, or undersampled cells cannot pass.

The comparator atomically publishes a directory containing versioned `comparison.json`, concise
`comparison.md`, and `checksums.sha256` covering both. The reports contain capture identity,
compatibility, point ratios, percentage gains, intervals, direction, and policy outcome.
When a policy is supplied, both outputs record its canonical hash and thresholds. Comparison policy
does not alter capture protocol identity or require recapture. Candidate outputs also record the
calibration-evidence hash and A1/A2/B capture IDs. JSON plus Markdown are the V1 sources of truth.

DuckDB may later query those JSON files as a derived read model. It is not part of capture,
validation, comparison, or publication in V1.

## Breaking resource-ownership redesign

### Ownership rule

Library-resolved path resources are owned by the library and closed in the same lexical scope in
which they are opened. Caller-supplied `InputStream` values remain caller-owned.

The implementation will:

- remove public `bufferFile` overloads, `bufferV3Definition`, and `bufferInputStream` rather than
  deprecate or wrap them;
- retain value-returning public operations such as `readFileToString`, making them close internally;
- retain `readInputStreamToString` with an explicit non-closing caller-ownership contract;
- keep raw source-opening helpers internal, using a scoped read/parse function for owned path
  sources and a deliberately non-closing adapter for caller streams;
- parse V2 collections, V2 environments, and JSON POJOs inside the owned source scope;
- read V3 definitions and request files with scoped `FileSystem` operations;
- close the complete gzip source chain; and
- preserve the parse/read failure as primary while attaching a close failure as suppressed.

The repository currently has no production call to `bufferV3Definition`; its retained value comes
from historical public surface rather than current internal leverage. By the deletion test, removing
it and the ambiguous `bufferFile` API makes the module deeper: callers request a value or parse
operation without coordinating a hidden resource lifecycle.

The change intentionally updates API dumps, documentation, and all repository call sites in one
pass. No deprecated aliases or compatibility adapter will be added.

`ClasspathResolver` separately owns its cached `NioFileSystem` instances for process lifetime.
Scoped path reads close every per-read `Source`; they do not close the cached ZipFS and poison later
lookups. V1 characterizes that repeated reads from the same JAR URI do not grow open handles per
read, but it does not add eviction or shutdown for the one registry entry per distinct JAR URI.

### Tests written before the fix

The red tests must demonstrate:

- a path resource is closed after a successful string read;
- a path resource is closed after its parser throws;
- V2 collection and environment sources close after success and malformed input;
- V3 root/child definitions, request YAML, and environment YAML close after success and malformed
  input;
- a gzip source chain closes after success and corrupt input;
- classpath/JAR and absolute-filesystem reads both close every per-read source;
- parse failure remains primary if close also fails;
- caller-provided input streams remain open after successful and failing string/parser operations;
- repeated reads from one cached JAR filesystem close each source without closing or multiplying the
  process-lifetime ZipFS entry.

A deterministic close-tracking filesystem/source double is the authoritative test mechanism. A
Unix-only JDK 21 regression probe additionally verifies that hundreds of absolute-path reads do not
cause open descriptor count to grow linearly without requesting GC. Unsupported platforms skip only
that runtime probe, not the deterministic ownership tests.

## CI and verification strategy

Ordinary CI proves structure and correctness, never timing:

- existing unit and integration tests;
- build-logic unit tests and Gradle TestKit coverage for the three performance tasks;
- capture schema, golden JSON/Markdown, compatibility, comparison, checksum, and atomic-publication
  tests;
- negative tests for nonzero forks, missing/extra/duplicate rows, empty/header-only results,
  malformed JSON, nonfinite values, log fallbacks, stale output, and teardown failure;
- an artifact-validator fixture whose duplicate class appears only through the selected JVM's
  multi-release version, proving runtime-version-aware collision detection;
- `performanceCanary` on JDK 21 with no numeric threshold;
- intentional ABI/API update verification;
- full `build`; and
- `qodanaScan` before push.

The build workflow invokes the boundaries explicitly rather than assuming root `build` discovers
them:

```bash
./gradlew -p buildSrc test
./gradlew build
./gradlew performanceCanary
```

The workflow uploads the sanitized canary directory with an unconditional `if: always()` step, so a
valid or `INVALID` diagnostic bundle survives task failure. Numeric scores from this artifact are
never compared or gated.

Controlled-host timing additionally requires:

- an operator-declared stable host ID;
- a clean committed tree;
- the checked-in cold or warm profile;
- the declared A1 → A2 → B session sequence and maximum duration;
- baseline and candidate captured on the same compatible host/runtime; and
- no external network dependency.

`StepReport.exeTimings` may be printed beside a run for diagnosis, but end-to-end JMH wall time minus
summed phase timings is not a gate. Current phase telemetry omits setup/load/report/event/logging work
and has known contract drift.

## Delivery workflow

The first implementation session follows this checkpoint sequence:

1. Build and test the classpath distribution and fail-closed canary.
2. Build and test strict capture/evidence handling.
3. Build and test the explicit comparator and Markdown renderer.
4. Add and characterize the minimal V3 scenario; run only structural canaries so far.
5. Run A/A calibration, finalize the checked-in profiles, and freeze the pre-fix baseline
   distribution plus a preliminary capture.
6. Add failing resource-ownership tests.
7. Implement the breaking ownership cleanup and make the tests pass.
8. Freeze the candidate distribution; in one controlled session run baseline A1, baseline A2, then
   candidate B; compare B explicitly with A2 and publish all evidence bundles.
9. Profile/rank the remaining audited hotspots and record the recommended next optimization.
10. Run all acceptance gates and stop. The next optimization requires a new design/plan or an
    approved continuation.

For every later hotspot, the repeatable loop is:

```text
characterize behavior -> capture baseline -> make one change -> capture candidate -> compare -> decide
```

Correctness changes remain test-driven. Performance claims remain measurement-driven. A plausible
hotspot found by code reading is a profiling hypothesis until compatible captures support it.

## Acceptance criteria

The tranche is complete only when:

1. The old fail-open reproduction is covered by a failing-then-passing build-logic test.
2. The authoritative distribution runs Graal and the real Log4j provider without flattening
   dependencies or duplicating classes, while direct `jmh`/`jmhJar` use fails with migration
   guidance.
3. A setup failure, empty result, malformed result, unexpected row, or known runtime fallback makes
   the outer task fail.
4. The V3 real-wire canary runs from the packaged distribution and proves all functional invariants.
5. Cold and warm captures are valid only for distributions built from clean commits by a clean
   capture runner and contain all required metadata, artifacts, sanitized logs, and checksums.
6. Explicit compatible, distinct baseline/candidate inputs produce deterministic JSON and Markdown
   comparison reports with gain and conditional fork-resampling uncertainty.
7. Incompatible captures are rejected before statistics are computed.
8. Published valid and invalid bundles contain no forbidden path/host data, while canonicalized JMH
   measurements retain exact semantic values and a hash of the temporary raw input.
9. Deterministic ownership tests fail on the starting implementation and pass after the breaking
   cleanup.
10. The real JDK 21 descriptor probe no longer shows linear descriptor growth across repeated path
   reads.
11. A valid A1/A2/B session documents the ownership change without claiming significance when the
    interval is inconclusive or calibration fails.
12. The remaining hotspots are ranked from current measurements, with no second production
    optimization bundled into this tranche.
13. Unit, integration, canary, ABI/API, build, and Qodana gates pass.

## Alternatives considered

### Repair the existing fat jar

Rejected. It requires maintaining multi-release manifest behavior, service-file merging, module
descriptor exclusions, signature handling, and future dependency-specific duplicate policy. The
classpath distribution achieves the same benchmark capability while deleting the shading problem.

### Use Shadow

Rejected for V1 because a single-file artifact is not required. It is safer than manual shading but
still makes packaging correctness depend on merge policy and plugin-version integration.

### Use V2 as the primary workload

Rejected for the primary end-to-end cell. V2 offers a smaller one-file fixture, but it omits the V3
directory/JAR, YAML, conversion, hashing, and resource-ownership work that public `revUp` performs
for V3 consumers. A minimal V2 companion may later be added as a differential loader diagnostic;
it is not required for the first performance claim.

### Use StepReport timings

Rejected as authoritative measurement. They are useful for localization but omit meaningful work,
truncate phase values in some projections, and are not consistently serialized.

### Store evidence in DuckDB

Deferred. V1 produces a small number of immutable bundles, and explicit paths are safer than an
implicit database query for baseline selection. DuckDB can query JSON later without becoming the
write-path authority.

### Build a general evidence platform

Rejected by the deletion test. V1 has one runner, one evidence schema, one comparator, and one
reporter. Provider interfaces, a database, remote publication, dashboards, and a matrix scheduler
would add surface without current leverage.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Loopback and JIT noise obscure small changes | Multiple independent forks, A/A calibration, per-fork reduction, uncertainty interval |
| Warm fixture retains requests | Fixed single-shot counts; trial-scoped server; no duration benchmark |
| V3 loader overhead dilutes a downstream optimization | Preserve component benchmarks for localization; add V2 differential only when needed |
| Benchmark source changes between commits | Protocol and workload hashes are exact compatibility keys |
| Dirty source or stale outputs contaminate a capture | Clean-tree preflight, new staging directory, atomic publication, checksums |
| JMH/log output exposes local paths | Schema-aware canonicalization and log sanitization before valid or invalid publication |
| CI runner variation produces false regressions | CI runs canaries only; timing stays on a declared controlled host |
| Host drift is mistaken for fork uncertainty | A1/A2/B same-session order, duration bound, explicit conditional-interval wording |
| API cleanup causes consumer breakage | Break is intentional; update API record/docs directly; no compatibility layer |
| FD-count probe is platform-specific or GC-sensitive | Deterministic close-tracking tests are authoritative; Unix FD probe is secondary |
| Closing a cached ZipFS breaks later classpath reads | Close per-read sources only; preserve and characterize the process-lifetime registry |
| Comparison math appears more certain than the samples allow | Minimum independent forks, raw-data retention, interval reporting, `INCONCLUSIVE` outcome |

## Relevant source locations

- `build.gradle.kts` — current JMH configuration and source-set wiring.
- `gradle/libs.versions.toml` — JMH, Graal, Log4j, and build dependency versions.
- `src/jmh/kotlin/com/salesforce/revoman/benchmark/SandboxBenchmark.kt` — existing sandbox probe.
- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — public lifecycle and V2/V3 dispatch.
- `src/main/kotlin/com/salesforce/revoman/input/FileUtils.kt` — ambiguous path-source ownership.
- `src/main/kotlin/com/salesforce/revoman/input/ClasspathResolver.kt` — resolved filesystem and
  process-lifetime ZipFS ownership.
- `src/main/kotlin/com/salesforce/revoman/input/json/JsonPojoUtils.kt` — path-source JSON parsing.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/Environment.kt` — environment
  loading.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3Loader.kt` — V3 walk,
  parsing, conversion input, and unclosed sources.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3EnvLoader.kt` — V3
  environment read.
- `src/main/kotlin/com/salesforce/revoman/internal/postman/template/v3/V3ToV2Converter.kt` — V3
  normalization boundary.
- `src/main/kotlin/com/salesforce/revoman/internal/exe/UnmarshallResponse.kt` — response content-type
  branch exercised by the real-wire fixture.
- `src/main/kotlin/com/salesforce/revoman/testing/http/MockHttpServer.kt` — loopback server and
  bounded request ledger.
- `.github/workflows/build.yml` — ordinary CI, which currently has no performance canary.
- `docs/superpowers/benchmarks/baseline.md` — current manual, unauditable benchmark workflow.

## Reproduction commands

Run these from the repository root at the starting SHA. They modify no source files; they create
normal `build/` output and a temporary diagnostic directory.

On the current machine, bare `java` resolves to a Salesforce guidance wrapper that can exit zero
without launching a JVM. The reproduction therefore names the real Corretto 21 installation.

### Invalid, fail-open JMH artifact

```bash
set -euo pipefail

EXPECTED_SHA=009bc8f4c1fe9fb7d393036616a3c3b6cd787aca
REVOMAN_JDK21=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/21.0.11-amzn
REAL_JAVA="$REVOMAN_JDK21/bin/java"
REAL_JAR="$REVOMAN_JDK21/bin/jar"
JMH_ARTIFACT=build/libs/revoman-0.90.0-jmh.jar
REPRO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/revoman-jmh-repro.XXXXXX")"

test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"
test -x "$REAL_JAVA"
git status --short
"$REAL_JAVA" -version

./gradlew jmhJar \
  -Dorg.gradle.java.home="$REVOMAN_JDK21" \
  --rerun-tasks \
  --no-build-cache \
  --no-daemon \
  --no-configuration-cache \
  --console=plain
test -f "$JMH_ARTIFACT"
ls -lh "$JMH_ARTIFACT"

set +e
./gradlew jmh \
  -Dorg.gradle.java.home="$REVOMAN_JDK21" \
  -Pjmh.includes=SandboxBenchmark \
  --rerun-tasks \
  --no-build-cache \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  >"$REPRO_DIR/gradle-jmh.log" 2>&1
GRADLE_STATUS=$?
set -e

echo "gradle_status=$GRADLE_STATUS"
test "$GRADLE_STATUS" -eq 0
rg -n 'BUILD SUCCESSFUL|Multi-Release classes are not configured correctly|NotInjectableException' \
  "$REPRO_DIR/gradle-jmh.log"

sed -n '1,20p' build/results/jmh/results.txt
RESULT_ROWS="$(
  awk 'NR > 1 && NF { count++ } END { print count + 0 }' \
    build/results/jmh/results.txt
)"
echo "jmh_result_rows=$RESULT_ROWS"
test "$RESULT_ROWS" -eq 0
```

Expected: the jar is approximately 92 MiB, Gradle exits zero and prints `BUILD SUCCESSFUL`, while
the JMH result contains only its header and `jmh_result_rows=0`.

Inspect and validate the jar:

```bash
unzip -p "$JMH_ARTIFACT" META-INF/MANIFEST.MF

if unzip -p "$JMH_ARTIFACT" META-INF/MANIFEST.MF |
  tr -d '\r' |
  rg -q '^Multi-Release: true$'
then
  echo "Unexpected: Multi-Release is present"
  exit 1
else
  echo "Confirmed: Multi-Release is absent"
fi

VERSIONED_MODULES="$(
  "$REAL_JAR" tf "$JMH_ARTIFACT" |
    rg '^META-INF/versions/[0-9]+/module-info\.class$' |
    wc -l |
    tr -d ' '
)"
echo "versioned_module_info_entries=$VERSIONED_MODULES"
test "$VERSIONED_MODULES" -eq 30

set +e
"$REAL_JAR" --validate --file "$JMH_ARTIFACT" \
  >"$REPRO_DIR/jar-validate.log" 2>&1
JAR_VALIDATE_STATUS=$?
set -e

echo "jar_validate_status=$JAR_VALIDATE_STATUS"
test "$JAR_VALIDATE_STATUS" -ne 0
wc -l "$REPRO_DIR/jar-validate.log"
sed -n '1,20p' "$REPRO_DIR/jar-validate.log"
tail -n 20 "$REPRO_DIR/jar-validate.log"
```

Expected: the manifest contains only `Manifest-Version` and JMH `Main-Class`, there are 30 versioned
`module-info.class` entries, and `jar --validate` exits nonzero with thousands of diagnostics.

Contrast JMH's default failure behavior with `-foe true`:

```bash
set +e
"$REAL_JAVA" -jar "$JMH_ARTIFACT" \
  '.*SandboxBenchmark.*' \
  -f 1 -wi 0 -i 1 -r 10ms \
  -rf json \
  -rff "$REPRO_DIR/fail-open.json" \
  -o "$REPRO_DIR/fail-open.txt" \
  >"$REPRO_DIR/fail-open.console" 2>&1
FAIL_OPEN_STATUS=$?
set -e

echo "fail_open_status=$FAIL_OPEN_STATUS"
test "$FAIL_OPEN_STATUS" -eq 0
test "$(tr -d '[:space:]' <"$REPRO_DIR/fail-open.json")" = '[]'
rg -n 'NotInjectableException|SimpleLogger|Multi-Release classes are not configured correctly|<failure>' \
  "$REPRO_DIR/fail-open.console" \
  "$REPRO_DIR/fail-open.txt"

set +e
"$REAL_JAVA" -jar "$JMH_ARTIFACT" \
  '.*SandboxBenchmark.*' \
  -f 1 -wi 0 -i 1 -r 10ms \
  -foe true \
  -rf json \
  -rff "$REPRO_DIR/fail-closed.json" \
  -o "$REPRO_DIR/fail-closed.txt" \
  >"$REPRO_DIR/fail-closed.console" 2>&1
FAIL_CLOSED_STATUS=$?
set -e

echo "fail_closed_status=$FAIL_CLOSED_STATUS"
test "$FAIL_CLOSED_STATUS" -ne 0
test ! -s "$REPRO_DIR/fail-closed.json"
rg -n 'Benchmark caught the exception|Multi-Release classes are not configured correctly|NotInjectableException|<failure>' \
  "$REPRO_DIR/fail-closed.console" \
  "$REPRO_DIR/fail-closed.txt"
```

Expected: the default run exits zero with JSON `[]`; the fail-on-error run exits nonzero and leaves
the JSON result absent or empty. This is why both fail-on-error and explicit row validation are
required.

### File-descriptor leak

The descriptor probe uses the dependency-complete current JMH jar only as a classpath. It does not
execute Graal, so the jar's known manifest defect does not affect this probe.

```bash
"$REVOMAN_JDK21/bin/jshell" \
  --class-path "$JMH_ARTIFACT" <<'EOF'
import com.salesforce.revoman.input.FileUtils;
import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

var os = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
var file = Path.of("README.adoc").toAbsolutePath().toString();

var before = os.getOpenFileDescriptorCount();
for (int i = 0; i < 200; i++) FileUtils.readFileToString(file);
var afterReads = os.getOpenFileDescriptorCount();

System.gc();
Thread.sleep(1000);
var afterGc = os.getOpenFileDescriptorCount();

System.out.printf(
    "before=%d after_200_reads=%d after_gc=%d delta=%d%n",
    before, afterReads, afterGc, afterReads - before);
/exit
EOF
```

Verified on macOS/Apple Silicon with Corretto 21.0.11:

```text
before=10 after_200_reads=210 after_gc=10 delta=200
```

Absolute counts can vary. The defining observation is approximately 200 additional open
descriptors after 200 reads without GC, followed by recovery only after GC-driven cleanup. The
probe requires a Unix-like JDK exposing `UnixOperatingSystemMXBean`; deterministic ownership tests
remain authoritative on every platform.
