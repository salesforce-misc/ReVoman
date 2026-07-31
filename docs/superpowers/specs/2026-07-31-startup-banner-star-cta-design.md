# Startup Banner + Shutdown Star CTA — Design

**Date:** 2026-07-31
**Status:** Approved (design)
**Author:** Gopal S Akshintala

## Problem

ReṼoman is used but under-starred. Usage does not convert to GitHub stars unless we
ask. We want a delightful, Spring-Boot-style ASCII banner when execution starts and a
one-line "star us" call-to-action when execution ends — without polluting logs in the
embedded (Salesforce Core server) deployment.

## Constraints (what shapes every decision)

ReṼoman runs in two very different homes:

1. **Test harness** — a JUnit suite calls `revUp(...)` many times per JVM. A banner here
   is charming; the star ask lands with the right audience (developers).
2. **Embedded jar inside the Salesforce Core server** — the same `revUp` runs in
   production server code. A banner + "star us" plea is **log noise** there.

Therefore: **on by default, suppressible**, and the suppression lever is trivially
flippable once by Core.

There is also **no natural "all execution done" moment**: `revUp(List<Kick>)` fans out
into N internal single-`Kick` `revUp` calls, and a suite makes dozens of top-level calls.
So "start" and "end" must be defined explicitly.

## Decisions (locked with the user)

| Axis | Decision |
| --- | --- |
| Scope | **On by default, suppressible** via system property / env var / (implicitly) log config. |
| Banner timing | **Once per JVM**, on the *first* `revUp`. Guarded by an `AtomicBoolean`. |
| Star CTA timing | **Once per JVM, on shutdown** — a `Runtime.getRuntime().addShutdownHook`. This is the only true "all execution done" signal. |
| Banner art | **Figlet wordmark** (Spring-style block letters spelling `ReVoman`). |
| CTA counts | **Keep** JVM-wide run + step counters ("ran N steps across M runs") — the number earns the star. |
| Output channel | **Logger**, as one multi-line `RevomanLog.info` event (respects log config; single event avoids per-line prefix mangling). |

## Component design

One new internal object, one wiring change, no new public API.

### `internal object Banner` — `src/main/kotlin/com/salesforce/revoman/internal/log/Banner.kt`

Owns all banner state and rendering. Single responsibility: decide *whether* to speak, and
*what* to say. Knows nothing about steps/kicks beyond the two counts handed to it.

State (all JVM-static, thread-safe):

```kotlin
private val printed = AtomicBoolean(false)      // banner shown yet?
private val hookRegistered = AtomicBoolean(false) // shutdown hook installed yet?
private val runCount = AtomicLong(0)
private val stepCount = AtomicLong(0)
```

Public (internal) surface:

```kotlin
/** Called at the top of every top-level revUp. Prints the banner on the first call of the
 *  JVM and registers the shutdown-hook CTA (both once), then bumps the run counter. No-op
 *  entirely when suppressed. */
fun onRunStart()

/** Called after a run finishes with the number of steps that ran, to feed the CTA count. */
fun recordSteps(steps: Int)
```

Suppression (checked once, memoized):

```kotlin
// Precedence: system property wins over env var. off/false/0/no (case-insensitive) => silent.
private val enabled: Boolean by lazy {
    val raw = System.getProperty("revoman.banner")
        ?: System.getenv("REVOMAN_BANNER")
        ?: return@lazy true
    raw.trim().lowercase() !in setOf("off", "false", "0", "no")
}
```

When `enabled` is false: `onRunStart`/`recordSteps` return immediately, no hook is
registered, nothing prints.

Version source:

```kotlin
// Jar manifest Implementation-Version; empty when run from loose classes (tests/dev).
private val version: String = Banner::class.java.`package`?.implementationVersion?.let { "v$it" } ?: ""
```

Banner text (block letters spell `ReVoman`; the real `ReṼoman` wordmark + tagline + links
ride the caption lines — block figlet cannot render the `Ṽ` glyph):

```
   ____     __     __
  |  _ \ ___\ \   / /__  _ __ ___   __ _ _ __
  | |_) / _ \\ \ / / _ \| '_ ` _ \ / _` | '_ \
  |  _ <  __/ \ V / (_) | | | | | | (_| | | | |
  |_| \_\___|  \_/ \___/|_| |_| |_|\__,_|_| |_|
  ReṼoman · API Orchestration Engine for the JVM · {version}
  ► docs sfdc.co/revoman-docs   ★ star github.com/salesforce-misc/ReVoman
```

Star CTA (built by a pure, unit-testable `fun ctaText(runs: Long, steps: Long): String`):

```
  ────────────────────────────────────────────────────────
  ReṼoman ran {steps} steps across {runs} run(s). Useful?
  ⭐ Star it → github.com/salesforce-misc/ReVoman
  ────────────────────────────────────────────────────────
```

The shutdown hook only prints when `runCount > 0` (a JVM that loaded the class but never
ran gets no CTA).

### Wiring — `ReVoman.kt`

Two call sites, both top-level entry points (NOT the internal fan-out, so the `List<Kick>`
form counts as the number of real single-kick runs it performs — which is what we want):

- `revUp(kick: Kick)` at `ReVoman.kt:122` — call `Banner.onRunStart()` at the very top
  (before `RunLogContext.install`), and `Banner.recordSteps(rundown.totalStepsCount)` (or
  the flattened step count) before returning.
- `revUp(runbook: Runbook, ...)` at `ReVoman.kt:117` — same `onRunStart()` at entry; feed
  the runbook's executed step count to `recordSteps`.

`revUp(List<Kick>)` needs **no** direct wiring: it delegates to `revUp(kick)` per kick, so
each real run is counted once and the banner still prints on the first.

Rationale for placement: `onRunStart()` is idempotent and cheap; putting it at both true
entry points means every way a user starts ReṼoman triggers exactly one banner per JVM.

## Data flow

```
first revUp(...)  ──► Banner.onRunStart()
                        ├─ enabled? no ─► return (silent)
                        ├─ printed.compareAndSet(false,true) ─► RevomanLog.info { bannerText }
                        ├─ hookRegistered.compareAndSet(false,true) ─► Runtime.addShutdownHook { if runCount>0 print ctaText }
                        └─ runCount.incrementAndGet()
run completes    ──► Banner.recordSteps(n) ─► stepCount.addAndGet(n)
...more runs...   ──► runCount/stepCount accumulate
JVM exits        ──► shutdown hook ─► RevomanLog.info { ctaText(runCount, stepCount) }
```

## Error handling

- Banner/CTA must **never** fail a run. `onRunStart`/`recordSteps` bodies wrap their work so
  any unexpected error is swallowed to a `logger.debug` breadcrumb (same no-throw contract
  `RevomanLog.event`/`line` already follow).
- Shutdown hook body likewise guards with `runCatching`.
- Reading the manifest version returns `""` on any failure — never throws.

## Suppression levers (documented for Core)

1. `-Drevoman.banner=off` (JVM arg) — highest precedence.
2. `REVOMAN_BANNER=off` (env) — for container/CI.
3. Logger config — since output is a `RevomanLog.info` event, silencing the
   `com.salesforce.revoman` logger at INFO also hides it.

Core flips lever 1 or 2 once in server bootstrap.

## Testing

Unit tests (`src/test`), no shutdown-hook process spawning:

1. **`ctaText` formatting** — pure function: `ctaText(4, 37)` contains `37`, `4`, and the
   repo URL; singular/plural `run(s)` handled.
2. **Banner prints once** — call `onRunStart()` twice; capture the logger/appender; assert
   the banner event appears exactly once.
3. **Suppression via system property** — set `revoman.banner=off`, assert `onRunStart()`
   emits nothing and registers no hook. (Reset the memoized `enabled` between cases — see
   note below.)
4. **Suppression via env var** — covered by injecting the resolver, see below.
5. **Counters accumulate** — `recordSteps` sums across calls; `runCount` bumps per
   `onRunStart`.

Testability note: the `by lazy enabled` + `System.getenv` are awkward to toggle per-test.
To keep tests deterministic, factor suppression into a package-private
`fun bannerEnabled(getProp: (String) -> String?, getEnv: (String) -> String?): Boolean`
that the object calls with the real `System::getProperty`/`System::getenv`, and unit-test
that function directly with fakes. The `AtomicBoolean` latches get a test-only
`internal fun resetForTest()` (guarded by comment that it is test-only).

Shutdown-hook *firing* is not unit-tested (JVM-lifecycle); the CTA content it prints is
covered by test 1.

## Logging

The banner and CTA ARE the logging feature. Both go through `RevomanLog.info` so they land
wherever ReṼoman logs already go and honor log config. Registration/suppression decisions
also emit a one-line `debug` breadcrumb (`"banner suppressed via revoman.banner=off"`) so
an operator can confirm why they see nothing.

## Out of scope (YAGNI)

- Color / ANSI — plain ASCII only (logs, CI, Core server capture are not TTYs).
- Per-run banners, spinners, progress bars.
- Configuring the banner text via the `Kick` DSL — one env/prop switch is enough.
- Detecting test-vs-embedded automatically (rejected: fragile).

## Files touched

| File | Change |
| --- | --- |
| `internal/log/Banner.kt` | **new** — state, suppression, render, wiring helpers |
| `ReVoman.kt` | call `Banner.onRunStart()` + `recordSteps(...)` at the two top-level `revUp` entries |
| `src/test/.../log/BannerTest.kt` | **new** — the 5 unit tests above |
