# ReVoman Performance Improvements — Design

**Date:** 2026-07-13
**Status:** Approved (design), pending implementation plan
**Author:** perf audit (5 parallel subagent audits) + brainstorming

## Goal

Apply the concrete, FP-preserving performance improvements surfaced by a 5-domain
parallel code audit of the ReVoman library, **without mangling the functional
programming style** (the deliberate `Either`/`map`/`flatMap`/immutable-flow design
from `STYLE.md`). Every change either preserves the FP style or improves it.

## Non-goals

- No de-functionalizing clean pipelines for micro-perf.
- No unrelated refactoring.
- No behavior change visible to consumers (except the intentional `truffle-runtime`
  classpath addition and measured latency reduction).

## Constraints verified during brainstorming

- `graal-js = org.graalvm.js:js-language` only — no optimizing Truffle runtime on
  the classpath today ⇒ GraalJS runs interpreter-only (~10× slower per eval).
- `arrow-core` is present, but Arrow 2.x **dropped persistent collections** — so the
  persistent-map fix genuinely requires a new `kotlinx.collections.immutable`
  dependency.
- No JMH / benchmark infrastructure exists yet.
- Single git worktree today (`master`).
- Collision files across fixes: `PostmanSDK.kt`, `ReVoman.kt`, `Template.kt`,
  `RegexReplacer.kt`, `PostmanEnvironment.kt` — partition assigns each to exactly one
  worktree.

## User decisions (brainstorming forks)

1. **Scope:** ALL tiers, including the high-risk Tier 5 (`pmEnvSnapshot` persistent map).
2. **Verification:** full JMH benchmark module (new source set + Gradle plugin).
3. **Graal dependency:** add `truffle-runtime` as an `api` dependency — every
   consumer of ReVoman inherits the optimizing runtime (jargraal) by default.

---

## Fix inventory (17 fixes, 5 domains)

Deduplicated across the 5 audits. Each fix is FP-preserving; risk noted where relevant.

### Domain A — GraalJS sandbox (`WT-1`)

| # | File:line | Fix | Risk |
|---|-----------|-----|------|
| A1 | `build.gradle.kts:33` / `libs.versions.toml:60` | Add `org.graalvm.truffle:truffle-runtime:25.1.3` (version.ref = existing `graal`) as an `api` dep alongside the existing `js-language` — chosen over the `js-community` meta-artifact, which would redundantly re-pull `js-language`. jargraal engages on stock JDK 21. Remove now-unneeded `WarnInterpreterOnly=false` suppressions (`SandboxBridge.kt:50`, `PostmanSDK.kt:130`) **only if** the interpreter warning no longer fires; otherwise keep them. | dep size (accepted) |
| A2 | `SandboxBridge.kt:45` + `PostmanSDK.kt:132` | Introduce ONE shared immutable `Engine` (top-level `object`/lazy val), pass to both `Context.newBuilder(...).engine(shared)`. Keep `Context` **per-run** — never share Context (guest-state bleed). Reuses parsed 2.2 MB bootcode Source across runs + across the two contexts. | state-bleed if Context shared — mitigate by sharing Engine only |
| A3 | `PostmanSDK.kt:226` | `jsonStrToObj`: memoize the `JSON.parse` arrow closure as `by lazy` `Value`, reuse. Stateless guest fn. | none |
| A4 | `PostmanSDK.kt:146` | `evaluateJS`: hoist constant `imports` prefix; optional small memo `Map<String,Value>` for repeated identical scripts. | none |

### Domain B — JSON marshalling (`WT-2`)

| # | File:line | Fix | Risk |
|---|-----------|-----|------|
| B1 | `CaseInsensitiveEnumAdapter.kt:41,47` | Cache `private val enumConstants = enumType.enumConstants` (avoid `getEnumConstants()` array clone per enum parse). | none |
| B2 | `EpochAdapter.kt:23` | Hoist `"\d+".toRegex()` to an `object`-level `val` (or `epoch.isNotEmpty() && epoch.all(Char::isDigit)`). | none |
| B3 | `DiMorphicAdapter.kt:35` | `private val labelOptions = Options.of(labelKey)` — build Okio options once, not per composite element. | none |
| B4 | `MoshiReVoman.kt:101` | `objToJsonStrToObj`: replace string round-trip with `fromJsonValue(toJsonValue(x))` value tree. | number-typing may differ — verify `PostmanEnvironment` tests |
| B5 | `JsonPojoUtils.kt:39,79,121` | Memoize a default `MoshiReVoman` for the empty-config case; build fresh only for non-default configs. | none |
| B6 | `Template.kt:77` | Prefer `JsonPretty.pretty()` (whitespace-only, precision-safe) for comment-free bodies; keep the Moshi round-trip only to strip JSON5 comments (gate on `containsComments`). | comment-stripping nuance — verify request-marshal tests |

### Domain C — regex / templates / vars (`WT-3`)

| # | File:line | Fix | Risk |
|---|-----------|-----|------|
| C1 | `RegexReplacer.kt:41` | `{{` fast-path guard: `if (!it.contains("{{")) return@let it` before the regex scan. Byte-for-byte identical (pattern can't match without `{{`). Also discounts the per-step env rescan. | none |
| C2 | `RegexReplacer.kt:135` | `replaceVariablesInEnv`: only remap entries whose key/value contains `{{`; pass static entries through. Keep `.toMap()` snapshot (write-back requires it). | verify no test relies on per-step type round-trip |
| C3 | `V3YamlReader.kt:120` | Reuse one `Yaml` (SnakeYAML) instance for reading (walk is sequential; `ThreadLocal` if ever parallelized). Cold path. | none |
| C4 | `V3Loader.kt:66,83` | `walk`: materialize `metadataOrNull` once per child, then partition (avoid 2× stat per entry). Cold path. | none |
| C5 | `V3Loader.kt:108` + `DynamicVariableGenerator.kt:65,69` | Replace `String.format`-based hex/sha encoding with JDK 21 `HexFormat`; `randomAlphanumeric` via `CharArray`. | none |

### Domain D — exe engine + output + persistent env (`WT-4`)

| # | File:line | Fix | Risk |
|---|-----------|-----|------|
| D1 | `HttpRequest.kt:40,62` + `Polling.kt:43` | Memoize one Apache HTTP client per variant (secure/insecure) via `by lazy`; stop building a fresh pooled client + discarding it per request. Auth is per-`Request`, so sharing is safe. Also fixes the per-run client leak. | none |
| D2 | `PreStepHook.kt:46` + `PostStepHook.kt:47` | Materialize the picked-hooks `Sequence` to a `List` once (tiny lists); use `.size`/`.isNotEmpty()` — predicates run once, not 3–4×. | none (improves FP) |
| D3 | `UnmarshallResponse.kt:35,49` | Hoist `val body = httpResponse.bodyString()` + `contentType` — decode body once, not twice. | none |
| D4 | `PostmanEnvironment.kt:36-37,46,64,74` + `PostmanSDK.kt:96` | Key the 6 `Step`-keyed maps by `step.path` (String) instead of the whole `Step` (whose `hashCode` recurses through body + all JS scripts). `path` is the existing unique identity. | none |
| D5 | `TxnInfo.kt:71` | `containsHeader`: `httpMsg.header(key) != null` instead of `headers.toMap().containsKey`. | none |
| D6 | `PostmanEnvironment.kt:214` | Single-prefix `valuesForKeysStartingWith` delegates to the sequence-based vararg sibling (kill double-pass). | none |

### Domain E — high-effort (split across WT-1 and WT-4)

| # | File:line | Fix | Risk | Worktree |
|---|-----------|-----|------|----------|
| E1 | `PostmanSDK.kt:157` (`syncProgress` ONLY) | `syncProgress`: **replace** the current step's report instead of appending it 3×/step (kills 3 of 4 O(M) copies per step + the duplicate current-step entries). Keep `Rundown` immutable at the boundary. **Scoped strictly to `PostmanSDK.kt`** to preserve disjoint worktree ownership — the 4th copy (the `ReVoman.kt:402` seed `stepReportsSoFar + preStepReport`) is left as-is in this pass; fully eliminating it would touch `ReVoman.kt` (WT-4) and is deferred to avoid cross-worktree collision. | hook-visible `Rundown` shape — verify behavior | WT-1 (owns `PostmanSDK.kt`) |
| E2 | `ReVoman.kt:498` + `StepReport.kt:152,171` + `PostmanEnvironment.kt:27` | Back the env with a **persistent map** (`kotlinx.collections.immutable`) so each `pmEnvSnapshot` is O(1) structural-share instead of a full O(E) copy per step (O(M·E) → O(M)). Adjust `setItBackInEnvironment` write path. | most invasive — heavy test-verification burden | WT-4 (owns `ReVoman.kt`, `PostmanEnvironment.kt`, `StepReport.kt`) |

---

## Worktree partition (disjoint file ownership)

Each source file is owned by exactly ONE worktree ⇒ conflict-free parallel merges.

```
WT-0  foundation  — lands FIRST, others branch off it
   libs.versions.toml   (+truffle-runtime, +jmh, +kotlinx-collections-immutable)
   build.gradle.kts     (api truffle-runtime, impl kotlinx.immutable, jmh plugin + sourceSet)
   new JMH benchmark source set + component benchmarks + baseline capture

WT-1  sandbox / GraalJS            [depends on WT-0 deps]   fixes A2,A3,A4,E1
   SandboxBridge.kt · PmJsEval.kt · PostmanSDK.kt (entire file — incl. syncProgress E1)

WT-2  JSON marshalling             [independent]            fixes B1..B6
   CaseInsensitiveEnumAdapter.kt · EpochAdapter.kt · DiMorphicAdapter.kt
   MoshiReVoman.kt · JsonPojoUtils.kt · Template.kt

WT-3  regex / templates / vars     [independent]            fixes C1..C5
   RegexReplacer.kt · V3YamlReader.kt · V3Loader.kt · DynamicVariableGenerator.kt

WT-4  exe engine + output + env    [depends on WT-0 kotlinx.immutable]  fixes D1..D6,E2
   ReVoman.kt · HttpRequest.kt · Polling.kt · PreStepHook.kt · PostStepHook.kt
   UnmarshallResponse.kt · PostmanEnvironment.kt · TxnInfo.kt · StepReport.kt
```

**No file appears in two worktrees.** `PostmanSDK.kt` → WT-1 only. `ReVoman.kt` →
WT-4 only. The shared GraalJS `Engine` is a top-level `object` val requiring no
`ReVoman.kt` edit. `Template.kt` → WT-2 only. `RegexReplacer.kt` → WT-3 only.
`PostmanEnvironment.kt` → WT-4 only.

**A1** (the Truffle dependency) lands in WT-0, so WT-1's Engine work builds against it.

### Ordering

1. **WT-0 first** — deps + JMH module + baseline must exist before anything else.
2. **WT-1 / WT-2 / WT-3 / WT-4 fan out in parallel**, each branched from WT-0.
3. Merge order after green: WT-2, WT-3 (independent) → WT-1, WT-4 (dep on WT-0).

---

## Verification

### JMH benchmarks (component-level, offline, repeatable)

Full end-to-end collection runs need live network/orgs (Pokemon, restfulapidev,
apigee) or a Salesforce org (core WFS/PQ) — not JMH-repeatable. So benchmark the
**isolated hot paths** instead, which is exactly where the audit found the wins:

- **Regex/vars:** `RegexReplacer.replaceVariablesRecursively` over a mix of
  placeholder / no-placeholder strings; `replaceVariablesInEnv` over a large env.
- **Marshalling:** `MoshiReVoman.fromJson`/`toJson` over a representative Salesforce
  composite response (enums + dates + polymorphic elements) — exercises B1/B2/B3.
- **GraalJS:** eval a representative Postman test script through the sandbox N times
  (measures A1 interpreter→JIT + A2 Engine reuse + A3 `json()`).
- **Env accumulation:** simulate M steps of `set` + `pmEnvSnapshot` to measure
  D4 (Step-key) + E2 (persistent map) O(M·E) → O(M).

Optional stretch: a mock-server (http4k) end-to-end benchmark for D1 (client reuse).

### Baseline + gates

- Capture baseline on `master` in WT-0 before any fix.
- Re-run the relevant domain benchmark after each worktree lands; record deltas.
- **Correctness gate (every worktree, non-negotiable):** `./gradlew test integrationTest`
  green. Highest burden on WT-1 (Engine state-bleed) and WT-4 (persistent map).

---

## Execution model

Per `/subagent-driven-development` + `/dispatching-parallel-agents`:

- One implementation subagent per worktree; each receives its file-set, exact fix
  list (from the inventory above), and the "run tests, report diff + benchmark delta"
  contract.
- Navigation/refactor via the `intellij-index` MCP (`/ide-index-mcp`) — e.g. the D4
  `Step`-key→`path` change uses find-references + rename, not grep.
- Test failures → `/systematic-debugging` + `jetbrains-debugger` MCP.
- Each worktree externalizes progress to a ledger so a reset is safe.

## Risks (summary)

| Risk | Mitigation |
|------|------------|
| GraalJS Engine sharing → guest-state bleed | Share Engine ONLY; Context stays per-run. Test gate catches bleed. |
| Persistent-map rewrite of `PostmanEnvironment` (most invasive) | Isolate in WT-4; full `test integrationTest` gate; incremental — get maps green before swapping the backing store. |
| `objToJsonStrToObj` value-tree number-typing (B4) | Verify against existing `PostmanEnvironment` tests before merge. |
| `Rundown` replace-not-append changes hook-visible state (E1) | Verify hooks that read `rundown.stepReports` still see correct per-step view. |
| `truffle-runtime` `api` grows every consumer's artifact | Accepted by user decision; benchmark confirms the win justifies it. |
```