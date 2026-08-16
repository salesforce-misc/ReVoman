# CS2a closed-native cryptographic framing closure proposal

Status: normative proposal for reapproval. This document closes only the shared canonical JSON,
remote terminal framing, Ed25519 key/signature, and `ReceiptSignatureVerifier` contracts requested
by Plan 1 Task 1. It adds no production dependency, helper, schema authority, or caller-selectable
extension point.

The words MUST, MUST NOT, REQUIRED, and EXACT mean protocol requirements. All byte counts are octet
counts. `SHA256(X)` is the 32 raw digest octets; `hex(X)` is lowercase hexadecimal; `||` is byte
concatenation. Ed25519 terms follow RFC 8032. The SSH public-key blob follows RFC 8709/RFC 4251.

## Recommended variant and seam

| Variant | Interface | What remains behind the seam | Depth, locality, and seam placement | Decision |
|---|---|---|---|---|
| A — schema-specific wrappers/domain argv | A wrapper or caller supplies a schema/domain in addition to three paths | Only raw Ed25519 verification | Shallow: every publisher/collector must duplicate domain selection and cross-domain checks; the seam sits above cryptographic framing | Rejected |
| B — one closed self-identifying envelope verifier | Exactly `ReceiptSignatureVerifier ENVELOPE_PATH SIGNATURE_PATH PUBLIC_KEY_PATH` | Allowed-domain recognition, u64be framing, caps, key/signature canonicality, Ed25519 verification, post-verification canonical JSON/schema validation, and fixed result rendering | Deep: one small interface hides all framing knowledge; changes and tests are local to the integrity package; the seam is immediately before any authenticated payload parse | **Recommended** |
| C — typed binary payloads | Same three paths, but a separate binary codec per schema | Framing plus schema codecs | Deep but not local to the frozen design: it discards the approved ordered compact JSON direction and makes native/Kotlin interoperability larger | Rejected |

Variant B is normative below. The verifier recognizes a finite compiled-in domain set; there is no
domain, algorithm, provider, schema, result-format, or size-limit argument. The native signer signs
the complete already-framed envelope received on its fixed message FD and returns only 64 signature
octets on its fixed result FD.

## 1. Common canonical JSON and byte profile

| Constant/rule | Exact requirement |
|---|---|
| Terminal pair cap | `ENVELOPE_PATH` bytes plus `SIGNATURE_PATH` bytes MUST total at most 65,536. |
| Common terminal payload cap | `payloadLength <= 65,428`. This single cap leaves room for the longest terminal domain (36), u64be length (8), and signature (64) within 65,536. It MUST be checked from the eight length octets before payload allocation or JSON parsing. |
| Envelope grammar | `domain || u64be(payloadLength) || payload`; nothing precedes `domain`, and nothing follows `payload`. |
| Signature grammar | Exactly 64 raw octets `R || S`; no tag, length, armor, newline, or trailing byte. |
| Signed bytes | Ed25519 signs and verifies the entire exact `ENVELOPE_PATH` byte sequence, not a digest and not reserialized JSON. |
| JSON encoding | `payload` is one RFC 8259 JSON object encoded as shortest-form UTF-8, with no BOM, leading/trailing whitespace, or final newline. |
| Allocation/read strategy | Bound the file by `fstat`; recognize a compiled-in domain using at most 36 bytes; read exactly 8 length octets; reject an over-cap or size-mismatched length; then stream/hash/verify. A producer MUST prospectively prove the final pair cap before creating a hidden candidate. |
| EOF | EOF before any declared byte is invalid input. Any byte after the declared payload, after the 64th signature octet, or after the 32nd key octet is invalid input. A stream that does not reach EOF after its exact length is invalid input. Zero-length reads without EOF are bounded by the process deadline and never interpreted as EOF. |
| Integer arithmetic | Length, offset, count, and sum calculations use checked unsigned 64-bit arithmetic. Overflow is invalid input before allocation, path creation, or parse. |

| JSON construct | Canonical form | Rejection rule |
|---|---|---|
| Object | One top-level object only. Keys occur exactly once and in the schema-table order. | Reject missing, duplicate, unknown, reordered, or nested-object keys. |
| Separators | Exactly `:` and `,`; no surrounding whitespace. | Reject every whitespace octet outside a string. |
| Key | Exact ASCII spelling from the schema table, quoted with `"`. | Reject escaped key spellings, alternate case, or normalization. |
| String | Unicode scalar values encoded in shortest UTF-8. `"` and `\\` are the only escapes for quote and backslash. U+0008/0009/000A/000C/000D use `\b`, `\t`, `\n`, `\f`, `\r`; other U+0000..001F use lowercase `\u00xx`. All other scalars are literal; `/` is never escaped. | Reject invalid/overlong UTF-8, surrogate code points, alternate escape spellings, uppercase hex escapes, and Unicode noncharacters. Protocol identifier/path/digest/enumeration fields are further restricted to their ASCII grammars below. |
| Integer | JSON number in base-10: `0` or `[1-9][0-9]*`; no sign, fraction, exponent, or leading zero. Range is field-specific and never exceeds unsigned 64-bit. | Reject `-0`, `+`, decimal point, exponent, leading zero, or out-of-range value. Parsing MUST be exact, never IEEE-754. |
| Boolean/null | Exact lowercase `true`, `false`, `null`. A nullable key remains present with `null`. | Reject omitted nullable keys or string substitutes such as `"-"`. |
| Arrays/nested values | Forbidden in the three terminal payloads. Pre-existing canonical records are embedded as base64url byte strings. | Reject `[` or a non-top-level `{` outside a string. |

| Named type | Exact grammar and cap |
|---|---|
| `schema` | One exact domain/schema string from the domain table; ASCII; at most 36 bytes. |
| `token` | `[0-9a-f]{32}`. |
| `sha256` / `keyId` | `[0-9a-f]{64}`. |
| `implementationSha` | `[0-9a-f]{40}`. |
| `bootId` | `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`. |
| `u64` | Canonical JSON integer `0..18446744073709551615`; narrower field caps still apply. |
| `sequence` | Canonical JSON integer `1..9007199254740991`, the exact shell-lossless positive bound. |
| `path512` | Nonempty absolute printable-ASCII canonical path, 1..512 bytes; no `//`, `/./`, `/../`, trailing `/`, backslash, control, or NUL. A field described as fixed/derived MUST also equal that fixed derivation. |
| `bytes8k` | RFC 4648 base64url without `=` padding; shortest/canonical spelling; decoded length 1..8,192. Re-encode-and-compare is REQUIRED. |
| `bytes16k?` | Either JSON `null`, or canonical unpadded base64url whose decoded length is 1..16,384. |
| `sigSha256` | SHA-256 of the exact detached 64-byte signature file, lowercase 64-hex. |
| `sshFingerprint` | Exactly 50 ASCII bytes matching `SHA256:[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]`; derivation below is authoritative, not the regex alone. |

The compact serializer MUST compute into a bounded sink, fail before exceeding 65,428 payload
bytes, and byte-compare a parse/re-encode result in tests. Consumers MUST verify the signature
before invoking this JSON parser. A successful signature over a noncanonical or schema-invalid
payload remains invalid input; it does not create a second accepted serialization.

## 2. Domains, framing, and remote on-wire split

| Schema | Literal domain bytes (hex-free display; every character is one US-ASCII octet) | Domain length | Envelope final path | Detached signature final path |
|---|---|---:|---|---|
| claim-terminal | `revoman-cs2a-claim-terminal/v1` | 30 | `/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim` | `/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim.sig` |
| claim-terminal-ready | `revoman-cs2a-claim-terminal-ready/v1` | 36 | `/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim.ready` | `/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim.ready.sig` |
| READY | `revoman-cs2a-ready/v1` | 21 | `/opt/revoman-benchmark/cs2a-receipt-ready/cs2a.TOKEN.ready` | `/opt/revoman-benchmark/cs2a-receipt-ready/cs2a.TOKEN.ready.sig` |

There is no NUL, newline, length, or delimiter in a domain. The compiled-in list plus its exact
length defines the split before the u64be length. No allowed domain may be a byte prefix of another
allowed domain. `payload.schema` MUST equal the matched literal domain as a JSON string.

| Item | Exact bytes/publication rule |
|---|---|
| `ENVELOPE_PATH` | Exact `domain || u64be(payloadLength) || payload`; regular nonsymlink `root:root 0444`; file size equals `domainLength + 8 + payloadLength`. |
| `SIGNATURE_PATH` | Exact 64 raw signature octets; regular nonsymlink `root:root 0444`. |
| Hidden publication | Create both no-follow beneath the already-open canonical parent, fully write and file-fsync both, rename the signature no-clobber first, then rename the envelope no-clobber. The envelope rename is the pair visibility point. Fsync the parent after both final names exist. |
| Adoption | A lone final signature authorizes nothing. Recovery may publish/adopt only when hidden/final bytes, metadata, key, token, and signature all exactly match the unique expected pair; it then completes the missing rename/parent fsync. A lone envelope, mismatch, collision, or extra sibling fails closed. |
| Causal ordering | claim-terminal pair durable -> claim-terminal-ready signature -> claim-terminal-ready envelope rename -> parent fsync -> terminal-observed; receipt parent durable -> READY signature -> READY envelope rename -> READY parent fsync -> terminal-observed. |
| Later signed local/admin domain derivation | For a separately approved schema literal exactly matching `revoman-cs2a-[a-z0-9-]+/v[1-9][0-9]*`, domain bytes are the US-ASCII bytes of that full schema literal with no delimiter. The approval MUST assign a unique literal, prove prefix-freedom against the complete compiled-in domain set, declare its payload cap and ordered schema, and use the same `domain || u64be || payload` plus detached-64-byte-signature split. A schema name alone, filename, mode, or caller argument never derives a domain. |

## 3. Ed25519 key, point, signature, and derived identity contract

| Artifact/check | Exact normative contract |
|---|---|
| Private key file | Exactly the 32-octet RFC 8032 Ed25519 private seed. No PKCS#8, OpenSSH container, expanded 64-byte secret, public-key suffix, armor, passphrase, newline, or trailing byte. All 32-octet values are syntactically valid seeds. The signer derives the public key and constant-time compares it with its separately sealed expected public key before signing. |
| Public key file | Exactly the 32-octet RFC 8032 compressed Edwards point `A`: little-endian `y` in bits 0..254 and `x` parity in bit 255. No X.509 SubjectPublicKeyInfo, SSH blob, armor, or newline. |
| Public point canonicality | Clear bit 255 to decode `y`; require `y < p = 2^255-19`; recover an on-curve `x`; reject a failed square root and reject `x=0` with parity bit 1. Require `A != identity`, `[L]A = identity`, where `L = 2^252 + 27742317777372353535851937790883648493`; thus the accepted public key is in the nonidentity prime-order subgroup. Re-encoding MUST reproduce all 32 input octets. Provider acceptance alone is insufficient. |
| Signature file | Exactly 64 octets `R[0..31] || S[0..31]`. Decode `R` with the same canonical-y, on-curve, parity, and re-encoding rules. Interpret `S` little-endian and require `0 <= S < L`. Provider acceptance alone is insufficient. The RFC 8032 verification equation must then succeed over the exact envelope bytes. |
| Signature malleability | Noncanonical `R`, out-of-range `S`, truncated/extended signature, and any alternative encoding are invalid input (exit 2), never merely another representation of the same signature. A canonical but mathematically false signature exits 3. |
| Key file bounds | Private key, public key, and signature reads are exact-size streaming reads followed by an immediate EOF check; allocation caps are respectively 32, 32, and 64. |

| Derived value | Exact input and rendering |
|---|---|
| Public-key SHA-256 | `hex(SHA256(publicKey32))`; identical to `keyId` in v1. |
| Key ID | `hex(SHA256(publicKey32))`, exactly 64 lowercase hex characters. Both provisioned key filenames equal this string. |
| OpenSSH public-key blob | `u32be(11) || ASCII("ssh-ed25519") || u32be(32) || publicKey32`; total 51 octets. SSH `string` lengths are unsigned 32-bit big-endian. |
| OpenSSH fingerprint digest | `SHA256(openSshPublicKeyBlob)`, exactly 32 octets. It is deliberately different input from the key ID. |
| OpenSSH fingerprint | ASCII `SHA256:` followed by RFC 4648 standard Base64 of the 32 digest octets with all `=` padding removed; exactly 50 octets; no whitespace/newline. |

The rejected key-ID alternative was hashing the 51-byte SSH blob. It would make key ID and
fingerprint aliases of one digest and couple the native protocol identity to SSH serialization.
Hashing the raw 32 public bytes is recommended because it follows the frozen “derived from the
public bytes” wording, while the separate fingerprint remains exactly OpenSSH-interoperable.

## 4. Terminal payload schemas

All keys below are present in exactly the listed order. `bytes8k` values are exact already-canonical
record bytes, not caller prose and not reconstructed identity summaries. Their producer/consumer
must byte-compare them with the authoritative record named by the existing design.

### 4.1 `revoman-cs2a-claim-terminal/v1`

| # | Key | Type/cap | Null | Exact existing-design meaning/invariant |
|---:|---|---|---|---|
| 1 | `schema` | exact schema string | no | `revoman-cs2a-claim-terminal/v1` |
| 2 | `token` | `token` | no | Session token. |
| 3 | `claimDevice` | `u64` | no | Permanent claim device identity. |
| 4 | `claimInode` | `u64`, `1..2^64-1` | no | Permanent claim inode identity. |
| 5 | `entryIdentity` | `bytes8k` | no | Exact entry identity bytes. |
| 6 | `signerIdentity` | `bytes8k` | no | Exact signer identity bytes. |
| 7 | `normalizedEntryProcessState` | `bytes8k` | no | Exact normalized entry process-state bytes. |
| 8 | `claimHeaderSha256` | `sha256` | no | Digest of the exact immutable claim-header bytes. |
| 9 | `runtimeIdentity` | `bytes8k` | no | Exact bound runtime identity bytes. |
| 10 | `signerKeyIdentity` | `bytes8k` | no | Exact bound signer/key identity bytes, including the key ID/public-key identity used by this envelope. |
| 11 | `durableSessionStateAbsent` | boolean, exact `true` | no | Required proof-state assertion; collector must cross-check the authoritative claim/session state. |
| 12 | `remote-evidence-present` | boolean, exact `true` | no | Existing design field verbatim. |
| 13 | `status` | integer `1..255` | no | Actual terminal status; `recovery-detected` fixes it to 70. |
| 14 | `source` | `native-entry|installer|recovery-detected` | no | Existing closed source set. |
| 15 | `namespaceRemapBytes` | `bytes16k?` | yes | Exact canonical claim-namespace-remap record bytes. |
| 16 | `namespaceRemapPath` | `path512` | yes | Exact fixed token/new-selection-derived remap path. |
| 17 | `namespaceRemapSize` | integer `1..16384` | yes | Decoded remap byte length. |
| 18 | `namespaceRemapSha256` | `sha256` | yes | Digest of decoded remap bytes. |
| 19 | `oldBootId` | `bootId` | yes | Sealed old boot ID. |
| 20 | `newBootId` | `bootId` | yes | Authenticated current boot ID. |
| 21 | `oldNamespaceSelectionIdentity` | `bytes8k` | yes | Exact old selection identity bytes. |
| 22 | `newNamespaceSelectionIdentity` | `bytes8k` | yes | Exact new selection identity bytes. |
| 23 | `oldNamespaceAttestationIdentity` | `bytes8k` | yes | Exact old attestation identity bytes. |
| 24 | `newNamespaceAttestationIdentity` | `bytes8k` | yes | Exact new attestation identity bytes/current-match proof. |

| Claim-terminal condition | Required rule |
|---|---|
| Original or same-boot publication | Fields 15..24 are all JSON `null`. Partial null groups are invalid. |
| Changed-boot publication | Fields 15..24 are all non-null; old/new boot IDs differ; decoded bytes equal `namespaceRemapSize` and `namespaceRemapSha256`; path is the exact fixed child; all identities byte-equal the embedded remap cross-links. |
| `native-entry` / `installer` | Caught original failure; any status 1..255; remap group null. |
| `recovery-detected` | Every recorded owner is proved absent and `status=70`; remap group follows actual same/changed boot. |
| Authority | It authorizes no bundle, workload, receipt, READY, benchmark projection, or performance decision. |

### 4.2 `revoman-cs2a-claim-terminal-ready/v1`

| # | Key | Type/cap | Null | Exact existing-design meaning/invariant |
|---:|---|---|---|---|
| 1 | `schema` | exact schema string | no | `revoman-cs2a-claim-terminal-ready/v1` |
| 2 | `token` | `token` | no | Must equal the bound envelope token. |
| 3 | `claimDevice` | `u64` | no | Permanent claim device identity. |
| 4 | `claimInode` | `u64`, nonzero | no | Permanent claim inode identity. |
| 5 | `claimEnvelopeHeaderSha256` | `sha256` | no | `SHA256(domain || u64be(payloadLength))` for the bound claim envelope. |
| 6 | `claimEnvelopePath` | `path512` | no | Exact `/opt/revoman-benchmark/cs2a-claim-terminals/cs2a.TOKEN.claim`. |
| 7 | `claimEnvelopeSize` | integer `40..65466` | no | Exact envelope-file byte length (domain 30 + length 8 + nonempty JSON-object payload). |
| 8 | `claimEnvelopeSha256` | `sha256` | no | Digest of exact envelope-file bytes. |
| 9 | `claimEnvelopeSignatureSha256` | `sigSha256` | no | Digest of exact detached claim-envelope signature bytes. |
| 10 | `receiptSigningKeyId` | `keyId` | no | Key ID that verified the bound envelope and signs this commit. |
| 11 | `receiptSigningPublicKeySha256` | `sha256` | no | SHA-256 of the exact raw public-key file. |
| 12 | `receiptSigningKeyFingerprint` | `sshFingerprint` | no | Exact OpenSSH fingerprint derived above. |
| 13 | `claimEnvelopeParentDurable` | boolean, exact `true` | no | Completed envelope and parent durability barrier. |
| 14 | `postEnvelopeNamespaceRemapBytes` | `bytes16k?` | yes | Exact signed post-envelope current-match remap bytes. |
| 15 | `oldBootId` | `bootId` | yes | Old boot ID. |
| 16 | `newBootId` | `bootId` | yes | Current boot ID. |
| 17 | `oldNamespaceSelectionIdentity` | `bytes8k` | yes | Exact old selection identity. |
| 18 | `newNamespaceSelectionIdentity` | `bytes8k` | yes | Exact new selection identity. |
| 19 | `oldNamespaceAttestationIdentity` | `bytes8k` | yes | Exact old attestation identity. |
| 20 | `newNamespaceAttestationIdentity` | `bytes8k` | yes | Exact new attestation/current-match identity. |

| Claim-terminal-ready condition | Required rule |
|---|---|
| Original or same-boot publication | Fields 14..20 are all `null`. |
| Changed-boot recovery publishing a previously absent commit | Fields 14..20 are all non-null; boot IDs differ; embedded bytes and all identities cross-match the current authenticated remap. |
| Bound pair | Fields 2..12 MUST match exact bytes/metadata of the already-durable claim envelope/signature and the same permanent claim. This commit is never interpreted as the envelope or READY. |
| Causal boundary | Signature final name precedes envelope rename; the claim-terminal-ready envelope rename is the causal collection boundary; parent fsync controls publisher success. |

### 4.3 `revoman-cs2a-ready/v1`

| # | Key | Type/cap | Null | Exact existing-design meaning/invariant |
|---:|---|---|---|---|
| 1 | `schema` | exact schema string | no | `revoman-cs2a-ready/v1` |
| 2 | `token` | `token` | no | Session token. |
| 3 | `receiptKind` | `run-finalized|run-finalization-failure|supervisor-finalized-no-run|prelaunch-failure` | no | Closed receipt kind. |
| 4 | `receiptDirectoryDevice` | `u64` | no | Receipt-directory device identity. |
| 5 | `receiptDirectoryInode` | `u64`, nonzero | no | Receipt-directory inode identity. |
| 6 | `receiptInventoryByteLength` | integer `1..4194304` | no | Exact canonical inventory byte length. |
| 7 | `receiptInventoryFileCount` | integer `0..8192` | no | Inventory file count. |
| 8 | `receiptInventoryDirectoryCount` | integer `0..8192` | no | Unique nonempty directory-prefix count. |
| 9 | `receiptInventoryTotalObjectCount` | integer `1..8192` | no | Equals fields 7 + 8 and inventory record count. |
| 10 | `receiptAggregatePathBytes` | integer `1..1048576` | no | Aggregate relative-path bytes. |
| 11 | `receiptMetadataByteTotal` | integer `0..67108864` | no | Nonprojection receipt metadata total. |
| 12 | `receiptProjectionByteTotal` | integer `0..2147483648` | no | Sealed projection total. |
| 13 | `receiptGrandByteTotal` | integer `1..2147483648` | no | All inventory-covered file bytes; equals applicable component totals. |
| 14 | `receiptInventorySha256` | `sha256` | no | Digest of exact inventory bytes. |
| 15 | `transitionHeadSha256` | `sha256` | no | Frozen receipt transition-head digest. |
| 16 | `terminalStatus` | integer `0..255` | no | Exact authenticated terminal status. |
| 17 | `terminalSource` | `installer|orchestrator|supervisor|finalizer|recovery-detected` | no | Exact authenticated terminal source. |
| 18 | `implementationSha` | `implementationSha` | no | Executed implementation identity. |
| 19 | `runtimeConfigSha256` | `sha256` | no | Runtime-config digest. |
| 20 | `approvalIdentity` | `bytes8k` | no | Exact selected approval identity bytes. |
| 21 | `bundleManifestSha256` | `sha256` | no | Approved bundle-manifest digest. |
| 22 | `signerIdentity` | `bytes8k` | no | Exact canonical signer identity/provenance bytes. |
| 23 | `receiptSigningKeyId` | `keyId` | no | Raw-public-key-derived key ID. |
| 24 | `receiptSigningKeyFingerprint` | `sshFingerprint` | no | Exact OpenSSH fingerprint. |
| 25 | `cleanHostPrerequisite` | `not-reached|assumed-and-endpoint-checked|detected-failure` | no | Existing design field and enum verbatim. |
| 26 | `receiptParentDurable` | boolean, exact `true` | no | Receipt-directory rename and receipt-parent fsync completed before READY signing. |
| 27 | `activeGenerationSequence` | `sequence` | no | Already-durable active-generation sequence. |
| 28 | `activeGenerationSha256` | `sha256` | no | Exact active-generation record digest. |
| 29 | `activeGenerationPreviousHeadSha256` | `sha256` or `null` | yes | Previous global-ledger head; null only for the genesis rule already defined by the ledger. |
| 30 | `admissionLockIdentity` | `bytes8k` | no | Exact admission-lock identity bytes bound by the active generation. |
| 31 | `postReceiptNamespaceRemapBytes` | `bytes16k?` | yes | Exact signed post-receipt remap/current-match bytes. |
| 32 | `oldBootId` | `bootId` | yes | Sealed receipt boot ID. |
| 33 | `newBootId` | `bootId` | yes | Authenticated current boot ID. |
| 34 | `oldNamespaceSelectionIdentity` | `bytes8k` | yes | Exact old selection identity. |
| 35 | `newNamespaceSelectionIdentity` | `bytes8k` | yes | Exact new selection identity. |
| 36 | `oldNamespaceAttestationIdentity` | `bytes8k` | yes | Exact old attestation identity. |
| 37 | `newNamespaceAttestationIdentity` | `bytes8k` | yes | Exact new attestation/current-match identity. |

| READY condition | Required rule |
|---|---|
| Inventory arithmetic | `totalObjectCount=fileCount+directoryCount=inventoryRecordCount`; every sum is checked u64; grand total and all independent caps hold. |
| Projection | `receiptProjectionByteTotal>0` is permitted only for `run-finalized`; all other receipt kinds require zero and no projection. The existing terminal phase/field matrix remains authoritative for status/source. |
| Original or same-boot publication | Fields 31..37 are all `null`. |
| Changed-boot recovery publishing previously absent READY | Fields 31..37 are all non-null; boot IDs differ; embedded remap bytes and identities exactly match authenticated old/new selection and current namespace/rootfs observation. |
| Excluded future state | READY binds the already-durable active generation but MUST NOT bind a later terminal-observed or clearance head; those later records point to the signed READY pair. |
| Authority | The collector may trust fields only after verifier exit 0 and exact success-line validation, then MUST cross-validate the receipt/inventory and current trust policy. |

## 5. `ReceiptSignatureVerifier` contract

| Interface item | Exact contract |
|---|---|
| Main class | `com.salesforce.revoman.benchmark.driver.integrity.ReceiptSignatureVerifier` only. |
| Application argv | Exactly three positional operands: `ENVELOPE_PATH SIGNATURE_PATH PUBLIC_KEY_PATH`. No leading mode and no fourth operand. Each is an absolute canonical printable-ASCII path of 1..4096 bytes. |
| Path opening | Open no-follow; require regular file, `nlink=1`, expected current-user ownership, no group/other write bit, protected no-group/other-writable ancestry, stable pre/post `fstat` identity/size, and three distinct `(device,inode)` identities. |
| File caps | Envelope `31..65472` bytes (schema-specific exact size and nonempty JSON object are checked); signature exactly 64; public key exactly 32. |
| Domain selection | Match only the three compiled-in terminal domains. A caller cannot select a domain. Match and u64be framing may be inspected before verification; no payload JSON token or field may be parsed then. |
| Verification order | Validate argc/process/path/type/size -> match domain/read u64be/check cap and EOF -> validate raw key and signature canonicality -> Ed25519-verify exact envelope -> parse/re-encode canonical JSON and schema table -> emit success. No success byte is written earlier. |

| Process-envelope property | Exact requirement |
|---|---|
| Launch | Direct absolute inventoried JDK 21 `bin/java`; explicit inventoried classpath; fixed main class; never Gradle, a shell-selected class, `-jar`, or ambient `CLASSPATH`. JVM argv is exactly `-Duser.language=en -Duser.country=US -Duser.timezone=UTC -Dfile.encoding=UTF-8 -cp CLASSPATH MAIN ENVELOPE SIGNATURE PUBLIC_KEY`. |
| Provider | Algorithm exactly `Ed25519`; provider exactly `SunEC`, version string exactly `21`, obtained from the pinned inventoried JDK. No provider/algorithm argument or property. The complete provider list/order and JDK inventory are revalidated by the authenticated executor/session record. |
| Environment | Exact three-entry environment: `LANG=C`, `LC_ALL=C`, `TZ=UTC`. `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_HOME`, `CLASSPATH`, application option variables, agents, alternate security properties, provider overrides, and every other environment entry are absent. |
| Cwd/umask | Authenticated canonical read-only cwd outside repository, worktree, temporary, run, evidence, and cleanup roots; mode/ancestry not writable by the verifier identity; `umask 077`. |
| Descriptors | FD 0 is canonical `/dev/null`; FD 1 and FD 2 are separate bounded write-only capture pipes/files; every FD >2 is closed. Stdout cap 512 bytes; stderr cap 96 bytes. |
| Deadlines | Caller enforces 10 seconds total and kills on expiry; timeout, signal, cap overflow, extra FD, or envelope drift is a hard invalid verification and authorizes no archive. It is not remapped to an apparent verifier exit. |
| Distribution | Exact no-clobber CAS distribution/source/dependency/JDK inventories and build recipe are authenticated before each launch. Candidate Gradle, network, user init scripts, daemon, cache, and caller-selected executable are forbidden. |

| Exit | Stdout | Stderr | Meaning |
|---:|---|---|---|
| 0 | Exactly one LF-terminated seven-field TSV line: `revoman-cs2a-signature-verified/v1`, schema, envelope SHA-256, payload SHA-256, signature SHA-256, key ID, OpenSSH fingerprint. No trailing field. | Empty | Canonical point/signature, Ed25519 signature, canonical JSON, exact schema/order/types/nullability/caps, and schema/domain equality all passed. |
| 2 | Empty | Exactly `revoman-cs2a-signature-error/v1\tinvalid-input\n` | Wrong argc/path/process envelope; unsupported/ambiguous domain; malformed/over-cap/short/extra input; noncanonical key/signature/JSON; schema/type/order/nullability/cap/cross-field failure. |
| 3 | Empty | Exactly `revoman-cs2a-signature-error/v1\tverification-failed\n` | Structurally canonical inputs reached Ed25519 verification but the signature equation failed (including wrong canonical key, changed envelope, or cross-domain substitution). |
| 70 | Empty | Exactly `revoman-cs2a-signature-error/v1\tinternal-failure\n` | Pinned provider/JDK or other internal computation failed after the required process envelope was established. No exception, path, payload, provider text, stack trace, or attacker byte is emitted. |

All SHA-256 fields in the success line are lowercase 64-hex; `schema` is the matched domain literal;
the line is at most 383 bytes. The caller requires exact status/output agreement and exact field
count before retaining the result. Any other status, signal, missing/extra line, CR, NUL, oversized
capture, or stderr on exit 0 is a hard stop. The caller never reopens JSON to rediscover a value
already returned by a future schema-specific authenticated verifier.

## 6. Exact unresolved design decision

| ID | Evidence gap | Exact minimal decision required before RED production work |
|---|---|---|
| U1 | The frozen design and dependent Plans 1–4 name but do not enumerate the byte schemas for “entry identity”, “signer identity”, “normalized entry process state”, “runtime identity”, “signer/key identity”, “approval identity”, “admission-lock identity”, or namespace selection/attestation identity/current-match records. Inventing their semantic members here would exceed cryptographic-framing closure. | Plan 2/3 must name, for each `bytes8k`/`bytes16k?` field above, the one existing authoritative record schema and fixed path whose exact canonical bytes are embedded, or explicitly approve a complete ordered sub-schema. It must also prove each chosen record fits the stated decoded cap. This is one grouped source-schema selection decision; field names, outer types, framing, nullability, and caps in this proposal do not otherwise vary. |

There are no other unresolved choices in this package. U1 is a gate, not a placeholder: until the
named source-record mapping is approved, fixture authors MUST NOT fabricate identity JSON or replace
exact bytes with caller-authored summaries/digests.
