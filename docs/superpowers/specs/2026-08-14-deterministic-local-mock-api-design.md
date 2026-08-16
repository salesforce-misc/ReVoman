# Deterministic Local Mock API Design

**Status:** Approved for implementation on 2026-08-14

**Base commit:** `478529ad02030de7beb9dd98aa032e3c5ea2aa4b`

**Scope:** Integration-test infrastructure, active integration resources, current documentation,
and final CS2a/Task 9 landing evidence

## Goal

Replace every automated request to `restful-api.dev` with a deterministic http4k mock API on a
real IPv4 loopback socket. Make the mixed `PokemonSandboxApiTest` wholly local by serving its three
Pokemon reads from the same fixture. Then complete the existing local gates, Qodana, Antora,
independent review, landing, push, exact-SHA CI verification, and recoverability-gated worktree
cleanup.

The change must preserve the integration tests' purpose: Postman V2 and V3 loading, real HTTP
serialization, script execution, cross-step variables, request bodies, response parsing, ledger
learning, and warm producer skipping. A direct `HttpHandler` call is not an acceptable substitute
for the production ReVoman client and a kernel socket.

## Scope boundaries

The change covers:

- the V2 Java and Kotlin restful-api.dev collection tests;
- the V3 Java and Kotlin restful-api.dev collection tests;
- both V3 ledger round-trip tests;
- all five requests in `PokemonSandboxApiTest`, including its three PokeAPI reads;
- the active V2/V3 Postman resources those tests execute;
- build comments that still attribute retry behavior to restful-api.dev;
- README and current Antora pages that describe the automated example as a public live run; and
- a final report recording RED/GREEN, mutation, gate, review, landing, CI, and cleanup evidence.

Other Pokemon integration tests remain unchanged. Historical specifications, archived Postman CLI
reports, benchmark identities, production source, public ABI files, and benchmark-driver behavior
are outside this correction.

This task does not run or claim the privileged CS2a controlled benchmark. Its administrator-owned
UID policy and disposable Linux/root launch harness remain explicit measurement blockers. The
user's current instruction authorizes landing after the deterministic correctness, packaging,
Qodana, documentation, review, and CI gates succeed; the landing report must not misstate the
privileged measurement as complete.

## Selected architecture

Create one public-to-the-integration-source-set Kotlin fixture:

```text
src/integrationTest/kotlin/com/salesforce/revoman/integration/testsupport/
└── DeterministicMockApiServer.kt
```

`DeterministicMockApiServer` implements `AutoCloseable` and exposes Java-friendly entry points:

```kotlin
class DeterministicMockApiServer : AutoCloseable {
  val baseUrl: String
  fun requestSignatures(): List<String>
  fun hitCount(path: String): Int
  override fun close()

  companion object {
    @JvmStatic fun start(): DeterministicMockApiServer
  }
}
```

It owns a JDK `HttpServer` bound to literal `127.0.0.1` and requested port `0`, one explicitly
named single-thread `ExecutorService`, an in-memory object store, and a concurrent request ledger.
The server installs one root JDK context backed by http4k's public `HttpExchangeHandler`; the
application itself is composed from http4k `routes` and handlers. This retains real HTTP transport
while avoiding stock `SunHttp`, whose wildcard bind and executor lifecycle do not meet the
fixture's containment contract.

Startup proves the selected address is exactly IPv4 loopback and the selected port is nonzero. A
startup failure stops the server and shuts down the executor. `close()` is idempotent and orders
cleanup as server stop, executor shutdown, bounded await, `shutdownNow`, and a second bounded
fail-closed await. Tests prove the listener refuses connections and the named non-daemon worker is
absent after close.

## Deterministic API contract

The fixture serves the following exact routes:

| Request | Response and state |
|---|---|
| `GET /objects` | `200` JSON array containing the current deterministic store |
| `POST /objects` | Validate a JSON object, allocate `local-object-1`, store `name` and `data`, return `200` with `{id,name,data}` |
| `PATCH /objects/{id}` | For an existing object, merge supplied fields and return `200` with `{id,name,data}`; otherwise return JSON `404` |
| `PUT /objects/{id}` | For an existing object, replace supplied fields and return `200` with `{id,name,data}`; otherwise return JSON `404` |
| `GET /objects/{id}` | Return the stored `{id,name,data}` with `200`; otherwise return JSON `404` |
| `GET /pokemon?limit=5` | Return five fixed results beginning with `bulbasaur` |
| `GET /pokemon/bulbasaur` | Return fixed JSON with `id: 1` and `name: "bulbasaur"` |
| `GET /pokemon-species/bulbasaur` | Return fixed JSON with the same `id: 1` |

Malformed JSON returns `400`; an unsupported method or path returns `404`. Route code must not
read the wall clock, network, random generator, environment, or filesystem. The existing Postman
scripts may generate product text and prices, but server decisions, IDs, status codes, response
shape, ordering, and state transitions are deterministic.

Every request is recorded after materializing its body once and before routing. The downstream
handler receives a replayable in-memory body. The ledger records method, path, decoded query, and
raw body bytes so tests can assert the exact protocol without inspecting a mock invocation.

## Resource and environment model

The active standalone object collections use a complete `{{baseUrl}}` variable instead of the
hard-coded `https://{{uri}}` composition. Their checked-in environment default is the fail-closed
sentinel `http://127.0.0.1:1`; every automated test overlays the fixture's selected `baseUrl` with
`Kick.dynamicEnvironment`.

The mixed Pokemon collection uses two complete variables:

- `{{pokemonApiBaseUrl}}` for `/pokemon` and `/pokemon-species` routes; and
- `{{objectApiBaseUrl}}` for `/objects` routes.

Both checked-in defaults are `http://127.0.0.1:1`. `PokemonSandboxApiTest` overlays both with the
same fixture URL. Consequently a missing overlay fails locally and cannot leak a request to a
public service. The `limit=5` environment value remains unchanged.

Do not rename the existing test packages, classes, or resource directories. Their names are
historical fixture identities and are linked from documentation. Current README and Antora prose
must say the automated run is deterministic and local rather than claim that the test reaches a
free public API.

## Test migration and assertions

Each direct V2/V3 test starts a fresh fixture, overlays `baseUrl`, executes the unchanged collection
through `ReVoman.revUp`, and asserts:

- four successful step reports;
- exact request order `GET /objects`, `POST /objects`, `PATCH /objects/local-object-1`,
  `GET /objects/local-object-1`;
- exact one-hit counts; and
- loopback request URIs in the resulting reports.

The ledger cold test uses a fresh fixture and retains its produced-key and nonempty V3 source-hash
assertions while adding the exact four-request protocol. The warm test runs cold and warm against
one fixture. The warm producer POST is skipped, so its three-request tail is `GET /objects`,
`PATCH /objects/ledgered-obj-id`, `GET /objects/null`. The PATCH returns `404`; the unchanged V3
`afterResponse` behavior then overwrites `objId` with null because that 404 response has no `id`.
The test preserves that downstream-failure caveat while proving ledger injection and re-emission.

`PokemonSandboxApiTest` overlays both base URLs and asserts the exact five-request sequence:

```text
GET /pokemon?limit=5
GET /pokemon/bulbasaur
GET /pokemon-species/bulbasaur
POST /objects
PUT /objects/local-object-1
```

Its existing script, scope, request-body, response-body, and assertion checks remain in place.
The fixture's deterministic responses make `pokemonName=bulbasaur` and `pokemonId=1` literal
cross-step expectations.

## TDD and mutation contract

Implementation follows RED/GREEN/refactor:

1. Add the fixture contract test first and observe failure while the fixture/route behavior is
   absent.
2. Implement only loopback lifecycle and the first route; progress route-by-route with focused RED
   cases for object creation, patch/put state, missing IDs, Pokemon responses, request recording,
   and shutdown.
3. Change each integration test to start the fixture and add exact protocol assertions before
   changing its resource variables. Capture the current public-service failure or fail-closed
   sentinel failure, then apply the minimal resource/overlay change and observe GREEN.
4. Run each migration group before proceeding to the next.

Execute and restore at least these behavioral mutants:

- delete each object and Pokemon route family;
- return the wrong POST/PATCH/PUT status or body;
- stop storing the created object or fail to preserve PATCH data;
- remove one dynamic base-URL overlay, which must hit only `127.0.0.1:1` and fail;
- change the bind to wildcard or a fixed port;
- bypass the live socket with a direct handler;
- omit request-ledger insertion; and
- omit executor shutdown or bounded termination.

The restored tree must rerun the exact focused selector and the full integration suite.

## Verification, landing, and cleanup

After implementation and documentation are committed and independently reviewed, run the existing
Task 8 Step 3 block verbatim on one clean SHA, followed in the same shell by the operator Bash,
ShellCheck, jq, and focused security tests. The block includes root/unit/integration/driver tests,
ABI consumers, the fixed baseline manifest and benchmark self-tests, `build`, Detekt, JMH classes,
install distribution, Spotless, generated-source compilation, Qodana, and Antora. No failed or
interrupted subcommand counts as partial completion.

If Qodana or Antora cannot consume a linked-worktree `.git` file, validate the exact same committed
SHA in a disposable ordinary clone rather than skipping the gate. Record commands, exit status,
test counts, Qodana findings, Antora result, and exact SHA in the final report.

Run final fixed-range Standards, Spec, and security reviews. Push the feature branch as a remote
recovery point, merge it into local `master`, rerun the required merged-result verification, and
push `master` without force. Use GitHub Actions to wait for the exact landed SHA's Build, Qodana,
and Docs workflows; inspect and repair any failure before declaring landing complete.

Only after the exact landed SHA is present on `origin/master`, all required CI is green, and every
retained change is committed or intentionally preserved may cleanup begin. Inventory every
registered worktree and ordinary disposable clone. Remove nested generated worktrees before their
parent, inspect any dirty scratch diff before discarding it, preserve unrelated untracked files in
the main checkout, remove the clean completed feature worktree from outside it, prune registrations,
and delete the merged local feature branch normally. Report every removed path and its recovery
point.

## Rejected alternatives

1. **Stock http4k `SunHttp`:** rejected because it binds with an unspecified/wildcard address and
   owns an executor whose immediate-stop lifecycle does not prove this fixture's thread cleanup.
2. **Direct `HttpHandler` tests:** rejected because they bypass DNS/socket selection, Apache client
   serialization, headers, body streaming, and response decoding.
3. **Ad hoc server logic in each test:** rejected because it duplicates state and lifecycle rules,
   makes Java/Kotlin/V2/V3 behavior drift, and weakens mutation coverage.
4. **Runtime rewriting or temporary copies of Postman resources:** rejected because V3 path-based
   loading/source hashes and Antora live includes make the copy boundary unnecessarily complex.

## Success criteria

The work is complete only when:

- the targeted tests make zero public restful-api.dev or PokeAPI requests;
- the real-wire http4k fixture contract and every migrated integration test pass;
- required mutants go RED and the restored tree returns GREEN;
- current docs no longer describe these automated tests as live public calls;
- complete local gates, Qodana, Antora, independent reviews, merge, push, and exact-SHA CI pass;
- `origin/master` contains the landed commits; and
- completed worktrees are removed only after recoverability is proven.
