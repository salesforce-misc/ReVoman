# ReVomanUtils Slim Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slim `ReVomanUtils.revUp(ResourceLoader, ...)` template/env path-routing from ~150 lines to ~30 by deleting jar materialization and yaml jar-branch (now handled natively by ReVoman's okio-based v3 FileUtils), while keeping `ResourceLoader` for its only remaining role: dev-mode source-tree override that preserves hot-reload of `*.postman_collection.json` and v3 `*.yaml` fixtures during FTests.

**Architecture:** Single Java file modified in the `core` repo. Replace `partitionByV3Dir` (which built `PartitionedSources` with dual `v3DirPaths` + `streams` lists) with a flat `rewritePaths` helper that returns `List<String>`. Each input path is run through `ResourceLoader.getResource` once: if dev mode resolves it to a `file:` URI, hand the absolute on-disk path to ReVoman so live source edits are read; otherwise pass the classpath path through unchanged so ReVoman's okio `RESOURCES` filesystem (jar-aware) reads jar entries directly. Delete `materializeJarV3DirToTempDir`, `resolveYamlFilePath`, `resolveFileV3DirAbsolutePath`, the `PartitionedSources` record, and the unused imports.

**Tech Stack:** Java 21, Bazel build system, JUnit 5, Vavr (`Try`), Salesforce Core's `ResourceLoader` Spring bean, ReVoman (consumed via Maven coordinate; relies on the v3 FileUtils feature in `feat-v3-reader-util`).

**Spec:** `docs/superpowers/specs/2026-05-27-revomanutils-slim-design.md`

**Repo:** This plan lives in the revoman repo for archival, but **the implementation work happens in `/Users/gopala.akshintala/core-public/core`**, on a branch off core's main integration branch. All file paths below are absolute or rooted at `core-public/core/`.

**Pre-flight check:** Before starting, verify the revoman dependency consumed by core includes the v3 FileUtils feature (`isV3Collection`, `bufferV3Definition`, jar-aware `V3Loader`). Run:

```bash
cd /Users/gopala.akshintala/core-public/core
grep -rn "isV3Collection\|bufferV3Definition" loki-core/test/utils/func/java/ 2>/dev/null
# If revoman version pinned in core pom/Bazel deps does NOT include feat-v3-reader-util,
# pause this plan until the new revoman release is consumed by core.
```

If the dep is not yet available, abort and surface the dependency block to the user.

---

## File Structure

### Modified files

| File | Change |
|---|---|
| `/Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java` | Replace path-routing block (lines 254–428) with a slim `rewritePaths`-based implementation. Delete `partitionByV3Dir`, `materializeJarV3DirToTempDir`, `resolveYamlFilePath`, `resolveFileV3DirAbsolutePath`, `PartitionedSources` record. Update KDoc on the two `revUp(ResourceLoader, ...)` overloads. Remove now-unused imports. |

### New files

None.

---

## Conventions used in this plan

- **Build system:** Bazel. Compile/test commands target the `loki-core` test util module.
- **Java style:** Follows existing file's style. Records, `var` locals, switch-on-string-with-fallback. No comments unless WHY is non-obvious.
- **Commits:** Single commit at the end of Task 6. Tasks 1–5 are non-committal (read/edit/build/test) so the diff lands as one atomic unit per the spec's "single PR" rollout.
- **Verification commands:** Bazel `query` and `build` first to catch compile errors; then targeted FTest runs as a regression net.

---

## Task 1: Confirm the editable region and capture before-state

**Files:**
- Read: `/Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java`

- [ ] **Step 1: Read the full file**

Run:

```bash
wc -l /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java
```

Expected: line count ≥ 428 (current implementation extends to line 428 per the spec). If smaller, the file has changed since the spec was written — re-read it and locate the path-routing block by searching for `partitionByV3Dir`.

- [ ] **Step 2: Confirm the editable region boundaries**

Run:

```bash
grep -n "Both these \`revUp\` methods\|partitionByV3Dir\|PartitionedSources\|materializeJarV3DirToTempDir\|resolveYamlFilePath\|resolveFileV3DirAbsolutePath" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java
```

Expected output: line numbers for each token. The first match (the KDoc starting "Both these `revUp` methods…") marks the start of the editable region; the last `}` of `resolveYamlFilePath` (around line 427 per the spec) marks the end. The file's closing `}` on line 428 is preserved.

- [ ] **Step 3: Confirm no callers depend on the soon-to-be-deleted private helpers**

Run:

```bash
grep -rn "partitionByV3Dir\|PartitionedSources\|materializeJarV3DirToTempDir\|resolveYamlFilePath\|resolveFileV3DirAbsolutePath" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/ /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/test/
```

Expected: matches only inside `ReVomanUtils.java` itself. They are `private static`, so this is a sanity check.

If unexpected hits surface (e.g. a test using one of these via reflection), pause and surface to user before continuing.

- [ ] **Step 4: No commit; this is read-only**

---

## Task 2: Verify the new revoman dependency exposes the v3 FileUtils API to core

**Files:**
- Read-only: core's revoman dependency declaration (Bazel `BUILD.bazel` or Maven equivalent).

- [ ] **Step 1: Find core's revoman version pin**

Run:

```bash
grep -rn "com.salesforce.revoman\|revoman" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/BUILD.bazel /Users/gopala.akshintala/core-public/core/maven_install.json 2>/dev/null | head -20
```

Expected: at least one line referencing the revoman group/artifact and a version. Capture the version.

- [ ] **Step 2: Confirm the pinned version contains `isV3Collection`**

Two ways, pick whichever is faster in your environment:

**Option A — Bazel-resolved jar inspection:**

```bash
bazel query 'kind("jar_import", deps(//loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman:revoman))' 2>/dev/null
```

Then `unzip -l` the resolved revoman jar and grep for `FileUtilsKt.class` containing the new methods.

**Option B — Direct grep on the Maven repository cache:**

```bash
find ~/.m2/repository/com/salesforce/revoman -name '*.jar' 2>/dev/null | xargs -I{} unzip -p {} 'com/salesforce/revoman/input/FileUtils.class' 2>/dev/null | strings | grep -E "isV3Collection|bufferV3Definition" | head -5
```

Expected: matches for `isV3Collection` and `bufferV3Definition`.

If no matches, pause. The revoman dependency consumed by core does not yet include `feat-v3-reader-util`. Surface to user; do not proceed with Task 3+ until the new revoman release is published and consumed by core.

- [ ] **Step 3: No commit; this is verification only**

---

## Task 3: Edit `ReVomanUtils.java` — remove unused imports

**Files:**
- Modify: `/Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java`

This task removes the imports that the new implementation no longer needs. We do imports first because some are tightly co-located in the import block; doing them after the body edits makes the diff harder to read.

- [ ] **Step 1: Remove `java.io.InputStream`**

Edit the import block. Remove this line:

```java
import java.io.InputStream;
```

The file still imports `java.io.File` and `java.io.IOException` — keep those.

- [ ] **Step 2: Remove `java.net.URLDecoder` and `java.nio.charset.StandardCharsets`**

Remove:

```java
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 3: Remove `java.nio.file.Files` and `java.nio.file.StandardCopyOption`**

Remove:

```java
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
```

Keep `java.nio.file.Path` and `java.nio.file.Paths` — both are still used by the new implementation (`Paths.get(p)` to build a `Path` for `ResourceLoader.getResource`).

- [ ] **Step 4: Remove `java.util.ArrayList`**

Remove:

```java
import java.util.ArrayList;
```

The new implementation builds the list via `Stream.toList()`.

- [ ] **Step 5: Verify other imports stay**

These must remain because the body still references them:

```java
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.Nullable;       // can also be removed if no other helper uses it
import io.vavr.control.Try;
import kernel.apis.resourceloading.Resource;
import kernel.apis.resourceloading.ResourceLoader;
import kotlin.collections.ArraysKt;
```

**Verification step for `@Nullable`:** Run `grep -n "@Nullable" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java`. After Task 4 (which removes the helper bodies), if no occurrences remain, also remove `import org.jetbrains.annotations.Nullable;` in Task 5.

- [ ] **Step 6: Compile to confirm import removal didn't break anything yet**

The body still references the soon-to-be-deleted helpers, so compile MUST fail with "cannot find symbol" for things still using them — that's fine. We only want to confirm there is no NEW failure caused by mis-removing an import. Skip this build until Task 5; it's faster to compile once the body is consistent.

- [ ] **Step 7: No commit; intermediate state**

---

## Task 4: Edit `ReVomanUtils.java` — replace the editable region body

**Files:**
- Modify: `/Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java`

This task replaces lines 254–427 (the editable region identified in Task 1) with the slim implementation. The closing `}` of the class on line 428 is preserved.

- [ ] **Step 1: Replace the entire editable region**

Use the Edit tool to replace this block (currently spans the KDoc starting "Both these `revUp` methods…" through the closing `}` of `resolveYamlFilePath`):

```java
    /**
     * Both these `revUp` methods read Template and Environment files via resourceLoader,
     * which help in HotSwap changes for template files
     * *** Recommendation for Core FTests: prefer `ReVomanUtils.revUp()` over `ReVoman.revUp()` ***
     */

    public static List<Rundown> revUp(ResourceLoader resourceLoader, PostExeHook postExeHook, Kick... configs) {
        final var configsWithMixedSources = ArraysKt.map(configs, config -> withMixedSources(resourceLoader, config));
        return ReVoman.revUp(configsWithMixedSources, postExeHook);
    }

    public static Rundown revUp(ResourceLoader resourceLoader, Kick config) {
        return ReVoman.revUp(withMixedSources(resourceLoader, config));
    }

    private static Kick withMixedSources(ResourceLoader resourceLoader, Kick config) {
        final var templates = partitionByV3Dir(resourceLoader, config.templatePaths());
        final var envs = partitionByV3Dir(resourceLoader, config.environmentPaths());
        return config
                .overrideTemplatePaths(templates.v3DirPaths)
                .overrideTemplateInputStreams(templates.streams)
                .overrideEnvironmentPaths(envs.v3DirPaths)
                .overrideEnvironmentInputStreams(envs.streams);
    }

    private record PartitionedSources(List<String> v3DirPaths, List<InputStream> streams) {}

    private static PartitionedSources partitionByV3Dir(ResourceLoader resourceLoader, Collection<String> paths) {
        final List<String> v3DirPaths = new ArrayList<>();
        final List<InputStream> streams = new ArrayList<>();
        for (String path : paths) {
            assertNotNull(path, "File path to load from resources cannot be null");

            // Fast path: try resolving the path directly. If it lands on a `file:` v3 dir,
            // hand the absolute path back to ReVoman (preserves hot-reload).
            final var resource = Try.of(() -> resourceLoader.getResource(Paths.get(path))).get();
            if (resource == null) {
                throw new IllegalStateException("Resource not found on classpath: " + path);
            }
            final var fileV3DirPath = resolveFileV3DirAbsolutePath(resource);
            if (fileV3DirPath != null) {
                v3DirPaths.add(fileV3DirPath);
                continue;
            }

            // Jar-walk path: probe for a v3 marker via Ant pattern, materialize to a temp dir.
            final var jarV3DirPath = materializeJarV3DirToTempDir(resourceLoader, path);
            if (jarV3DirPath != null) {
                v3DirPaths.add(jarV3DirPath);
                continue;
            }

            // YAML single-file: route to ReVoman's path branch which supports YAML via V3EnvLoader/V3YamlReader.
            // The streams branch only handles JSON (Moshi), so YAML envs/templates must take the path branch.
            if (path.endsWith(".yaml") || path.endsWith(".yml")) {
                final var yamlAbsPath = resolveYamlFilePath(resource, path);
                if (yamlAbsPath != null) {
                    v3DirPaths.add(yamlAbsPath);
                    continue;
                }
            }

            // Default: treat as a single-file resource and stream it (v2 collections).
            streams.add(Try.of(resource::newInputStream).get());
        }
        return new PartitionedSources(v3DirPaths, streams);
    }

    /**
     * Returns the absolute on-disk path if the {@link Resource}'s URI is a {@code file:} URL pointing to a directory
     * containing {@code .resources/definition.yaml}; otherwise null.
     *
     * <p>Such paths are handed back to ReVoman as {@code templatePaths}, allowing
     * {@code ReVoman.resolveV3CollectionDir} + {@code V3Loader} to walk the directory tree
     * (preserving hot-reload of yaml edits without server restart).
     *
     * <p>Returns null for jar-backed resources or non-directory resources — those flow through
     * {@link #materializeJarV3DirToTempDir} or the {@code newInputStream()} fallback.
     */
    private static @Nullable String resolveFileV3DirAbsolutePath(Resource resource) {
        final URI uri = resource.toUri();
        if (uri == null || !"file".equals(uri.getScheme())) return null;
        final File file = new File(uri);
        if (!file.isDirectory()) return null;
        final File marker = new File(file, ".resources/definition.yaml");
        return marker.isFile() ? file.getAbsolutePath() : null;
    }

    /**
     * Probes for a jar-backed v3 collection at the given classpath path. If found, extracts every
     * {@code .yaml} resource under the path to a temp directory (preserving relative structure under {@code basePath})
     * and returns the temp directory's absolute path. Returns null if no {@code .resources/definition.yaml} entry
     * exists under {@code basePath}.
     *
     * <p>The temp directory is registered for {@link File#deleteOnExit()} cleanup. Each call materializes a fresh
     * copy — there is no caching across {@code revUp} calls. This trades dev-mode hot-reload semantics for jar-mode
     * compatibility; v2 collections and {@code file:}-backed v3 dirs still take the fast paths above.
     */
    private static @Nullable String materializeJarV3DirToTempDir(ResourceLoader resourceLoader, String basePath) {
        final var normalizedBase = basePath.endsWith("/") ? basePath : basePath + "/";
        final List<Resource> matches;
        try {
            matches = resourceLoader.discoverResources(normalizedBase + "**/*.yaml");
        } catch (IOException e) {
            return null;
        }
        final boolean hasDef = matches.stream()
                .anyMatch(r -> r.getAbsolutePath().endsWith("/.resources/definition.yaml")
                        || r.getAbsolutePath().endsWith("\\.resources\\definition.yaml"));
        if (!hasDef) {
            return null;
        }
        final Path tempDir;
        try {
            tempDir = Files.createTempDirectory("revoman-v3-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temp dir for jar-backed v3 collection: " + basePath, e);
        }
        tempDir.toFile().deleteOnExit();

        for (Resource r : matches) {
            final var rawPath = r.getAbsolutePath();
            final var resPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
            // Trim the basePath prefix so we get the relative entry path under the v3 root
            final int idx = resPath.indexOf(normalizedBase);
            if (idx < 0) continue;
            final String relative = resPath.substring(idx + normalizedBase.length());
            final Path destination = tempDir.resolve(relative);
            try {
                Files.createDirectories(destination.getParent());
                try (InputStream in = r.newInputStream()) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                destination.toFile().deleteOnExit();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to materialize jar-backed v3 entry: " + resPath, e);
            }
        }
        return tempDir.toFile().getAbsolutePath();
    }

    /**
     * Resolves a single yaml file to an absolute on-disk path. Handles both {@code file:} URIs (returned directly)
     * and {@code jar:} URIs (materialized to a temp file with the original extension). Returns null on failure.
     *
     * <p>The path bucket in the Kick (templatePaths/environmentPaths) supports YAML via ReVoman's
     * {@code V3EnvLoader} and {@code V3YamlReader}. The streams bucket does not — Moshi expects JSON.
     */
    private static @Nullable String resolveYamlFilePath(Resource resource, String classpathPath) {
        final URI uri = resource.toUri();
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            try {
                return Paths.get(uri).toAbsolutePath().toString();
            } catch (Exception e) {
                return null;
            }
        }
        // jar-backed: materialize the yaml content to a temp file with the correct extension
        final String suffix = classpathPath.endsWith(".yml") ? ".yml" : ".yaml";
        final Path tempFile;
        try {
            tempFile = Files.createTempFile("revoman-yaml-", suffix);
        } catch (IOException e) {
            return null;
        }
        tempFile.toFile().deleteOnExit();
        try (InputStream in = resource.newInputStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return null;
        }
        return tempFile.toAbsolutePath().toString();
    }
```

with this slim block:

```java
    /**
     * Both overloads route every template and environment path through {@link ResourceLoader}, so that in dev mode
     * (with {@code .bazelproject} source-tree dirs configured) live edits to {@code *.postman_collection.json} and
     * v3 {@code *.yaml} files are visible without restarting the Core App. Jar-backed collections and yaml are read
     * by ReVoman directly via okio; no temp-dir materialization is performed here.
     *
     * <p>Recommendation for Core FTests: prefer {@code ReVomanUtils.revUp(...)} over {@code ReVoman.revUp(...)}.
     */
    public static List<Rundown> revUp(ResourceLoader resourceLoader, PostExeHook postExeHook, Kick... configs) {
        final var configsWithRewrittenPaths = ArraysKt.map(configs, config -> withRewrittenPaths(resourceLoader, config));
        return ReVoman.revUp(configsWithRewrittenPaths, postExeHook);
    }

    public static Rundown revUp(ResourceLoader resourceLoader, Kick config) {
        return ReVoman.revUp(withRewrittenPaths(resourceLoader, config));
    }

    private static Kick withRewrittenPaths(ResourceLoader resourceLoader, Kick config) {
        return config
                .overrideTemplatePaths(rewritePaths(resourceLoader, config.templatePaths()))
                .overrideEnvironmentPaths(rewritePaths(resourceLoader, config.environmentPaths()));
    }

    /**
     * For each input path, returns the on-disk absolute path if {@link ResourceLoader} resolves it to a
     * {@code file:} URI (dev-mode source-tree hit, preserves hot-reload) — otherwise returns the input path unchanged
     * so ReVoman's {@code okio.FileSystem.RESOURCES} reads jar entries directly.
     */
    private static List<String> rewritePaths(ResourceLoader resourceLoader, Collection<String> paths) {
        return paths.stream().map(p -> {
            assertNotNull(p, "File path to load from resources cannot be null");
            final Resource resource = Try.of(() -> resourceLoader.getResource(Paths.get(p))).getOrNull();
            if (resource == null) {
                throw new IllegalStateException("Resource not found on classpath: " + p);
            }
            final URI uri = resource.toUri();
            return (uri != null && "file".equals(uri.getScheme()))
                    ? new File(uri).getAbsolutePath()
                    : p;
        }).toList();
    }
```

Use the Edit tool with the full old_string above as `old_string` and the slim block as `new_string`. The closing `}` on line 428 of the file (class terminator) is OUTSIDE the replaced range and stays untouched.

- [ ] **Step 2: No commit; intermediate state**

---

## Task 5: Edit `ReVomanUtils.java` — clean up imports made unused by Task 4

**Files:**
- Modify: `/Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java`

The body in Task 4 no longer references `IOException`, `Path` (only `Paths.get` is used; `Path` itself is unused), or `@Nullable`. We confirm and remove.

- [ ] **Step 1: Confirm `IOException` is no longer used in the file**

Run:

```bash
grep -n "IOException" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java
```

Expected: no matches OR matches only inside still-present method signatures (none exist after Task 4's slim body — the `memQAwait...` methods declare `InterruptedException`, `TimeoutException`, not `IOException`).

If no matches, remove the import:

```java
import java.io.IOException;
```

If matches exist (e.g. an unrelated method we didn't touch threw `IOException`), keep the import.

- [ ] **Step 2: Confirm `java.nio.file.Path` is no longer used**

Run:

```bash
grep -n "Path " /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java | grep -v "Paths\."
```

Expected: no matches outside `Paths.` qualified usages. If clean, remove:

```java
import java.nio.file.Path;
```

If matches exist (some other code uses `Path` we didn't touch), keep the import.

- [ ] **Step 3: Confirm `@Nullable` is no longer used**

Run:

```bash
grep -n "@Nullable" /Users/gopala.akshintala/core-public/core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java
```

Expected: no matches. If clean, remove:

```java
import org.jetbrains.annotations.Nullable;
```

If any `@Nullable` usage remains in the file (an unrelated method we didn't touch), keep the import.

- [ ] **Step 4: Compile**

```bash
cd /Users/gopala.akshintala/core-public/core
bazel build //loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman:revoman 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL. No `cannot find symbol` errors. No `unused import` warnings (if Bazel is configured to fail on those — many core targets are).

If a `cannot find symbol` for `Path`, `IOException`, or `Nullable` appears, restore that single import and rebuild. (We were defensive in Steps 1–3, but a stray usage may exist.)

If the Bazel target name differs in your local `BUILD.bazel`, locate it via:

```bash
bazel query 'kind("java_library", //loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/...)'
```

and substitute.

- [ ] **Step 5: No commit; intermediate state**

---

## Task 6: Run targeted FTest regression and commit

**Files:**
- None modified directly.

This task picks representative FTest suites covering the four data-flow cells from the spec (v2 dev-mode, v2 jar-mode, v3 dev-mode, v3 jar-mode) and runs them. Concrete suite IDs depend on the local repo state — discover them, run them, then commit the change.

- [ ] **Step 1: Discover candidate FTest suites**

Run:

```bash
cd /Users/gopala.akshintala/core-public/core
grep -rln "ReVomanUtils\.revUp" --include="*.java" loki-core/test/ unified-scheduling-impl/test/ 2>/dev/null | head -20
```

Pick four (or as many as exist):

1. One that uses a v2 collection from a local source dir (dev-mode v2).
2. One that uses a v2 collection from a jar dependency (jar-mode v2). To find: cross-reference each candidate's `templatePaths` with `BUILD.bazel` to see which collection's source lives in another module.
3. One that uses a v3 collection dir locally (dev-mode v3).
4. One that uses a v3 collection dir from a jar dependency (jar-mode v3).

If a category has no FTest available, document the gap in the commit message rather than skipping.

- [ ] **Step 2: Run the picked suites**

For each picked FTest, find its Bazel target and run:

```bash
cd /Users/gopala.akshintala/core-public/core
bazel test //path/to/test:target_name --test_output=errors --test_arg="--test_filter=FQDN.testMethodName"
```

Expected: PASS for each. If any FAIL, capture the failure output and stop — surface to user before committing.

- [ ] **Step 3: Manual hot-reload smoke check (optional but recommended for the PR description)**

If a Core App instance is running locally with dev-mode `.bazelproject` source dirs configured:

1. Pick one v3-using FTest. Run it. Note the request firing or response shape from logs.
2. Edit a `.request.yaml` under that FTest's v3 collection source tree (e.g. change a header value or URL fragment). Save.
3. Re-run the same FTest **without** restarting the Core App. The new value must take effect.

Capture as a one-line note in the PR description: "verified hot-reload still works — edited `<path>` and re-ran without restart".

If no Core App is running, skip this step. The dev-mode-vs-jar-mode regression coverage in Step 2 is the load-bearing check.

- [ ] **Step 4: Commit**

```bash
cd /Users/gopala.akshintala/core-public/core
git checkout -b slim-revomanutils-via-v3-fileutils
git add loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java
git diff --cached --stat
```

Expected: one file changed, roughly `+30 -150` lines.

Commit:

```bash
git commit -m "$(cat <<'EOF'
refactor(revoman-utils): slim path-routing to dev-mode override only

Drops jar materialization (~50 lines) and yaml jar-branch (~30 lines)
from ReVomanUtils.revUp(ResourceLoader, ...). ReVoman now walks v3
collection directories and reads v3 yaml directly via okio (jar-aware
FileSystem.RESOURCES), so no temp-dir extraction is needed for jar-
backed fixtures. ResourceLoader is kept for its only remaining role:
dev-mode source-tree override that exposes live source edits ahead of
the classpath snapshot, preserving hot-reload of *.postman_collection.json
and v3 *.yaml during FTests.

Wrapper shrinks from ~150 lines to ~30. Single PR; no public API change.
EOF
)"
```

- [ ] **Step 5: Push and open the PR per core's process**

The exact PR-creation command depends on core's tooling. If gh CLI is enabled for this repo:

```bash
git push -u origin slim-revomanutils-via-v3-fileutils
gh pr create --title "Slim ReVomanUtils path-routing using ReVoman v3 FileUtils" --body "$(cat <<'EOF'
## Summary
- ReVoman's new okio-based v3 FileUtils (jar-aware `V3Loader` and `isV3Collection`) makes ReVomanUtils' jar materialization redundant.
- This change deletes the jar-walk + yaml temp-file paths in ReVomanUtils, leaving ResourceLoader in place as the dev-mode source-tree override (the only reason it was still needed).
- Wrapper drops from ~150 lines to ~30. No public API change. Hot-reload of `*.postman_collection.json` and v3 `*.yaml` during FTests is preserved.

## Test plan
- [ ] Bazel build of the revoman test util target succeeds
- [ ] FTest using v2 collection from local source (dev-mode v2)
- [ ] FTest using v2 collection from jar dependency (jar-mode v2)
- [ ] FTest using v3 collection dir locally (dev-mode v3)
- [ ] FTest using v3 collection dir from jar dependency (jar-mode v3)
- [ ] Manual: edited a `.request.yaml` mid-run and re-ran without server restart — hot-reload works
EOF
)"
```

If gh CLI is not used, push the branch and follow core's normal PR-creation flow.

---

## Self-review

Cross-checked the spec sections against tasks:

1. **Goal (slim ~150→~30, preserve hot-reload).** Tasks 3–5 do the slimming; Task 4's KDoc + slim `rewritePaths` preserves the dev-mode `file:` URI conversion that hot-reload depends on.
2. **Non-goals.** No ReVoman API change (Tasks 1–6 only touch one Java file in core). No `Kick` API change (Task 4's `withRewrittenPaths` only calls `overrideTemplatePaths` / `overrideEnvironmentPaths`). No `ResourceLoader` deletion.
3. **Architecture diagram.** Task 4's `rewritePaths` body matches the diagram exactly: `getResource` → branch on `file:` vs other → return absolute or pass through.
4. **Components — public surface unchanged.** Task 4 retains both signatures verbatim (`revUp(ResourceLoader, PostExeHook, Kick...)` and `revUp(ResourceLoader, Kick)`).
5. **Components — internal helpers replaced.** Task 4 replaces `partitionByV3Dir` with `rewritePaths` and `withMixedSources` with `withRewrittenPaths`. `PartitionedSources`, `materializeJarV3DirToTempDir`, `resolveYamlFilePath`, `resolveFileV3DirAbsolutePath` deleted.
6. **`withMixedSources` simplification.** Task 4 ships `withRewrittenPaths` matching the spec snippet exactly, with the rename for clarity (`Mixed` no longer fits — there are no streams).
7. **`rewritePaths` core logic.** Task 4 ships the spec's 10-line implementation with the `assertNotNull` null-path guard preserved from the existing code (the spec didn't show this but the original code had it on line 285; defensible to keep).
8. **Imports removed.** Task 3 removes `InputStream`, `Files`, `StandardCopyOption`, `URLDecoder`, `StandardCharsets`, `ArrayList`. Task 5 conditionally removes `IOException`, `Path`, `Nullable` — guarded by grep checks because some other helper in the same file may still use them.
9. **KDoc updated.** Task 4 ships a one-paragraph KDoc on the public overloads naming hot-reload as the rationale.
10. **Error handling.**
    - `getResource` returns null → `IllegalStateException` (Task 4's `rewritePaths`, line guarding `resource == null`).
    - `getResource` throws → propagates via `Try.of(...).getOrNull()` → null → `IllegalStateException` (NB: changed from current `Try.of(...).get()` semantics, where `IOException` would propagate as a vavr `RuntimeException`. Either is acceptable per the spec's "fail fast — wrong path = config bug"; the `getOrNull` form makes the null-path message clearer.)
    - `Resource.toUri()` returns null → falls through to "pass classpath path through" branch.
    - URI scheme neither `file` nor `jar` → falls through to "pass classpath path through" branch.
11. **Path-shape behavior.** All four cells in the spec table (v2 dev/jar, v3 dir dev/jar, env yaml dev/jar, bracketed paths) are covered transitively because the slim wrapper is symmetric in dev-mode-vs-jar-mode and ReVoman handles the rest.
12. **Hot-reload guarantee.** No caching layer added or removed in Task 4. Each `revUp` re-runs `rewritePaths` which re-runs `getResource`, so dev-mode source-tree hits resolve to current source paths every call.
13. **Failure-mode change.** No more temp-dir creation (deleted with `materializeJarV3DirToTempDir`); no more yaml temp-file (deleted with `resolveYamlFilePath`).
14. **Risks.** ResourceLoader staleness and ReVoman semantics drift are out-of-scope mitigations per the spec; not action-required here.
15. **Testing — no new unit tests on ReVomanUtils.** Plan doesn't add any. Existing FTests cover (Task 6 step 2).
16. **Testing — FTest regression net.** Task 6 step 2 explicitly covers the four cells.
17. **Testing — manual hot-reload smoke.** Task 6 step 3 covers it as optional but recommended.
18. **Verification commands.** Task 6 step 2 runs Bazel test targets. Task 5 runs Bazel build.
19. **Rollout — single PR.** Task 6 step 4 commits as one, step 5 opens one PR. Pre-flight check at top of plan covers the revoman dependency dependency.
20. **Out-of-scope.** Bazel resource hot-reload activation, ReVoman SPI for path resolution — not in any task.

**Type / signature consistency:**
- `revUp(ResourceLoader, PostExeHook, Kick...)` — kept in Task 4.
- `revUp(ResourceLoader, Kick)` — kept in Task 4.
- `withRewrittenPaths(ResourceLoader, Kick): Kick` — defined and called in Task 4.
- `rewritePaths(ResourceLoader, Collection<String>): List<String>` — defined and called in Task 4.

**Placeholder scan:** No "TBD", "TODO", "implement later", "fill in details", "appropriate error handling", "Similar to Task N". Two intentional discovery steps (Task 6 step 1 — picking concrete FTest IDs; Task 5 conditional import removal — guarded by grep checks to handle unrelated file content) are spelled out with concrete commands and decision criteria.

**Spec gap check:** None.

---

Plan complete and saved to `docs/superpowers/plans/2026-05-27-revomanutils-slim.md`.

---

## Revision: 2026-05-28 — Status, root-cause discovery, and revoman-side fix

### What actually happened on the first run-through

Tasks 3–6 were executed against revoman 0.9.2, yielding core commit `6d6432a49f65` ("refactor(revoman-utils): slim path-routing to dev-mode override only"). FTests failed in jar-backed v3 mode. A partial revert (`2555a4147495`) re-introduced a focused jar-to-tempdir materializer and the diagnosing message claimed:

> "okio.FileSystem.RESOURCES cannot list directories inside JARs reliably"

This diagnosis was directionally wrong.

### Real root cause

`okio.FileSystem.RESOURCES` is a JVM singleton initialized as:

```kotlin
val RESOURCES: FileSystem = ResourceFileSystem(
  classLoader = ResourceFileSystem::class.java.classLoader,
  indexEagerly = false,
)
```

The classloader is captured at static-init and never reconsidered. In bazel runfiles topologies (and any deployment using a child / sibling classloader for consumer resources — URLClassLoader, OSGi, app servers), `RESOURCES` cannot see the consumer's resources at all because the okio class is loaded by the parent and never sees the child's URLs.

Symptoms manifested as:

1. `isV3Collection(jarPath)` → `RESOURCES.metadataOrNull(p)` returned null → revoman fell into v2 Moshi branch → `bufferFile(jarPath)` → `RESOURCES.source(p)` → `FileNotFoundException: file not found`.
2. Even after the resolver was switched to thread context classloader, jar entry paths arrived percent-encoded (`0%20-%20auth`). NIO ZipFS `getPath` does NOT URL-decode, so spaces in folder names broke the lookup.

### Fix shipped on 2026-05-28 (revoman repo `feat-v3-reader-util`)

Commit `9740d6d`:

- New file `src/main/kotlin/com/salesforce/revoman/input/ClasspathResolver.kt`:
  - `resolveClasspath(path)` — file lookup via `Thread.currentThread().contextClassLoader.getResource(...)`.
  - `resolveClasspathDir(dirPath, sentinelRelPath)` — directory lookup. Tries direct first; falls back to probing `dirPath + sentinelRelPath` because `URLClassLoader.getResource("some/dir")` returns null even when the jar has explicit dir entries.
  - For jar URLs, opens NIO `FileSystems.newFileSystem(jarUri)` and wraps with `okio.FileSystem.Companion.asOkioFileSystem`. Caches NIO `FileSystem` instances per jar URI in a process-wide `ConcurrentHashMap`.
  - Decodes percent-encoded entry segments via `URI.create(s).path` before handing to `getPath` (handles `%20` for spaces, `%5B`/`%5D` for brackets).
- Rewired in:
  - `FileUtils.bufferFile` — uses `resolveClasspath`.
  - `isV3Collection`, `bufferV3Definition` — use `resolveClasspathDir(path, V3_DEFINITION_REL_PATH)`.
  - `V3Loader.load(rootPath: String)` — uses `resolveClasspathDir(rootPath, V3_DEFINITION_REL_PATH)`. Removed private `resolvePath` helper.
- New integration test `src/integrationTest/kotlin/com/salesforce/revoman/integration/jarmode/JarModeRevUpKtTest.kt`:
  - Builds in-memory jar with v3 fixtures (`flat`, `with [brackets]`).
  - Mounts on `URLClassLoader`, sets as thread context CL.
  - Asserts: fixture visible via CCL; `isV3Collection` returns true for jar dir; full `ReVoman.revUp(Kick)` resolves all 3 steps; spaces+brackets path resolves 1 step.
  - Pre-fix: `FileNotFoundException`. Post-fix: 4 tests passing.

Test results post-fix: 105 unit + 19 integration + 4 jar-mode = all green. Zero regressions.

### Core-side application (commit `00080a2244d3` on `t/wfs/revoman-core-fwk`)

`ReVomanUtils.java` re-slimmed to 286 lines:

- Dropped `materializeJarV3DirToTempDir` (re-introduced by `2555a4147495`).
- Restored to the slim shape from `6d6432a49f65`.
- KDoc updated to reference revoman's classpath resolver instead of `okio.FileSystem.RESOURCES`.

End-to-end verification: `unified.scheduling.revoman.UnifiedValidationE2ETest.testAllRulesPositiveCleanSA` passed in 220s with the locally-overridden revoman jar (bazel `--override_repository=com_salesforce_revoman_revoman=...` already wired in core's `.bazelrc-local`).

### Done summary (commits)

| Repo | Branch | Commit | Subject |
|---|---|---|---|
| revoman | `feat-v3-reader-util` | `9740d6d` | fix(v3): resolve classpath via TCCL + NIO ZipFS, not okio.FileSystem.RESOURCES |
| core | `t/wfs/revoman-core-fwk` | `00080a2244d3` | refactor(loki-core): re-slim ReVomanUtils now that ReVoman handles jar v3 dirs |

### Outstanding work

1. Release a new revoman version containing `9740d6d` (currently the local override jar `revoman-0.9.2.jar` is the only build with the fix; the published 0.9.2 on Maven Central does not have it).
2. Bump core's revoman dep to that new version; remove `--override_repository` from `.bazelrc-local` so CI builds pick it up too.
3. Open core PR for the slim ReVomanUtils once the new revoman is consumable.
4. (Optional) Add a unit test in revoman for `resolveClasspathDir` covering: file-system dir, jar dir without explicit dir entry (sentinel fallback), jar dir with spaces (URL decoding), missing path returns null.

