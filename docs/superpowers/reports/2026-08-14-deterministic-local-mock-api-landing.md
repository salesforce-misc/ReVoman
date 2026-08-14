# Deterministic local mock API landing record

## Scope and evidence boundary

This record is current through Task 5 only. The approved design is
`0353e76b9ede244b150489d1f173aa4b33748e66`, based on
`478529ad02030de7beb9dd98aa032e3c5ea2aa4b`. Tasks 1–4 were committed through
`bedbf5f7435d383bd0c58d052bdc6fd4fb5ee908`; Task 5 adds the documentation,
mutation, resource-scan, and report evidence recorded here.

The work remains limited to integration-test fixtures and resources plus current documentation.
It does not change production source, dependencies, public ABI, benchmark-driver code, or benchmark
identity. No privileged CS2a controlled measurement was run or claimed: its administrator-owned UID
policy and disposable Linux/root launch harness remain blockers. Tasks 6 and 7 review, final gate,
Qodana, Antora, landing, push, CI, and cleanup evidence are pending and are intentionally absent.

## Current local contract

`DeterministicMockApiServer` binds a real `127.0.0.1:0` socket, exposes an http4k-backed root
handler, records replayable real-wire requests, allocates fixture-local object IDs, and closes its
named non-daemon executor. Its active deterministic routes are:

- `GET`, `POST /objects`; `GET`, `PATCH`, `PUT /objects/{id}`;
- `GET /pokemon?limit=5`, `GET /pokemon/bulbasaur`, and
  `GET /pokemon-species/bulbasaur`.

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
| 8 | Bind wildcard | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; expected `http://127.0.0.1:`, got `http://0.0.0.0:<port>`. | Restored literal `127.0.0.1`; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 9 | Bind fixed port | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; second fixture failed with `java.net.BindException: Address already in use`. | Restored requested port `0`; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 10 | Stop server before returning fixture | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; expected `200 OK`, got connection-refused `503`. | Removed premature server stop; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 11 | Omit request-ledger insertion | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test; expected four ordered signatures, got `[]`. | Restored request-ledger insertion; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest`; 1 selected test passed; `BUILD SUCCESSFUL`. |
| 12 | Omit executor shutdown/await | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test; named non-daemon worker remained alive (`expected to be false`). | Restored executor shutdown and bounded await; `git diff --exit-code -- src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/DeterministicMockApiServer.kt` exited 0. | `com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.fixture owns an ephemeral loopback server and shuts down its worker`; 1 selected test passed; `BUILD SUCCESSFUL`. |

Every RED was `BUILD FAILED`; every restored GREEN was `BUILD SUCCESSFUL`. The matrix contains 12
mutants, 13 RED selected tests, and 13 matching GREEN selected tests. No source-token assertion was
used as mutation evidence, and no temporary mutation remains in the worktree.

## Documentation and static checks

Current build and development retry comments name only apigee and beeceptor as remaining live
services. README and Antora now describe a deterministic local http4k-backed real-wire fixture;
the home-page readout is ordered GET/POST/PATCH/GET. The approved design and plan correct only the
stale warm final GET, retaining the unchanged V3 script behavior and documenting the actual
`GET /objects/null` tail.

`./gradlew detekt --rerun-tasks --no-build-cache --no-configuration-cache --console=plain` and
`./gradlew spotlessCheck --rerun-tasks --no-build-cache --no-configuration-cache --console=plain`
both completed successfully. Detekt did not name a changed source for baseline fingerprint refresh,
so `detekt/baseline-source-sha256sums.txt` is unchanged. The archive-excluded resource scan and the
separate explicit active-source scan found no `api.restful-api.dev` or `https://pokeapi.co` match.
IDE closed-batch diagnostics for changed `build.gradle.kts` reported zero errors after sync.
`git diff --check` and `git diff --cached --check` were clean; the cached diff contains only the
five brief files, the landing report, and the two explicitly authorized V3-tail corrections.
