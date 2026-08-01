# Gradle Infra Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the misplaced/dead `pluginManagement` block in buildSrc settings and add a Kover line-coverage regression gate wired into `check`.

**Architecture:** Two independent single-file build-infra edits. Task 1 restructures `buildSrc/settings.gradle.kts` (hoist `pluginManagement` to top level). Task 2 replaces the one-line `kover {}` config in `build.gradle.kts` with a `verify` rule at `minBound(69)`. Neither touches product code.

**Tech Stack:** Gradle 9.7 (Kotlin DSL), `buildSrc` convention plugins, Kover 0.9.9, version catalog.

## Global Constraints

- JDK 21 toolchain (`jvmToolchain(21)`) — do NOT run the build under a newer JDK (detekt breaks on 25). Use `$HOME/.sdkman/candidates/java/21.0.10-amzn` or any JDK 21.
- Run `./gradlew spotlessApply` before any build that runs `spotlessCheck`, else the build fails on formatting.
- Kover baseline is **69.8133% line coverage** (measured). The floor is a regression ratchet at `minBound(69)` — line coverage, not branch.
- No coverage exclusions — a spike proved generated-code (`*JsonAdapter`/`*Immutable*`) exclusions change coverage by 0.0000%.
- Preserve the copyright header at the top of every file you edit.
- Both edits are formatted with `ktfmt().googleStyle()` (2-space indent) via spotless.

---

### Task 1: Hoist pluginManagement in buildSrc settings

**Files:**
- Modify: `buildSrc/settings.gradle.kts` (entire management-block structure)

**Interfaces:**
- Consumes: nothing (settings script, no upstream task).
- Produces: nothing consumed by Task 2 — the two tasks are independent.

**Context:** The current file nests `pluginManagement { repositories { ... } }` INSIDE `dependencyResolutionManagement { ... }`. `pluginManagement` must be a top-level settings block. The nested `pluginManagement` is also dead (buildSrc's only plugin is the bundled `` `kotlin-dsl` ``, which never resolves from the portal), but hoisting keeps the workspace-proxy fallback valid and mirrors the root `settings.gradle.kts`. This is a settings-file structural change — there is no unit test; verification is that the build still resolves buildSrc deps and Gradle emits no settings-ordering warning.

- [ ] **Step 1: Read the current file to capture the exact block contents**

Run: `cat buildSrc/settings.gradle.kts`
Note the copyright header and the exact `pluginManagement { repositories { ... } }` contents nested inside `dependencyResolutionManagement`.

- [ ] **Step 2: Replace the whole management structure with two top-level sibling blocks**

Overwrite the file body (keep the existing copyright header block verbatim at the top) so it reads:

```kotlin
/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    // Workspace fallback: resolve Gradle plugins from the internal Nexus mirror when
    // plugins.gradle.org is unreachable behind the SFDC proxy. Fully driven by the nexus* Gradle
    // properties (URL + credentials), so it is a no-op on CI / other machines (nothing checked in).
    val nexusUrl: String? = providers.gradleProperty("nexusGradlePluginsUrl").orNull
    val nexusUser: String? = providers.gradleProperty("nexusUsername").orNull
    val nexusPass: String? = providers.gradleProperty("nexusPassword").orNull
    if (nexusUrl != null && nexusUser != null && nexusPass != null) {
      maven {
        name = "nexusGradlePlugins"
        url = uri(nexusUrl)
        credentials {
          username = nexusUser
          password = nexusPass
        }
      }
    }
  }
}

dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
```

- [ ] **Step 3: Format**

Run: `./gradlew spotlessApply`
Expected: `BUILD SUCCESSFUL`; `buildSrc/settings.gradle.kts` unchanged by ktfmt (already google-style) or reformatted with no semantic change.

- [ ] **Step 4: Verify buildSrc still resolves + no settings-ordering warning**

Run: `./gradlew :buildSrc:help --warning-mode all`
Expected: `BUILD SUCCESSFUL`, no warning mentioning `pluginManagement`, `dependencyResolutionManagement`, or block ordering.

- [ ] **Step 5: Verify the whole build still configures and compiles**

Run: `./gradlew classes`
Expected: `BUILD SUCCESSFUL` — buildSrc convention-plugin deps (`kotlin-gradle`, `spotless`, `detekt`, `testLogger`) resolve exactly as before (the hoist changes structure, not resolved artifacts).

- [ ] **Step 6: Commit**

```bash
git add buildSrc/settings.gradle.kts
git commit -m "build(buildSrc): hoist pluginManagement to a valid top-level settings block"
```

---

### Task 2: Add Kover line-coverage gate

**Files:**
- Modify: `build.gradle.kts` (the single `kover { reports { total { html { onCheck = true } } } }` line)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: a `koverVerify` rule bound into `check`.

**Context:** Kover 0.9.9 already registers a `koverVerify` task; declaring `total.verify` binds it into `check` automatically (no manual `check.dependsOn`). Default `minBound` metric is line coverage, matching the 69.8% baseline. Floor is `69` — a ratchet just below baseline. There is no new test file; verification is running `koverVerify` (green at 69, and a negative check proving it fails when the floor is raised above baseline).

- [ ] **Step 1: Confirm the current baseline (records the pre-change number)**

Run: `./gradlew test koverLog`
Expected: output line `application line coverage: 69.8133%`, `BUILD SUCCESSFUL`.

- [ ] **Step 2: Replace the one-line kover config with the verify rule**

Find this line in `build.gradle.kts`:

```kotlin
kover { reports { total { html { onCheck = true } } } }
```

Replace it with:

```kotlin
kover {
  reports {
    total {
      html { onCheck = true }
      // Coverage regression ratchet. Baseline line coverage is ~69.8% (measured); this floor
      // sits just below it so normal churn/branch noise doesn't false-fail the build. Raise
      // `minBound` over time toward the 80% goal as tests are added. Wired into `check`, so
      // `./gradlew build` (local + CI) enforces it.
      verify {
        rule {
          minBound(69) // total LINE coverage %
        }
      }
    }
  }
}
```

- [ ] **Step 3: Format**

Run: `./gradlew spotlessApply`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify the gate passes at the baseline**

Run: `./gradlew test koverVerify`
Expected: `BUILD SUCCESSFUL` (69.8% ≥ 69 floor).

- [ ] **Step 5: Negative check — prove the gate actually bites**

Temporarily change `minBound(69)` to `minBound(75)` in `build.gradle.kts`, then run:

Run: `./gradlew test koverVerify`
Expected: `BUILD FAILED` with a Kover rule-violation message (line coverage 69.81% < 75).

Then revert the value back to `minBound(69)` and re-run:

Run: `./gradlew test koverVerify`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the gate is wired into the full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` — `koverVerify` runs under `check` with no extra wiring. (Uses the default local config: retries off, `integration.core.*` excluded.)

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts
git commit -m "build(kover): add line-coverage regression gate at minBound(69)"
```

---

## Self-Review

**Spec coverage:**
- Change 1 (hoist pluginManagement) → Task 1. ✓
- Change 2 (Kover verify rule, `minBound(69)`, wired into check) → Task 2. ✓
- Decision "no exclusions" → encoded in Global Constraints + Task 2 has no `filters` block. ✓
- Decision "line not branch" → stated in Global Constraints + Task 2 comment. ✓
- Out-of-scope items (wrapper RC, checksum, CI `@main`, pre-release deps) → correctly NOT in any task. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases". Every code step shows full code. ✓

**Type consistency:** `minBound(69)` used consistently (Task 2 Step 2, Step 5 revert, Global Constraints). Task-1 block contents match the spec verbatim. ✓
