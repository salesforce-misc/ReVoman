# Occupancy Parity — Root-Cause Confirmation — Design

**Date:** 2026-07-06
**Branch:** wfs/scheduler-vs-unified-1x-parity
**Status:** Approved (autonomous mode), ready for implementation plan
**Suite:** `SchedulerVsUnifiedParityE2ETest` (ReVoman integration tests, live orgs)
**Supersedes:** the "confirm parity once `CalendarEvents` configured" follow-up in the
handoff `~/work/handoff/2026-07-06-scheduler-parity-occupancy.md`.
**Decision log:** `~/work/impl-decisions/2026-07-06-occupancy-parity-premise-break-reframe.md`

## Background — what the prior test established

`testPriorAssignmentOccupancyParity_E2E` pinned a total divergence on the prior-assignment
occupancy scenario: OLD Salesforce Scheduler **REFUSED** all four 2×2 cells (an existing
overlapping assignment on worker B blocks a second booking), while Unified **BOOKED** all
four (it double-books B regardless of the required/optional flag). The test PINS these
observed outcomes; it does not assert `old == unified`.

The handoff hypothesised this was a **policy-config** difference: Unified's occupancy check
was said to be gated by the per-policy `CalendarEvents` rule (`ShouldConsiderCalendarEvents`),
absent from our test policy. Proposed fix: add that rule and expect Unified → REFUSED.

## Why the handoff premise is FALSE (verified against local Core `p4/260-patch`)

Three independent reasons, each verified by direct reads of
`/opt/workspace/core-public/core` (not by research agents — see the decision log's
research-integrity note; two agents hallucinated after their MCP dropped):

1. **`CalendarEvents` was removed in 264.** No such rule to add on the target release.
2. **Even on 262 it never gated ServiceAppointment occupancy.**
   `UnavailabilityService.getResourceUnavailability` calls `loadResourceUnavailability`
   (existing ServiceAppointments + ResourceAbsences) **unconditionally**; the
   `shouldConsiderCalendarEvents` branch only adds Salesforce **`Event`** sObject records.
3. **Unified's `schedule` action never computes occupancy at all.** The fixture books via
   `POST /connect/unified-scheduling/actions/schedule` → `ScheduleProcessor` (constructed
   with `PersistService`; `persistRecords()==true`) → maps caller `assignedResources` →
   persists. `getResourceUnavailability` has exactly one production caller —
   `InBusinessGetCandidatesSlotsDataService`, used only by the **get-slots / get-candidates
   READ path** (`GetCandidatesProcessor.persistRecords()==false`).

**Conclusion:** the divergence is not config-gated on the Unified side. It is a **real
write-path API-contract difference**: Unified's `schedule` action trusts caller-supplied
resources and does not re-check occupancy; OLD's booking API validates availability before
persisting. No Unified policy change can flip our scenario.

On the OLD side the premise IS sound (verified): `ServiceAppointmentResource.post` →
`ServiceAppointmentServiceImpl.create` → `areResourcesAvailable` →
`schedulingService.getAppointmentSlots` → reads
`orgHasOverbooking = AppointmentBooking.orgHasAppointmentBooking && OrgPreferences.Overbooking`
(default OFF). OFF ⇒ an occupied resource yields no slot ⇒ `resourcesNotAvailable` ⇒ REFUSED.
ON ⇒ concurrency allowed ⇒ BOOKED.

## Goal

Replace the dead "add CalendarEvents rule" confirmation with tests that pin the **true**
root cause on both engines:

1. **Unified read-vs-write probe** — demonstrate that Unified *does* know B is occupied on
   its READ surface, but its WRITE surface books B anyway. Concretely, after seeding appt #1
   on B: call `get-appointment-candidates` (or `get-appointment-slots`) for appt #2's
   overlapping window naming B as required → **B is EXCLUDED** (read path runs
   `UnavailabilityService`). Then `schedule` appt #2 with B → **BOOKED** (write path trusts
   the caller). This pins the divergence at the read/write boundary, not at a policy rule.

2. **OLD Overbooking flip** — flip `OrgPreferences.Overbooking` ON on the OLD scheduler org,
   re-run the occupancy cells, and expect OLD to **converge to BOOKED** (double-book),
   symmetrically confirming that OLD's refusal is the Overbooking pref, not a hard product
   invariant.

Together these confirm: *the OLD↔Unified occupancy divergence is (a) on the OLD side a
pref-gated refusal, and (b) on the Unified side a write-path-trusts-caller contract, provable
by the read surface already computing occupancy.* Not a config gap.

## Non-goals

- Not adding a `CalendarEvents` policy variant (removed in 264; irrelevant to SA occupancy).
- Not asserting `old == unified` on the write path (they genuinely differ by contract).
- Not changing any product code. These are characterization tests over live orgs.
- Not fixing the Unified write-path-no-occupancy behavior — that's a product question logged
  separately for the WFS team, not this test's job.

## Behavior model

Reuses the existing prior-assignment fixtures unchanged (A/B/C all available at 11:00, no
shift gap; B the only shared worker; A primary of appt #1, C dedicated free primary of appt
#2; both target 11:00–11:30). See the prior spec
`2026-07-06-prior-assignment-occupancy-parity-design.md` for the fixture rationale.

### Part 1 — Unified read-vs-write probe (new test method)

`testPriorAssignmentUnifiedReadVsWriteE2E` (Unified org only):

1. AUTH → op-hours policy → prior-assignment fixture.
2. Book appt #1 on B (required) via `schedule` → assert `schedulingStatus == Success`
   (B now has a committed overlapping ServiceAppointment). Non-vacuity guard as today.
3. **READ probe:** call `get-appointment-candidates` for appt #2's window (same 11:00–11:30),
   naming B as a required resource, under the same policy/fixture. Assert **B is NOT in the
   returned candidates** (occupancy IS computed on the read path). Capture the candidate list
   / count.
   - Control: a get-candidates call for the SAME window BEFORE appt #1 is booked (or for
     free worker C) returns B/ C respectively — so the exclusion is occupancy, not a dead
     read. (Use C as the always-present control to avoid an extra pre-booking revUp if
     simpler.)
4. **WRITE:** `schedule` appt #2 with C(primary)+B(required) via `schedule` → assert
   **BOOKED** (`schedulingStatus == Success`). Same as the existing occupancy test's Unified
   cell.
5. The test's claim: **read EXCLUDES B ∧ write BOOKS B** — the divergence lives at the
   read/write boundary. Assert both in one method so the contradiction is pinned atomically.

Read surface choice: `get-appointment-candidates` returns a resource-candidate list
(`data.result[].candidates[]`) — the cleanest "is B offered?" signal. `get-appointment-slots`
returns time-slots (occupancy collapses the slot) — usable as a fallback if candidates has an
input-validation quirk (note: the candidates action rejects `isPrimaryResource`; omit it, per
the existing get-candidates fixtures). Implementer picks whichever cleanly shows B's exclusion
live; document which and why.

### Part 2 — OLD Overbooking flip (new test method)

`testPriorAssignmentOldOverbookingFlipE2E` (OLD org only):

1. **Flip Overbooking ON** (mechanism below).
2. Run the OLD occupancy cell(s): AUTH → prior-assignment fixture → GRANT → book appt #1 (B)
   → book appt #2 (C primary + B). With Overbooking ON, expect appt #2 → **BOOKED**
   (converges with Unified's write behavior), vs the default-OFF **REFUSED** the existing
   test pins.
3. **Revert Overbooking to OFF** (post-cell), so every other OLD-side scenario keeps its
   default-OFF assumption.
4. Non-vacuity: appt #1 booked a real 08p SA (as today). The flip's effect IS the assertion:
   the same fixture that REFUSES with the pref OFF must BOOK with it ON.

Cover all four 2×2 cells (user chose "all 4 cells") — each cell that REFUSED at default-OFF
must now BOOK at pref-ON. (The 2×2 flag-independence is already pinned by the existing test;
here the 2×2 shows the pref flips every cell uniformly.)

### Overbooking flip mechanism (see decision log for full rationale)

- **Primary:** Metadata API `updateMetadata(IndustriesSettings{enableOverbookingOrgPref:true})`
  as a ReVoman http-request SOAP step to `/services/Soap/m/{v}` on the OLD org, reusing the
  SOAP `sessionId` from OLD auth. Goes through the server ⇒ proper org-pref cache invalidation
  ⇒ reads live, no restart (skill-documented for the sibling `enableWorkforceSchdMulResSchdPref`).
- **Fallback:** PLSQL via a ReVoman `PreStepHook` shelling to
  `/opt/workspace/sdb/sdbbuild/current262/bin/psql` (db `sdb262`, org `00Dxx00iDhF3QtA`),
  flipping the `preferencesN` bit for `Overbooking` index 427. Risk: server caches org prefs
  (`orgpreferencesp.g_pd_api_cache_org_pref`) → may need a server restart (sub-agent, core
  `app-server.sh stop && start`).
- **Decide empirically:** the cell's REFUSED→BOOKED assertion verifies the flip. Start with
  Metadata API; if OLD stays REFUSED, diagnose (also check the `AppointmentBooking` org-perm,
  since `orgHasOverbooking` is an AND) then fall back to PLSQL + restart.
- **Prerequisite to verify live:** OLD org must have `AppointmentBooking` org-perm for
  `orgHasOverbooking` to become true.

## Components & wiring

- New Kick config constants in `SchedulerParityConfig` (OLD side) and `ReVomanConfigForWfs`
  (Unified side, reuse existing) for: get-candidates read probe (Unified), Overbooking
  flip + revert steps (OLD).
- New fixtures under `pm-templates/v3/core/`:
  - Unified: `booking/get-candidates-prior-appt2-b-required` (read probe) — clone from
    `booking/get-candidates-skills-violating`, retarget to the prior-assignment fixture +
    op-hours policy + appt #2 window, capture whether B appears in candidates.
  - OLD: `booking/enable-overbooking` + `booking/disable-overbooking` (SOAP updateMetadata
    steps) — or a `HookConfig` in the test if a step-file SOAP body is awkward.
- New test methods in `SchedulerVsUnifiedParityE2ETest` (per above). Reuse the existing
  `oldOccupancyCell` / `unifiedOccupancyCell` helpers where possible; add a read-probe helper.
- `.resources/definition.yaml` collection markers in every new fixture dir (the prior session's
  FileNotFoundException lesson).

## Error handling / faithfulness

- All book/read steps keep `ignoreHTTPStatusUnsuccessful` so a legit refusal / empty read is
  captured, not counted as a step failure. Leading success guard
  (`firstUnIgnoredUnsuccessfulStepReport() == null`) still surfaces a rolled-back fixture.
- The Overbooking flip step must be asserted to have SUCCEEDED (updateMetadata returns a
  success envelope) before trusting the BOOKED outcome — else a silently-failed flip would
  masquerade as "OLD books" only because the flip no-op'd. Fail loud via Truth
  `assertWithMessage`, mirroring the existing `(a)` occupancy guard.
- Revert-to-OFF runs even if the cell assertion fails (finally-style ordering), to avoid
  leaking pref state into sibling OLD scenarios.

## Testing (this is ReVoman, NOT Core FTests)

- ReVomanTests via `ReVoman.revUp(...)`, mirroring the existing occupancy methods. Live run
  needs both `~/.revoman/config.yaml` (Unified org) and `~/.revoman/scheduler-config.yaml`
  (OLD org); `assumeBothOrgCreds()` skips when absent.
- Run only the new/changed methods via `gradle integrationTest --tests
  "...SchedulerVsUnifiedParityE2ETest.testPriorAssignmentUnifiedReadVsWriteE2E"` and the OLD
  flip method. (Use `gradle`, never `./gradlew`.)

## Risks / open questions

- **Metadata API reachable on the orgfarm org?** If `updateMetadata` is blocked, fall back to
  PLSQL+restart (heavier). Verified during impl.
- **`AppointmentBooking` org-perm** may itself be off on the OLD org → flip alone insufficient.
  Diagnose live; may need a second perm grant (or accept OLD-side as characterized-only if the
  perm can't be granted over the wire, logging the blocker to handoff).
- **get-candidates input quirks** on the Unified surface (rejects `isPrimaryResource`); use the
  existing skills-violating fixture as the working template.
- **Read-probe control**: simplest is to assert free worker C IS a candidate in the same call
  where B is excluded (one call proves both "read works" and "B excluded"). Implementer
  confirms C appears.
