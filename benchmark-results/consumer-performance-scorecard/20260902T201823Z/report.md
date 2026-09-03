# Consumer performance scorecard

| Journey | Workload | Score | 99.9% error | Unit |
| --- | --- | ---: | ---: | --- |
| Postman V2 collection | 10-step script-free revUp | 0.13391900000000000 | 0.0041760000000000000 | ms/op |
| ReVoman V3 collection | 10-step script-free revUp | 0.59005600000000000 | 0.014865000000000000 | ms/op |
| Large V3 collection | 100-step script-free revUp | 4.0804350000000000 | 0.12833500000000000 | ms/op |
| Script-bearing V3 collection | 10-step scripted revUp | 176.35557700000000 | 8.8774730000000000 | ms/op |
| Three-kick workflow | Three 10-step kicks with environment handoff | 510.02384700000000 | 17.622360000000000 | ms/op |
| Contracted runbook | Three-step runbook with contracts | 487.85374100000000 | 19.494099000000000 | ms/op |
| Verbose result rendering | 100-step rundown to verbose JSON | 0.32738700000000000 | 0.0087820000000000000 | ms/op |
