/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.testsupport

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.exe.prepareHttpClient
import com.salesforce.revoman.testing.http.MockHttpServer
import com.salesforce.revoman.testing.http.RecordedNameValue
import com.squareup.moshi.Moshi
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.http4k.core.Method.GET
import org.http4k.core.Method.PATCH
import org.http4k.core.Method.POST
import org.http4k.core.Method.PUT
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test

class DeterministicMockApiTest {
  @Test
  fun `empty object list is served through the public mock server`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}/objects"))

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString()).isEqualTo("[]")
      assertThat(server.requests().single().method).isEqualTo(GET)
      assertThat(server.requests().single().path).isEqualTo("/objects")
    }
  }

  @Test
  fun `post objects creates a fixture-local object`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(POST, "${server.baseUrl}/objects")
            .body("""{"id":"client-id","name":"first object","data":{"color":"blue"}}""")
        )

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"first object","data":{"color":"blue"}}""")
      assertThat(server.requests().single().method).isEqualTo(POST)
      assertThat(server.requests().single().path).isEqualTo("/objects")
    }
  }

  @Test
  fun `concurrent object posts allocate every deterministic identifier exactly once`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val executor = Executors.newVirtualThreadPerTaskExecutor()
      try {
        val client = prepareHttpClient(insecureHttp = false)
        val responses =
          (1..20)
            .map { number ->
              executor.submit(
                Callable {
                  client(
                    Request(POST, "${server.baseUrl}/objects").body("""{"name":"object-$number"}""")
                  )
                }
              )
            }
            .map { future -> future.get(5, TimeUnit.SECONDS) }

        val objectIds = responses.map { response ->
          assertThat(response.status).isEqualTo(OK)
          Regex("""\"id\":\"(local-object-\d+)\"""")
            .find(response.bodyString())
            ?.groupValues
            ?.get(1) ?: error("missing deterministic object id in ${response.bodyString()}")
        }

        assertThat(objectIds).containsExactlyElementsIn((1..20).map { "local-object-$it" })
        val objects = client(Request(GET, "${server.baseUrl}/objects"))
        val entries =
          Moshi.Builder().build().adapter(List::class.java).fromJson(objects.bodyString())
        assertThat(entries).hasSize(20)
      } finally {
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) {
          "concurrent deterministic mock API test workers did not stop"
        }
      }
    }
  }

  @Test
  fun `list objects returns an id-sorted snapshot after create and update`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)
      client(Request(POST, "${server.baseUrl}/objects").body("""{"name":"first object"}"""))
      client(Request(POST, "${server.baseUrl}/objects").body("""{"name":"second object"}"""))

      val created = client(Request(GET, "${server.baseUrl}/objects"))

      assertThat(created.status).isEqualTo(OK)
      assertThat(created.bodyString())
        .isEqualTo(
          """[{"id":"local-object-1","name":"first object"},{"id":"local-object-2","name":"second object"}]"""
        )

      client(
        Request(PATCH, "${server.baseUrl}/objects/local-object-1")
          .body("""{"name":"updated object"}""")
      )
      val updated = client(Request(GET, "${server.baseUrl}/objects"))

      assertThat(updated.status).isEqualTo(OK)
      assertThat(updated.bodyString())
        .isEqualTo(
          """[{"id":"local-object-1","name":"updated object"},{"id":"local-object-2","name":"second object"}]"""
        )
    }
  }

  @Test
  fun `patch objects updates supplied fields and preserves omitted data`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${server.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response =
        client(
          Request(PATCH, "${server.baseUrl}/objects/local-object-1")
            .body("""{"name":"renamed object"}""")
        )

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"renamed object","data":{"color":"blue"}}""")
    }
  }

  @Test
  fun `get objects returns the exact stored object`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${server.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response = client(Request(GET, "${server.baseUrl}/objects/local-object-1"))

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"first object","data":{"color":"blue"}}""")
    }
  }

  @Test
  fun `put objects replaces the stored object data`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${server.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response =
        client(
          Request(PUT, "${server.baseUrl}/objects/local-object-1")
            .body("""{"name":"replacement object","data":{"color":"green"}}""")
        )

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo(
          """{"id":"local-object-1","name":"replacement object","data":{"color":"green"}}"""
        )
    }
  }

  @Test
  fun `patch missing object returns the deterministic not-found response`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(PATCH, "${server.baseUrl}/objects/missing").body("""{"name":"unused"}""")
        )

      assertThat(response.status).isEqualTo(NOT_FOUND)
      assertThat(response.bodyString()).isEqualTo("""{"error":"object not found"}""")
    }
  }

  @Test
  fun `get missing object returns the deterministic not-found response`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}/objects/missing"))

      assertThat(response.status).isEqualTo(NOT_FOUND)
      assertThat(response.bodyString()).isEqualTo("""{"error":"object not found"}""")
    }
  }

  @Test
  fun `post objects rejects malformed JSON`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(POST, "${server.baseUrl}/objects").body("""{"name":"incomplete""")
        )

      assertThat(response.status.code).isEqualTo(400)
    }
  }

  @Test
  fun `pokemon index accepts exactly one decoded limit five query`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)
      val response = client(Request(GET, "${server.baseUrl}/pokemon?limit=5"))
      val encodedResponse = client(Request(GET, "${server.baseUrl}/pokemon?li%6Dit=%35"))

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo(
          """{"results":[{"name":"bulbasaur"},{"name":"ivysaur"},{"name":"venusaur"},{"name":"charmander"},{"name":"charmeleon"}]}"""
        )
      assertThat(encodedResponse.status).isEqualTo(OK)
      assertThat(encodedResponse.bodyString()).isEqualTo(response.bodyString())
      assertThat(server.requests().map { it.path to it.queryParameters })
        .containsExactly(
          "/pokemon" to listOf(RecordedNameValue("limit", "5")),
          "/pokemon" to listOf(RecordedNameValue("limit", "5")),
        )
        .inOrder()
    }
  }

  @Test
  fun `recorded requests retain decoded query pair order duplicates and null values`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(
            GET,
            "${server.baseUrl}/pokemon?z=%32&tag=first&tag=second&flag&empty=",
          )
        )

      assertThat(response.status).isEqualTo(NOT_FOUND)
      assertThat(server.requests().single().queryParameters)
        .containsExactly(
          RecordedNameValue("z", "2"),
          RecordedNameValue("tag", "first"),
          RecordedNameValue("tag", "second"),
          RecordedNameValue("flag", null),
          RecordedNameValue("empty", ""),
        )
        .inOrder()
    }
  }

  @Test
  fun `pokemon index without query returns not found`() {
    assertPokemonIndexNotFound("/pokemon")
  }

  @Test
  fun `pokemon index with wrong limit returns not found`() {
    assertPokemonIndexNotFound("/pokemon?limit=4")
  }

  @Test
  fun `pokemon index with duplicate limit returns not found`() {
    assertPokemonIndexNotFound("/pokemon?limit=5&limit=5")
  }

  @Test
  fun `pokemon index with extra query returns not found`() {
    assertPokemonIndexNotFound("/pokemon?limit=5&offset=0")
  }

  @Test
  fun `pokemon detail and species return fixed bulbasaur responses`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)

      val pokemon = client(Request(GET, "${server.baseUrl}/pokemon/bulbasaur"))
      val species = client(Request(GET, "${server.baseUrl}/pokemon-species/bulbasaur"))

      assertThat(pokemon.status).isEqualTo(OK)
      assertThat(pokemon.bodyString()).isEqualTo("""{"id":1,"name":"bulbasaur"}""")
      assertThat(species.status).isEqualTo(OK)
      assertThat(species.bodyString()).isEqualTo("""{"id":1,"name":"bulbasaur"}""")
    }
  }

  @Test
  fun `unsupported pokemon paths and methods return not found`() {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val client = prepareHttpClient(insecureHttp = false)

      assertThat(client(Request(GET, "${server.baseUrl}/pokemon/missing")).status)
        .isEqualTo(NOT_FOUND)
      assertThat(client(Request(POST, "${server.baseUrl}/pokemon")).status).isEqualTo(NOT_FOUND)
    }
  }

  private fun assertPokemonIndexNotFound(pathAndQuery: String) {
    MockHttpServer.start(DeterministicMockApi()).use { server ->
      val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${server.baseUrl}$pathAndQuery"))

      assertThat(response.status).isEqualTo(NOT_FOUND)
    }
  }
}
