# Benchmark comparison

Campaign: campaign-fe7d7364-4dce-48a9-a23b-32c076fe424e
Overall: PASS

## Decisions

| Claim | Mode | Metric | Statistic | Point | Lower 95% | Upper 95% | Observed | Limit | Decision | Reason |
|---|---|---|---|---:|---:|---:|---:|---:|---|---|
| NON_REGRESSION | WARM | LATENCY | MEDIAN | 1.0001588107582935 | 0.997197331982389 | 1.0052968400696192 | - | 1.03 | PASS | upper95 1.0052968400696192 is at most 1.03 |
| NON_REGRESSION | WARM | LATENCY | P95 | 0.9985003826546492 | 0.9796704449806652 | 1.0135137001543748 | - | 1.05 | PASS | upper95 1.0135137001543748 is at most 1.05 |
| NON_REGRESSION | WARM | ALLOCATED_BYTES | MEAN | 0.9998884517112505 | 0.9992281979843176 | 1.000546931609329 | - | 1.03 | PASS | upper95 1.000546931609329 is at most 1.03 |
