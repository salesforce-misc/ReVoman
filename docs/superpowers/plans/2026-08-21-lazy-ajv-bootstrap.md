# Lazy Ajv Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan.

**Goal:** Defer construction of the Postman sandbox's Ajv module graph until the first `jsonSchema` assertion, without changing assertion behavior or sandbox isolation.

**Architecture:** Keep the existing immutable retained boot `Source`, shared Graal `Engine`, and one kick-local `Context` per sandbox. Change only the generated Browserify bootstrap binding passed to `chai-postman`: replace its eager `require("ajv")` value with a constructor-compatible adapter whose first invocation resolves Ajv through the same context-local Browserify cache.

**Tech Stack:** Kotlin/Gradle, GraalJS, Browserify-generated Postman sandbox bootcode, Kotest/JUnit 5.

**Spec:** `/home/gopala.akshintala/code-clones/work/revoman-root/build/perf-hotspots/scripted-lifecycle-post-bootstrap-20260821/REPORT.md`

## Global Constraints

- Work only in this detached worktree at `9439dc416ca7676c1f501a93924d7d3900f33e16`.
- Do not commit, update the published feature branch, or alter the root worktree.
- Do not pool contexts, detect script text, change benchmarks or gates, modify Graal internals, or remove Postman APIs.
- If any npm package installation is needed, use `pnpm`; no package installation is expected for this plan.
- The generated bootcode transformation must fail closed if the exact upstream marker is absent or duplicated.

---

### Task 1: Lock the lazy-loading behavior with a failing test

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxResourcesTest.kt`

**Steps:**
1. Instrument the real embedded Browserify bootcode in-memory so the test records each executed module name.
2. Boot and initialize a real GraalJS sandbox and assert that module `ajv` has not executed.
3. Execute one valid `chai` `jsonSchema` assertion and assert that `ajv` executes exactly once.
4. Execute a second assertion and assert the execution count remains one, proving Browserify cache reuse.
5. Run the focused test and confirm it fails specifically because current boot eagerly executes `ajv`.

### Task 2: Apply the smallest generator and resource change

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/postman-sandbox/bootcode.js.gz`

**Steps:**
1. Add an exact-one generator rewrite from the eager Ajv binding to a constructor-compatible lazy adapter.
2. Reconstruct the resource from the already-verified local `postman-sandbox@6.7.0` source, first proving the existing request-json/scrubbing transforms reproduce the current decompressed resource exactly.
3. Apply the lazy transform, regenerate the gzip resource, and record its hashes.
4. Rerun the focused test and confirm boot deferral, first-use loading, and cache reuse all pass.

### Task 3: Preserve public behavior and isolation

**Files:**
- Modify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/PmSandboxScriptApiTest.kt`
- Verify: `src/test/kotlin/com/salesforce/revoman/internal/postman/sandbox/SandboxEngineSharingTest.kt`

**Steps:**
1. Add end-to-end assertions proving a matching JSON schema passes and a nonmatching schema fails through the public script API.
2. Run the focused API/resource/engine-sharing tests.
3. Run the full unit suite and formatting/static checks that do not start Docker.

### Task 4: Measure the candidate without making an unsupported claim

**Files:**
- Create: `build/perf-hotspots/lazy-ajv-20260821/` diagnostic artifacts and report.

**Steps:**
1. Verify the quiet-host conditions and record two process/load observations before each measurement group.
2. Run fresh candidate A/A calibration using the exact established JMH workloads and settings.
3. Compare unmodified `9439dc41` with the detached lazy-Ajv candidate using the same settings.
4. If JFR is collected, require two readable independent recordings per interpreted result and validate identity/event families.
5. Report results as local diagnostic evidence only (`ClaimEligible=false`), together with exact provenance, tests, risks, and rejected alternatives.
