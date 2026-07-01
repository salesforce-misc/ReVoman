# Multi-kick `revUp` — pass all env value types across kicks

**Date:** 2026-07-01
**Status:** Approved, pending implementation

## Problem

The multi-kick fold in `ReVoman.revUp` (`src/main/kotlin/com/salesforce/revoman/ReVoman.kt:85-98`)
threads the environment from one kick into the next so that values produced by kick N are visible
to kick N+1. It does this incorrectly for non-String values:

```kotlin
kicks
  .fold(dynamicEnvironment to listOf<Rundown>()) { (accumulatedMutableEnv, rundowns), kick ->
    val rundown =
      revUp(kick.overrideDynamicEnvironment(kick.dynamicEnvironment() + accumulatedMutableEnv))
    val accumulatedRundowns = rundowns + rundown
    postExeHook.accept(rundown, accumulatedRundowns)
    rundown.mutableEnv.mutableEnvCopyWithValuesOfType<String>() to accumulatedRundowns  // line 96
  }
  .second
```

Two String-only chokepoints drop every non-String value between kicks:

1. **Line 96** reduces `rundown.mutableEnv` (a `PostmanEnvironment<Any?>`) to only its `String`
   entries via `mutableEnvCopyWithValuesOfType<String>()`. Any `Int`, POJO, `List`, etc. that an
   earlier kick produced is silently discarded before the next kick runs.
2. **Lines 79 & 88** — the public multi-kick seed param `dynamicEnvironment: Map<String, String>`
   is String-only.

This is inconsistent with the rest of the pipeline, which already carries `Any?`:

- `KickDef.dynamicEnvironment(): Map<String, Any?>` (the single-kick path is already type-preserving)
- `overrideDynamicEnvironment(Map<String, ? extends Object>)` (generated Kick API)
- `rundown.mutableEnv: PostmanEnvironment<Any?>`

The multi-kick fold is the lone String-only regression.

## Change

Three edits, all in `ReVoman.kt`; no other production files change.

1. `revUp(vararg)` overload (line 79): `dynamicEnvironment: Map<String, String>` → `Map<String, Any?>`
2. `revUp(List)` overload (line 88): same widening
3. Line 96: `rundown.mutableEnv.mutableEnvCopyWithValuesOfType<String>()` → `rundown.mutableEnv.immutableEnv`

`immutableEnv` (defined on `PostmanEnvironment` as `mutableEnv.toMap()`) returns an immutable,
all-value-types snapshot of the env. Using it here preserves every produced value's type across
kicks, needs no new helper, and matches the existing snapshot idiom. The fold accumulator's type
widens to `Map<String, Any?>`, inferred from the widened seed param. `overrideDynamicEnvironment`
and `dynamicEnvironment() + accumulatedMutableEnv` already accept `Any?`, so no downstream edits are
required.

## Compatibility

Widening `Map<String, String>` → `Map<String, Any?>` breaks no caller:

- Kotlin `Map<K, out V>` is covariant in `V`, so a `Map<String, String>` argument still satisfies a
  `Map<String, Any?>` parameter.
- Kotlin exposes the widened param to Java as `Map<String, ?>`, which accepts any `Map<String, X>`.

Existing String-only callers keep compiling and behave identically — String values were never the
ones being dropped, so they continue to thread through unchanged.

## Error handling

No new error paths — this is pure type-widening. Null values already flow through the pipeline
(`PokemonTest` carries a `null`-valued key through a multi-kick run); both `immutableEnv` and `Any?`
preserve nulls.

## Testing (TDD)

New Kotlin E2E test (`src/test`):

- **Non-String value threads across kicks (red → green):** two kicks where kick 1 produces a
  non-String value (e.g. an `Int` seeded via `dynamicEnvironment` or set via `pm.environment.set`),
  asserting that kick 2 receives it as the **same type** — not stringified, not dropped. Fails on
  the current `<String>` filter, passes after the fix.
- **String regression:** String values still thread through kick → kick unchanged.

Existing coverage that must stay green:

- `PokemonTest` multi-kick `overrideDynamicEnvironment` assertions (integrationTest).
- `KickTest` / `V3EnvLoaderTest` `dynamicEnvironment` seed behavior.

## Files touched

- `src/main/kotlin/com/salesforce/revoman/ReVoman.kt` — the 3 edits above.
- New test file under `src/test/kotlin/com/salesforce/revoman/` — multi-kick env type-preservation.
