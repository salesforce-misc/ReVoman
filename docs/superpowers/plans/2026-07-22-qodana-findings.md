# Qodana Critical+High Findings — 2026-07-22

Scan: `./gradlew qodanaScan`, community linter `jetbrains/qodana-jvm-community:2026.1`,
profile `qodana.recommended`. **20 problems total: 9 High, 11 Moderate.**
Per the design's fix-scope choice, we target the **9 High**. The 11 Moderate are out of scope
this pass (listed at the bottom for reference).

## Reality vs. plan assumption

The plan templated a large multi-category parallel-worktree fan-out. Actual High findings are
**4 trivial one-file Kotlin cleanups + 5 Gradle-build-DSL incubating-API notices**. The
worktree-per-category machinery is oversized for 4 one-liners — see the checkpoint decision.

---

## Category A — `UnstableApiUsage` (5 hits) — NOT code-fixable

All in Gradle build scripts, from intentionally using Gradle's `@Incubating` APIs:

- `build.gradle.kts:88` — `useJUnitJupiter(String)` on `JvmTestSuite` (unstable)
- `build.gradle.kts:88` — `JvmTestSuite` type marked `@Incubating`
- `build.gradle.kts:90` — `JvmTestSuite` type marked `@Incubating`
- `build.gradle.kts:91` — `dependencies(Action)` on `JvmTestSuite` (unstable)
- `settings.gradle.kts:36` — `repositories(Action)` marked `@Incubating`

**Why no code fix:** these are the modern Gradle test-suite / dependency-resolution DSLs. There
is no behavior-preserving rewrite — the "fix" would be to stop using the DSL. This is not a
defect; it is an intentional API choice.

**Proposed resolution (needs human call):** exclude the `UnstableApiUsage` inspection for
`*.gradle.kts` in `qodana.yaml`. This is a project-level config decision (scoping an inspection
away from build scripts), distinct from an inline `@Suppress` to silence a finding in product
code. Alternative: leave as-is and accept 5 standing High notices on build scripts.

---

## Category B — real Kotlin cleanups (4 hits) — one file each, trivial

### B1. `RedundantNullableReturnType`
- `src/main/kotlin/com/salesforce/revoman/input/json/adapters/salesforce/CompositeResponse.kt:69`
- `override fun fromJson(reader: JsonReader): Record?` always returns non-null → return type can
  be `Record`. **Caution:** `JsonAdapter.fromJson` is an overridable Java API declared
  `@Nullable T`; narrowing the Kotlin override to non-null must not break the `JsonAdapter<Record>`
  contract or callers that expect nullability. Verify the override still compiles against Moshi's
  signature; if narrowing fights the supertype, leave it and record as a false positive.

### B2. `RedundantSamConstructor`
- `src/main/kotlin/com/salesforce/revoman/input/config/Runbook.kt:75`
- `step(intent, phase, kick, Consumer {})` — the `Consumer {}` SAM wrapper is redundant; the
  lambda can be passed directly. Pure simplification, no behavior change. Confirm the target
  param is a functional interface that accepts the lambda directly.

### B3. `RedundantUpperBound` (`Any?`)
- `src/main/kotlin/com/salesforce/revoman/output/postman/PostmanEnvironment.kt:25`
- `data class PostmanEnvironment<ValueT : Any?>` — `: Any?` is the default bound, so it's
  redundant → `<ValueT>`. Trivial, no behavior change.

### B4. `JavaDefaultMethodsNotOverriddenByDelegation` — SUBTLE, needs care
- `src/main/kotlin/com/salesforce/revoman/output/RunbookRundown.kt:26`
- `class RunbookRundown(...) : List<Rundown> by rundownsView` — Kotlin interface delegation does
  NOT forward Java `default` methods (e.g. `List.getFirst()`/`getLast()`/`reversed()` added in
  Java 21, `stream()`, `spliterator()`). Callers hitting those go through the default impl, not
  `rundownsView`'s override. This can be a **latent correctness bug**, not just style. Assess
  whether any Java default `List` method matters here; if so, override + delegate explicitly. If
  the class is only ever iterated (never calls a Java default), document why it's safe. Do NOT
  blanket-suppress without that assessment.

---

## Checkpoint decision needed (before Phase B)

1. **Fan-out shape:** 4 one-file fixes is too small for 4 git worktrees + 4 reviewer round-trips.
   Options: (a) drop worktrees, fix the 4 inline as one small task with review; (b) keep a light
   parallel fan-out (e.g. B4 alone in a worktree given its subtlety, B1–B3 together); (c) full
   worktree-per-finding as originally planned.
2. **Category A:** exclude `UnstableApiUsage` for `*.gradle.kts` in qodana.yaml, or leave as
   standing notices?

---

## Out of scope this pass — 11 Moderate (reference only)
- `MultiDollarInterpolation` (4), `RedundantUnnecessaryTypeArgument` (4),
  `LiftReturnOrAssignment`/when-simplification (1), `SimplifiableCallChain` (1),
  `MoveVariableDeclarationIntoWhen` (1).
