# Benchmark comparison

Campaign: campaign-dfdabddd-7d51-4ac7-b5fd-f69b093b87cf
Overall: PASS

## Decisions

| Claim | Mode | Metric | Statistic | Point | Lower 95% | Upper 95% | Observed | Limit | Decision | Reason |
|---|---|---|---|---:|---:|---:|---:|---:|---|---|
| NON_REGRESSION | COLD | LATENCY | MEDIAN | 0.9947485581249779 | 0.9885179360304267 | 0.9992411840788291 | - | 1.05 | PASS | upper95 0.9992411840788291 is at most 1.05 |
| NON_REGRESSION | COLD | LATENCY | P95 | 0.9837132997060669 | 0.9604404530662021 | 1.0258090377991314 | - | 1.1 | PASS | upper95 1.0258090377991314 is at most 1.1 |
| NON_REGRESSION | COLD | ALLOCATED_BYTES | MEAN | 1.0008156817594287 | 0.9978442336183978 | 1.0038070041536087 | - | 1.05 | PASS | upper95 1.0038070041536087 is at most 1.05 |
| NON_REGRESSION | COLD | PEAK_RSS | MEAN | 0.9996328171925666 | 0.9904807622380588 | 1.0087873573002464 | - | 1.05 | PASS | upper95 1.0087873573002464 is at most 1.05 |

## Rejected blocks
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/38: load-average-exceeds-maximum
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/39: load-average-exceeds-maximum
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/40: load-average-exceeds-maximum
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/41: load-average-exceeds-maximum
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/42: load-average-exceeds-maximum
- lifecycle.no-script-one-step.v1/ALLOCATED_BYTES/43: load-average-exceeds-maximum
