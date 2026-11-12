package kotobaya.auth

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotobaya.server.AppDeps
import kotobaya.server.ConfigException
import kotobaya.server.TestPg
import kotobaya.server.kotobayaModule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientAddressTest {
    private val proxies = ClientAddressResolver(ClientAddressResolver.parseList("10.42.0.0/16, 10.43.0.10, fd00::/8"))

    @Test
    fun spoofedForwardedForFromAnUntrustedPeerIsIgnored() {
        assertEquals("203.0.113.9", proxies.resolve("203.0.113.9", listOf("1.2.3.4")))
        assertEquals("203.0.113.9", ClientAddressResolver(emptyList()).resolve("203.0.113.9", listOf("1.2.3.4")))
    }

    @Test
    fun trustedProxyChainsResolveToTheRightmostUntrustedHop() {
        // client 198.51.100.7 -> traefik 10.42.0.5 -> (peer) 10.42.0.6; the leftmost hop is attacker-controlled.
        assertEquals("198.51.100.7", proxies.resolve("10.42.0.6", listOf("6.6.6.6, 198.51.100.7, 10.42.0.5")))
        assertEquals("198.51.100.7", proxies.resolve("10.42.0.6", listOf("6.6.6.6", "198.51.100.7")), "header repeated")
        assertEquals("198.51.100.7", proxies.resolve("10.43.0.10", listOf("198.51.100.7")), "single-address entry")
        assertEquals("2001:db8:0:0:0:0:0:0/64", proxies.resolve("fd00::1", listOf("2001:db8::1")))
        assertEquals("10.42.0.1", proxies.resolve("10.42.0.6", listOf("10.42.0.1, 10.42.0.2")), "all trusted: leftmost")
        assertEquals("10.42.0.6", proxies.resolve("10.42.0.6", emptyList()), "no header: the peer")
    }

    @Test
    fun malformedForwardedForFallsBackToThePeer() {
        for (h in listOf("not-an-ip", "198.51.100.7, evil.example.com", "1.2.3.4:80", "999.1.1.1", ",,", "unknown")) {
            assertEquals("10.42.0.6", proxies.resolve("10.42.0.6", listOf(h)), h)
        }
    }

    @Test
    fun hopsLeftOfTheClientAreNeverParsed() {
        // Garbage the client put in front of its own address does not make us fall back to the proxy.
        assertEquals("198.51.100.7", proxies.resolve("10.42.0.6", listOf("evil.example.com, not-an-ip, 198.51.100.7, 10.42.0.5")))
        assertEquals("198.51.100.7", proxies.resolve("10.42.0.6", listOf("garbage", "198.51.100.7")))
        // A malformed hop on the trusted side still falls back to the peer.
        assertEquals("10.42.0.6", proxies.resolve("10.42.0.6", listOf("198.51.100.7, bogus, 10.42.0.5")))
    }

    @Test
    fun ipv6ClientsAreKeyedOnTheirSlash64() {
        val a = proxies.resolve("2001:db8:1:2::1", emptyList())
        assertEquals("2001:db8:1:2:0:0:0:0/64", a)
        assertEquals(a, proxies.resolve("2001:db8:1:2:ffff:ffff:ffff:ffff", emptyList()))
        assertEquals("2001:db8:1:3:0:0:0:0/64", proxies.resolve("2001:db8:1:3::1", emptyList()))
        assertEquals("2001:db8:1:2:0:0:0:0/64", proxies.resolve("fd00::5", listOf("2001:db8:1:2::abcd")))
        assertEquals("203.0.113.9", proxies.resolve("203.0.113.9", emptyList()), "IPv4 keeps the full address")
    }

    @Test
    fun ipLiteralsAreParsedStrictly() {
        for (ok in listOf("1.2.3.4", "::", "::1", "2001:db8::1", "[2001:db8::1]", "::ffff:192.0.2.1", "1:2:3:4:5:6:7:8", "1::", "64:ff9b::192.0.2.33", "FE80::A")) {
            assertTrue(parseIpLiteral(ok) != null, ok)
        }
        assertEquals("2001:db8:0:0:0:0:0:1", parseIpLiteral("2001:0db8::0001")!!.hostAddress)
        assertEquals("192.0.2.1", parseIpLiteral("::ffff:192.0.2.1")!!.hostAddress)
        for (bad in listOf(
            "localhost", "example.com", "1.2.3", "1.2.3.4.5", "01.2.3.4", "1.2.3.256", "1:2:3:4:5:6:7:8:9", "1::2::3", ":::",
            "1:2:3:4:5:6:7::8", ":1::", "1::2:", "fe80::1%eth0", "12345::", "g::1", "::1.2.3", "1.2.3.4::", "", "[]", "0x7f.1",
        )) {
            assertNull(parseIpLiteral(bad), bad)
        }
    }

    @Test
    fun cidrMatchingAndParsing() {
        val c = Cidr.parse("10.42.0.0/16")
        assertTrue(c.contains(parseIpLiteral("10.42.255.1")!!))
        assertFalse(c.contains(parseIpLiteral("10.43.0.1")!!))
        assertTrue(Cidr.parse("0.0.0.0/0").contains(parseIpLiteral("8.8.8.8")!!))
        assertNull(parseIpLiteral("localhost"), "host names are never resolved")
        assertFailsWith<IllegalArgumentException> { Cidr.parse("10.0.0.0/33") }
        assertFailsWith<IllegalArgumentException> { Cidr.parse("example.com/8") }
        val phc = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))
        assertFailsWith<ConfigException> { AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TRUSTED_PROXIES" to "nope")) }
        assertEquals(2, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TRUSTED_PROXIES" to "10.0.0.0/8,fd00::/8")).trustedProxies.size)
    }

    @Test
    fun twoClientsBehindTheProxyAreLimitedIndependentlyAndSuccessClearsOnlyYourOwn() = testApplication {
        val db = TestPg.migratedDb()
        val phc = PasswordHasher.hash("right", PasswordHasher.Params(1024, 1, 1))
        // The test host's peer is "localhost"-ish; trust everything so X-Forwarded-For is believed.
        val auth = Auth(
            db, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TRUSTED_PROXIES" to "0.0.0.0/0,::/0")),
            clientAddress = { call ->
                ClientAddressResolver(ClientAddressResolver.parseList("0.0.0.0/0,::/0"))
                    .resolve("10.42.0.6", call.request.headers.getAll("X-Forwarded-For").orEmpty())
            },
        )
        application { kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = auth.handlers)) }
        suspend fun login(pw: String, xff: String) = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", xff)
            setBody("""{"password":"$pw"}""")
        }.status.value

        repeat(4) { assertEquals(401, login("wrong", "198.51.100.1")) }
        repeat(4) { assertEquals(401, login("wrong", "198.51.100.2")) }
        assertEquals(200, login("right", "198.51.100.2")) // clears only .2's failures
        assertEquals(401, login("wrong", "198.51.100.1")) // .1's fifth failure still counts
        assertEquals(429, login("right", "198.51.100.1"))
        repeat(4) { assertEquals(401, login("wrong", "198.51.100.2")) }
        assertEquals(200, login("right", "198.51.100.2"))
        db.close()
    }

    @Test
    fun theDefaultResolverIgnoresForwardedForWithoutTrustedProxies() = testApplication {
        val db = TestPg.migratedDb()
        val phc = PasswordHasher.hash("right", PasswordHasher.Params(1024, 1, 1))
        val auth = Auth(db, AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc)))
        application { kotobayaModule(AppDeps(db, authenticator = auth.authenticator, handlers = auth.handlers)) }
        suspend fun login(pw: String, xff: String) = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", xff)
            setBody("""{"password":"$pw"}""")
        }.status.value
        // Rotating a spoofed header does not buy fresh tries.
        repeat(5) { assertEquals(401, login("wrong", "198.51.100.$it")) }
        assertEquals(429, login("right", "198.51.100.99"))
        db.close()
    }

    @Test
    fun aCodeUsedJustBeforeARestartCannotBeReplayedAfterIt() {
        val key = "12345678901234567890".toByteArray()
        val t = Instant.ofEpochSecond(1_700_000_015)
        val before = Totp(key)
        val step = before.step(t)
        assertTrue(before.consume(step))
        val restarted = Totp(key, startedAt = t)
        assertFalse(restarted.consume(step), "same step after restart")
        assertFalse(restarted.consume(step - 1))
        assertTrue(restarted.consume(step + 1))
    }

    @Test
    fun argon2MemoryIsCappedAt256MiB() {
        val good = PasswordHasher.hash("x", PasswordHasher.Params(1024, 1, 1))
        PasswordHasher.parse(good.replace("m=1024", "m=262144"))
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse(good.replace("m=1024", "m=262145")) }
    }
}
