/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.readExternalOrgConfig
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Seeds Workforce Scheduling base data onto the external org configured in `~/.revoman/config.yaml`,
 * then asserts the seed landed. A faithful HTTP port of the org-manager post-processor
 * `WorkforceSchedulingPostProcessor.runBaseSeed()` (git.soma:orgfarm/org-manager).
 *
 * Flow:
 * 1. Read `~/.revoman/config.yaml` (baseUrl/username/password); SKIP the test if absent (this test
 *    needs a real org — it does not spin up a mock server like the other E2E tests).
 * 2. SOAP-login as admin in-test to mint {{adminToken}} and read the 15-char org id (the OSS ReVoman
 *    library has no in-JVM minting — SOAP login is the token source; it is enabled on the org).
 * 3. Pre-hook: idempotently seed the Shift.Status dyn-enum in local SDB ([WfsShiftStatusSeeder]).
 * 4. Run the `pm-templates/v3/wfs-seed` V3 collection as ONE [ReVoman.revUp]: auth/ creates the
 *    manager + case-worker personas and SOAP-logins the manager for {{managerToken}}; fixtures/ seed
 *    all data as the manager persona; probes/ count the results as admin.
 * 5. Assert transport (every step 2xx) + the count probes (persona-correct seed actually landed).
 *
 * Personas: admin only creates the persona users (and reads the count probes); the manager persona
 * does all the data seeding; case-worker owns the 30 ServiceResources. Never seeds as admin.
 */
class WfsSeedE2ETest {

  private val collection = "pm-templates/v3/wfs-seed"
  private val soapLoginApiVersion = "64" // v68 rejects SOAP login on this org

  @Test
  fun `seeds Workforce Scheduling base data and verifies record counts`() {
    val cfg = readExternalOrgConfig()
    assumeTrue(
      cfg["baseUrl"] != null && cfg["username"] != null && cfg["password"] != null,
      "No external-org creds in ~/.revoman/config.yaml — skipping WFS seed (needs a real org).",
    )
    val baseUrl = (cfg["baseUrl"] as String).trimEnd('/')
    val username = cfg["username"] as String
    val password = cfg["password"] as String

    // 1. Admin SOAP login → adminToken + org15.
    val (adminToken, org15) = soapLogin(baseUrl, username, password)

    // 2. Pre-hook: seed Shift.Status dyn-enum in SDB (idempotent; skips if SDB not local).
    WfsShiftStatusSeeder.seed(org15)

    // 3. Run the whole collection as one revUp, admin token injected; auth/ mints the manager token.
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
    // own set, NOT `==`: this collection is create-only (unlike the idempotent org-manager script), so
    // re-running against the same org accumulates records (fresh timestamped OH/territory/worktype
    // names each run). A pristine org yields exactly these numbers; a re-seeded org yields more. The
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
  }

  /** SOAP-login; returns (sessionId, org15). Pinned to an API version the org allows for SOAP login. */
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
            .uri(java.net.URI.create("$baseUrl/services/Soap/u/$soapLoginApiVersion"))
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
          override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
          override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
          override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
      )
    val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
    return HttpClient.newBuilder().sslContext(ssl).build()
  }

  @Suppress("unused") private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
