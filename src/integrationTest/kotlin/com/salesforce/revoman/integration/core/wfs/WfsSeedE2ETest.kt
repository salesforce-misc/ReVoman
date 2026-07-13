/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.core.wfs

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.readExternalOrgConfig
import com.salesforce.revoman.internal.postman.template.v3.V3EnvLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Seeds Workforce Scheduling base data onto an external org, then asserts the seed landed. A
 * faithful HTTP port of the org-manager post-processor
 * `WorkforceSchedulingPostProcessor.runBaseSeed()` (git.soma:orgfarm/org-manager).
 *
 * Flow:
 * 1. Resolve org creds: `ws.environment.yaml` (alongside the collection) FIRST, else fall back to
 *    `~/.revoman/config.yaml`. SKIP the test if neither yields creds (this test needs a real org —
 *    it does not spin up a mock server like the other E2E tests).
 * 2. SOAP-login as admin in-test to mint {{adminToken}} and read the 15-char org id (the OSS
 *    ReVoman library has no in-JVM minting — SOAP login is the token source; it is enabled on the
 *    org).
 * 3. Pre-hook: idempotently seed the Shift.Status dyn-enum in local SDB ([WfsShiftStatusSeeder]).
 * 4. Run the `pm-templates/v3/core/wfs/wfs-seed` V3 collection as ONE
 *    [com.salesforce.revoman.ReVoman.revUp]: auth/ creates the manager + case-worker personas and
 *    SOAP-logins the manager for {{managerToken}}; fixtures/ seed all data as the manager persona;
 *    probes/ count the results as admin.
 * 5. Assert transport (every step 2xx) + the count probes (persona-correct seed actually landed).
 *
 * Personas: admin only creates the persona users (and reads the count probes); the manager persona
 * does all the data seeding; case-worker owns the 30 ServiceResources. Never seeds as admin.
 */
class WfsSeedE2ETest {

  private val logger = KotlinLogging.logger {}
  private val collection = "pm-templates/v3/core/wfs/wfs-seed"
  private val soapLoginApiVersion = "64" // v68 rejects SOAP login on this org

  @Test
  fun `seeds Workforce Scheduling base data and verifies record counts`() {
    // Creds precedence: ws.environment.yaml (alongside this collection) FIRST, then fall back to
    // ~/.revoman/config.yaml. The env file wins only when its baseUrl/username/password are all
    // non-blank; otherwise the dotfile supplies them.
    val cfg = resolveOrgCreds()
    assumeTrue(
      cfg != null,
      "No org creds — set baseUrl/username/password in $collection/ws.environment.yaml OR " +
        "~/.revoman/config.yaml — skipping WFS seed (needs a real org).",
    )
    val (baseUrl, username, password) = cfg!!

    // 1. Admin SOAP login → adminToken + org15.
    val (adminToken, org15) = soapLogin(baseUrl, username, password)

    // 2. Pre-hook: seed Shift.Status dyn-enum in SDB (idempotent; skips if SDB not local).
    WfsShiftStatusSeeder.seed(org15)

    // 3. Run the whole collection as one revUp, admin token injected; auth/ mints the manager
    // token.
    val seedPersonaPassword = "Revoman-${org15}-1!"
    val rundown =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(collection)
          .dynamicEnvironment("baseUrl", "$baseUrl/")
          .dynamicEnvironment("adminToken", adminToken)
          .dynamicEnvironment("seedPersonaPassword", seedPersonaPassword)
          .dynamicEnvironment(
            "unifiedPermSets",
            "'WorkforceSchedulingManager', 'WorkforceSchedulingResource'",
          )
          .insecureHttp(true)
          .haltOnAnyFailure(true)
          .off()
      )

    // 4a. Transport: every non-ignored step returned 2xx.
    assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport).isNull()

    // 4b. Business outcome: the count probes prove the seed actually landed. Assert `>=` the seed's
    // own set, NOT `==`: this collection is create-only (unlike the idempotent org-manager script),
    // so
    // re-running against the same org accumulates records (fresh timestamped OH/territory/worktype
    // names each run). A pristine org yields exactly these numbers; a re-seeded org yields more.
    // The
    // `>=` contract holds in both cases and still fails loudly if a whole object type didn't seed.
    fun count(key: String): Int =
      rundown.mutableEnv.getAsString(key)?.toIntOrNull()
        ?: error("Probe env var '$key' missing/non-numeric — probe step did not run or capture")

    assertThat(count("wfsSeedAccountCount")).isAtLeast(20)
    assertThat(count("wfsSeedOperatingHoursCount")).isAtLeast(3)
    assertThat(count("wfsSeedTerritoryCount")).isAtLeast(3)
    assertThat(count("wfsSeedWorkTypeCount")).isAtLeast(5)
    assertThat(count("wfsSeedLocationCount")).isAtLeast(3)
    // One ServiceResource + one ServiceTerritoryMember per run (persona-model: single case-worker
    // owns one resource — see the service-resources folder for the (user, type) uniqueness note).
    assertThat(count("wfsSeedServiceResourceCount")).isAtLeast(1)
    assertThat(count("wfsSeedTerritoryMemberCount")).isAtLeast(1)

    // 5. Summary of what now exists on the org (counts are org-wide totals matching the WS name
    // filters, so on a re-seeded org they include prior runs — see the `>=` note above).
    fun env(key: String) = rundown.mutableEnv.getAsString(key) ?: "?"
    logger.info {
      """
      |
      |========== Workforce Scheduling seed summary ==========
      |  Org:            $org15  @ $baseUrl
      |  Personas created this run:
      |    manager     : ${env("managerUserName")}  (userId ${env("caseManagerUserId")})
      |    case-worker : ${env("caseWorkerUserName")}  (userId ${env("caseWorkerUserId")})
      |  Data on org (WS-* totals):
      |    Accounts               : ${count("wfsSeedAccountCount")}
      |    OperatingHours         : ${count("wfsSeedOperatingHoursCount")}
      |    ServiceTerritories     : ${count("wfsSeedTerritoryCount")}
      |    WorkTypes              : ${count("wfsSeedWorkTypeCount")}
      |    Locations              : ${count("wfsSeedLocationCount")}
      |    ServiceResources       : ${count("wfsSeedServiceResourceCount")}
      |    ServiceTerritoryMembers: ${count("wfsSeedTerritoryMemberCount")}
      |=======================================================
      """
        .trimMargin()
    }
  }

  /** (baseUrl, username, password). */
  private data class OrgCreds(val baseUrl: String, val username: String, val password: String)

  /**
   * Resolve org creds: try `ws.environment.yaml` (a `values:` env file on the classpath, alongside
   * the collection) FIRST, then fall back to `~/.revoman/config.yaml` (flat `key: value`). Returns
   * null when neither yields all three non-blank fields (→ the test is assumeTrue-skipped).
   */
  private fun resolveOrgCreds(): OrgCreds? {
    fun fromMap(m: Map<String, Any?>): OrgCreds? {
      val b = (m["baseUrl"] as? String)?.trim().orEmpty().trimEnd('/')
      val u = (m["username"] as? String)?.trim().orEmpty()
      val p = (m["password"] as? String)?.trim().orEmpty()
      return if (b.isNotEmpty() && u.isNotEmpty() && p.isNotEmpty()) OrgCreds(b, u, p) else null
    }
    val fromEnvFile =
      runCatching { fromMap(V3EnvLoader.loadFromPath("$collection/ws.environment.yaml")) }
        .getOrNull()
    return fromEnvFile ?: fromMap(readExternalOrgConfig())
  }

  /**
   * SOAP-login; returns (sessionId, org15). Pinned to an API version the org allows for SOAP login.
   */
  private fun soapLogin(baseUrl: String, username: String, password: String): Pair<String, String> {
    val envelope =
      """
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:urn="urn:partner.soap.sforce.com">
        <soapenv:Body><urn:login><urn:username>$username</urn:username><urn:password>$password</urn:password></urn:login></soapenv:Body>
      </soapenv:Envelope>
      """
        .trimIndent()
    val resp =
      insecureHttpClient()
        .send(
          HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/services/Soap/u/$soapLoginApiVersion"))
            .header("Content-Type", "text/xml; charset=UTF-8")
            .header("SOAPAction", "login")
            .POST(HttpRequest.BodyPublishers.ofString(envelope))
            .build(),
          HttpResponse.BodyHandlers.ofString(),
        )
    val body = resp.body()
    val sessionId =
      Regex("<sessionId>(.*?)</sessionId>").find(body)?.groupValues?.get(1)
        ?: error("Admin SOAP login failed: ${body.take(400)}")
    val orgId =
      Regex("<organizationId>(.*?)</organizationId>").find(body)?.groupValues?.get(1)
        ?: error("Admin SOAP login response missing organizationId")
    return sessionId to orgId.take(15)
  }

  private fun insecureHttpClient(): HttpClient {
    val trustAll =
      arrayOf<TrustManager>(
        object : X509TrustManager {
          override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}

          override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}

          override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
      )
    val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    return HttpClient.newBuilder().sslContext(ssl).build()
  }

  @Suppress("unused") private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
