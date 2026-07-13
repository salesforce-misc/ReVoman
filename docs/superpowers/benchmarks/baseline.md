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

1. **Before** applying the domain fix, from the worktree's WT-0 base commit, run the domain's
   benchmark and snapshot the numbers:
   ```bash
   ./gradlew jmh -Pjmh.includes=<DomainBenchmark>
   cp build/results/jmh/results.txt docs/superpowers/benchmarks/results/$(git rev-parse --short HEAD)-<domain>-before.txt
   ```
2. **After** the fix lands (tests green), re-run the same benchmark and snapshot again with a
   `-after` suffix.
3. Record the before/after delta in the worktree's PR description / ledger. Keep the raw
   snapshot files committed under `docs/superpowers/benchmarks/results/` so deltas are auditable.

## Notes

- The optimizing GraalJS runtime (`org.graalvm.truffle:truffle-runtime`, added in WT-0) is on the
  classpath, so GraalJS benchmarks measure jargraal-JIT behavior, not interpreter-only.
- JMH warmup/fork settings live on each `@Benchmark` class; the smoke benchmark uses minimal
  iterations for speed — domain benchmarks should use JMH defaults or higher for stable numbers.
