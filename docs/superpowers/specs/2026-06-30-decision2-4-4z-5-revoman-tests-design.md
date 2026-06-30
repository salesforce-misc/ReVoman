# Design — ReVoman tests for WFS read↔write parity Decisions 2, 4, 4z, 5

> **STATUS: IMPLEMENTED (2026-06-30).** All four decisions landed as live-verified ReVoman integrationTest
> tests (commits `1176dc4` Dec4, `99a9794` Dec5, `59f3954`+`c731795` Dec4z, `09fb608` Dec2, `8071048`
> final-review minors). Final whole-branch review: READY WITH MINORS (no Critical/Important; all minors
> fixed). The investigation reshaped the doc's claims — see decision log
> `~/work/impl-decisions/2026-06-30-decision2-4-4z-5-revoman-tests.md` (4z doc-claim refuted; 4 already-clean;
> 5 reject-not-autocorrect; 2 field-match half not characterizable on 262). 4z doc bug handed off:
> `~/work/handoff/2026-06-30-wfs-doc-4z-no-primary-contradiction.md`.

**Date:** 2026-06-30
**Author:** Claude (autonomous mode, workspace box)
**Branch:** `wfs/decision-1-9-revoman-tests` in `~/code-clones/work/revoman-root`
**Source decisions doc:** `~/work/impl-decisions/2026-06-21-PRODUCT-presentation-read-write-parity.md` (L91-166)
**Predecessor design (Decisions 1/1.4/1.5/3/8/9):** `docs/superpowers/specs/2026-06-30-decision1-9-revoman-tests-design.md`
**Predecessor decision log:** `~/work/impl-decisions/2026-06-30-decision1-9-revoman-tests-persona-auth.md`

## Goal

Extend the WFS read↔write parity characterization suite (`src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/`) to cover the four remaining decisions — **2, 4, 4z, 5** — as live ReVoman integration tests against the provisioned 262 workspace org, in the SAME harness/idioms the existing 6 decisions use (V3 collections, `kickFor(...)`, manager-persona auth, Approach-A fresh-persona-per-revUp, assert-262 / 264-contrast-in-javadoc).

These are **characterization tests**: each act is run live, the observed verdict is encoded, and the javadoc records what 264 would change. Per the existing suite's contract.

## Investigation summary — what the code actually does (vs. the doc's claims)

Five parallel read-only investigations of `/opt/workspace/core-public/core` (no Core edits) produced the load-bearing facts below. Citations are in the decision log; the headlines that shape the design:

### Decision 4 — two primary resources → clean input-validation rejection
- **Verdict (current code):** HTTP **400**, top-level connect error, **errorCode `INVALID_INPUT`**, message label `UnifiedSchedulingApiCommon/MultiplePrimary` → rendered **"Only one of the provided assigned resources can be a primary resource."**
- **Where:** `ScheduleCommonValidator.validatePrimaryResourceConstraints` (`unified-scheduling-impl/.../validators/schedule/ScheduleCommonValidator.java:179-181`) — **input validation, step 1**, before availability/persist. Reschedule mirrors it in `RescheduleCommonValidator:223`.
- **Response shape:** top-level array `[{errorCode, message}]` + HTTP 400 (NOT `appointments[0]…`), because the throw is in `validatePayload` before per-appointment processing.
- **Org pref:** requires `WorkforceSchdMulResSchdPref` enabled (already on for the workspace) — otherwise `isPrimaryResource` is not an accessible field and the rule is a no-op.
- **vs doc:** doc said 262 gave a "confusing database error"; the **current** code already returns the clean message. We characterize the current clean behavior (which is the 264 target) and note the historical 262 DB-error in javadoc.

### Decision 4z — reschedule leaving no primary → the doc's conclusion is WRONG
Two **independent** rules, keyed on different things:
- **(payload-field rule)** `RescheduleCommonValidator.validateDeleteOperationFields:200-204` throws `INVALID_INPUT` (label `UnifiedSchedulingRescheduleApi/InvalidPropertyForDelete`, param `isPrimaryResource`) **only when** a `DeleteOperation` entry **explicitly carries** `isPrimaryResource` (`_isSetIsPrimaryResource() && getIsPrimaryResource() != null`). This is the scenario's quoted "isPrimaryResource cannot be set for Delete."
- **(crew-state rule)** `RescheduleCommonValidator.validatePrimaryResourceCount:217-226` rejects **only** `primaryCount > 1`; its comment states **"allow zero primaries for reschedule."** Contrast the schedule path, which DOES reject zero-primary-with-multiple (`ScheduleCommonValidator:177` `NoPrimary`).
- **Contradiction resolved:** the func test `OnSiteRescheduleAppointmentsConnectApiTest.testRescheduleAppointmentDeleteAllAssignedResources:648` deletes all resources (incl. the primary) and **succeeds**, because its delete entries **omit** `isPrimaryResource`. So a reschedule **CAN** leave an appointment with no primary. The doc's blanket "not possible to reschedule without a primary" conflates the payload-field guard with a crew rule and is **incorrect for the reschedule API**.
- **Design consequence:** this is the richest finding. The test characterizes **both** arms:
  - **Arm A (doc's claimed error):** reschedule with `DeleteOperation` on the primary **WITH** `isPrimaryResource:true` → **400 `INVALID_INPUT`** (`InvalidPropertyForDelete`).
  - **Arm B (the reality the doc missed):** reschedule with `DeleteOperation` on the primary **WITHOUT** `isPrimaryResource` → **Success**, resulting crew has no primary.

### Decision 5 — primary marked not-required → persist reject, NO auto-correct
- **Verdict:** the request is **rejected** (not auto-corrected, not silently double-booked). Persist-layer producer is in **`fieldservice-impl`** (not unified-scheduling): `LightningSchedulerAssignedResourceValidator.java:82-88` → **errorCode `INVALID_FIELD`** on field `IsRequiredResource`, message label `Industries_AssignedResource/AssignedResourceSavePrimaryAndRequired` → **"Only an required service resource can be set as a primary service resource."** (the "an required" grammar is the `<a/>`+`<required/>` glossary token rendering — matches the manual note verbatim).
- **No auto-correct:** confirmed — no code flips `IsRequiredResource` to true when `IsPrimaryResource` is true. Input validation only counts primaries; it does not inspect the required flag, so primary+optional passes input validation and reaches persist.
- **Ordering:** availability (engine, step 3) runs **before** persist (step 5). Free slot → passes availability → persist error; busy slot → availability error first.
- **Response shape:** UNCERTAIN from code (top-level 400 vs `appointments[0].errors[0]`). The capture script handles BOTH; the live run pins it down.
- **vs doc/story:** the story expected "quietly fixed to required" (auto-correct) and the 262 claim was "skipped free/busy, could be double-booked." Current code does neither — it rejects. We characterize the rejection, which **refutes both** the auto-correct expectation and the double-book risk (the request never persists).

### Decision 2 — "is a shown slot a promise?" → only the cheap-check half is characterizable on 262
- **Field-match half NOT characterizable on 262** via these endpoints. The three field-match rules (`MatchFields`, `MatchBoolean`, `ExtendedMatch`) are **OnField-only** (`RuleObjectiveMapper:139-143`), evaluated **inside the external ESO optimizer** (a black box to Core). The OnField path is a 262 **stub** (`OnFieldGetSlotsHandler.mapEsoResponseToUnifiedResponse` returns null) and **not wired** in `StrategyResolver` (all `OnSite` routes to InBusiness). The live **InBusiness** path **shares read==write** checks exactly (`InBusinessScheduleHandler` re-runs the same `getSlots` calc), so it would prove read==write, not the defer. The readable Core code shows **no defer** — exactly the open question the doc flags ("reasonable assumption, not a verified fact").
- **Cheap-check half IS characterizable and is the verified contract.** The cheap checks (skill/territory/free-busy/location/excluded) are shared by read and write, so a shown slot is a **promise** on them. The test characterizes this with the availability cheap-check: read offers a slot for a clean resource+window ⟺ write into that exact window succeeds; and for a window where the resource is unavailable, read offers 0 slots ⟺ write is rejected.
- **Design consequence:** the Decision-2 test asserts the **verified cheap-check promise** (read⟺write agreement on availability) and is the durable test-home for the finding that the field-match "shown-but-rejected" half is **not** observable on 262 (recorded in javadoc + decision log with citations).

## Scope

Four new test methods, all reusing the **existing** repo `required-non-required` fixture and `availability-op-hours-policy` (both lifted/conformed in the predecessor session — no NEW source fixture/policy lift is required). New work is per-scenario **act folders** (booking requests) modeled on the existing repo acts, plus Kick constants and the test methods.

| Decision | Test method | Class | Acts (new folders) | Asserts (to be live-confirmed) |
|---|---|---|---|---|
| 4 | `testTwoPrimaryResourcesRejectedE2E` | `WfsWritePathParityE2ETest` | `schedule-two-primary` | HTTP 400, top-level `INVALID_INPUT`, message contains "primary resource"; input-validation (no booking) |
| 4z | `testRescheduleNoPrimaryE2E` | `WfsWritePathParityE2ETest` | `schedule-two-resource-clean`, `reschedule-delete-primary-with-flag`, `reschedule-delete-primary-no-flag` | Arm A 400 `INVALID_INPUT` (InvalidPropertyForDelete); Arm B Success with no-primary crew |
| 5 | `testPrimaryNotRequiredRejectedE2E` | `WfsWritePathParityE2ETest` | `schedule-primary-not-required`, `schedule-primary-required-control` | probe rejected (`INVALID_FIELD` / primary-must-be-required msg), NOT Success/double-book; control Success |
| 2 | `testCheapCheckReadWritePromiseE2E` | `WfsReadPathParityE2ETest` | `get-slots-parity-available`, `get-slots-parity-unavailable`, `schedule-parity-unavailable`, `schedule-parity-available` | read avail≥1 ⟺ write Success; read unavail=0 ⟺ write rejected |

### Structure decisions
- **One `revUp` per test**, each starting with `AUTH_CONFIG` (fresh manager persona → fresh `ServiceResource(RelatedRecordId,ResourceType)` → no cross-test SR-uniqueness collision). Matches the established Approach A.
- **Act ordering within a revUp** chosen so non-persisting acts (validation rejects) precede persisting acts on the same fixture: 4z `A(no-mutate)→B(mutate)`; 5 `probe(no-persist)→control(persist)`; 2 `reads→write-unavailable(no-persist)→write-available(persist)`.
- **4z reschedule chaining:** the clean 2-resource schedule act captures `serviceAppointment.serviceAppointmentId` (per the openapi `AppointmentOutputRepresentation.serviceAppointment.serviceAppointmentId`) into the env; the two reschedule acts consume it. `DeleteOperation` entries send only `{operation, assignedResource:{serviceResourceId}}` (Arm B) or additionally `isPrimaryResource:true` (Arm A).
- **Scenario 2 placement** in `WfsReadPathParityE2ETest`: its novel assertion is the read "shown" half (matches that class's read-path contract); the two write acts complete the parity. The class javadoc is updated to note it now also holds the read↔write cheap-check parity characterization.
- **Capture scripts** mirror the existing acts: `x-revoman-ledger: "off"` on every act under assertion; `ignoreHTTPStatusUnsuccessful: "true"` on acts expected to be non-2xx; capture both top-level-array `errorCode`/`message` and `appointments[0].errors[0]` shapes; stash `schedulingStatus` + counts into the env for Truth assertions read from `CollectionsKt.last(rundown).mutableEnv`.
- **API versions:** REST on the dynamic `versionPath` (v67 on the org); SOAP persona login stays v64 — per the existing suite and the user's directive ("64 for SOAP, latest-api-version for the rest").

### Out of scope / deliberately not done
- **No Core changes.** Bugs found are characterized as-is and handed off (`~/work/handoff`), not fixed.
- **No field-match (OnField/ESO) test for Decision 2** — not characterizable on 262 (see investigation). Recorded, not attempted. If/when OnField is wired, it's a separate effort and still ESO-internal.
- **No new source-collection lift** — the `required-non-required` fixture + `availability-op-hours-policy` already in the repo cover all four scenarios. New booking acts are authored fresh (the source repo has no two-primary / primary-optional / real-reschedule analog).

## Risks & mitigations
- **Org-state drift** (the predecessor log noted perm-set removals dropping `Account.OperatingHoursId` access, which `GetAvailableResources` needs). Decision-2 read acts use **GetAppointmentSlots** (not GetAvailableResources), which the Decision-9 acts already exercise cleanly, sidestepping that specific field-access drift. If the org is unavailable/drifted at run time, encode the investigation's expected verdict and mark the test for re-verification (documented), per predecessor practice.
- **Decision 5 response shape unknown** → capture script handles both shapes; the live run pins it and the javadoc records the observed shape.
- **4z Arm B leaving a single non-primary resource** — if the resulting single-resource crew triggers an unforeseen rule, fall back to **delete-ALL** (the func-test-proven path) to demonstrate "reschedule to no-primary (empty crew) succeeds," still refuting the doc. Decision made live.

## Verification
- Each act run live via the existing integrationTest harness (`gradle integrationTest --tests …`, external-org creds from `~/.revoman/config.yaml`); observed verdicts encoded.
- Holistic review (best review agent) of **both** the code AND each scenario from all angles (does the fixture isolate the rule under test? does the assertion actually prove the claim? are the 262/264 contrasts right? is the response-shape capture correct?).
- Decision log appended to `~/work/impl-decisions`; out-of-scope bugs (4z doc-contradiction is a doc bug, not a code bug; any new crashes) handed off per `~/work/handoff/CLAUDE.md`.
