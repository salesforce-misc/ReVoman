# Kick `httpClient` (whisper) — Design

**Date:** 2026-08-23  
**Status:** Draft (awaiting review)  
**Scope:** Optional per-run http4k `HttpHandler` on `Kick`. JMH engine benches use it. `MockHttpServer` stays for tests that need a real socket. No fake HTTP delay.

## Problem

ReVoman always dials HTTP with a process-lifetime `ApacheClient` (`fireHttpRequest` / `executePolling` → `prepareHttpClient(insecureHttp)`). Tests that must not hit the public network therefore start a loopback `MockHttpServer` and put its `baseUrl` in the environment.

That loopback is the right tool to prove Apache actually speaks HTTP (host in the URI, `Content-Length`, IPv4 vs `localhost`, consumed body streams). It is the wrong tool for **engine** JMH: the score includes TCP, Apache, and the JDK server.

http4k already *is* in-process HTTP: `HttpHandler` is `Request -> Response`. `ApacheClient` is one such function. ReVoman never lets the caller pass another.

## Decision

Add **one optional Kick seam**: `httpClient(HttpHandler)`.

| Situation | What runs |
| --- | --- |
| `httpClient` unset (production, most tests) | Today’s Apache client |
| `httpClient` set (JMH engine, in-process stubs) | That handler, in-process |
| Skip / control-flow / wire tests | Unset client + `MockHttpServer` |

Two measurements, one API. Whisper is the engine bench. Loopback is not a bench.

## Kick contract

- Builder: `Kick.httpClient(HttpHandler)` (Immutables/`KickDef`, Java SAM).
- Type: `org.http4k.core.HttpHandler` only. No `MockHttpHandler` overload.
- Default: absent. **Not** insecure Apache. `insecureHttp()` stays **false** unless the caller sets it.
- `insecureHttp()`: explicit opt-in for TLS-blind Apache (local/self-signed). No new loopback-only guard.
- If `httpClient` is set, **ignore** `insecureHttp()` — there is no TLS handshake.
- Scope: that `revUp` only. Not a static/process override.

## Data flow

At the start of `revUp`, resolve one `HttpHandler`:

1. `kick.httpClient()` if present.
2. Else `prepareHttpClient(kick.insecureHttp())` (secure Apache unless `insecureHttp()` is true).

Pass that handler into `fireHttpRequest` and `executePolling`. Do not call `prepareHttpClient` again per step.

Whisper sees the same http4k `Request` ReVoman already built (method, URI after env overlay, headers, body) and returns an http4k `Response`. Unmarshalling, scripts, and rundown do not change.

Env still needs an absolute URI after overlay. Engine benches may use a dummy `baseUrl` such as `http://whisper.invalid`. Nothing listens there.

## Errors

- Handler throws → same as Apache throwing: `HttpRequestFailure` (polling: existing polling failure). No whisper-specific error type.
- Java `null` response → NPE wrapped on that path. Do not map client-side null to HTTP 500 (that mapping is `MockHttpServer`’s job).

## JMH

- **Engine only:** set `httpClient` to a constant `200` plus a small JSON body. No `Thread.sleep`. No `MockHttpServer`.
- Do **not** add a second loopback JMH bench. Localhost TCP is not production WAN; it hides engine changes.
- Existing revUp benches that start `MockHttpServer` should move to whisper.

## Tests

Keep all current `MockHttpServer` tests (wire, skip, Java, `DeterministicMockApi`).

Add Kick-level tests:

- Custom `httpClient` is invoked; no listen port required.
- Same handler is used for a polling step.
- Unset `httpClient` still uses Apache (existing loopback tests cover this).
- `insecureHttp()` default remains false; with a custom client it has no effect.

## Non-goals

- Recording wrapper so whisper has `requests()` (skip tests keep `MockHttpServer.requests()`).
- `Kick.httpMode(WHISPER | LOOPBACK)` or auto-starting a mock.
- Fake delay / latency injection.
- Removing or shrinking `MockHttpServer` further in this change.
- Changing `insecureHttp()` default or tying it to loopback URLs.
- `pm.sendRequest` (not implemented; still throws in the sandbox). This seam only covers `fireHttpRequest` and `executePolling`.

## Implementation sketch

- `KickDef`: optional `HttpHandler httpClient()` (nullable / `Optional` per existing Kick style).
- `ReVoman.revUp`: resolve handler once; thread through `fireHttpRequest` and `executePolling` instead of `Boolean insecureHttp` *or* pass both and resolve inside those functions from Kick — prefer **one `HttpHandler` argument** so call sites cannot mix flags.
- JMH `@Setup`: `Kick.configure().httpClient { Response(OK).body("""{"id":1}""") }.…`

## Success

- Engine JMH has no `MockHttpServer` in the timed path.
- Production Kick with no `httpClient` is behavior-identical (secure Apache).
- At least one unit test proves a stub handler runs a collection without binding a port.
- Loopback tests still catch URI-without-host / Apache-only failures.
