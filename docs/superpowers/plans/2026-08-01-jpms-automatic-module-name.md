# JPMS Automatic-Module-Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the published `revoman` jar a stable JPMS module name via an `Automatic-Module-Name` manifest attribute, so module-path consumers get a stable name at zero risk to classpath consumers.

**Architecture:** Add one manifest attribute inside the existing `tasks.named<Jar>("jar")` block in `build.gradle.kts`. No `module-info.java`. No `requires`/`exports`/`opens`. The attribute is inert on the classpath, so Salesforce Core (bazel `java_import`) is unaffected. A documentation note in `DEVELOPMENT.md` records *why* this is `Automatic-Module-Name` and not full JPMS.

**Tech Stack:** Kotlin/Gradle (Gradle Kotlin DSL), JDK 21, `jar`/`unzip` CLI for verification.

**Spec:** `docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md`

## Global Constraints

- JDK 21+ required for the build (`export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn` or any JDK 21).
- Module name MUST be exactly `com.salesforce.revoman` (matches `GROUP_ID` in `buildSrc/src/main/kotlin/Config.kt` and the source-package root).
- MUST NOT touch classpath behavior — no change to what classes ship, no `module-info.class` in the jar.
- MUST preserve the fat-jar invariant: `kotlinx-collections-immutable` classes stay bundled (~130 classes), and revoman's own manifest stays authoritative (bundled `META-INF/MANIFEST.MF` / `module-info.class` remain excluded).
- Format with `./gradlew spotlessApply` before any build that runs `spotlessCheck`.
- NEVER use hard wraps when writing Markdown (global rule); this project's `.md`/`.adoc` are spotless-managed — do not introduce trailing whitespace.

---

### Task 1: Add `Automatic-Module-Name` to the jar manifest

**Files:**
- Modify: `build.gradle.kts` (the `tasks.named<Jar>("jar")` block, currently at lines 77-84)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a built `build/libs/revoman-<version>.jar` whose `META-INF/MANIFEST.MF` contains `Automatic-Module-Name: com.salesforce.revoman`. Later docs task references this attribute name and value verbatim.

The current block looks exactly like this:

```kotlin
tasks.named<Jar>("jar") {
  from({ bundledRuntime.map { zipTree(it) } }) {
    // Drop the bundled artifact's own MANIFEST/module metadata — keep only its classes so the
    // revoman jar's manifest and any module-info stay authoritative.
    exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "module-info.class")
  }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

- [ ] **Step 1: Write the failing verification (build the current jar, confirm the attribute is ABSENT)**

Run:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-amzn
./gradlew jar -x detekt -x test --rerun-tasks
unzip -p build/libs/revoman-*.jar META-INF/MANIFEST.MF | grep 'Automatic-Module-Name' || echo "ATTRIBUTE ABSENT (expected before change)"
```

Expected: prints `ATTRIBUTE ABSENT (expected before change)` — the attribute does not exist yet.

- [ ] **Step 2: Add the manifest attribute**

Edit `build.gradle.kts` — add a `manifest { ... }` call as the first statement inside the `jar` block, so it becomes:

```kotlin
tasks.named<Jar>("jar") {
  // Stable JPMS module name for module-path consumers. Deliberately NOT a full module-info: the
  // primary consumer (Salesforce Core) uses a classpath java_import where module-info is ignored,
  // and moshi/spring/kapt reflect into revoman's own types (would force opening `internal`). See
  // docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md.
  manifest { attributes("Automatic-Module-Name" to "com.salesforce.revoman") }
  from({ bundledRuntime.map { zipTree(it) } }) {
    // Drop the bundled artifact's own MANIFEST/module metadata — keep only its classes so the
    // revoman jar's manifest and any module-info stay authoritative.
    exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module", "module-info.class")
  }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

- [ ] **Step 3: Format**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL (formats the edited `build.gradle.kts` if needed).

- [ ] **Step 4: Rebuild and verify the attribute is now PRESENT**

Run:

```bash
./gradlew jar -x detekt -x test --rerun-tasks
unzip -p build/libs/revoman-*.jar META-INF/MANIFEST.MF | grep 'Automatic-Module-Name'
```

Expected: prints `Automatic-Module-Name: com.salesforce.revoman`.

- [ ] **Step 5: Verify the fat-jar invariant is intact (regression guard)**

Run:

```bash
echo "immutable classes: $(unzip -l build/libs/revoman-*.jar | grep -c 'kotlinx/collections/immutable')"
echo "module-info.class: $(unzip -l build/libs/revoman-*.jar | grep -c 'module-info.class')"
```

Expected: `immutable classes:` is a non-zero count (~130); `module-info.class:` is `0`.

- [ ] **Step 6: Verify module resolution reports the stable automatic name**

Run: `jar --describe-module --file $(ls build/libs/revoman-*.jar | grep -v sources | head -1)`
Expected: output includes `com.salesforce.revoman automatic` (the `automatic` keyword confirms it resolved from the manifest attribute, not a filename).

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts
git commit -m "build(jar): set Automatic-Module-Name to com.salesforce.revoman

Gives module-path consumers a stable JPMS module name instead of the fragile
filename-derived one. Inert on the classpath, so Core's java_import path is
unaffected. Deliberately not a full module-info — see the design spec."
```

---

### Task 2: Document why it's Automatic-Module-Name, not full JPMS

**Files:**
- Modify: `DEVELOPMENT.md` (insert a new subsection after the "The kotlinx-collections-immutable fat-jar bundle" section, which currently ends at line 109 — immediately before the `### How Core picks up a locally-built jar` heading at line 111)

**Interfaces:**
- Consumes: the attribute name/value `Automatic-Module-Name: com.salesforce.revoman` produced by Task 1.
- Produces: nothing consumed by later tasks (final task).

- [ ] **Step 1: Insert the documentation subsection**

In `DEVELOPMENT.md`, immediately before the line `### How Core picks up a locally-built jar`, insert this new section (with a blank line separating it from the fat-jar section above and the heading below):

```markdown
### JPMS module name (`Automatic-Module-Name`)

The `jar` task stamps `Automatic-Module-Name: com.salesforce.revoman` into the manifest (see
`build.gradle.kts`). This is a **stable JPMS module name** for consumers on the Java module path —
nothing more. It is deliberately **NOT** a full `module-info.java`, and it should stay that way:

- **Core doesn't see it anyway.** Core consumes revoman via a bazel `java_import` → the
  **classpath** → the *unnamed module*, where a `module-info.class` is ignored entirely. Full
  JPMS would encapsulate against a module path Core never uses.
- **Reflection would force opening `internal`.** Moshi (moshix codegen), kapt/Immutables, and
  Spring `BeanUtils` reflect into revoman's own types across `input`, `output`, **and**
  `internal`. A real `module-info` would need broad `opens` — including `opens ...internal` —
  defeating the encapsulation that would be the only reason to add it.
- **Deps aren't module-ready.** Several runtime deps (http4k, moshi, snakeyaml, underscore,
  pprint, kotlinx-collections-immutable, kotlin-logging) are plain jars with no
  `Automatic-Module-Name`, so `requires` clauses would bind to fragile filename-derived names.

Rationale and rejected alternatives (full `module-info`, multi-release modular jar) are recorded
in `docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md`. Verify the attribute
after building:

\`\`\`bash
unzip -p build/libs/revoman-*.jar META-INF/MANIFEST.MF | grep 'Automatic-Module-Name'
# → Automatic-Module-Name: com.salesforce.revoman
\`\`\`
```

Note: in the actual file, the three backtick-fenced lines above use plain triple backticks (they are shown escaped here only to nest inside this plan's code block).

- [ ] **Step 2: Format the docs**

Run: `./gradlew spotlessApply`
Expected: BUILD SUCCESSFUL (the `documentation` spotless target trims trailing whitespace / ensures final newline on `*.md`).

- [ ] **Step 3: Verify the section reads correctly and links resolve**

Run:

```bash
grep -n 'JPMS module name' DEVELOPMENT.md
test -f docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md && echo "spec link target exists"
```

Expected: the `grep` prints the new heading line; prints `spec link target exists`.

- [ ] **Step 4: Commit**

```bash
git add DEVELOPMENT.md
git commit -m "docs(dev): explain Automatic-Module-Name choice over full JPMS

Records why revoman ships a stable module name only, not a module-info:
Core consumes via classpath java_import, reflection forces opening internal,
and deps are non-modular. Points at the design spec."
```

---

## Self-Review

**1. Spec coverage:**
- Decision (add `Automatic-Module-Name: com.salesforce.revoman`) → Task 1.
- Implementation (manifest attr in the `jar` block, composes with fat-jar exclude) → Task 1 Step 2.
- Verification (attribute present, fat-jar intact, `jar --describe-module`) → Task 1 Steps 4-6.
- Documentation (why AMN not module-info, in DEVELOPMENT.md) → Task 2.
- "No unit/integration test changes" → honored; no test tasks, classpath behavior unchanged.
- Rejected alternatives / out-of-scope → captured in the spec, referenced from the DEVELOPMENT.md note; no code task needed.
- No gaps.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to Task N". Every code and command step shows exact content. Clean.

**3. Type consistency:** No code types introduced. The single string constant `com.salesforce.revoman` and attribute key `Automatic-Module-Name` are identical across the spec, Task 1, and Task 2. Consistent.
