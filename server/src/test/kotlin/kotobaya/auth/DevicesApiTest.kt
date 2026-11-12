package kotobaya.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotobaya.jobs.EventHub
import kotobaya.server.SERVER_DEVICE_ID
import kotobaya.server.Uuid7
import java.net.URLDecoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicesApiTest : AuthFixture() {
    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val patch = HttpMethod.Patch
    private val delete = HttpMethod.Delete

    private suspend fun HttpClient.newCode(admin: String): String {
        val r = call(post, "/devices/pairings", bearer = admin)
        assertEquals(201, r.status.value)
        return r.json()["code"].asText()
    }

    private suspend fun HttpClient.pair(
        code: String,
        deviceId: UUID = Uuid7.generate(),
        name: String = "Pixel 9",
        extra: Map<String, Any?> = emptyMap(),
        client: String = "client-a",
    ): HttpResponse = call(post, "/devices/pair", body = mapOf("code" to code, "device_id" to deviceId.toString(), "name" to name) + extra) {
        header("X-Test-Client", client)
    }

    private fun logOp(deviceId: UUID, hlc: String) = db.txBlocking {
        it.execute(
            "insert into app.change (op_id, op_type, entity_type, entity_id, device_id, hlc, payload) values (?, 'note.created', 'note', 'n', ?, ?, '{}'::jsonb)",
            UUID.randomUUID(), deviceId, hlc,
        )
    }

    // ---------------------------------------------------------------- pairing

    @Test
    fun pairingIssuesADeviceTokenWithReadWriteReviewAndEmitsDevicePaired() = app {
        val admin = admin()
        val pairing = client.call(post, "/devices/pairings", bearer = admin)
        assertEquals(201, pairing.status.value)
        val p = pairing.json()
        val code = p["code"].asText()
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{8}$").matches(code), code)
        assertEquals("2026-11-12T09:40:00.000Z", p["expires_at"].asText())
        val uri = p["pairing_uri"].asText()
        assertEquals("kotobaya://pair?server=https%3A%2F%2Fkotobaya.example.net%2Fapi%2Fv1&code=$code", uri)
        assertEquals("https://kotobaya.example.net/api/v1", URLDecoder.decode(uri.substringAfter("server=").substringBefore('&'), Charsets.UTF_8))

        val id = Uuid7.generate()
        val r = client.pair(code, id, extra = mapOf("push_topic" to "https://ntfy.example.net/up-a1"))
        assertEquals(201, r.status.value)
        val body = r.json()
        val token = body["token"].asText()
        assertTrue(Regex("^kbd_[A-Za-z0-9]{40}$").matches(token))
        assertEquals(listOf("read", "write", "review"), body["scopes"].map { it.asText() })
        assertEquals(SERVER_DEVICE_ID, body["server_device_id"].asText())
        assertEquals(0, body["high_water_seq"].asLong())
        val device = body["device"]
        assertEquals(id.toString(), device["id"].asText())
        assertEquals("android", device["kind"].asText())
        assertEquals("https://ntfy.example.net/up-a1", device["push_topic"].asText())
        assertTrue(device["revoked_at"].isNull)
        assertEquals(listOf(EVENT_DEVICE_PAIRED to id), events.deviceEvents.toList())

        val row = db.txBlocking { it.fetchOne("select kind, scopes, device_id, expires_at, token_hash from app.api_token where id = ?::uuid", body["token_id"].asText())!! }
        assertEquals("device", row.get(0))
        assertEquals(Tokens.hash(token), row.get(4), "only the hash is stored")
        assertNull(row.get(3), "no expiry")

        val self = client.call(get, "/devices/self", bearer = token)
        assertEquals(200, self.status.value)
        assertEquals(id.toString(), self.json()["id"].asText())
    }

    @Test
    fun pairingCodeIsSingleUse() = app {
        val code = client.newCode(admin())
        assertEquals(201, client.pair(code).status.value)
        assertProblem(client.pair(code), 401, "invalid_pairing_code")
    }

    @Test
    fun pairingCodeExpiresAfterTenMinutes() = app {
        val admin = admin()
        val code = client.newCode(admin)
        now.set(now.get().plusSeconds(600))
        assertProblem(client.pair(code), 401, "invalid_pairing_code")
        val fresh = client.newCode(admin)
        now.set(now.get().plusSeconds(599))
        assertEquals(201, client.pair(fresh).status.value)
    }

    @Test
    fun pairingCodeIsCaseInsensitiveAndIgnoresHyphensAndSpaces() = app {
        val code = client.newCode(admin())
        val typed = code.lowercase().chunked(4).joinToString("- ")
        assertEquals(201, client.pair(typed).status.value)
    }

    @Test
    fun wrongCodesAreRateLimitedPerClientAddress() = app {
        val code = client.newCode(admin())
        repeat(10) { assertProblem(client.pair("ZZZZZZZZ"), 401, "invalid_pairing_code") }
        val limited = client.pair(code)
        assertProblem(limited, 429, "rate_limited")
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        assertEquals(201, client.pair(code, client = "client-b").status.value, "another address is unaffected; the code was not used")
        now.set(now.get().plusSeconds(15 * 60 + 1))
        assertProblem(client.pair(code), 401, "invalid_pairing_code")
    }

    @Test
    fun validationErrorsAreNotCountedAsGuesses() = app {
        val code = client.newCode(admin())
        repeat(12) { assertProblem(client.call(post, "/devices/pair", body = mapOf("code" to code)), 422, "validation_failed") }
        assertEquals(201, client.pair(code).status.value)
    }

    @Test
    fun twoRequestsRacingWithOneCodeExactlyOneSucceeds() = app {
        val code = client.newCode(admin())
        val statuses = coroutineScope {
            (1..6).map { async { client.pair(code).status.value } }.map { it.await() }
        }
        assertEquals(1, statuses.count { it == 201 }, statuses.toString())
        assertTrue(statuses.filter { it != 201 }.all { it == 401 }, statuses.toString())
    }

    @Test
    fun serverDeviceIdIs422AndTheCodeStaysValid() = app {
        val code = client.newCode(admin())
        val r = client.pair(code, UUID.fromString(SERVER_DEVICE_ID))
        assertProblem(r, 422, "validation_failed")
        assertEquals("/device_id", r.json()["errors"][0]["pointer"].asText())
        assertProblem(client.call(post, "/devices/pair", body = mapOf("code" to code, "device_id" to Uuid7.generate().toString().uppercase(), "name" to "x")), 422, "validation_failed")
        assertEquals(201, client.pair(code).status.value)
    }

    @Test
    fun rePairOverwritesTheRowClearsRevokedAtAndRevokesEarlierTokens() = app {
        val admin = admin()
        val id = Uuid7.generate()
        val first = client.pair(client.newCode(admin), id, "Pixel 9").json()["token"].asText()
        assertEquals(200, client.call(get, "/devices/self", bearer = first).status.value)

        // Re-pair without a revoke: the earlier token dies at once.
        val second = client.pair(client.newCode(admin), id, "Pixel 9 Pro", mapOf("push_topic" to "https://ntfy.example.net/up-1")).json()["token"].asText()
        assertProblem(client.call(get, "/devices/self", bearer = first), 401, "unauthenticated")
        assertEquals(200, client.call(get, "/devices/self", bearer = second).status.value)

        assertEquals(204, client.call(delete, "/devices/$id", bearer = admin).status.value)
        assertProblem(client.call(get, "/devices/self", bearer = second), 401, "unauthenticated")
        now.set(now.get().plusSeconds(5))
        val third = client.pair(client.newCode(admin), id, "Pixel 10")
        assertEquals(201, third.status.value)
        val d = third.json()["device"]
        assertTrue(d["revoked_at"].isNull)
        assertEquals("Pixel 10", d["name"].asText())
        assertEquals("android", d["kind"].asText())
        assertTrue(d["push_topic"].isNull, "push_topic overwritten")
        assertEquals("2026-11-12T09:30:05.000Z", d["paired_at"].asText())
        assertEquals(1, db.txBlocking { it.fetchCount(it.selectFrom("app.device").where("id = ?", id)) })
        assertEquals(1, db.txBlocking { it.fetchCount(it.selectFrom("app.api_token").where("device_id = ? and revoked_at is null", id)) })
    }

    @Test
    fun rePairingAScriptDeviceAsAPhoneIs422AndKeepsTheScriptTokenAndTheCode() = app {
        val admin = admin()
        val created = client.call(post, "/tokens", bearer = admin, body = mapOf("name" to "pusher", "scopes" to listOf("read", "write"), "with_device" to true)).json()
        val script = created["token"].asText()
        val id = UUID.fromString(created["info"]["device_id"].asText())
        val code = client.newCode(admin)
        val r = client.pair(code, id)
        assertProblem(r, 422, "validation_failed")
        assertEquals("/kind", r.json()["errors"][0]["pointer"].asText())
        assertEquals(200, client.call(get, "/devices/self", bearer = script).status.value, "script token untouched")
        assertEquals(201, client.pair(code, id, extra = mapOf("kind" to "script")).status.value, "same kind is a re-pair")
    }

    // ---------------------------------------------------------------- pairing_uri origin

    private fun uriServer(r: HttpResponse): String {
        assertEquals(201, r.status.value)
        return URLDecoder.decode(r.json()["pairing_uri"].asText().substringAfter("server=").substringBefore('&'), Charsets.UTF_8)
    }

    @Test
    fun publicUrlWinsOverForwardedHeaders() = app {
        val r = client.call(post, "/devices/pairings", bearer = admin()) {
            header("X-Test-Trusted-Peer", "1")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "evil.example.com")
        }
        assertEquals("https://kotobaya.example.net/api/v1", uriServer(r))
    }

    @Test
    fun withoutPublicUrlForwardedProtoAndHostAreBelievedOnlyFromTrustedProxies() = app(publicUrl = null) {
        val admin = admin()
        val trusted = client.call(post, "/devices/pairings", bearer = admin) {
            header("X-Test-Trusted-Peer", "1")
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "kotobaya.lan:8443")
        }
        assertEquals("https://kotobaya.lan:8443/api/v1", uriServer(trusted))
        val hostOnly = client.call(post, "/devices/pairings", bearer = admin) {
            header("X-Test-Trusted-Peer", "1")
            header("X-Forwarded-Proto", "https")
            header(HttpHeaders.Host, "kotobaya.local")
        }
        assertEquals("https://kotobaya.local/api/v1", uriServer(hostOnly))

        // The same headers from an untrusted peer over plain http: refused, nothing put in a QR code.
        val untrusted = client.call(post, "/devices/pairings", bearer = admin) {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Host", "kotobaya.lan")
        }
        assertProblem(untrusted, 500, "internal")
        assertTrue(untrusted.json()["detail"].asText().contains("PUBLIC_URL"))
        // A trusted peer with a header that is not a host is refused too.
        assertProblem(
            client.call(post, "/devices/pairings", bearer = admin) {
                header("X-Test-Trusted-Peer", "1")
                header("X-Forwarded-Proto", "https")
                header("X-Forwarded-Host", "evil.example.com/x?y")
            },
            500, "internal",
        )
    }

    @Test
    fun withoutPublicUrlOrTrustedProxyAPlainHttpRequestCannotCreateAPairing() = app(publicUrl = null) {
        assertProblem(client.call(post, "/devices/pairings", bearer = admin()), 500, "internal")
    }

    @Test
    fun publicOriginBranches() {
        val h = mapOf("Host" to "kotobaya.home", "X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "fwd.example")
        assertEquals("https://pub.example", Devices.publicOrigin("https://pub.example/", true, "http") { h[it] })
        assertEquals("https://fwd.example", Devices.publicOrigin(null, true, "http") { h[it] })
        assertEquals("https://kotobaya.home", Devices.publicOrigin(null, false, "https") { h[it] }, "direct TLS")
        assertNull(Devices.publicOrigin(null, false, "http") { h[it] }, "plain http, untrusted peer")
        assertNull(Devices.publicOrigin(null, false, "https") { null }, "no Host")
        assertNull(Devices.publicOrigin(null, true, "http") { mapOf("Host" to "x")[it] }, "trusted peer without X-Forwarded-Proto")
    }

    @Test
    fun theDefaultTrustCheckUsesTheProxyList() {
        val r = ClientAddressResolver(ClientAddressResolver.parseList("10.42.0.0/16"))
        assertTrue(r.isTrusted("10.42.3.4"))
        assertTrue(!r.isTrusted("203.0.113.9"))
        assertTrue(!r.isTrusted("localhost"))
    }

    @Test
    fun hlcRegressedOnRePairRollsBackAndKeepsTheCode() = app {
        val admin = admin()
        val id = Uuid7.generate()
        client.pair(client.newCode(admin), id)
        val logged = "0193a1b2c9f0-0000-$id"
        logOp(id, logged)

        val code = client.newCode(admin)
        val r = client.pair(code, id, extra = mapOf("hlc_last" to "0193a1b2c3d4-0001-$id"))
        assertProblem(r, 422, "validation_failed")
        val err = r.json()["errors"][0]
        assertEquals("/hlc_last", err["pointer"].asText())
        assertEquals("hlc_regressed", err["code"].asText())

        val foreign = client.pair(code, id, extra = mapOf("hlc_last" to "0193a1b2c9f0-0000-${Uuid7.generate()}"))
        assertProblem(foreign, 422, "validation_failed")
        assertEquals("invalid_value", foreign.json()["errors"][0]["code"].asText())

        // Equal to the greatest logged HLC is fine, and the code survived both 422s.
        assertEquals(201, client.pair(code, id, extra = mapOf("hlc_last" to logged)).status.value)
    }

    @Test
    fun hlcRegressedClientPairsAsANewDeviceWithTheSameCode() = app {
        val admin = admin()
        val id = Uuid7.generate()
        client.pair(client.newCode(admin), id)
        logOp(id, "0193a1b2c9f0-0000-$id")
        val code = client.newCode(admin)
        assertProblem(client.pair(code, id, extra = mapOf("hlc_last" to "0193a1b2c3d4-0001-$id")), 422, "validation_failed")
        assertEquals(201, client.pair(code, Uuid7.generate()).status.value)
    }

    @Test
    fun pairWithoutHlcLastOrForADeviceThatNeverLoggedSkipsTheCheck() = app {
        val admin = admin()
        val id = Uuid7.generate()
        assertEquals(201, client.pair(client.newCode(admin), id, extra = mapOf("hlc_last" to "000000000001-0000-$id")).status.value)
        logOp(id, "0193a1b2c9f0-0000-$id")
        assertEquals(201, client.pair(client.newCode(admin), id).status.value)
    }

    // ---------------------------------------------------------------- revocation

    @Test
    fun revokingADeviceKillsAllItsTokensAtOnceAndEmitsDeviceRevoked() = app {
        val admin = admin()
        val id = Uuid7.generate()
        val paired = client.pair(client.newCode(admin), id).json()
        val deviceToken = paired["token"].asText()
        val (boundId, bound) = mint(TokenKind.PERSONAL, listOf("read"), deviceId = id)
        // Prime the validator cache: revocation must not wait for it.
        assertEquals(200, client.call(get, "/devices/self", bearer = deviceToken).status.value)
        assertEquals(200, client.call(get, "/devices/self", bearer = bound).status.value)

        assertEquals(204, client.call(delete, "/devices/$id", bearer = admin).status.value)
        assertProblem(client.call(get, "/devices/self", bearer = deviceToken), 401, "unauthenticated")
        assertProblem(client.call(get, "/auth/session", bearer = bound), 401, "unauthenticated")
        assertEquals(setOf(UUID.fromString(paired["token_id"].asText()), boundId), events.revoked.toSet())
        assertEquals(EVENT_DEVICE_REVOKED to id, events.deviceEvents.last())

        val row = client.call(get, "/devices/$id", bearer = admin).json()
        assertEquals("2026-11-12T09:30:00.000Z", row["revoked_at"].asText())
        val revokedEvents = events.deviceEvents.count { it.first == EVENT_DEVICE_REVOKED }
        assertEquals(204, client.call(delete, "/devices/$id", bearer = admin).status.value, "idempotent")
        assertEquals(revokedEvents, events.deviceEvents.count { it.first == EVENT_DEVICE_REVOKED }, "no second event")
    }

    @Test
    fun serverDeviceCannotBeRevokedOrPatchedAndUnknownDevicesAre404() = app {
        val admin = admin()
        assertProblem(client.call(delete, "/devices/$SERVER_DEVICE_ID", bearer = admin), 409, "not_applicable")
        assertProblem(client.call(patch, "/devices/$SERVER_DEVICE_ID", bearer = admin, body = mapOf("name" to "x")), 409, "not_applicable")
        assertProblem(client.call(delete, "/devices/${Uuid7.generate()}", bearer = admin), 404, "not_found")
        assertProblem(client.call(get, "/devices/${Uuid7.generate()}", bearer = admin), 404, "not_found")
        assertProblem(client.call(get, "/devices/not-a-uuid", bearer = admin), 404, "not_found")
    }

    @Test
    fun revocationEndsOpenEventStreamsOfTheRevokedTokens() {
        val hub = EventHub()
        app(authEvents = AuthEvents.of(null, hub)) {
            val admin = admin()
            val id = Uuid7.generate()
            val token = client.pair(client.newCode(admin), id).json()["token_id"].asText()
            val (otherId, _) = mint(TokenKind.PERSONAL, listOf("read"))
            val phone = hub.subscribe(null, token)
            val other = hub.subscribe(null, otherId.toString())
            client.call(delete, "/devices/$id", bearer = admin)
            assertTrue(phone.dropped)
            assertTrue(!other.dropped)
            client.call(delete, "/tokens/$otherId", bearer = admin)
            assertTrue(other.dropped)
        }
    }

    // ---------------------------------------------------------------- list, self, patch

    @Test
    fun listDevicesIncludesServerAndRevokedOrderedByPairedAt() = app {
        val admin = admin()
        now.set(now.get().plusSeconds(1))
        val a = Uuid7.generate()
        client.pair(client.newCode(admin), a)
        now.set(now.get().plusSeconds(1))
        val b = Uuid7.generate()
        client.pair(client.newCode(admin), b)
        client.call(delete, "/devices/$a", bearer = admin)
        val items = client.call(get, "/devices", bearer = admin).json()["items"]
        // The server row's paired_at is the migration's now(), so only the order of the two phones is fixed here.
        val ids = items.map { it["id"].asText() }
        assertEquals(setOf(SERVER_DEVICE_ID, a.toString(), b.toString()), ids.toSet())
        assertTrue(ids.indexOf(a.toString()) < ids.indexOf(b.toString()))
        assertTrue(!items[ids.indexOf(a.toString())]["revoked_at"].isNull)
        assertTrue(items.all { it.size() == 9 }, "every Device property")
    }

    @Test
    fun devicesSelfNeedsADeviceBoundBearerTokenButNotAdmin() = app {
        val admin = admin()
        val id = Uuid7.generate()
        val phone = client.pair(client.newCode(admin), id).json()["token"].asText()
        val p = client.call(patch, "/devices/self", bearer = phone, body = mapOf("push_topic" to "https://ntfy.example.net/up-x", "name" to "Pixel 9 Pro"))
        assertEquals(200, p.status.value)
        assertEquals("https://ntfy.example.net/up-x", p.json()["push_topic"].asText())
        assertEquals("Pixel 9 Pro", p.json()["name"].asText())
        assertEquals(null, client.call(patch, "/devices/self", bearer = phone, body = mapOf("push_topic" to null)).json()["push_topic"].textValue())
        assertProblem(client.call(get, "/devices/$id", bearer = phone), 403, "insufficient_scope")

        val (_, session) = mint(TokenKind.SESSION, ALL_SCOPES)
        assertProblem(client.call(get, "/devices/self", cookie = session), 403, "device_required")
        assertProblem(client.call(patch, "/devices/self", cookie = session, body = mapOf("name" to "x")), 403, "device_required")
        assertProblem(client.call(get, "/devices/self", bearer = admin), 403, "device_required")
        val (_, readOnly) = mint(TokenKind.PERSONAL, listOf("read"), deviceId = id)
        assertEquals(200, client.call(get, "/devices/self", bearer = readOnly).status.value)
        assertProblem(client.call(patch, "/devices/self", bearer = readOnly, body = mapOf("name" to "x")), 403, "insufficient_scope")
    }

    @Test
    fun devicePatchIsValidated() = app {
        val admin = admin()
        val id = insertDevice()
        for (bad in listOf(emptyMap(), mapOf("name" to ""), mapOf("bogus" to 1), mapOf("push_topic" to "ftp://x"), mapOf("push_topic" to 3), mapOf("name" to "x".repeat(101)))) {
            assertProblem(client.call(patch, "/devices/$id", bearer = admin, body = bad), 422, "validation_failed")
        }
        assertEquals("Tablet", client.call(patch, "/devices/$id", bearer = admin, body = mapOf("name" to "Tablet")).json()["name"].asText())
        client.call(delete, "/devices/$id", bearer = admin)
        assertProblem(client.call(patch, "/devices/$id", bearer = admin, body = mapOf("name" to "x")), 409, "not_applicable")
    }
}
