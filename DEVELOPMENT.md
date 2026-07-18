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

## Continuous Integration

- `.github/workflows/build.yml` runs `./gradlew build` on every push/PR to `master` —
  full coverage: unit (`test`) + integration (`integrationTest`) + `spotlessCheck` + `kover`.
- **Org tests** (`integration.core.*`) skip-loud on CI (no org creds); see `-PincludeCoreIT` above.
- **Flaky external-API tests** (pokeapi.co, restful-api.dev, apigee, beeceptor) are retried via the
  `org.gradle.test-retry` plugin — but ONLY on CI (`CI` env var set). Locally `maxRetries=0`, so
  flakes surface immediately. A test failing every attempt still fails the build (no masking).

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

### How Core picks up a locally-built jar

Core's `.bazelrc-local` overrides the `com_salesforce_revoman_revoman` repository to a local
checkout, and that repo's `BUILD.bazel` globs `build/libs/revoman-*.jar`. So a rebuilt jar here
is picked up by Core on its next **server restart** (a `java_import` jar is not hot-reloaded — the
running server holds the old bytecode until it restarts). ReVoman-library change → rebuild the jar
here → restart the Core server.

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
