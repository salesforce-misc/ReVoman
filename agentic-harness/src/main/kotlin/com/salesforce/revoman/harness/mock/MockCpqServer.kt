/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local, in-process mock of the three Revenue-Cloud CPQ endpoints the harness graphs execute
 * against — [/configure], [/price], [/quote]. Backed by an in-memory [db] map that stands in for
 * the org's records (Stage 3's tau-bench-style checks assert on this final state). Uses the JDK
 * [HttpServer], the same zero-dependency mock idiom ReVoman's own `ControlFlowE2ETest` uses.
 */
class MockCpqServer {
  val db: MutableMap<String, Any?> = LinkedHashMap()
  private val seq = AtomicInteger(0)
  private lateinit var server: HttpServer

  fun start(): Int {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/configure") { ex -> handleConfigure(ex) }
    server.createContext("/price") { ex -> handlePrice(ex) }
    server.createContext("/quote") { ex -> handleQuote(ex) }
    server.start()
    val port = server.address.port
    logger.log(System.Logger.Level.INFO, "MockCpqServer started on port {0}", port)
    return port
  }

  fun stop() = server.stop(0)

  // --- handlers
  // -----------------------------------------------

  private fun handleConfigure(ex: HttpExchange) {
    val body = ex.readBody()
    val productCode = body.field("productCode")
    if (productCode == null) {
      logger.log(System.Logger.Level.DEBUG, "/configure rejected: missing productCode")
      return ex.respond(400, """{"error":"missing productCode"}""")
    }
    val quantity = body.field("quantity") ?: "1"
    val configId = "cfg-${seq.incrementAndGet()}"
    db["config:$configId"] = "$productCode x$quantity"
    logger.log(System.Logger.Level.DEBUG, "/configure → {0} (product={1}, qty={2})", configId, productCode, quantity)
    ex.respond(200, """{"configId":"$configId"}""")
  }

  private fun handlePrice(ex: HttpExchange) {
    val body = ex.readBody()
    val configId = body.field("configId")
    if (configId == null || !db.containsKey("config:$configId")) {
      logger.log(System.Logger.Level.DEBUG, "/price rejected: unknown configId={0}", configId)
      return ex.respond(400, """{"error":"unknown configId"}""")
    }
    val priceId = "prc-${seq.incrementAndGet()}"
    val total = 100.0 // deterministic stub price
    db["price:$priceId"] = total
    logger.log(System.Logger.Level.DEBUG, "/price → {0} (config={1}, total={2})", priceId, configId, total)
    ex.respond(200, """{"priceId":"$priceId","total":$total}""")
  }

  private fun handleQuote(ex: HttpExchange) {
    val body = ex.readBody()
    val priceId = body.field("priceId")
    if (priceId == null || !db.containsKey("price:$priceId")) {
      logger.log(System.Logger.Level.DEBUG, "/quote rejected: unknown priceId={0}", priceId)
      return ex.respond(400, """{"error":"unknown priceId"}""")
    }
    val quoteId = "qot-${seq.incrementAndGet()}"
    db["quote:$quoteId"] = "DRAFT"
    logger.log(System.Logger.Level.DEBUG, "/quote → {0} (price={1}, status=DRAFT)", quoteId, priceId)
    ex.respond(200, """{"quoteId":"$quoteId","status":"DRAFT"}""")
  }

  // --- tiny JSON helpers (no dep; naive on purpose — bodies are flat +
  // trusted)
  // ------

  private fun HttpExchange.readBody(): String = requestBody.readBytes().decodeToString()

  /** Extracts a flat JSON string/number field value by key, or null. */
  private fun String.field(key: String): String? =
    Regex(""""$key"\s*:\s*"?([^",}\s]+)"?""").find(this)?.groupValues?.get(1)

  private fun HttpExchange.respond(status: Int, json: String) {
    val bytes = json.encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }
}

private val logger: System.Logger = System.getLogger("com.salesforce.revoman.harness.mock.MockCpqServer")
