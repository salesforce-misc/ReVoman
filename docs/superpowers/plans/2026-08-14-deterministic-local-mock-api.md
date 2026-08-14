# Deterministic Local Mock API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all automated restful-api.dev traffic and every request in the mixed Pokemon
sandbox test with one deterministic http4k real-wire fixture, then complete local gates, Qodana,
Antora, landing, exact-SHA CI verification, and recoverability-gated worktree cleanup.

**Architecture:** An integration-test-only Kotlin `AutoCloseable` owns a JDK `HttpServer` bound to
`127.0.0.1:0`, a named executor, a stateful object store, and an http4k `HttpHandler` mounted through
the public `HttpExchangeHandler`. Checked-in Postman environments use fail-closed loopback defaults;
tests overlay the selected fixture URL and continue through ReVoman's production HTTP client.

**Tech Stack:** JDK 21 `HttpServer`, http4k 6.57.1.0 core routing/adapter APIs, Kotlin, Java,
Moshi, Gradle 9.7 JVM Test Suites, JUnit 5, Truth, Detekt, Spotless, Qodana, Antora, GitHub Actions.

## Global Constraints

- Work only in `.worktrees/performance-cs2a-lifecycle` until the landing task explicitly changes
  directory to the main checkout.
- Use JDK `/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn` for every local Gradle
  gate.
- Preserve real IPv4 loopback traffic on a kernel-selected port; never invoke the handler directly
  as a substitute for an integration request.
- Use http4k `routes`/handlers mounted through public `HttpExchangeHandler`; do not use stock
  `SunHttp` and do not add a dependency.
- Keep production source, public/frozen ABI, benchmark-driver code, benchmark identities, Task 8
  evidence, and historical specs/reports unchanged unless a failing required gate proves a
  correction is necessary.
- Keep existing test packages, class names, and resource-directory names because current docs link
  to them.
- Active environment defaults for migrated routes are exactly `http://127.0.0.1:1`; missing test
  overlays must fail locally without contacting a public API.
- `PokemonSandboxApiTest` is wholly local; other Pokemon integration tests remain outside this
  correction.
- Follow strict RED/GREEN/refactor. Record the production change each test catches and observe the
  expected failure before implementation.
- Do not weaken, skip, or reorder the Task 8 Step 3 gate. An interrupted command invalidates the
  whole serial block.
- Do not claim or run the privileged CS2a controlled benchmark; retain its UID-policy and
  disposable-Linux blockers in the report.
- Never force-push. Preserve unrelated untracked files in the main checkout.
- Remove worktrees only after the exact landed SHA is on `origin/master`, required CI is green, and
  every retained byte is committed or intentionally preserved.

---

### Task 1: Build the deterministic http4k real-wire fixture

**Files:**

- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt`
- Create: `src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServerTest.kt`

**Interfaces:**

- Consumes: http4k `HttpHandler`, `Request`, `Response`, `routes`, and public
  `HttpExchangeHandler`; JDK `HttpServer`; ReVoman `prepareHttpClient` for real-wire verification.
- Produces: Java-friendly `DeterministicMockApiServer.start()`, `baseUrl`,
  `requestSignatures()`, `hitCount(path)`, and idempotent `close()`.

- [ ] **Step 1: Write the first failing lifecycle test**

Create `DeterministicMockApiServerTest.kt` with a test that starts two fixtures, requires exact
`127.0.0.1` addresses and distinct nonzero ports, sends a real request with `prepareHttpClient`,
closes one fixture, then requires connection refusal and absence of its named non-daemon worker.
The expected public API is:

```kotlin
DeterministicMockApiServer.start().use { fixture ->
  assertThat(fixture.baseUrl).startsWith("http://127.0.0.1:")
  val response = prepareHttpClient(insecureHttp = false)(Request(GET, "${fixture.baseUrl}/objects"))
  assertThat(response.status).isEqualTo(OK)
  assertThat(fixture.requestSignatures()).containsExactly("GET /objects")
}
```

The production break named by this test is replacing the loopback/ephemeral real server with a
wildcard bind, fixed port, direct handler, or incomplete shutdown.

- [ ] **Step 2: Run the focused test and capture RED**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Require failure because `DeterministicMockApiServer` or its first route is absent. A typo or
unrelated compilation failure is not accepted as RED.

- [ ] **Step 3: Implement loopback ownership and request recording**

Create a public Kotlin class with this shape:

```kotlin
class DeterministicMockApiServer private constructor(
  private val server: HttpServer,
  private val executor: ExecutorService,
  private val requests: ConcurrentLinkedQueue<RecordedApiRequest>,
) : AutoCloseable {
  private val closed = AtomicBoolean()

  val baseUrl: String
    get() = "http://127.0.0.1:${server.address.port}"

  fun requestSignatures(): List<String> =
    requests.map { request ->
      buildString {
        append(request.method)
        append(' ')
        append(request.path)
        if (request.query != null) append('?').append(request.query)
      }
    }

  fun hitCount(path: String): Int = requests.count { it.path == path }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    server.stop(0)
    executor.shutdown()
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow()
      check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
        "deterministic mock API worker did not stop"
      }
    }
  }

  companion object {
    @JvmStatic fun start(): DeterministicMockApiServer = startFixture()
  }
}
```

`startFixture()` must bind `InetSocketAddress("127.0.0.1", 0)`, install one root context using
`HttpExchangeHandler`, assign one uniquely named non-daemon single-thread executor, start the
server, and fail-closed cleanup on startup failure. The adapter materializes request bytes once,
records method/path/raw query/body, and forwards a `Body(ByteBuffer.wrap(bytes))` replay.

- [ ] **Step 4: Add failing object-route tests one behavior at a time**

Add real-client tests with hand-derived literal expectations for:

```text
GET /objects                           -> 200 []
POST /objects                          -> 200 {"id":"local-object-1",...}
PATCH /objects/local-object-1          -> 200, new name and preserved data
GET /objects/local-object-1            -> 200, exact stored object
PUT /objects/local-object-1            -> 200, replacement data
PATCH /objects/missing                 -> 404
GET /objects/missing                   -> 404
POST /objects with malformed JSON      -> 400
```

Run the focused selector after each new test and require a route/status/state-specific RED.

- [ ] **Step 5: Implement the minimal stateful object routes**

Build an http4k handler from exact method/path branches. Use Moshi's `Any` or parameterized map
adapter with `JsonAdapter.lenient()` to accept the existing Postman bodies' JavaScript-style line
comments, then copy the result into `Map<String, Any?>`; do not interpolate response JSON by hand.
Allocate IDs with a fixture-local `AtomicInteger` beginning at one. PATCH merges supplied `name`
and `data` while retaining omitted fields; PUT replaces supplied fields. Missing IDs return:

```json
{"error":"object not found"}
```

- [ ] **Step 6: Add RED/GREEN Pokemon routes**

Add tests and implement exact fixed responses:

```json
{"results":[{"name":"bulbasaur"},{"name":"ivysaur"},{"name":"venusaur"},{"name":"charmander"},{"name":"charmeleon"}]}
```

`GET /pokemon/bulbasaur` returns `{"id":1,"name":"bulbasaur"}` and
`GET /pokemon-species/bulbasaur` returns `{"id":1,"name":"bulbasaur"}`. Unsupported paths or
methods return `404`.

- [ ] **Step 7: Run fixture GREEN and static checks**

Run the focused selector, `spotlessCheck`, `git diff --check`, and IDE diagnostics for both new
Kotlin files. Require all fixture tests to pass and zero IDE errors.

- [ ] **Step 8: Commit the fixture task**

```bash
git add \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServerTest.kt
git diff --cached --check
git commit -m "test: add deterministic integration mock APIs"
```

---

### Task 2: Migrate the four direct V2 and V3 collection tests

**Files:**

- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java`
- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevKtTest.kt`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevV3Test.java`
- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/RestfulAPIDevKtTest.kt`
- Modify: `src/integrationTest/resources/pm-templates/v2/restfulapidev/restful-api.dev.postman_collection.json`
- Modify: `src/integrationTest/resources/pm-templates/v2/restfulapidev/restful-api.dev.postman_environment.json`
- Modify: `src/integrationTest/resources/pm-templates/v3/restful-api.dev/all-objects.request.yaml`
- Modify: `src/integrationTest/resources/pm-templates/v3/restful-api.dev/add-object.request.yaml`
- Modify: `src/integrationTest/resources/pm-templates/v3/restful-api.dev/update-object.request.yaml`
- Modify: `src/integrationTest/resources/pm-templates/v3/restful-api.dev/get-object-by-id.request.yaml`
- Modify: `src/integrationTest/resources/pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml`

**Interfaces:**

- Consumes: Task 1 `DeterministicMockApiServer`.
- Produces: four network-independent V2/V3 language-surface integration tests and fail-closed active
  resources.

- [ ] **Step 1: Add exact protocol assertions before resource rewiring**

Wrap each Kotlin test in:

```kotlin
DeterministicMockApiServer.start().use { api ->
  val rundown =
    ReVoman.revUp(
      Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .dynamicEnvironment("baseUrl", api.baseUrl)
        .off()
    )
  assertThat(rundown.firstUnsuccessfulStepReport).isNull()
  assertThat(rundown.stepReports).hasSize(4)
  assertThat(api.requestSignatures())
    .containsExactly(
      "GET /objects",
      "POST /objects",
      "PATCH /objects/local-object-1",
      "GET /objects/local-object-1",
    )
    .inOrder()
}
```

Use Java try-with-resources and `api.getBaseUrl()`/`api.requestSignatures()` for Java. Retain the
existing Java Antora tag around a complete, compilable example including the fixture lifecycle.

- [ ] **Step 2: Capture migration RED**

Run the exact four-class selector. Require failure because the current resources still construct
public `https://{{uri}}` URLs and therefore do not reach the local request ledger.

- [ ] **Step 3: Rewire active resources minimally**

Replace every standalone request prefix `https://{{uri}}` with `{{baseUrl}}`. Replace the V2
environment's `uri` entry and the V3 environment's `uri` entry with one enabled `baseUrl` entry
whose exact value is `http://127.0.0.1:1`. Preserve collection names, item order, scripts, bodies,
and V3 file paths.

- [ ] **Step 4: Run the four classes GREEN**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Require four passing tests, sixteen recorded loopback requests in isolated fixtures, and no retry.

- [ ] **Step 5: Commit the direct-test migration**

Stage only the eleven files listed in this task, run `git diff --cached --check`, inspect the staged
resource substitutions, and commit:

```bash
git commit -m "test: localize restful API collection runs"
```

---

### Task 3: Migrate the V3 ledger cold/warm proof

**Files:**

- Modify: `src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/LedgerRoundTripKtTest.kt`

**Interfaces:**

- Consumes: Task 1 fixture and Task 2 V3 `baseUrl` resource contract.
- Produces: deterministic four-request cold learning and seven-total-request cold/warm skip proof.

- [ ] **Step 1: Rewrite test setup around one fixture per test**

Change `revUp` to accept `baseUrl` and overlay it:

```kotlin
private fun revUp(baseUrl: String, ledger: LedgerSnapshot? = null): Rundown {
  var builder =
    Kick.configure()
      .templatePath(PM_COLLECTION_PATH)
      .environmentPath(PM_ENVIRONMENT_PATH)
      .dynamicEnvironment("baseUrl", baseUrl)
  if (ledger != null) builder = builder.ledger(ledger)
  return ReVoman.revUp(builder.off())
}
```

The cold test asserts the exact four-request sequence. The warm test starts one fixture, performs
cold then warm, and asserts the seven signatures end in:

```text
GET /objects
PATCH /objects/ledgered-obj-id
GET /objects/null
```

The warm sequence must contain no second `POST /objects`. The unchanged V3 `afterResponse`
behavior overwrites `objId` with null after the PATCH `404` response has no `id`, so the actual
final request is `GET /objects/null`.

- [ ] **Step 2: Capture RED then GREEN**

First add the exact request assertions while calling the old `revUp()` and observe the empty local
ledger RED. Then pass the fixture URL, update comments from public API to deterministic real-wire
API, and require both tests GREEN while preserving source-hash, produced-key, skipped-request,
injected-value, and re-emitted-ledger assertions.

- [ ] **Step 3: Commit the ledger migration**

Run the focused class, Spotless, diff-check, and IDE diagnostics; then:

```bash
git add src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev/v3/LedgerRoundTripKtTest.kt
git commit -m "test: localize ledger round-trip integration"
```

---

### Task 4: Make the mixed Pokemon sandbox integration wholly local

**Files:**

- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java`
- Modify: `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json`
- Modify: `src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json`

**Interfaces:**

- Consumes: Task 1 Pokemon and object routes.
- Produces: one five-step script/scope integration test with zero public HTTP dependency.

- [ ] **Step 1: Add local protocol assertions and capture RED**

Wrap the run in Java try-with-resources, overlay both `pokemonApiBaseUrl` and `objectApiBaseUrl`,
retain all existing Rundown assertions, and require:

```java
assertThat(api.requestSignatures())
    .containsExactly(
        "GET /pokemon?limit=5",
        "GET /pokemon/bulbasaur",
        "GET /pokemon-species/bulbasaur",
        "POST /objects",
        "PUT /objects/local-object-1")
    .inOrder();
```

Also assert `pokemonName == "bulbasaur"` and `pokemonId == 1` through the existing Rundown scopes.
Run the class before resource rewiring and require failure at the local protocol assertion.

- [ ] **Step 2: Rewire the mixed collection**

Replace the first three `{{baseUrl}}` prefixes with `{{pokemonApiBaseUrl}}`. Replace the two
`https://{{uri}}` prefixes with `{{objectApiBaseUrl}}`. Replace the environment's `baseUrl` and
`uri` entries with enabled `pokemonApiBaseUrl` and `objectApiBaseUrl` entries, both exactly
`http://127.0.0.1:1`; keep `limit=5` unchanged.

- [ ] **Step 3: Run focused GREEN and commit**

Run the class and fixture tests together, require all six HTTP/script behaviors green, then commit
the exact three-file migration:

```bash
git add \
  src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java \
  src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_collection.json \
  src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api/pokemon-sandbox-api.postman_environment.json
git commit -m "test: localize Pokemon sandbox API integration"
```

---

### Task 5: Close mutation, documentation, and report contracts

**Files:**

- Modify: `build.gradle.kts`
- Modify: `DEVELOPMENT.md`
- Modify: `README.adoc`
- Modify: `docs/modules/ROOT/pages/getting-started.adoc`
- Modify: `docs/modules/ROOT/pages/index.adoc`
- Create: `docs/superpowers/reports/2026-08-14-deterministic-local-mock-api-landing.md`
- Modify if named by Detekt: `detekt/baseline-source-sha256sums.txt`

**Interfaces:**

- Consumes: Tasks 1-4 final behavior and exact test evidence.
- Produces: truthful current docs, executed mutation evidence, and the landing report consumed by
  final reviews.

- [ ] **Step 1: Execute the required mutation matrix**

Apply one mutation at a time with `apply_patch`, run the narrowest owning selector, observe a
behavior-specific failure, and restore before the next mutation. Cover:

```text
delete object route family
delete Pokemon route family
POST returns wrong status
PATCH/PUT returns wrong body
created object is not stored
PATCH drops existing data
remove baseUrl overlay (must fail against 127.0.0.1:1)
bind wildcard
bind fixed port
stop server before returning fixture
omit request ledger insertion
omit executor shutdown/await
```

Do not accept source-token tests as mutation evidence. Record command, selector count, and observed
failure for every mutant in the report.

- [ ] **Step 2: Update current documentation truthfully**

Change build/DEVELOPMENT retry comments to list only remaining live services. Change README and
Antora prose from a public restful-api.dev automated run to a deterministic local http4k-backed
real-wire run. In `index.adoc`, correct the four-step order to GET/POST/PATCH/GET and remove public
host claims while preserving the illustrative `/objects` readout. Keep historical specs and the
archived Postman CLI report untouched.

- [ ] **Step 3: Prove active resources are fail-closed**

Run:

```bash
rg -n 'api\.restful-api\.dev|https://pokeapi\.co' \
  src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev \
  src/integrationTest/kotlin/com/salesforce/revoman/integration/restfulapidev \
  src/integrationTest/java/com/salesforce/revoman/integration/pokemon/PokemonSandboxApiTest.java \
  src/integrationTest/resources/pm-templates/v2/restfulapidev \
  src/integrationTest/resources/pm-templates/v3/restful-api.dev \
  src/integrationTest/resources/pm-templates/v2/pokemon-sandbox-api
```

Require no match outside the explicitly excluded archived
`postman-cli-reports/restful-api.dev-2025-04-14-13-16-43.json`.

- [ ] **Step 4: Refresh Detekt fingerprints only if required**

Run `./gradlew detekt --rerun-tasks`. If Detekt baseline source integrity names an edited file,
replace only that file's SHA-256 row with `sha256sum <path>` and run
`DetektBaselineIntegrityTest`. Do not change `detekt/baseline.xml`, suppress rules, or regenerate a
blanket baseline.

- [ ] **Step 5: Write the evidence report and commit**

The report initially records only evidence observed through Tasks 1-5: design/base SHA, current
commit range, route/resource inventory, every RED and GREEN selector, mutation table, focused test
counts, unchanged ABI/benchmark boundaries, and the explicit non-claim for privileged measurement.
Later tasks append gate, review, landing, CI, and cleanup evidence only after it exists. Commit the
current docs/report after Spotless and diff-check:

```bash
git add build.gradle.kts DEVELOPMENT.md README.adoc docs/modules/ROOT/pages/getting-started.adoc \
  docs/modules/ROOT/pages/index.adoc \
  docs/superpowers/reports/2026-08-14-deterministic-local-mock-api-landing.md \
  detekt/baseline-source-sha256sums.txt
git diff --cached --check
git commit -m "docs: record deterministic integration API migration"
```

Omit `detekt/baseline-source-sha256sums.txt` from `git add` when it is unchanged.

---

### Task 6: Review and run the indivisible final local gate

**Files:**

- Review: `docs/superpowers/specs/2026-08-14-deterministic-local-mock-api-design.md`
- Review: `docs/superpowers/plans/2026-08-14-deterministic-local-mock-api.md`
- Review: complete committed range `478529ad..HEAD`

**Interfaces:**

- Consumes: committed Tasks 1-5.
- Produces: one clean, reviewed, fully gated implementation SHA.

- [ ] **Step 1: Obtain independent pre-gate reviews**

Dispatch read-only Standards, Spec, and security/evidence reviews over `478529ad..HEAD`. Resolve
every Critical or Important finding with focused RED/GREEN evidence and a new correction commit,
then repeat all three reviews on the new exact HEAD.

- [ ] **Step 2: Run the focused local API selector**

Run fixture plus the six owning classes:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

- [ ] **Step 3: Run the exact final gate in one Bash process**

Run Appendix A verbatim. It begins from a clean committed HEAD, retains
`GATED_IMPLEMENTATION_SHA` in the shell, and ends only after Qodana, Antora, unchanged-SHA, and
clean-tree assertions. Before entering the block, require `colima status` or successfully run
`colima start`, and export the pinned JDK 21 `JAVA_HOME` from Global Constraints.

- [ ] **Step 4: Run the exact operator gate in the same shell**

With the Step 3 `GATED_IMPLEMENTATION_SHA` still exported, run Appendix B verbatim. Require
unchanged SHA and clean status before writing `build/cs2a-implementation-sha` and pushing the
recovery branch.

- [ ] **Step 5: Handle Qodana/Antora linked-worktree incompatibility without weakening**

If either tool rejects the linked `.git` file, create a disposable ordinary clone, fetch the exact
reviewed SHA, detach it, verify `git rev-parse HEAD` equals `GATED_IMPLEMENTATION_SHA`, run the exact
failed command there, and record the clone path/result. Do not substitute a different commit.

- [ ] **Step 6: Update and commit only observed final report evidence**

Replace the report's pre-gate observations with literal command results, counts, durations, review
verdicts, Qodana finding count, Antora result, and reviewed SHA. Commit only the report, rerun Antora
on the new report commit, repeat the fixed-range reviews for that doc-only delta, and rerun any gate
whose input bytes include the report.

---

### Task 7: Land, push, verify CI, and remove completed worktrees

**Files:**

- No source edits expected; any correction returns to Task 6 and produces a reviewed commit.

**Interfaces:**

- Consumes: clean reviewed/gated feature HEAD and green local Qodana/Antora.
- Produces: exact landed SHA on `origin/master`, green Build/Qodana/Docs CI, and a pruned worktree
  inventory with unrelated main-checkout files preserved.

- [ ] **Step 1: Establish remote recoverability**

Push the named feature branch without force and verify the remote OID:

```bash
git push -u origin codex/performance-cs2a-lifecycle
test "$(git ls-remote origin refs/heads/codex/performance-cs2a-lifecycle | awk '{print $1}')" = \
  "$(git rev-parse HEAD)"
```

- [ ] **Step 2: Fast-forward local master safely**

From `/Users/gopala.akshintala/code-clones/work/revoman-root`, preserve the existing untracked
`.ai/`, `.superpowers/`, and `docs/revoman-graphalow-licensing-brief.md`. Fetch origin, require local
`master` equals `origin/master`, then:

```bash
git merge --ff-only codex/performance-cs2a-lifecycle
```

If remote master moved, stop the landing, integrate it on the feature branch without force, repeat
Task 6 reviews/gates, and only then retry the fast-forward.

- [ ] **Step 3: Verify the merged result before push**

On local master run the full root `build`/JMH/installDist/Spotless command, the focused deterministic
API selector, the exact operator gate, Qodana, and Antora against the merged SHA. Require no tracked
or staged diff; preserve the known unrelated untracked paths.

- [ ] **Step 4: Push master and prove exact remote OID**

```bash
LANDED_SHA=$(git rev-parse HEAD)
readonly LANDED_SHA
git push origin master
test "$(git ls-remote origin refs/heads/master | awk '{print $1}')" = "$LANDED_SHA"
```

- [ ] **Step 5: Wait for exact-SHA GitHub CI**

Use `gh run list --commit "$LANDED_SHA" --json databaseId,workflowName,status,conclusion,url,headSha`
until the exact SHA has runs for `Build and Scan`, `Qodana`, and `Publish Docs to GitHub Pages`.
Run `gh run watch <databaseId> --exit-status` for each. If one fails, inspect its logs with
`gh run view <databaseId> --log-failed`, make a correction on the feature branch, repeat Task 6,
fast-forward master, push, and verify the new exact SHA.

- [ ] **Step 6: Audit every cleanup target**

From the main checkout record `git worktree list --porcelain`. For each registered path under
`.worktrees/performance-cs2a-lifecycle/build/`, verify it is detached, its HEAD is an ancestor of
the landed SHA, and any dirty path is limited to generated `detekt/baseline.xml`. Record the diff
before discarding. Verify the parent feature worktree is clean and its HEAD equals `LANDED_SHA`.

For ordinary Qodana clones under `.worktrees/qodana-*`, verify their HEAD is an ancestor of or equal
to `LANDED_SHA`; allow only generated `.idea/kotlinc.xml` dirt. Preserve any clone with another
change and report it instead of deleting it.

- [ ] **Step 7: Remove only verified completed worktrees**

Remove registered nested build worktrees first with `git worktree remove`; use `--force` only for a
path whose sole recorded dirt is generated `detekt/baseline.xml`. Then remove the clean parent
feature worktree from the main checkout and run `git worktree prune`. Move verified disposable
ordinary Qodana clone directories to explicit uniquely named paths under
`/Users/gopala.akshintala/.Trash/` so recovery remains possible.

- [ ] **Step 8: Commit literal landing and cleanup evidence on master**

From the main checkout, append only the observed `LANDED_SHA`, three CI run URLs/results, remote
recovery refs, removed worktree paths, trashed clone paths, and preserved unrelated paths to
`docs/superpowers/reports/2026-08-14-deterministic-local-mock-api-landing.md`. Run Spotless,
Antora, diff-check, and a read-only doc/spec review, then commit:

```bash
git add docs/superpowers/reports/2026-08-14-deterministic-local-mock-api-landing.md
git diff --cached --check
git commit -m "docs: finalize deterministic API landing evidence"
```

Push `master` again and wait for Build, Qodana, and Docs on this new exact documentation SHA. The
earlier green code SHA authorizes cleanup; the final doc-only SHA becomes the repository's terminal
landing SHA.

- [ ] **Step 9: Delete the merged local feature branch normally and verify final state**

```bash
git branch -d codex/performance-cs2a-lifecycle
git worktree list
git status --short
test "$(git rev-parse master)" = "$(git ls-remote origin refs/heads/master | awk '{print $1}')"
```

Report the landed SHA, CI URLs, retained main-checkout untracked files, every removed worktree path,
every trashed clone path, and the remote recovery refs.

---

## Appendix A: Indivisible final local gate

Run this exact block from the feature worktree in one Bash process:

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

GATED_IMPLEMENTATION_SHA=$(git rev-parse HEAD)
readonly GATED_IMPLEMENTATION_SHA
[[ "$GATED_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
test -z "$(git status --porcelain)"

./gradlew :benchmark-driver:installDist \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew \
  -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts \
  writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.targetId=current-cs2a \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew checkKotlinAbi apiCompatibilityTestClasses \
  :test :integrationTest :benchmark-driver:test \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

SELFTEST_ROOT=$(mktemp -d "$PWD/build/cs2a-selftest.XXXXXXXX")
git worktree add --detach "$SELFTEST_ROOT/baseline" \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
test -z "$(git -C "$SELFTEST_ROOT/baseline" status --porcelain)"
test "$(git -C "$SELFTEST_ROOT/baseline" rev-parse HEAD)" = \
  83f3cd70f78ad733412d10cbc8287aaabafe7aac
"$SELFTEST_ROOT/baseline/gradlew" -p "$SELFTEST_ROOT/baseline" \
  -I "$PWD/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts" \
  clean writeBenchmarkTargetManifest \
  -Pbenchmark.targetManifest="$PWD/build/benchmark-target-baseline-selftest.json" \
  -Pbenchmark.targetId=baseline-selftest-83f3cd70 --no-daemon --console=plain

./gradlew :benchmark-driver:integrationTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:integrationTest \
  --tests '*RunnerIntegrationTest.real retained worker reports major lifecycle weak references*' \
  --tests '*BenchmarkDriverIntegrationTest.major lifecycle retained campaign preserves v2 series identity*' \
  -Pbenchmark.targetManifest=build/benchmark-target-current.json \
  -Pbenchmark.adapter=major-v1 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew :benchmark-driver:benchmarkHarnessSelfTest \
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json \
  -Pbenchmark.adapter=baseline-83f3cd70 \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew build :benchmark-driver:jmhClasses :benchmark-driver:installDist \
  spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin \
  :benchmark-driver:classes --no-configuration-cache --console=plain
./gradlew qodanaScan --no-configuration-cache --console=plain
npx antora antora-playbook.yml

test "$(git rev-parse HEAD)" = "$GATED_IMPLEMENTATION_SHA"
test -z "$(git status --porcelain)"
```

## Appendix B: Exact operator and recovery-branch gate

Run immediately after Appendix A in the same shell:

```bash
: "${GATED_IMPLEMENTATION_SHA:?run Appendix A first in this same shell}"
[[ "$GATED_IMPLEMENTATION_SHA" =~ ^[0-9a-f]{40}$ ]]
test "$(git rev-parse HEAD)" = "$GATED_IMPLEMENTATION_SHA"
test -z "$(git status --porcelain)"

for script in \
  docs/superpowers/benchmarks/operators/cs2a-controlled-run.sh \
  docs/superpowers/benchmarks/operators/cs2a-governor-supervisor.sh \
  docs/superpowers/benchmarks/operators/cs2a-operator.sh; do
  /bin/bash -n "$script"
  shellcheck "$script"
done
test -x docs/superpowers/benchmarks/operators/cs2a-validate-manifest.jq
./gradlew :test \
  --tests '*Cs2aManifestValidatorTest*' \
  --tests '*Cs2aOperatorScriptTest*' \
  --tests '*Cs2aSupervisorAtomicHandoffTest*' \
  --tests '*DetektBaselineIntegrityTest*' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain

test "$(git rev-parse HEAD)" = "$GATED_IMPLEMENTATION_SHA"
test -z "$(git status --porcelain)"
export CS2A_IMPLEMENTATION_SHA=$GATED_IMPLEMENTATION_SHA
readonly CS2A_IMPLEMENTATION_SHA
printf '%s\n' "$CS2A_IMPLEMENTATION_SHA" >"$PWD/build/cs2a-implementation-sha"
git push origin HEAD:refs/heads/codex/performance-cs2a-lifecycle
```
