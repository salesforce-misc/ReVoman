# ReVomanUtils Slim Design — Lean on V3 FileUtils, Keep ResourceLoader

**Date:** 2026-05-27
**Branch context:** revoman `feat-v3-reader-util` (v3 FileUtils feature) is implemented. Consumer cleanup happens in core repo against the new ReVoman.

**Status:** Approved (Option A — keep `ResourceLoader`, slim wrapper)

---

## Goal

Reduce `ReVomanUtils.revUp(ResourceLoader, ...)` template/env path-routing from ~150 lines to ~30 by leaning on ReVoman's new okio-based v3 detection (`isV3Collection`, `bufferV3Definition`, jar-aware `V3Loader`). Preserve dev-mode hot-reload of `*.postman_collection.json` and v3 `*.yaml` files via `ResourceLoader`'s source-tree override.

## Non-goals

- Adding a public extension API in ReVoman (e.g. a `pathResolver` SPI). Considered and rejected — premature abstraction for `n=1` consumer.
- Deleting `kernel.apis.resourceloading.ResourceLoader` or its callers. ResourceLoader is load-bearing for the broader Core App, not just FTests; out of scope here regardless.
- Changing ReVoman public API. The `feat-v3-reader-util` branch already shipped what was needed; this design only changes a Core consumer.
- Reworking `Kick.overrideTemplateInputStreams` / `overrideEnvironmentInputStreams`. They remain in the Kick API for other consumers; this consumer simply stops using them.

---

## Background

### Why hot-reload matters

FTest engineers iterate on Postman fixtures (`*.postman_collection.json`, v3 `*.yaml`) without restarting the local Core App. ResourceLoader is what makes this work: in dev mode, it searches user-configured source-tree directories (resolved from `.bazelproject` `directories:` block via `BazelProjectBaseDirectorySupplier`) **before** the classpath. The default JVM classloader sees `target/classes/` snapshots only; resource files under `test/.../resources/...` are not auto-copied into `target/classes/` on every edit.

### Why `ReVomanUtils` exists today

Three reasons, of which only the first remains valid after the v3 FileUtils feature:

1. **Dev-mode source-tree override.** Translate dev-mode-resolved `file:` URIs to absolute paths so ReVoman reads live source instead of the classpath snapshot. **Still needed.**
2. **Jar-backed v3 collection materialization.** Walk `classpath:**/*.yaml` Ant patterns, copy each match to a temp dir, hand the temp dir to ReVoman. **No longer needed** — ReVoman walks jar entries via `okio.FileSystem.RESOURCES` natively.
3. **Jar-backed yaml file materialization for environment paths.** Copy a single `jar:` yaml to a temp file. **No longer needed** — ReVoman reads jar yaml via okio natively.

### Why Option A over Option B (resolver SPI)

Considered adding `Kick.pathResolver: (String) -> String?` so ReVoman could call into a consumer-provided resolver. Rejected:

- **Premature abstraction.** No other ReVoman consumer has expressed need.
- **Wrong coupling direction.** ReVoman would gain a hook whose only purpose is letting Core's `ResourceLoader` influence path resolution. ReVoman shouldn't know that concept exists.
- **Net LoC barely changes.** The dev-mode-override work moves from Core into a resolver impl in Core — same code, different file. The SPI surface is pure overhead.
- **Reversibility.** If A turns out limiting, can promote to B later. If B ships first and the SPI design is wrong, removing/changing public API is breaking.

---

## Architecture

**Single-responsibility split:**

- **ReVoman (revoman repo, untouched in this work).** Handles all classpath, jar, filesystem, v2, v3, yaml, and v3-collection-dir cases through `okio.FileSystem.RESOURCES` / `SYSTEM`. Already implemented on `feat-v3-reader-util`.
- **`ResourceLoader` (core, untouched).** Spring-bean dev-mode source-tree resolver.
- **`ReVomanUtils.revUp(ResourceLoader, ...)` (core, simplified).** Single role: rewrite each input path to its on-disk absolute path **iff** dev mode resolves it to a `file:` URI; otherwise pass the classpath path through unchanged. ReVoman handles the rest.

**Data flow per path:**

```
caller path "pm-templates/v3/foo"
   │
   ▼
ResourceLoader.getResource(path)
   │
   ├─ file: URI (dev mode, source tree)  ──▶  absolute path on disk
   │                                            │
   └─ jar:/other URI (jar mode)          ──▶  original classpath path
                                                │
                                                ▼
                              ReVoman.revUp templatePaths / environmentPaths
                              (okio resolves SYSTEM for absolute, RESOURCES for relative)
```

ReVoman's existing string-keyed dispatch (`isV3Collection(path)` → `V3Loader.load(path)` else `bufferFile(path)` → Moshi v2) handles every artifact type for both branches.

### Definition of "dev mode"

"Dev mode" = Core App runtime configuration in which `ResourceLoader` searches user-configured source-tree directories **before** the classpath. Activated by the FTest engineer's `.eclipse/.bazelproject` `directories:` block resolved against `coreHome` via `BazelProjectBaseDirectorySupplier` and consumed by `CoreResourceLoaderImpl`. When dev mode is off (production / CI Bazel runs / IDE without `.bazelproject` source dirs), `ResourceLoader.getResource` returns a `jar:` URI and the wrapper passes the classpath path through unchanged.

---

## Components

### File touched

`core/loki-core/test/utils/func/java/src/org/revcloud/loki/core/testutils/revoman/ReVomanUtils.java` only.

### Public surface (unchanged signatures)

```java
public static List<Rundown> revUp(ResourceLoader rl, PostExeHook postExeHook, Kick... configs);
public static Rundown      revUp(ResourceLoader rl, Kick config);
```

### Internal helpers — replaced

| Removed | Replacement |
|---|---|
| `partitionByV3Dir(rl, paths)` returning `PartitionedSources` | `rewritePaths(rl, paths)` returning `List<String>` |
| `record PartitionedSources(List<String> v3DirPaths, List<InputStream> streams)` | gone — no streams branch needed |
| `materializeJarV3DirToTempDir(rl, basePath)` (~50 lines) | gone — ReVoman walks jar v3 via `okio.RESOURCES` |
| `resolveYamlFilePath(resource, classpathPath)` (~30 lines) | gone — ReVoman reads jar yaml via `okio.RESOURCES` |
| `resolveFileV3DirAbsolutePath(resource)` (v3-marker check) | replaced by inline `file:`-URI conversion (no marker check; ReVoman does that) |

### `withMixedSources` simplification

```java
private static Kick withMixedSources(ResourceLoader rl, Kick config) {
    return config
        .overrideTemplatePaths(rewritePaths(rl, config.templatePaths()))
        .overrideEnvironmentPaths(rewritePaths(rl, config.environmentPaths()));
}
```

No more `overrideTemplateInputStreams` / `overrideEnvironmentInputStreams` calls. Those Kick overrides remain in the ReVoman API for other consumers — we just stop using them here.

### `rewritePaths` core logic (~10 lines)

```java
private static List<String> rewritePaths(ResourceLoader rl, Collection<String> paths) {
    return paths.stream().map(p -> {
        Resource r = Try.of(() -> rl.getResource(Paths.get(p))).getOrNull();
        if (r == null) {
            throw new IllegalStateException("Resource not found on classpath: " + p);
        }
        URI uri = r.toUri();
        return (uri != null && "file".equals(uri.getScheme()))
            ? new File(uri).getAbsolutePath()
            : p;
    }).toList();
}
```

### Imports removed

`InputStream`, `Files`, `StandardCopyOption`, `URLDecoder`, `StandardCharsets`, `IOException` (if no other use), `ArrayList`. Net: ~7 imports gone. Kept: `URI`, `File`, `Paths`, `Collection`, `List`, `Resource`, `ResourceLoader`, `Kick`, `Try`, `ArraysKt`, `ReVoman`.

### KDoc

The `revUp(ResourceLoader, ...)` overloads keep a one-sentence KDoc that names dev-mode hot-reload as the reason this overload exists. Replace the existing two-paragraph KDoc above lines 254–267.

---

## Error Handling and Edge Cases

### Resolution failures

| Case | Behavior |
|---|---|
| `ResourceLoader.getResource(p)` returns null | Throw `IllegalStateException("Resource not found on classpath: " + p)`. Same as today. Fail fast — wrong path = config bug. |
| `ResourceLoader.getResource(p)` throws `IOException` | Wrap via existing `Try.of(...)` semantics → propagates as `RuntimeException`. Same as today. |
| `Resource.toUri()` returns null | Pass the classpath path through unchanged. ReVoman's `okio.RESOURCES` will surface a clearer error if the path is not on the classpath. |
| URI scheme is neither `file` nor `jar` (e.g. `bundle:`, custom) | Pass the classpath path through. okio handles standard schemes; non-standard fail at read time with okio's error. |

### Path-shape behavior

| Input | Dev-mode (file:) result | Jar-mode result |
|---|---|---|
| `pm-templates/v2/foo.postman_collection.json` | absolute path → ReVoman reads via `SYSTEM` | classpath → reads via `RESOURCES` |
| `pm-templates/v3/flat` (v3 dir with `.resources/definition.yaml`) | absolute path → `isV3Collection` true → V3Loader walks `SYSTEM` | classpath → `isV3Collection` true → V3Loader walks `RESOURCES` (jar entries) |
| `envs/foo.yaml` (v3 env) | absolute path → V3EnvLoader reads via `SYSTEM` | classpath → V3EnvLoader reads via `RESOURCES` |
| Path with `[brackets]` or spaces | `okio.Path` handles both (already covered by V3LoaderTest fixtures) | same |

### Failure-mode change vs today

- **Today:** jar-backed v3 → materialize to temp dir → ReVoman reads temp dir. Each `revUp` call creates a fresh temp tree (no caching). `deleteOnExit` accumulates entries across long FTest runs.
- **New:** jar-backed v3 → ReVoman reads jar entries directly via `okio.FileSystem.RESOURCES`. No temp dir. No filesystem writes. No `deleteOnExit` registrations.

### Hot-reload guarantee

Whenever `getResource` returns `file:`, the absolute path goes to ReVoman, which reads via `SYSTEM` on every `revUp` call — i.e. each FTest invocation re-reads the live file. No caching layer is added or removed.

### Risks

- **`ResourceLoader` returns a stale-but-`file:` URI.** Only happens if user's `.bazelproject` points at a build output dir instead of source. Out of scope; ResourceLoader behavior unchanged.
- **Future ReVoman semantics change.** `templatePaths` / `environmentPaths` resolution semantics are now contract-tested in `V3LoaderTest`, `V3LoaderJarTest`, and `FileUtilsTest` (per the v3 FileUtils plan). The slim wrapper rides on those guarantees.

---

## Testing

### No new unit tests in this consumer change

`ReVomanUtils` has no dedicated unit tests today. Existing FTests (hundreds across `loki-core`, `unified-scheduling-impl`, etc.) are the integration coverage. Reasons not to add unit tests on `rewritePaths` itself:

1. The utility lives downstream of `ResourceLoader` (Spring bean) and ReVoman, both of which already have their own coverage. Mocking `ResourceLoader` to test ~10 lines of path rewriting is more boilerplate than value.
2. Existing FTests are the only environment in which `ResourceLoader` is realistically wired.

### FTest suites that act as the regression net

| Suite (representative) | What it proves |
|---|---|
| FTest using v2 `*.postman_collection.json` with `revUp(rl, ...)` in dev mode | v2 dev-mode path: `file:` → absolute → `SYSTEM` read |
| FTest using v2 collection from a jar dependency (cross-module) | v2 jar-mode path: classpath → `RESOURCES` read |
| FTest using v3 collection dir locally | v3 dev-mode: `file:` → absolute → V3Loader walks live dir |
| FTest using v3 collection dir from jar dependency | v3 jar-mode: classpath → V3Loader walks jar entries |
| FTest with v3 `.yaml` env in `environmentPaths` | v3 env: both branches |

Concrete suite IDs to be picked during implementation. Candidates from the existing repo:

- A `loki-core` FTest using a v3 unified-scheduling fixture.
- A cross-module FTest using a v2 collection shipped in a jar dependency.

### Manual hot-reload smoke test (one-time, documented in PR)

1. Engineer starts the Core App locally with dev-mode `.bazelproject` source dirs configured.
2. Run a v3-using FTest. Note current behavior.
3. Edit a `*.request.yaml` in source tree without restarting the server.
4. Re-run the FTest. New behavior must be applied without server restart. Capture in PR description as a one-line "verified hot-reload still works" note.

### Verification commands

To be embedded in the implementation plan:

- `bazel test //loki-core/test/...` (or the equivalent FTest target in core's build config).
- Spot-check: one v2-via-jar FTest exercising a cross-module collection.

---

## Rollout

Single PR in `core` repo against the next core integration target. Depends on:

- The `feat-v3-reader-util` branch in revoman repo being merged and a release of revoman containing it being available to core's dependency graph.

If the released revoman containing v3 FileUtils is not yet consumable by core when this work begins, hold the core PR until the dependency lands.

---

## Out-of-scope follow-ups

- Removing `ResourceLoader` from Core entirely — depends on Bazel resource hot-reload being enabled across the relevant test targets, which is a separate workstream.
- Adding pluggable path resolution to ReVoman as a public SPI — only revisit if a second consumer surfaces the same need.
