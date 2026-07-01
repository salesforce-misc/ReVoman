# Design — WFS rules read==write parity ReVoman suite (Q1/Q2/Q3 proofs)

**Date:** 2026-06-30
**Author:** Claude (autonomous mode, workspace box)
**Branch:** `wfs/decision-1-9-revoman-tests` in `~/code-clones/work/revoman-root`
**Predecessor suite:** Decisions 1/1.4/1.5/2/3/4/4z/5/8/9 (`WfsWritePathParityE2ETest`, `WfsReadPathParityE2ETest`)
**Motivating questions (from the user):** Q1 — are any rules configurable at design time but backend-inert? Q2 — which rules run on both read and write APIs? Q3 — can we ASSERT (not assume) read==write for every rule, and surface any divergence?

## Goal

Prove — with live ReVoman tests, anchored by jdwp Core debugging where an API verdict is ambiguous — the read↔write rule-parity claims the code review derived, across **all rule-evaluating unified-scheduling APIs**. The suite is allowed (and expected) to **discover and record read≠write divergences**, not to manufacture parity. Its value is faithfulness.

## Scope — the rule-evaluating API surface (Core-verified)

Per two Core investigations + a direct read of `AvailableResourcesServiceImpl:322` (which resolved a contradiction between them):

- **READ rule-evaluating APIs (4):** `get-appointment-slots`, `get-appointment-candidates`, `get-available-slots`, `get-available-resources`. **All four reach the single engine entrypoint `InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots` with the full 7-rule `SchedulingPolicyInfo` set.** `get-available-slots` is a dispatcher to slots/candidates (`GetAvailableSlotServiceImpl:73-102`); `get-available-resources` calls the same `getCandidatesProcessor.process(...)` (`AvailableResourcesServiceImpl:322`) then only post-processes/truncates surviving resources (the Decision-8 cap — presentation, not a rule skip).
- **WRITE rule-evaluating APIs (2):** `schedule`, `reschedule`. Both re-invoke `loadSchedulableSlots` via `SlotAvailabilityChecker.isSlotAvailable` → `getSlots` → `InBusinessGetSlotsHandler`. Reschedule has a **no-op short-circuit**: `SlotAvailabilityChecker:174-176` returns `true` (available) when neither time nor required-resources change — write then does LESS than read.
- **Out of scope (non-rule):** cancel, get-work-types, get-fields-from-page-layout, get-scheduling-policy-details, org-settings, get-service-territories-by-location, get-group-appointments, scheduling-parameter-by-type, getSuggestedResources, getRelatedServiceResource, getEctDetails, validateServiceResource, save-scheduling-{policy,rule,objective}, waitlist-checkin. These are CRUD/lookup/metadata; they never touch the rule engine.

## The 7 rules in scope (Core `RuleObjectiveMapper:108-125`)

- **COMMON_RULE_TYPES (6):** MatchSkills, ExcludedResources, RequiredResources, Availability, ServiceAppointmentVisitingHours, WorkingLocations.
- **IN_BUSINESS_RULE_TYPES (1):** AppointmentStartTimeInterval.
- **ON_FIELD_RULE_TYPES (10):** MatchTerritory, MatchFields, MatchBoolean, ExtendedMatch, Count, MatchTime, MaximumTravelFromHome, MatchCrewSize, TimeSlotDesignatedWork, CapacityLimit — **explicitly OUT OF SCOPE per the user** (onField/inField excluded).

## Q1/Q2/Q3 — how the suite answers each

- **Q1 (inert rules):** within scope, code shows **all 7 have a live `SchedulingPolicyInfo` predicate + engine consumer** — none are design-time-only. This is proven as a *byproduct*: each rule that demonstrably fires on read AND write has backend logic. No separate inert-proof (the onField-inert bucket is dropped with onField). Q1 answer: "no inert rules among Common+InBusiness," established by the parity matrix.
- **Q2 (both paths):** the cross-API agreement test proves the 4 reads + 2 writes share one engine → the same rule set runs on all of them. The earlier-claimed "get-available-resources runs a subset" is **refuted** (it runs the full set; only truncates output).
- **Q3 (strict equality + divergence):** the parity matrix is a **differential agreement test** — it asserts read decision == write decision for both a violating and a control case per rule, and FAILS on divergence in either direction. Two genuine divergences are expected and recorded, not hidden: the reschedule no-op short-circuit (write<read) and the RequiredResources 262 NPE (write crashes where read prunes).

## Architecture — the parity-triple pattern

New class **`WfsRulesParityE2ETest`** (sibling to the two existing parity classes; same `kickFor(...)` harness, manager-persona `AUTH_CONFIG`, Approach-A fresh persona per revUp, ledger-off + ignore-HTTP-status on acts under assertion, dual-shape error capture, assert-262/264-in-javadoc).

Per rule R, one `@Test`, one chained revUp over one fixture, with a matched pair:
- **violating** (violates R on the required/primary resource): GetSlots → **0 slots**; Schedule → **rejected** (assert the rule's errorCode/message where one exists, catching "rejected for a different reason").
- **control** (satisfies R, else identical): GetSlots → **>0**; Schedule → **Success**.
- **Assertion:** read decision == write decision in BOTH rows. Read-0⟺write-reject and read->0⟺write-Success is the equality proof.

**Honesty guardrails (non-negotiable):**
1. Every **control must book Success + return >0 slots** — proves the fixture is otherwise valid, so the violating-case empty/reject is the RULE, not a dead fixture (the recurring "0 slots for the wrong reason" failure mode from prior sessions).
2. **RequiredResources** asserts the observed read-prune vs write-NPE divergence verbatim (the Decision-1.4 262 crash), NOT a faked clean parity. jdwp-confirmed.
3. Each test's javadoc cites the Core file:line it proves.

## Test inventory (~9 methods)

**A. Parity matrix — 7 rules (6 new + Availability referenced):**
| Rule | Fixture source | Notes |
|---|---|---|
| Availability | Decision 2 (`testCheapCheckReadWritePromiseE2E`) | Already proven — referenced in javadoc, not re-authored |
| ServiceAppointmentVisitingHours | lift source `visiting-hours-op-hours-policy` + `visiting-hours-account-oh` fixture + existing read/write acts | clean lift |
| WorkingLocations | lift `territory-membership-partial` (has read+write acts) | clean lift |
| MatchSkills | adapt `skills-non-required` fixture → violation on required+primary resource | flip helper→primary |
| ExcludedResources | adapt `excluded-non-required` → exclude the required+primary resource | flip helper→primary |
| RequiredResources | `required-non-required` fixture | **records read-prune vs write-NPE divergence** (jdwp-confirmed) |
| AppointmentStartTimeInterval | **net-new** fixture + policy (SchedulingRuleParameter interval) | highest fixture uncertainty; jdwp if 0-slots-for-wrong-reason |

**B. Cross-API agreement — 1 test:** one violating MatchSkills case run through **all 6 rule-evaluating APIs** (get-appointment-slots, get-appointment-candidates, get-available-slots, get-available-resources, schedule, reschedule); assert all agree (4 reads prune/return-consistently, schedule rejects). Empirically proves the shared-engine claim so the matrix generalizes to every read+write API. (get-available-resources returns the resource per its post-rule truncation semantics — assert consistent with the other reads' rule verdict, accounting for its output shape.)

**C. No-op reschedule short-circuit — 1 test:** schedule Success → reschedule changing neither time nor required-resources into a now-unavailable state → **Success** (write<read). **jdwp double-assert** on `SlotAvailabilityChecker:174-176` to confirm the short-circuit branch executed (API Success alone is ambiguous — could be a genuine re-eval that passed).

## jdwp double-assert protocol (per the user's steer)

Where a ReVoman API verdict cannot *confidently* prove the mechanism, attach jdwp to the running Core server and confirm the code path:
- **No-op reschedule (C):** breakpoint at `SlotAvailabilityChecker:174-176`; confirm `timesAreChanging==false && resourcesHaveChanged==false` → early `return true` (loadSchedulableSlots NOT re-invoked).
- **RequiredResources divergence:** confirm read prunes cleanly (candidate absent from slots) while write hits the NPE — breakpoint on the write persist/eval path vs the read prune, showing the asymmetry is real.
- **AppointmentStartTimeInterval (if the fixture yields 0 slots for an unclear reason):** breakpoint in `loadSchedulableSlots` / `InBusinessAppointmentSlotCalculator` (start-time-interval stepping ~`:1014-1088`) to confirm it's the interval rule pruning, not a fixture contract violation.
jdwp findings are recorded in the decision log alongside the ReVoman assertion; the test remains the durable proof, jdwp is the confidence anchor.

## Data flow / harness reuse

- Reuse in-repo `required-non-required` fixture + `availability-op-hours-policy` where the rule allows; lift `visiting-hours-*`, `territory-membership-partial` from source `~/work/revoman/unified-validation-262/postman/collections/`; adapt `skills-non-required`/`excluded-non-required` (flip the violation onto the required/primary resource); author net-new for AppointmentStartTimeInterval.
- **New collection/env files carry NO YAML `#` comments** (corrupts Postman import) — intent goes in the `description:` field or inside JS `scripts: code:` blocks (`//`). This is a hard rule for every authored `*.request.yaml` / `definition.yaml`.
- Live-org via `~/.revoman/config.yaml` (v67 REST, v64 SOAP). Approach A fresh persona per revUp. Seat-pressure mitigation: batch runs; reclaim stale `@revoman.org` seats.

## Risks

- **AppointmentStartTimeInterval net-new fixture** — highest uncertainty; may need jdwp to confirm slots generate. Mitigation: control-must-book guardrail + jdwp.
- **get-available-resources output shape** — returns resources (array-of-arrays), not slots; the cross-API test must compare the rule VERDICT (resource present/absent per rules) consistently, accounting for its post-rule truncation (Decision-8 cap) — set a limit above seeded count so the cap is a no-op.
- **Org drift / seat pressure** (recurring). Mitigation as prior sessions; if the org is unavailable, encode the code-derived expectation + jdwp evidence and mark for re-verify.
- **Reschedule requires an existing SA** — the no-op test chains off a successful schedule (captures `serviceAppointmentId`), per the Decision-4z pattern.

## Verification

Each test run live; jdwp-anchored where noted; holistic review (code + scenario faithfulness from all angles). Decision log appended to `~/work/impl-decisions` capturing the full code→test→jdwp mapping and the recorded divergences (reschedule short-circuit; RequiredResources crash). Any read≠write finding that is a product/Core concern → handoff per `~/work/handoff/CLAUDE.md`.
