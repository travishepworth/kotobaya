package kotobaya.auth

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotobaya.server.Uuid7
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokensApiTest : AuthFixture() {
    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val delete = HttpMethod.Delete

    // ---------------------------------------------------------------- personal access tokens

    @Test
    fun createTokenShowsTheStringOnceAndStoresOnlyItsHash() = app {
        val admin = admin()
        val r = client.call(post, "/tokens", bearer = admin, body = mapOf("name" to "coverage-script", "scopes" to listOf("import", "read"), "expires_at" to "2027-11-12T09:00:00+09:00"))
        assertEquals(201, r.status.value)
        val body = r.json()
        val token = body["token"].asText()
        assertTrue(Regex("^kbp_[A-Za-z0-9]{40}$").matches(token))
        val info = body["info"]
        assertEquals("personal", info["kind"].asText())
        assertEquals(listOf("read", "import"), info["scopes"].map { it.asText() }, "API order")
        assertEquals(token.take(8), info["token_prefix"].asText())
        assertEquals("2027-11-12T00:00:00.000Z", info["expires_at"].asText())
        assertTrue(info["device_id"].isNull)
        assertTrue(info["active"].asBoolean())
        assertEquals(11, info.size())

        val id = info["id"].asText()
        val again = client.call(get, "/tokens/$id", bearer = admin).bodyAsText()
        assertFalse(token in again)
        val hash = db.txBlocking { it.fetchValue("select token_hash from app.api_token where id = ?::uuid", id) }
        assertEquals(Tokens.hash(token), hash)

        val principal = client.call(get, "/auth/session", bearer = token).json()
        assertEquals(listOf("read", "import"), principal["scopes"].map { it.asText() })
    }

    @Test
    fun createTokenValidatesItsBody() = app {
        val admin = admin()
        val bad = listOf(
            mapOf("name" to "x", "scopes" to emptyList<String>()),
            mapOf("name" to "x", "scopes" to listOf("read", "read")),
            mapOf("name" to "x", "scopes" to listOf("root")),
            mapOf("name" to "", "scopes" to listOf("read")),
            mapOf("scopes" to listOf("read")),
            mapOf("name" to "x", "scopes" to listOf("read"), "expires_at" to "2026-11-12T09:29:59Z"),
            mapOf("name" to "x", "scopes" to listOf("read"), "expires_at" to "tomorrow"),
            mapOf("name" to "x", "scopes" to listOf("read"), "with_device" to "yes"),
            mapOf("name" to "x", "scopes" to listOf("read"), "extra" to 1),
        )
        for (b in bad) assertProblem(client.call(post, "/tokens", bearer = admin, body = b), 422, "validation_failed")
        assertEquals(201, client.call(post, "/tokens", bearer = admin, body = mapOf("name" to "x", "scopes" to listOf("read"), "expires_at" to null)).status.value)
    }

    @Test
    fun withDeviceCreatesAScriptDeviceAndOnlyDeviceRevocationCascades() = app {
        val admin = admin()
        val created = client.call(post, "/tokens", bearer = admin, body = mapOf("name" to "pusher", "scopes" to listOf("read", "write"), "with_device" to true)).json()
        val token = created["token"].asText()
        val deviceId = created["info"]["device_id"].asText()
        val device = client.call(get, "/devices/$deviceId", bearer = admin).json()
        assertEquals("script", device["kind"].asText())
        assertEquals("pusher", device["name"].asText())
        assertEquals(deviceId, client.call(get, "/devices/self", bearer = token).json()["id"].asText())

        // Revoking the token leaves the device alive.
        assertEquals(204, client.call(delete, "/tokens/${created["info"]["id"].asText()}", bearer = admin).status.value)
        assertTrue(client.call(get, "/devices/$deviceId", bearer = admin).json()["revoked_at"].isNull)

        // Revoking the device revokes a second token bound to it.
        val (_, second) = mint(TokenKind.PERSONAL, listOf("read"), deviceId = UUID.fromString(deviceId))
        assertEquals(200, client.call(get, "/devices/self", bearer = second).status.value)
        client.call(delete, "/devices/$deviceId", bearer = admin)
        assertProblem(client.call(get, "/devices/self", bearer = second), 401, "unauthenticated")
    }

    // ---------------------------------------------------------------- revocation

    @Test
    fun tokenRevocationIsEffectiveOnTheNextRequest() = app {
        val admin = admin()
        val (id, token) = mint(TokenKind.PERSONAL, listOf("read"))
        assertEquals(200, client.call(get, "/auth/session", bearer = token).status.value) // cached now
        assertEquals(204, client.call(delete, "/tokens/$id", bearer = admin).status.value)
        assertProblem(client.call(get, "/auth/session", bearer = token), 401, "unauthenticated")
        assertEquals(listOf(id), events.revoked.toList())
        assertEquals(204, client.call(delete, "/tokens/$id", bearer = admin).status.value, "idempotent")
        assertProblem(client.call(delete, "/tokens/${Uuid7.generate()}", bearer = admin), 404, "not_found")
    }

    @Test
    fun revokingTheTokenInUseStillReturns204AndTheNextRequestFails() = app {
        val (id, admin) = mint(TokenKind.PERSONAL, ALL_SCOPES)
        assertEquals(204, client.call(delete, "/tokens/$id", bearer = admin).status.value)
        assertProblem(client.call(get, "/tokens", bearer = admin), 401, "unauthenticated")
    }

    @Test
    fun aSessionCanBeRevokedByIdAndLogoutClosesItsStreams() = app {
        val admin = admin()
        val (sid, session) = mint(TokenKind.SESSION, ALL_SCOPES)
        assertEquals(204, client.call(post, "/auth/logout", cookie = session).status.value)
        assertEquals(listOf(sid), events.revoked.toList())
        val (sid2, session2) = mint(TokenKind.SESSION, ALL_SCOPES)
        assertEquals(204, client.call(delete, "/tokens/$sid2", bearer = admin).status.value)
        assertProblem(client.call(get, "/auth/session", cookie = session2), 401, "unauthenticated")
    }

    @Test
    fun expiredTokensAreRejectedAndListedInactive() = app {
        val admin = admin()
        val (id, token) = mint(TokenKind.PERSONAL, listOf("read"), expiresAt = now.get().plusSeconds(60))
        assertEquals(200, client.call(get, "/auth/session", bearer = token).status.value)
        now.set(now.get().plusSeconds(60))
        assertProblem(client.call(get, "/auth/session", bearer = token), 401, "unauthenticated")
        assertFalse(client.call(get, "/tokens/$id", bearer = admin).json()["active"].asBoolean())
        assertEquals(204, client.call(delete, "/tokens/$id", bearer = admin).status.value, "204 for an expired token too")
    }

    @Test
    fun logoutWithABearerDeviceTokenIs422() = app {
        val device = insertDevice()
        val (_, token) = mint(TokenKind.DEVICE, listOf("read", "write", "review"), deviceId = device)
        assertProblem(client.call(post, "/auth/logout", bearer = token), 422, "validation_failed")
        assertEquals(200, client.call(get, "/auth/session", bearer = token).status.value)
    }

    // ---------------------------------------------------------------- list

    @Test
    fun listTokensFiltersByKindAndActiveNewestFirst() = app {
        val (adminId, admin) = mint(TokenKind.PERSONAL, ALL_SCOPES)
        now.set(now.get().plusSeconds(1))
        val (revokedId, _) = mint(TokenKind.PERSONAL, listOf("read"))
        now.set(now.get().plusSeconds(1))
        val (sessionId, _) = mint(TokenKind.SESSION, ALL_SCOPES)
        client.call(delete, "/tokens/$revokedId", bearer = admin)

        suspend fun ids(q: String) = client.call(get, "/tokens$q", bearer = admin).json()["items"].map { UUID.fromString(it["id"].asText()) }
        assertEquals(listOf(sessionId, revokedId, adminId), ids(""))
        assertEquals(listOf(revokedId, adminId), ids("?kind=personal"))
        assertEquals(listOf(sessionId, adminId), ids("?active=true"))
        assertEquals(listOf(revokedId), ids("?kind=personal&active=false"))
        assertProblem(client.call(get, "/tokens?kind=root", bearer = admin), 422, "validation_failed")
        assertProblem(client.call(get, "/tokens?active=maybe", bearer = admin), 422, "validation_failed")
        assertProblem(client.call(get, "/tokens?limit=1", bearer = admin), 422, "validation_failed")
    }

    @Test
    fun lastUsedAtIsRecordedAtMostOncePerMinute() = app {
        val admin = admin()
        val device = insertDevice()
        val (id, token) = mint(TokenKind.DEVICE, listOf("read"), deviceId = device)
        client.call(get, "/devices/self", bearer = token)
        assertEquals("2026-11-12T09:30:00.000Z", client.call(get, "/tokens/$id", bearer = admin).json()["last_used_at"].asText())
        assertEquals("2026-11-12T09:30:00.000Z", client.call(get, "/devices/$device", bearer = admin).json()["last_seen_at"].asText())
        now.set(now.get().plusSeconds(30))
        client.call(get, "/devices/self", bearer = token)
        assertEquals("2026-11-12T09:30:00.000Z", client.call(get, "/tokens/$id", bearer = admin).json()["last_used_at"].asText())
        now.set(now.get().plusSeconds(31))
        client.call(get, "/devices/self", bearer = token)
        assertEquals("2026-11-12T09:31:01.000Z", client.call(get, "/tokens/$id", bearer = admin).json()["last_used_at"].asText())
    }

    // ---------------------------------------------------------------- /ankiconnect body key

    private val recorded = mutableListOf<Pair<String, Authenticated>>()
    private val actions = AnkiConnectActions { _, auth, action, _ ->
        recorded += action to auth
        listOf("Inbox")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.anki(body: String, bearer: String? = null) =
        client.post("/api/v1/ankiconnect") {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private fun ok(r: io.ktor.client.statement.HttpResponse) = r.json().also { assertEquals(200, r.status.value) }

    @Test
    fun ankiConnectAcceptsAPersonalTokenWithWriteInTheBodyKey() = app(ankiConnect = actions) {
        val (id, key) = mint(TokenKind.PERSONAL, listOf("write"))
        val r = ok(anki("""{"action":"deckNames","version":6,"key":"$key"}"""))
        assertEquals("Inbox", r["result"][0].asText())
        assertTrue(r["error"].isNull)
        assertEquals(id, recorded.single().second.principal.tokenId)
        assertEquals(CredentialSource.BODY_KEY, recorded.single().second.source)
    }

    @Test
    fun ankiConnectBodyKeyNeedsTheWriteScopeAndAValidPersonalToken() = app(ankiConnect = actions) {
        val (_, readOnly) = mint(TokenKind.PERSONAL, listOf("read", "admin"))
        assertEquals("the api key lacks the write scope", ok(anki("""{"action":"deckNames","key":"$readOnly"}"""))["error"].asText())
        val (revokedId, revoked) = mint(TokenKind.PERSONAL, listOf("write"))
        client.call(HttpMethod.Delete, "/tokens/$revokedId", bearer = admin())
        val device = insertDevice()
        val (_, deviceToken) = mint(TokenKind.DEVICE, listOf("read", "write", "review"), deviceId = device)
        for (key in listOf(revoked, deviceToken, "kbp_nope", "")) {
            val r = ok(anki("""{"action":"deckNames","key":"$key"}"""))
            assertTrue(r["result"].isNull)
            assertEquals("valid api key must be provided", r["error"].asText(), key)
        }
        assertEquals("valid api key must be provided", ok(anki("""{"action":"deckNames"}"""))["error"].asText())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun ankiConnectVersionNeedsNoCredentialAndNonJsonIs400() = app(ankiConnect = actions) {
        val r = ok(anki("""{"action":"version","version":6}"""))
        assertEquals(6, r["result"].asInt())
        assertTrue(r["error"].isNull)
        assertProblem(anki("not json"), 400, "invalid_request")
    }

    @Test
    fun ankiConnectHeaderWinsOverTheBodyKey() = app(ankiConnect = actions) {
        val (_, key) = mint(TokenKind.PERSONAL, listOf("write"))
        val (_, readOnly) = mint(TokenKind.PERSONAL, listOf("read"))
        assertEquals("valid api key must be provided", ok(anki("""{"action":"deckNames","key":"$key"}""", bearer = "kbp_invalid"))["error"].asText())
        assertTrue(ok(anki("""{"action":"deckNames","key":"$key"}""", bearer = readOnly))["error"].asText().contains("write"))
        val (headerId, header) = mint(TokenKind.PERSONAL, listOf("write"))
        assertTrue(ok(anki("""{"action":"deckNames","key":"$readOnly"}""", bearer = header))["error"].isNull)
        assertEquals(headerId, recorded.single().second.principal.tokenId)
        assertEquals(CredentialSource.BEARER, recorded.single().second.source)
    }

    @Test
    fun aBodyKeyIsAcceptedNowhereButAnkiConnect() = app(ankiConnect = actions) {
        val admin = admin()
        assertProblem(client.call(post, "/devices/pairings", body = mapOf("key" to admin)), 401, "unauthenticated")
        assertProblem(client.call(post, "/tokens", body = mapOf("key" to admin, "name" to "x", "scopes" to listOf("read"))), 401, "unauthenticated")
        assertProblem(client.call(HttpMethod.Patch, "/settings", body = mapOf("key" to admin, "values" to mapOf("a" to 1))), 401, "unauthenticated")
    }

    @Test
    fun ankiConnectWithoutActionsAnswersOnlyVersion() = app {
        val (_, key) = mint(TokenKind.PERSONAL, listOf("write"))
        assertEquals("unsupported action", ok(anki("""{"action":"deckNames","key":"$key"}"""))["error"].asText())
        assertEquals(6, ok(anki("""{"action":"version"}"""))["result"].asInt())
    }
}
