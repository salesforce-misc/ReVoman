# Prior-Assignment Occupancy Parity — Design

**Date:** 2026-07-06
**Branch:** wfs/scheduler-vs-unified-1x-parity
**Status:** Approved, ready for implementation plan
**Suite:** `SchedulerVsUnifiedParityE2ETest` (ReVoman integration tests, live 262 org)

## Problem

The existing double-book scenario (`testHelperDoubleBookParity_1_5_E2E`) makes worker B
"busy" through a **working-hours gap**: B's operating hours and shift are set to
12:00–14:00, so B is unavailable at the 11:00 booking window. No real appointment is
involved.

This leaves an untested claim: **does an existing appointment assignment occupy the
worker, even when that worker joined the earlier appointment as an optional
(non-required) helper?** In Salesforce scheduling, `required` vs `non-required` decides
whether a worker is *checked* when joining an appointment — not whether their existing
assignments *count* against a future check. The suite never seeds a real prior
appointment and books over it, so the claim is unverified.

The test must be able to **confirm or refute** the claim, not assume it. A plausible
outcome is that a product only checks operating-hours/shift and ignores existing
appointments unless a "prevent overbooking" setting is on.

## Goal

Prove — through a parity test against both products — whether a prior appointment
assignment on worker B occupies B's time and blocks a later *required* booking on an
overlapping appointment, across all four combinations of how B was booked the first time
versus the second time.

## Behavior model

Setup that isolates the mechanism:

- Worker B is **genuinely available** at 11:00 (member OH + Shift both 10:00–14:00, same
  as A). This is the key difference from the double-book fixture — no shift gap.
- Worker A is a clean primary member, always available, covers the whole window.
- Both appointments target the **same overlapping window**, 11:00–11:30.
- Therefore the **only** thing that can block appointment #2 is B's assignment on
  appointment #1. No shift gap, territory, or skill trick is in play.

### The 2×2 truth table

B is the shared worker; appointment #2 overlaps appointment #1 (both 11:00–11:30).

| # | Appt #1 (B as) | Appt #2 (B as) | Expected | Proves |
|---|----------------|----------------|----------|--------|
| a | required       | required       | refused  | occupancy check works at all → **fail-loud guard** |
| b | optional       | required       | refused  | **THE claim: an optional booking still occupies** |
| c | optional       | optional       | booked   | optional #2 is never checked (mirrors today's double-book) |
| d | required       | optional       | booked   | optional #2 is never checked (symmetry) |

Reading the table:

- If **(a)** does not refuse, the org allows overbooking; occupancy is not enforced at
  all → the whole test **fails loud**, so nothing passes vacuously.
- Given (a) refuses, **(b)** is the money cell: identical appt #2 (required), only appt
  #1's flavor changed required→optional. If (b) still refuses, an optional booking
  creates real, enforced occupancy — claim proven. If (b) *books* while (a) refuses, that
  is the finding: optional assignments do not count.
- **(c)** and **(d)** confirm the check is skipped whenever appt #2 is optional.

### Parity

All four cells run against **both** Scheduler (old) and Unified (new). Each cell asserts
`oldOutcome == unifiedOutcome`. A divergence is a sheet-worthy finding and is asserted
verbatim (not forced to green), the same way the 1.4 / 3 crash cells are handled.

## Test mechanics

**Structure (Approach A — one parity test, four arms):** a single new method
`testPriorAssignmentOccupancyParity_E2E`, mirroring the multi-arm shape of
`testRescheduleNoPrimaryParity_4z_E2E`. Keeps the 2×2 together as one truth table and
matches the suite idiom.

**New fixture `fixtures/prior-assignment`:** a clone of the double-book graph with one
change — B is fully available at 11:00 (member OH B and Shift B set to 10:00–14:00, same
as A). Both A and B are primary `ServiceTerritoryMember`s covering the window. Fresh
timestamped users minted per run (the proven double-book pattern; avoids the
`(RelatedRecordId, ResourceType)` `DUPLICATE_VALUE` rollback). Emits `schedResourceAId`,
`schedResourceBId`, `schedWorkTypeId`, `schedTerritoryId`, `schedAccountId`.

**Per-cell flow (each a fresh revUp):**

```
AUTH → FIXTURE → GRANT-LS-ACCESS → book appt #1 (capture SA id) → book appt #2 → capture outcome
```

- Appt #1 seeds the prior assignment. Two seed variants by B's flag on appt #1:
  B-required-first, B-optional-first (A is primary+required in both).
- Appt #2 is a second `service-appointments` call on the same 11:00–11:30 window. Two
  variants by B's flag on appt #2: B-required, B-optional.
- The four cells wire {seed variant} × {appt-#2 variant}.

**New booking collections:** appt-#1 seed variants may reuse / adapt
`service-appointments-clean-two-resource`; appt-#2 variants are new collections
(old-side under `pm-templates/v3/core/scheduler/booking/`, plus matching WFS configs in
`ReVomanConfigForWfs`). Exact collection count to be finalized in the plan; the logical
combos are 2 seeds + 2 appt-#2 calls.

**Outcome capture:** reuse the existing `WriteOutcome` enum (BOOKED / REFUSED / CRASHED)
and the `oldWriteOutcome` / `unifiedWriteOutcome` normalizers. A BOOKED verdict requires a
real 18-char `08p` ServiceAppointment id (shape guard, as in double-book).

**Assertions per cell:**

- (a) both REFUSED — hard guard; comment: "if this books, the org allows overbooking and
  the whole test is meaningless."
- (b) both REFUSED — the claim.
- (c) both BOOKED (real SA id).
- (d) both BOOKED (real SA id).
- Each cell: `assertThat(oldOutcome).isEqualTo(unifiedOutcome)` — parity.
- Non-vacuity: appointment #1 must itself succeed in every cell (real 18-char `08p` SA
  id), else appt #2's refusal is a dead-fixture artifact rather than an occupancy block.

## Risks

1. **Org allows overbooking** → cell (a) books → fail-loud guard trips. The spec documents
   that the org may need a "prevent overlapping appointments" / absence-conflict rule
   enabled; (a) is the detector.
2. **Optional booking creates no `AssignedResource` on one product** → the optional-first
   seed has no row to conflict with → cell (b) books for the wrong reason. Mitigation:
   non-vacuity assert appt #1's optional booking returns a real SA id; optionally query
   and assert an `AssignedResource` row for B exists before firing appt #2.
3. **Occupancy is keyed on exact overlap** → appt #2's window must overlap appt #1's
   booked slot. Both are fixed at 11:00–11:30.
4. **Unified crashes** (HTTP 500, as in 1.4 / 3) → assert the actual value verbatim and
   record it as a finding rather than forcing a green.

## Sheet updates (after the test runs)

Only once the test is green (or has produced a documented finding):

- Add a row to the **multi-resource** comparison tab of the parity Sheet
  (`147N4ZEteXjxgKx34f9ZWoPJ5T_42l2li83x9Ejq03FQ`). Scenario name: "Prior booking blocks
  a later required one."
- Add a matching Setup / Fire / Check row to the **How each test works** tab, with the
  test-method GitHub hyperlink.

The Sheet reflects verified behavior, never the plan.

## Out of scope (YAGNI)

Not built here; noted as possible follow-ups:

- Adjacency / back-to-back non-overlapping windows.
- Cross-territory occupancy.
- Capacity > 1 resources (a resource bookable twice in one window).
- Absence-based (`ResourceAbsence`) busy instead of appointment-based.

## How to run

```bash
gradle integrationTest --tests "com.salesforce.revoman.integration.core.scheduler.SchedulerVsUnifiedParityE2ETest.testPriorAssignmentOccupancyParity_E2E"
```

Live against the provisioned 262 org. Fresh timestamped users per run, no cleanup. The
leading success guard (`firstUnIgnoredUnsuccessfulStepReport() == null`) surfaces a
rolled-back fixture loudly; book acts carry `ignoreHTTPStatusUnsuccessful` so legitimate
400s and empty reads do not trip it.
