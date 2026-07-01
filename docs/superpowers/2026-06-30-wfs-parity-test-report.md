# Workforce Scheduling — Read/Write Parity Test Report

**Suite:** `WfsWritePathParityE2ETest` + `WfsReadPathParityE2ETest` + `WfsRulesParityE2ETest`

**Last verified:** 01 Jul 2026, live against the 262 WFS workspace org (`orgfarm-4dbef90d6c…:6101`)

**Release under test:** 262 (each test records *today's* behavior; the 264 expectation is noted per scenario)

---

## Purpose

This suite books and reads back real appointments against a live Workforce Scheduling org and pins down exactly how the product behaves today. They are **characterization tests**: each one asserts what the product *actually* does on release 262 — including several genuine crashes we don't want to paper over — and the description of each scenario states what release 264 is expected to change. When 264 lands and changes a behavior, the matching test will fail, and that failure is the signal the team is waiting for.

The behaviors under test come from a set of product decisions about (a) how a **non-required "helper" resource** is treated when scheduling (Decisions 1 / 1.4 / 1.5 / 3), (b) two read-path presentation rules (a result cap and a sharing gate — Decisions 8 / 9), (c) primary-resource and reschedule invariants (Decisions 2 / 4 / 4z / 5), and (d) whether every scheduling **rule** evaluates identically on the read and write APIs (the *rules read==write parity* matrix).

### The three test classes

- **`WfsWritePathParityE2ETest`** — the write/booking path (Schedule + Reschedule). Decisions 1, 1.4, 1.5, 3, 4, 4z, 5.
- **`WfsReadPathParityE2ETest`** — the read path (GetAvailableResources, GetSlots). Decisions 2, 8, 9.
- **`WfsRulesParityE2ETest`** — proves each scheduling **rule** evaluates identically on read and write (read==write), across all four read APIs + both write APIs. The seven Common+InBusiness rules.

All three are **live, environment-gated** tests: each `@Test` calls `ReVomanConfigForWfs.assumeExternalOrgCreds()` first and JUnit-**skips** (does not fail) when `~/.revoman/config.yaml` external-org creds are absent. Creds present → they run live; the committed `ws.environment.yaml` stays blank (no secrets).

### Vocabulary

- **Required resource** — a worker the appointment genuinely depends on; checked against every rule.
- **Non-required "helper"** — a worker added alongside, flagged *not* required. The recurring question is *how much rule-checking a helper gets*.
- **The anchor (a.k.a. resourceA)** — in the helper scenarios, the clean **required + primary** worker that always passes, so the *only* variable is the helper.
- **The helper (a.k.a. resourceB)** — the non-required worker, set up to break exactly **one** rule, so the outcome is attributable to that rule alone.
- **Policy / Fixture** — the rulebook the engine applies / the test data wired so only the rule under test can decide the outcome.
- **schedulingStatus** — the verdict on a booking: `Success` = booked; `ScheduleError` / `PersistError` = rejected.
- **read==write parity** — the same rule, run over the same fixture, produces the same decision on the read path (slots offered) and the write path (booking accepted/rejected).

---

## The headline finding (rules read==write parity)

The `WfsRulesParityE2ETest` suite set out to prove read==write per rule *and to surface any divergence*. **Result: read==write holds for every rule — no genuine read≠write divergence was found.** The two things originally hypothesized as divergences were both **refuted** by live evidence:

- **RequiredResources** is read==write: the read path and the write path **both crash identically** with the same `serviceTerritoryMembers` NPE (they share one engine, so the 262 bug manifests on both) — a shared-engine crash, not an asymmetry.
- **The reschedule no-op short-circuit** is **not reachable over REST** for a required-resource appointment (jdwp-confirmed: a 15/18-char ServiceResourceId mismatch always trips `resourcesHaveChanged`), so the reschedule recomputes and 262 crashes.

Also corrected along the way: **`get-available-resources` is NOT a rule subset** — it runs the full seven-rule engine, then only post-processes/truncates the survivors. All four read APIs are faithful rule-parity surfaces.

Net: the shared-engine design (`InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots`, re-invoked on write via `SlotAvailabilityChecker`) makes read==write **structural**, and the tests confirm it — the only asymmetries observed are shared-engine 262 crash bugs (identical on both paths) and one unreachable short-circuit.

---

## Scenario catalogue

### `WfsWritePathParityE2ETest` (the write/booking path)

#### Decision 1 — a non-required helper is NOT fitness-checked (four scenarios)

> **The claim:** add a helper that breaks one scheduling rule, and on 262 the booking still succeeds — the helper escapes the check. Verified across four rules; each its own `revUp` (fresh login + fresh users) so the four can't collide on resource uniqueness.
>
> **Method:** `testNonRequiredHelperFitnessE2E` — all four are dimensions of this one method.

- **1a Excluded** *(asserts `excludedNonReqSchedulingStatus == "Success"`)* — helper on the account's excluded list; only **ExcludedResources** can fire. **262: Success** (helper escapes). *(264: `ScheduleError`/ExcludedResources.)*
- **1b Territory** *(`territoryNonReqSchedulingStatus == "Success"`)* — helper's membership overlaps but doesn't contain the window; only **MatchTerritory** can fire. **262: Success.** *(264: `ScheduleError`/MatchTerritory.)*
- **1c Skills** *(`skillsNonReqSchedulingStatus == "Success"`)* — helper lacks the work-type skill; only **MatchSkills** can fire; helper kept non-primary so the primary-implies-required guard can't mask it. **262: Success.** *(264: `ScheduleError`/MatchSkills.)*
- **1d Working-locations** *(`workingLocationsSecondaryNonReqSchedulingStatus == "Success"`)* — helper is a secondary member under a primary-only policy; only **WorkingLocations** can fire. **262: Success.** *(264: `ScheduleError`/WorkingLocations.)*

#### Decision 1.4 — a helper cannot satisfy a *required-resource demand* (two scenarios)

> **Method:** `testNonRequiredHelperCannotSatisfyRequiredDemandE2E` — violating probe + control, two `revUp`s.

- **1.4a Violating** *(asserts `requiredNonReqSatisfierErrorCode == "INTERNAL_SERVER_ERROR"` **and** `…ErrorMessage contains "serviceTerritoryMembers"`)* — the account requires worker B via `ResourcePreference(Required)`, but B is only a helper. **262: server CRASH** (null-pointer on `serviceTerritoryMembers`), *not* the tidy RequiredResources rejection the doc predicted. Asserted verbatim. *(264: clean `ScheduleError`/RequiredResources → test flips.)*
- **1.4b Control** *(`requiredSatisfierControlErrorCode != "RequiredResources"`)* — a genuine required worker. **262: rejected on availability** (`INVALID_INPUT`), not a RequiredResources error.

#### Decision 1.5 — a helper is NOT availability-checked, so it can double-book (two scenarios)

> **Method:** `testNonRequiredHelperDoubleBooksE2E` — one `revUp`, A/B flips only `isRequiredResource` on resourceB.

- **1.5a Busy helper, non-required** *(`doubleBookNonRequiredSchedulingStatus == "Success"`)* — busy helper double-books, no availability check. **262: Success.** *(264: blocked as not-available.)*
- **1.5b Same worker, flipped to required (control)** *(`doubleBookRequiredControlSchedulingStatus != "Success"`)* — a *required* worker **is** availability-checked. **262: rejected** (`400 INVALID_INPUT`). Cleanest helper-vs-required demonstration in the suite.

#### Decision 3 — a missing `isRequiredResource` flag (two scenarios)

> **Method:** `testMissingRequiredFlagE2E` — Act A (missing flag) + Act B (control), two `revUp`s.

- **3a Missing flag** *(asserts `missingRequiredFlagErrorCode == "INTERNAL_SERVER_ERROR"` **and** `…ErrorMessage contains "Boolean.booleanValue()"`)* — a resource sent with no `isRequiredResource`. **262: server CRASH** (null-pointer reading the missing flag). Asserted verbatim. *(264: treat missing as not-required → test flips.)*
- **3b L142 control** *(`singleRequiredNoPrimaryStatus == "Success"`)* — a single `isRequiredResource=true` with no `isPrimaryResource`. **262: Success** — the absence of the *primary* flag is harmless.

#### Decision 4 — two primary resources rejected up front *(NEW)*

> **Method:** `testTwoPrimaryResourcesRejectedE2E` — one `revUp`.

- *Setup:* one appointment, **two** assigned resources both `isPrimaryResource=true` (both also required). Multi-resource scheduling requires exactly one primary, caught at **input validation** (`ScheduleCommonValidator.validatePrimaryResourceConstraints`), before availability/persist.
- *Asserts:* `twoPrimaryErrorCode == "INVALID_INPUT"` **and** `twoPrimaryErrorMessage contains "can be a primary resource"` **and** `twoPrimaryHttpCode == "400"` **and** `twoPrimaryStatus` is null (no booking).
- *262 result:* **clean HTTP 400 / `INVALID_INPUT`**, message "Only one of the provided assigned resource can be a primary resource." (org renders it singular). *(264: unchanged — the doc's "confusing DB error" era is over; 262 and 264 coincide, so this is characterization-only.)*

#### Decision 5 — a primary marked not-required is rejected at persist (no auto-correct, no double-book) *(NEW)*

> **Method:** `testPrimaryNotRequiredRejectedE2E` — probe + control, two `revUp`s.

- **5 probe** *(asserts `primaryNotReqStatus == "PersistError"` **and** `primaryNotReqErrorCode == "INVALID_FIELD"` **and** `primaryNotReqErrorMessage contains "primary service resource"`)* — a single resource `isPrimaryResource=true, isRequiredResource=false` into a **free** window. Input validation doesn't inspect the required flag, so it passes validation, availability passes (free), and it's **rejected at persist** (`fieldservice` `LightningSchedulerAssignedResourceValidator`). **262: `PersistError` / `INVALID_FIELD`**, message "Only an required service resource can be set as a primary service resource." (HTTP 201 with `appointments[0].errors[0]`). REFUTES both the story's "quietly fixed to required" (no auto-correct) and the 262 "could be double-booked" claim (nothing persists). *(264: unchanged — reject is intended.)*
- **5 control** *(`primaryReqControlStatus == "Success"`)* — same resource/window, `isRequiredResource=true`. **262: Success** — isolates the reject to the not-required flag.

#### Decision 4z — a reschedule CAN leave an appointment with no primary (the product doc's claim is wrong) *(NEW)*

> **Method:** `testRescheduleNoPrimaryE2E` — one `revUp` chaining a clean two-resource schedule then two reschedule arms over the captured SA.

- *Setup / claim:* the doc says "it is not possible to schedule or reschedule an appointment without a primary resource." This is **wrong for the reschedule API**: `RescheduleCommonValidator.validatePrimaryResourceCount` explicitly *allows* zero primaries; the doc's quoted error "isPrimaryResource cannot be set for Delete" is a **payload-field guard**, not a crew rule.
- *Asserts:* clean schedule `reschedCleanStatus == "Success"` + `reschedCleanSaId` captured; **Arm A** (`DeleteOperation` on the primary **with** `isPrimaryResource:true`) → `reschedWithFlagErrorCode == "INVALID_INPUT"` + `reschedWithFlagHttpCode == "400"` (the payload-field guard; SA not mutated); **Arm B** (delete the primary **without** the flag) → `reschedNoFlagStatus != "Success"`, `reschedNoFlagErrorCode == "INVALID_INPUT"`, HTTP 400, message contains "not available for the requested slot".
- *262 result:* the doc is **refuted at the validation layer** (no must-keep-primary rule). Live, Arm B does not complete either — but it is blocked by a **downstream availability re-check** (`SlotNotAvailable`), NEVER a primary rule. So end-to-end the 262 org can't leave a primary-less crew *via this path*, but for the reason the doc got wrong. **This is a DOC bug** (handed off), not a code bug; the product OPEN QUESTION "should a reschedule be allowed to leave no primary?" stands — today validation allows it.

---

### `WfsReadPathParityE2ETest` (the read path)

> Read-path contract: a rule violation or a cap returns an **empty** list (or a rejected booking on the paired write), never an unexpected 4xx/5xx on the read itself.

#### Decision 2 — a shown slot is a promise on the shared cheap checks (read==write) *(NEW)*

> **Method:** `testCheapCheckReadWritePromiseE2E` — one `revUp`: two GetSlots reads + two Schedule writes over one resource.

- *Setup:* over a single resource, an **available** window (inside its OH+shift) and an **unavailable** window (outside). The cheap availability check is shared by read and write.
- *Asserts:* `parityReadAvailSlotCount > 0` **and** `parityReadUnavailSlotCount == 0` **and** `parityWriteAvailStatus == "Success"` **and** `parityWriteUnavailStatus != "Success"` **and** `parityWriteUnavailErrorCode == "INVALID_INPUT"` + message "not available for the requested slot".
- *262 result:* read offers slots in the available window ⟺ write Succeeds; read offers 0 in the unavailable window ⟺ write is rejected. Proves the shown slot is a **promise** on the shared cheap check.
- *Scope honesty (in the test javadoc):* the doc's other half — a slot *shown* on the read path yet *turned down* at booking on a **field-match** rule — is **NOT characterizable on 262**: the three field-match rules (MatchFields/MatchBoolean/ExtendedMatch) are OnField-only, evaluated inside the external ESO optimizer; the OnField path is a 262 stub; the live OnSite path shares read==write. Recorded, not attempted.

#### Decision 8 — the load-balancing result cap (two scenarios, one run)

> **Method:** `testResourceLimitApptDistributionCapE2E`.

- **8a Limit 0** *(`limitZeroResourceCount == 0` **and** `limitZeroErrorCode` null)* — **262: `availableResources: [[]]`**, empty, HTTP 200, no error. The empty list is the cap (`Stream.limit(0)`), confirmed by also asserting no error code.
- **8b Limit 50 (control)** *(`limitPositiveResourceCount > 0` **and** `limitPositiveErrorCode` null)* — resources returned. Proves 0 is a literal cap-of-0, not "unlimited". *(264, if product picks "no cap": 0 → all eligible.)*

#### Decision 9 — the silent shift-sharing gate (two scenarios, one run)

> **Method:** `testShiftSharingModeSplitE2E` — mint manager + case-worker personas; manager (control) + case-worker (probe) reads.

- **9a Manager (owner, control)** *(`dec9ManagerSlotCount > 0`)* — the owner's user-mode shift read sees its own shift. **262: slots returned.**
- **9b Case-worker (no sharing, probe)** *(`dec9CaseWorkerSlotCount == 0`)* — identical request, no sharing on the manager's private shifts. **262: zero slots, HTTP 200, no error** — user-mode shift read returns empty, resource still admitted but contributes no slots. *(264, option B: align modes → case-worker also sees slots.)*

---

### `WfsRulesParityE2ETest` (rules read==write parity) *(NEW class — 8 tests)*

> Each rule runs a **differential matrix**: a *violating* case (rule fires → read 0 slots AND write rejected) + a *control* (rule passes → read >0 AND write Success), asserting the read decision == the write decision in both rows. Each violation is on the **required+primary** resource (single-variable), and each control returns >0 + Success (proves the fixture is valid, so the violating 0 is the rule, not a dead fixture). A pruned-candidate write reject surfaces as a generic `INVALID_INPUT "not available for the requested slot"`, so the write-violating assertion is `status != "Success"` (the honest ceiling). Availability (the 7th Common rule) is already proven by `WfsReadPathParityE2ETest.testCheapCheckReadWritePromiseE2E` (Decision 2) and is referenced, not re-authored.

- **MatchSkills** `testMatchSkillsReadWriteParityE2E` — required+primary resource lacks the skill. **PARITY:** read 0 ⟺ write reject; control >0 ⟺ Success.
- **ExcludedResources** `testExcludedResourcesReadWriteParityE2E` — required+primary resource on the account excluded list. **PARITY.**
- **WorkingLocations** `testWorkingLocationsReadWriteParityE2E` — the required+primary resource is a Secondary member under a primary-only policy (the WorkingTerritories rule's own `IsPrimaryLocationEnabled`/`IsSecondaryLocationEnabled` params drive `loadMembersInTerritory`, Core-confirmed). **PARITY.**
- **VisitingHours** `testVisitingHoursReadWriteParityE2E` — booking window outside the account's visiting hours (09-10 outside VH 10-14) while inside the resource's OH+shift, so only VisitingHours can prune. Policy+fixture lifted from source and 262-conformed. **PARITY.**
- **AppointmentStartTimeInterval** `testStartTimeIntervalReadWriteParityE2E` — an off-boundary start (11:30 vs an on-boundary 11:00 control) under a net-new policy that pins interval=60. **PARITY.**
- **RequiredResources** `testRequiredResourcesReadWriteBothCrashE2E` — **read==write, BOTH crash.** The read path crashes with the SAME `serviceTerritoryMembers` NPE (HTTP 500 `INTERNAL_SERVER_ERROR`) the write path throws (1.4a) — they share `loadSchedulableSlots`, so the 262 bug is identical on both. *Asserts* `requiredReadViolatingErrorCode == "INTERNAL_SERVER_ERROR"` + message "serviceTerritoryMembers"; a satisfied-demand control does not crash (HTTP 201, null error, 0 slots — proving the crash is conditional on the non-required-helper input). **This REFUTES the plan's read-prunes/write-crashes divergence hypothesis** — it's a shared-engine crash, not an asymmetry.
- **Cross-API agreement** `testCrossApiRuleAgreementE2E` — one MatchSkills violation run through **all four read APIs + schedule**: `get-appointment-slots` 0, `get-appointment-candidates` 0, `get-available-slots` 0, `get-available-resources` prunes the skill-lacking resource (resourceB **absent** while resourceA **survives**, survivor count > 0), and schedule rejects. Proves all four reads + write share the one engine → the per-rule matrix generalizes to every API, and confirms `get-available-resources` runs the **full** rule set (not a subset).
- **No-op reschedule short-circuit** `testNoOpRescheduleShortCircuitE2E` — the `SlotAvailabilityChecker:174-176` short-circuit is **not reached over REST** for a required-resource SA. *Asserts* setup schedule Success + saId captured; the no-op reschedule → `status != "Success"`, HTTP 500, `INTERNAL_SERVER_ERROR`, message "getServiceResourceIds". **jdwp-confirmed mechanism** (breakpoint `SlotAvailabilityChecker:237`): the existing SA's required-resource id is 18-char (`0Hnxx0000004GB2CAM`, from SOQL) while the reschedule request's is 15-char (`0Hnxx0000004GB2`, the ESO request DTO truncates it) → the sets never match → `resourcesHaveChanged==true` → short-circuit skipped → recompute → 262 crash. **REFUTES the plan's "no-op returns Success via the short-circuit" premise**; the recurring 15/18-char ResourceId canonicalization gotcha, surfacing here.

---

## Recorded results at a glance

| # | Scenario | Test method | 262 result | Status |
|---|---|---|---|---|
| 1a | Dec 1 excluded helper | `testNonRequiredHelperFitnessE2E` (1/4) | Success | ✅ |
| 1b | Dec 1 territory helper | `testNonRequiredHelperFitnessE2E` (2/4) | Success | ✅ |
| 1c | Dec 1 skills helper | `testNonRequiredHelperFitnessE2E` (3/4) | Success | ✅ |
| 1d | Dec 1 working-locations helper | `testNonRequiredHelperFitnessE2E` (4/4) | Success | ✅ |
| 1.4a | Dec 1.4 helper can't satisfy required | `testNonRequiredHelperCannotSatisfyRequiredDemandE2E` (violating) | **server crash** (serviceTerritoryMembers NPE) | ✅ |
| 1.4b | Dec 1.4 genuine required (control) | `…CannotSatisfyRequiredDemandE2E` (control) | INVALID_INPUT (availability) | ✅ |
| 1.5a | Dec 1.5 busy helper, non-required | `testNonRequiredHelperDoubleBooksE2E` | Success (double-books) | ✅ |
| 1.5b | Dec 1.5 busy worker, required (control) | `testNonRequiredHelperDoubleBooksE2E` | rejected (not-available) | ✅ |
| 3a | Dec 3 missing required flag | `testMissingRequiredFlagE2E` (Act A) | **server crash** (Boolean.booleanValue NPE) | ✅ |
| 3b | Dec 3 single required, no primary (control) | `testMissingRequiredFlagE2E` (Act B) | Success | ✅ |
| 4 | Dec 4 two primary resources | `testTwoPrimaryResourcesRejectedE2E` | HTTP 400 INVALID_INPUT (input validation) | ✅ |
| 5 | Dec 5 primary not-required (probe) | `testPrimaryNotRequiredRejectedE2E` | PersistError / INVALID_FIELD (persist reject) | ✅ |
| 5c | Dec 5 primary+required (control) | `testPrimaryNotRequiredRejectedE2E` | Success | ✅ |
| 4z-A | Dec 4z reschedule delete-primary WITH flag | `testRescheduleNoPrimaryE2E` (Arm A) | HTTP 400 INVALID_INPUT (payload-field guard) | ✅ |
| 4z-B | Dec 4z reschedule delete-primary NO flag | `testRescheduleNoPrimaryE2E` (Arm B) | HTTP 400 INVALID_INPUT (availability, not a primary rule) | ✅ |
| 2 | Dec 2 cheap-check read==write promise | `testCheapCheckReadWritePromiseE2E` | read>0⟺Success; read0⟺rejected | ✅ |
| 8a | Dec 8 limit 0 | `testResourceLimitApptDistributionCapE2E` | empty list `[[]]`, no error | ✅ |
| 8b | Dec 8 limit 50 (control) | `testResourceLimitApptDistributionCapE2E` | resources returned | ✅ |
| 9a | Dec 9 manager (owner, control) | `testShiftSharingModeSplitE2E` | slots returned | ✅ |
| 9b | Dec 9 case-worker (no sharing) | `testShiftSharingModeSplitE2E` | 0 slots, 200, no error | ✅ |
| R-skills | Rules parity MatchSkills | `testMatchSkillsReadWriteParityE2E` | read==write (parity) | ✅ |
| R-excluded | Rules parity ExcludedResources | `testExcludedResourcesReadWriteParityE2E` | read==write (parity) | ✅ |
| R-workloc | Rules parity WorkingLocations | `testWorkingLocationsReadWriteParityE2E` | read==write (parity) | ✅ |
| R-visiting | Rules parity VisitingHours | `testVisitingHoursReadWriteParityE2E` | read==write (parity) | ✅ |
| R-sti | Rules parity StartTimeInterval | `testStartTimeIntervalReadWriteParityE2E` | read==write (parity) | ✅ |
| R-required | Rules parity RequiredResources | `testRequiredResourcesReadWriteBothCrashE2E` | read==write, **both crash** (serviceTerritoryMembers NPE) | ✅ |
| R-crossapi | Rules parity cross-API agreement | `testCrossApiRuleAgreementE2E` | all 4 reads + write agree (prune/reject) | ✅ |
| R-noop | Rules parity no-op reschedule | `testNoOpRescheduleShortCircuitE2E` | short-circuit unreachable → 500 (getServiceResourceIds NPE) | ✅ |

Full `WfsRulesParityE2ETest` suite last run **8/8 green together** (BUILD SUCCESSFUL, 7m3s, 0 license issues).

---

## Out-of-scope 262 bugs surfaced (handed off, not fixed here)

The suite characterizes **three distinct 262 slot-gen NPE crashes** (all HTTP 500 `INTERNAL_SERVER_ERROR`), all in the shared `loadSchedulableSlots` engine — see `~/work/handoff/2026-07-01-wfs-262-slotgen-npe-family.md`:

1. `serviceTerritoryMembers` NPE on **Schedule** (Decision 1.4a / RequiredResources write path).
2. Same `serviceTerritoryMembers` NPE on the **READ** path (GetSlots) for the same scenario — new evidence the bug is shared read+write (RequiredResources rules-parity test).
3. `ServiceTerritory.getServiceResourceIds()` NPE on **Reschedule-recompute** (no-op reschedule test) — a distinct call site / null field.

Plus a **DOC bug** (Decision 4z: the product doc's "cannot reschedule without a primary" is wrong) — see `~/work/handoff/2026-07-01-wfs-doc-4z-no-primary-contradiction.md`.

These crashes are the intended 262 behavior — pinned so the suite alerts the moment 264 fixes them (the assertions flip from crash to clean).

---

## Preconditions (why the tests are environment-gated)

These run against a **provisioned WFS workspace org**, not unattended CI, and self-skip (JUnit assumption) when creds are absent. The org must have:

- Multi-resource scheduling preference (`WorkforceSchdMulResSchdPref`) + InBusinessScheduling enabled.
- The `Shift.Status` picklist seeded so `Confirmed` shifts validate.
- Each resource: a confirmed shift covering the window + a territory membership effective in the past.
- The 262 release contract honored in the data (`SchedulingMethod=OnSite` on timeslot/shift/booking; WorkType carries no SchedulingMethod/IsRegular; relative composite-graph URLs).
- The **Workforce Scheduling** permission sets (Manager + Resource) assigned to the users.
- External-org creds in `~/.revoman/config.yaml` (baseUrl/username/password) — auto-overlaid onto every Kick's environment; the committed `ws.environment.yaml` stays blank (no secrets committed).

**Operational note:** the org accumulates timestamped `@revoman.org` personas across runs and can hit `LICENSE_LIMIT_EXCEEDED`; deactivate stale non-admin `@revoman.org` users to reclaim seats (never the admin `@orgfarm` user).

---

## Coverage notes (gaps a careful author would close next)

The scenarios are well-isolated and the crash assertions are sharp. Honest gaps, for the record:

1. **Caller identity isn't asserted.** The API-under-test runs as a least-privilege manager (proven live) rather than admin, but no test *fails* if someone re-aliases manager back to admin. A "the caller is the manager, not the admin" assertion would lock it in. (A fail-loud safeguard exists for a *broken* manager login, not for deliberate re-aliasing.)
2. **Decision 8's cap only proves the extremes.** The fixture seeds two resources, so "limit 50" and "no limit" look identical — it proves *0 returns zero* but not that the cap *trims* a longer list. A "limit 1 over a 2-resource pool → exactly 1" scenario would prove truncation.
3. **Decision 1's four scenarios are single-sided** *(now partially closed)*. Each proves the helper *books*; none has a paired "same violation *does* reject when the worker is required" control in that method. However, the new `WfsRulesParityE2ETest` matrix now provides exactly that paired violating+control coverage for MatchSkills / ExcludedResources / WorkingLocations (on the required+primary resource), so the "wrong-reason green" risk is materially reduced at the suite level; only the helper-specific single-sidedness remains in Decision 1 itself.
4. **A couple of "not equal to X" assertions are loose** (1.4b, 1.5b, and the parity write-violating `!= Success`). "Anything but Success" also passes for an empty/null response; where the exact reject value is stable it could be tightened. (Decisions 4/5 and the crash tests now assert exact codes/messages, so this is confined to the availability-reject rows where the message is generic.)
5. **Decision 4z / no-op reschedule "why" vs "what".** Both assert the observed 262 crash faithfully; the *mechanism* behind 4z's availability block and the no-op reschedule's `resourcesHaveChanged` are documented (the latter jdwp-confirmed as the 15/18-char id mismatch). Open product question for 4z stands: should a reschedule be allowed to leave no primary?
6. **RequiredResources satisfied-control returns 0 slots** (rules-parity). The control proves *no crash* (HTTP 201) but returns 0 slots — why a satisfied-demand read yields no slots is noted but not investigated.

These are next-step suggestions, not defects in what exists today.
