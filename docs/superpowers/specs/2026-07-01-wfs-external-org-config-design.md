# WFS external-org config wiring — design

**Date:** 2026-07-01
**Status:** Approved (brainstorm)
**Topic:** Wire `~/.revoman/config.yaml` external-org creds into the WFS integration tests so they run live (or skip cleanly when creds are absent).

## Problem

`WfsReadPathParityE2ETest`, `WfsWritePathParityE2ETest`, and `WfsRulesParityE2ETest` cannot run from the IDE (or gradle). They fail at the auth step because `baseUrl`/`username`/`password` are blank.

Root cause (confirmed by static analysis, NOT a library defect):

- Every WFS `Kick` is built by `ReVomanConfigForWfs.kickFor()`, which wires only `.environmentPath(ENV_PATH)` → the committed `ws.environment.yaml`, whose `baseUrl`/`username`/`password` are blank (`''`).
- The auth collection (`auth/login-as-sysadmin.request.yaml`, `latest-api-version.request.yaml`) substitutes `{{baseUrl}}`/`{{username}}`/`{{password}}` → blank → auth fails → every test fails.
- The class javadoc (`ReVomanConfigForWfs.java`) and the plan doc claim creds come "via `~/.revoman/config.yaml`", but **no code ever reads that file**. Grep proof: the only `.revoman` literal is the javadoc comment; `FileUtils.readYamlMap` (the lib fn built for exactly this) is called only by its own unit test.
- There is also no skip guard — the javadoc promises tests are "skipped only when those creds are absent", but there is no `assumeTrue`, so an absent config produces a confusing blank-cred auth failure rather than a skip.

The library already ships both primitives:

- `FileUtils.readYamlMap(path)` — reads a flat `key: value` yaml into a `Map`.
- `Kick.dynamicEnvironment(Map)` overlay; `Environment.mergeEnvs` applies `dynamicEnvironment` **last** (`envFromYamlPaths + ... + dynamicEnvironment`), so it overrides blank env-file keys (last-wins).
- `ClasspathResolver.resolveClasspath` returns `(path, SYSTEM)` for absolute paths, so an absolute `$HOME/.revoman/config.yaml` reads straight from disk.

They were simply never connected. CI is unaffected: `build.yml` runs only `PokemonTest`, not the WFS suite.

## Decisions

- **Fix location: first-class lib feature.** A small, reusable helper in `main/src` (`com.salesforce.revoman.input.ExternalOrgConfig`) that reads the well-known `~/.revoman/config.yaml`. Makes external-org config a supported ReVoman capability, not one-off WFS test glue.
- **Absent config: skip via `assumeTrue`.** When the file is absent or `baseUrl`/`username`/`password` are blank, the WFS tests are SKIPPED (JUnit assumption), honoring the existing javadoc contract.

## Architecture

### Layer A — lib feature (`src/main/kotlin/.../input/ExternalOrgConfig.kt`)

One clear purpose: read external-org creds from the well-known path into a `Map` suitable for `dynamicEnvironment` overlay. Lenient — absent file returns an empty map, so callers can overlay unconditionally. Blank-vs-absent creds policy stays OUT of the lib (the lib just reads; the test decides to skip).

```kotlin
@file:JvmName("ExternalOrgConfig")
package com.salesforce.revoman.input

import java.io.File

/** Well-known external-org creds file, relative to the user's home dir. */
const val EXTERNAL_ORG_CONFIG_REL_PATH = ".revoman/config.yaml"

/** Read the well-known `~/.revoman/config.yaml`. Absent file → empty map. */
fun readExternalOrgConfig(): Map<String, Any?> =
  readExternalOrgConfig(defaultExternalOrgConfigPath())

/** Read a flat key:value external-org config at [absolutePath]. Absent file → empty map. */
fun readExternalOrgConfig(absolutePath: String): Map<String, Any?> =
  if (File(absolutePath).isFile) readYamlMap(absolutePath) else emptyMap()

private fun defaultExternalOrgConfigPath(): String =
  File(System.getProperty("user.home"), EXTERNAL_ORG_CONFIG_REL_PATH).absolutePath
```

- Reuses `readYamlMap` → `resolveClasspath` (absolute → SYSTEM fs). Jar-safe, classloader-safe.
- `@JvmName("ExternalOrgConfig")` so Java callers use `ExternalOrgConfig.readExternalOrgConfig()`.
- Logging: a single info-level line ("external-org config loaded: N keys" / "absent, using empty") consistent with the repo's logging convention.

### Layer B — test wiring (`ReVomanConfigForWfs.java`)

```java
import com.salesforce.revoman.input.ExternalOrgConfig;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

// Loaded once at class init; lenient read never throws.
static final Map<String, Object> EXTERNAL_ORG_CONFIG = ExternalOrgConfig.readExternalOrgConfig();

// Skip (not fail) when external-org creds are absent/blank — honors the class-javadoc contract.
static void assumeExternalOrgCreds() {
    Assumptions.assumeTrue(
        hasText("baseUrl") && hasText("username") && hasText("password"),
        "WFS external-org creds absent — set ~/.revoman/config.yaml (baseUrl/username/password). Skipping.");
}
private static boolean hasText(String key) {
    final Object v = EXTERNAL_ORG_CONFIG.get(key);
    return v != null && !v.toString().isBlank();
}
```

`kickFor` gains one line: `.dynamicEnvironment(EXTERNAL_ORG_CONFIG)`. Precedence via `mergeEnvs` → overrides the blank env-file creds.

Multi-kick propagation is safe: `ReVoman.revUp` folds the accumulated `mutableEnv` on top of each kick's `dynamicEnvironment` (later kicks override), so the static config seeds AUTH while minted tokens (adminToken/managerToken) propagate downstream without clashing.

### Test call sites

Each `@Test` first line: `assumeExternalOrgCreds();`. 12 sites total:

- `WfsReadPathParityE2ETest` — 3 methods
- `WfsWritePathParityE2ETest` — 8 methods
- `WfsRulesParityE2ETest` — 1 method

## Testing

- **Lib unit test** (`src/test/kotlin/.../input/ExternalOrgConfigTest.kt`):
  - reads a temp `config.yaml` (absolute path overload) → map with expected keys/values;
  - absent path → empty map.
  - Mirrors the existing `FileUtilsTest` `readYamlMap` cases.
- **No new integration test.** The WFS E2E tests are the integration coverage; they require the live workspace org. With a real `~/.revoman/config.yaml` present, `testResourceLimitApptDistributionCapE2E` is the live smoke check (the test the user reported).
- **Verify:** `gradle test` (unit passes, no live org needed); confirm the skip fires when config is absent and the tests dispatch when present.

## Error handling / edge cases

| Case | Behavior |
|------|----------|
| File absent | `readExternalOrgConfig` → empty map → `assumeExternalOrgCreds` skips. No crash. |
| Malformed / non-mapping yaml | `readYamlMap` already returns empty → skip. |
| Blank creds (committed template) | `hasText` false → skip. |
| Static init | Lenient read never throws → class loads safely. |

## Non-goals (YAGNI)

- No change to `mergeEnvs` precedence (already correct).
- No new `Kick` builder method — `dynamicEnvironment` already exists.
- No CI wiring for the WFS suite (out of scope; CI runs only `PokemonTest`).
- No unrelated refactor of `ReVomanConfigForWfs`.

## Files

- **New:** `src/main/kotlin/com/salesforce/revoman/input/ExternalOrgConfig.kt`
- **New:** `src/test/kotlin/com/salesforce/revoman/input/ExternalOrgConfigTest.kt`
- **Edit:** `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java` (import, static field, helper, `.dynamicEnvironment(...)` in `kickFor`)
- **Edit:** `WfsReadPathParityE2ETest.java`, `WfsWritePathParityE2ETest.java`, `WfsRulesParityE2ETest.java` (`assumeExternalOrgCreds();` first line of each `@Test`)

## Delivery

Work on a git worktree off `master`; merge the fix into `master` when green.
