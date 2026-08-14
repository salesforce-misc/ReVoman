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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import org.http4k.core.Method.GET
import org.http4k.core.Method.PATCH
import org.http4k.core.Method.POST
import org.http4k.core.Method.PUT
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeterministicMockApiServerTest {
  @Test
  fun `fixture owns an ephemeral loopback server and shuts down its worker`() {
    lateinit var closedAddress: InetSocketAddress
    lateinit var closedWorkerName: String

    DeterministicMockApiServer.start().use { fixture ->
      DeterministicMockApiServer.start().use { secondFixture ->
        assertThat(fixture.baseUrl).startsWith("http://127.0.0.1:")
        assertThat(secondFixture.baseUrl).startsWith("http://127.0.0.1:")
        val fixtureAddress =
          InetSocketAddress("127.0.0.1", fixture.baseUrl.substringAfterLast(':').toInt())
        val secondFixtureAddress =
          InetSocketAddress("127.0.0.1", secondFixture.baseUrl.substringAfterLast(':').toInt())
        assertThat(fixtureAddress.address.hostAddress).isEqualTo("127.0.0.1")
        assertThat(fixtureAddress.port).isGreaterThan(0)
        assertThat(secondFixtureAddress.port).isGreaterThan(0)
        assertThat(secondFixtureAddress.port).isNotEqualTo(fixtureAddress.port)

        val response =
          prepareHttpClient(insecureHttp = false)(Request(GET, "${fixture.baseUrl}/objects"))

        assertThat(response.status).isEqualTo(OK)
        assertThat(response.bodyString()).isEqualTo("[]")
        assertThat(fixture.requestSignatures()).containsExactly("GET /objects")
        assertThat(fixture.hitCount("/objects")).isEqualTo(1)
        closedAddress = fixtureAddress
        closedWorkerName =
          Thread.getAllStackTraces()
            .keys
            .single { thread ->
              thread.name.startsWith("revoman-deterministic-mock-api-") &&
                thread.isAlive &&
                !thread.isDaemon
            }
            .name
      }

      fixture.close()
      fixture.close()
    }

    assertThrows<IOException> {
      Socket().use { socket -> socket.connect(closedAddress, SOCKET_CONNECT_TIMEOUT_MILLIS) }
    }
    assertThat(
        Thread.getAllStackTraces().keys.any { thread ->
          thread.name == closedWorkerName && thread.isAlive && !thread.isDaemon
        }
      )
      .isFalse()
  }

  @Test
  fun `post objects creates a fixture-local object`() {
    DeterministicMockApiServer.start().use { fixture ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(POST, "${fixture.baseUrl}/objects")
            .body("""{"id":"client-id","name":"first object","data":{"color":"blue"}}""")
        )

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"first object","data":{"color":"blue"}}""")
      assertThat(fixture.requestSignatures()).containsExactly("POST /objects")
    }
  }

  @Test
  fun `patch objects updates supplied fields and preserves omitted data`() {
    DeterministicMockApiServer.start().use { fixture ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${fixture.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response =
        client(
          Request(PATCH, "${fixture.baseUrl}/objects/local-object-1")
            .body("""{"name":"renamed object"}""")
        )

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"renamed object","data":{"color":"blue"}}""")
    }
  }

  @Test
  fun `get objects returns the exact stored object`() {
    DeterministicMockApiServer.start().use { fixture ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${fixture.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response = client(Request(GET, "${fixture.baseUrl}/objects/local-object-1"))

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo("""{"id":"local-object-1","name":"first object","data":{"color":"blue"}}""")
    }
  }

  @Test
  fun `put objects replaces the stored object data`() {
    DeterministicMockApiServer.start().use { fixture ->
      val client = prepareHttpClient(insecureHttp = false)
      client(
        Request(POST, "${fixture.baseUrl}/objects")
          .body("""{"name":"first object","data":{"color":"blue"}}""")
      )

      val response =
        client(
          Request(PUT, "${fixture.baseUrl}/objects/local-object-1")
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
    DeterministicMockApiServer.start().use { fixture ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(PATCH, "${fixture.baseUrl}/objects/missing").body("""{"name":"unused"}""")
        )

      assertThat(response.status).isEqualTo(NOT_FOUND)
      assertThat(response.bodyString()).isEqualTo("""{"error":"object not found"}""")
    }
  }

  @Test
  fun `get missing object returns the deterministic not-found response`() {
    DeterministicMockApiServer.start().use { fixture ->
      val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${fixture.baseUrl}/objects/missing"))

      assertThat(response.status).isEqualTo(NOT_FOUND)
      assertThat(response.bodyString()).isEqualTo("""{"error":"object not found"}""")
    }
  }

  @Test
  fun `post objects rejects malformed JSON`() {
    DeterministicMockApiServer.start().use { fixture ->
      val response =
        prepareHttpClient(insecureHttp = false)(
          Request(POST, "${fixture.baseUrl}/objects").body("""{"name":"incomplete""")
        )

      assertThat(response.status.code).isEqualTo(400)
    }
  }

  @Test
  fun `pokemon index returns the fixed deterministic catalog`() {
    DeterministicMockApiServer.start().use { fixture ->
      val response =
        prepareHttpClient(insecureHttp = false)(Request(GET, "${fixture.baseUrl}/pokemon"))

      assertThat(response.status).isEqualTo(OK)
      assertThat(response.bodyString())
        .isEqualTo(
          """{"results":[{"name":"bulbasaur"},{"name":"ivysaur"},{"name":"venusaur"},{"name":"charmander"},{"name":"charmeleon"}]}"""
        )
    }
  }

  @Test
  fun `pokemon detail and species return fixed bulbasaur responses`() {
    DeterministicMockApiServer.start().use { fixture ->
      val client = prepareHttpClient(insecureHttp = false)

      val pokemon = client(Request(GET, "${fixture.baseUrl}/pokemon/bulbasaur"))
      val species = client(Request(GET, "${fixture.baseUrl}/pokemon-species/bulbasaur"))

      assertThat(pokemon.status).isEqualTo(OK)
      assertThat(pokemon.bodyString()).isEqualTo("""{"id":1,"name":"bulbasaur"}""")
      assertThat(species.status).isEqualTo(OK)
      assertThat(species.bodyString()).isEqualTo("""{"id":1,"name":"bulbasaur"}""")
    }
  }

  @Test
  fun `unsupported pokemon paths and methods return not found`() {
    DeterministicMockApiServer.start().use { fixture ->
      val client = prepareHttpClient(insecureHttp = false)

      assertThat(client(Request(GET, "${fixture.baseUrl}/pokemon/missing")).status)
        .isEqualTo(NOT_FOUND)
      assertThat(client(Request(POST, "${fixture.baseUrl}/pokemon")).status).isEqualTo(NOT_FOUND)
    }
  }

  private companion object {
    const val SOCKET_CONNECT_TIMEOUT_MILLIS = 500
  }
}
