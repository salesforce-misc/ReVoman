# CS2a Host-Neutral Remote Session Design

**Status:** Draft detailed specification; explicit user approval pending

**Base commit:** `a18ab6f8c805dddaec684f5b4bd3fdc23c4af183`

**Scope:** Controlled-benchmark operator transport, privileged remote-session orchestration,
host-runtime configuration, immutable collection receipts, tests, gates, and the subsequent
baseline-versus-candidate measurement

## Goal

Make the CS2a controlled benchmark runnable through one interactive `dzdo` session without
checking any machine-specific hostname, username, home directory, JDK path, repository path, UID,
policy digest, or host fingerprint into an executable script.

The change is an evidence-integrity correction, not a shortcut around the existing controlled
protocol. It must preserve the supervisor as the only launcher of the controlled runner, retain
the exclusive lock and governor lifecycle, stop candidate capture after non-PASS A/A evidence,
archive every outcome, and create the append-only evidence commit before review or retry.

After this correction is implemented, independently reviewed, and fully gated, the work continues
through the actual controlled comparison: fresh baseline A/A, baseline `83f3cd70` versus the exact
new implementation SHA, immutable collection and semantic validation, then reporting of cold,
warm, and retained before/after decisions and confidence intervals.

## Trigger and preserved evidence

The implementation at `f9164a66626ded5abdd738c89656a8aa4574baf2` passed its complete local,
Linux/root, Qodana, Antora, and operator gates. Its local proportional smoke passed. The first
controlled operator attempt stopped at the first interactive `dzdo` prompt before supervisor
launch or run-root creation. That outcome is preserved by immutable evidence commit
`a18ab6f8c805dddaec684f5b4bd3fdc23c4af183` under:

```text
docs/superpowers/benchmarks/results/v1/
└── cs2a-f9164a66626ded5abdd738c89656a8aa4574baf2/
    └── operator-failure.20260815T020337Z.98196/
```

The archive truthfully records install status `70`, no remote evidence, and no local semantic
validation. Independent review confirmed that no supervisor, governor mutation, benchmark, or
run root occurred. This design and its implementation are a documented harness correction; the
preserved attempt is never amended or removed.

## Scope boundaries

The correction covers:

- parameterized SSH transport in the local operator;
- checked-in, unprivileged session-request data and a root-owned static launch mode;
- separately provisioned root-owned canonical installer, native copier, build provenance, and
  approval records;
- a checked-in canonical root remote-session orchestrator;
- an authenticated host-runtime configuration;
- root supervisor session finalization and immutable receipt/READY publication;
- privilege-free local collection from a completed receipt;
- existing exceptional archive recovery;
- exact script, manifest, archive, and Detekt integrity ledgers;
- TDD, mutation, Linux/root, complete build, Qodana, Antora, and independent reviews; and
- the controlled performance run after the new implementation SHA passes every gate.

The correction does not change benchmark workloads, baseline SHA, adapters, statistical rules,
controlled sample counts, comparison thresholds, the production API, or the requirement to retain
unfavorable evidence. It does not put a password into a file, pipe a password, use `dzdo -S`, add a
password helper, or weaken administrator policy.

The fixed `/opt/revoman-benchmark` protocol root is a portable host prerequisite, not a machine
identity. The unprivileged launchers resolve `dzdo` from the closed root-owned system search path
`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, enumerating every matching entry.
Every containing directory and any symlink in every resolved chain must be root-owned and have no
group/other write bit; every canonical target must be a regular root-owned executable with no
group/other write bit. All entries must canonicalize to the same device, inode, and SHA-256; a
differing target or metadata fails. The protocol selects the first alias in the fixed search order
and records the complete canonically deduplicated alias set. Before any privileged state mutation,
the canonical installer independently repeats that closed search and all chain, link, metadata,
executable, alias-equivalence, and hash validation. Session state and the receipt record this
root-verified protocol-selected path, alias set, canonical target, link-chain metadata, and target
SHA-256. They do not claim to prove the provenance of the already-completed privilege transition.
No checked-in script contains the host's resolved path.

The protocol trust chain is explicit. `/`, `/opt`, and `/opt/revoman-benchmark` must be canonical
nonsymlink `root:root` directories with no group/other write bit;
`/opt/revoman-benchmark` is exactly mode `0755`. The approval, canonical-bundle, durable-session,
receipt, policy, runtime-configuration, installer, and every other privileged state parent beneath
it are canonical nonsymlink `root:root` directories with their specified exact modes and no
group/other write bit. The only untrusted exception is the explicitly designated staging subtree
`/opt/revoman-benchmark/runs`: that path is one canonical nonsymlink directory owned by the
provisioned controlled UID with exact mode `0700`, and its session children have the same owner and
mode. It is used only for mutable transport staging. The distinct token-protocol run-root parent
`/opt/revoman-benchmark/run-roots` is a canonical nonsymlink `root:root 0711` directory. The
supervisor alone anchored-creates each exact child `run-roots/cs2a.TOKEN` beneath an already-open
parent, then makes that child controlled-UID-owned mode `0700`; the controlled UID can write inside
the run root but cannot create, rename, or replace its directory entry or a sibling. Both parents'
`/opt/revoman-benchmark` ancestor remains nonwritable to that UID, so neither controlled subtree can
replace a privileged sibling or ancestor. Every installer, orchestrator, supervisor, finalizer,
recovery, collector, and preflight use validates the complete ancestor chain before opening bytes
and revalidates it with the opened object's identity before acting. A symlink, ownership/mode
change, rename race, writable privileged ancestor, or path escape fails closed.

Every claimed atomic publication uses the same durability barrier: fully write and fsync bounded
files, fsync the hidden candidate directory, perform the no-clobber rename beneath an already-open
authenticated destination parent, and fsync that parent after the rename. Genesis state, canonical
bundles, transition snapshots, transcript activation/seal metadata, prepared run roots, and terminal
receipts are not
authoritative and cannot release a child, begin a side effect, report success, or permit a new
attempt until the parent fsync succeeds. An fsync or storage error fails closed and burns the token
for fresh-run use. Under the same token/control lock, recovery handles a final-name object left by
rename-before-parent-fsync in one way: if every byte, metadata field, identity, and hash-chain link
is complete, it idempotently fsyncs the object and destination parent and adopts that exact
publication before continuing; if the name is absent, it uses the last durable snapshot; if the
name is malformed, colliding, or cannot be made durable, it requires permanent fail-closed manual
recovery and never measures. This adoption rule applies to genesis, transitions, transcript
activation/seal metadata, prepared run roots, and receipts. Canonical-bundle adoption uses its
publication lock
and the same complete validation. The original process authorizes no dependent side effect before
the barrier. Power loss between rename and parent fsync is a required fault-injection boundary, not
assumed away. READY and the claim-terminal commit record have the narrower causal boundaries
defined below: each rename occurs only after its referenced object and parent are durable, so
visibility proves that prerequisite durability. The commit-record parent fsync controls remote
persistence and publisher success, not whether an already observed valid record can bind its
already-durable referenced object.

Token serialization uses the canonical nonsymlink `root:root 0700` parent
`/opt/revoman-benchmark/cs2a-session-locks` and one regular nonsymlink `root:root 0600` lock file
derived only from the strict token. The lock file is privileged state, never staging, and cannot be
selected by a caller-supplied path. That permanent claim/lock file also contains one immutable
closed-schema claim header binding the token and claim inode to exact bounded copies of the current
runtime-configuration and selected versioned privilege-policy bytes/metadata/digests plus its
fresh-run selection generation/digest and bound successful privilege-probe/complete pair, exact
controlled-UID policy/passwd/group-resolution bytes and digests, losslessly parsed UID and primary
GID, exact sealed launch-account-proof bytes/digest and fixed-FD validation result,
native-entry identity/process state, signer
protocol/path/device/inode/hash and provenance, signing-key ID/public bytes/hash/fingerprint and
private-path metadata without private bytes, and lifecycle/retirement-policy generation. A separate
canonical regular nonsymlink `root:root 0600`
admission mutex `/opt/revoman-benchmark/cs2a-session-locks/admission.lock` beneath the same
protected parent serializes every root `run` and `recover` across claim creation/open, inode and
parent fsync, claim-lock acquisition, and initial classification. It is opened no-follow and
identity-checked; the controlled UID cannot open either lock.

The same admission mutex serializes an append-only durable global-session ledger beneath canonical
`root:root 0555` `/opt/revoman-benchmark/cs2a-global-sessions`. Each regular nonsymlink
`root:root 0444` `generation.NNNNNN` record binds its sequence, previous digest, either one exact
active token/claim-header identity or `activeToken=null`, and nullable terminal-artifact and
administrator-clearance identities governed by the record kind. There is no mutable current
pointer or deletion. Fresh `run`
requires the highest contiguous valid generation to have no active token and no unclosed orphan
claim/session, where a matching durable `manual-safety` clearance administratively closes its old
claim/session for admission only. It durably appends the active-token generation after claim-header
durability and
before any payload or side effect. A crash in that narrow gap leaves one claim-only shape: the next
admission-locked recovery may reconcile exactly that unique claim by appending its active
generation;
fresh run is forbidden, and zero or multiple ambiguous candidates require manual fail-closed
recovery.

Native-entry mode `run` first holds that admission mutex, acquires the shared lifecycle/retirement
lock, and authenticates the root runtime, signer, key, and policy state without staging input. It
creates the permanent token claim with
an anchored no-follow `O_CREAT|O_EXCL` operation, writes and validates the complete claim header,
fsyncs the exact inode, fsyncs the lock-parent directory entry, acquires its open-file-description
lock, and durably appends the global active-token record before handing off to the payload. The
admission open-file-description lock is inherited and held by the complete privileged controller
lineage until durable terminal-observed publication; controlled runners/workloads close it. A
failure before the
complete header/global-ledger barrier authorizes no payload or measurement and leaves a burned claim
for admission-locked recovery. A preexisting claim forever denies fresh run: while the admission or
claim lock is held it reports `in-progress`; after release it reports `consumed`. The controller
then
releases admission and exits while the ledger remains active and blocks fresh run. Mode `recover`
holds the same admission mutex while it requires the ledger's active token to equal the requested
claim, validates the claim/header, nonblock-acquires its token lock, and classifies durable state or
the preserved pre-state outcome. Recovery also holds the shared lifecycle/retirement lock from
before it opens claim/session-bound artifacts through its last signing/publication use. It never
restores run eligibility. Claims, headers, and global generations are never unlinked, truncated, or
replaced. A reboot may not turn a consumed token or durable session into a different recreated lock.

A signed claim-terminal commit record or signed READY causal commit is necessary but is not by
itself sufficient for `normal` clearance of the global active token. The admission-locked terminal
publisher first
appends a terminal-observed generation that retains the same `activeToken` and binds the exact
signed artifact. Terminal-observed rename plus parent fsync is part of root terminal publication
success: if it fails after the signed artifact becomes collectable, root exits `70`, keeps the
artifact unchanged, and recovery may adopt a complete visible generation or append the exact absent
one. Only after terminal-observed is durable may the root invocation return the recorded terminal
status. After privilege-free collection, current offline-policy verification, immutable
local persistence, and independent archive-integrity validation, a host administrator may publish
one append-only root-owned clearance generation through the root-only administrative mode defined
below. The clearance binds the token, terminal
artifact, persisted evidence commit, current cumulative offline trust-policy/high-water digest, and
administrator action identity; only that generation sets `activeToken=null`. A root-visible
signature made by an offline-revoked key may be retained as diagnostic evidence but cannot receive
`normal` clearance. A crash after terminal publication merely adopts or finishes the
terminal-observed
generation and remains blocked. Unsigned quarantine, a remotely signed but locally revoked result,
local files without clearance, mutable run-root state, released benchmark flock, process absence,
or reboot never clears the global active token. Thus a second token cannot capture an abandoned or
quarantined session's mutated governors as its baseline. When authoritative clearance cannot be
produced, explicit administrator safety recovery is required and the host remains closed to fresh
measurement.

When that entry remains trusted, the same static native entry has root-only modes
`clear-normal TOKEN REQUEST_SHA256` and `clear-manual-safety TOKEN REQUEST_SHA256`; unprivileged
`launch` rejects them, and the controlled UID's exact
`dzdo` rule cannot authorize them. A separately authenticated administrator publishes one bounded
regular nonsymlink `root:root 0400` no-clobber request at the token-and-digest-derived fixed path
`/opt/revoman-benchmark/cs2a-global-clearances/cs2a.TOKEN.REQUEST_SHA256.clear.json` beneath a
canonical `root:root 0500` parent, then invokes the mode directly as root. `REQUEST_SHA256` must
equal the exact request bytes and is the request ID; it never selects an arbitrary path. The record
has exact schema
`revoman-cs2a-global-clearance/v1`, exact kind `normal` or `manual-safety`, and binds the token,
active-ledger generation/digest, terminal-observed generation/digest when present, nullable terminal
artifact and quarantine digests with an exact absence/publication-failure reason, last valid
ledger/claim/session/transition heads, exact partial-or-complete claim
path/device/inode/bounded-bytes digest and
durability boundary, persisted evidence commit or `null`, offline trust-policy and high-water
digests, host-policy/fingerprint digests, remediation-evidence digest or `null`, exact
clearance-tool
identity/provenance, and administrator action identity. Unknown fields, caller-selected paths, or
missing values required by the kind fail closed for that request only.

The root-only mode acquires admission, the shared lifecycle descriptor, and the existing token lock
when a complete lock inode permits it, in that order; it validates the exact mode-specific
ledger/session/claim state and fixed clearance record, and appends the
no-clobber parent-fsynced `activeToken=null` generation. `normal` requires the exact signed
terminal,
locally persisted commit, current nonrevoked offline policy/high-water, and independent archive
integrity approval asserted by the administrator record. `manual-safety` is the sole unsigned
administrative closure and is permitted only after
direct administrator-authenticated containment, exact governor and lock restoration, and host-state
repair or reprovisioning; it binds those remediation and clearance-tool proofs and closes the old
claim/session only
for global admission, but never authenticates, upgrades, archives as authoritative, or permits a
performance decision from the quarantined attempt. Crash recovery adopts only a complete
matching clearance generation under the common durability rule. Recovery that sees a
terminal-observed generation without clearance reports `awaiting-clearance` and performs no new
publication or workload.

For `normal`, terminal-artifact digest is mandatory and quarantine/absence fields are forbidden.
For `manual-safety`, either available diagnostic digest is bound, but both may be `null` only with
the exact authenticated publication-failure/absence reason, last valid heads, and remediation or
reprovision proof. Failure to publish quarantine therefore cannot weaken evidence, but also cannot
permanently prevent administrator host recovery.

Clearance requests are append-only and may become stale or prove malformed without consuming the
token's one clearance. A later corrected request uses a different digest-derived path. While holding
admission, root validates exactly the requested ID against the current ledger, host, policy, and
high-water state; invalid requests remain inert. The first accepted request appends the only
clearance generation, after which every other request is validation-only rejected. A policy advance
or revocation between request publication and use therefore requires a new request rather than
rewriting or trusting stale bytes.

`clear-manual-safety` also has one closed pre-ledger orphan branch for a process death after
permanent claim creation but before a complete header/active generation. Under admission and the
lifecycle lock it binds the exact partial claim inode/bytes and last durability boundary, proves all
recorded and candidate root owners absent, proves no durable session/bundle invocation, workload,
governor mutation, or other side effect could precede the missing active-generation barrier, and
revalidates unchanged host policy/state. It then appends an `activeToken=null` manual-safety
generation naming that orphan. This closes the burned token only for host admission and produces no
remote evidence or performance authority. Any ambiguity requires host reprovisioning rather than
claim deletion or inference.

A claim that exists but never reached durable session genesis has one terminal path. While holding
the claim lock after admission-serialized acquisition, root `run` after a caught pre-genesis
failure or root `recover` after proving every earlier owner absent may publish one no-clobber
root-signed claim-terminal envelope at
`/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim`. Its canonical parent is
`root:root 0555`, and the regular nonsymlink envelope is `root:root 0444`.

The envelope has distinct domain/schema `revoman-cs2a-claim-terminal/v1` and the same closed raw
length/signature framing discipline as READY, but it is never interpreted as READY or a receipt.
Its signed payload binds the token, claim device/inode, entry and signer identities, normalized
entry process state, exact immutable claim-header digest and bound runtime/signer/key identity,
required absence of durable session state, `remote-evidence-present=true`, and an actual status in
`1..255`. It also carries nullable claim-namespace-remap bytes plus their fixed path, size, SHA-256,
old/new boot IDs, and old/new selection/attestation identities. A changed boot requires the exact
durable remap record and byte equality to every signed field; the same boot forbids every remap
field. Because the signed envelope embeds the bounded canonical remap bytes, its commit record and
local archive bind them transitively without trusting a separate transport read. Source is exactly
`native-entry` or `installer` for a caught original failure, or `recovery-detected` with fixed
status `70` only after recovery proves every recorded owner absent. It authorizes no bundle,
workload, receipt, READY, benchmark projection, or performance decision.

The publisher validates fixed signer/key FDs, signs the complete payload, fsyncs the hidden file,
renames no-clobber, and fsyncs the claim-terminal parent. A complete final-name envelope observed
after rename but before parent fsync is handled under the common durability-adoption rule: recovery
validates the exact signature, claim identity, payload, and metadata, then idempotently fsyncs and
adopts it; absent uses the still-locked claim state; malformed or colliding requires manual
fail-closed recovery. An identical existing durable envelope is validation-only and never rewritten.

Only after the envelope and its parent are durable does the publisher sign and no-clobber publish
`/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim.ready`. This regular nonsymlink
`root:root 0444` commit record uses the distinct domain/schema
`revoman-cs2a-claim-terminal-ready/v1` and binds the token, permanent claim identity, exact envelope
header digest, path/size/SHA-256/signature/key identity, and the completed envelope durability
barrier. It also contains nullable signed post-envelope namespace-remap bytes and their old/new
boot, selection, and attestation identities. Those fields are required exactly when changed-boot
recovery publishes a previously missing claim-ready and are forbidden for original or same-boot
publication.
Its rename is the causal collection boundary; its own parent fsync controls publisher success and
remote persistence. Recovery may adopt a complete envelope and publish only its absent commit
record, but never rewrites either. Missing, malformed, mismatched, replayed, or colliding pairs fail
closed.

An absent claim means the human's credential attempt did not reach root admission: the same
prepared token remains pending and may be invoked again, but unsigned remote `stat` output can
neither close it nor authorize a new token. After a claim exists, only a signed claim-terminal
envelope plus its signed commit record, or a signed READY-backed receipt, can close and archive it
as authoritative evidence. A root-only `manual-safety` clearance may close it solely for later host
admission under the stricter remediation contract above; it creates no archive-integrity or
performance authority. A malformed or unverifiable claim state requires manual fail-closed recovery.

## Host-neutral interfaces

The local operator has no default SSH host. Remote modes require a validated explicit parameter:

```text
cs2a-operator.sh --prepare-remote-session \
  --remote-host USER@HOST \
  --ssh-port PORT \
  --ssh-identity-file ABSOLUTE_PATH \
  --ssh-host-key-fingerprint SHA256_FINGERPRINT \
  --receipt-signing-key-fingerprint SHA256_FINGERPRINT \
  --receipt-key-trust-policy FILE \
  --receipt-key-trust-policy-sha256 SHA256 \
  --receipt-key-trust-high-water FILE \
  --receipt-key-trust-high-water-sha256 SHA256 \
  --local-verifier-cas ABSOLUTE_PATH \
  --local-verifier-java-home ABSOLUTE_PATH \
  --runtime-config-sha256 SHA256

cs2a-operator.sh --collect-remote-session \
  --remote-host USER@HOST \
  --ssh-port PORT \
  --ssh-identity-file ABSOLUTE_PATH \
  --ssh-host-key-fingerprint SHA256_FINGERPRINT \
  --receipt-signing-key-fingerprint SHA256_FINGERPRINT \
  --receipt-key-trust-policy FILE \
  --receipt-key-trust-policy-sha256 SHA256 \
  --receipt-key-trust-high-water FILE \
  --receipt-key-trust-high-water-sha256 SHA256 \
  --local-verifier-cas ABSOLUTE_PATH \
  --local-verifier-java-home ABSOLUTE_PATH \
  --session-token TOKEN

cs2a-operator.sh --recover-remote-session \
  --remote-host USER@HOST \
  --ssh-port PORT \
  --ssh-identity-file ABSOLUTE_PATH \
  --ssh-host-key-fingerprint SHA256_FINGERPRINT \
  --receipt-signing-key-fingerprint SHA256_FINGERPRINT \
  --receipt-key-trust-policy FILE \
  --receipt-key-trust-policy-sha256 SHA256 \
  --receipt-key-trust-high-water FILE \
  --receipt-key-trust-high-water-sha256 SHA256 \
  --local-verifier-cas ABSOLUTE_PATH \
  --session-token TOKEN

cs2a-operator.sh --archive-only \
  --remote-host USER@HOST \
  --ssh-port PORT \
  --ssh-identity-file ABSOLUTE_PATH \
  --ssh-host-key-fingerprint SHA256_FINGERPRINT RUN_ROOT GOVERNOR_STATE
```

`--persist-only` and `--validate-attempt` remain local-only and accept no host; both require the
same current trust-policy/high-water and local-verifier CAS/JDK inputs for signed remote evidence.
Recovery revalidates its explicit CAS path and append-only registry against the authenticated local
session record before active-versus-safety-only classification; it does not need the JDK unless it
also verifies a signed terminal artifact. Reporting revalidates those same explicit inputs.
`REMOTE_HOST` is at most 318 bytes and has the exact mandatory-user grammar
`[A-Za-z_][A-Za-z0-9._-]{0,63}@[A-Za-z0-9][A-Za-z0-9._-]{0,252}`. The operator splits it once,
passes the user only through the fixed OpenSSH user option and the host only as the host operand,
then cross-checks the remote account against the authenticated controlled-UID account. Omitted
user, local-username fallback, leading options, colons, brackets, percent signs, slashes,
backslashes, whitespace, control characters, shell metacharacters, IPv6 literals, URI forms, and
embedded ports are rejected. `PORT` is canonical decimal `1..65535` with no leading zero. Every
`ssh`, SFTP-backed `scp`, `sftp`, and legacy `rsync` call uses the same explicit user, host, and
port
and terminates local options before user-derived operands where the tool supports it.

`--archive-only` is quarantined legacy ingress, not a downgrade around signatures. Implementation
freezes an exact checked-in allowlist of pre-protocol implementation/schema identities, remote
run/governor paths, and expected bounded path/digest inventory observed at migration. Caller paths
must byte-equal one row; they cannot select another source. The mode is strictly privilege-free: it
uses only the same hermetic controlled-UID transport and never invokes `dzdo`, a root helper,
installer, supervisor, recovery, or caller-selected executable. Inaccessible or mismatched legacy
bytes yield quarantine incident failure rather than privileged fallback. Future SHAs and all
token-protocol sessions are ineligible. Its output is marked
`legacy-unsigned-quarantine=true`, cannot enter the signed terminal matrix, cannot yield an
authoritative archive-integrity PASS or performance decision, and is retained only for incident
recovery. An empty migration inventory produces an empty allowlist.

Every remote mode also requires one out-of-band OpenSSH `SHA256:` host-key fingerprint with an
exact bounded base64 grammar and one explicit local SSH identity file. The identity is a canonical
regular nonsymlink file owned by the invoking user, mode `0400` or `0600`, beneath ancestry not
writable by group/others. Preparation derives and records only its public key/fingerprint plus
private-path device/inode/type/owner/mode; private bytes never enter a session record or archive.
Every later remote operation no-follow revalidates that identity.

The operator obtains candidate host public keys without trusting them, keeps only the unique key
whose locally computed fingerprint equals the explicit parameter, and writes one ignored
session-specific known-hosts file. It selects the local OpenSSH tool suite only from a closed
root-owned nonwritable system path, records every selected executable's canonical identity/hash,
and invokes it through an exact clean environment with no `HOME`, `SSH_ASKPASS`, agent socket,
locale/plugin option, or caller SSH variable. One fixed option array is reused by host-key
acquisition where applicable, preflight, `ssh`, SFTP-backed `scp`, `sftp`, recovery, legacy
`rsync`, and collection. It uses no ambient user or system configuration (`-F /dev/null` or the
platform's reviewed equivalent), batch mode, explicit identity/port/host operand, strict checking
`yes`, `IdentitiesOnly=yes`, that exact known-hosts file as the only trust store, and explicit
disabling of default identities, proxy/jump, local command, known-hosts command, forwarding,
hostname/user/port rewrite, askpass,
update-host-key, and connection-master behavior. Neither `no` nor `accept-new` is permitted.
Preparation records the
selected host-key type, bytes, fingerprint, complete SSH executable/option/environment identity,
and explicit local identity public fingerprint. Later operations must match the authenticated local
session record and the final archive records them. A new host key or local identity requires
explicit preparation for a later token and cannot silently recover or collect an old session.
The exact host key and local identity are protected session prerequisites from preparation through
terminal collection and global clearance. Repository code has no host-key/identity rotation,
removal, rebind, or cleanup capability: every cleanup mode only reports the bound external
identity path/device/inode and both fingerprints and never deletes or replaces them. The
administrator must retain those exact external prerequisites and defer any separately managed
rotation until direct root inspection proves the global ledger cleared; this is an explicit
operational prerequisite outside repository automation, not a claimed local pending-session
registry. If either
prerequisite is lost, changed, or suspected compromised anyway, automated recovery/collection stays
fail-closed and the global active token remains blocking. The administrator must use a separately
trusted direct host-safety/remediation path and, when necessary, `manual-safety` clearance. This
does not permit a new token, `accept-new`, unsigned rebind, or rerun preparation to upgrade the old
evidence.

Every pre-authentication or pre-receipt remote observation uses a streaming bounded reader; no tool
output is first captured without a limit and then validated. Host-key acquisition accepts at most
16 records, 8 KiB per line, 128 KiB total, only the closed supported key-type set, a 30-second
object deadline, and a 60-second total deadline. Preparation, preflight, recovery coordination, and
legacy observation enforce each referenced schema/file cap while streaming, plus at most 1 MiB and
256 records for otherwise fixed diagnostic metadata, a 60-second object deadline, a ten-minute
phase deadline, and checked aggregate arithmetic. Transfers whose authenticated protocol objects
have larger explicit caps use those exact caps, never an inferred remote size. All stdout and stderr
are noninteractive and stream into user-owned `0600` hidden files beneath a quota-limited local
stage; overflow, extra records, parse failure, quota failure, or timeout closes the process, removes
the stage, and publishes nothing. Raw remote control bytes are never rendered to the credential
terminal. User-facing errors are locally generated and include only a bounded escaped or hex digest
summary, so ANSI/OSC text or a fake password prompt from transport cannot impersonate the later
native-entry instruction.

The separate receipt-signing-key fingerprint uses the same bounded OpenSSH `SHA256:` grammar but
must identify an Ed25519 public key, not an SSH host key. Preparation, recovery, and collection
require exact equality with the authenticated local session record and with the root-signed
completion envelope. Host-key possession alone cannot authenticate a receipt.

Receipt-key compromise revocation is a separate local/offline trust boundary. Every new remote
mode requires one bounded exact-schema receipt-key trust-policy file plus its out-of-band SHA-256.
The policy is administrator-authenticated outside the controlled host, has a strictly increasing
generation, and lists active and irreversibly revoked signing-key fingerprints. Preparation records
the current generation. A separate administrator-authenticated local high-water record binds the
largest accepted generation and its policy digest. Preparation, recovery, collection,
`--validate-attempt`, and reporting require an explicit policy and high-water pair with matching
out-of-band digests and require the policy generation/digest to equal that nondecreasing high-water;
repository code never lowers or silently initializes it. Preparation rejects a revoked key for a
new session. Collection, persistence, validation, and reporting reject a revoked key for
authoritative evidence. The local recovery coordinator instead labels the invocation `safety-only`,
permits the same token-only root recovery command, and refuses to accept any authoritative signature
or performance path while containment, governor restoration, and lock release proceed. Remote
retirement and a
signature made by the compromised key cannot override local revocation. A revoked-key session may
be preserved only as untrusted incident bytes and can never yield authoritative archive integrity
or a performance decision; unfinished sessions under that key remain fail-closed and uncollectable
for evidence, but remain recoverable for host safety. Compromise response also blocks/removes the
remote private key through administrator state outside this protocol. Previously persisted evidence
under that key is always distrusted once compromise revocation is current. No timestamp, evidence
commit, remote retirement, caller-supplied key, or signature by the compromised key restores it.

The bounded trust-policy JSON has schema `revoman-cs2a-receipt-key-trust-policy/v1`, one positive
integer `generation`, and sorted unique disjoint arrays `activeKeyFingerprints` and
`revokedKeyFingerprints`; unknown keys are rejected. The bounded high-water JSON has schema
`revoman-cs2a-receipt-key-trust-high-water/v1`, the same generation and policy SHA-256, and the
previous accepted high-water digest or `null` for genesis. The operator verifies both explicit
out-of-band digests, then stores every accepted high-water record no-clobber in an append-only
hash-linked registry beneath the protected local verifier CAS. An input must equal or extend the
largest complete locally retained generation. Every later revoked array must be a superset of the
cumulative earlier revoked set, and no cumulatively revoked key may ever re-enter active. A gap,
fork, regression, removal/reactivation, active/revoked overlap, changed policy at the same
generation, missing registry, or attempted implicit genesis after prior use fails closed. Cleanup
protects that registry with the verifier/JDK CAS.

Preparation writes the unique remote session directory and exact direct invocation to ignored
local marker files. It stages no credential-bearing executable. The human runs only the
pre-provisioned root-owned static native entry on the controlled machine:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 launch run SESSION_TOKEN
```

The same entry performs recovery only when the human invokes:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 launch recover SESSION_TOKEN
```

Provisioning alone uses the same binary's fixed non-measuring command:

```text
/opt/revoman-benchmark/cs2a-session-installer-v1 launch auth-probe PROBE_NONCE
```

`PROBE_NONCE` has the token grammar but is never a session token. After successful authorization,
root `auth-probe` performs the native clean-entry checks, reads only the highest contiguous valid
privilege-policy selection from root-owned state, requires that selection to bind this exact entry
and installed command rule, validates the nonce-derived active probe lease while holding the
lifecycle lock shared, and emits one fixed nonce/lease-bound success record. It consumes no runtime
configuration or approval, creates no claim, session, ledger generation, bundle, transcript, or
workload, and exits. It does not execute the Bash payload.

In unprivileged `launch` mode, the static entry accepts only exact `auth-probe|run|recover` plus one
strict 32-character lowercase hexadecimal nonce or token. It independently performs the closed
`dzdo` discovery,
validates the administrator-bound initial namespace/rootfs attestation, normalizes its own
environment/descriptors/signal state, rejects any caller-supplied FD 3, constructs and seals the
fixed launch-account proof described below from its own live syscall observations, and `execve`s
the selected root-owned `dzdo` with fixed argv naming the same canonical entry, mode, and token. It
never reads the password. After authorization, the credential-bearing root invocation accepts only
the matching
`auth-probe NONCE` or `run|recover TOKEN` form, requires that exact preserved FD 3, and repeats all
root-side validation without reconstructing the proof. The separate
already-root administrative
interface accepts only the two clearance modes defined above and is absent from `launch` and the
controlled UID's command rule. The protocol itself executes no staged byte before or during
credential
entry, and staging can supply no path, digest, executable, shell fragment, or installer option. A
staged instruction or session-result file is untrusted data and is never executed.

The human's credential-entry console and controlled account are explicit trust prerequisites. The
host OS, administrator-approved initial user/PID/mount namespace and rootfs/mount identities,
`dzdo`, terminal application, shell that performs the absolute native-entry exec, absence
of a hostile same-UID process, and absence of any unapproved host workload able to tamper with or
bias CPU, memory, disk, or network behavior are trusted continuously from preparation through
completed `dzdo` authorization, workload execution, sealed receipt, READY, and terminal-observed
publication. The native entry rejects a preattached tracer, but disabling
dumpability after entry cannot protect a TTY that was already observable. The protocol therefore
does not claim to protect a password from an already compromised interactive account: such an
account can define a slash-named shell function, replace terminal bytes, observe the TTY, ptrace or
signal the runner, enter a user/mount namespace with a fake `/opt` or `dzdo`, forge coherent
result/handoff bytes, or bias timing. Staging, caller, and
transport bytes remain adversarially parsed, but signed performance authority explicitly depends on
the continuing clean-account/controlled-host prerequisite; this protocol does not claim malicious
same-UID isolation. Root records prelaunch and pre-seal process/cgroup/host-policy snapshots in
durable state and the receipt, and any detected unapproved process or host-policy drift makes the
attempt evidence-invalid. Those endpoint checks detect persistent or sampled drift; they do not
prove that a transient hostile process or load never appeared between them. Undetected transient
interference is explicitly outside this trusted-mode guarantee and must be excluded operationally,
not relabeled as protocol-detected isolation. If the credential-console prerequisite cannot be
established, the
administrator must provision a separately reviewed root-owned restricted invocation channel. If the
continuing account/host prerequisite cannot be established, this single-UID protocol has no safe
measurement mode and the session must not run. A future separately reviewed design may introduce a
dedicated no-login measurement UID with process/signal/ptrace and resource isolation, but that is
outside this correction and cannot be inferred from these interfaces.
Once root entry begins, it immediately disables dumpability and normalizes the process state
described below. Staging, transport, caller-supplied, and unauthenticated post-entry bytes remain
untrusted for authorization and evidence; authenticated runner output is authoritative only under
the continuing prerequisite above.

The administrator's command rule for this exact native-entry path must require fresh interactive
authorization for every invocation and must disable reusable per-user, per-TTY, per-session, or
global credential tickets. A cached authorization from the trusted launch may not authorize a
second entry after the controlled UID becomes untrusted. This is a provisioned host-policy
prerequisite, not behavior inferred from repository code. Its exact root-owned attestation is the
privilege-policy file defined below and is bound by runtime configuration, claim header, durable
state, receipt, and report. Provisioning validates the actual installed rule through the vendor's
administrator interface and a non-measuring behavioral probe: immediately after one authorized
entry, the same controlled UID on the same authenticated TTY/session invokes the exact native entry
again with prompting and credential input disabled by the reviewed vendor mechanism; it must fail
before root entry or claim creation. Separate bounded probes cover every other cache scope the
installed product supports. The exact probe method, TTY/session context, statuses, and result digest
are published in the root-owned probe record defined below; later runtime configuration and approval
bind that record.
Where the platform supplies a reviewed ticket-invalidation operation, the first root instructions
of production `run|recover` invalidate the just-used ticket before any controlled child is released.
The supervised first `auth-probe` deliberately records
`invalidationDeferredForCacheTest=true` and does not invalidate before the same-TTY/session and
other cache-scope attempts; otherwise invalidation could conceal a caching policy defect. The clean
account remains trusted during this bounded test, no runtime approval is yet fresh-run eligible, and
the probe mode cannot create state. After all second attempts fail, the administrator invokes the
reviewed invalidation operation and separately proves that no ticket remains. The probe record binds
both pre-invalidation policy-only results and final invalidation/absence results. If the rule cannot
be proved, a second attempt enters root, or a ticket remains, no measurement or automated recovery
may start.

The cache test is one append-only nonce-bound provisioning transaction. Canonical nonsymlink
`root:root 0555` `/opt/revoman-benchmark/controlled-privilege-probe-leases` contains only bounded
16 KiB regular nonsymlink `root:root 0444` records named
`generation.NNNNNN.RECORD_SHA256.json`. Each exact-schema record contains its positive canonical
sequence, previous-record digest or `null` at genesis, kind `begin|complete|abort`, and the
kind-specific fields below; unknown fields, gaps, forks, duplicate sequences, or digest/name
mismatch fail closed. Every publication uses the common hidden-file, no-clobber rename, file/parent
fsync, and adoption contract.

Before the first authorized probe, the administrator holds the lifecycle lock exclusively and
appends one `revoman-controlled-privilege-probe-lease/v1` `begin` record binding the nonce digest,
highest contiguous policy-selection generation/digest, entry/privilege-command identities, exact
controlled-account-policy path/digest, passwd/group-resolution digests, UID/primary GID and
supplementary-group set, namespace selection/attestation identities, and probe-method digest. Root
`auth-probe` derives the
active lease from the highest
record and its nonce, takes the lifecycle lock shared, requires the selection still highest, and
binds the begin-record digest in its result. Every policy, selection, entry, command-rule,
command-binary, UID, namespace selection/attestation, or probe-method updater must refuse to run
while the
highest record is an
unclosed `begin`, even between probe invocations.

After all prompting-disabled attempts fail and final ticket invalidation is proved, the
administrator keeps the lease active, publishes and parent-fsyncs the immutable probe record, then
holds the lifecycle lock exclusively and appends one
`revoman-controlled-privilege-probe-complete/v1` `complete` causal commit. It binds the exact begin
record, unchanged selection and identities, probe-record path/size/SHA-256, and final-invalidation
proof digest. The lease remains active and every updater remains blocked until the complete-record
parent fsync succeeds. A crash after probe publication may adopt that exact record and publish only
the missing complete commit after full revalidation; a complete record without its exact durable
probe is invalid and leaves the lease active. A stopped test with no published probe receives only
one `revoman-controlled-privilege-probe-abort/v1` `abort` record after ticket invalidation, binding
the begin record and one closed failure reason. An aborted, missing, forked, stale, or incomplete
transaction is never fresh-run eligible. No mutable current pointer, caller path, or long-lived
helper process is trusted.

## Authenticated host-runtime configuration

Machine-specific execution values live only in administrator-owned remote state:

```text
/opt/revoman-benchmark/controlled-runtime.json
```

The file must be a bounded file of at most 16 KiB and a regular nonsymlink `root:root 0444` file.
It contains one JSON object with exactly these keys and no additional properties:

```json
{
  "schema": "revoman-controlled-runtime/v1",
  "javaHome": "/absolute/canonical/path",
  "javaExecutableSha256": "lowercase-64-hex",
  "javaRuntimeInventory": "/absolute/canonical/path",
  "javaRuntimeInventorySha256": "lowercase-64-hex",
  "sourceRepo": "/absolute/canonical/path",
  "gradleUserHomeSeed": "/absolute/canonical/path",
  "gradleUserHomeSeedInventory": "/absolute/canonical/path",
  "gradleUserHomeSeedInventorySha256": "lowercase-64-hex",
  "gradleUserHomeSeedTreeSha256": "lowercase-64-hex",
  "gradleUserHomeSeedRecipe": "/absolute/canonical/path",
  "gradleUserHomeSeedRecipeSha256": "lowercase-64-hex",
  "gradleUserHomeSeedProvenance": "/absolute/canonical/path",
  "gradleUserHomeSeedProvenanceSha256": "lowercase-64-hex",
  "receiptSignerProtocol": "v1",
  "receiptSignerSha256": "lowercase-64-hex",
  "receiptSignerBuildProvenanceSha256": "lowercase-64-hex",
  "receiptSigningKeyId": "lowercase-64-hex",
  "receiptSigningPublicKeySha256": "lowercase-64-hex",
  "receiptSigningKeyFingerprint": "OpenSSH-SHA256-fingerprint",
  "controlledUid": "canonical-unsigned-decimal",
  "controlledPrimaryGid": "canonical-unsigned-decimal",
  "controlledAccountRecordSha256": "lowercase-64-hex",
  "controlledUidPolicySha256": "lowercase-64-hex",
  "privilegePolicyPath":
    "/opt/revoman-benchmark/controlled-privilege-policies/derived-version.json",
  "privilegePolicySha256": "lowercase-64-hex",
  "privilegeProbePath":
    "/opt/revoman-benchmark/controlled-privilege-probes/derived-probe.json",
  "privilegeProbeSha256": "lowercase-64-hex",
  "privilegeProbeCommitPath":
    "/opt/revoman-benchmark/controlled-privilege-probe-leases/derived-complete.json",
  "privilegeProbeCommitSha256": "lowercase-64-hex",
  "namespaceAttestationPath":
    "/opt/revoman-benchmark/controlled-namespaces/derived-attestation.json",
  "namespaceAttestationSha256": "lowercase-64-hex",
  "namespaceSelectionGeneration": "positive-canonical-decimal",
  "namespaceSelectionSha256": "lowercase-64-hex",
  "hostPolicySha256": "lowercase-64-hex",
  "hostPolicySemanticSha256": "lowercase-64-hex",
  "hostFingerprintSha256": "lowercase-64-hex"
}
```

The operator requires the SHA-256 of these exact bytes as a preparation parameter. That digest is
bound separately from the reusable bundle manifest by the approval filename/content, then carried
through durable session state, root handoff, supervisor state, run metadata, final handoff, receipt,
archive, semantic selection, and report.
The three explicit controlled-account values must byte-equal the fixed controlled-UID policy after
its lossless type and account-resolution validation. The runtime may neither override nor truncate
that policy. The Gradle-seed recipe/provenance fields identify the exact administrator-published
files associated with the configured seed and inventory; their paths are content-derived beneath
the fixed root-owned seed-policy parent, never caller-selected.

Preparation also requires the exact out-of-band receipt-signing-key fingerprint. It downloads only
the fixed key-ID-derived public file as untrusted bytes, verifies its SHA-256 and OpenSSH
fingerprint against both the explicit parameter and runtime configuration, and stores that
verified public key in the authenticated local session record. The private key is never remotely
readable. A signing key rotation changes runtime-config bytes and requires a new append-only
approval. Old private keys remain installed only to finish or recover already durable sessions
or an unclosed permanent claim whose immutable header binds that key, while their public keys remain
active in the independent local trust policy. Compromise revocation makes those claims/sessions
uncollectable rather than trusting a forged signature.

Every digest is lowercase 64-hex. Every configured path is nonempty, absolute, canonical, contains
no control character, and is validated against its required type and metadata. The configuration
cannot redirect the protocol's fixed UID, namespace, and host-policy paths:

```text
/opt/revoman-benchmark/controlled-uid
/opt/revoman-benchmark/controlled-namespaces
/opt/revoman-benchmark/controlled-gradle-seeds
/opt/revoman-benchmark/controlled-host.json
```

The Gradle-seed parent is canonical nonsymlink `root:root 0555`; each seed, inventory, recipe, and
provenance child has a complete content-derived name and the root-owned nonwritable metadata
required below. Runtime paths must resolve to those exact related children.

Namespace attestations are bounded regular nonsymlink `root:root 0444` exact-schema files beneath
canonical nonsymlink `root:root 0555` `/opt/revoman-benchmark/controlled-namespaces`. Each derived
filename binds its complete SHA-256. An attestation binds one boot-ID digest, initial user/PID/mount
namespace device/inode identities, complete UID/GID-map digests, and root-mount
ID/device/filesystem identity. It separately inventories the protocol root and run-root-parent
mountpoints with canonical path, boot-local mount ID/device, filesystem type, root-provisioned
persistent semantic filesystem ID, and mount-source identity, so a separate `/opt` is explicit
rather than assumed to share `/`; it contains no checked-in host value. Append-only
`selection.NNNNNN` records under the same parent bind their sequence, previous digest, and one
current attestation path/digest. Publication holds the lifecycle lock exclusively and uses the
common no-clobber durability barrier. A gap, fork, regression, mutable current pointer, or selected
path that is not the exact derived child fails closed. Old attestations and selections are never
removed while a claim/session binds them.

The administrator publishes the current selection before the probe lease; the lease, probe result,
runtime configuration, claim, state, and receipt bind its generation/digest and attestation. Fresh
run requires the runtime fields to name the highest current selection. A boot or namespace/rootfs
change requires a new append-only attestation/selection and, before another fresh run, a new probe
transaction, runtime configuration, and approval. Preparation records the administrator-approved
current values in the authenticated local session. The real native launch branch and post-`dzdo`
root entry compare `/proc` and root-mount state to the highest current selection and reject
noninitial or partial UID/GID maps, an unexpected namespace, or a different root mount before
command discovery/state mutation.

Recovery after reboot validates the new current selection at native entry, then authenticates the
claim/session's sealed original selection. For durable state without a receipt, recovery first
proves the prior transcript owner/writers absent, seals any abandoned transcript, and publishes its
mandatory `recovery-owner-activation` transition. That same atomic transition carries the remap
fields before containment, governor restoration, signing, or terminal publication: old/new boot
IDs, both complete attestation/selection identities, and proof that the current namespace/rootfs
matches the new attestation. It also maps the old/new boot-local mount IDs and devices for the
protocol root and run-root parent while requiring exact persistent semantic filesystem ID,
mountpoint, type, and source equality. Same-boot mount drift is fatal; changed-boot boot-local IDs
are never treated as persistent identity. Without genesis, the header-bound installer instead
publishes one
bounded regular nonsymlink `root:root 0444` record at the token/new-selection-derived child of
canonical `root:root 0555` `/opt/revoman-benchmark/cs2a-claim-namespace-remaps`, using the common
no-clobber durability/adoption contract. The claim-terminal pair binds that record. All later
recovery state and the claim-terminal or receipt bind the applicable remap; it never rewrites the
original runtime copy or treats the new attestation as the old execution environment. A same-boot
mismatch or an unapproved new selection fails closed. A fake entry already selected inside a
hostile namespace remains outside the declared clean-console/namespace prerequisite; these checks
are defense in depth, not a way to authenticate a hostile shell view.

A claim-terminal envelope freezes the namespace remap applicable when that envelope is published.
If a later boot recovers an unchanged envelope with no claim-ready, the new claim-ready embeds and
signs one bounded post-envelope current-match remap while binding that envelope. If both signed pair
members already exist and only terminal-observed is missing, recovery changes neither member; the
new global terminal-observed generation instead binds the pair digest and current-match remap. Each
additional reboot repeats current-selection authentication for only the still-missing causal object.
Original and same-boot claim-ready records forbid post-envelope remap fields. Claim collection and
archive-integrity validation retain and authenticate a commit-level remap when present; a
ledger-only
remap remains host-admission state outside the signed pair and can never alter its evidence payload.

A complete receipt freezes its transition head, so changed-boot recovery after receipt publication
is a separate causal-completion case. It validates the current and sealed original namespace
selections but creates no transcript, recovery-owner activation, or session transition. When READY
is missing, the newly signed READY embeds the bounded canonical old/new attestation/selection remap
and current-match proof. When READY already exists but terminal-observed is missing, the new global
terminal-observed record binds that remap plus the unchanged READY digest. If both are already
durable, recovery is validation-only and reports `awaiting-clearance`. None of these cases rewrites
the receipt or claims that the new boot was the original execution environment.

Privilege-policy attestations are immutable and versioned beneath canonical nonsymlink
`root:root 0555` `/opt/revoman-benchmark/controlled-privilege-policies`. The selected file is a
bounded regular nonsymlink `root:root 0444` file whose basename is exactly
`cs2a.ENTRY_PROTOCOL.CANONICAL_ENTRY_SHA256.POLICY_GENERATION.POLICY_SHA256.json`, derived from its
content and complete byte digest. The runtime path must be that exact child, not an arbitrary
absolute path. It contains exactly one JSON object with no
additional properties:

```json
{
  "schema": "revoman-controlled-privilege-policy/v1",
  "entryProtocol": "v1",
  "canonicalEntryPath": "/opt/revoman-benchmark/cs2a-session-installer-v1",
  "canonicalEntrySha256": "lowercase-64-hex",
  "privilegeCommandSha256": "lowercase-64-hex",
  "policyRuleSha256": "lowercase-64-hex",
  "policyGeneration": "positive-canonical-decimal",
  "authenticationPerInvocation": true,
  "credentialTicketReuse": "forbidden",
  "launchProofFd": 3,
  "preservedCallerFds": [3],
  "rootProcessTransition": "same-process-exec|direct-child-exec"
}
```

`policyRuleSha256` is the digest of the administrator-exported exact installed rule through the
vendor's trusted policy interface, not caller text. The administrator owns publication and
rotation. `rootProcessTransition` is one exact selected enum value, not the displayed schema
metavalue. `same-process-exec` requires the root entry PID/start time to equal the sealed launcher
identity. `direct-child-exec` requires the live root entry's immediate parent PID/start time to
equal the sealed launcher identity after that process became the exact hash-bound privilege
command; the root entry records its own distinct PID/start time. No other ancestry, reparenting, or
depth is accepted. The root entry revalidates the privilege command, canonical entry, file
bytes/metadata, rule digest, fixed authorization values, and selected relation before durable
admission. An
attestation without the matching installed behavior is a failed provisioning gate, never evidence
that caching is disabled.

Policy selection is itself append-only beneath that parent. Each no-clobber regular nonsymlink
`root:root 0444` `selection.NNNNNN` record contains its canonical sequence, previous-record digest,
and exactly one current fresh-run policy path/digest plus a sorted unique set of retained
recovery-only policy paths/digests. Publication holds the lifecycle lock exclusively, fsyncs the new
policy file, appends and parent-fsyncs the next selection, and never rewrites either. The highest
contiguous valid selection is the only fresh-run high-water; a gap, fork, regression, duplicate
generation, selected old record, missing retained record, or runtime path/digest different from that
fresh entry fails before claim creation. Claim-header and genesis publication seal the exact policy
bytes/identity and selection generation/digest. New entry, rule, or privilege-command versions
receive a new no-clobber policy file, selection generation, and runtime configuration; older records
and command rules are retained only for recovery of claims/sessions that sealed them. Recovery uses
its sealed policy and does not reinterpret the current selection. Compromise response still removes
the affected external authorization before execution.

Successful cache testing is a separate append-only administrator record beneath canonical
`root:root 0555` `/opt/revoman-benchmark/controlled-privilege-probes`. Each bounded regular
nonsymlink `root:root 0444` file has derived basename
`cs2a.POLICY_SHA256.PROBE_GENERATION.PROBE_SHA256.json` and exact schema
`revoman-controlled-privilege-probe/v1`. It binds the selected policy path/digest/generation,
privilege-command and entry identities, controlled-account-policy path/digest, controlled
UID/primary GID/passwd-record digest, normalized group-resolution digest and supplementary set,
namespace selection/attestation identities, launch-account-proof digest and fixed preserved-FD
result, one-way
probe-nonce digest, vendor probe
method digest, first authorized root probe's exact fixed result/status, same-TTY/session identity
digest, sealed launcher PID/start identity, observed root-entry PID/start identity, selected
same-process or direct-child relation and its live `/proc` verification, prompting-disabled second
status proving failure before root/claim, and the bounded results
for every other supported cache scope, plus deferred-invalidation and final ticket-absence results.
Unknown fields, a success from any second entry, premature invalidation, or a probe
performed against another policy/entry/UID/context fails. Publication holds the lifecycle lock
exclusively and uses the common no-clobber durability barrier. Only after that probe may the
administrator publish runtime configuration and approval that name its exact path/digest. Fresh run
requires the highest selected policy and its exact bound successful probe; claim, genesis, receipt,
archive, and report seal both. Every policy, entry, privilege-command, or probe-method change
requires a new probe record and runtime approval. Recovery uses only its sealed prior record.
The probe record binds the immutable lease-begin digest. Its matching complete record binds the
probe in the opposite direction without a publication cycle. Runtime configuration names and binds
both; lease completion is valid only when every selected and installed identity stayed unchanged for
the complete transaction.

The supervisor validates the configuration before launch. It rejects symlinks where ownership is
security-relevant, hashes the Java executable, authenticates the existing UID, privilege-policy,
and host-policy files, and verifies the authorization values, rule digest, policy's semantic digest,
and host fingerprint. `javaHome` and every ancestor are
canonical root-owned directories with no group/other write bit. Every object in that JDK tree is
root-owned and nonwritable by group/other. A bounded administrator-published regular nonsymlink
`root:root 0444` inventory records the exact sorted relative path, type, mode, size, and link target
when applicable, plus the SHA-256 of every JDK-tree object. Any symlink must be relative, resolve
beneath the same inventoried tree, and have its resolved object separately inventoried. The
inventory path and every ancestor are canonical root-owned and nonwritable by group/other. Its path
and digest are exact runtime-config fields. The supervisor securely no-follow/no-cross-device walks
and revalidates the entire tree and inventory immediately before the first Java launch, proves
stable device/inode/type/mode/size/hash metadata across the walk, and commits a
`runtime-verified` transition before any Java
process or controlled child is released. The JDK is a versioned immutable tree whose canonical
path is content/inventory-derived; it is never updated in place. The controlled top-level Gradle
launch receives only that absolute `java` executable, exact `JAVA_HOME`, fixed toolchain selection,
disabled toolchain download and daemon reuse, and no alternate JVM/provider option. Its indirect
Gradle/test JVMs must resolve beneath the same inventoried tree. The protocol does not claim to
interpose a new privileged check before each internal JVM spawn. Instead, the root controller keeps
the shared lifecycle lock and global active-token gate for the complete process-group lifetime, and
finalization repeats the full sealed-inventory walk after group absence and before any
performance-authoritative receipt. It commits that complete bounded result into the terminalization
transition before receipt construction. Any mismatch or nonzero walk status sets
`cleanHostPrerequisite=detected-failure` and permits only a no-decision receipt after
containment/restoration. Root identity or nonwritable metadata alone is insufficient. Hashing only
`$javaHome/bin/java` is never accepted as runtime authentication.

The genesis snapshot seals the approved inventory bytes and records separate
`jdkPrelaunchWalkState` and `jdkPostGroupWalkState` values, both initially `not-started`. A
first-walk failure commits prelaunch state `failed` with one bounded closed-schema diagnostic and a
nonzero supervisor status while the post-group state remains `not-started`; it releases no Java
process or controlled child. Success commits prelaunch state `verified` with the complete inventory
result, retains post-group state `not-started`, and advances execution phase to
`runtime-verified`. After
controlled-group absence, terminalization changes the post-group state exactly once to `failed` or
`verified` and binds the complete second result. A receipt before any Java or controlled-child
release requires post-group `not-started`; any receipt after release requires it to be `failed` or
`verified`. Only `verified` for both states can support a performance decision. No partial
diagnostic is treated as an inventory or performance result.

The controlled-UID policy at the fixed path above is at most 16 KiB and is a regular nonsymlink
`root:root 0444` file containing exactly this JSON schema with no additional properties:

```json
{
  "schema": "revoman-controlled-account/v1",
  "controlledUid": "canonical-unsigned-decimal",
  "controlledPrimaryGid": "canonical-unsigned-decimal",
  "accountName": "canonical-account-name",
  "passwdRecordSha256": "lowercase-64-hex",
  "supplementaryGids": ["sorted-canonical-unsigned-decimal"],
  "privilegedGids": ["sorted-canonical-unsigned-decimal-including-zero"]
}
```

It binds canonical decimal `controlledUid` and `controlledPrimaryGid`, the account name, the exact
seven-field passwd-record SHA-256, the normalized sorted unique supplementary-GID set, and a
root-administrator-declared sorted privileged-GID set that contains GID `0`. Each numeric identity
is parsed first into a wide unsigned integer with checked arithmetic and no sign, whitespace,
leading zero, truncation, or trailing byte. The UID, primary GID, and every supplementary GID must
be nonzero, round-trip losslessly through `uid_t` or `gid_t`, and differ from the all-ones
`(uid_t)-1` or `(gid_t)-1` sentinel. Privileged-set values obey the same lossless GID-domain rule
but may contain `0`, which is mandatory as a denied value. The account's primary and supplementary
sets must be disjoint from the privileged set.

The runtime configuration repeats the exact UID, primary GID, passwd-record digest, and policy
digest. Bounded absolute-path account resolution must return exactly one matching passwd record;
the account name must match `[A-Za-z_][A-Za-z0-9._-]{0,63}`, and normalized group resolution must
match the sealed complete set. The launch branch, root payload, and supervisor repeat that check
before claim publication, pre-fork, and child release while the lifecycle lock excludes account or
NSS-policy maintenance. They reject resolver ambiguity, status failure, UID/GID cast mismatch,
passwd/group drift, and any privileged group. The claim header and genesis seal the exact policy,
passwd record, normalized group-resolution record, UID, and primary GID; the launch record,
transition chain, post-drop proof, receipt, archive, and report bind them transitively.

Administrator provisioning publishes and fully validates that controlled-account policy plus its
exact passwd/group-resolution record while holding the lifecycle lock exclusively. It does so
before any privilege probe lease begins. Lease-begin, every probe invocation, the probe record, and
lease-complete all bind the same bytes and identities; they fail if any account/NSS input changes
during the transaction. An account-policy, passwd, primary/supplementary-group, or resolution
change requires a new lease/probe/complete pair, runtime configuration, and approval. No probe may
bootstrap from an unpublished or caller-supplied account identity.

The native launch process, SSH account, staging directory, and every staged file must resolve to
that exact UID; a `user@host` transport does not authorize another account. No username is checked
in or accepted as a runtime argument. Only authenticated runtime values enter the runner's clean
environment. Before it discovers or executes `dzdo`, native unprivileged `launch` uses only syscalls
to require its real/effective/saved UIDs and GIDs plus live kernel supplementary groups to equal the
sealed nonroot account identity and complete nonprivileged set. A stale login retaining GID `0`, an
extra group, or any database/kernel disagreement aborts before credential input. That same
unprivileged `launch` branch rejects an inherited FD 3, canonicalizes its syscall result plus
boot/PID/start time, namespace/TTY/session identity, exact target mode, and token or probe nonce
into
a bounded `launch-account-proof/v1` memfd, applies and verifies all write, grow, shrink, and seal
seals, installs it as fixed FD 3, and only then `execve`s `dzdo`. The exact attested privilege rule
preserves that one descriptor across `dzdo` and closes every other caller FD. The post-authorization
root `auth-probe|run|recover` invocation never creates or rewrites this proof: it validates the
inherited FD's type, seals, bytes, sealed launcher identity, TTY/session/namespace binding, mode,
token or nonce, and policy identity before claim creation. It separately records its live root-entry
PID/start time and enforces the policy's probed `same-process-exec` or `direct-child-exec` relation
exactly as defined above; it never equates the two identities unless the selected relation requires
that. Missing preservation, replacement, an inherited proof at unprivileged entry, unproved
fork/exec ancestry, reparenting, or any mismatch fails before a root side effect. A host whose
privilege mechanism cannot enforce that descriptor and process-transition contract is not eligible.
The root entry binds both identities, the relation result, and the exact pre-`dzdo` proof into
claim/state/receipt rather than reconstructing them from NSS.
Controlled-child `setgroups(0, NULL)` removes every supplementary group, and the post-drop proof
requires all three UIDs and GIDs to equal the original wide nonzero values after an exact type round
trip.

The runner no longer contains a home-relative JDK or source repository. It receives the
authenticated values from the supervisor, revalidates them without privilege, records them, and
continues to clone only the exact baseline and implementation commits. Comparison and selection
derive host/JDK expectations from the archived authenticated configuration rather than constants.

The Gradle dependency seed is a versioned, root-owned, nonwritable, content-derived tree with a
closed `revoman-gradle-seed/v1` inventory, reviewed deterministic recipe, and root build-provenance
record. Runtime configuration binds the tree, inventory, recipe, and provenance digests, so the
approval binds them through `runtimeConfigSha256`. Its only permitted path classes are the exact
pinned Gradle distribution beneath `wrapper/dists/` and checksum-locked dependency, POM, module,
checksum, and resolution metadata beneath `caches/modules-2/files-2.1/` or the recipe's one exact
versioned `caches/modules-2/metadata-*` grammar. Every file has an independently approved checksum.
All other top-level paths and every init script, `gradle.properties`, credentials/proxy file,
daemon/worker/native state, compiled-script or transformed-output cache, `caches/jars-*`,
`caches/scripts-*`, `caches/transforms-*`, and arbitrary plugin/configuration directory are
forbidden. Plugin artifacts needed by the build are admitted only as checksum-locked dependency
artifacts in the allowed modules tree, never as executable ambient Gradle configuration.

Administrator provisioning materializes the seed twice from independently empty homes using the
pinned Gradle/JDK, dependency locks and verification metadata, reviewed recipe, clean environment,
no daemon, and network-disabled verified input store; byte-identical trees and inventories are a
publication prerequisite. The seed tree is at most 3 GiB and 16,000 entries; its canonical
inventory is at most 3 MiB, with at most 1 MiB aggregate relative-path bytes, 512 bytes and 16
components per path, and 512 MiB per artifact. Recipe and provenance total at most 1 MiB. Checked
totals and every source/copy walk use those limits.

Genesis seals the exact seed inventory bytes, tree-root identity/digest/bounds, recipe, and
provenance. Before child release the supervisor performs a complete stable no-follow source walk
against that sealed inventory; the same transition that advances to `runtime-verified` commits
seed-source state `verified`, while an earlier failure terminalization commits `failed` and every
unreached phase retains `not-started`.

After the post-exec gate but before Gradle, the runner validates and records its observed
environment, then anchored-copies only that verified tree into the separately quota-enforced
nonprojection run-root cache. It sends one fixed bounded copy-ready record on FD 14 containing the
observed-environment bytes/digest and copy totals, then blocks on FD 15. The supervisor invokes the
approved native copier's fixed
`verify-gradle-seed-copy` mode; after its permanent UID/GID drop, the copier walks only the
transition-bound run-root FD and compares every destination byte to the sealed inventory. A
same-phase `seed-copy-verification` transition commits exact state `verified` or `failed`, copier
status, inventory digest, and checked totals. Only after the `verified` transition's parent-fsync
barrier may the supervisor send its digest on FD 15. EOF, mismatch, failure, or early Gradle/Java
execution causes containment and no performance decision. This event is the sole additional
same-phase control event admitted for seed materialization.

The verified destination digest is historical initial-state evidence, not an immutable
post-release assertion. After FD 15 opens, that destination is the writable `GRADLE_USER_HOME`, and
ordinary Gradle locks, metadata, and generated state may change it within quota. Finalization never
compares the mutated destination to the seed inventory; it repeats only the immutable root-owned
source walk after group absence. A pre-gate destination mismatch, later source drift, forbidden
source path, or nonzero verifier status is a pre-capture failure or detected clean-host failure and
yields no performance decision. The copied initial seed may consume at most 3 GiB and 16,000
entries of a distinct 4 GiB, 20,000-entry nonprojection runtime-workspace quota. The remaining 1
GiB and 4,000 entries are reserved before the copy for Gradle-home mutation plus HOME/TMP directory,
block, lock, and generated-state growth. Both checked logical sizes and filesystem-allocated blocks
count, and directory/block overhead cannot consume the reserve invisibly. These trees are excluded
from receipt projection and cannot consume scratch, metadata, or terminal headroom.

The supervisor constructs one canonical NUL-framed `nativeEnv/v1` record from sealed runtime/host
state. Its exact ordered names are `PATH`, `JAVA_HOME`, `HOME`, `GRADLE_USER_HOME`, `TMPDIR`,
`REVOMAN_SOURCE_REPO`, `LC_ALL`, `LANG`, and `TZ`, with no duplicates or extra entry. `PATH` is
exactly the closed root-owned system path from host policy; every nonempty absolute segment is
independently authenticated, and segment values forbid colon and control bytes. Java is invoked
only by its sealed absolute executable, never by PATH lookup. `JAVA_HOME` and
`REVOMAN_SOURCE_REPO` equal the sealed runtime values. `HOME`, `GRADLE_USER_HOME`, and `TMPDIR` are
exactly `RUN_ROOT/runtime/home`, `RUN_ROOT/runtime/gradle-home`, and `RUN_ROOT/runtime/tmp`; locale
and time values are `C`, `C`, and `UTC`. FD 10 is the runner's initial run-root authentication
capability,
not a path intended to survive arbitrary JVM descriptor closure. Before Gradle, the runner
anchored-validates each fixed child against FD 10 and the immutable run-root attestation. Every
descendant receives the canonical paths, so closing or reusing its FD 10 cannot change authority.
Every Java/Gradle option, agent, classpath, toolchain-download, project-property, XDG, proxy, and
ambient configuration variable—including `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`,
`JDK_JAVA_OPTIONS`, `CLASSPATH`, `GRADLE_OPTS`, `JAVA_OPTS`, and every
`ORG_GRADLE_PROJECT_*`—is absent. The native internal mode constructs a fresh `envp` from those
record bytes rather than inheriting one. Bash may synthesize only the closed diagnostic set `PWD`,
`SHLVL`, and `_`; the runner normalizes and records those into a separate
`runnerObservedEnv/v1` record and rejects every other added or changed name. Security-sensitive
bindings must still equal `nativeEnv/v1`; Bash-created values never select a path or executable.
The launch record and `child-launched` transition bind exact `nativeEnv/v1` bytes/digest and the
closed expected observed schema. The runner validates the observed environment after the post-exec
gate but before any seed copy, Java, or Gradle action; its FD-14 record and the durable
`seed-copy-verification` transition bind exact `runnerObservedEnv/v1` bytes/digest and validation
result. The receipt binds both. Gradle uses only fixed offline, no-daemon, no-build-cache,
no-configuration-cache, fixed-toolchain arguments. Any mismatch exits before workload or result
creation.

## Root trust anchor, preparation, and bundle publication

Before any remote session is staged, an administrator separately provisions these immutable
protocol trust anchors:

- `cs2a-session-entry-v1.c` compiled as canonical
  `/opt/revoman-benchmark/cs2a-session-installer-v1`, a regular nonsymlink `root:root 0555` native
  executable, plus bounded regular nonsymlink `root:root 0444`
  `/opt/revoman-benchmark/cs2a-session-entry-v1.provenance.json`;
- the checked-in `cs2a-session-installer-v1.sh` as regular nonsymlink `root:root 0444`
  `/opt/revoman-benchmark/cs2a-session-installer-v1.payload`;
- `cs2a-stage-copier-v1.c` compiled as canonical
  `/opt/revoman-benchmark/cs2a-stage-copier-v1`, a regular nonsymlink `root:root 0555` native
  executable; and
- bounded regular nonsymlink `root:root 0444`
  `/opt/revoman-benchmark/cs2a-stage-copier-v1.provenance.json`;
- `cs2a-receipt-signer-v1.c` compiled as canonical
  `/opt/revoman-benchmark/cs2a-receipt-signer-v1`, a regular nonsymlink `root:root 0555` static
  Ed25519 signer, plus bounded regular nonsymlink `root:root 0444`
  `/opt/revoman-benchmark/cs2a-receipt-signer-v1.provenance.json`; and
- one administrator-generated versioned Ed25519 key pair. The private key is a regular nonsymlink
  `root:root 0400` file beneath canonical `root:root 0700`
  `/opt/revoman-benchmark/cs2a-receipt-signing-private`; the public key is regular nonsymlink
  `root:root 0444` beneath canonical `root:root 0555`
  `/opt/revoman-benchmark/cs2a-receipt-signing-public`. Both filenames are the lowercase 64-hex
  key ID derived from the public bytes. The private key never enters staging, state, receipts, or
  local transport.

Repository session code cannot install, replace, or update any trust anchor. `dzdo` directly execs
the native canonical installer, never a shell script. The same binary's unprivileged `launch`
branch performs only fixed `dzdo` discovery and `execve`; it cannot enter root
`auth-probe|run|recover`, either
clearance mode, read staging, or create state without the real credential boundary. Every root
invocation rejects `launch`. A root invocation reached through the controlled UID's exact command
rule accepts only `auth-probe|run|recover`; `clear-normal|clear-manual-safety` requires an
already-root direct
administrator invocation and the fixed root-owned clearance record. That entry binary is
freestanding/static:
its release ELF has no `PT_INTERP`, `DT_NEEDED`, constructor, NSS, locale, or allocator path before
environment scrub. Bounded syscall-only startup validates mode-appropriate credentials—exact
controlled real/effective/saved UID/GID and supplementary-group set for unprivileged `launch`, exact
root identities for every privileged mode—plus `argc`, the closed mode set and mode-specific
invocation provenance, strict nonce/token,
protocol-root chain,
native binary identity, exact current namespace/rootfs attestation, payload identity/hash, and the
fixed compatible copier/provenance identities embedded by the entry recipe without reading caller
environment. It then selects one of four closed syscall-only descriptor profiles before
normalization. External unprivileged `launch` validates the controlled-account TTY and caller
descriptors, rejects any inherited FD 3, and creates the proof itself only after the live-account
checks above. Privileged `auth-probe|run|recover` validates the same TTY/session continuity and
requires only the exact sealed launch-account-proof FD 3 preserved from that launch. Direct-root
`clear-*` uses the administrator profile and forbids that caller proof. Each external profile
replaces stdin with canonical `/dev/null` at its specified boundary and closes every descriptor not
explicitly allowed for that mode. Internal `controlled-child` forbids the TTY
assumption and instead validates/preserves exactly the pipe and authority descriptors enumerated
below. Internal `lock-guardian` uses its own exact root-only descriptor profile and never execs or
forks. Cross-profile descriptors or provenance fail before state mutation. Before any payload or
child byte runs, all profiles
reset every catchable signal disposition to `SIG_DFL`, replace the signal mask with the exact
empty protocol mask, disable any alternate signal stack, and reject any process state they cannot
normalize safely. It changes cwd to the validated root-owned `/`, rejects an unsafe inherited
seccomp, personality, dumpability, or other process-policy state, and enforces only the
machine-neutral minimum process envelope required for the native-to-payload handoff. It records
the inherited and normalized entry state in the sealed handoff. Benchmark-specific rlimits,
nice/scheduler policy, CPU affinity, cgroup placement, and other host-dependent execution values
come only from the authenticated root-owned host/runtime policy; the payload and supervisor apply
and revalidate that exact policy before any Java process or controlled child is released. A valid
different CPU topology or policy never requires a different native-entry binary. Mode `auth-probe`
validates only the highest selected privilege policy, installed rule,
and active nonce-derived probe lease as specified above, emits only its fixed result, and exits
before claim creation or payload execution. Other credential modes apply the permanent
token-claim contract above; root administrative clearance modes require the existing claim/ledger.
Those non-probe modes exec exactly:

```text
/bin/bash -p /opt/revoman-benchmark/cs2a-session-installer-v1.payload run|recover TOKEN
/bin/bash -p /opt/revoman-benchmark/cs2a-session-installer-v1.payload \
  clear-normal|clear-manual-safety TOKEN REQUEST_SHA256
```

The same approved static binary implements two separately closed internal protocols:
`controlled-child/v1` and `lock-guardian/v1`. Unprivileged `launch`, the controlled UID's `dzdo`
rule, the installer payload, and the root administrative CLI reject both; only the authenticated
supervisor may invoke exact root mode `controlled-child TOKEN` or `lock-guardian TOKEN`. The
`installerProtocol=v1` approval, entry source, build recipe/provenance, canonical binary hash,
retirement lifecycle, genesis state, transition chain, and receipt bind both modes as part of that
same native entry—there is no unapproved `setpriv`, `runuser`, shell helper, guardian executable,
or fourth native artifact.

Internal child mode accepts only fixed FDs: `/dev/null` FD 0, bounded child stdout/stderr pipe FDs
1 and 2, launch-record memfd 3, token-lock OFD 8, read-only benchmark-authorization FD 9, run-root
directory FD 10, pre-exec release-pipe FD 11, `CLOEXEC` exec-status pipe FD 12, and post-exec gate
pipe FD 13, bounded seed-copy-ready pipe FD 14, and seed-verification release pipe FD 15. The
authenticated independent root guardian alone retains the true
benchmark-lock-owning OFD after its durable handoff; it is never inherited by controlled code. FD
9 is a distinct non-lock-owning `O_RDONLY` open-file
description for the same canonical `root:root 0600` lock inode. The runner requires its identity and
an exclusive nonblocking lock attempt to report contended; `LOCK_UN`, duplicate/close, write, or
truncate through FD 9 cannot release or mutate the root guardian's lock.

Guardian mode accepts exact root credentials and the recorded supervisor parent only. Its fixed
profile is `/dev/null` FDs 0, 1, and 2; true benchmark-lock OFD 3; root-owned controlled-cgroup
directory FD 4; durable session-state directory FD 5; registration/normal-release read FD 6;
acknowledgement write FD 7; and controller-liveness read FD 8. It closes everything else before its
loop. Using only bounded syscalls and anchored relative opens beneath FDs 4 and 5, it validates
registration/activation/terminal transitions, polls controller liveness and cgroup membership,
writes `cgroup.kill` when required, waits for exact emptiness, acknowledges fixed records, and
closes lock FD 3 only under the release contract. It never invokes an allocator, shell, NSS,
external command, `fork`, `clone`, or `exec`, and installs a fixed no-new-privileges seccomp
allowlist after startup. No descendant or helper can inherit, duplicate, unlock, or outlive its true
lock OFD.

Before fork, the parent creates FD 3 with `memfd_create(MFD_ALLOW_SEALING)` and the child validates
only that empty descriptor shell, then blocks without parsing it. After fork, the parent
authenticates
the child's boot/PID/start-time/PGID/cgroup and native-entry identity, writes one bounded canonical
launch record binding those values, the token, exact entry/Bash/runner identities, controlled UID,
primary GID, UID-policy and passwd/group-resolution digests, the original wide parsed identity
values, run-root attestation, prior transition head, intended child event, clean environment, and
every FD identity, then applies and verifies
`F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL`.
The complete `child-launched` transition embeds those exact bytes and digest; the volatile memfd is
never the durable authority. Only after that transition's parent-fsync barrier may the parent send
one bounded release record on FD 11 binding its digest, child identity, and launch-record digest.
EOF, short/extra/different bytes, owner death, mutable/unsealed FD 3, or inability to anchored-open
and validate that exact committed transition makes the child exit and close the token lock without
executing.

After a valid release, internal child mode reads and validates sealed FD 3, then closes FDs 3, 8,
and 11 plus every unlisted FD, leaving exactly `0`, `1`, `2`, `9`, `10`, `12`, `13`, `14`, and
`15`. It performs one
checked irreversible credential sequence while the required capability remains available: set and
lock keep-caps-off/noroot securebits and drop the capability bounding set while retaining
`CAP_SETPCAP`; clear ambient capabilities; call `setgroups(0, NULL)` while retaining `CAP_SETGID`;
set every real/effective/saved GID to the losslessly parsed nonzero authenticated primary GID; set
every real/effective/saved UID to the losslessly parsed nonzero authenticated controlled UID last
while retaining `CAP_SETUID`; then clear and verify permitted, effective, inheritable, bounding, and
ambient capabilities plus all IDs, groups, and securebits. `getresuid`, `getresgid`, and
`getgroups` must prove that every kernel value round-trips to the original wide value, all three
UIDs/GIDs equal the sealed nonzero targets, and the supplementary set is empty. It then sets and
verifies `no_new_privs` and post-validates the descriptor
allowlist, namespace/cgroup/process profile, and run-root identity. Every syscall or postcondition
failure exits before runner execution; no fallback order is allowed. It then
executes only the opened/hash-validated canonical runner through the fixed root-owned Bash with
fixed argv `--run-root-fd 10` and the sealed clean environment. FD 12 is the sole write end of a
root-read exec-status pipe. After the credential and descriptor checks, the native child writes one
bounded `post-drop-proof/v1` record containing the checked wide UID/GID inputs and exact observed
IDs, empty group set, capability/securebit/no-new-privileges results, namespace/cgroup profile, and
FD allowlist. FD 12 has exactly three length-prefixed terminal sequences: a failure before proof is
`FAIL_PRE+EOF`; successful exec is `PROOF+EOF`; and `execve` returning failure is
`PROOF+FAIL_EXEC+EOF`. `FAIL_PRE` and `FAIL_EXEC` contain only one closed numeric stage/errno code.
Successful `execve` closes FD 12 by `CLOEXEC`; a returned failure writes `FAIL_EXEC` before exit.
`PROOF+EOF` is success only when the parent also observes the exact Bash endpoint below; death or a
signal after proof but before exec is therefore failed, not a successful handoff. No other framing,
short record, extra byte, or ordering is accepted. The reviewed runner's first action is to block on
FD 13 before Gradle, Java, filesystem output, or any benchmark work. Parent-observed exact
post-drop proof followed by FD-12 EOF plus exact
same-boot/PID/start-time/PGID/cgroup and Bash executable identity allows one durable same-phase
`process-identity-handoff` transition binding the native pre-state, post-drop proof, authorized
Bash/runner edge, and member rules. Only after that barrier may the parent send the exact handoff
digest on FD 13; EOF or mismatch makes the runner exit before work. The runner closes FD 13 and
retains only FDs `0`, `1`,
`2`, `9`, `10`, `14`, and `15` during the seed-copy protocol. It closes 14 and 15 after the exact
verified transition digest arrives, then begins Gradle with only `0`, `1`, `2`, `9`, and `10`.
Any residual privilege, identity or FD mismatch, runner substitution, unexpected exec/member,
seed-gate bypass, or exec failure exits without workload authority. The native entry remains the
UID-transition implementation; the supervisor remains its sole authenticated caller and lifecycle
owner.

Terminal transition snapshots and receipts carry separate closed
`controlledChildEvidenceState` and `postDropIdentityState` values, each exactly
`not-started|failed|verified`. Before `child-launched`, both are `not-started` and every
controlled-child launch, release, exec, and post-drop-proof field is forbidden. If the child path is
reached but no valid process-identity handoff becomes durable, terminalization records `failed`, one
bounded closed diagnostic, and only the exact launch/release records that actually became durable;
it never fabricates a post-drop proof. `verified` for both states requires the complete
`child-launched` record, transition-bound release, exact FD-12 proof/EOF, and durable
process-identity handoff. A performance decision requires both verified. These states describe only
the controlled-child path; the mandatory pre-`dzdo` launch-account proof remains a separate field
for every durable session.

For installer and clearance Bash-payload execution only, the `execve` environment is an exact fixed
set containing the closed absolute `PATH`, `IFS`, `LC_ALL=C`, and `TZ=UTC`; `HOME` is absent and
the validated cwd is `/`. Internal `controlled-child` instead uses the exact `nativeEnv/v1` and
`runnerObservedEnv/v1` contract above. The launcher sets `umask 077`. It passes the locked
admission FD, claim FD when present, shared lifecycle/retirement-policy FD in every root mode, and
one sealed fixed-FD handoff that binds the native entry identity, mode, and token.
The Bash payload validates that handoff and repeats the
root-credential/mode/token/request/path checks
before any external command. Bash privileged mode plus the clean environment prevents `BASH_ENV`,
`ENV`, imported functions, `SHELLOPTS`, `BASHOPTS`, `CDPATH`, `GLOBIGNORE`, `LD_PRELOAD`,
`LD_AUDIT`,
`LD_LIBRARY_PATH`, `GCONV_PATH`, `LOCPATH`, `GLIBC_TUNABLES`, or a poisoned `PATH` from running
caller bytes. Every later privileged shell entry uses the same `/bin/bash -p` and exact clean-env
contract. The payload never reads commands from stdin, evaluates caller strings, or sources a
caller-selected file. Administrator `dzdo` sanitation is defense in depth, not the trust boundary.

The entry build recipe embeds the exact frozen payload, compatible copier executable, and copier
provenance SHA-256 values; its provenance binds those inputs and the resulting native binary.
Startup compares each opened root-owned object to its embedded digest before `execve`, and the
installer payload later requires the selected approval to bind the same entry, provenance,
payload, and copier digests. No staged or environment byte supplies an expected hash.

The native copier implements controlled staging ingestion, fixed Gradle-seed destination
verification, and controlled-run projection sealing with anchored `openat2`-style
`RESOLVE_BENEATH`, `RESOLVE_NO_SYMLINKS`, and `RESOLVE_NO_XDEV` semantics. Before it opens
controlled content, it permanently drops all real,
effective, and saved user and group IDs to the authenticated controlled identity; clears
supplementary groups, capabilities, ambient capabilities, and privilege-retaining securebits; sets
`no_new_privs`; and closes every descriptor except the exact anchored source-directory and hidden
output descriptors required by its fixed mode. It uses the same wide, lossless, nonzero UID/GID
parse and checked credential-drop ordering as internal child mode, then proves all three IDs equal
the original values, the supplementary set is empty, and the descriptor allowlist is exact before
reading. It accepts no absolute path or caller-selected destination.

The checked-in `cs2a-session-entry-v1.build.json`, `cs2a-stage-copier-v1.build.json`, and
`cs2a-receipt-signer-v1.build.json` fix each Linux target, compiler/toolchain and static-library
artifact digest, complete source set, warning policy, and every compile/link flag. Release
compilation is
warning-free with warnings-as-errors and enables PIE, full RELRO/NOW, stack protection, fortified
bounded operations, and the other supported deterministic hardening flags. The attestation binds
each source, build-recipe, toolchain, and release-binary hash and both bit-identical pinned-build
results. The entry recipe additionally proves the required freestanding/static ELF properties. The
signer recipe pins and statically links its reviewed Ed25519 implementation and exposes only
fixed-FD canonical-envelope signing; it accepts no caller-selected key path. Separate ASan and
UBSan builds plus a fuzz/property corpus exercise environment-independent entry, argv,
relative-path, inventory, descriptor, guardian transition/cgroup, envelope, signature, and
`openat2` failure parsing. The Linux/root end-to-end suite executes the exact three release binaries
whose digests enter the
approval, not a sanitizer or substitute build.

All three v1 native sources, recipes, attestations, and canonical bytes are frozen permanently. A
future correction must add a new versioned source and canonical path, retain every older version
needed for session recovery, update the corresponding entry protocol, and receive a new approval;
it must never overwrite or reinterpret v1.

The administrator also provisions a versioned approval record beneath
`/opt/revoman-benchmark/cs2a-approvals/`. The parent is `root:root 0555`; each record is a regular
nonsymlink `root:root 0444` file of at most 8 KiB published without replacement. Its path is exactly
`cs2a.IMPLEMENTATION_SHA.BUNDLE_MANIFEST_SHA256.RUNTIME_CONFIG_SHA256.json`, and it contains one
JSON object with exactly these keys and no additional properties:

```json
{
  "schema": "revoman-cs2a-approval/v1",
  "implementationSha": "lowercase-40-hex",
  "bundleManifestSha256": "lowercase-64-hex",
  "runtimeConfigSha256": "lowercase-64-hex",
  "installerProtocol": "v1",
  "installerEntrySourceSha256": "lowercase-64-hex",
  "installerEntryBuildRecipeSha256": "lowercase-64-hex",
  "installerEntryBuildProvenanceSha256": "lowercase-64-hex",
  "canonicalInstallerSha256": "lowercase-64-hex",
  "installerPayloadSha256": "lowercase-64-hex",
  "stageCopierProtocol": "v1",
  "stageCopierSourceSha256": "lowercase-64-hex",
  "stageCopierBuildRecipeSha256": "lowercase-64-hex",
  "stageCopierBuildProvenanceSha256": "lowercase-64-hex",
  "canonicalStageCopierSha256": "lowercase-64-hex",
  "receiptSignerProtocol": "v1",
  "receiptSignerSourceSha256": "lowercase-64-hex",
  "receiptSignerBuildRecipeSha256": "lowercase-64-hex",
  "receiptSignerBuildProvenanceSha256": "lowercase-64-hex",
  "canonicalReceiptSignerSha256": "lowercase-64-hex",
  "remoteSessionSha256": "lowercase-64-hex",
  "bundleId": "cs2a-bundle.lowercase-40-hex.lowercase-64-hex"
}
```

The filename and `bundleId` are exact derivations of the content fields. The installer requires
exactly this approval and requires every field to match the staged bytes and pre-provisioned state.
Absence, ambiguity, wrong metadata, or replay against another implementation, bundle, installer,
copier, signer, signing key, or runtime configuration fails before root executes staged code.

Append-only approval never means permanent fresh-run authorization. Administrators may publish
irreversible bounded regular nonsymlink `root:root 0444` retirement records beneath the canonical
`root:root 0555` tree `/opt/revoman-benchmark/cs2a-retirements/`, with exact derived paths for an
installer protocol, copier protocol, signer protocol, signing-key ID, implementation SHA,
approval-record SHA-256, or bundle ID.
Repository code can neither create nor remove them. Publication takes the exclusive lock on
canonical regular nonsymlink `root:root 0600`
`/opt/revoman-benchmark/cs2a-retirement-policy.lock`, fsyncs each no-clobber record, and atomically
appends a generation record containing the complete sorted retirement inventory digest. The lock
is opened no-follow beneath its authenticated nonwritable parent and its device/inode/type/mode are
bound by every generation, fresh-run state, and receipt. The controlled UID cannot open a
lock-capable descriptor for it and therefore cannot indefinitely block an administrator's
exclusive retirement publication.

That same file is the protocol lifecycle lock. Every planned publication, rotation, in-place
maintenance prohibition, and removal for runtime configuration, the complete versioned JDK tree and
inventory, Gradle dependency seed/inventory/recipe/provenance, UID policy/account and NSS/group
resolution state, namespace
selection/attestation, host policy/fingerprint,
privilege-policy
file and selection chain,
privilege-command binary/rule/probe, signer/signing key, approval, bundle, or canonical protocol
code
holds it exclusively. Package managers, service automation, and other out-of-band maintenance may
not mutate any bound object while a shared lifecycle descriptor exists; provisioning either
participates in this lock or takes the host out of benchmark service first. A
planned removal validates the complete protected claim/session trees and is forbidden while any
unclosed permanent claim or durable session binds the object. Fresh `run` acquires one shared
open-file-description lock before claim-header authentication/publication, and the same descriptor
is inherited continuously by the complete privileged controller lineage through claim-terminal or
quarantine publication, or through receipt, READY, and terminal-observed global-ledger publication.
Recovery acquires that shared lock before it opens claim/session-bound artifacts and likewise
inherits it through its last safety, signing, and publication action. Finalizer, signer,
claim-terminal publisher, receipt publisher, and READY publisher never reacquire a lifecycle lock
while holding the token lock; they use only the inherited shared descriptor. Controlled
runners/workloads and any unrelated child close it before executing. Thus an exclusive
administrator cannot observe zero references and race a new reference, finalizer, recovery, or
signer open. Every exclusive updater also validates that the global-session ledger has no active
token. If a controller dies while its child survives, that durable active-token record remains
blocking, so loss of every shared descriptor cannot authorize maintenance. Lock order is fixed as
admission mutex, lifecycle lock, token claim lock, then benchmark lock; no component may invert it
or acquire an earlier lock while holding a later one.
The privilege-probe lease transaction above is also serialized by this lock, and every listed
updater checks that no active lease exists before publication or mutation.
Compromise response is the explicit exception: external authorization may remove a dangerous
artifact and force manual safety-only quarantine rather than execute compromised code.

Native-entry mode `run` acquires and inherits a shared policy lock, then denies a retired installer
protocol before invoking Bash. Before the first copier process is spawned, the installer payload
revalidates the latest complete policy generation using only root-owned state and denies the fixed
entry-associated copier protocol and exact embedded copier identity if retired. Only
after that denial check may the copier seal the selector inputs. After deriving the one approval,
the payload denies any matching signer, signing key, implementation, approval, or bundle retirement
before copying the remaining staged assets or executing a canonical bundle. It holds that same
inherited shared lock through the complete terminal publication path. The durable
`bundle-installed` transition records the exact retirement generation,
inventory digest, and relevant absence proofs; later retirement cannot retroactively invalidate an
already durable session. Native-entry mode `recover` instead requires an existing permanent token
claim and validates its immutable header. Without durable genesis it may use only that header's
entry/runtime/signer/key identities to publish the claim-terminal pair or quarantine; it never
opens staging, invokes the copier, selects an approval/bundle, or launches an orchestrator/workload.
With durable state it uses that state's sealed approval/bundle. Both recovery branches ignore later
ordinary retirement for recovery only. Old entry/signer/key versions bound by an unclosed claim and
old binaries/approvals bound by durable sessions remain installed solely to finish or recover those
claims/sessions, never to authorize a new run.

Ordinary retirement assumes every recovery-executed trust anchor remains safe enough to run its
check. Suspected compromise of an entry, payload, copier, signer, orchestrator, supervisor, runner,
validator, approval, or bundle is deliberately not represented by a self-enforced repository
"compromise set." It is an external incident boundary: the administrator takes the host out of
benchmark service through independently trusted OS and privilege-policy controls before the
suspected component's next instruction. For a native-entry/startup flaw, that includes removing its
`dzdo` authorization and executable reachability; its own self-check is never a barrier. No fresh
run or automatic recovery under this protocol is then permitted. Affected live claims/sessions use
only a separately reviewed administrator safety procedure or trusted replacement recovery path to
contain processes and restore governors, and can produce only quarantine evidence plus a
`manual-safety` global clearance. If the native entry or its payload is suspected, its own clearance
mode is forbidden: the administrator must reprovision/reset the host under external authority or use
a separately reviewed versioned replacement clearance tool that validates the old immutable ledger
without executing any old byte. The manual-safety generation binds that replacement's exact
identity/provenance and remediation record. The unsafe artifact remains unavailable even if this
sacrifices
automatic recovery. A corrected version receives a new canonical path, approval, and external
authorization. Component-compromise incident designation is an explicit external override, not a
claim that repository validators maintain a compromise registry: administrators suspend collection,
validation, reporting, and every existing performance conclusion involving the suspected identity
until a separately reviewed incident correction decides disposition. This design makes no automated
post-hoc component-validity claim. The separate offline receipt-key compromise policy remains
authoritative for local signature acceptance; repository or remote self-assertion cannot clear
either incident.

The canonical bundle parent is the canonical nonsymlink `root:root 0555` directory
`/opt/revoman-benchmark/cs2a-bundles`. A bundle may appear only at
`/opt/revoman-benchmark/cs2a-bundles/BUNDLE_ID`, where `BUNDLE_ID` is the exact approval-derived
value. No runtime or staged path can select another bundle location.

`--prepare-remote-session` first authenticates a clean detached checkout of the exact implementation
SHA and byte-compares all fourteen security-critical sources against that checkout:

- `cs2a-controlled-run.sh`;
- `cs2a-governor-supervisor.sh`;
- `cs2a-operator.sh`;
- `cs2a-validate-manifest.jq`;
- `cs2a-session-installer-v1.sh`;
- `cs2a-session-entry-v1.c`;
- `cs2a-session-entry-v1.build.json`;
- `cs2a-stage-copier-v1.c`;
- `cs2a-stage-copier-v1.build.json`;
- `cs2a-receipt-signer-v1.c`;
- `cs2a-receipt-signer-v1.build.json`;
- `cs2a-remote-session.sh`;
- `cs2a-local-verifier-v1.build.json`; and
- `ReceiptSignatureVerifier.kt` beneath the exact directory
  `benchmark-driver/src/main/kotlin/com/salesforce/revoman/benchmark/driver/integrity/`.

The first thirteen paths are all beneath the exact repository directory
`docs/superpowers/benchmarks/operators/`; the fourteenth path is the verifier path stated above.

Before it creates a session token or remote path, preparation deterministically builds the approved
staged-bundle manifest. That manifest contains exactly these five staged relative paths and no
other asset:

```text
cs2a-controlled-run.sh
cs2a-governor-supervisor.sh
cs2a-operator.sh
cs2a-validate-manifest.jq
cs2a-remote-session.sh
```

It excludes the installer payload, local-verifier recipe/source/distribution, and every native
entry, copier, and signer source/recipe/provenance/binary, whose independent identities are bound
by their root approval or authenticated local-session fields.
The manifest contains only the sorted, unique, fixed relative protocol
paths, exact byte sizes, and SHA-256 values: no token, hostname, user, remote prefix, timestamp,
absolute path, or transport metadata. Every asset also has a conservative schema-defined maximum
size. Identical implementation inputs therefore produce the same manifest digest before a session
exists. The approval's `bundleManifestSha256` binds this staged-bundle manifest, while its
separately filename-bound `runtimeConfigSha256` permits distinct append-only approvals for distinct
authenticated host-runtime configurations. After a token is created, a separate transport
inventory binds the token-derived remote stage paths and maps bijectively to those five staged
paths only; it is never an approval input.

It then performs a read-only remote preflight for the native entry, payload, copier, signer, all
three build attestations, signing public key/fingerprint, retirement-policy generation, and exact
approval record. If approval is absent, it
prints the exact approval-record bytes and expected destination,
creates no session token or remote stage, and stops for independent administrator provisioning.
This `provisioning-required` stop is not a measurement attempt and creates no attempt archive.
After approval exists, a fresh invocation verifies that the native installer, shell payload,
copier, signer, and signing-key metadata/digests, reviewed sources and recipes, and all three
root-owned build attestations equal the approval and runtime configuration. It creates a
never-reused remote `cs2a-session.TOKEN` directory
and copies the five session assets, implementation marker, runtime-config digest marker, approved
staged-bundle manifest, and token-derived transport inventory. It then reads the copied bytes back
through hashes. Fixed shared staging filenames are removed.

No staged launcher exists, and staging cannot nominate an expected executable hash. The canonical
native entry performs the clean handoff to the root-owned installer payload. That payload
reconstructs the stage from the strict token but never
path-opens controlled-writable content with root credentials. It validates the inherited acquired
claim-lock FD, exact immutable claim header, and unchanged runtime/signer/key identities bound by
that header. It holds that open-file-description lock through state publication, `exec`,
orchestration, finalization, receipt/READY publication, and terminal exit. A held token lock reports
`in-progress` and permits no second run or recovery mutation.

The installer first uses the bounded controlled-UID copier to seal only the untrusted selector
inputs: the fixed-size implementation marker, bounded staged-bundle manifest, and bounded runtime
digest marker. It computes the implementation, manifest digest, and current fixed runtime-config
digest from root-owned copies/current root state, requires the marker to equal that current digest,
derives the one exact approval filename, and opens only that record beneath the already-open
approval parent. It never scans or accepts another
approval. The record must cross-bind all three selectors plus native-entry, installer-payload,
copier, and orchestrator identities before any remaining staged byte is copied or parsed.

The installer then copies the transport inventory and all five approved assets through a fixed-argv
copier that permanently drops to the authenticated controlled UID/primary-GID identity before
opening source content.
Source opens are anchored beneath an already-validated stage descriptor, no-follow,
no-cross-device, and bounded by exact or schema-maximum byte counts. Every source must be regular,
`nlink == 1`, and stable in device/inode/type/link count/size/hash before and after copying. The
staged manifest is bounded before parsing and must match the approval digest; only its root-owned
hidden copy is parsed. Each asset's copied bytes must equal its manifest size and SHA-256. The
transport inventory is similarly bounded and must be a bijection over the exact staged assets. A
FIFO, device, socket, directory, symlink, hardlink, oversize/short stream, timeout, identity change,
copy failure, or digest mismatch produces one generic failure, removes every hidden candidate, and
publishes no bundle or state. An unavailable required kernel primitive or any failure of the
approved canonical copier fails closed; root never falls back to shell path opening or another
helper.

The root copies become one hidden bundle candidate containing all five approved assets plus the
approved staged-bundle manifest. The token-specific transport inventory is validated against
staging but is not part of the reusable canonical bundle. The installer verifies every post-copy
hash, path, type, owner, and mode, then atomically publishes the complete digest-versioned
`root:root 0555` canonical bundle directory without replacement. An existing byte-identical
canonical bundle may be reused only after complete validation. Partial, different, symlinked,
wrong-metadata, or raced destinations fail closed and remain unmodified.

Before abandoning mutable staging, the installer atomically creates the token-bound durable root
session directory. It seals regular root-owned `0400` exact copies of the transport inventory,
selected approval, runtime configuration, referenced JDK-tree inventory, Gradle-seed inventory,
recipe and provenance, seed-tree identity/digest/bounds, controlled-UID policy, exact passwd and
normalized group-resolution records, parsed UID and primary GID,
selected versioned privilege-policy, selection generation, and successful privilege-probe/complete
pair, namespace selection/attestation, controlled-host policy, signer public key, key
ID/fingerprint,
signer executable/provenance, and
private-key path metadata but never private-key bytes, plus a root-generated transport attestation
and protocol-selected privilege-command resolution record. It seals the fresh-run retirement-policy
generation, inventory, shared-lock identity, and relevant absence proofs. It also records the
native entry's exact sealed launch-account proof, inherited/normalized machine-neutral process
state, and the exact authenticated host-policy execution profile that must be applied before child
release, plus the canonical
native-entry, installer-payload, copier, and signer paths, device/inode/type/owner/mode,
executable/payload SHA-256 values, reviewed source SHA-256 values, and versioned protocols, and
seals all three exact native build recipes and root provenance records. The
attestation
binds the inventory SHA-256, token, staging-directory device/inode/owner/mode, staged-bundle
manifest digest, implementation, runtime-config digest, approval identity, and canonical bundle
identity.
Staging cannot supply an expected attestation digest. The state also creates the exact root-owned
initial transcript segment in active mode `0600`, records the lock identity, boot ID, and
installer/orchestrator PID, start time, shell executable identity/hash, and canonical script hashes,
then publishes the atomic genesis transition snapshot at phase `bundle-installed` last. A partial or
pre-existing session directory fails closed. Fresh execution must compare current runtime,
approval, account, JDK/Gradle-seed, policy, installer, copier, signer, and signing-key bytes and
metadata to these sealed
originals; recovery and receipts use only the sealed copies even if the fixed host files later
change.

Phase, marker, process-identity, and terminal-status durability uses one transaction protocol. No
independently published mutable phase file, marker file, status file, or `current` pointer is
authoritative. Every transition is a new root-owned no-clobber directory
`transitions/transition.NNNNNN` containing an exact-schema record with its sequence, previous-record
digest, strictly increasing `controlEpoch`, closed-schema `eventKind`, monotonic `executionPhase`,
complete marker set, authenticated process/group identities, transcript-set state, and optional
terminal status plus source. A transition carrying `GOVERNOR_STATE` contains the exact small
root-owned marker bytes and binds the referenced durable object's canonical identity and digest. A
transition carrying `RUN_ROOT` instead contains a root-owned immutable run-root identity attestation
and binds its digest. That attestation records the canonical root-owned parent and child path,
boot-local mount/device, persistent semantic filesystem identity, directory device/inode,
controlled UID/GID, required mode `0700`, project/quota ID and limits, final-directory and parent
durability barriers, fixed launch FD number, and opened-FD identity. It explicitly excludes
directory entries/content and mutable directory size, mtime, and ctime. Legitimate controlled-run
writes therefore cannot invalidate the marker. Recovery and finalization anchored-reopen the
parent and child and revalidate every stable attested field. On the same boot, every mount/device
field must match. After an authenticated reboot remap, only the explicitly mapped boot-local
mount/device fields
may differ, while semantic filesystem, path, directory inode, owner/mode/quota, and
all other fields must match. Entry replacement or unremapped drift fails; contents remain untrusted
until bounded copier sealing. The publisher constructs and fsyncs every file and the containing
hidden directory,
validates the complete snapshot, and commits it with one atomic rename. The highest contiguous,
hash-linked, fully valid sequence is the only current state; a gap, duplicate, collision,
unauthorized mutable file, partial directory, or competing chain fails closed.

The only deliberately mutable transition member is the one active transcript inode created inside
an activation candidate. The immutable activation record binds its exact path, device/inode, owner,
initial mode `0600`, initial size zero, writer-owner identity, and allowed inherited-writer set, but
explicitly excludes its changing bytes from the transition digest. After the activation directory
and parent are fsynced and atomically published, the recorded owner opens no new path: it writes
only through that already-open inode. A later `transcript-seal` transition is allowed only after
proving
every recorded writer absent or closed; it fsyncs that same inode, changes it to `0400`, fsyncs it
and its parent, and binds its final device/inode/size/SHA-256/metadata. Historical validation
permits only this active-to-sealed lifecycle for the same inode. Any replacement, second active
transcript, unrecorded writer, reopened path, changed non-transcript member, or post-seal mutation
fails closed. A kill before activation publication leaves only a removable hidden candidate; a kill
after publication but before first write leaves a valid empty active transcript that recovery can
seal. A kill after the inode is chmod/fsynced `0400` but before the seal transition leaves one
admitted prepared-seal shape: with the exact recorded device/inode and every owner/writer absent,
recovery accepts only mode `0600` or already-`0400`, idempotently chmods/fsyncs it `0400`, computes
the final hash, and commits the seal transition. Any other mode or identity fails. Fault tests cover
both sides of chmod, inode fsync, transition rename, and transition-parent fsync.

Each committed transition is a complete snapshot, not a delta. `executionPhase` may advance only
through the closed phase graph. A snapshot may retain the same execution phase only for one of the
closed control events: recovery-owner activation/rotation, process-identity handoff,
guardian-activation, guardian-release, seed-copy-verification, transcript seal, or terminalization.
Every such event advances
`controlEpoch`, retains the entire execution
snapshot, and carries the exact new identity, transcript, or status state. Any other repeated,
regressed, skipped, or unknown phase/event combination fails closed. Recovery first seals any
abandoned active transcript after proving the earlier owner and every writer absent, then atomically
publishes its new owner identity and new active transcript in one activation bound to the prior
digest. On a changed boot, that first activation also must carry the exact namespace-remap fields
defined above; on the same boot those fields are forbidden. No process may write a recovery
transcript before that activation becomes durable.

The approved static native entry has the root-only internal `lock-guardian/v1` mode above; it is not
exposed through the operator, credential rule, controlled child, installer payload, or orchestrator.
It accepts no path or status. Before governor mutation or child fork, the supervisor acquires the
canonical benchmark lock, creates the fixed root-owned cgroup-v2 child and bounded liveness/release
channels, and starts an independent root
guardian with only the true lock-owning OFD, the authenticated cgroup FD, fixed session-state FD,
and those channels. The guardian initially blocks without acting on a sole-parent release channel;
EOF, wrong bytes, or parent death in this precommit state makes it exit and close the inherited
lock. A same-phase `process-identity-handoff` transition then binds the guardian's boot/PID/start
time, native-entry source/recipe/provenance/executable hashes, protected root cgroup, FD identities,
exact lock inode, and expected release digest. Only after that transition's parent-fsync barrier may
the supervisor send the exact
digest. The guardian anchored-validates the committed registration and returns one fixed ready
acknowledgement but remains preactive. The supervisor then commits a same-phase
`guardian-activation` event binding that acknowledgement and state `active`. The guardian enters
active behavior only after it anchored-reads that durable event and returns a second fixed
acknowledgement; on parent EOF it first checks for the exact event, activating only when it is
durable and otherwise exiting. The supervisor retains its own true-lock duplicate until the active
acknowledgement, then closes it; the guardian becomes the sole lock owner. No governor mutation or
controlled child may precede that acknowledgement. A hidden candidate is removable only after the
precommit guardian and parent are absent. A committed registration without activation is a closed
pre-mutation `lock-held` failure that recovery may acquire and terminalize; it is never inferred to
be an active guardian. Controlled code receives only the distinct read-only FD 9 described above.

The authenticated host policy binds the exact cgroup-v2 mount/namespace identity, root-owned
parent, required `cgroup.kill` and `cgroup.events` semantics, controller set, membership rules, and
denial of controlled-UID migration or subtree control. The supervisor and native guardian
anchored-validate those facts before registration; an unavailable controller or kill/emptiness
primitive is a pre-mutation launch failure, never a fallback to process-name enumeration.

The guardian retains the true lock while any controlled cgroup member exists and through exact
governor restoration. On normal completion it releases only after validating a durable
restoration/terminal transition and an exact controller release record on its inherited channel.
If the controller or its watchdog disappears, the independent guardian invokes `cgroup.kill`, waits
for `populated=0`, and continues holding the lock for token-only recovery. The guardian does not
hold the token claim lock, so recovery can acquire that lock, authenticate the durable guardian
identity, prove the guardian's lock is contended through a separate open description, restore from
the sealed snapshot, and commit a terminal/restoration transition whose closed fields explicitly
authorize guardian release. The guardian watches only the already-open fixed session-state FD,
authenticates that exact transition and cgroup-empty state, and then publishes one bounded
`guardian-release-ack/v1` record beneath FD 5. The token/control-epoch-derived record binds the
guardian identity, release-record digest, exact terminal/restoration transition head, cgroup-empty
result, lock inode/OFD identity, and release kind. It uses the common hidden-candidate,
no-clobber-rename, file-fsync, and parent-fsync/adoption barrier. Only after that barrier does the
guardian emit the exact ACK digest on FD 7, close ACK FD 7 and lock FD 3, and exit. The original
controller requires the exact ACK framing followed by EOF; recovery may adopt the complete durable
ACK after authenticating the same state when the original volatile reader no longer exists.
`guardian-release` is published only after the ACK, guardian exit, and released-lock
acquisition/probe all agree. Death or EOF before a complete durable ACK sets guardian state
`failed` and `cleanHostPrerequisite=detected-failure`; death after the ACK barrier is an adoptable
release completion, never inferred merely from process absence. Recovery proves release through
its separate lock description; no recovery-selected path or reopened control socket exists. Thus
controller death cannot create a release-before-containment or release-before-restoration window.

Unexpected guardian death is a detected clean-host failure, never a performance-authoritative run.
The durable global active-token gate forbids every conforming benchmark entrant while recovery
contains the cgroup and acquires the released lock; an unrelated holder makes recovery remain
fail-closed. Direct raw lock use outside that gate is an excluded hostile-host action and cannot
produce a valid claim, launch chain, receipt decision, or clearance. The host is taken out of
benchmark service if it cannot guarantee that all benchmark entrants use this admission gate.
Receipt evidence binds guardian identity, lifetime, cgroup-empty proof, and normal or recovery
release record.

Guardian state in the transition chain is closed: `not-started` before lock acquisition,
`registered` after the first identity transition, `active` only after the activation transition,
`released` only after an exact same-phase `guardian-release` event proves cgroup absence,
authorized restoration, the exact durable final ACK and any required FD-7 notification, guardian
exit, and released-lock acquisition/probe, or `failed` with one bounded reason.
`guardian-activation` and `guardian-release` are the only additional repeated-phase control events.
Receipts before the lock phase require `not-started` and forbid guardian handoff fields. Every later
receipt requires the phase-valid terminal guardian state and only the fields that state mandates;
no receipt fabricates an identity, acknowledgement, or release for an unreached guardian.

Before `GOVERNOR_STATE` can enter any transition, the supervisor captures the complete bounded
pre-mutation governor snapshot and sorted inventory inside the hidden
`governor-state-published` transition candidate. It includes every controlled CPU/policy path,
original governor bytes, source device/inode/type/mode/size/SHA-256, host-policy binding, and
required restoration value. Those snapshot files become immutable `0400` files beneath
`root:root 0500` ancestry inside the committed transition. Every file, nested directory, transition
candidate, and transition parent passes the common fsync, no-clobber rename, and destination-parent
fsync barrier as one publication. The `GOVERNOR_STATE` marker in that same transition binds the
exact snapshot identity/digest. No separately published snapshot exists, and no governor mutation
is allowed before the transition barrier succeeds. Recovery and finalization restore only from
this durable snapshot, never from `/run`, a process-local variable, or another volatile copy. A
volatile mirror may exist only as non-authoritative diagnostics. Immediately before each first
mutation, the supervisor reopens the fixed policy-derived target no-follow and requires its current
identity and bytes still equal the snapshot; external drift aborts before writing. On the same boot,
recovery requires the recorded target identity. After a changed boot ID, it reauthenticates the
host policy/fingerprint and exact CPU/topology/relative-path set, records the newly opened sysfs
device/inode identities as a reboot remap, and restores the sealed values; it never requires a
volatile sysfs inode to survive reboot. Any semantic target-set, policy, path, or restoration
mismatch remains fail-closed for manual host recovery and never authorizes measurement.

Required intent transitions are published before the side effect they authorize. In particular,
`governor-state-published`
atomically includes `GOVERNOR_STATE` and the full snapshot and is durable before the first possible
governor mutation; from that transition onward recovery conservatively assumes mutation may have
begun.
Before the child exists, the supervisor creates the exact token-derived run root as a hidden
candidate beneath its already-open nonwritable root-owned parent. It applies controlled-UID
ownership, mode `0700`, and the authenticated project quota; fsyncs and validates the empty
directory and metadata; renames it no-clobber to `run-roots/cs2a.TOKEN`; fsyncs the parent; and
reopens and revalidates the final directory FD. It cannot fork until that complete durability
barrier succeeds. It records the canonical path, device/inode, owner/mode/quota, and FD identity in
the stopped-child candidate. `child-launched` atomically includes that authenticated
`RUN_ROOT` marker plus the stopped-child/process-group identity and is committed before the child
handshake releases it to execute; from that transition onward recovery assumes work may have begun.
The supervisor passes only the already-open fixed-number run-root FD and its transition-bound
token-derived identity to the child. The protocol fixes that descriptor as FD 10; the launcher
retains only read-only non-lock-owning authorization FD 9, run-root FD 10, and its bounded
stdout/stderr pipes across the UID drop and `exec`. The runner receives fixed argv
`--run-root-fd 10` and uses only
`/proc/self/fd/10` to authenticate the already attested canonical token-derived run-root path; it
accepts no run-root path argument or environment override. It anchored-creates and validates the
fixed `runtime/home`, `runtime/gradle-home`, `runtime/tmp`, projection, and scratch children through
that FD,
then passes their canonical transition-derived absolute paths to descendants that may close FD 10.
An absent or reused FD in a Gradle daemon or test worker therefore cannot alias path authority. The
child never selects, prints, or publishes a run-root path, and stdout/stderr cannot create or alter
the marker. A terminal transition atomically adds the
captured status and source to the complete then-current marker snapshot before any finalizer or
receipt publisher can act.

A crash before the run-root rename leaves only a hidden candidate that recovery may anchored-remove
after validating its fixed name, parent, and lack of references. Rename-before-parent-fsync uses the
common exact-object adoption rule. A complete durable final-name run root with no committed
`child-launched` marker is the sole prepared-run-root orphan shape: while holding token and
admission locks, recovery proves every controller/launcher/controlled process absent, validates that
the parent could be mutated only by root, requires the exact inode/owner/mode/quota and an empty
anchored directory, removes that exact empty inode no-follow, fsyncs the parent, and continues the
governor-state-only terminal path. Nonempty, changed, duplicate, malformed, or ambiguous content is
manual-safety fail-closed and never a receipt source. A committed `RUN_ROOT` is never removed by
this branch.

A process killed while constructing a hidden transition leaves no new authoritative phase, marker,
or status. Recovery first contains every process implied by the last committed transition. It may
then remove an uncommitted hidden candidate, but it never treats candidate bytes as evidence. The
ordering rules guarantee that an uncommitted governor marker precedes all mutation. An uncommitted
child/run-root or status candidate is reported truthfully from the last committed transition, using
the existing recovery-detected outcome after containment when no original status is authentic. The
receipt binds the entire transition chain. Kill-point tests immediately before and after every
phase, marker-set, process-identity, and terminal-status commit must end in exact restoration and
one valid receipt or a proved pre-mutation terminal outcome; no crash shape may dead-end recovery.

After state publication the installer redirects all further output to that transcript and executes
only the canonical root-owned orchestrator inside the published bundle, preserving the token-lock
FD across that one `exec`. The orchestrator validates the inherited lock. The canonical supervisor,
its root parent-watchdog, finalizer, recovery process, and receipt publisher inherit and hold the
same open-file-description lock for their complete control lifetime, closing it only from the
controlled runner, workload, and unrelated descendants. This preserves serialization even if a
parent is killed between fork and durable child-identity publication. An exec/catchable failure
after state publication records its actual nonzero terminal status with source `installer`; an
uncatchable failure leaves recovery to record `recovery-detected`.

Transcript segments are token-derived, root-owned, no-clobber files: the initial
installer/orchestrator/supervisor segment, a finalizer segment when finalization runs, and one new
monotonically numbered segment per recovery invocation. The initial segment remains `0600` across
the installer-to-orchestrator `exec`. Before the orchestrator releases a freshly spawned supervisor,
it transfers that same already-open writable FD, the supervisor validates it, and the orchestrator
closes every local writable duplicate; no process path-reopens the segment. The supervisor is then
the sole writer until it exits. If no supervisor launches, the orchestrator remains the sole writer.

No controlled-UID process receives a root-transcript descriptor. The supervisor gives each child
only bounded stdout/stderr pipe write ends and drains them through root-owned readers into separate
source-tagged diagnostic files, never into the root-control transcript. Child payload is always
untrusted diagnostic data and no validator parses it as phase, status, receipt, READY, or other
control evidence. The authenticated host policy fixes each child stream at no more than 8 MiB,
aggregate child diagnostics at no more than 24 MiB, and the root-control transcript at no more than
12 MiB, reserving exactly 28 MiB of the 64 MiB receipt-metadata allowance for other protocol files.
The receipt-projection run root is limited to 1,792 MiB, 5,000 inventory files, 1,000 unique
nonempty relative-directory prefixes, 6,000 combined file/directory objects, 512 MiB per file,
512-byte relative paths, and 16 components. A path contributes every distinct nonempty directory
prefix to that count; an omitted explicit directory record never makes an inode free. Before any
projection `mkdir` or file creation, the bounded canonical path set is complete enough to prove the
prospective unique-prefix and total-object counts. The separately rooted nonprojection
runtime-workspace tree is at
most 4 GiB and 20,000 entries, and the separately rooted excluded scratch/JFR tree is at most 16
GiB and 100,000 inodes. Neither is a receipt source. The encompassing run-root quota is at most 23
GiB and 132,000 inodes, reserving over 1 GiB and 5,000 inodes for checked filesystem/directory
overhead beyond the subtree maxima. Root-provisioned
project/filesystem quotas, `RLIMIT_FSIZE`, fixed path construction, and bounded writers enforce
each subtree and the combined limit before and during child execution; unavailable enforcement is
a launch failure. The remaining 2,192 receipt objects are a closed metadata reservation, counting
files and directories: at most 512 transition/control objects, 256 governor-snapshot objects, 64
root-transcript/child-stream files, 512 fixed protocol/nested directories, and 848 fixed
protocol/runtime/finalization files. The categories are disjoint. Execution plus control epochs are
capped at 256, and every complete snapshot consumes exactly one transition directory plus one
record from the 512-object class. Nonterminal operation may consume at most 248 snapshots; the
final eight complete snapshot slots, therefore 16 base objects, are reserved for the closed
worst-case containment/recovery/JDK-post-group/guardian-release/finalizer sequence. The reservation
also holds 24 fixed protocol file/directory objects for activation containers, guardian ACK, status,
and other branch members, plus eight of the transcript-file slots; those are not hidden inside the
16 base objects. A crash before a publication barrier leaves only an adoptable/removable candidate,
and a crash after it reuses that committed generation rather than consuming a duplicate slot. No
allowed branch requires more than the eight reserved complete snapshots; a prospective action that
would do so fails before its side effect and enters the administrator-only manual-safety path.
Transcript segments are capped at 32, child diagnostic files at 32, and the authenticated governor
topology must fit its 256-object bound before mutation. Before every root directory, metadata,
recovery segment, child diagnostic, or projection creation, checked arithmetic proves its
sub-budget plus the prospective unique-directory, file, and combined-object counts stay within
8,192; no
nonterminal action may consume a reserved terminal object. Thus a run that remains within its
authenticated execution policy always fits the later 2 GiB/8,192-object
receipt caps. Exceeding any pipe, transcript, projection, scratch, byte, inode, path, file-size, or
time limit terminates/contains the child, restores host state, and yields only the phase-appropriate
nonzero outcome without leaving an over-cap receipt source. It never fills an unbounded root file or
host filesystem.

Receipt metadata bytes have a matching closed reservation within the 64 MiB total: at most 4 MiB
for the complete JDK inventory, 4 MiB for the complete Gradle-seed inventory/recipe/provenance, 8
MiB aggregate for every transition/control snapshot plus the full governor snapshot/inventory, 2
MiB aggregate for sealed configuration, policy, transport, native provenance, and other fixed
protocol inputs, and 10 MiB for the receipt inventory, wrapper, signatures, final status, and all
still-uncreated terminal files. Except for the separately capped JDK, Gradle-seed, and receipt
inventories, one metadata object is at most 1 MiB. Genesis accounting records every current byte
and the worst-case reserved bytes, files, directories, and objects for all remaining recovery and
terminal artifacts.
Before governor mutation, child release, seed copy, and every later metadata/diagnostic creation,
checked arithmetic must prove simultaneous maximum JDK, seed, transition, diagnostic, projection,
and terminal values stay within their class, 64 MiB metadata, 8,192-object, 2 GiB receipt, and
23 GiB run-root limits. Failure before a side effect takes
the prelaunch path using the reserved terminal budget; no nonterminal transition may consume that
headroom. The same prospective accounting is rechecked against exact bytes during finalization.

At a segment boundary, the controlling process proves every writer exited or closed its copy,
changes the segment to `0400`, opens it read-only through the authenticated root-state chain, and
records its size/hash/metadata in durable state. The orchestrator creates a separate finalizer
segment only after sealing the initial segment. A final publisher closes and seals its own segment
before the atomic receipt rename and performs no later transcript write. Recovery never reopens an
earlier segment for writing. It may finalize an abandoned `0600` segment only after the recorded
owner identity and every inherited writer are proved absent. Any process retaining a writable
transcript FD forbids publication.

Staging cannot create, replace, select, or modify an approval record. The pre-provisioned native
entry/payload/copier/signer, build provenance, signing key, retirement policy, and approval
record—rather than staged data—form the root trust anchor.

Installer mode `run` is the sole privileged component that reads mutable staging. It requires the
pristine exact staged asset/transport set, authenticates and copies those approved bytes, publishes
the canonical versioned bundle and token-bound root transport attestation, and starts the fresh root
orchestrator from that bundle. Installer mode `recover` does not enumerate, authenticate, copy, or
execute mutable stage bundle bytes, so later unprivileged result files cannot affect it. It first
validates the inherited claim-lock FD already acquired by native-entry mode `recover`; a held lock
would already have returned `in-progress` before payload execution or any state read. It
first authenticates the permanent claim and immutable header. If genesis is absent, it enters only
the closed claim-terminal/quarantine publisher and reads no staging, transport attestation,
approval, bundle, or orchestrator. If durable state exists, it authenticates only that token-bound
state and its sealed transport attestation, selected approval, and already installed canonical
bundle/orchestrator, then enters canonical orchestrator recovery. Neither branch can install or
invoke fresh-run code.

## Root remote-session orchestration

The canonical `cs2a-remote-session.sh` accepts only exact mode `run` or `recover` plus the strict
32-character lowercase hexadecimal session token forwarded by the installer. It obtains the
approved manifest digest and all other session identities from the installer-sealed approval and
runtime/policy copies; fresh `run` additionally requires the current fixed files to match those
copies byte-for-byte and by metadata. It
reconstructs all paths beneath fixed protocol roots; it accepts no arbitrary absolute path,
inherited configuration, free-form command, or staging-supplied expected digest. It starts from a
clean environment and absolute tool paths.

`run` requires the installer's unique authenticated durable session state at exact phase
`bundle-installed` and requires that no receipt exists. It performs the fresh-run sequence below.
`recover` requires existing authenticated durable session state, never installs a different bundle
or trusts staged bytes, resolves only the root-owned bundle identity recorded in that state, and
never invokes the supervisor's fresh-run mode. It follows the closed recovery dispatch defined
below for that token. A mode mismatch, replay, or attempt to recover with different
approval/runtime/bundle bytes fails closed.

In the remainder of this design, “phase,” “marker set,” “process identity,” and
“terminal status record” always mean the corresponding fields of the latest valid committed
transition snapshot.
They never mean a separately mutable file. A named `GOVERNOR_STATE` or `RUN_ROOT` marker is the
small root-owned marker embedded in that snapshot and its authenticated reference, not a
path-discovered side channel.

The fresh `run` sequence never reopens mutable staging. It:

1. authenticates and uses only the already-published canonical bundle's exact path set, approved
   staged-bundle manifest, root ownership, and read-only modes, plus the
   installer-sealed token-bound transport inventory and attestation in durable session state;
2. independently verifies, but never installs or replaces, the sealed native entry/payload/copier
   identities and build provenance, selected approval, runtime/JDK inventory, retirement-policy
   generation, controlled-UID policy, or controlled-host policy;
3. creates the exact root-owned handoff, including native launch-mode, orchestrator, signer, and
   runtime-config identities;
4. authenticates `/opt/revoman-benchmark/cs2a-session-state/session-state.SESSION_TOKEN` as the
   installer's root-owned `0700` durable session directory beneath a canonical nonsymlink
   `root:root 0711` parent,
   verifies its implementation, approval, bundle, runtime, and transport identities as regular
   nonsymlink `root:root 0400` files, validates the inherited token lock, original process identity,
   and pre-created active root transcript, then monotonically advances the phase to
   `supervisor-starting`;
5. invokes the canonical installed supervisor's fresh-run mode exactly once, redirecting its output
   directly to the root-owned transcript;
6. captures the supervisor's real status without `errexit`, `tee`, or pipeline masking;
7. atomically records that non-predicted status with exact source `supervisor` in the durable
   root-owned session state;
8. validates the exact marker set allowed by the monotonic terminal phase: neither marker for a
   prelaunch outcome, `GOVERNOR_STATE` only before any child release, or both `GOVERNOR_STATE` and
   the supervisor-created `RUN_ROOT` for every phase at or after `child-launched`;
9. invokes a separate installed-supervisor finalization process whenever governor state exists, or
   publishes the restricted prelaunch-failure receipt without invoking finalization; and
10. emits the receipt path and exact terminal status.

Every token-owned privileged child other than the independently handed-off benchmark-lock guardian
uses a token-lock-held parent/child handshake before it may advance:
durable state records boot ID, PID, start time, executable device/inode/hash, canonical script hash,
role, and parent identity for the supervisor, controller watchdog, recovery process, finalizer, and
publisher. The child validates that record and inherited token lock before continuing. The
guardian instead uses its closed true-lock/cgroup/liveness handoff and deliberately holds no token
lock, as defined above. Native-entry
internal mode `controlled-child/v1` is the one narrower fork-gap case. It inherits the token lock,
remains stopped before UID drop or workload exec, and waits on exactly one root-owned release pipe
whose only writer is the recorded supervisor/watchdog lineage. EOF, parent death, or any framing or
identity mismatch makes it exit without executing and thereby close the lock. Only after the
complete `child-launched` transition and parent fsync bind its PID/start time, group, run-root
FD/identity, sealed launch-record digest, authorized native-to-Bash/runner exec edge, protected
cgroup membership rules, and owner lineage may the parent send the exact transition-bound release
record. The
native mode then revalidates those identities and performs the closed FD/credential drop and runner
exec contract above. The Bash endpoint remains blocked on its post-exec gate until the supervisor
observes the CLOEXEC status channel and durably publishes `process-identity-handoff`. Before that
commit, recovery recognizes and contains only the exact native pre-exec endpoint or authorized Bash
endpoint with the same boot/PID/start time/PGID/protected cgroup and launch record; it never trusts
`cmdline`. After commit, it requires the exact Bash endpoint and closed descendant/member rules.
Unexpected exec, cgroup member, leader reuse, or identity mismatch is contained and quarantined,
never accepted as a normal run. A parent killed before transition publication cannot leave an
unrecorded lock-holding child; a kill before the post-exec gate leaves no benchmark work; a kill
after the handoff leaves a fully containable identity even if the leader exits before descendants.
Run-root authority does not use the existing stdout marker: the supervisor creates and
opens the token-derived directory beneath `/opt/revoman-benchmark/run-roots` before forking, binds
its FD and canonical identity together with the stopped process/group in the `child-launched`
transition, passes only that inherited FD and identity, and releases the child only after the
transition durability barrier. A mismatch or kill before that barrier leaves no child execution and
no authoritative `RUN_ROOT`; a kill after it is recoverable from the complete marker/process
snapshot.

The orchestrator contains no executable call to `cs2a-controlled-run.sh` or to the supervisor's
internal child/launcher modes. The supervisor remains the sole authority for lock acquisition,
stale-state recovery, governors, process-group containment, the root-held lock OFD, launcher
authentication, workload execution, and restoration. The approved native internal mode is the sole
UID-transition implementation; controlled code receives only non-lock-owning authorization FD 9.

## Supervisor finalization and recovery

Add one public root-only mode:

```text
--finalize-session SESSION_TOKEN
```

The mode accepts no filesystem path. It validates the token and reconstructs the root-owned session
directory, transcript-segment set, and marker paths beneath
`/opt/revoman-benchmark/cs2a-session-state/`. It validates their strict canonical path grammars,
root ownership and modes, the inherited held token-lock FD and lock-file identity, and the current
root-session process identity. Calling finalization or either receipt publisher without owning that
exact lock fails before state mutation. The finalization mode accepts only `GOVERNOR_STATE` for a
no-run finalization or both `GOVERNOR_STATE` and `RUN_ROOT` for a run finalization. The restricted
prelaunch publisher separately requires zero markers. Every present marker must be one regular
nonsymlink root-owned file with the required content and mode, and all present markers must satisfy
their authenticated cross-links. Finalization accepts no caller-supplied status. Terminal status
and source are authoritative only as fields of the latest complete committed transition snapshot
under the common rename, parent-fsync, and adoption protocol. Before finalization, the numeric value
is restricted to `0..255`, and its source is exactly `supervisor` or `recovery-detected`. Source
`supervisor` requires byte-equality to an immutable raw wait-status evidence file already
cross-bound into that same snapshot; the raw file is never independently current or terminal.
Source `recovery-detected` is allowed only after the recovery-only mode proved the original
processes absent and no authentic supervisor status was recoverable, and its value is fixed at
`70`. A finalizer may append one same-phase `terminalization` control event with status `70`, source
`finalizer`, and one closed failure code only under the post-run failure contract below. That event
preserves the complete prior status/source and raw supervisor evidence as `originalTerminalStatus`
and `originalTerminalSource`; it never fabricates or rewrites them. Every transcript segment must
be an exact regular nonsymlink root-owned file derived from the session token and sequence,
finalized to `0400`, and free of writable FDs before receipt publication.
Missing, duplicate, stale, cross-session, wrong-owner/mode, noncanonical, or inconsistent state
fails before finalization.
Idempotence means adoption or validation of the exact existing transition generation; no separate
status path is published, retried, truncated, or rewritten.

For a run-root marker, before any root process parses or validates controlled-writable handoff
content, finalization invokes the approved canonical copier. After permanently dropping to the
controlled UID, the copier anchored-opens and boundedly seals the fixed final-handoff files and
their inventory into hidden root-owned state. Every source must be regular with `nlink == 1` and
stable device/inode/type/link-count/size/hash metadata before and after copying; a symlink,
hardlink, FIFO, special file, oversize/malformed input, rename race, or nonzero copier status fails
before root parses a byte. Root parses only that sealed copy, cross-binds it to the token, run-root
marker, transition head, supervisor state, and approval, and then performs released-lock
authentication and read-only final-handoff validation against the sealed inventory. Existing
final-handoff logic is reused only behind this boundary and may not path-open the mutable original.

If the run root and prior terminal snapshot are authentic, finalization first proves
controlled-group absence, exact governor restoration, and benchmark-lock release, then performs
exactly one complete bounded handoff sealing/validation attempt: at most five minutes per source and
30 minutes total under the fixed byte/count caps. Success proceeds to `run-finalized`; the first
complete attempt that returns one closed failure commits the one `finalizer` terminalization event
described above and publishes receipt kind
`run-finalization-failure`. Its failure code is exactly one of `handoff-missing`,
`handoff-seal-rejected`, `handoff-schema-invalid`, `handoff-cross-link-invalid`, or
`projection-policy-invalid`; free-form child/copier text is forbidden. The receipt binds the
supervisor-created run-root marker and prior status, but copies no handoff byte and no benchmark
projection. It contains only bounded root-generated diagnostics, durable session/transition state,
transcripts, containment/restoration/lock-release proof, and the closed code. It is valid evidence
of failed execution/finalization and always yields no performance decision. Missing or compromised
protocol code, signer failure, ambiguous root state, or failure to prove restoration cannot use this
kind and remains fail-closed for recovery or manual safety.
An interruption before the failure transition becomes durable may repeat only that same bounded
attempt after recovery re-proves all prerequisites. Once the transition is durable, no handoff or
copier attempt may run again; recovery may only finish/adopt the corresponding receipt and causal
records.

With authenticated governor state but no run-root marker, finalization instead requires
containment, exact governor restoration, and released-lock proof before publishing the no-run
terminal state. It handles every captured supervisor status for
which authenticated governor state exists, including `0`, `3`, `70`, signals, and the fixed
recovery-detected status. Status `0` without a run root remains invalid. A nonzero terminal outcome
can therefore produce a valid immutable failure receipt and be archived without rerunning
measurement. This finalization process is not a second fresh-run invocation and cannot enter the
workload path.

Add a separate root-only recovery mode:

```text
--recover-session SESSION_TOKEN
```

It accepts no path or status and first validates the successfully acquired inherited token-lock FD
and the recovery-owner record already atomically published by canonical orchestrator recovery. The
record binds boot ID, PID, start time, shell identity/hash, canonical script hash, and recovery
sequence. It then reconstructs and authenticates the exact durable session and monotonic phase from
the token. Governor state is required and authenticated only when the phase and marker protocol
says it must exist. If any recorded token-owned installer, orchestrator, supervisor, controller
watchdog, or finalizer identity is still live, it reports `in-progress` and performs no status or
receipt publication. The independently recorded lock guardian alone is not a live controller; it is
the expected exclusion owner during orphan recovery. Successful token-lock acquisition plus absence
of every recorded root controller distinguishes an authenticated live controlled group as an orphan
rather than a legitimate active session. Recovery authenticates the guardian and its contended true
lock, requires the guardian's controller-death path to invoke bounded cgroup containment, and waits
for exact member absence while that lock remains held. It restores state under that exclusion and
then sends the transition-bound guardian release record. If the guardian itself died unexpectedly,
recovery marks the clean-host prerequisite failed, contains the cgroup first, and only then acquires
the released benchmark lock; any unrelated holder remains fail-closed. The controlled group's
distinct read-only FD 9 never owns or releases that lock.
PID checks always bind boot ID and start time and never treat a bare reusable PID as proof. It
accepts only these two recovery shapes:

- phase `lock-held` with no `GOVERNOR_STATE` or `RUN_ROOT` marker: authenticate either the live
  guardian-held benchmark lock or, after an unexpected guardian death, acquire the released lock;
  authenticate the pre-mutation phase and
  exact session identities, verify that protocol ordering prevented governor mutation or child
  launch, monotonically record `lock-state-failed`, publish fixed terminal status `70` with source
  `recovery-detected` only when no authentic supervisor status exists, release the fallback lock or
  direct the guardian to release, and return;
  or
- a phase that requires authenticated `GOVERNOR_STATE`: contain any authenticated orphan controlled
  group as specified above, authenticate the guardian-held lock or safely acquire its released
  fallback, invoke the
  existing stale-governor recovery implementation only for the marker-bound full durable
  pre-mutation snapshot, verify process-group absence and exact
  governor restoration against every sealed target/value, write terminal root state, recover the
  authentic supervisor status when available or otherwise publish fixed status `70` with source
  `recovery-detected`, release the fallback lock or direct the guardian to release, and return. A
  volatile `/run` copy is never sufficient.

The mode rejects an already terminal prelaunch phase, an impossible marker/phase combination, or
any ambiguous state. It exits without entering `supervisor_main`, installing a bundle, or launching
a child.

Signer/key unavailability or local compromise revocation never blocks the safety half of recovery.
Token-lock acquisition, process containment, governor restoration, and benchmark-lock release
complete first using the authenticated claim header or durable session state applicable to that
phase. Ordinary append-only signer/key retirement is
fresh-run-only: root recovery uses the claim-header- or session-sealed signer/key when it remains
installed, while the local collector accepts it only when the key remains active in the offline
policy. If the root signer/key is unavailable, recovery commits the safe terminal state and
the closed unsigned quarantine diagnostic defined below, but publishes no authoritative
receipt/READY or claim-terminal pair. If only the offline local policy revokes a still-usable remote
key, root recovery may finish its ordinary signed publication, but the local operator labels it
revoked, quarantines its bytes, and rejects it as authoritative. In either case the session remains
uncollectable for performance and cannot be rerun; safety recovery is not weakened to preserve
evidence signing.

The unsigned quarantine diagnostic has exactly one non-authoritative form. Root may publish a
bounded canonical JSON file at
`/opt/revoman-benchmark/cs2a-recovery-quarantine/cs2a.TOKEN.json` beneath a canonical
`root:root 0555` parent as a regular nonsymlink `root:root 0444` file. Schema
`revoman-cs2a-recovery-quarantine/v1` contains only the token, permanent claim identity, durable
session-state identity when present, last adopted transition head, attempted safety operations and
their actual statuses, terminal status/source when known, and one reason from
`signer-unavailable`, `signature-publication-failed`, or `ambiguous-state`.
Publication uses the common no-clobber durability/adoption protocol, but the file is unsigned and
therefore arrives locally only as forgeable incident bytes. It never evidence-closes a claim or
session, authorizes another token by itself, satisfies archive integrity, enters the signed terminal
matrix, or yields a performance decision. Only the separately authenticated `manual-safety`
clearance may later close global admission without upgrading this diagnostic. Absence or publication
failure never rolls back completed safety actions.

With no durable genesis, installer mode `recover` remains the sole header-bound publisher. When the
claim-terminal envelope is absent, it publishes that exact envelope from the immutable claim header
and authenticated recovery status; when the envelope is already complete, it authenticates and
adopts it without rewriting it. It then publishes only an absent claim-ready commit record. On a
changed boot that new commit embeds the exact signed post-envelope remap; original and same-boot
commits omit it. It next appends or adopts the exact terminal-observed global generation. If the
pair
already existed across a boot change, that generation binds the unchanged pair plus the current
namespace remap; otherwise it binds the remap already present in the applicable signed member. It
never invokes a bundle, orchestrator, transcript, supervisor, or workload. If terminal-observed
already exists it reports `awaiting-clearance`.

Only after installer mode `recover` validates both the token lock and durable genesis may canonical
orchestrator mode `recover` inspect the token-derived receipt path. An existing receipt is
authenticated and returned read-only, without creating a transcript or mutating receipt/session
state. Recovery may publish only its absent derived READY after the receipt durability barrier. On a
changed boot that new READY carries the exact signed post-receipt namespace remap; on the same boot
those fields are absent. Recovery then appends or adopts the exact missing terminal-observed global
generation that binds the complete signed artifact and, when it runs on a changed boot, the same
current-match remap whether READY was old or newly created. It never rewrites the receipt, READY,
transition, or transcript. If terminal-observed already exists it reports `awaiting-clearance`. If
no receipt exists, canonical recovery proves any
prior owner and inherited
transcript writers absent and seals their active transcript in a `transcript-seal` transition when
necessary. It then
atomically registers or rotates the authenticated recovery owner and creates its next no-clobber
active transcript as one `recovery-owner-activation` transition before it inspects status, phase,
or markers. That transition retains the current execution phase and advances only `controlEpoch`.
Supervisor recovery and every publisher must validate this same owner. It then applies exactly one
branch:

1. an authenticated terminal prelaunch phase with no `GOVERNOR_STATE` or `RUN_ROOT` marker requires
   authenticated absence of every recorded original installer, orchestrator, supervisor, root
   watchdog, finalizer, and controlled group plus a released benchmark lock, preserves an existing
   authentic `installer`, `orchestrator`, or `supervisor` terminal status or atomically records
   fixed status `70` with source `recovery-detected`, then retries only the restricted prelaunch
   publisher;
2. phase `lock-held` with no marker calls `--recover-session`, requires the resulting proved
   `lock-state-failed` phase, then invokes only the restricted prelaunch publisher; or
3. an authenticated `GOVERNOR_STATE` marker calls `--recover-session` and then token-derived
   finalization appropriate to the resulting marker set. With `RUN_ROOT`, successful handoff
   sealing/validation yields `run-finalized`; the first complete bounded attempt returning a closed
   handoff/copier/validation failure yields exactly `run-finalization-failure` after the required
   finalizer terminalization event.

Any other no-receipt state fails closed. The orchestrator itself never performs containment or
governor restoration, and neither recovery branch can install, fresh-run, or invoke workload code.

The exact monotonic phases are installer-published `bundle-installed`, then
`supervisor-starting`, `supervisor-preflight`, `runtime-verified`, `lock-contended`, `lock-held`,
terminal `lock-state-failed`, `governor-state-published`, `governors-mutated`, `child-launched`, and
`supervisor-finalized`. The phase protocol distinguishes the governor state from the later runner
root. The supervisor publishes only `GOVERNOR_STATE` after authenticated supervisor-state creation.
It atomically publishes the supervisor-created `RUN_ROOT` with the stopped child/group identity in
`child-launched`, before releasing that child. No child-selected marker or diagnostic stream is an
authority.

The lock-acquired-to-governor-state interval defers catchable signals. A normal failure in that
interval releases the lock and records `lock-state-failed` without governor mutation or child
launch. If an uncatchable termination leaves phase `lock-held`, token-only recovery must first prove
every recorded original token-owned process and controlled group absent, the benchmark lock
released, no `GOVERNOR_STATE` or `RUN_ROOT` marker, and no governor mutation, then monotonically
records `lock-state-failed`. Only that proved state may use the restricted prelaunch receipt.

If installer/orchestrator/supervisor execution terminates nonzero while the authenticated phase is
`bundle-installed`, `supervisor-starting`, `supervisor-preflight`, `runtime-verified`,
`lock-contended`, or proved
`lock-state-failed`, the absence of `GOVERNOR_STATE` and `RUN_ROOT` markers is required and the root
orchestrator publishes an immutable `prelaunch-failure` receipt. That receipt contains the session
identities, approval/runtime/bundle bytes and hashes, root transcript set, phase, terminal status,
and status source, declares `remote-evidence-present=true` and
`benchmark-projection-present=false`, and contains no run root or sealed benchmark projection.
Status `0`, an unproved later phase, ambiguous phase, or either marker forbids
this receipt. Its source is `installer` for an actual post-state installer failure,
`orchestrator` for an actual catchable pre-supervisor error/signal recorded by the root
orchestrator, `supervisor` when the fresh supervisor returned before governor-state publication, or
`recovery-detected` only when recovery proved absence but no authentic earlier status was
recoverable.

If authenticated governor state exists but no run-root marker exists, the supervisor finalizes
containment, exact governor restoration, and lock release, then publishes receipt kind
`supervisor-finalized-no-run`. It requires a nonzero terminal status with an authenticated source,
contains the root transcript set and final supervisor state, declares that no benchmark projection
exists, and yields no performance decision. A run-root marker requires either `run-finalized` after
successful handoff validation or `run-finalization-failure` after the one bounded closed failure
path above. Any incomplete combination creates no receipt until token-only recovery establishes one
of these exact terminal states.

## Immutable completion receipt

Exactly one terminal receipt is atomically published beneath:

```text
/opt/revoman-benchmark/cs2a-receipts/cs2a.TOKEN/
```

The parent, published receipt directory, and nested receipt directories are `root:root 0555`.
Receipt files are regular nonsymlink `root:root 0444` files. Every receipt has exact kind
`run-finalized`, `run-finalization-failure`, `supervisor-finalized-no-run`, or
`prelaunch-failure` and binds:

- schema and unique session/attempt identity;
- implementation SHA, losslessly parsed controlled UID and primary GID, exact account name, passwd
  record, normalized supplementary-group set, and the mandatory pre-`dzdo` live-kernel launch proof;
- phase-governed `controlledChildEvidenceState` and `postDropIdentityState`, each exactly
  `not-started|failed|verified`. `not-started` forbids every controlled-child field; `failed` binds
  one bounded diagnostic and only durable records actually reached; `verified` requires the exact
  launch/release records, FD-12 post-drop proof/EOF, and process-identity handoff;
- exact byte copies of `controlled-runtime.json`, the selected root approval record, controlled-UID
  policy and account-resolution record, controlled privilege-policy attestation and its fresh-run
  selection generation/digest,
  bound successful privilege-probe/complete pair, namespace selection/attestation and any committed
  recovery remap, and controlled-host policy,
  including their original root metadata;
- the exact receipt-signing public key bytes, key ID, SHA-256 and OpenSSH fingerprint, canonical
  signer identity/provenance, and private-key path metadata/identity without any private-key byte;
- the administrator-approved JDK-tree inventory and canonical tree-root identity, plus separate
  phase-governed `jdkPrelaunchWalkState` and `jdkPostGroupWalkState` values. Each is exactly
  `not-started|failed|verified`, with complete bounded result bytes mandatory for `verified` and one
  closed diagnostic for `failed`. Pre-release receipts require post-group `not-started`; any receipt
  after Java or controlled-child release requires a terminal post-group result, and performance
  decisions require both states `verified`;
- the exact sealed Gradle-seed inventory, tree identity/digest/bounds, recipe and provenance, plus
  phase-governed pre-release source-walk, one-time initial destination-copy, and post-group
  source-rewalk states. Each state is exactly `not-started`, `failed` with a bounded closed
  diagnostic, or `verified`; early receipts record `not-started` truthfully, and a performance
  decision requires every applicable state to be `verified`. Post-gate writable Gradle-home bytes
  are excluded and never claimed to match the initial copy;
- canonical `nativeEnv/v1` and `runnerObservedEnv/v1` bytes/digests plus separate phase-governed
  native construction, Bash-observed, runner-validation, exec-handoff, and post-exec-gate states,
  together with the already-defined `postDropIdentityState`. Each is exactly
  `not-started|failed|verified`; receipt kind and transition phase determine which state is
  required, and no prelaunch receipt fabricates a result for an unreached action;
- the executed canonical remote-session script and approved staged-bundle manifest;
- the installer-sealed token-specific transport inventory and root attestation, including their
  exact bytes, root metadata, digest, stage identity, and bundle/session cross-links;
- runtime-config, approval, remote-session, and bundle-manifest digest cross-links to supervisor
  state, run metadata, and receipt identity;
- the sealed fresh-run retirement-policy generation/inventory and exact protocol, implementation,
  approval, bundle, and copier absence proofs;
- the terminal status, its exact `installer`, `orchestrator`, `supervisor`, `finalizer`, or
  `recovery-detected` source, any available authentic prior/supervisor status, and the authenticated
  terminal phase;
- every exact finalized root transcript segment and its original/final metadata for every receipt
  kind, plus separately inventoried source-tagged child stdout/stderr diagnostic files that are
  explicitly untrusted and never parsed as control evidence;
- the complete immutable transition chain, including every record digest, marker snapshot,
  process/group identity, and terminal-status snapshot;
- the phase-governed guardian state `not-started|registered|active|released|failed`. `not-started`
  forbids guardian identity fields; later states bind exactly the applicable independent root
  guardian identity/handoff, protected cgroup and channel identities, true-lock inode/OFD proof,
  registration/activation acknowledgements, controller-liveness outcome, cgroup-empty proof, normal
  or recovery release record, and the exact durable final release-ACK bytes/path/digest/barrier plus
  FD-7 notification result when applicable. Controlled code's FD 9 is recorded only as a separate
  non-owning read-only authorization description;
- the one already durable global-session active-generation record and claim binding, including that
  record's sequence, digest, previous-head digest, and admission-lock identity. Historical ledger
  records and the full prefix are explicitly excluded from the receipt inventory; the root admission
  path validates them in place. The later terminal-observed and administrator-clearance generations
  are host-admission state outside the receipt self-inventory: the terminal-observed generation
  binds the completed signed artifact, never the other way around, so receipt, READY, and ledger
  publication have no dependency cycle;
- whenever `GOVERNOR_STATE` exists, the complete durable pre-mutation governor snapshot/inventory,
  its publication barrier and transition binding, every restoration result, and required absence
  of any volatile file as restoration authority;
- root-verified protocol-selected privilege-command path, canonical target, link-chain metadata and
  target hash, plus operator, validator, native launch-mode and root-entry records, native installer
  entry/source/build-recipe/provenance, installer payload, canonical copier and signer
  executable/source/build-recipe/provenance, phase-permitted `controlled-child/v1` records under the
  two states above, `lock-guardian/v1` descriptor/seccomp/lifetime records, orchestrator, runner,
  and supervisor hashes. A receipt never requires or carries controlled-child launch/release or
  post-drop bytes when that path is `not-started`;
- the native entry's exact inherited/normalized signal, descriptor, cwd, environment, and
  machine-neutral process-state and namespace/rootfs-attestation record plus the authenticated
  host-policy rlimit, scheduler,
  affinity, cgroup, process-profile, project-quota, pipe/output-limit, and prelaunch/pre-seal
  clean-account/host snapshot results. It records exact `cleanHostPrerequisite` value
  `not-reached`, `assumed-and-endpoint-checked`, or `detected-failure`; only the middle value can
  support a performance decision, and it truthfully denotes the operational trust assumption plus
  endpoint checks rather than continuous kernel isolation;
- for `run-finalized`, canonical run-root and governor-state paths, the exact final
  `meta/supervisor-core/` and `meta/supervisor/` trees, and the SHA-256 of every regular byte copied
  from `manifests`, `results`, `logs`, and `meta`;
- for `run-finalization-failure`, the canonical run-root and governor-state marker identities,
  preserved original terminal status/source, fixed finalizer status/source, exact closed failure
  code, required absence of handoff/projection bytes, proved containment, exact governor
  restoration, benchmark-lock release, and bounded root-generated finalizer diagnostics;
- for `supervisor-finalized-no-run`, the canonical governor-state path, required absence of a
  run-root marker and benchmark projection, proved containment, exact governor restoration, lock
  release, and the final root-owned supervisor state;
- for `prelaunch-failure`, required absence of `GOVERNOR_STATE` and `RUN_ROOT` markers and any
  benchmark projection plus `benchmark-projection-present=false`; and
- a sorted receipt inventory covering every other receipt file.

Every authenticated READY-backed remote receipt kind records `remote-evidence-present=true`.
It records `benchmark-projection-present=true` exactly for `run-finalized` and `false` for the other
three kinds. Only the local-only failure path that proves durable remote session state absent may
record `remote-evidence-present=false`; that path has no receipt or READY.

For `run-finalized`, after containment and final-handoff validation, finalization seals only the
exact `manifests`, `results`, `logs`, and `meta` projection into a hidden staging directory beneath
the root-owned receipt parent. The root-owned sealed final-handoff copy supplies the sorted relative
path, source device/inode/type/link count/size, and SHA-256 expected for every projected file. Every
source must be a regular file with `nlink == 1`, remain beneath the canonical run root on its
expected device, and have no missing, extra, unsafe, duplicate, symlinked, hardlinked, special, or
cross-device path.

No privileged process path-opens controlled-writable source content. A bounded copier permanently
drops to the authenticated controlled UID/primary-GID identity before it opens each strict relative
source path and
streams only that file's exact expected byte count to a root-owned hidden destination; therefore a
rename/symlink race cannot disclose any root-only byte. The root side accepts the stream only when
the copier exits zero, the copied size and SHA-256 equal the authenticated final-handoff entry, and
post-copy source device/inode/type/link count/size/hash equal the pre-copy identity. Destination
creation is anchored beneath the already-open root-owned receipt-stage directory and never follows
a link. Root-owned supervisor metadata is copied only from already-open descriptors beneath the
separately authenticated nonwritable root-state chain. Any identity change, transient substitution,
short/long stream, timeout, copier failure, or digest mismatch aborts the entire hidden receipt and
publishes nothing. The approved canonical copier is the only implementation of this contract;
unavailable kernel support or copier failure aborts publication rather than weakening the rule.

After every source is sealed, finalization changes the staged projection to exact root ownership
and read-only modes and computes the final byte inventory from those staged bytes. The sealed
projection and receipt metadata are children of one hidden receipt directory that is atomically
renamed without replacement to `cs2a.TOKEN`.

For `run-finalization-failure`, the publisher discards every partial sealed-handoff or projection
candidate, proves no such byte is reachable from its new hidden receipt directory, and copies only
the root-owned session/transition/transcript/finalizer state named above. It never enumerates the
mutable run root for receipt inventory. The run-root marker remains evidence that execution may have
occurred, not a source of trusted result bytes. The bounded diagnostic records only the fixed code
and numeric protocol component status; no child/copier text, path selected by the child, or handoff
payload enters the receipt.

For `supervisor-finalized-no-run`, finalization copies the exact final governor state and root
transcript set into that hidden receipt directory only after containment, restoration, and
lock-release
proof. For `prelaunch-failure`, the restricted root publisher copies the exact durable session
identities, terminal phase/status, and transcript set only while holding the token lock. Its current
identity must equal either the recorded original orchestrator or the recorded recovery owner. The
original orchestrator path requires every other token-owned privileged process and controlled group
absent; the recovery path requires every recorded original token-owned process and controlled group
absent. Both require the allowed phase/marker rules. These copies receive the same root-owned
read-only treatment before the one-directory publication.

For `run-finalized`, JFR bytes remain excluded from the sealed projection; their already
authenticated path, size, and SHA-256 inventory files are included. The receipt inventory has one
explicitly named
self-exclusion: it hashes every receipt file and sealed-projection byte except the receipt inventory
file itself. No other receipt path is excluded.

The transport and receipt limits are closed protocol constants: READY, claim envelope, and each
commit record are at most 64 KiB; the receipt inventory is at most 4 MiB and 8,192 relative-object
records. Every regular file and every unique nonempty directory prefix has exactly one typed record;
directory records precede descendants, and the receipt root itself is bound separately by READY.
A relative path is at most 512 bytes and 16 components, while aggregate relative-path bytes are at
most 1 MiB; one receipt/projection file is at most 512 MiB; nonprojection receipt metadata totals at
most 64 MiB; and all inventory-covered file bytes total at most 2 GiB. Counts and byte totals use
checked unsigned 64-bit arithmetic and any overflow is invalid. The exact canonical inventory
encoder prospectively proves the complete encoded length remains at most 4 MiB before every object
creation; file count, directory count, total-object count, and individual path limits alone are
never treated as sufficient. Publisher and finalizer enforce the same constants before hidden-stage
creation and before signing. The inventory records exact byte length, file count, directory count,
total-object count, aggregate path bytes, metadata total, projection total, and grand byte total.
It requires `totalObjectCount=fileCount+directoryCount=inventoryRecordCount`. READY signs all eight
values plus the inventory digest. These caps are deliberately outside and independent of forgeable
remote metadata.

No `run-finalized` receipt appears until the final handoff is both published and independently
validated. No `run-finalization-failure` receipt appears unless `RUN_ROOT` is authentic, the exact
closed sealing/validation failure is committed with source `finalizer`, every partial controlled
copy is absent, and containment, governor restoration, and lock release are proved. No
`supervisor-finalized-no-run` receipt appears until containment, governor restoration, and lock
release are proved from authenticated governor state. No `prelaunch-failure` receipt appears unless
the root-owned phase proves the fresh-run process terminated before governor-state publication,
governor mutation, or child launch and any transient lock is released. Receipt bytes alone are not
the privilege-free collection boundary.

Only after the receipt-directory rename and receipt-parent fsync succeed does the publisher create
the separate root-signed readiness envelope:

```text
/opt/revoman-benchmark/cs2a-receipt-ready/cs2a.TOKEN.ready
```

Its canonical parent is `root:root 0555`; the bounded regular nonsymlink record is `root:root 0444`.
It uses one closed bounded binary framing for schema `revoman-cs2a-ready/v1`: exact raw payload
length and bytes followed by one fixed-length detached Ed25519 signature. The signer and local
verifier sign/verify the domain-separated raw bytes directly and never reserialize JSON or accept
another framing, algorithm, key type, noncanonical signature, trailing byte, or unknown field. The
signed payload binds the token, receipt kind, receipt-directory
device/inode, complete receipt-inventory byte length, file count, directory count, total-object
count, metadata/projection/grand byte totals, aggregate path bytes, and digest, transition-head
digest, terminal status/source,
implementation, runtime-config, approval, bundle-manifest and signer identities, signing-key ID and
fingerprint, exact `cleanHostPrerequisite` value, and `receiptParentDurable=true`. It also contains
nullable signed post-receipt namespace-remap bytes and old/new identities: they are required only
when changed-boot recovery publishes a previously missing READY, and forbidden for original or
same-boot publication. The publisher
validates the session-sealed signer,
public key, and private-key metadata, gives the approved signer only fixed input/output/key FDs, and
requires status zero and the exact bounded signature framing before it publishes READY no-clobber.
The privilege-free collector independently verifies that signature before trusting it. The
publisher never supplies a caller-selected key path.

The READY parent is fsynced after publication. The signed record can become visible only after the
receipt is durable, so even a reader racing its own parent fsync cannot observe READY before that
prerequisite. The privilege-free collector requires and locally verifies the exact signature with
the separately pinned public key before it trusts any READY field, remote metadata, receipt byte,
or claimed outcome. It then cross-validates the signed payload and receipt; mere receipt-directory
visibility or a self-consistent unsigned transport is rejected. A complete atomically visible
signed READY is a valid causal collection commit even while its own parent fsync is pending; the
publisher reports success only after that fsync.

The local verifier is the exact implementation-pinned
`ReceiptSignatureVerifier.kt` source named in the security-critical list. Its only entry is the
fixed main class
`com.salesforce.revoman.benchmark.driver.integrity.ReceiptSignatureVerifier`; no general driver
command parser or caller-selected class is involved. `--local-verifier-cas` names a canonical
user-owned `0700` CAS root outside every repository, worktree, temporary, run, evidence, and cleanup
root. `--local-verifier-java-home` names the exact retained JDK 21 tree beneath equivalently
protected ancestry. Neither has a checked-in default. Preparation authenticates the clean
implementation checkout and the reviewed `cs2a-local-verifier-v1.build.json`. That recipe binds the
verifier source, Gradle wrapper/build logic, dependency locks and strict verification metadata,
pinned JDK/toolchain inventory, every resolved artifact checksum, fixed build environment/flags,
and the independently gated expected complete install-distribution inventory digest. Preparation
uses a fresh empty Gradle User Home with no user/system init script or daemon. It exposes only a
read-only dependency materialization whose every byte is admitted by the recipe and Gradle strict
verification metadata, clears every Gradle/Java option environment variable, uses the exact
inventoried wrapper and JDK, disables network, build cache, and configuration cache, and runs
`:benchmark-driver:installDist` twice in independent clean directories. Both complete inventories
must be bit-identical and equal the recipe's independently reviewed expected digest before the
distribution is published no-clobber beneath the CAS. The CAS installation has a content-derived
name, `0555` directories, and regular `0444` data/`0555` executable files.

Preparation records the canonical CAS/JDK paths, complete sorted regular-file path/size/SHA-256
inventories, verifier source and implementation SHAs, Gradle wrapper/build/dependency-provenance
and local-verifier recipe identities, selected JDK 21 Ed25519 provider name/version, and fixed
direct-Java argv in the authenticated local session record. Invocation uses only the absolute
inventoried `bin/java`, an
explicit inventoried classpath, normalized nonwritable cwd, `umask 077`, a closed descriptor set,
and an exact allowlisted locale/time-zone environment. It clears and rejects `JAVA_TOOL_OPTIONS`,
`_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_HOME`, `CLASSPATH`, application option variables,
agents, alternate security properties, provider overrides, and every unapproved JVM argument. The
verifier asserts that process/provider envelope and accepts only fixed paths for the downloaded
bounded envelope, detached signature, and pinned public key. It parses no trusted field
before raw domain-separated Ed25519 verification succeeds.

Collection, persistence, `--validate-attempt`, and reporting no-follow revalidate the CAS/JDK
ancestry, exact distribution and JDK inventories, verifier source/implementation SHA, build and
dependency provenance, provider identity, session-record binding, fixed argv, and clean JVM
envelope before every verification. They invoke only that authenticated distribution and never
resolve a new classpath or substitute a verifier. The local archive retains the complete verifier
distribution/dependency closure plus all these identities and its verification result. A missing
CAS copy may be reconstructed only by repeating the same clean deterministic build and proving its
entire output inventory equals the archived inventory before use; no different output is accepted.
The JDK CAS remains a required exact input. Cleanup inventories protect both CAS roots while any
associated session or evidence commit remains retained and must report rather than remove them.
A verifier mismatch, mutation after preparation, failure, or nonzero status is terminal and no
canonical archive publication or evidence upgrade occurs.

If recovery finds a complete receipt but no READY, it revalidates and idempotently fsyncs the
unchanged receipt and parent, authenticates the session-sealed original signing key/signer, then
signs and publishes the one missing READY record. It never rewrites receipt or session-state bytes.
A malformed, unverifiable, mismatched, replayed, or colliding receipt/READY pair fails closed.

A completed receipt never records a predicted publisher or root-session exit. Receipt publication
is the final receipt/session-state mutation; READY is the separate derived collection commit. A
`run-finalized`, `run-finalization-failure`, or `supervisor-finalized-no-run` receipt implies
finalization status `0`; a `prelaunch-failure` receipt implies its restricted root publisher
returned
`0`. After the receipt publisher, READY publication, and terminal-observed global generation all
reach their required barriers, the root orchestrator's terminal outcome is defined to equal the
recorded terminal status.
If receipt publication fails or READY never reaches its atomic rename, no collectable terminal
receipt exists and the orchestrator exits `70`. If READY-parent fsync or terminal-observed
publication fails after READY rename, the orchestrator still exits `70`, but the visible signed
record remains a valid causal collection commit and recovery may fsync/adopt it and append only the
exact missing terminal-observed generation. Token-only recovery may adopt a complete durable receipt
and publish a missing READY record but never rewrites either.

## Privilege-free local collection and persistence

`--collect-remote-session` reconstructs the receipt and READY paths only from the explicit strict
token and authenticated local preparation record. It derives no outcome from terminal text or any
staged instruction/result file. A bounded reader downloads at most 64 KiB for READY under a fixed
60-second object timeout; any extra byte, short framing, or timeout fails. It treats the result as
untrusted transport,
requires the key to remain active in the newest supplied offline trust-policy generation, and
verifies its Ed25519 signature locally against the explicit pinned key before trusting a field.
It then requires the strict token/path/schema and cross-links. A changed-boot-recovery READY must
contain and authenticate the exact bounded post-receipt remap defined above; original and same-boot
READY records must omit it. The archive retains any signed remap bytes/identities with READY. Only
after those checks does it
download the receipt and require its inventory digest to equal
the signed READY value. It first reads only the fixed receipt-inventory path, under the signed
length
and 4 MiB/60-second independent caps, and verifies exact length and digest before parsing any entry.
Parsing then reconstructs the canonical unique-directory-prefix set and enforces the signed file,
directory, total-object, path/depth/file/metadata/projection/grand-total limits with checked
arithmetic. It rejects duplicate, missing-parent, out-of-order, type-conflicting, or unsafe paths
and sparse-size tricks before creating any directory. The local hidden stage has an independently
enforced 8,256-object quota: at most 8,192 signed receipt objects plus 64 fixed transport/stage
objects. The collector proves that quota and available space before its first `mkdir`. Each
remaining fixed relative file is streamed at exactly its
authenticated size under the 512 MiB per-file cap, a five-minute per-file timeout, the signed 2 GiB
aggregate cap, and a 30-minute total transfer deadline. Any extra/short byte, overflow, quota
failure, or timeout removes the hidden stage and publishes nothing. It treats every controlled-UID
SSH shell, forced command, SFTP/`scp`/`rsync`
response, and remote `stat` value as forgeable transport rather than root provenance. A forged
transport can withhold or corrupt evidence but cannot produce a collectable signed outcome. The
collector downloads the sealed projection only when receipt kind `run-finalized` requires it. For
`prelaunch-failure` and `run-finalization-failure`, it requires that projection to be absent. The
latter also requires the exact signed closed failure code, finalizer source/status, preserved prior
status/source, authentic run-root marker, and absence of every handoff byte. For
`supervisor-finalized-no-run`, it copies only the sealed final supervisor-state projection and
requires benchmark directories to be absent. It then requires exact receipt/inventory equality
before any canonical local publication. It never copies evidence from the mutable run root.

If the only terminal object is a signed claim-terminal pair, collection requires the key to remain
active and downloads both the bounded envelope and its commit record as untrusted transport. It
verifies the commit-record signature and pinned key first, then the bound envelope signature, and
requires exact token, claim identity, path, size, SHA-256, key, durability, and absence of
receipt/READY and durable-session claims in their signed payloads. It authenticates and archives the
envelope-level canonical namespace-remap bytes and exact old/new identities when present. It also
authenticates and archives the claim-ready-level post-envelope remap exactly when changed-boot
recovery published that commit; original and same-boot commits must omit it. It rejects an envelope
without
its commit record, archives no benchmark projection, and emits no performance decision. An unsigned
“no state” response is not this terminal object.

Every local archive retains the exact signed READY bytes, detached signature, verified public-key
bytes/fingerprint, newest receipt-key trust-policy generation/digest, matching local high-water
identity, local-verifier identity, and local verification result as required transport/commit
artifacts outside the receipt's self-inventory. It also retains a bounded untrusted observation of
remote canonical path, type, UID, GID, mode, size, and SHA-256. Archive-integrity validation
requires the signed bytes for every remote receipt kind and rechecks the signature, current
nonrevoked-key policy/high-water state, token, receipt-inventory digest, transition-head digest,
receipt identity, and all implementation/runtime/approval cross-links. It never upgrades remote
`stat` output into root provenance. Missing signed READY evidence can never be reconstructed from a
copied receipt.

A claim-terminal archive analogously retains the exact signed envelope and commit-record bytes,
both detached signatures, public key/fingerprint, trust-policy generation/digest and high-water
identity, local-verifier identity, both verification results, every applicable signed envelope- or
commit-level namespace remap, and bounded untrusted transport observations. Its inventory forbids
receipt, READY, run-root, manifest, result, comparison, or performance-decision bytes.

Collection must not invoke `dzdo`, install a bundle, publish a final handoff, launch a supervisor,
invoke the runner, build a driver, or perform semantic selection. After receipt authentication, it
reuses the existing archive-safety, local-authority, checksum, hidden-stage, no-clobber publication,
and marker implementation. It returns the signed artifact's effective terminal status and writes
the same local evidence marker used by `--persist-only`. For `run-finalization-failure` this is
always `70`; its preserved prior status/source are archived context and can never become the
collector, persistence, or marker status.

Local selection parses only the archived runtime and approval copies, requires their exact closed
schemas and digests, and never dereferences their remote absolute paths.

The caller immediately invokes `--persist-only STATUS` for PASS, FAIL, INCONCLUSIVE, or any valid
archived nonzero outcome. Persistence retains its exact frozen-tree, scope, hook-disabled commit,
CAS ref update, and direct-parent validation, and rechecks the current policy/high-water pair before
the commit/ref mutation.

## Authoritative validation and performance classification

The post-persistence validator is split into two explicit layers so unfavorable evidence is not
rejected merely because it is unfavorable:

1. **Archive-integrity validation** authenticates the external signed READY or claim-terminal pair,
   active local key policy/high-water state, pinned local verifier, receipt kind when present,
   sealed projection, staged-bundle manifest, transport inventory, runtime/approval bytes,
   executed provenance,
   status/phase/stage cross-links, and the exact set of files allowed for that terminal state. A
   valid archive may represent setup failure, prelaunch failure, an interrupted run, A/A cutoff,
   candidate comparison failure, or a complete passing comparison.
2. **Performance classification** runs only after archive integrity passes. It recomputes and
   verifies every result/comparison that the authenticated stage requires, without requiring its
   decision to be `PASS`, and emits the normative performance outcome where the captured stage is
   sufficient.

The accepted terminal matrix is closed:

For every row, `cleanHostPrerequisite=detected-failure` remains archive-integrity-valid only when
its detection, containment, restoration, and terminal cross-links are authentic, but performance
classification emits no decision regardless of captured stage or comparison bytes.
Any performance decision additionally requires JDK prelaunch/post-group, Gradle-seed
source/copy/post-group, native environment, `controlledChildEvidenceState`,
`postDropIdentityState`, Bash-observed environment, runner validation, exec handoff, and
post-exec/seed gate states all to be `verified`. A truthful `not-started` or `failed` state remains
archiveable only in a row whose phase permits it and always yields no decision.

- signed claim-terminal pair: nonzero root-entry status/source, exact permanent claim identity,
  envelope-to-commit-record digest/key/durability binding, required absence of durable session
  state, exact envelope-level remap when envelope publication changed boot, exact commit-level remap
  when claim-ready publication occurred on a later boot, required absence of each remap on its same
  boot path, no receipt/READY/bundle/benchmark projection, and no performance decision;
- `prelaunch-failure` receipt: nonzero terminal status with an authenticated source, no
  `GOVERNOR_STATE` or `RUN_ROOT` marker, no benchmark projection, and no performance decision;
- `supervisor-finalized-no-run` receipt: nonzero terminal status with an authenticated source,
  authenticated final governor state with proved containment/restoration/lock release, no runner
  root or benchmark projection, and no performance decision;
- `run-finalization-failure` receipt: fixed terminal status `70` and source `finalizer`, one closed
  finalization failure code, an authenticated supervisor-created `RUN_ROOT` marker and preserved
  prior terminal status/source, proved containment/restoration/lock release, no handoff or benchmark
  projection byte, and no performance decision;
- `run-finalized` at `setup`, `aa-captured`, or `candidate-captured`: a nonzero terminal status with
  an authenticated source, exact stage-required partial bytes and status cross-links, classified as
  execution failure with no before/after decision;
- `run-finalized` at `aa-compared`: both A/A captures/comparisons are authentic and candidate files
  are absent. If either A/A comparison is non-PASS, the normative outcome is `INCONCLUSIVE`;
  supervisor-sourced status `3` is the expected cutoff, while another independently cross-linked
  nonzero terminal status records a later execution interruption without invalidating the archive.
  If both A/A comparisons are `PASS`, only an independently cross-linked nonzero interruption
  status is valid and there is no before/after decision. Status `0` with no candidate stage is
  invalid;
- `run-finalized` at `candidate-compared`: both A/A comparisons must be `PASS`; all cold, warm, and
  retained candidate captures/comparisons must be authentic; the normative outcome is `FAIL` if any
  candidate comparison is `FAIL`, otherwise `INCONCLUSIVE` if any is `INCONCLUSIVE`, otherwise
  `PASS`. A supervisor-sourced terminal status and the comparison exits must normally match that
  outcome. A different nonzero terminal status remains archive-valid only when all available
  runner, child, and supervisor records plus durable state and receipt cross-link its source as an
  interruption after atomic `candidate-compared` publication; the recomputed performance outcome
  is retained and reported alongside the interruption; and
- any other kind, stage, status, file set, or combination is invalid rather than coerced into a
  decision.

The existing `--validate-attempt` entry point is extended to enforce this matrix and record archive
integrity separately from the performance outcome. It never deletes or refuses to authenticate a
well-formed FAIL or INCONCLUSIVE archive. Reporting distinguishes “valid evidence with no
performance decision” from `PASS`, `FAIL`, and `INCONCLUSIVE`.

## Failure and recovery semantics

- A local preparation failure conclusively before any human launch is immediately preserved as
  local-only setup evidence with `remote-evidence-present=false`. Once the human attempts native
  launch, no controlled-UID `stat`, shell output, disconnect, returned status, or claimed absence
  can authorize that local-only path or a new token. If no root claim exists, the same token remains
  pending and may be launched again. If a claim exists without durable session state, root
  `recover` publishes the signed claim-terminal pair only when the claim-header-bound remote
  signer/key remains installed and usable. If durable session state exists, normal signed receipt
  recovery is mandatory when the session-sealed signer/key remains installed and usable. Ordinary
  remote retirement is fresh-run-only and does not block signing with either sealed usable key. An
  unavailable signer/key yields only the unsigned quarantine outcome
  defined above after every possible safety action. An offline-revoked key may still produce a
  remote signature, but the local coordinator always quarantines and rejects it. The local
  collector verifies and persists an exact signed terminal artifact before normal clearance; only
  the root-only `manual-safety` remediation path can later admit a new token without one. Quarantine
  never unlocks another run by itself.
- A terminal status other than zero publishes the exact receipt kind supported by its authenticated
  source, phase, and marker set once the required absence, containment, restoration, lock-release,
  and kind-specific final-handoff proof or closed finalization-failure proof passes.
- A non-PASS cold or warm A/A comparison stops candidate capture exactly as today.
- Before supervisor launch, the root orchestrator creates durable root-owned session state and log
  files. Its `INT`, `TERM`, and `HUP` handlers atomically record the actual signal with source
  `orchestrator` if no supervisor exists; otherwise they forward the signal to the supervisor
  exactly once and do not exit until the supervisor terminates and completes containment and
  restoration. No staged byte executes in the credential path, and the static entry resets
  inherited signal state, so a controlled-UID launcher context cannot suppress the privileged
  child's terminal signal.
- Once durable `bundle-installed` state exists, local-only fallback is forbidden and fresh
  measurement remains forbidden until either the normal signed-terminal/terminal-observed/clearance
  path completes or one exact durable `manual-safety` clearance closes the old claim/session for
  admission only. `--recover-remote-session` source-authenticates the prepared token and prints the
  exact root-owned native `launch recover TOKEN` invocation. The human runs it in a new interactive
  TTY; its token-only canonical root `recover` mode obtains the exact authenticated
  `GOVERNOR_STATE` and
  `RUN_ROOT` marker combination permitted by durable root session state, follows only the closed
  recovery dispatch, validates the final handoff when the receipt kind requires it, and publishes a
  receipt for that same session only when the session-bound remote signer/key remains installed and
  usable. Missing or ambiguous markers preserve only the closed unsigned quarantine diagnostic,
  remain fail-closed, and never authorize a new attempt. An offline-revoked but remotely signed
  result is likewise quarantined after local policy verification rather than accepted.
- Existing explicit `--archive-only` remains available only for legacy/incomplete sessions created
  before this token protocol and admitted by the frozen exact-path/digest allowlist. It is strictly
  privilege-free; any separately administered legacy host recovery is outside repository code and
  cannot feed this mode as authoritative evidence. It never launches a new measurement, never
  becomes authoritative, and always remains unsigned quarantine evidence.
- Missing, malformed, replayed, or mismatched receipts never fall back to a fresh run.
- On the normal evidence path, a new attempt is allowed only after the previous archive is persisted
  and independently reviewed, and after a documented implementation, harness, or objectively
  measured host-state correction. When no authoritative terminal archive can exist, only the exact
  durable `manual-safety` path may admit a new token after its authenticated containment,
  restoration, and remediation or reprovision proof; it grants no archive or performance authority
  to the old attempt.

## Security invariants

- Root never executes code from a controlled-user-writable staging path.
- No staged protocol byte executes before or during credential entry; the explicitly trusted clean
  console boundary is not misrepresented as protection from a compromised interactive account.
- Signed performance evidence requires the explicitly trusted clean controlled account and host
  through terminal-observed publication; this design does not claim malicious same-UID isolation.
- Staging cannot choose an executable digest or approval; only the pre-provisioned root installer
  and root-owned approval record authorize privileged code.
- No password is read, stored, logged, piped, exported, or accepted by repository code.
- Each native-entry invocation requires fresh human authorization; a credential ticket from an
  earlier trusted launch cannot authorize a later controlled-UID invocation.
- Runtime paths and identities come only from the authenticated root-owned configuration.
- Every security-relevant command status is explicitly propagated even inside Bash conditional
  contexts; correct-looking stdout with nonzero status fails.
- Unique tokens reject traversal, leading options, whitespace, control characters, metacharacters,
  globs, command substitutions, and excess length.
- `BASH_ENV`, `ENV`, `PATH`, `IFS`, `CDPATH`, `SHELLOPTS`, `GIT_*`, and inherited file descriptors
  cannot influence privileged execution.
- Caller-ignored or blocked signals, an alternate signal stack, a writable cwd, or inherited
  process-policy values cannot suppress privileged handlers or alter the authenticated benchmark
  execution profile.
- Bundle, receipt, and READY publication are atomic, no-clobber, exact-path, and identity-checked.
- Remote transport and metadata are untrusted until a locally active, out-of-band-pinned signing key
  verifies the root completion envelope and every signed digest cross-checks.
- Local collection is privilege-free and cannot invoke any workload path.
- Token-only recovery can contain and finalize only its existing session and cannot invoke any
  fresh-run or workload path.
- A durable global active token blocks every other measurement until root-only normal or
  manual-safety clearance; quarantine and locally revoked signatures never clear it.
- Direct runner invocation remains invalid because the lossless UID/GID post-drop proof, sealed
  launch/release/process-handoff chain, authenticated FD-10 run-root identity, distinct read-only
  FD-9 identity plus root-lock contention proof, exact implementation/runner hashes, and post-exec
  gate are all mandatory. A genuinely lock-owning FD in controlled code is explicitly invalid.

## TDD and mutation contract

Implementation follows RED/GREEN/refactor in protocol slices:

1. Add CLI parser and forbidden-literal tests before removing the checked-in host values.
2. Add exact runtime-config schema/metadata/path/hash failures before consuming it.
3. Add preparation and unique-bundle tests before copying a remote session.
4. Add native-entry/copier/signer builds, installer trust-anchor, approval, launch-mode, and
   canonical-publication tests before privileged orchestration.
5. Add supervisor finalization and receipt tests before moving post-status responsibility.
6. Add privilege-free collection tests before replacing the default local remote flow.
7. Add a disposable Linux/root end-to-end test before any real host invocation.

At minimum, execute and restore mutants for:

- missing, extra, reordered, duplicated, malformed, or injected CLI parameters;
- every former hostname, username, home, JDK, repository, UID, policy, and fingerprint literal;
- omission, addition, substitution, wrong order, or aliasing of each of the five named staged asset
  paths; no other staged executable or credential-bearing launcher is permitted;
- missing/malformed/mismatched/duplicate SSH host key, key rotation, host impersonation,
  `StrictHostKeyChecking=no|accept-new`, a different trust store or option set in any remote call,
  connection-master reuse across pins, missing/substituted/wrong-metadata local identity, hostile
  user/system `ssh_config` `Host`/`Match` rules, proxy/jump/local/known-host command sentinels,
  omitted remote user, local-username fallback, invalid/implicit/differing port, hostname/user/port
  rewrite, forwarding, default-identity authentication despite the explicit identity, askpass/agent
  injection, alternate config/trust store, dirty SSH environment, and a differing
  option/executable identity in any transport phase; infinite, oversize, over-record, slow, or
  quota-exhausting host-key/preflight/recovery/legacy output and ANSI/OSC/fake-password-prompt bytes
  must be bounded, hidden, cleaned up, and never rendered raw; every repository rotation, removal,
  rebind, or deletion attempt for a bound host key/identity after claim, governor mutation,
  receipt-before-READY, and before collection must be unavailable/rejected, while external
  prerequisite loss must never fall back to a new token or unsigned transport rebind;
- non-root, writable, symlinked, or rename-raced `/opt`, protocol-root, privileged-state, approval,
  bundle, session, or receipt ancestor; a staging root replacing a privileged sibling;
- privilege-command discovery with no entry, incorrect rejection of a same-identity alias,
  acceptance of a differing alias, unstable first-alias selection, symlink cycle or swap,
  non-root/writable path component or target, changed target hash, or receipt/state mismatch;
- missing, mutable, wrong-owner/mode/schema/generation/digest privilege-policy attestation, an
  installed rule that differs from its administrator export, authorization or ticket reuse despite
  the fixed values, and a second noninteractive/cached-ticket native-entry sentinel after one
  authorized launch on the same TTY/session or every other supported cache scope; the sentinel must
  fail before root entry and before any new claim; `auth-probe` that executes the Bash payload or
  creates a claim/session/ledger/workload, invalidates before the policy-only second probes and
  thereby masks caching, or omits the final invalidation/absence proof; policy-file path collision
  after rule/command
  rotation, gapped/forked/regressed selection high-water, fresh run selecting a retained older
  policy, or recovery reinterpreting anything except its exact sealed generation; selection/rule
  rotation during an active probe lease, missing/unpublished controlled-account policy at
  lease-begin, account/passwd/group drift during any probe or completion boundary, publishing the
  account only after probe completion, or changing it without a new probe/runtime/approval; crash
  before/after probe-record rename/fsync and complete rename/fsync, completion without its exact
  durable probe, abort after probe publication, or a runtime configuration binding anything except
  the complete probe/commit pair;
- runtime-config symlink, owner/mode, schema, path, digest, Java hash, policy hash, and identity
  mismatch; canonical UID/GID values `0`, `-1`, type maximum, maximum plus one, huge decimal,
  overflow/wrap alias, primary GID `0`, privileged group, non-round-tripping kernel type, ambiguous
  resolver result, passwd primary-GID race, or NSS/group drift at claim, pre-fork, or release; a
  stale unprivileged login with live supplementary GID `0` or any extra kernel group must fail
  before `dzdo`; missing/unsealed/altered/replayed launch-account-proof FD 3, policy closing or
  preserving any other caller FD, or root reconstructing the proof from NSS must fail before claim.
  Probe both privilege-command behaviors: same-process policy with a forked root, direct-child
  policy with an in-place exec, wrong/dead/reparented launcher, extra ancestry, or launcher/root
  PID/start substitution must fail; the matching in-place and direct-child cases must bind both
  identities;
  altered `lib/server/libjvm.so`, `lib/modules`, provider/configuration byte, inventory,
  tree identity, or writable JDK component while `$javaHome/bin/java` remains unchanged; failure
  before, during, and after every prelaunch and post-group inventory-walk boundary, omission of the
  second walk, correct-looking output with nonzero status, or a receipt whose phase/state/result
  combination is false; same-inode JDK byte mutation after `runtime-verified` must be blocked by
  lifecycle/global admission or detected by a committed `jdkPostGroupWalkState=failed` with no
  performance decision; an indirect
  Gradle-spawned JVM resolving outside the inventoried immutable tree, in-place package update, or
  concurrent JDK/inventory/UID/host/privilege-policy/command-rule change that bypasses either gate;
- missing/altered Gradle seed, inventory, recipe, provenance, tree identity, or source/copy/post-run
  state; forbidden init/property/credential/proxy/daemon/worker/native/compiled-script/transform
  path, poisoned plugin/cache metadata, nonreproducible double materialization, ambient user Gradle
  home/cache/network, rotation or crash across claim/genesis/source-walk/release/copy/finalization,
  seed tree/inventory/path/entry/total quota overflow, or simultaneous maximum JDK/seed/protocol
  metadata/diagnostic/projection use that cannot preserve terminal headroom; normal post-gate
  Gradle-home lock/metadata mutation must remain valid historical-copy evidence, while any pre-gate
  mismatch or immutable source drift must fail;
- missing/extra/reordered/duplicate `nativeEnv/v1` entry, runtime/env digest mismatch, unexpected
  Bash-created name, injected `PWD`, `SHLVL`, `_`, function, `SHELLOPTS`, `BASHOPTS`, Java/Gradle
  agent, option, classpath, proxy, XDG, toolchain, project-property, or
  `ORG_GRADLE_PROJECT_*` variable; execute real `/bin/bash -p` from exact native envp and require
  the closed `runnerObservedEnv/v1` result. FD 10 closed or reused by a Gradle daemon or test worker
  must not change canonical path authority; a colon-bearing or empty PATH segment and command
  resolution after cwd change must fail, while Java uses only the sealed absolute executable. Each
  not-started/failed/verified boundary must remain truthful in every receipt. Only the verified
  run-root seed copy may be used;
- missing, dynamic, substituted, or wrong-source/recipe/provenance native entry; malformed argv,
  wrong native-to-payload handoff, direct payload invocation, constructor hook, or leaked high
  descriptor; readable attacker stdin, `eval`/dynamic source, attacker-owned `BASH_ENV`, `ENV`,
  exported function, `SHELLOPTS`, poisoned `PATH`,
  `LD_PRELOAD`, `LD_AUDIT`, `LD_LIBRARY_PATH`, `GCONV_PATH`, `LOCPATH`, or `GLIBC_TUNABLES`; no
  attacker sentinel may execute before the first installer invariant;
- same-UID replacement of every staged instruction/data path with a fake `dzdo` prompt; the
  repository protocol must never invoke it or present it as a credential launcher. A slash-named
  shell function, `unshare` user/PID/mount namespace with fake root-owned `/opt`/`dzdo`, or hostile
  same-UID ptracer instead demonstrates the documented clean-console/namespace prerequisite and
  must stop the run rather than be mislabeled as a protocol guarantee; the real entry must reject a
  partial UID/GID map or namespace/root-mount identity mismatch and bind the approved attestation;
- a preexisting, persistent, or snapshot-detected hostile/unexpected same-UID process before root
  entry, during workload, or before receipt seal; detected ptrace/signal injection, coherent forged
  result/final-handoff bytes, and unapproved CPU/memory/disk/network load must set
  `cleanHostPrerequisite=detected-failure` and may never yield a performance decision; missing
  prelaunch/pre-seal process/cgroup/host snapshots, detected drift between them, or reporting
  without
  that binding fails. A test process that appears and exits entirely between snapshots demonstrates
  the explicitly declared trusted-mode boundary rather than a claimed protocol detector; full
  transient detection belongs only to a separately specified isolation protocol;
- caller-ignored or blocked `HUP`, `INT`, or `TERM`, a caller alternate signal stack, writable cwd,
  unsafe seccomp/personality/dumpability state, poisoned rlimit/nice/scheduler/affinity/cgroup
  state, and a different valid CPU topology/host policy; privileged handlers and the exact
  authenticated child profile must remain effective without a host-specific native binary;
- direct or controlled-UID invocation of native `controlled-child`, wrong caller/parent/token,
  substituted entry or runner, external/internal descriptor-profile crossover, malformed
  launch/release/handoff record, wrong or leaked FD, EOF/owner death, or release before transition
  durability; kill before/after fork, memfd write/seal, transition commit, first release,
  exec-status
  EOF, process-identity-handoff, and post-exec gate; mutable/forged FD 3, unexpected exec/cgroup
  member, leader exit with descendants, or recovery trusting cmdline; injected failure at every
  securebit/bounding/ambient/setgroups/setresgid/setresuid/cap-clear/no-new-privs step, residual
  real/effective/saved root UID/GID, group, capability, securebit, or privilege-bearing descriptor;
  parser truncation or `(uid_t)-1`/`(gid_t)-1` leave-unchanged semantics must never reach a syscall,
  and post-drop checks compare kernel values to the original wide inputs rather than truncated
  intermediates. Prelaunch/no-child receipts must bind both child states as `not-started` and omit
  every child record; a reached failure must bind `failed` without a fabricated proof; altered,
  missing, extra, or reordered FD-12 proof/failure framing, real exec failure after proof, and kill
  after proof but before/during exec must never become `verified`. Exercise exact `FAIL_PRE+EOF`,
  `PROOF+EOF`, and `PROOF+FAIL_EXEC+EOF` parsing;
  only the exact released and handed-off child may reach runner work with FDs `0`, `1`, `2`, `9`,
  and `10`; FD-14 copy-ready and FD-15 verification-gate short/extra/mismatch/EOF, early Gradle,
  copier failure, or missing/duplicate `seed-copy-verification` transition must never cross the
  seed gate;
- child `flock(LOCK_UN)`, lock acquisition, write, truncate, duplicate, or close through read-only
  authorization FD 9, plus controller/watchdog death at every workload phase and concurrent
  acquisition; the independent guardian must keep the true lock through cgroup containment and
  restoration. No controlled operation may release or mutate it, and work must abort if FD 9 no
  longer proves that distinct lock contended. Any genuinely lock-owning inherited descriptor is
  rejected. Unexpected guardian death must mark evidence invalid, keep the global token active,
  contain before fallback acquisition, and give no external acquirer signed benchmark authority;
  guardian attempt to fork/exec a helper, leaked true-lock FD, wrong descriptor profile, unlocked
  OFD, substituted native mode, malformed state/cgroup record, or syscall outside its seccomp
  allowlist; cgroup-v1, missing/wrong `cgroup.kill` or `cgroup.events`, controlled migration, or
  unbound mount/controller identity. Killing guardian during every possible state read/cgroup write
  must leave no helper or unrecorded lock owner;
  kill parent/guardian before and after guardian fork, FD inheritance, registration-transition
  rename/parent-fsync, registration digest/acknowledgement, activation-transition
  rename/parent-fsync, active acknowledgement, and parent duplicate close; kill around
  release-record write/read, terminal and cgroup validation, release-ACK candidate
  write/fsync/rename/parent-fsync,
  FD-7 ACK write/EOF, lock close, guardian exit, and `guardian-release` publication. A pre-ACK death
  must become `failed` with no decision; a complete durable ACK followed by death is adoptable only
  after exact lock/cgroup/state revalidation. A preactive guardian must exit on EOF unless the exact
  activation is durable, while only the phase-valid active guardian may hold for recovery. Pre-lock
  receipts require `not-started`; every other receipt must bind an exact terminal guardian state;
- missing, substituted, mutable, wrong-source/recipe/provenance/executable hash, nonreproducible
  build, or wrong-version canonical copier; retained real/effective/saved root ID, group,
  supplementary group, capability, securebit, privilege-bearing descriptor, or absent
  `no_new_privs` after its privilege drop;
- missing, substituted, mutable, wrong-source/recipe/provenance signer, signing key, public-key
  fingerprint, canonical framing, domain separator, or local verifier; forged shell/forced-command,
  `stat`, SFTP/`scp`/`rsync`, cross-token replay, unknown field, trailing byte,
  malformed/noncanonical signature, correct-looking output with nonzero signer/verifier status,
  signature verification moved after any trusted parse or archive publication, post-preparation
  substitution of the verifier distribution/source/JDK/provider/classpath, inventory or fixed-argv
  mismatch, dependency/provenance or expected-digest drift, non-bit-identical clean rebuild, ambient
  Gradle User Home/init script/daemon/cache/plugin/environment injection, unverified dependency
  material, CAS cleanup while evidence remains retained, an archive missing the verifier closure,
  and injected `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `CLASSPATH`, application
  options, agent, security property, provider, environment, cwd, or descriptor state;
- planned key rotation versus compromise revocation, collection/recovery under a revoked old key,
  forged post-compromise READY, stale/forked local trust-policy generation, removal or reactivation
  of a cumulatively revoked key, active/revoked overlap, and previously persisted evidence
  incorrectly reaccepted through a timestamp, commit, remote retirement, caller key, or
  compromised-key signature;
- staging symlink/hardlink/FIFO/device, wrong metadata, extra file, changed byte, mixed generation,
  oversize/short stream, timeout, or source rename during bounded controlled-UID copy;
- missing, changed, mutable, cross-token, or stage-supplied root transport attestation; recovery
  that reopens the mutable transport inventory instead of its sealed durable copy;
- missing, ambiguous, mutable, wrong-metadata, mismatched, or replayed root approval records;
- two approvals with the same implementation/bundle but distinct runtime-config digests;
- fresh restaging after installer/copier/signer/key/implementation/approval/bundle retirement;
  missing shared
  retirement lock, stale/partial/forked generation, retirement publication racing genesis, a
  receipt without the absence proof, recovery incorrectly blocked by a later retirement, and a
  retired copier that would emit a sentinel before its UID drop; the sentinel must never execute;
  a controlled-UID attempt to open or hold either retirement-policy lock mode must fail and must not
  block administrator retirement; planned publication/removal racing finalizer, recovery, signer,
  claim-terminal, receipt, or READY publication; premature lifecycle-descriptor close, descendant
  reacquisition while holding the token lock, or any lock-order inversion; the same shared
  descriptor must remain inherited through terminal-observed publication;
- compromised native entry whose sentinel executes before its self-retirement check; external
  `dzdo` authorization/removal must prevent its first instruction, automatic recovery through it
  must be impossible, and affected evidence must remain quarantine-only;
- any staged attempt to choose an executable digest or bypass the canonical installer;
- post-copy hash/stat commands that print expected output and exit `99`;
- deleted copy, chmod, chown, hash, atomic-publication, and validation guards under conditional use;
- crash or injected fsync failure after candidate rename but before destination-parent fsync for
  genesis state, bundle, transition, transcript activation/seal, and receipt publication; a
  complete final name must be adopted, an absent name must use the last durable snapshot, and a
  malformed/colliding name must require manual fail-closed recovery;
- reboot before token-lock inode/parent fsync; transcript-seal crash before/after chmod, inode
  fsync, transition rename, and transition-parent fsync, including idempotent recovery from the
  exact already-`0400` prepared-seal inode; reboot to a new selected namespace attestation before
  and after claim-only remap, abandoned-transcript seal, recovery-owner activation/remap rename and
  parent fsync, with old/new identities preserved and no containment/restoration before the durable
  remap;
- second fresh run after a held, released, pre-genesis-failed, or locally preserved token claim;
  claim unlink/replacement/recreation, recover without the permanent claim, and conversion of any
  consumed token back to run eligibility;
- run/recover interleavings and kills at admission-lock acquisition, claim create/open, inode fsync,
  claim-header write/validation, lock-parent fsync, claim-lock acquisition, initial classification,
  and admission release; no recovery may classify a visible claim while its creator can still
  proceed; every kill after O_EXCL claim creation and before complete header, inode fsync,
  parent-fsync, or active-ledger publication must remain burned and admit only the exact
  no-side-effect manual-orphan resolution or host reprovisioning;
- two-token interleavings before and after reboot, an active global generation omitted, forked,
  replaced, or cleared by process absence, released benchmark lock, unsigned quarantine, a merely
  remote signature, an offline-revoked signature, or local files without administrator clearance;
  receipt/READY must bind only the one prior active record and its previous-head digest, never an
  unbounded ledger prefix; terminal-observed must bind the already
  complete signed artifact without a dependency cycle, and recovery at terminal-observed must report
  `awaiting-clearance` without holding admission indefinitely; kills and fsync failures after
  receipt/claim-envelope, READY/claim-ready, and terminal-observed rename must adopt or append only
  the exact next artifact and return `70` until terminal-observed is durable;
- missing, staged, controlled-UID-authorized, wrong-owner/mode/schema/kind/token, replayed, or
  conflicting global-clearance records; unprivileged access to `clear-normal` or
  `clear-manual-safety`; normal clearance without current signed evidence, persistence, policy, and
  archive approval; manual-safety clearance without exact containment/restoration/remediation; and
  any manual clearance that authenticates quarantined evidence or yields a performance decision;
  one fresh token after a valid manual-safety clearance must be admission-eligible while the old
  attempt remains permanently quarantined and evidence-ineligible; malformed or stale first
  clearance requests, policy advancement/revocation before use, later corrected request IDs,
  quarantine rename/parent-fsync failure, and manual clearance with both diagnostic digests absent
  only under the exact authenticated failure/remediation branch;
- unsigned claimed absence after human launch, a new token while the original is pending, forged
  claim-terminal bytes, missing/malformed/replaced claim header, runtime/key rotation after the
  claim but before genesis, premature removal of a key bound by an unclosed claim, claim-terminal
  publication with durable session state present, envelope rename before parent fsync, collection
  before commit-record rename, commit-record parent-fsync failure, missing/mismatched/colliding
  claim-terminal commit record, missing/mismatched/replayed envelope- or commit-level changed-boot
  claim namespace remap, remap fields on either same-boot publication, recovery that rewrites either
  member of an existing pair, and a signed pair that authorizes bundle, workload, or performance
  bytes; reboot and kill after claim-envelope before claim-ready, after claim-ready before
  terminal-observed, and repeated reboots at both boundaries must complete only the missing causal
  object with the current remap; only the header-bound installer publisher may act, and the
  canonical
  orchestrator sentinel must never execute without genesis;
- canonical bundle collision, partial destination, replay, and publication race;
- direct staged-code or runner execution;
- poisoned environment and leaked extra descriptors;
- deleted or prematurely closed token-lock inheritance; concurrent recovery while the original
  process is live at `bundle-installed`, `supervisor-starting`, supervisor fork/identity
  publication, and finalization; complete root-lineage death before and after child fork,
  run-root create/chown/quota/directory-fsync/rename/parent-fsync/reopen, child transition
  fsync/rename/parent-fsync, and release-record delivery; hidden or complete prepared-run-root
  orphan
  recovery, reboot with a durable transition but missing run-root entry, EOF, parent death,
  duplicate/wrong release record, launcher exec before commit, or an unrecorded stopped child
  retaining
  the token lock; two concurrent recoveries; recycled bare PID acceptance;
- prelaunch recovery without registering the recovery owner, publisher retry with the wrong owner,
  or any durable-state/transcript mutation while validating an already published receipt;
- repeated execution phase outside the closed control-event set; recovery activation without an
  atomic owner/transcript pair; active transcript replacement, byte hashing at activation, wrong
  inode at seal, post-seal write, or abandonment without a writer-absence proof;
- local-only fallback after durable state publication or refusal of legitimate pre-state failure;
- supervisor statuses `0`, `3`, `70`, `143`, duplicate/missing markers, and `tee` masking;
- every allowed prelaunch phase, forbidden marker/phase combination, and missing phase transition;
- SIGKILL immediately before and after every transition-directory rename for phase, marker set,
  child/group identity, run root, and terminal status; partial/gapped/forked transition chains and
  any recovery that trusts an uncommitted candidate;
- governor-state-only failures before atomic child/run-root publication, including containment,
  restoration, or lock-release guard deletion and illegal benchmark projection bytes; controlled
  staging used as the run-root parent, controlled precreation/replacement of a run-root entry,
  stdout/stderr `RUN_ROOT` forgery, wrong inherited run-root FD, child selection of another path,
  run-root replacement/chown/chmod/quota/mount drift, and a positive child-content write that must
  leave the immutable identity attestation valid; same-boot mount drift, reboot on the root mount,
  and reboot with a separately mounted `/opt` must enforce the exact semantic-filesystem remap;
- reboot/power loss before and after every governor-snapshot file/directory fsync, the enclosing
  `governor-state-published` transition rename and parent fsync, and first mutation; recovery must
  use only the exact committed durable snapshot, reject volatile `/run` authority, and either
  restore every value exactly or remain manual fail-closed without measurement; same-boot external
  drift must abort before mutation, while a changed boot ID with the exact semantic topology must
  use an authenticated inode remap rather than require volatile sysfs inode reuse;
- deletion or mutation of the recovery-mode guard to reach fresh-run installation or workload code;
- deletion of the supervisor recovery call, substitution of another token's state, or moving
  containment/governor restoration into the orchestrator; kill of the complete root controller and
  controller-watchdog lineage while the independent authenticated guardian and a controlled child
  survive must make the guardian contain the child and retain the true lock until recovery restores
  exact state. Kill the guardian separately before/during/after group containment and require the
  global gate, no-decision evidence, and containment-before-fallback-lock contract;
- post-status collision, symlink victim, changed retry, or deleted no-clobber guard;
- torn or altered raw wait-status evidence, a raw status accepted without its exact committed
  transition, and any retry that publishes or rewrites a standalone terminal-status path;
- final-handoff or inventory symlink/hardlink/FIFO/device, missing/oversize/malformed bytes, source
  rename, controlled-UID copier rejection/nonzero status, projection-policy violation, or any root
  path-open/parse before the bounded root-owned sealed copy and token/run-root/transition
  cross-check; the first complete bounded closed handoff failure must discard partial bytes and
  produce exactly
  one `run-finalization-failure` receipt with effective status `70`, source `finalizer`, the
  matching
  fixed code, preserved prior status/source, no benchmark projection, and no performance decision;
  prior statuses `0`, `3`, and `70` must all propagate locally as effective finalizer status `70`;
  kill between completed failed attempt and terminalization, repeated recovery before that commit,
  and any copier retry after the durable finalizer transition;
- receipt-directory publisher, READY signer, or post-receipt integrity-publication failure, which
  must create no new collectable READY-backed receipt and may leave only an exact complete receipt
  eligible for durability adoption; this rule explicitly excludes the five closed handoff
  validation failures handled by `run-finalization-failure`;
- collection racing receipt rename, receipt-parent fsync, READY rename, and READY-parent fsync;
  it must reject before READY rename and accept an exact READY after rename even before its parent
  fsync; missing/mismatched/colliding READY, acceptance of receipt bytes without READY, and recovery
  that rewrites a receipt instead of publishing only the absent derived record; reboot after receipt
  before READY must sign the post-receipt remap without changing the transition head, while reboot
  after READY before terminal-observed must bind the remap only in the global causal-completion
  record;
- missing, altered, cross-session, or still-writable root transcript in any receipt kind;
- a controlled child inheriting a root-transcript FD, unbounded stdout/stderr, missing staging or
  run-root project quota, child output containing forged supervisor/status/READY lines, parsing a
  child diagnostic as control evidence, pipe/transcript/run-root byte or inode limit overflow,
  under-old-run-quota but over-receipt object/byte/file/path/depth limits, scratch/JFR entering the
  projection, 5,000 files distributed across maximally disjoint depth-16 paths, a missing or extra
  directory record, unique-prefix or total-object overflow in remote run-root, receipt stage, or
  local stage, maximum object count with maximum individual paths, aggregate-path or canonical
  inventory-encoding overflow, transition/recovery/diagnostic sub-budget exhaustion, consumption
  of any of the final eight complete snapshot slots, 16 base objects, 24 branch-member objects, or
  eight transcript slots by nonterminal work; crash before/after every reserved snapshot/member
  barrier at maximum epoch must still complete or enter manual safety without an over-cap receipt;
  maximum JDK inventory, transition/governor aggregate,
  maximum Gradle-seed inventory/tree/path/copy quota, simultaneous JDK-plus-seed maximum,
  maximum initial seed plus the first reserved Gradle/HOME/TMP byte, block, directory, and inode,
  config/provenance aggregate, transcript/diagnostic aggregate, or terminal-wrapper byte budget,
  failure to account worst-case remaining bytes before mutation/child release, disk-fill attempt,
  and any failure to contain/restore after a limit is hit;
- receipt partial publication, wrong metadata, replay against another implementation/run, altered
  inventory, extra/missing path, source symlink/hardlink/rename swap, device/inode/link-count/size
  drift, controlled-UID copier escape/failure/timeout, rsync-time mutation, infinite or oversize
  READY/inventory/file streams, huge file/directory/object-count/path/depth/size fields, unsigned
  length/count/totals,
  integer overflow, sparse/quota exhaustion, short/extra bytes, and per-object/aggregate timeout;
- each closed terminal-matrix row, including attempts to reject authentic FAIL/INCONCLUSIVE
  evidence or to classify partial/error evidence as a before/after decision; and
- `--archive-only` with a future/token-protocol SHA, missing allowlist row, removed quarantine flag,
  caller path/digest different from its exact row, any root helper or interactive privilege path,
  staged fake prompt, path escape, symlink/hardlink/device source, signature-matrix admission, or
  any
  authoritative/performance outcome; and
- any `dzdo`, install, supervisor, runner, driver, or semantic-validation call added to collection.

Focused tests must drive the same Bash conditional/public call contexts used in production. The
real Linux/root harness covers normal completion, A/A cutoff, nonzero finalization, lock contention,
signals, early/late stale recovery, canonical bundle reuse, receipt collection, and a second launch.

## Verification and controlled measurement

The corrective implementation is committed after the preserved `a18ab6f8` attempt. Independent
Standards, Spec, and security reviews cover the fixed range, all executing scripts, configuration
schema, receipt protocol, archive selection, tests, Detekt reconciliation, and plan lockstep. Every
Critical or Important finding requires a corrective commit and repeated review.

Before remote use, run the complete clean-SHA Gradle, ABI, unit, integration, benchmark-driver,
baseline-manifest, retained-worker, JMH, Kover, Spotless, Detekt, Qodana, Antora, Bash, ShellCheck,
operator, supervisor, receipt, and disposable Linux/root gates. The Linux block builds all three
native binaries twice with their pinned warning-free hardened recipes, requires byte identity,
runs ASan and UBSan suites plus the fixed fuzz/property corpus, and exercises those exact release
binaries in every root end-to-end scenario. Export and push only the exact reviewed implementation
SHA that passes the complete block.

Only after the implementation commit, approved staged-bundle manifest, complete gates, and
fixed-range reviews are final may an administrator provision selected frozen installer, copier, and
signer versions and signing key if not already present. The administrator then publishes the
forced-reauthorization/no-ticket-reuse privilege rule, its immutable policy/selection records, and
the current namespace attestation/selection. Still under the lifecycle lock, the administrator next
publishes and validates the controlled-account policy, exact passwd record, and normalized
group-resolution record. Only then does the administrator append the nonce-bound lease-begin record,
run the first authorized `auth-probe` plus same-TTY/session and all-scope prompting-disabled second
attempts, and perform and verify final ticket invalidation. While that lease remains active, the
administrator durably publishes the immutable probe record and then its causal lease-complete
commit; every record binds the unchanged account state, and a crash adopts or finishes only that
exact pair. A reusable credential ticket, account drift, or incomplete/aborted lease is a launch
blocker. The administrator may then publish the twice-materialized dependency-only Gradle
seed/inventory/recipe/provenance under the lifecycle lock. Only after the lossless account and
closed seed checks pass may the administrator publish the host-specific runtime configuration. It
binds the account and seed with the probe/completion pair; the root approval record is published
outside the repository only afterward.
The approval is
append-only and versioned by
implementation, bundle-manifest digest, and runtime-config digest. Any runtime-config byte change
requires a new approval record but may reuse the same byte-identical canonical bundle; no approval
is overwritten. Any source correction requires a new implementation, staged-bundle manifest,
approval record, and never-reused session token. An installer, copier, or signer correction
additionally requires a new immutable protocol version and canonical path; old versions remain
installed for recovery but may be irreversibly retired from fresh run while their signing keys
remain active. Measurement preparation proves the exact
approval already exists and records the current retirement-policy generation; repository code
never provisions, retires, or updates it.

Then run proportional local smoke, prepare the remote session with explicit parameters, and have
the human invoke the root-owned native launch mode once. Collect and persist the resulting attempt
immediately.
If A/A is non-PASS, preserve the decision and stop. If A/A passes, the same session completes cold,
warm, and retained baseline-versus-candidate capture.

After persistence and independent archive review, invoke the extended archive-integrity validator
and then the performance classifier when the terminal matrix permits it. Report valid failures with
no performance decision distinctly from the normative PASS/FAIL/INCONCLUSIVE decisions. For a
complete comparison, report exact sample counts, bootstrap confidence intervals, and the
preselected cold-allocation and warm-latency improvement thresholds. Do not claim a performance
improvement until this controlled evidence is immutable and validated.

## Rejected alternatives

1. **Invoke the SCP-staged script as the credential launcher or execute it through `dzdo`:**
   rejected because mutable same-UID bytes can fake the password prompt before elevation, while
   root execution would also leave a verify/use race. The copied session material is data only;
   the human invokes the pre-provisioned native entry from the explicit clean-console boundary.
2. **Copy only the supervisor and invoke it directly:** rejected because it bypasses authenticated
   installation, original-status persistence, final-handoff publication, collection, and immutable
   evidence registration.
3. **Prime `dzdo` in another SSH session:** rejected because authorization is TTY-bound on the
   controlled host and is not reusable by the operator's separate sessions.
4. **Store or pipe the password:** rejected because repository code must never handle privileged
   credentials.
5. **Use a direct privileged Bash shebang:** rejected because Bash `-p` blocks shell startup hooks
   only after the dynamic interpreter starts; policy-independent safety requires the static native
   clean-entry boundary.
6. **Keep host literals in checked-in scripts:** rejected because the harness must be reusable and
   evidence must identify explicit provisioned host inputs.
7. **Collect without a root receipt and READY record:** rejected because archive authentication
   requires a durable root commit; a user-writable final handoff alone cannot replace it.
8. **Increase benchmark timeouts or weaken A/A:** rejected because credential transport is unrelated
   to workload identity, sample sufficiency, or statistical acceptance.
