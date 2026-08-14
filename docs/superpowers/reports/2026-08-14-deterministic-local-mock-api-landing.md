# Deterministic local mock API landing record

## Scope and evidence boundary

This record includes the observed Task 6 pre-gate correction wave from reviewed base
`9295162dc1f2f37113d141346f39cb8d2a9ee2ab`. The approved design is
`0353e76b9ede244b150489d1f173aa4b33748e66`, based on
`478529ad02030de7beb9dd98aa032e3c5ea2aa4b`. Tasks 1–4 were committed through
`bedbf5f7435d383bd0c58d052bdc6fd4fb5ee908`; Task 5 adds the documentation,
mutation, resource-scan, and report evidence recorded here. The Task 6 correction commit adds the
review fixes and evidence below.

The work remains limited to integration-test fixtures and resources plus current documentation.
It does not change production source, dependencies, public ABI, benchmark-driver code, or benchmark
identity. No privileged CS2a controlled measurement was run or claimed: its administrator-owned UID
policy and disposable Linux/root launch harness remain blockers. The final Task 6 Standards, Spec,
and security/evidence re-reviews and every post-commit gate remain pending, including the final
focused selector, Appendix A/B, Qodana, Antora, landing, push, CI, and cleanup evidence.

## Current local contract

`DeterministicMockApiServer` binds a real `127.0.0.1:0` socket, authenticates the selected
`server.address.address` as exact IPv4 `127.0.0.1`, derives `baseUrl` from that bound address,
exposes an http4k-backed root handler, records replayable real-wire requests, allocates fixture-local
object IDs, and closes its named non-daemon executor. Its active deterministic routes are:

- `GET`, `POST /objects`; `GET`, `PATCH`, `PUT /objects/{id}`; the list route returns a stable
  ID-sorted snapshot of the current store;
- `GET /pokemon?limit=5`, `GET /pokemon/bulbasaur`, and
  `GET /pokemon-species/bulbasaur`; the Pokemon index accepts exactly one decoded `limit=5` pair.

The active V2/V3 object resources use `baseUrl` with a checked-in `http://127.0.0.1:1` fail-closed
default. The Pokemon resource uses `pokemonApiBaseUrl` and `objectApiBaseUrl` with the same default.
The active resource inventory is the V2 object collection/environment, four V3 object request files
and environment, and the Pokemon collection/environment. The historical Postman CLI report remains
unchanged and excluded from the no-public-host check.

Task 1's fixture selector passed 11 tests. Task 2's four direct Java/Kotlin V2/V3 selectors passed
4 tests and recorded 16 loopback requests. Task 3's ledger selector passed 2 tests, including the
actual seven-request cold/warm sequence ending `GET /objects`,
`PATCH /objects/ledgered-obj-id`, `GET /objects/null`; the PATCH 404 leaves no `id`, so the unchanged
V3 `afterResponse` behavior overwrites `objId` with null. Task 4's Pokemon plus fixture selector
passed 12 tests and proved its five-request local sequence.

## Task 6 pre-gate review corrections

The pre-gate review reported five unique Important findings: actual-bound-address proof, stateful
object listing, exact Pokemon query validation, four omitted Rundown URI assertions, and incomplete
remaining-live-service wording. All focused Gradle invocations used the pinned JDK and this exact
project-qualified command shape:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :integrationTest <listed --tests selectors> \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

| Finding | Focused RED / gap evidence | Focused GREEN / correction evidence |
|---|---|---|
| Actual bound address | Before correction, changing only the bind expression to `InetSocketAddress(0)` left `DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker` GREEN: 1 selected test passed, proving the gap. After actual-address derivation and validation, the same one-line mutant made that 1 selected test RED with `IllegalStateException: deterministic mock API must bind exact IPv4 loopback, got /0:0:0:0:0:0:0:0`; `BUILD FAILED`. | Restored only the bind expression with `apply_patch`; `rg` showed `HttpServer.create(InetSocketAddress(LOOPBACK_ADDRESS, 0), 0)` and no `InetSocketAddress(0)` bind. The same lifecycle selector passed 1 test; `BUILD SUCCESSFUL`. |
| Current object store | `DeterministicMockApiServerTest.list objects returns an id-sorted snapshot after create and update`; 1 selected real-wire test; expected the two created objects but got `[]`; `BUILD FAILED`. A separate state-preserving mutant removed only the sort and made the same test RED with IDs returned as `local-object-2`, `local-object-1`; `BUILD FAILED`. | The same selector passed 1 test after `GET /objects` returned the sorted current values; it checks the exact list after two creates and again after PATCH update. The sort-only mutant was restored with `apply_patch`; `BUILD SUCCESSFUL`. |
| Exact Pokemon query | Four exact selectors—without query, wrong limit, duplicate limit, and extra query—ran as 4 selected real-wire tests; all expected `404 Not Found` but got `200 OK`; `BUILD FAILED`. | Those four selectors plus `pokemon index accepts exactly one decoded limit five query` ran as 5 selected tests and passed. The positive test covers plain `limit=5` and URL-encoded `li%6Dit=%35`; `BUILD SUCCESSFUL`. |
| Rundown request URIs | Task 2's recorded public-resource RED ran the four direct V2/V3 selectors and failed all 4 with an empty fixture ledger. That historical RED establishes that public resources did not reach the loopback fixture. | The corrected four direct selectors passed 4 tests in 6.4s. Each test now asserts all four report request URIs exactly and in order under its fixture `baseUrl`, while retaining the exact fixture ledger assertion; 16 loopback requests total. No production reporting code changed. |
| Remaining live services and scan boundary | Review found the Task 5 wording omitted live PokeAPI use by out-of-scope Pokemon integration tests. | `build.gradle.kts` and `DEVELOPMENT.md` now name Apigee, Beeceptor, and PokeAPI in those remaining Pokemon tests. The migrated restful-api.dev tests/resources plus `PokemonSandboxApiTest` and its resources are the local/no-public-host scan scope; all other Pokemon integration tests/resources remain live and out of scope. |

The fixture, ledger, and PokemonSandbox selectors then passed 19 tests in 6.2s. A combined run of
the fixture plus all six owning integration classes passed 23 tests in 8.4s; the fresh pre-commit
rerun passed the same 23 tests in 8.2s. These are correction verification runs, not the still-pending
final post-commit Task 6 gates or re-reviews.

## Task 5 mutation evidence

Every temporary mutation was applied with `apply_patch`, run with the pinned JDK
`/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn`, immediately restored with
`apply_patch`, source-checked, then rerun green. For every RED and GREEN invocation, the exact
reusable command template was:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :integrationTest --tests '<exact selector in this row>' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

Row 4 used the same command with both listed `--tests` arguments; all other rows used one.

| # | Mutation | RED: exact selector(s), count, and observed failure | Restoration evidence | GREEN: exact selector(s), count, and result |
|---:|---|---|---|---|
| 1 | Delete object route family | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.post objects creates a fixture-local object`; 1 selected test; expected `200 OK`, got `404 Not Found`. | Restored all object routes; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.post objects creates a fixture-local object`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 2 | Delete Pokemon route family | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.pokemon index returns the fixed deterministic catalog`; 1 selected test; expected `200 OK`, got `404 Not Found`. | Restored all Pokemon routes; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.pokemon index returns the fixed deterministic catalog`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 3 | POST returns wrong status | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.post objects creates a fixture-local object`; 1 selected test; expected `200 OK`, got `400 Bad Request`. | Removed the POST status override; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.post objects creates a fixture-local object`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 4 | PATCH/PUT return wrong body | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.patch objects updates supplied fields and preserves omitted data` and `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.put objects replaces the stored object data`; 2 selected tests; both exact JSON assertions got `{}`. | Restored both JSON responses; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.patch objects updates supplied fields and preserves omitted data` and `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.put objects replaces the stored object data`; 2 selected tests passed; `BUILD SUCCESSFUL`. |
| 5 | Created object is not stored | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.get objects returns the exact stored object`; 1 selected test; expected `200 OK`, got `404 Not Found`. | Restored POST object-store insertion; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.get objects returns the exact stored object`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 6 | PATCH drops existing data | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.patch objects updates supplied fields and preserves omitted data`; 1 selected test; expected retained `data`, got `{"id":"local-object-1","name":"renamed object"}`. | Restored merge from the existing object map; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.patch objects updates supplied fields and preserves omitted data`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 7 | Remove `baseUrl` overlay | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test; `GET http://127.0.0.1:1/objects` returned connection-refused `503`. | Restored `dynamicEnvironment("baseUrl", api.getBaseUrl())`; `git diff --exit-code -- src/integrationTest/java/com/salesforce/revoman/integration/restfulapidev/RestfulAPIDevTest.java` exited 0. | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 8 | Change the shared loopback constant to wildcard `0.0.0.0` (not a bind-expression-only mutant) | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; expected `http://127.0.0.1:`, got `http://0.0.0.0:<port>`. | Restored literal `127.0.0.1`; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 9 | Bind fixed port | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; second fixture failed with `java.net.BindException: Address already in use`. | Restored requested port `0`; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 10 | Stop server before returning fixture | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; expected `200 OK`, got connection-refused `503`. | Removed premature server stop; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 11 | Omit request-ledger insertion | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test; expected four ordered signatures, got `[]`. | Restored request-ledger insertion; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 12 | Omit executor shutdown/await | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; named non-daemon worker remained alive (`expected to be false`). | Restored executor shutdown and bounded await; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |

Every RED was `BUILD FAILED`; every restored GREEN was `BUILD SUCCESSFUL`. The matrix contains 12
mutants, 13 RED selected tests, and 13 matching GREEN selected tests. No source-token assertion was
used as mutation evidence, and no temporary mutation remains in the worktree.

## Documentation and static checks

Current build and development retry comments name Apigee, Beeceptor, and PokeAPI use by Pokemon
tests outside the localized `PokemonSandboxApiTest` as remaining live services. README and Antora
describe a deterministic local http4k-backed real-wire fixture;
the home-page readout is ordered GET/POST/PATCH/GET. The approved design and plan correct only the
stale warm final GET, retaining the unchanged V3 script behavior and documenting the actual
`GET /objects/null` tail.

`./gradlew detekt --rerun-tasks --no-build-cache --no-configuration-cache --console=plain` and
`./gradlew spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain`
both completed successfully for Task 5. Detekt did not name a changed source for baseline fingerprint
refresh, so `detekt/baseline-source-sha256sums.txt` is unchanged. The explicit local scan applies
only to the migrated restful-api.dev Java/Kotlin tests and V2/V3 resources plus
`PokemonSandboxApiTest` and `pm-templates/v2/pokemon-sandbox-api`. It found no
`api.restful-api.dev` or `https://pokeapi.co` match in that scope. Other Pokemon integration tests
and their V2/V3 Pokemon resources remain live PokeAPI consumers and are explicitly outside the scan
and migration scope. The remaining-live-service allowlist is therefore Apigee, Beeceptor, and those
out-of-scope PokeAPI consumers; the allowlist scan observed the expected three PokeAPI locations in
`PokemonTest.java` and the V2/V3 Pokemon environments.
IDE closed-batch diagnostics for changed `build.gradle.kts` reported zero errors after sync.
`git diff --check` and `git diff --cached --check` were clean; the cached diff contains only the
five brief files, the landing report, and the two explicitly authorized V3-tail corrections.

For the Task 6 correction, pinned-JDK `./gradlew :detekt --rerun-tasks --no-build-cache
--no-configuration-cache --console=plain` completed `BUILD SUCCESSFUL` in 2s with 10 actionable
tasks. The first pinned-JDK `:spotlessCheck` named only two Kotlin layout violations; pinned-JDK
`:spotlessApply` corrected them, and the fresh pinned-JDK `:spotlessCheck` completed
`BUILD SUCCESSFUL` in 8s with 17 actionable tasks. After synchronizing the nine changed paths, IDE
closed-batch diagnostics reported zero errors and zero build errors across `build.gradle.kts` and all
six changed Java/Kotlin files. The correction staging audit contained exactly the nine authorized
files and `git diff --cached --check` was clean. The post-commit re-reviews and all gates remain
pending.
