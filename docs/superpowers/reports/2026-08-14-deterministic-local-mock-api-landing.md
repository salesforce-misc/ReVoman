# Deterministic local mock API landing record

## Scope and evidence boundary

This record includes the observed Task 6 pre-gate correction wave from reviewed base
`9295162dc1f2f37113d141346f39cb8d2a9ee2ab`. The approved design is
`0353e76b9ede244b150489d1f173aa4b33748e66`, based on
`478529ad02030de7beb9dd98aa032e3c5ea2aa4b`. Tasks 1–4 were committed through
`bedbf5f7435d383bd0c58d052bdc6fd4fb5ee908`; Task 5 adds the documentation,
mutation, resource-scan, and report evidence recorded here. The Task 6 correction commit adds the
review fixes and evidence below.

The work remains limited to integration-test fixtures and resources, build/docs gate-reproducibility
inputs, and current documentation. It does not change production source, production/runtime
dependencies, public ABI, benchmark-driver code, or benchmark identity. The root-private npm
manifest and lockfile pin only the Antora documentation toolchain. No privileged CS2a controlled
measurement was run or claimed: its administrator-owned UID policy and disposable Linux/root launch
harness remain blockers. Task 6's completed local gate attempt and current correction status are
recorded below. Because the decoded-query correction changes fixture source bytes after that gate,
its scoped re-review and the complete Appendix A/B rerun are pending. Landing, the merge and master
push, GitHub CI verification, worktree/clone cleanup, and privileged CS2a measurement also remain
pending and belong to later tasks.

## Current local contract

`DeterministicMockApiServer` binds a real `127.0.0.1:0` socket, authenticates the selected
`server.address.address` as exact IPv4 `127.0.0.1`, derives `baseUrl` from that bound address,
exposes an http4k-backed root handler, records replayable real-wire requests with decoded ordered
query pairs, allocates fixture-local object IDs, and closes its named non-daemon executor. Its active
deterministic routes are:

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
rerun passed the same 23 tests in 8.2s. These were correction verification runs; the independent
reviews, first exact-HEAD gate, and subsequent Spec finding are recorded separately below.

## Task 6 first final-gate attempt and review result

The first implementation gate used exact clean SHA
`cd68f5a09119ae906c1ef0b43e74d136ca818602`. The subsequent final Standards and security reviews
passed, but the final Spec review found one Important gap: the approved design requires a decoded
query in each request-ledger entry, while the fixture still exposed raw `uri.query` text. Thus the
earlier zero-finding task/spec claim was overstated. The security review found zero Critical or
Important issues and traced seven reported HIGH package nodes to three `js-yaml` build-time
CPU-denial-of-service advisories. They remain recorded nonblocking hardening debt under the mandated
Antora/Lunr pins; no package pin or lockfile byte was changed for the gate.

The results below are literal evidence for the first clean gate at `cd68f5a`; they are not final gate
evidence for the later decoded-query source correction.

The first-gate root-project-qualified focused selector was:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevV3Test' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.RestfulAPIDevKtTest' \
  --tests 'com.salesforce.revoman.integration.restfulapidev.v3.LedgerRoundTripKtTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

It passed 23 tests in 35s; the build completed in 1m29s with 22 executed tasks.

Appendix A and Appendix B then ran serially from command one in the same Bash process with pinned
Corretto 21.0.11 and readonly
`GATED_IMPLEMENTATION_SHA=cd68f5a09119ae906c1ef0b43e74d136ca818602`. No command was skipped,
reordered, overlapped, or resumed mid-block. Appendix A produced these literal-command milestones:

- `./gradlew :benchmark-driver:installDist --rerun-tasks --no-build-cache
  --no-configuration-cache --console=plain`: `BUILD SUCCESSFUL in 30s`, 19 executed tasks.
- `./gradlew -I benchmark-driver/src/main/dist/libexec/benchmark-target.init.gradle.kts
  writeBenchmarkTargetManifest -Pbenchmark.targetManifest=build/benchmark-target-current.json
  -Pbenchmark.targetId=current-cs2a --rerun-tasks --no-build-cache --no-configuration-cache
  --console=plain`: `BUILD SUCCESSFUL in 17s`, 16 executed tasks.
- `./gradlew checkKotlinAbi apiCompatibilityTestClasses :test :integrationTest
  :benchmark-driver:test -Pbenchmark.targetManifest=build/benchmark-target-current.json
  -Pbenchmark.adapter=major-v1 --rerun-tasks --no-build-cache --no-configuration-cache
  --console=plain`: integrationTest passed 40 tests in 29.9s; the test tasks passed 834 tests in
  7m26s; `BUILD SUCCESSFUL in 8m22s`, 41 executed tasks.
- The literal `git worktree add --detach` block authenticated
  `build/cs2a-selftest.6iVhkp24/baseline` at exact baseline
  `83f3cd70f78ad733412d10cbc8287aaabafe7aac`. Its literal clean baseline
  `clean writeBenchmarkTargetManifest` invocation completed `BUILD SUCCESSFUL in 8s`, 17 tasks
  (7 executed, 10 from cache).
- `./gradlew :benchmark-driver:integrationTest
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json
  -Pbenchmark.adapter=baseline-83f3cd70 --rerun-tasks --no-build-cache
  --no-configuration-cache --console=plain`: `BUILD SUCCESSFUL in 1m02s`, 21 executed tasks.
- The literal `:benchmark-driver:integrationTest` invocation with the two exact major-lifecycle
  `--tests` selectors and current manifest completed `BUILD SUCCESSFUL in 19s`, 21 executed tasks.
- `./gradlew :benchmark-driver:benchmarkHarnessSelfTest
  -Pbenchmark.targetManifest=build/benchmark-target-baseline-selftest.json
  -Pbenchmark.adapter=baseline-83f3cd70 --rerun-tasks --no-build-cache
  --no-configuration-cache --console=plain`: `BUILD SUCCESSFUL in 11s`, 20 executed tasks.
- `./gradlew build :benchmark-driver:jmhClasses :benchmark-driver:installDist spotlessCheck
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain`: the intentional
  compile-only compatibility suite ran 0 tests in 1.1s, integrationTest passed 40 tests in 25.7s,
  the test tasks passed 834 tests in 7m03s, and the full build including Kover and Spotless completed
  `BUILD SUCCESSFUL in 8m06s`, 68 executed tasks.
- `./gradlew kaptKotlin classes :benchmark-driver:kaptKotlin :benchmark-driver:classes
  --no-configuration-cache --console=plain`: `BUILD SUCCESSFUL in 973ms`, 16 up-to-date tasks.
- `./gradlew qodanaScan --no-configuration-cache --console=plain`: Qodana literally reported
  `Analysis results: 85 problems detected`, `High - 45, Moderate - 40`, and
  `Found 85 new problems according to the checks applied`; `BUILD SUCCESSFUL in 8m44s`, 10 tasks
  (1 executed, 9 up-to-date), with no tracked or untracked drift. Independent range triage at
  `eb02278b` classified all 85 as predating `478529ad..eb02278b` and zero as introduced through that
  correction; the final scan at `cd68f5a` reported the same count and classification. No threshold,
  baseline, finding, or Qodana configuration was changed.
- Literal `npm ci` installed 168 packages in 3s. The required first linked-worktree attempt,
  `npx antora antora-playbook.yml`, exited 1 only with Antora's known `Local content source must be a
  git repository ... (url: .)` rejection. In the same shell, the prescribed fallback created the
  fresh ordinary clone
  `build/antora-gate.U9bVA5sy/repo`, fetched and detached exact readonly gated SHA, proved its real
  `.git` directory and clean state, ran literal `npm ci` (168 packages in 3s), and ran the exact same
  literal `npx antora antora-playbook.yml` successfully. `build/site/index.html`, exact SHA, and a
  clean clone were asserted before the shell continued.
- Appendix A's final exact-SHA and clean-worktree assertions passed.

Appendix B then validated all three operator scripts with literal `/bin/bash -n` and `shellcheck`,
and proved the manifest validator executable. Its exact focused command was:

```bash
./gradlew :test \
  --tests '*Cs2aManifestValidatorTest*' \
  --tests '*Cs2aOperatorScriptTest*' \
  --tests '*Cs2aSupervisorAtomicHandoffTest*' \
  --tests '*DetektBaselineIntegrityTest*' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

It passed 107 tests in 8m28s; the build completed in 9m52s with 24 executed tasks. Appendix B's
exact-SHA and clean-worktree assertions passed, `build/cs2a-implementation-sha` recorded the gated
SHA, and the authorized literal recovery push
`git push origin HEAD:refs/heads/codex/performance-cs2a-lifecycle` created that remote branch at
`cd68f5a09119ae906c1ef0b43e74d136ca818602`; `git ls-remote` independently returned the same object
ID. The indivisible A-to-B shell exited 0. No privileged CS2a measurement ran. This landing-report
commit remained separate from and later than the gated implementation SHA and was not part of that
recovery push. Its prescribed post-commit Antora check subsequently passed in an exact-SHA ordinary
clone after the known linked-worktree rejection.

## Task 6 decoded-query ledger correction

The agreed public seam is `DeterministicMockApiServer.requestSignatures()`. The test changed first so
the existing real-wire plain and encoded-valid Pokemon requests both had to appear as canonical
decoded `GET /pokemon?limit=5` signatures, in their received order. The exact pinned-JDK RED command
was:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest.pokemon index accepts exactly one decoded limit five query' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

It ran 1 test in 1.3s and failed in 14s: expected two `GET /pokemon?limit=5` entries, but the second
was raw `GET /pokemon?li%6Dit=%35`. Only the fixture test differed from the report commit during this
RED; the fixture implementation was untouched.

The minimal implementation now stores the decoded ordered http4k `List<Parameter>` returned by
`uri.queries()` instead of raw `uri.query`. `requestSignatures()` renders those decoded pairs in
list order without sorting or deduplication: `&` separates pairs, a null value retains the bare name,
and an empty value retains `=`. Method and decoded-query-independent path handling are unchanged.
The one-time body materialization, replayable body construction, and copied raw request-body bytes
are unchanged.

A second real-wire public-seam test makes the rendering convention unambiguous with deliberately
unsorted `z=%32`, repeated `tag`, bare/null `flag`, and empty `empty=` pairs. Its expected signature
is exactly `GET /pokemon?z=2&tag=first&tag=second&flag&empty=`. The baseline passed 1 test in 1.4s
(`BUILD SUCCESSFUL in 10s`). A temporary sort-plus-deduplicate mutant made it RED, returning
`GET /pokemon?empty=&flag&tag=first&z=2`; 1 test failed in 1.4s (`BUILD FAILED in 8s`). After restoring
that mutant, a separate null-as-empty mutant made the same test RED with `flag=` instead of bare
`flag`; 1 test failed in 1.4s (`BUILD FAILED in 10s`). Both mutants were applied and restored with
`apply_patch`; the restored selector passed 1 test in 1.3s (`BUILD SUCCESSFUL in 9s`).

The identical focused command then passed 1 test in 1.2s; `BUILD SUCCESSFUL in 9s`, 22 executed
tasks. The full fixture plus affected local Pokemon selector used:

```bash
JAVA_HOME=/opt/homebrew/opt/sdkman-cli/libexec/candidates/java/21.0.11-amzn \
  ./gradlew :integrationTest \
  --tests 'com.salesforce.revoman.integration.testsupport.DeterministicMockApiServerTest' \
  --tests 'com.salesforce.revoman.integration.pokemon.PokemonSandboxApiTest' \
  --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

It passed 18 tests in 9s; `BUILD SUCCESSFUL in 17s`, 22 executed tasks. Pinned-JDK
`:spotlessCheck` passed in 14s with 17 executed tasks without rewriting a file, and pinned-JDK
`:detekt` passed in 2s with 10 executed tasks. This correction has not yet received its scoped Spec
re-review, and the complete Appendix A/B block has not been rerun for its changed source bytes. No
push, merge, cleanup, CI verification, or privileged CS2a measurement is claimed.

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
files and `git diff --cached --check` was clean. Subsequent build-gate corrections restored the
compatibility suite runtime while preserving its compile-only consumer contract, pinned the Antora
toolchain, and committed Qodana's authenticated IDE synchronization. Their final reviews and local
gates are recorded above.
