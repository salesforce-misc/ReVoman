# ReVoman v1 Performance Evidence Protocol

ReVoman accepts comparative performance claims only from schema-validated v1 evidence captured on
the controlled host. The protocol treats cold standalone execution and warm repeated execution in
a long-lived JVM as equal release targets. This document describes how evidence is produced; it
does not claim that a candidate is faster.

## Fixed baseline and deterministic workload

Every release campaign rebuilds and measures this full baseline commit in a clean checkout:

```text
83f3cd70f78ad733412d10cbc8287aaabafe7aac
```

End-to-end measurement uses the packaged `lifecycle.no-script-one-step.v1` workload. Its HTTP
fixture is an in-process deterministic loopback server, so the protocol does not depend on a live
organization, public API, or external network. Component-level JMH uses the separately packaged
`jmh.component-operations.v1` workload and remains single-target evidence unless the campaign
driver attaches raw fork observations to real alternating block/role coordinates.

The fixed baseline is not a historical denominator. Each candidate campaign checks out
`baseline-a` and `baseline-b` independently at the full SHA, exports a fresh manifest for each, and
runs cold and warm A/A before candidate measurement. The candidate is measured only when both A/A
comparisons are `PASS`. A non-PASS A/A result makes the campaign `INCONCLUSIVE`; thresholds are not
relaxed and unfavorable observations are not deleted.

## Controlled-host rules

- Start the manual-only `Controlled performance benchmark` workflow with a separate full 40-character
  harness commit SHA, candidate ref, versioned candidate adapter, and absolute administrator-owned
  host-policy path.
- Use the protected `performance` environment and the
  `[self-hosted, linux, revoman-controlled-benchmark]` runner labels. Ordinary push/PR CI performs
  structural checks only.
- Require `/opt/revoman-benchmark/runs` to be pre-provisioned and writable. Each workflow attempt
  creates exactly one previously absent
  `/opt/revoman-benchmark/runs/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}`. Measured artifact
  directories are never restored from a build cache, reused, or globbed from an earlier run.
- Keep the harness, `baseline-a`, `baseline-b`, and candidate as separate clean checkouts. Build the
  installed driver only from the fixed harness checkout and export every target with its own Gradle
  wrapper plus the installed init script. Never edit or reuse one target manifest for another role.
- Run 50 cold blocks and five warm blocks, with one independent fork per role in every accepted
  block. Use seed `5928239383101656625`; use the cold/warm warmup, iteration, and provider settings
  documented in `DEVELOPMENT.md` and encoded by the workflow semantic test.
- Pass the supplied policy path, quoted, to every controlled command. Workflow code never creates,
  substitutes, or weakens controlled-host policy.
- For observed external power, retain the Linux power-supply sysfs result as runtime evidence. For
  a permanently mains-powered host, `FIXED_MAINS` is an administrator-owned, host-specific
  attestation that runtime telemetry is not applicable; it is valid only when
  `/sys/class/power_supply` exists and is empty. Any entry fails the probe.
- Preserve result and JFR files on every exit, including failed and inconclusive campaigns.

## Identity, provider, and hash requirements

A v1 campaign is auditable only when its JSON validates and binds all of these inputs:

- full clean harness commit/tree and installed distribution artifact hashes;
- full clean target commits/trees, original ordered target JAR classpaths, sizes, and SHA-256 hashes;
- separately pinned baseline and candidate adapter IDs/hashes;
- workload contract and deterministic fixture-tree hashes;
- Gradle version, wrapper hash, JDK distribution/vendor/full version, and relevant JVM flags;
- controlled-host fingerprint and administrator-provisioned policy SHA-256; and
- metric provider, provider-configuration SHA-256, unit, block, role, fork, process, and pairing
  coordinates.

Run the installed driver's `verify` command on each paired campaign or normalized JMH result before
publishing it. Run `compare ... --enforce-release-gates` separately for cold and warm A/A, then for
cold and warm baseline-versus-candidate evidence. Comparison reports are outputs, never substitutes
for their referenced machine-readable results.

Exact build, export, smoke, controlled-campaign, comparison, and verification commands are in
[`DEVELOPMENT.md`](../../../DEVELOPMENT.md). The workflow contract is guarded by
`BenchmarkWorkflowTest`, including independent refs, fixed SHA, three target IDs, A/A ordering,
distinct paths, supplied adapter/policy, release enforcement, and unconditional evidence upload.

Smoke captures record `UNAVAILABLE` power evidence and cannot support controlled release claims.

## Evidence locations and legacy output

Validated captures belong under:

```text
docs/superpowers/benchmarks/results/v1/baseline-83f3cd70/
```

That directory is populated only after a successful controlled capture. Until it contains verified
machine-readable results and their recorded hashes/providers, this repository makes no v1 measured
performance claim.

## First controlled A/A capture: 2026-08-11

The first v1 capture is an A/A validation of the harness and fixed baseline, not a claim that a
candidate is faster. It was produced in the unique remote run root
`/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a`. No evidence directory was reused, no rejected attempt
was removed, and both enforced comparisons returned `PASS`.

### Provenance

- The clean detached harness was commit
  `47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e`, tree
  `1713f58e9ab6220f2c2c39f1a2d907246dd591cc`. Its installed distribution SHA-256 was
  `02f57b4e02dc7cf7528a0f708aaceba5b44a8752a83d7e904d0856fc2621f81e`.
- Both clean detached targets were commit
  `83f3cd70f78ad733412d10cbc8287aaabafe7aac`, tree
  `e86b600e63f071119c6dd7ba3e06f69ac9cc5539`. Their independently exported manifest
  SHA-256 values were `c230e34a589e1fea29a36dc9f834e63a013744f405a36cdd2183b848bb2a100d`
  and `c46b8ce46500d7d72162f7d6f167a87c224eb378a92bbbc58c6e3c0afd15a2df`.
  Both resolve to path-free classpath SHA-256
  `8926c5c65f202d88163aaf1154604c9a8dc2aca0b0f2acc135ce22ca49bffca5`.
- The baseline adapter source SHA-256 was
  `86ab95ec023894b49655931e2321452ce88492b42fc755e39bcfbbf0cda3106a`.
  The target classpath contained the original multi-release `truffle-api-25.2.4.jar` and no
  `*-jmh.jar`.
- The lifecycle workload contract SHA-256 was
  `be9c08e7334000dfaeb687a1e35993af34601d9c85b29aa8f81678f955bffa7c`; its captured
  fixture-tree SHA-256 was `31af0229163ef1ed544189f9b1f1dbd9a80607ffd024a2e5bd09cddfae919c92`.
  The complete packaged fixture-set SHA-256 was
  `adf4d1d56a4252ad56421422e01cda95975a7d6505bc19210925b40aa95154ea`.
- The controlled-host fingerprint was
  `12e7d565978e40259c2f4c956c9e05696a32c0ba574c6971dfe85c8acd69fe44`.
  The administrator policy file SHA-256 was
  `7312efeed6a4c80e9588f0f4e25742021c6e11f46bbc8468a3adc06772408b79`; its canonical
  semantic SHA-256 was `48de27c7c84faec59c0ab2276489460ac4ffe3935cd0be41d9730b5aff1a3f60`.
- The root-owned governor supervisor SHA-256 was
  `093dd5354002bf6588863205fc878f8a5a835ca0e99525ecedfa0d9d73172795`; the controlled
  runner SHA-256 was `9cf79ac588479e4d43fe7231f1899482b6338d7ae658fff12020e1da8d31d204`.
- The host was Linux `6.8.0-137-generic`, Intel Core i7-9800X, 16 logical CPUs, using Azul
  OpenJDK `21.0.11.0.101+1-LTS` from
  `/home/gopala.akshintala/core-public/tools/Linux/jdk/sfdc-jdk-zulu-21.helium_x64`.
  Every macro health snapshot recorded `FIXED_MAINS` and all 16 governors at `performance`.
  Post-run inspection found the exclusive lock free, no benchmark supervisor/runner process, and
  all 16 governors restored to `powersave`. The privileged supervisor state recorded child and
  aggregate status `0`, `restoration-failed=false`, `containment-failed=false`, and completion at
  `2026-08-11T16:13:23+05:30`.

### Paired evidence

The cold campaign ID is `campaign-dfdabddd-7d51-4ac7-b5fd-f69b093b87cf`; the warm campaign ID is
`campaign-fe7d7364-4dce-48a9-a23b-32c076fe424e`.

| Mode / metric | Accepted | Rejected | Accepted target PIDs | Release-gate upper 95% | Limit | Decision |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Cold median latency | 50 | 0 | 100 | `0.9992411840788291` | `1.05` | `PASS` |
| Cold p95 latency | 50 | 0 | 100 | `1.0258090377991314` | `1.10` | `PASS` |
| Cold allocation | 50 | 6 | 100 | `1.0038070041536087` | `1.05` | `PASS` |
| Cold peak RSS | 50 | 0 | 100 | `1.0087873573002464` | `1.05` | `PASS` |
| Warm median latency | 5 | 0 | 10 | `1.0052968400696192` | `1.03` | `PASS` |
| Warm p95 latency | 5 | 0 | 10 | `1.0135137001543748` | `1.05` | `PASS` |
| Warm allocation | 5 | 0 | 10 | `1.000546931609329` | `1.03` | `PASS` |

The six rejected cold-allocation attempts were initial blocks 38 through 43, all with reason
`load-average-exceeds-maximum`. The driver retained those rejections; replacement blocks 50 through
55 supplied the missing accepted pairs, producing the required 50 accepted pairs. Warm latency and
allocation each contain 1,000 measured samples: 500 per role, iterations 0 through 99 in each block.
The 20 warmups are not published as measured observations.

Provider identities are:

- cold latency: `parent-process-wall-time/v1`, configuration
  `b23b02eb5e38f2a5e1e3ab7f4781c91d21c0f50230f39f6107e4bd733fbab5ab`;
- cold allocation: `jdk21-jfr-tlab-reserved-plus-outside/v1`, configuration
  `c223e5b171fbc324ab5f13efa31114446c2f1a59fddc1419e9fdca7fef837d15`;
- cold RSS: `gnu-time-v-maximum-resident-set-kib/v1`, configuration
  `97aad6221dd14bdfac371715774de43a737d58de37009b2b45c09a3f6dc96744`;
- warm latency: `target-nano-time/v1`, configuration
  `1852c738064999b407905e2db1423ad765cb9093a0a781ce295e11057994fa75`; and
- warm allocation:
  `jmh:gc.alloc.rate.norm:com.salesforce.revoman.benchmark.WarmLifecycleAllocationBenchmark.execute`,
  configuration `7657af452e4e2abdddd0354546229e681702cbecc92c4bc124f8580b6da811c8`.

### Component JMH archive

The component archive contains exactly eight normalized benchmark rows: two `RegexVarBenchmark`,
two `MarshallingBenchmark`, one `SandboxBenchmark`, and three `EnvAccumBenchmark` parameterizations.
Every row has five distinct fork PIDs, `quick=false`, and GC-profiler evidence. The output contains
no INFO logging, Kotlin-logging startup line, or multi-release warning. This file retains the JMH
importer's generic single-target environment identity (`governor=unknown`); it is archival evidence
and is not used as controlled paired release-gate evidence. The retained, non-published
`jmh-output.txt` had SHA-256
`27eafb8b1d3a06ba070c150802f0b5587e985ba5896eca2371da253ad97cb50b`.

### Post-capture validation

The controlled evidence remains bound to clean harness commit
`47d03c0fc3b0b01ac06d7a3a80bf925ae5ce201e` and distribution SHA-256
`02f57b4e02dc7cf7528a0f708aaceba5b44a8752a83d7e904d0856fc2621f81e`. The first Linux
final-gate run exposed a timing race only in a concurrent process-tracker test. Test-only commit
`3611a5d7e930aa4aec5ca58e22b88458ee831720` makes that test stop and join its sampler before
asserting settled state; it changes no production source or captured result. The controlled files
were not regenerated or relabeled. Final deterministic repository gates were rerun from a clean
detached checkout at that test-only commit: benchmark-driver check plus harness self-test passed
328 tests, the combined root/driver unit and integration gate passed its 1,037-test inventory, and
the full build passed Spotless, Detekt, and Kover. The controlled host's Qodana invocation could not
start analysis because its Docker daemon was failed; the same `qodanaScan` task completed locally
with 75 repository-wide findings (39 high and 36 moderate) and no finding in the changed
process-tracker test. GitHub CI runs Qodana again on the committed evidence.

### Published hashes

```text
3e4fb6f55b60afcdee88f14e9902ba2c26d1fc7ffa8b893d1176e5a3d5696a12  cold-aa.json
fc1672ecf30e54ff1be6fcecd22ee412f1f21c8709ebf8a3f34393fd6d7c267a  warm-aa.json
a1a715966b15a988f8759c5b971e1337485e5fd5adcfb3423da6ea8946c81320  revoman-benchmark-jmh-v1.json
5003f42d4853436a8df6fc5ea11126478328dc6510546c07d6c2b0dc1524deb3  jmh-raw.json
c9f34f7dacebb4cd6834035c8cae37ffeda27d31ffe8caeddad157f1c3863357  comparison.json
986290f5275ff8000731e8b9e64b759ee34abc5f1b3c03cb91134cee597c0345  comparison.md
d67213e22c893952fb6c2c2a61c7374fc49c591d9db8ca551f55eca4eddeabd6  comparison-warm.json
162e704dd53db5042c3d2de4880e84340d73532b04021f43d994298d637b2980  comparison-warm.md
```

The captured benchmark-command transcript had SHA-256
`4bcc1bf1fcef0f3d1dc8181bdad92dd908ac902398152fae2a0d7dbfea6c0077`. Its exact recorded
commands were:

```text
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/gradlew -p /opt/revoman-benchmark/checkouts/harness-cs1 :benchmark-driver:installDist --no-daemon --console=plain
COMMAND /opt/revoman-benchmark/checkouts/baseline-a/gradlew -p /opt/revoman-benchmark/checkouts/baseline-a -I /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts clean writeBenchmarkTargetManifest --no-daemon --console=plain -Pbenchmark.targetManifest=/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-a.json -Pbenchmark.targetId=baseline-a
COMMAND /opt/revoman-benchmark/checkouts/baseline-b/gradlew -p /opt/revoman-benchmark/checkouts/baseline-b -I /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/libexec/benchmark-target.init.gradle.kts clean writeBenchmarkTargetManifest --no-daemon --console=plain -Pbenchmark.targetManifest=/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-b.json -Pbenchmark.targetId=baseline-b
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-a.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-b.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver capture-baseline --mode cold --intent controlled --baseline /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-a.json --baseline-adapter baseline-83f3cd70 --candidate /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-b.json --candidate-adapter baseline-83f3cd70 --workload lifecycle.no-script-one-step.v1 --blocks 50 --forks-per-block 1 --warmups 0 --iterations 1 --seed 5928239383101656625 --metrics latency\,peak-rss\,allocation --host-policy /opt/revoman-benchmark/controlled-host.json --artifacts-dir /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/jfr/cold-aa --output /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/cold-aa.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/cold-aa.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver capture-baseline --mode warm --intent controlled --baseline /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-a.json --baseline-adapter baseline-83f3cd70 --candidate /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-b.json --candidate-adapter baseline-83f3cd70 --workload lifecycle.no-script-one-step.v1 --blocks 5 --forks-per-block 1 --warmups 20 --iterations 100 --seed 5928239383101656625 --metrics latency\,allocation --host-policy /opt/revoman-benchmark/controlled-host.json --artifacts-dir /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/jfr/warm-aa --output /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/warm-aa.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/warm-aa.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/gradlew -p /opt/revoman-benchmark/checkouts/harness-cs1 :benchmark-driver:benchmarkJmh -Pbenchmark.includes=RegexVarBenchmark\|MarshallingBenchmark\|SandboxBenchmark\|EnvAccumBenchmark -Pbenchmark.targetManifest=/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/manifests/baseline-a.json -Pbenchmark.adapter=baseline-83f3cd70 -Pbenchmark.forks=5 -Pbenchmark.profilers=gc -Pbenchmark.rawJmhOutput=/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/jmh-raw.json -Pbenchmark.resultOutput=/opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/revoman-benchmark-jmh-v1.json --no-daemon --console=plain
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver verify --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/revoman-benchmark-jmh-v1.json
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver compare --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/cold-aa.json --output-json /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/comparison.json --output-md /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/comparison.md --enforce-release-gates
COMMAND /opt/revoman-benchmark/checkouts/harness-cs1/benchmark-driver/build/install/benchmark-driver/bin/benchmark-driver compare --input /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/warm-aa.json --output-json /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/comparison-warm.json --output-md /opt/revoman-benchmark/runs/cs1-aa.K46Pvn3a/results/comparison-warm.md --enforce-release-gates
```

`docs/superpowers/benchmarks/results/491ea968-smoke.txt` is legacy human-readable smoke output. The
old harness let INFO logging contaminate measurements and did not provide the v1 target, harness,
fixture, provider, host-policy, or schema/hash guarantees. Keep the file only as historical harness
debugging evidence; never relabel, normalize, compare, or cite it as v1 evidence.
