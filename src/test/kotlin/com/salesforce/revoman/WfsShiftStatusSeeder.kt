/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.sql.DriverManager

/**
 * Seeds the `Shift.Status` dynamic-enum picklist (enum id 284) in the local SDB, so a fresh
 * Workforce Scheduling org can create the CONFIRMED Shift that slot-gen requires. WFS slot-gen needs
 * `Shift.Status` mapped to StatusCategory CONFIRMED (grouping_string '2'); on a fresh org the picklist
 * is empty and a Shift insert fails `FIELD_INTEGRITY_EXCEPTION`.
 *
 * This is the ONE piece the HTTP collection cannot do — dynamic-enum values live in SDB, not behind
 * any REST/Metadata API — so [WfsSeedE2ETest] runs it as a pre-hook via plain JDBC before the
 * collection.
 *
 * Release-agnostic by design: [discoverLocalSdb] derives the port + db name from the RUNNING
 * `postgres` process command line (`sdbbuild/<release>/bin/postgres -D <datadir> -p <port>`) rather
 * than hardcoding — port, db name, and release all change per box/build. If no local SDB process is
 * found (e.g. the org is genuinely remote), seeding is SKIPPED with a warning, not failed, so the
 * test still runs where SDB is not co-located.
 */
object WfsShiftStatusSeeder {
  private val logger = KotlinLogging.logger {}

  /** SDB connection coordinates derived from the running postgres process. */
  data class SdbCoords(
    val port: Int,
    val db: String,
    val release: String,
    val user: String,
    val password: String,
  )

  // SDB is version-scoped: a trust-auth connection as the OS process owner is pinned to a schema
  // VERSION (e.g. "26400") at which the app-managed `cpicklist` schema — the seed function's home — is
  // NOT visible, so the call fails `schema "cpicklist" does not exist at version "26400"`. The SDB
  // build superuser `saydb` runs UNVERSIONED and sees `cpicklist`, so we connect as that role (the
  // same one the reference `seed-dynenum.sh` uses). Overridable per box via env for release drift.
  private val SDB_USER = System.getenv("REVOMAN_SDB_USER") ?: "saydb"
  private val SDB_PASSWORD = System.getenv("REVOMAN_SDB_PASSWORD") ?: "sdb"

  private const val SHIFT_STATUS_ENUM_ID = "284"
  // Tentative->'0', Published->'1', Confirmed->'2' — ShiftStatusCategory dbValues; Confirmed is the
  // one slot-gen needs. defaultIdx 2 = Confirmed.
  private val VALUES = listOf("Tentative", "Published", "Confirmed")
  private val GROUPINGS = listOf("0", "1", "2")
  private const val DEFAULT_IDX = 2

  /**
   * Idempotently seed Shift.Status for [org15]. No-op if already seeded (Confirmed->'2' active).
   * BEST-EFFORT / NON-FATAL: any failure (no local SDB, function/schema absent for the release, SQL
   * error) is logged and swallowed — Shift.Status is only needed for later booking, not the base
   * seed, so it must never fail the seed run. Two release/box-drift guards: the seed-function's schema
   * is resolved at runtime from pg_proc (not hardcoded — it differs across releases, e.g. `cpicklist`),
   * and the connection is made as the UNVERSIONED SDB superuser [SDB_USER] rather than the trust-auth
   * OS owner, whose version-pinned connection can't see `cpicklist` (the `schema "cpicklist" does not
   * exist at version "…"` error).
   */
  fun seed(org15: String) {
    val coords = discoverLocalSdb()
    if (coords == null) {
      logger.warn {
        "No local SDB postgres process found — SKIPPING Shift.Status seed (non-fatal). If the org is " +
          "remote, seed Shift.Status out-of-band; if it's local, ensure the SDB postgres is running."
      }
      return
    }
    logger.info {
      "Seeding Shift.Status (enum $SHIFT_STATUS_ENUM_ID) for org $org15 via SDB " +
        "127.0.0.1:${coords.port}/${coords.db} (release ${coords.release}, user ${coords.user})"
    }
    val url = "jdbc:postgresql://127.0.0.1:${coords.port}/${coords.db}"
    try {
      DriverManager.getConnection(url, coords.user, coords.password).use { conn ->
        if (isAlreadySeeded(conn, org15)) {
          logger.info { "Shift.Status already seeded for $org15 (Confirmed->'2' active) — no-op" }
          return
        }
        val schema = resolveSeedFnSchema(conn)
        if (schema == null) {
          logger.warn {
            "Seed function `insert_default_dyn_enums_nc` not found in this SDB (release " +
              "${coords.release}) — SKIPPING Shift.Status seed (non-fatal). Seed it out-of-band if a " +
              "booking flow needs a CONFIRMED Shift."
          }
          return
        }
        conn.autoCommit = false
        // Platform seeder: inserts every value active, sort-ordered, with its category mapping, in one
        // txn — the same path CPicklist.insertDefaultDynEnumsNc uses. A bare UPDATE would miss on a
        // fresh org where the values don't exist yet. Schema is resolved (not hardcoded); the array
        // domains live in the stable `saydb` schema.
        conn.prepareStatement(
            "SELECT \"$schema\".insert_default_dyn_enums_nc(?, ?, ?::saydb.string_array, ?, ?::saydb.string_array)"
          )
          .use { ps ->
            ps.setString(1, org15)
            ps.setString(2, SHIFT_STATUS_ENUM_ID)
            ps.setArray(3, conn.createArrayOf("varchar", VALUES.toTypedArray()))
            ps.setInt(4, DEFAULT_IDX)
            ps.setArray(5, conn.createArrayOf("varchar", GROUPINGS.toTypedArray()))
            ps.execute()
          }
        conn.commit()
        logger.info { "Shift.Status seeded for $org15 via schema `$schema`: ${VALUES.zip(GROUPINGS)}" }
      }
    } catch (e: Exception) {
      logger.warn(e) {
        "Shift.Status seed FAILED (non-fatal) — continuing the base seed without it. Seed it " +
          "out-of-band if a booking flow needs a CONFIRMED Shift. Cause: ${e.message}"
      }
    }
  }

  /**
   * Resolve the schema that hosts `insert_default_dyn_enums_nc` in THIS SDB, or null if absent. The
   * schema is release-specific (do not hardcode it) — this queries pg_proc so it works on any release
   * that ships the function, and returns null (→ graceful skip) on one that doesn't.
   */
  private fun resolveSeedFnSchema(conn: java.sql.Connection): String? =
    conn
      .prepareStatement(
        "SELECT n.nspname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
          "WHERE p.proname = 'insert_default_dyn_enums_nc' LIMIT 1"
      )
      .use { ps -> ps.executeQuery().use { if (it.next()) it.getString(1) else null } }

  private fun isAlreadySeeded(conn: java.sql.Connection, org15: String): Boolean =
    conn
      .prepareStatement(
        "SELECT 1 FROM core.picklist_master WHERE organization_id = ? AND picklist_enum_or_id = ? " +
          "AND api_name = 'Confirmed' AND grouping_string = '2' AND is_active = 1 LIMIT 1"
      )
      .use { ps ->
        ps.setString(1, org15)
        ps.setString(2, SHIFT_STATUS_ENUM_ID)
        ps.executeQuery().use { it.next() }
      }

  /**
   * Derive local SDB coordinates from the running postgres process. Returns null when no such
   * process exists. Parses the process command line for the SDB postgres launched from
   * `.../sdbbuild/<release>/bin/postgres -D <datadir> -p <port>`; db name = basename(datadir).
   */
  fun discoverLocalSdb(): SdbCoords? {
    val line = runCatching { readSdbPostgresCmdLine() }.getOrNull() ?: return null
    val port = Regex("""-p\s+(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val dataDir = Regex("""-D\s+(\S+)""").find(line)?.groupValues?.get(1) ?: return null
    val db = File(dataDir).name
    val release = Regex("""sdbbuild/([^/]+)/bin/postgres""").find(line)?.groupValues?.get(1) ?: "unknown"
    // Connect as the UNVERSIONED SDB superuser (`saydb`), NOT the trust-auth OS process owner: the
    // latter is version-pinned and can't see the `cpicklist` schema the seed function lives in.
    return SdbCoords(port, db, release, SDB_USER, SDB_PASSWORD)
  }

  private fun readSdbPostgresCmdLine(): String? {
    val proc = ProcessBuilder("ps", "-eo", "args").redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    return out.lineSequence().firstOrNull {
      it.contains("sdbbuild/") && it.contains("/bin/postgres") && it.contains("-D ")
    }
  }
}
