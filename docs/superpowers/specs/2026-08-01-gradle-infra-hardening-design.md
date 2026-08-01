# Gradle Infra Hardening — buildSrc pluginManagement + Kover coverage gate

Date: 2026-08-01
Status: Approved (design)
Scope: Two focused build-infra fixes. Larger findings (wrapper RC pin, wrapper
checksum, CI action `@main` floats) were surfaced during brainstorming and
deferred — NOT in this spec.

## Context

The ReVoman build is a mature single-module Gradle setup (Gradle wrapper,
`buildSrc` convention plugins, version catalog). Two issues found during an infra
review are worth fixing now:

1. `buildSrc/settings.gradle.kts` places `pluginManagement { ... }` **inside**
   `dependencyResolutionManagement { ... }`. That is structurally wrong —
   `pluginManagement` is a top-level settings block evaluated before the rest of
   settings — and it is functionally dead: buildSrc's only plugin is the bundled
   `` `kotlin-dsl` ``, which never resolves from the plugin portal, so the Nexus
   workspace-proxy fallback in that misplaced block never fires.

2. Kover produces an HTML coverage report on `check` but enforces **no floor**,
   while `AGENTS.md` mandates "all new code covered." Nothing stops coverage
   silently regressing.

## Measured facts (from a throwaway spike, since reverted)

The floor value and the exclusion question were settled by measurement, not
assumption:

| Config | Line coverage |
| --- | --- |
| No exclusions (current) | **69.8133%** |
| Exclude generated `*JsonAdapter` / `*Immutable*` classes | **69.8133%** (byte-identical) |
| Exclude whole `com.salesforce.revoman.internal` package (sanity probe) | 61.4659% |

Conclusions:

- The Kover `filters { excludes { ... } }` DSL **works** (the `internal` probe
  moved the number), so a zero-effect result is real, not a silent no-op.
- Excluding generated Moshi/Immutables code changes coverage by **0.0000%** —
  those classes are already exercised by the marshalling tests, so removing them
  from the denominator does not move the ratio. Exclusions add config for no
  measured benefit → **dropped**.
- Honest line coverage is **69.8%**. An 80% `minBound` would fail
  `./gradlew build` on the first run (CI red, local red, release script red), so
  80% is a **future target reached by ratcheting**, not the initial gate value.

## Decisions

- **buildSrc pluginManagement:** hoist the block to a valid top-level position
  (sibling of `dependencyResolutionManagement`), preserving the workspace-proxy
  safety net and mirroring the root `settings.gradle.kts`. (Chosen over deleting
  it: keeps symmetry + the documented proxy fallback for any future portal plugin
  in buildSrc.)
- **Kover floor:** `minBound(69)` on total **line** coverage, wired into `check`.
  A regression ratchet just below today's 69.8% — absorbs normal churn/branch
  noise without false reds. Raise it over time toward the 80% goal as tests land.
- **Exclusions:** none. Plain floor against the current total report.

## Change 1 — buildSrc/settings.gradle.kts (hoist pluginManagement)

Current (abridged) structure:

```kotlin
dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }

  pluginManagement {           // <-- misplaced: nested inside dependencyResolutionManagement
    repositories { ... nexus fallback ... }
  }
}
```

Target structure — two sibling top-level blocks:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    // Workspace fallback: resolve Gradle plugins from the internal Nexus mirror when
    // plugins.gradle.org is unreachable behind the SFDC proxy. Fully driven by the nexus*
    // Gradle properties (URL + credentials), so it is a no-op on CI / other machines
    // (nothing checked in).
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

Note the copyright header at the top of the file stays; only the two management
blocks are restructured.

### Verification for Change 1

- `./gradlew :buildSrc:help --warning-mode all` — no settings-ordering warning
  (there is none today either; the point is the fix does not introduce one).
- `./gradlew build` still resolves buildSrc's convention-plugin deps
  (`kotlin-gradle`, `spotless`, `detekt`, `testLogger`) exactly as before — the
  hoist changes structure, not resolved artifacts.

## Change 2 — build.gradle.kts (Kover verify rule)

Replace the current one-liner at `build.gradle.kts` (the `kover { ... }` line):

```kotlin
kover { reports { total { html { onCheck = true } } } }
```

with:

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

`koverVerify` already exists as a task (Kover 0.9.9) and `total.verify` binds it
into `check` automatically — no extra `check.dependsOn` wiring needed. The default
`minBound` metric is line coverage, which matches the 69.8% baseline number.

### Verification for Change 2

- `./gradlew koverVerify` — passes at floor 69 against the 69.8% baseline.
- Negative check: temporarily set `minBound(75)`, run `./gradlew koverVerify`,
  confirm it FAILS with a bound-violation message; revert to 69.
- `./gradlew build` — green end-to-end (verify runs under `check`).

## Out of scope (deferred, tracked here only)

- Gradle wrapper pinned to `9.7.0-rc-2` → move to latest stable + add
  `distributionSha256Sum`.
- CI actions float on `@main` (`checkout`, `setup-java`, `setup-gradle`,
  `upload-artifact`) → pin to SHA/major tag.
- Pre-release dependency audit (Kotlin Beta2, arrow alpha, detekt alpha, assertj
  M1, jsonassert rc1).

## Rollback

Both changes are single-file, revertible in isolation:

- Change 1: restore the nested block in `buildSrc/settings.gradle.kts`.
- Change 2: restore the `kover { reports { total { html { onCheck = true } } } }`
  one-liner. If the floor ever wedges an unrelated build, dropping the `verify`
  rule (or lowering `minBound`) is a one-line change.
