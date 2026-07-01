# WFS external-org config wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `~/.revoman/config.yaml` external-org creds into the WFS integration tests so they run live from the IDE/gradle (and skip cleanly when creds are absent), via a reusable library helper.

**Architecture:** Add a small first-class lib helper `ExternalOrgConfig` (main/src) that reads the well-known `~/.revoman/config.yaml` into a `Map` (reusing the existing `FileUtils.readYamlMap`). Wire it into `ReVomanConfigForWfs.kickFor()` as a `dynamicEnvironment` overlay (which `Environment.mergeEnvs` applies last → overrides the blank env-file creds). Guard every WFS `@Test` with a JUnit `assumeTrue` so an absent/blank config yields SKIPPED, not a confusing auth failure.

**Tech Stack:** Kotlin (main), Java 21 (integrationTest), JUnit 5 (Jupiter `Assumptions`), Kotest/Truth (unit tests), Gradle, kotlin-logging.

## Global Constraints

- Build/test with `gradle`, never `./gradlew` (per user memory).
- JDK 21+.
- Kotlin style: 4-space indent; functional patterns; explicit types where not obvious; KDoc on public APIs (STYLE.md).
- Java test-function naming in this repo is `testXxx` (no backticks); Kotlin unit tests use the existing backtick-or-`testXxx` style already present in `FileUtilsTest.kt` — match that file.
- Add appropriate logging for the new feature (AGENTS.md) — use `io.github.oshai.kotlinlogging.KotlinLogging` (repo convention, e.g. `V3EnvLoader.kt`).
- Never commit `~/.revoman/config.yaml` (holds org creds, lives in `$HOME`, outside repo).
- Copyright header: use the Apache-license block exactly as in `FileUtils.kt` (main/src) — NOT the "Company Confidential" header (that one is integrationTest-only).
- CI unaffected: `build.yml` runs only `PokemonTest`; do not touch CI.

---

## File Structure

- **New (lib):** `src/main/kotlin/com/salesforce/revoman/input/ExternalOrgConfig.kt` — read `~/.revoman/config.yaml` (well-known path) or an explicit absolute path into `Map<String, Any?>`; lenient (absent → empty).
- **New (lib unit test):** `src/test/kotlin/com/salesforce/revoman/input/ExternalOrgConfigTest.kt` — absolute-path overload happy path + absent-path empty case.
- **Edit (test wiring):** `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java` — import, `EXTERNAL_ORG_CONFIG` static field, `assumeExternalOrgCreds()` + `hasText(...)` helpers, `.dynamicEnvironment(EXTERNAL_ORG_CONFIG)` in `kickFor`.
- **Edit (3 test classes):** `WfsReadPathParityE2ETest.java`, `WfsWritePathParityE2ETest.java`, `WfsRulesParityE2ETest.java` — `ReVomanConfigForWfs.assumeExternalOrgCreds();` as the first line of every `@Test` (11 methods total).

---

## Task 0: Create the worktree off master

**Files:** none (git plumbing).

- [ ] **Step 1: Create a fresh worktree off `master`**

The current branch is `wfs/decision-1-9-revoman-tests`. Create an isolated worktree on a new branch off `master` so the fix merges cleanly into `master`.

Run:
```bash
cd /home/sfwork/code-clones/work/revoman-root
git fetch origin 2>/dev/null || true
git worktree add -b wfs/external-org-config ../revoman-external-org-config master
cd ../revoman-external-org-config
git branch --show-current
```
Expected: prints `wfs/external-org-config`.

- [ ] **Step 2: Cherry-pick the design spec onto the new branch**

The design spec was committed on `wfs/decision-1-9-revoman-tests`. Bring it onto the worktree branch so the plan+spec travel with the fix.

Run:
```bash
git checkout wfs/decision-1-9-revoman-tests -- docs/superpowers/specs/2026-07-01-wfs-external-org-config-design.md docs/superpowers/plans/2026-07-01-wfs-external-org-config.md
git add docs/superpowers/specs/2026-07-01-wfs-external-org-config-design.md docs/superpowers/plans/2026-07-01-wfs-external-org-config.md
git commit -m "docs(wfs): design spec + plan for external-org config wiring"
```
Expected: one commit created on `wfs/external-org-config`.

> All subsequent tasks run from the worktree dir `../revoman-external-org-config` (i.e. `/home/sfwork/code-clones/work/revoman-external-org-config`).

---

## Task 1: `ExternalOrgConfig` lib helper (TDD)

**Files:**
- Create: `src/main/kotlin/com/salesforce/revoman/input/ExternalOrgConfig.kt`
- Test: `src/test/kotlin/com/salesforce/revoman/input/ExternalOrgConfigTest.kt`

**Interfaces:**
- Consumes: `com.salesforce.revoman.input.readYamlMap(filePath: String): Map<String, Any?>` (existing, `FileUtils.kt`).
- Produces:
  - `const val EXTERNAL_ORG_CONFIG_REL_PATH: String = ".revoman/config.yaml"`
  - `fun readExternalOrgConfig(): Map<String, Any?>` — reads `$HOME/.revoman/config.yaml`; absent → empty.
  - `fun readExternalOrgConfig(absolutePath: String): Map<String, Any?>` — reads the given absolute path; absent → empty.
  - Java call form (via `@file:JvmName("ExternalOrgConfig")`): `ExternalOrgConfig.readExternalOrgConfig()` / `ExternalOrgConfig.readExternalOrgConfig(String)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/salesforce/revoman/input/ExternalOrgConfigTest.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.jupiter.api.Test

class ExternalOrgConfigTest {
  @Test
  fun `readExternalOrgConfig reads creds from an explicit absolute path`() {
    val tmp = Files.createTempFile("external-org", ".yaml")
    Files.writeString(
      tmp,
      """
      baseUrl: https://localhost:6101
      username: admin@local.org
      password: secret
      """
        .trimIndent(),
    )
    val map = readExternalOrgConfig(tmp.toAbsolutePath().toString())
    assertThat(map)
      .containsExactly(
        "baseUrl",
        "https://localhost:6101",
        "username",
        "admin@local.org",
        "password",
        "secret",
      )
  }

  @Test
  fun `readExternalOrgConfig returns empty when the file is absent`() {
    val absent = Files.createTempDirectory("external-org").resolve("nope.yaml")
    assertThat(readExternalOrgConfig(absent.toAbsolutePath().toString())).isEmpty()
  }

  @Test
  fun `EXTERNAL_ORG_CONFIG_REL_PATH is the well-known dotfile path`() {
    assertThat(EXTERNAL_ORG_CONFIG_REL_PATH).isEqualTo(".revoman/config.yaml")
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
gradle test --tests "com.salesforce.revoman.input.ExternalOrgConfigTest"
```
Expected: FAIL — compile error / unresolved reference `readExternalOrgConfig` and `EXTERNAL_ORG_CONFIG_REL_PATH`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/com/salesforce/revoman/input/ExternalOrgConfig.kt`:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("ExternalOrgConfig")

package com.salesforce.revoman.input

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * The well-known external-org creds file, relative to the user's home dir. A human-friendly flat
 * `key: value` YAML (e.g. `baseUrl` / `username` / `password`) that lets a developer point a
 * ReVoman run at their own org WITHOUT committing creds — it lives in `$HOME`, outside any repo.
 */
const val EXTERNAL_ORG_CONFIG_REL_PATH: String = ".revoman/config.yaml"

/**
 * Read the well-known `~/.revoman/config.yaml` external-org creds into a plain map suitable for a
 * [com.salesforce.revoman.input.config.Kick] `dynamicEnvironment` overlay. Lenient: an absent file
 * returns an empty map so callers can overlay unconditionally (they decide whether missing creds
 * mean skip/fail). See [readExternalOrgConfig] (absolute-path overload) for the read semantics.
 */
fun readExternalOrgConfig(): Map<String, Any?> =
  readExternalOrgConfig(File(System.getProperty("user.home"), EXTERNAL_ORG_CONFIG_REL_PATH).absolutePath)

/**
 * Read a flat `key: value` external-org config at [absolutePath] into a plain map. Absent file →
 * empty map (logged). Malformed / non-mapping content reads as empty via [readYamlMap].
 */
fun readExternalOrgConfig(absolutePath: String): Map<String, Any?> =
  File(absolutePath)
    .takeIf { it.isFile }
    ?.let { readYamlMap(absolutePath).also { m -> logger.info { "External-org config loaded from $absolutePath: ${m.size} keys" } } }
    ?: emptyMap<String, Any?>().also { logger.info { "External-org config absent at $absolutePath — using empty overlay" } }

private val logger = KotlinLogging.logger {}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
gradle test --tests "com.salesforce.revoman.input.ExternalOrgConfigTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Format + commit**

Run:
```bash
gradle spotlessApply
git add src/main/kotlin/com/salesforce/revoman/input/ExternalOrgConfig.kt \
        src/test/kotlin/com/salesforce/revoman/input/ExternalOrgConfigTest.kt
git commit -m "feat(input): ExternalOrgConfig reads ~/.revoman/config.yaml creds (lenient)"
```

---

## Task 2: Wire the overlay + skip guard into `ReVomanConfigForWfs`

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java`

**Interfaces:**
- Consumes: `ExternalOrgConfig.readExternalOrgConfig()` (Task 1); `Kick.configure()....dynamicEnvironment(Map)` (existing builder, cf. `PokemonTest.java:142`).
- Produces (used by Task 3):
  - `static final Map<String, Object> EXTERNAL_ORG_CONFIG`
  - `static void assumeExternalOrgCreds()` — JUnit `assumeTrue` on `baseUrl` && `username` && `password` being non-blank.

- [ ] **Step 1: Add imports**

In `ReVomanConfigForWfs.java`, after the existing `import com.salesforce.revoman.input.config.Kick;` (line 16) add:

```java
import com.salesforce.revoman.input.ExternalOrgConfig;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
```

- [ ] **Step 2: Add the static config field + skip-guard helpers**

Immediately after the `private ReVomanConfigForWfs() {}` line (line 92) insert:

```java
  /**
   * External-org creds overlaid onto every {@link #kickFor} Kick's {@code dynamicEnvironment} (which
   * {@code Environment.mergeEnvs} applies LAST, so it overrides the blank {@code
   * baseUrl}/{@code username}/{@code password} in the committed {@code ws.environment.yaml}). Read
   * once from {@code ~/.revoman/config.yaml}; absent file → empty overlay (tests then skip via
   * {@link #assumeExternalOrgCreds}). NEVER commit that file — it holds org creds and lives in
   * {@code $HOME}.
   */
  static final Map<String, Object> EXTERNAL_ORG_CONFIG = ExternalOrgConfig.readExternalOrgConfig();

  /**
   * Skip (JUnit assumption — NOT fail) when the external-org creds are absent or blank, honoring
   * this class's contract that the WFS tests "are skipped only when those creds are absent". Set
   * {@code ~/.revoman/config.yaml} (baseUrl / username / password) to run them live.
   */
  static void assumeExternalOrgCreds() {
    Assumptions.assumeTrue(
        hasText("baseUrl") && hasText("username") && hasText("password"),
        "WFS external-org creds absent — set ~/.revoman/config.yaml (baseUrl/username/password)."
            + " Skipping.");
  }

  private static boolean hasText(final String key) {
    final Object value = EXTERNAL_ORG_CONFIG.get(key);
    return value != null && !value.toString().isBlank();
  }
```

- [ ] **Step 3: Overlay the creds in `kickFor`**

In the `kickFor` builder chain (currently ends `.environmentPath(ENV_PATH)` on line 283), add the `dynamicEnvironment` overlay right after `.environmentPath(ENV_PATH)`:

```java
    return Kick.configure()
        .templatePath(templatePath)
        .environmentPath(ENV_PATH)
        .dynamicEnvironment(EXTERNAL_ORG_CONFIG)
        .responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
        .hooks(ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS, ASSERT_COMPOSITE_RESPONSE_SUCCESS)
        .globalCustomTypeAdapter(IDAdapter.INSTANCE)
        .nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
        .haltOnFailureOfTypeExcept(
            HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
        .insecureHttp(true)
        .off();
```

- [ ] **Step 4: Compile the integrationTest source set**

Run:
```bash
gradle compileIntegrationTestJava
```
Expected: BUILD SUCCESSFUL (no unresolved symbols; `dynamicEnvironment`, `ExternalOrgConfig`, `Assumptions` all resolve).

- [ ] **Step 5: Commit**

Run:
```bash
gradle spotlessApply
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
git commit -m "test(wfs): overlay ~/.revoman/config.yaml creds + assumeExternalOrgCreds skip guard"
```

---

## Task 3: Add the skip guard to every WFS `@Test`

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsRulesParityE2ETest.java`

**Interfaces:**
- Consumes: `ReVomanConfigForWfs.assumeExternalOrgCreds()` (Task 2).

- [ ] **Step 1: Guard `WfsReadPathParityE2ETest` (3 methods)**

As the FIRST statement inside each of these method bodies, add `ReVomanConfigForWfs.assumeExternalOrgCreds();`:
- `testResourceLimitApptDistributionCapE2E()` (before `final var rundown =`, ~line 65)
- `testShiftSharingModeSplitE2E()` (~line 112)
- `testCheapCheckReadWritePromiseE2E()` (~line 152)

Example (first method):
```java
  @Test
  void testResourceLimitApptDistributionCapE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
```
(No import needed — same package `com.salesforce.revoman.integration.core.wfs`.)

- [ ] **Step 2: Guard `WfsWritePathParityE2ETest` (7 methods)**

Add `ReVomanConfigForWfs.assumeExternalOrgCreds();` as the first statement of each:
- `testNonRequiredHelperFitnessE2E()`
- `testNonRequiredHelperDoubleBooksE2E()`
- `testNonRequiredHelperCannotSatisfyRequiredDemandE2E()`
- `testMissingRequiredFlagE2E()`
- `testTwoPrimaryResourcesRejectedE2E()`
- `testPrimaryNotRequiredRejectedE2E()`
- `testRescheduleNoPrimaryE2E()`

- [ ] **Step 3: Guard `WfsRulesParityE2ETest` (1 method)**

Add `ReVomanConfigForWfs.assumeExternalOrgCreds();` as the first statement of:
- `testMatchSkillsReadWriteParityE2E()`

- [ ] **Step 4: Verify guard count + compile**

Run:
```bash
grep -rc "assumeExternalOrgCreds();" src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/Wfs*.java
gradle compileIntegrationTestJava
```
Expected: counts are `WfsReadPathParityE2ETest.java:3`, `WfsRulesParityE2ETest.java:1`, `WfsWritePathParityE2ETest.java:7` (total 11); BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Run:
```bash
gradle spotlessApply
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsRulesParityE2ETest.java
git commit -m "test(wfs): skip WFS E2E when external-org creds absent (assumeExternalOrgCreds)"
```

---

## Task 4: Verify skip-when-absent + live-when-present, then merge to master

**Files:** none (verification + merge).

- [ ] **Step 1: Unit tests green (no live org)**

Run:
```bash
gradle test --tests "com.salesforce.revoman.input.ExternalOrgConfigTest"
```
Expected: PASS (3 tests).

- [ ] **Step 2: Verify SKIP when config is absent**

Temporarily hide the real config (if present) and run one WFS test; it must be SKIPPED, not failed.

Run:
```bash
[ -f ~/.revoman/config.yaml ] && mv ~/.revoman/config.yaml ~/.revoman/config.yaml.bak || true
gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsReadPathParityE2ETest.testResourceLimitApptDistributionCapE2E" || true
```
Expected: the test reports SKIPPED (JUnit assumption failed: "WFS external-org creds absent…"), the gradle run does NOT fail on it.

- [ ] **Step 3: Verify LIVE run when config is present**

Restore the real config and run the reported test live against the workspace org.

Run:
```bash
[ -f ~/.revoman/config.yaml.bak ] && mv ~/.revoman/config.yaml.bak ~/.revoman/config.yaml || true
gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsReadPathParityE2ETest.testResourceLimitApptDistributionCapE2E"
```
Expected: the test now dispatches (auth succeeds against the workspace org) and PASSES. If it fails for a live-org/data reason (not blank creds), capture the failure — that is a separate concern from this wiring fix; report it rather than silently masking.

> Note: this is a live external-org test. If the workspace org is unreachable from this environment, record that Step 3 could not be executed here and rely on Step 2 (skip) + Step 1 (unit) as the automated gates; the user runs Step 3 in their IDE.

- [ ] **Step 4: Merge to `master`**

From the worktree, fast-forward/merge the branch into `master`.

Run:
```bash
cd /home/sfwork/code-clones/work/revoman-root
git checkout master
git merge --no-ff wfs/external-org-config -m "merge: WFS external-org config wiring (~/.revoman/config.yaml)"
git log --oneline -6
```
Expected: `master` contains the ExternalOrgConfig feature + wiring commits.

- [ ] **Step 5: Clean up the worktree**

Run:
```bash
git worktree remove ../revoman-external-org-config
git worktree list
```
Expected: the temporary worktree is gone; `master` retains the merge.

---

## Self-Review notes

- **Spec coverage:** Layer A (lib helper) → Task 1. Layer B (`kickFor` overlay) → Task 2 Step 3. Skip guard (`assumeTrue`) → Task 2 Steps 2 + Task 3. Lib unit test → Task 1. Verify (skip + live) → Task 4. Worktree + merge (user request) → Tasks 0 + 4.
- **Call-site count:** Read 3 + Write 7 + Rules 1 = 11 (verified via grep of `void test` in each file). Task 3 Step 4 asserts this.
- **Type consistency:** `readExternalOrgConfig()` / `readExternalOrgConfig(String)` / `EXTERNAL_ORG_CONFIG_REL_PATH` used identically in Task 1 (def) and Task 2 (consumer). `assumeExternalOrgCreds()` / `hasText(String)` defined in Task 2, consumed in Task 3.
- **No placeholders:** all steps carry exact code/commands/expected output.
