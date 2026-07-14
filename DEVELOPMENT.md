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
