# JPMS for ReVoman — `Automatic-Module-Name` (Design)

**Date:** 2026-08-01
**Status:** Approved (design), pending implementation
**Scope:** One manifest attribute on the `jar` task + a documentation note. No `module-info.java`.

## Problem

Can the Java Platform Module System (JPMS) be leveraged for the ReVoman library? The
motivating goals were: (1) real runtime encapsulation of the `internal` package, (2) being a
good modular citizen for module-path consumers, and (3) an explicit, self-documenting
dependency surface.

## Feasibility findings

An investigation of the codebase and dependency graph established the following facts, which
decide how far JPMS can realistically go here.

1. **The primary consumer is on the classpath, not the module path.** Salesforce Core consumes
   ReVoman as a prebuilt jar through a bazel `java_import`, which places it on the **classpath**
   → the *unnamed module*. A `module-info.class` on the classpath is **ignored entirely**. So a
   full `module-info` would provide runtime encapsulation to essentially none of ReVoman's
   primary consumption path.

2. **Heavy reflection into ReVoman's own types.** ~59 reflection touchpoints across ~33 source
   files. Moshi (moshix codegen) adapters, kapt/Immutables, and Spring `BeanUtils` all reflect
   *into* `revoman` classes — spanning `input`, `output`, **and** `internal`. A real
   `module-info` would therefore need broad `opens` directives, **including opening
   `...internal`**, which defeats the encapsulation that was goal (1).

3. **The dependency graph is not module-ready.** Roughly half the runtime dependencies are
   **plain jars with no `Automatic-Module-Name`** (http4k, moshi, snakeyaml, underscore, pprint,
   kotlinx-collections-immutable, kotlin-logging). On the module path these resolve to automatic
   modules with **filename-derived names** — unstable across versions. Writing `requires` clauses
   against those is exactly the fragility the JPMS spec warns against; a clean explicit
   `module-info` cannot sit on top of a non-modular dependency set.

4. **One thing already works in our favor.** The `jar` task bundles
   `kotlinx-collections-immutable`'s classes into the revoman jar (Core-consumption fat-jar fix),
   so revoman *owns* the `kotlinx.collections.immutable` package — no split-package hazard on the
   module path.

### Conclusion

Full JPMS (`module-info.java`) and multi-release modular jars both spend significant, ongoing
effort to encapsulate against a module path that ReVoman's biggest consumer does not use, while
the reflection surface forces re-opening `internal` anyway. The one JPMS benefit ReVoman *can*
collect cheaply and risk-free is a **stable module name** for module-path consumers. That is
`Automatic-Module-Name`.

## Decision

Add a single manifest attribute to the published jar:

```
Automatic-Module-Name: com.salesforce.revoman
```

- **Module name:** `com.salesforce.revoman` — matches `GROUP_ID` (`buildSrc/.../Config.kt`) and
  the source-package root. Reverse-DNS, collision-safe, stable across releases (replaces the
  fragile filename-derived name a plain jar otherwise gets on the module path).

## Implementation

Single change, in the existing `tasks.named<Jar>("jar")` block in `build.gradle.kts` (the block
that already bundles `kotlinx-collections-immutable`):

```kotlin
tasks.named<Jar>("jar") {
  manifest {
    attributes("Automatic-Module-Name" to "com.salesforce.revoman")
  }
  from({ bundledRuntime.map { zipTree(it) } }) {
    // Drop the bundled artifact's own MANIFEST/module metadata — keep only its classes so the
    // revoman jar's manifest and any module-info stay authoritative.
    exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "module-info.class")
  }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

### Interaction with the fat-jar bundling

The attribute is set on **revoman's own** manifest object. The `from({ bundledRuntime... })` copy
already **excludes** the bundled artifact's `META-INF/MANIFEST.MF` and `module-info.class`
(line 81), so revoman's manifest stays authoritative — the two changes compose without
interference, order-independent. The bundled `kotlinx-collections-immutable` artifact carries no
`Automatic-Module-Name` of its own, so there is nothing to leak or conflict.

## What this does and does not do

- ✅ **Good modular citizen:** module-path consumers can `requires com.salesforce.revoman;`
  against a *stable* name.
- ➖ **Runtime encapsulation of `internal`:** none — same as today. JPMS is the wrong tool for
  this given the classpath consumption model (see "Rejected alternatives" and "Out of scope").
- ➖ **Explicit dependency surface:** none — deliberately, to avoid coupling to non-modular deps.
- ✅ **Zero risk to the primary consumer:** the attribute is inert on the classpath, so Core's
  `java_import` build path is completely unaffected. Behavior is byte-identical on the classpath.

## Verification

1. Attribute present:
   ```bash
   ./gradlew jar --rerun-tasks
   unzip -p build/libs/revoman-*.jar META-INF/MANIFEST.MF | grep 'Automatic-Module-Name'
   # expect: Automatic-Module-Name: com.salesforce.revoman
   ```
2. Fat-jar bundle still intact (regression guard on the DEVELOPMENT.md invariant):
   ```bash
   unzip -l build/libs/revoman-*.jar | grep -c 'kotlinx/collections/immutable'   # expect ~130
   unzip -l build/libs/revoman-*.jar | grep -c 'module-info.class'               # expect 0
   ```
3. Module resolution smoke check:
   ```bash
   jar --describe-module --file build/libs/revoman-*.jar
   # expect: com.salesforce.revoman automatic
   ```
4. No unit/integration test changes — classpath behavior is unchanged; Core build path unaffected.

## Documentation

Add a short note to `DEVELOPMENT.md` near the existing jar/manifest section recording **why** it
is `Automatic-Module-Name` and **not** a full `module-info` — so a future reader does not
"upgrade" it to explicit JPMS and hit the reflection / classpath-consumer walls documented above.

## Rejected alternatives

- **B — Full explicit `module-info.java`.** Would need `opens ...internal` for
  moshi/spring/kapt reflection (self-defeating for encapsulation); fragile `requires` against
  filename-derived names of non-modular deps; known Kotlin + kapt + moshix + JPMS friction; and
  still gives Core nothing. Days of work plus an ongoing per-dep-bump maintenance tax.
- **C — Multi-release "modular jar"** (`module-info` under `META-INF/versions/`). Same
  encapsulation/dependency problems as B, plus MR-jar build complexity, and the payoff still
  evaporates because Core is on the classpath. Strictly worse than the chosen option here.

## Out of scope (possible future track)

If runtime hiding of `internal` becomes a real requirement, JPMS is not the fit for a
classpath-consumed library. A better-fit approach would be an API-surface gate that works on the
classpath — e.g. a separate published API artifact, or a Kotlin `@RequiresOptIn` / `@PublishedApi`
discipline. Not pursued in this change.
