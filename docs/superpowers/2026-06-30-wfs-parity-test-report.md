# Workforce Scheduling — Read/Write Parity Test Plan

**Suite:** `WfsWritePathParityE2ETest` + `WfsReadPathParityE2ETest`

**Last verified:** 30 Jun 2026, live against the 262 WFS workspace org (`orgfarm-4dbef90d6c…:6101`)

**Release under test:** 262 (each test records *today's* behavior; the 264 expectation is noted per scenario)

---

## Purpose

This suite books and reads back real appointments against a live Workforce Scheduling org and pins down exactly how the product behaves today. They are **characterization tests**: each one asserts what the product *actually* does on release 262 — including two genuine crashes we don't want to paper over — and the description of each scenario states what release 264 is expected to change. When 264 lands and changes a behavior, the matching test will fail, and that failure is the signal the team is waiting for.

The behaviors under test come from a set of product decisions about how a **non-required "helper" resource** is treated when scheduling, plus two read-path rules (a result cap and a sharing gate).

### Vocabulary

- **Required resource** — a worker the appointment genuinely depends on; checked against every rule.
- **Non-required "helper"** — a worker added alongside, flagged *not* required. The recurring question is *how much rule-checking a helper gets*.
- **The anchor (a.k.a. resourceA)** — in every write scenario, the clean **required + primary** worker that always passes, so the *only* variable is the helper.
- **The helper (a.k.a. resourceB)** — the non-required worker, set up to break exactly **one** rule, so the outcome is attributable to that rule alone.
- **Policy** — the rulebook the engine applies (availability, working territories, skills, …).
- **Fixture** — the test data (territory, operating hours, work type, resources, shifts, account) wired so only the rule under test can decide the outcome.
- **schedulingStatus** — the verdict on a booking: `Success` = booked; `ScheduleError` = rejected with a rule code.

---

## Scenario catalogue — what each test verifies, absolutely

Ten scenarios across six decisions. For each: the setup, the single rule it isolates, the **exact assertion**, and the **recorded 262 result**.

### Test `WfsWritePathParityE2ETest` (the write/booking path)

---

#### Decision 1 — a non-required helper is NOT fitness-checked (four scenarios)

> **The claim:** add a helper that breaks one scheduling rule, and on 262 the booking still succeeds — the helper escapes the check. Verified across four different rules. Each runs as its own independent booking (fresh login + fresh users) so the four can't collide on resource uniqueness.

**1a — Excluded resources.**
- *Setup:* anchor clean; helper is a primary territory member (so working-territories passes for it), but is on the account's **excluded list** via a `ResourcePreference(Excluded)`. Window sits inside both the operating hours and the confirmed shift, so availability can't be the blocker. Only the **ExcludedResources** rule can fire on the helper.
- *Asserts:* `excludedNonReqSchedulingStatus == "Success"`.
- *262 result:* **Success** — the excluded helper books anyway. *(264: expected `ScheduleError` / ExcludedResources.)*

**1b — Territory membership.**
- *Setup:* window straddles noon. Anchor's territory membership is open-ended (covers the whole window). Helper's membership is `[a month ago, noon]` — it **overlaps** the window (so working-territories passes) but does **not fully contain** it (noon < 12:30), so the **MatchTerritory** committed-containment check fails for the helper. Availability is not the blocker.
- *Asserts:* `territoryNonReqSchedulingStatus == "Success"`.
- *262 result:* **Success** — the helper's territory is never evaluated. *(264: `ScheduleError` / MatchTerritory.)*

**1c — Skills.**
- *Setup:* the work type requires a skill. Anchor holds that skill (a `ServiceResourceSkill`); helper does **not**. Helper otherwise passes territory, availability and territory-match, so **MatchSkills** is the only rule that can fire. Helper is kept **non-primary** deliberately — a primary resource would be lifted into the required set by the engine's primary-implies-required guard and mask the bug.
- *Asserts:* `skillsNonReqSchedulingStatus == "Success"`.
- *262 result:* **Success** — the skill-less helper books anyway. *(264: `ScheduleError` / MatchSkills.)*

**1d — Working locations (secondary).**
- *Setup:* anchor is a **primary** member; helper is a **secondary** member with its own confirmed shift (so availability passes), but the policy includes **primary only**, so the helper's secondary role is disabled. **WorkingLocations** is the only rule that can fire. This verdict is decided by the eligibility pass *before* slot-matching runs, so it's immune to slot mechanics.
- *Asserts:* `workingLocationsSecondaryNonReqSchedulingStatus == "Success"`.
- *262 result:* **Success** — the helper's location role is never evaluated. *(264: `ScheduleError` / WorkingLocations.)*

---

#### Decision 1.4 — a helper cannot satisfy a *required-resource demand* (two scenarios)

> **The claim:** the account itself requires worker B (via a `ResourcePreference(Required)`). If B is only added as a *helper*, it must not count as satisfying that demand. We expected a clean rejection; 262 instead crashes.

**1.4a — Violating (the demand is met only by a helper).**
- *Setup:* anchor is required+primary but is **not** on the account's required list; the helper **is** the sole required-listed resource, but is assigned non-required. Window is inside OH and the shift, so availability/territory aren't the blocker — the required-satisfaction rule is.
- *Asserts:* `requiredNonReqSatisfierErrorCode == "INTERNAL_SERVER_ERROR"` **and** `requiredNonReqSatisfierErrorMessage contains "serviceTerritoryMembers"`.
- *262 result:* **server CRASH** — a null-pointer on `serviceTerritoryMembers`, *not* the tidy RequiredResources rejection the product doc predicted. Asserted verbatim. *(264: expected a clean `ScheduleError` / RequiredResources, no crash — at which point this test flips and alerts.)*

**1.4b — Control (a genuine required satisfier).**
- *Setup:* same fixture, but the booking presents a real required worker.
- *Asserts:* `requiredSatisfierControlErrorCode != "RequiredResources"`.
- *262 result:* rejected on **availability** (`INVALID_INPUT`, "service resources are not available"), i.e. not a RequiredResources error — confirming the required path doesn't spuriously raise that code.

---

#### Decision 1.5 — a helper is NOT availability-checked, so it can double-book (two scenarios)

> **The claim:** if the helper is already busy at the requested time, that doesn't block the booking — helpers aren't availability-checked. The A/B flips a single flag over the *same* fixture.

**1.5a — Helper busy, non-required.**
- *Setup:* anchor free at the window; helper is a primary member but **busy** (its OH + shift are 12:00–14:00, the booking is 11:00–11:30). Helper assigned **non-required**.
- *Asserts:* `doubleBookNonRequiredSchedulingStatus == "Success"`.
- *262 result:* **Success** — the busy helper is double-booked, no availability check. *(264: expected to be blocked as not-available.)*

**1.5b — Same helper, flipped to required (control).**
- *Setup:* identical, but the busy worker is now `isRequiredResource=true`.
- *Asserts:* `doubleBookRequiredControlSchedulingStatus != "Success"`.
- *262 result:* **rejected** (`400 INVALID_INPUT`, not-available) — a *required* worker **is** availability-checked. The before/after pair is the cleanest demonstration of the helper-vs-required difference in the suite.

---

#### Decision 3 — a missing `isRequiredResource` flag (two scenarios)

> **The claim:** what happens when a resource is sent without the required-flag at all? 262 crashes. Paired with a control proving the *primary* flag's absence is harmless.

**3a — Missing flag.**
- *Setup:* a single assigned resource that **omits `isRequiredResource` entirely** (and `isPrimaryResource`), under a policy with no RequiredResources rule (so the account's required-pref can't confound the probe).
- *Asserts:* `missingRequiredFlagErrorCode == "INTERNAL_SERVER_ERROR"` **and** `missingRequiredFlagErrorMessage contains "Boolean.booleanValue()"`.
- *262 result:* **server CRASH** — a null-pointer reading the missing flag. Asserted verbatim. *(264: expected to treat a missing flag as not-required and handle it cleanly — test flips and alerts.)*

**3b — L142 control (single required, no primary).**
- *Setup:* a single resource `isRequiredResource=true` with **no** `isPrimaryResource`, on fresh resources. (`isPrimaryResource` is multi-resource plumbing, not the Decision-1 variable, so this must be a valid booking.)
- *Asserts:* `singleRequiredNoPrimaryStatus == "Success"`.
- *262 result:* **Success** — confirms the absence of the *primary* flag is harmless; only the *required* flag's omission breaks things.

---

### Test `WfsReadPathParityE2ETest` (the read path)

> Read-path contract: a rule violation or a cap returns an **empty** list, **HTTP 200**, **no error** — never a 4xx/5xx.

---

#### Decision 8 — the load-balancing result cap (two scenarios, one run)

> **The claim:** `resourceLimitApptDistribution` caps how many resources the "get available resources" read returns. The question: does a cap of **0** mean "return zero" or "no limit"? Both acts run over the same fixture under the default OnSite load-balancing policy (so the cap path executes).

**8a — Limit 0.**
- *Asserts:* `limitZeroResourceCount == 0` **and** `limitZeroErrorCode` is null.
- *262 result:* `availableResources: [[]]` — **empty**, HTTP 200, no error. The empty list is the cap (`Stream.limit(0)`), confirmed by also asserting there's no error code (so an error masquerading as "empty" can't pass).

**8b — Limit 50 (control).**
- *Asserts:* `limitPositiveResourceCount > 0` **and** `limitPositiveErrorCode` is null.
- *262 result:* resources returned. Proves 0 is a literal cap-of-0, not an "unlimited" sentinel. *(264, if product picks "no cap": 0 would return all eligible resources.)*

---

#### Decision 9 — the silent shift-sharing gate (two scenarios, one run)

> **The claim:** the shift-availability read respects the **caller's data sharing** (it runs in user mode), while the surrounding resource/territory reads run in full-access mode. So a caller who can't see a resource's shifts gets **no slots — silently, with no error**. This is the one decision whose result genuinely depends on *who is asking*, so it uses two real, distinct user sessions.

**9a — Manager (the shift owner) — control.**
- *Setup:* the manager owns the confirmed shift. GetSlots over a window (10:00–14:00, ≥ the 60-minute work type, inside the 08:00–16:00 shift).
- *Asserts:* `dec9ManagerSlotCount > 0`.
- *262 result:* **slots returned** — the owner's user-mode shift read sees its own shift. Proves the case-worker's empty result below is the sharing gate, not an empty fixture.

**9b — Case worker (no sharing on the shift) — probe.**
- *Setup:* identical request, run as a case-worker persona with **no sharing** on the manager's private shift rows.
- *Asserts:* `dec9CaseWorkerSlotCount == 0`.
- *262 result:* **zero slots, HTTP 200, no error** — the user-mode shift read returns an empty shift list, so no availability; the resource is still *admitted* (the full-access reads see it) but contributes no slots. *(264, option B: align the modes so the case-worker would also see slots.)*

---

## Recorded results at a glance

| # | Scenario | Assertion | 262 result | Status |
|---|---|---|---|---|
| 1a | Dec 1 excluded helper | status == Success | Success | ✅ |
| 1b | Dec 1 territory helper | status == Success | Success | ✅ |
| 1c | Dec 1 skills helper | status == Success | Success | ✅ |
| 1d | Dec 1 working-locations helper | status == Success | Success | ✅ |
| 1.4a | Dec 1.4 helper can't satisfy required | errorCode INTERNAL_SERVER_ERROR + serviceTerritoryMembers | server crash | ✅ |
| 1.4b | Dec 1.4 genuine required (control) | errorCode != RequiredResources | INVALID_INPUT (availability) | ✅ |
| 1.5a | Dec 1.5 busy helper, non-required | status == Success | Success | ✅ |
| 1.5b | Dec 1.5 busy worker, required (control) | status != Success | rejected (not-available) | ✅ |
| 3a | Dec 3 missing required flag | errorCode INTERNAL_SERVER_ERROR + Boolean.booleanValue() | server crash | ✅ |
| 3b | Dec 3 single required, no primary (control) | status == Success | Success | ✅ |
| 8a | Dec 8 limit 0 | count == 0, no error | empty list `[[]]` | ✅ |
| 8b | Dec 8 limit 50 (control) | count > 0, no error | resources returned | ✅ |
| 9a | Dec 9 manager (owner, control) | slots > 0 | slots returned | ✅ |
| 9b | Dec 9 case-worker (no sharing) | slots == 0 | 0 slots, 200, no error | ✅ |

The two **server crashes** (1.4a, 3a) are the intended 262 behavior — known product defects, pinned down so the suite alerts the moment 264 fixes them.

---

## Preconditions (why the tests are environment-gated)

These run against a **provisioned WFS workspace org**, not unattended CI. The org must have:

- Multi-resource scheduling preference (`WorkforceSchdMulResSchdPref`) + InBusinessScheduling enabled.
- The `Shift.Status` picklist seeded so `Confirmed` shifts validate.
- Each resource: a confirmed shift covering the window + a territory membership effective in the past.
- The 262 release contract honored in the data (`SchedulingMethod=OnSite` on timeslot/shift/booking; WorkType carries no SchedulingMethod/IsRegular; relative composite-graph URLs).
- The **Workforce Scheduling** permission sets (Manager + Resource) assigned to the users, since they gate the scheduling fields the read/write paths rely on.

The tests are currently **enabled** so they can be run manually against the workspace org.

---

## Coverage notes (gaps a careful author would close next)

The scenarios are well-isolated and the crash assertions are sharp. Honest gaps, for the record:

1. **Caller identity isn't asserted.** The booking runs as a least-privilege manager rather than an admin, and that was proven live — but no test *fails* if someone re-aliases manager back to admin; verdicts would look identical. A "the caller is the manager, not the admin" assertion would lock it in. (A fail-loud safeguard exists for a *broken* manager login, but not for deliberate re-aliasing.)
2. **Decision 8's cap only proves the extremes.** The fixture seeds two resources, so "limit 50" and "no limit" look identical — the test proves *0 returns zero* but not that the cap *trims* a longer list. A "limit 1 over a 2-resource pool → exactly 1" scenario would prove truncation; exercising the default cap (10) would round it out.
3. **Decision 9's "no error" half isn't asserted.** 9b correctly checks *zero slots*, but not that there was *no error* alongside — and "silent, no error" is the whole point of the decision. Capture and assert the case-worker error code is null.
4. **Decision 1's four scenarios are single-sided.** Each proves the helper *books*; none includes a paired "and the same violation *does* reject when the worker is required" control. If a fixture ever silently failed to set up the violation, the booking would succeed for the wrong reason and still look green. Decision 1.5 has exactly this kind of paired control; Decision 1 could borrow it (the skills scenario is the most exposed, since it relies on an *absent* skill).
5. **A couple of "not equal to X" assertions are loose** (1.4b, 1.5b). "Anything but Success/X" also passes for an empty/null response; asserting the *expected* rejection value would be tighter.

These are next-step suggestions, not defects in what exists today.
