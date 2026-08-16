# CS2a audit snapshot handoff

Date: 2026-08-16

Branch: `codex/cs2a-audit-snapshot-2026-08-16`

This branch is an unfinished preservation snapshot. It is not a successful benchmark result and
must not be merged or used for a performance claim without a separate audit and simplification.
The 2026-08-15 high-assurance protocol design and implementation plans are deferred.

## Preserved scope

- Existing CS2a operator, supervisor, runner, manifest validator, tests, and deferred protocol
  documents through implementation commit `7dcf3fe6dddb36d79f04645e01a1a45f93061b5e`.
- Three checksumed INVALID prelaunch/install-stage evidence commits:
  `f6038c676b05`, `f0406640587b`, and `7cc4f0fa0e66`.
- The complete direct smoke archive at
  `docs/superpowers/benchmarks/results/v1/cs2a-7dcf3fe6dddb36d79f04645e01a1a45f93061b5e/cs2a.Gp3djMyg`.
- The exact one-off direct smoke, legacy-lock repair, and direct-collection scripts under `build/`.
  They are retained only for audit provenance and are not intended to become supported interfaces.

Generated local marker files containing absolute workstation paths are not authority and are not
part of the snapshot. The canonical archive and this handoff contain the durable audit inputs.

## What actually ran

The direct smoke completed on `gopalaaksh-wsl3` with status 0:

- remote run root: `/opt/revoman-benchmark/runs/cs2a.Gp3djMyg`
- remote governor state: `/run/revoman-cs2a/governor-state.ZAprGaVE`
- direct receipt directory: `/home/gopala.akshintala/cs2a-direct-smoke.jg6BwhxE`
- profile/stage: `smoke` / `smoke-compared`
- runner, inventory, supervisor, and post-supervisor statuses: 0
- containment/restoration failures: false
- lock release: true

The archive contains 132 files. Its root `evidence-sha256sums.txt` has 131 entries and a fresh
`sha256sum -c` verification passed for every entry before this snapshot.

## Why measurement stopped

The smoke proved the end-to-end mechanics, but it did not prove campaign readiness:

- cold and warm baseline A/A comparisons are `INCONCLUSIVE`;
- cold and warm baseline-vs-candidate comparisons are `INCOMPATIBLE`;
- incompatibility reasons are:
  - target Gradle versions differ (`9.7.0-rc-2` versus `9.7.0`);
  - target wrapper hashes differ;
  - target build-JDK JVM flags differ because the Gradle javaagent paths differ.

The smoke runner returned 0 because its non-enforcing comparisons did not reject
`overall=INCOMPATIBLE`. The full campaign would enforce release gates, so no full
cold/warm/retained campaign was started.

Normal `--persist-only 0` also stopped with status 70. Its staged-tree preflight applied
`git diff --cached --check` to immutable raw Gradle stdout logs, four of which contain Gradle's
normal trailing space in `Daemon will be stopped at the end of the build `. The raw evidence bytes
were preserved unchanged; this audit snapshot commits them as explicitly non-authoritative smoke
evidence rather than pretending normal persistence succeeded.

Earlier install-stage status-70 attempts were caused by `dzdo` authentication being scoped to an
SSH TTY: installation could succeed in one SSH session while later `dzdo -n` verification in a new
session failed. The one-off direct path avoided that split. A legacy
`/opt/revoman-benchmark/task13.lock` was also repaired in place from ordinary-user ownership/mode to
`root:root 0600`; its inode was preserved.

## Audit priorities for the next session

1. Inventory every CS2a production interface and remove or consolidate layers before another run.
2. Keep the high-assurance protocol suite deferred; do not implement its native launchers,
   receipt/clearance ledgers, QEMU recovery, or release-signing machinery.
3. Decide one explicit comparable Gradle/wrapper/JDK envelope for baseline and candidate without
   weakening compatibility gates or rewriting historical evidence.
4. Make smoke reject incompatible comparison reports while continuing to allow statistically
   inconclusive low-sample results.
5. Separate source-text hygiene checks from byte-preserving evidence commits.
6. Replace the SSH/dzdo multi-session install flow with the shortest reviewed human-assisted or
   single-session workflow.
7. Only after a simplified smoke is compatible, run A/A and then the full candidate campaign.

## Operational caution

The local fail-closed operator lock was retained after persistence status 70:
`/tmp/revoman-cs2a-operator.501.gopalaaksh-wsl3.lock`.

Do not delete it or reuse the remote run blindly. In the next session, inspect its owner/process,
the remote workload/process set, the remote supervisor lock, and the archived run first. The remote
run root, governor state, and direct receipt directory above may still exist.

## One-off script hashes

- `build/cs2a-direct-smoke-7dcf3fe6.sh`:
  `50f3abb12127579bde40e7d25a9b295e092c224b5a695e9496e1e28e3db424bb`
- `build/cs2a-repair-task13-lock-7dcf3fe6.sh`:
  `5ea4899db00679343dcf07ce06ccd6efdaeeef028987e661efba94b39bcfba95`
- `build/cs2a-collect-direct-smoke-7dcf3fe6.sh`:
  `3a49438f265e5614b163bb91d9ed3bf377b87567885390d68dc6030f5d2df905`

All three passed `bash -n` before the snapshot. They contain no password or credential material.

## Suggested audit start

```bash
git switch codex/cs2a-audit-snapshot-2026-08-16
git log --oneline --reverse 6c931802..HEAD
git diff --stat 6c931802..HEAD
git status --short
```

Read this handoff first, then audit the diff from `6c931802` by subsystem. Treat all current
success markers as claims to verify, not facts to inherit.
