# Benchmark comparison

Campaign: markdown-fixture
Overall: PASS

## Decisions

| Claim | Mode | Metric | Statistic | Point | Lower 95% | Upper 95% | Observed | Limit | Decision | Reason |
|---|---|---|---|---:|---:|---:|---:|---:|---|---|
| NON_REGRESSION | COLD | LATENCY | MEDIAN | 0.9 | 0.8 | 1.0 | - | 1.05 | PASS | ratio passes |
| STRUCTURAL | RETAINED | RETAINED_BYTES | - | 10.0 | 5.0 | 15.0 | - | 1024.0 | PASS | retained passes |
| STRUCTURAL | RETAINED | BYTES_PER_STEP | - | - | - | - | 1.1 | 1.1 | PASS | spread passes |
