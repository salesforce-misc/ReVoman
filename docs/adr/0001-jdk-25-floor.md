# Raise the library JDK floor to 25

ReVoman’s consumer runtime floor is JDK 25 (`jvmToolchain(25)`, bytecode 25). Salesforce Core already runs on 25, so the primary consumer can take the jar. 0.90.x is the last line that runs on JDK 21; this is a breaking 0.x pin, not a 1.0.

We bumped for LTS hygiene — one current-LTS pin in docs, CI, and toolchain — not because production Kotlin needed a 22–25 API. Compact object headers, GC, AOT, and Loom pinning fixes are Core’s JVM, not this jar.

**This change is pin-only.** No production API rewrite. The only 25-gated migration worth doing later is `RunLogContext`’s `ThreadLocal` → `ScopedValue` (JEP 506): nested `revUp` / runbook already stack a sink via install/restore, and `ScopedValue.where` makes a forgotten restore leak impossible. Keep that as a follow-up so a logging-context refactor cannot block the pin.

## Considered options

- **Keep min 21, run on 25.** Gets the runtime wins without a breaking floor. Rejected: Core is already on 25 and we chose to move the single pin.
- **Build on 25, emit 21 bytecode.** Rejected: the knob we locked is the consumer runtime floor.
- **JDK `HttpClient`, Stream gatherers, Class-File, FFM, KDF.** Not 25-gated or unused here. Default HTTP stays http4k `ApacheClient`; the Kick `httpClient(HttpHandler)` whisper is the HTTP seam.
- **`V3YamlReader` ThreadLocal → ScopedValue / Stable Values.** Wrong job: that ThreadLocal is a per-thread SnakeYAML parser cache (`Yaml` is mutable and not thread-safe), not a run-scoped binding. Honest alternatives remain `ThreadLocal.withInitial` or `Yaml()` per parse.
- **Preview APIs in 25** (structured concurrency, Stable Values, Vector). No `--enable-preview` on a library floor. The engine is serial; Kotlin `by lazy` already covers deferred process-lifetime init.
- **Dual CI (21 + 25) / 21 backport branch.** Rejected unless Core asks.

## Consequences

Consumers on JDK 21–24 cannot use this line. Tooling is ready (Gradle 9.7.1, Kotlin 2.4.20-RC, detekt 2.0.0-alpha.6).
