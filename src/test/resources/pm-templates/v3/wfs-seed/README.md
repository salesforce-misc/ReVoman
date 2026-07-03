# wfs-seed — Workforce Scheduling base-seed runbook

Seeds Workforce Scheduling base data onto a Salesforce Core org over HTTPS, and verifies it.
Driven by `WfsSeedE2ETest` (`src/test/kotlin/com/salesforce/revoman/WfsSeedE2ETest.kt`), which
runs this V3 collection via `ReVoman.revUp`. A faithful HTTP port of the org-manager
post-processor `WorkforceSchedulingPostProcessor.runBaseSeed()`.

## What it creates

As the **manager** persona (admin only bootstraps users + the context sync):

| Folder | Records |
|--------|---------|
| `fixtures/operating-hours-territories` | 3 OperatingHours + Mon–Sun 09:00–17:00 TimeSlots, 3 ServiceTerritories, 5 WorkTypes, 10 ServiceTerritoryWorkTypes |
| `fixtures/accounts-locations` | 20 Accounts, 3 Locations + child Addresses, 20 AssociatedLocations |
| `fixtures/service-resources` | 1 ServiceResource (owned by the case-worker persona) + 1 Primary ServiceTerritoryMember |
| `fixtures/context-definition-sync` | Triggers the `UnifiedSchedule__stdctx` context-definition sync (skips if one exists) |

Run order = ascending folder `order:`: `auth` (100) → `fixtures` (1000: OH/territories 100 →
accounts 200 → resources 300 → context-sync 400) → `probes` (9000).

## Personas

Tokens are minted via **SOAP login** — the OSS ReVoman library has no in-JVM minting.

- **admin** (`{{adminToken}}`) — the test SOAP-logs-in as the config admin and injects the token.
  Used ONLY by `auth/` (create the persona users) and `context-definition-sync/` (org-level
  config, not accessible under the manager PSL) and the read-only `probes/`.
- **manager** (`{{managerToken}}`) — `auth/` creates the user, sets a password, SOAP-logs-in.
  Seeds all data.
- **case-worker** — created by `auth/` (with a password); owns the ServiceResource. Does not
  itself authenticate.

## Prerequisites on a NEW machine

1. **The org.** A Core org reachable over HTTPS (workspace/orgfarm), WFS-provisioned
   (`WorkforceSchedulingManager` / `WorkforceSchedulingResource` permission sets exist).

2. **Org creds** — resolved in this precedence:
   1. **`ws.environment.yaml`** (in this collection dir) — fill `baseUrl` / `username` / `password`.
      Used when all three are non-blank.
   2. **`~/.revoman/config.yaml`** — fallback when the env file's creds are blank:
      ```yaml
      externalOrg:
        enabled: true
      baseUrl: https://<org-host>:6101
      username: <admin-username>
      password: <admin-password>
      cleanup:
        skip: true
      ```
   Without creds in either, the test is `assumeTrue`-skipped (never fails CI on a machine with no org).

3. **SOAP login ENABLED on the org.** The token source for every persona. If disabled, minting
   fails; enable it (or provision an org that allows it). SOAP login is pinned to API `v64`
   in the collection (`v68` rejects SOAP login on some instances).

4. **Local SDB reachable (for the `Shift.Status` pre-hook).** `WfsShiftStatusSeeder` seeds the
   `Shift.Status` dyn-enum via JDBC. It **auto-derives** the postgres port / db / release from
   the running `postgres` process (`sdbbuild/<release>/bin/postgres -D <datadir> -p <port>`),
   so no config is needed — but the SDB must be a local postgres (trust auth, OS user). If SDB
   is not local, the hook **skips with a warning** (the test still runs; seed just lacks the
   Shift.Status values, which only matter for later booking, not the base seed).

5. **Build tooling.**
   - Use the system **`gradle`**, NOT `./gradlew` — the pinned wrapper distribution may be
     unreachable behind a proxy. (`gradle test --tests '...'`.)
   - If plugin/dependency resolution can't reach `plugins.gradle.org` / Maven Central directly,
     add a Gradle init script pointing at your org's mirror (see "Offline / mirrored builds").

6. **WFS PSL seats.** `WorkforceSchedulingPsl` defaults to a small seat count (e.g. 50).
   Repeated runs create persona users and can exhaust it → `LICENSE_LIMIT_EXCEEDED`. Bump the
   seat count in SDB or deactivate stale users (see "Troubleshooting").

## Run it

```bash
cd <revoman-root>
gradle test --tests 'com.salesforce.revoman.WfsSeedE2ETest' --console=plain
```

Green looks like:
```
Test seeds Workforce Scheduling base data and verifies record counts() PASSED
```

The test asserts every step returned HTTP 2xx, then asserts the count probes with `>=`
(the collection is create-only, so re-runs against the same org accumulate records; a pristine
org yields exactly the seed's set, a re-seeded org yields more).

## Offline / mirrored builds

If `plugins.gradle.org` / `services.gradle.org` are unreachable (common on locked-down boxes),
create `~/.gradle/init.d/<mirror>.gradle.kts` routing `pluginManagement` + dependency
resolution to your org's authenticated Maven mirror (which proxies both Maven Central and the
Gradle plugin portal). This is a machine-local file, never committed.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Test SKIPPED | No `~/.revoman/config.yaml` creds. Add them (prereq 2). |
| `INVALID_OPERATION: SOAP Login not available in API version` | SOAP login pinned version too new; the collection uses `v64`. Ensure SOAP login is enabled on the org (prereq 3). |
| `LICENSE_LIMIT_EXCEEDED` on create-manager | `WorkforceSchedulingPsl` seats exhausted. In local SDB: `UPDATE core.permission_set_license SET total_licenses=500 WHERE organization_id='<org15>' AND developer_name='WorkforceSchedulingPsl';` — or deactivate stale users. |
| `INVALID_FIELD: No such column 'Latitude' on Location` | FLS on Location/Address geo. Already handled — the collection omits Location/Address geolocation (manager lacks the `WS_Location_Field_Access` grant). |
| `DUPLICATE_VALUE: A service resource of this type already exists for this user` | Org enforces unique ServiceResource per `(user, ResourceType)`. The collection seeds exactly one resource for the case-worker persona. |
| `Shift.Status` hook skipped | No local postgres found, OR the seed function isn't in this release's SDB. Fine for the base seed; only needed if you later create a CONFIRMED Shift for booking. |
| `ERROR: schema "cpicklist" does not exist` (or similar) during the SDB hook | Old hardcoded-schema bug — fixed: the hook now resolves the `insert_default_dyn_enums_nc` schema at runtime from `pg_proc` and is non-fatal (logs + continues). Pull the latest. |
| `gradle` "plugin not found" / wrapper SSL error | Use system `gradle`, add the mirror init script (see above). |

## Prompt for a new machine

Paste this to the agent on a fresh machine (fill in the org):

> Seed Workforce Scheduling base data on this org and verify it by running `WfsSeedE2ETest` in
> the ReVoman repo (`salesforce-misc/ReVoman`).
> Org: baseUrl `https://<org-host>:6101`, admin `<username>` / `<password>`.
> Steps: (1) ensure `~/.revoman/config.yaml` has `externalOrg.enabled: true` + those creds
> (`cleanup.skip: true`); (2) confirm SOAP login is enabled on the org; (3) run
> `gradle test --tests 'com.salesforce.revoman.WfsSeedE2ETest'` (use system `gradle`, NOT
> `./gradlew`); (4) if the build can't reach the Gradle plugin portal, add a nexus-mirror init
> script under `~/.gradle/init.d/`; (5) if `LICENSE_LIMIT_EXCEEDED`, bump `WorkforceSchedulingPsl`
> seats in local SDB. See `src/test/resources/pm-templates/v3/wfs-seed/README.md` for the full
> runbook.
