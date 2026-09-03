# Consumer performance scorecard

| Journey | Workload | Score | 99.9% error | Unit |
| --- | --- | ---: | ---: | --- |
| Postman V2 collection | 10-step script-free revUp | 0.14486800000000000 | 0.0046480000000000000 | ms/op |
| Script-bearing Postman V2 collection | 10-step scripted revUp | 192.13604900000000 | 9.7858400000000000 | ms/op |
| ReVoman V3 collection | 10-step script-free revUp | 0.62594800000000000 | 0.017814000000000000 | ms/op |
| Large V3 collection | 100-step script-free revUp | 4.3863480000000000 | 0.14709400000000000 | ms/op |
| Script-bearing V3 collection | 10-step scripted revUp | 182.20242700000000 | 8.9182890000000000 | ms/op |
| Three-kick workflow | Three 10-step kicks with environment handoff | 542.96420300000000 | 27.298730000000000 | ms/op |
| Contracted runbook | Three-step runbook with contracts | 578.77940300000000 | 27.643908000000000 | ms/op |
| Verbose result rendering | 100-step rundown to verbose JSON | 0.35695000000000000 | 0.012996000000000000 | ms/op |
