# ReVoman Perf Benchmarks — Baseline Capture

Component-level JMH benchmarks live in `src/jmh/kotlin/com/salesforce/revoman/benchmark/`.
Full end-to-end collection runs need live network/orgs and are NOT JMH-repeatable, so we
benchmark the isolated hot paths the perf audit flagged (regex/vars, marshalling, GraalJS
eval, env accumulation).

## Run all benchmarks

```bash
./gradlew jmh
```

Results are written to `build/results/jmh/results.txt`.

## Run one benchmark class

```bash
./gradlew jmh -Pjmh.includes=SmokeBenchmark      # regex substring against the class name
```

## Baseline protocol (per domain worktree WT-1..WT-4)

1. Author the domain benchmark on top of the WT-0 base, then — **before** applying the
   optimization itself — run it against the unoptimized code and snapshot the numbers. (The
   benchmark class doesn't exist at the raw WT-0 base commit; the "before" measurement is of
   WT-0 behavior + the new benchmark, with the fix not yet applied.)
   ```bash
   ./gradlew jmh -Pjmh.includes=<DomainBenchmark>
   cp build/results/jmh/results.txt docs/superpowers/benchmarks/results/$(git rev-parse --short HEAD)-<domain>-before.txt
   ```
2. **After** the fix lands (tests green), re-run the same benchmark and snapshot again with a
   `-after` suffix.
3. Record the before/after delta in the worktree's PR description / ledger. Keep the raw
   snapshot files committed under `docs/superpowers/benchmarks/results/` so deltas are auditable.

The `<sha>-smoke.txt` snapshot in `results/` is a one-off harness sanity check from WT-0 (hence
the `-smoke` name, not the `-before`/`-after` domain-measurement scheme above).

## Notes

- The optimizing GraalJS runtime (`org.graalvm.truffle:truffle-runtime`, added in WT-0) is on the
  classpath, so GraalJS benchmarks measure optimizing-compiler behavior, not interpreter-only. On a
  stock JDK 21 this is *jargraal* (the Graal compiler running as ordinary JVM bytecode — no GraalVM
  JDK required); on a GraalVM JDK it would be libgraal instead.
  - Classpath presence is necessary but not sufficient: Truffle can silently fall back to
    interpreted execution (emitting only a warning) if the JDK/dependency set changes, which would
    quietly invalidate GraalJS numbers. To confirm the optimizing runtime actually engaged, watch
    the run for Truffle's "falling back to interpreted execution" warning (its absence = optimizing
    runtime active), or run with a Truffle compilation-trace flag.
- JMH warmup/fork settings live on each `@Benchmark` class; the smoke benchmark uses minimal
  iterations for speed — domain benchmarks should use JMH defaults or higher for stable numbers.
