# Remove the `kotlinx-collections-immutable` Fat-Jar Workaround (Design)

**Date:** 2026-08-16
**Status:** Approved in principle, pending written-design review
**Scope:** Stop embedding `kotlinx-collections-immutable` in ReVoman's jar while preserving the
persistent-map optimization and both published and local Salesforce Core consumption.

## Problem

ReVoman currently publishes a hybrid jar: Gradle metadata declares
`kotlinx-collections-immutable-jvm` as an external runtime dependency, while the `jar` task also
unzips the dependency's classes into the ReVoman artifact. The embedding was introduced when
Salesforce Core's Bazel import did not carry ReVoman's transitive runtime dependencies.

That premise is no longer true for published Core consumption. Core's generated ReVoman import
now has an explicit runtime dependency on
`@org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm`. Keeping the embedded copy therefore
double-supplies the same classes. It is especially risky during the current version transition:
this ReVoman checkout uses immutable collections 0.5.1, while the locally inspected Core catalog
still pins 0.4.0 for its published ReVoman 0.9.18 artifact.

The dependency itself is not obsolete. `PersistentBackedMutableMap` uses it to make per-step
environment snapshots structurally shared and O(1), and every `revUp` constructs that adapter.
Removing the normal Gradle dependency or reverting the adapter would discard the performance
improvement and is outside this cleanup.

## Consumer findings

There are two Bazel consumption paths, and both must remain valid:

1. **Published artifact.** Core's generated
   `@com_salesforce_revoman_revoman` import now propagates ordinary dependencies and explicitly
   names immutable collections in `runtime_deps`. This satisfies the removal condition documented
   when the fat-jar workaround was added.
2. **Local repository override.** Core developers can use
   `--override_repository=com_salesforce_revoman_revoman=<local checkout>`. That path evaluates
   this repository's root `BUILD.bazel`, whose `java_import` currently names only the ReVoman jar
   and source jar. Removing the embedded classes without fixing this target would recreate the
   original `NoClassDefFoundError` for local development.

The APIs used by `PersistentBackedMutableMap` have also been exercised with Core's current 0.4.0
artifact: construction, conversion, updates, removal, collection views, and snapshotting all work
with bytecode compiled against 0.5.1. Core should nevertheless regenerate its dependency catalog
when it upgrades ReVoman so the declared runtime version remains aligned rather than relying on
that compatibility indefinitely.

## Decision

Publish a conventional thin ReVoman jar and make the dependency edge explicit at each consumer
boundary.

### Gradle packaging

- Keep `implementation(libs.kotlinx.collections.immutable)`. This is the production dependency
  and publishes the correct Maven POM and Gradle module-metadata runtime edge.
- Delete the custom `bundledRuntime` configuration and its non-transitive dependency declaration.
- Delete the `from(zipTree(...))` copy specification, bundled-artifact metadata exclusions, and
  bundle-specific `DuplicatesStrategy` setting from the `jar` task.
- Keep `Automatic-Module-Name: com.salesforce.revoman`. With no embedded multi-release jar, the
  manifest configuration stands alone and no foreign `module-info.class` exclusions are needed.

### Bazel local override

Add the immutable-collections JVM target to the root `BUILD.bazel` `java_import.runtime_deps`:

```starlark
runtime_deps = [
    "@org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm//:org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm",
]
```

The label is already present in Core's dependency catalog. This repository does not own that
catalog or its version; Core must regenerate it when consuming a ReVoman release whose dependency
version changes.

### Documentation

- Replace `DEVELOPMENT.md`'s fat-jar instructions with the thin-jar consumer contract.
- Record that both the generated published import and this repository's local-override
  `BUILD.bazel` supply the runtime edge.
- Remove the obsolete multi-release-jar exclusion warning while retaining the independent JPMS
  rationale and manifest verification.
- Add a supersession note to the earlier JPMS design so its historical fat-jar implementation
  details are not mistaken for current guidance.

## Verification

1. Build the jar and run the normal unit/ABI gates:

   ```bash
   ./gradlew jar test checkKotlinAbi --rerun-tasks
   ```

2. Confirm the artifact is thin and its stable module name remains intact:

   ```bash
   revoman_jar="$(find build/libs -maxdepth 1 -type f -name 'revoman-*.jar' \
     ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
   test -n "$revoman_jar"
   immutable_entries="$(unzip -Z1 "$revoman_jar" \
     | awk '/^kotlinx\/collections\/immutable\// { count++ } END { print count + 0 }')"
   test "$immutable_entries" -eq 0

   unzip -p "$revoman_jar" META-INF/MANIFEST.MF \
     | grep 'Automatic-Module-Name: com.salesforce.revoman'
   ```

3. Inspect Core's generated published-artifact catalog and confirm ReVoman's `runtime_deps`
   contains `@org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm`. This check must not be
   inferred from a plain query when Core's `.bazelrc-local` enables a repository override, because
   that query resolves the overridden repository instead of the generated catalog.

4. From Core, query the local-override path explicitly after replacing `/path/to/revoman`:

   ```bash
   bazel query \
     --override_repository=com_salesforce_revoman_revoman=/path/to/revoman \
     'somepath(
       @com_salesforce_revoman_revoman//:com_salesforce_revoman_revoman,
       @org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm//:org_jetbrains_kotlinx_kotlinx_collections_immutable_jvm
     )'
   ```

   The output must include both targets, proving this repository's root `BUILD.bazel` supplies the
   runtime edge for local development.

Qodana remains the required pre-push static-analysis gate, but no push is part of this cleanup.

## Rejected alternatives

- **Delete only the Gradle bundle.** Published Core would work, but Core's local repository
  override would fail because the root `BUILD.bazel` currently has no replacement runtime edge.
- **Keep the bundle.** This preserves an obsolete workaround and leaves duplicate, potentially
  version-skewed classes on Maven/Gradle and current Core classpaths.
- **Remove `kotlinx-collections-immutable` entirely.** This would require redesigning
  `PersistentBackedMutableMap` and revalidating the snapshot-performance contract; it is not a
  packaging cleanup.
- **Hand-edit Core's generated pinned catalog from this change.** The catalog is generated and
  belongs to a separate repository. Version alignment should happen through Core's graph-tool
  workflow when its ReVoman artifact is upgraded.

## Out of scope

- Changing `PersistentBackedMutableMap` or environment-snapshot semantics.
- Updating or publishing a ReVoman version.
- Editing Core's generated dependency catalog.
- Starting or testing a full Core server.
