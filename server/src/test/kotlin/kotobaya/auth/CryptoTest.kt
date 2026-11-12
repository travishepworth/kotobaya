package kotobaya.auth

import kotobaya.server.ConfigException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun tokenStringsMatchTheContractFormat() {
        for (kind in TokenKind.entries) {
            val t = Tokens.generate(kind)
            assertTrue(Regex("^${kind.prefix}[A-Za-z0-9]{40}$").matches(t), t)
            assertTrue(Tokens.isWellFormed(t))
            assertEquals(t.take(8), Tokens.prefix(t))
        }
        assertNotEquals(Tokens.generate(TokenKind.SESSION), Tokens.generate(TokenKind.SESSION))
        assertFalse(Tokens.isWellFormed("kbx_" + "a".repeat(40)))
        assertFalse(Tokens.isWellFormed("kbs_" + "a".repeat(39)))
        assertFalse(Tokens.isWellFormed("kbs_" + "a".repeat(39) + "-"))
    }

    @Test
    fun tokenHashIsLowercaseHexSha256OfTheWholeString() {
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Tokens.hash("abc"))
    }

    @Test
    fun argon2idRoundTripAndWrongPassword() {
        val phc = PasswordHasher.hash("correct horse", PasswordHasher.Params(memoryKiB = 1024, iterations = 1, parallelism = 1))
        assertTrue(phc.startsWith("\$argon2id\$v=19\$m=1024,t=1,p=1\$"), phc)
        val parsed = PasswordHasher.parse(phc)
        assertTrue(PasswordHasher.verify("correct horse", parsed))
        assertFalse(PasswordHasher.verify("correct horsE", parsed))
        assertFalse(PasswordHasher.verify("", parsed))
        assertNotEquals(phc, PasswordHasher.hash("correct horse", PasswordHasher.Params(1024, 1, 1)), "salted")
    }

    @Test
    fun argon2DefaultsAreRfc9106SecondOption() {
        assertEquals(PasswordHasher.Params(65_536, 3, 4), PasswordHasher.DEFAULT)
    }

    @Test
    fun phcParserRejectsOtherAlgorithmsAndAbsurdParameters() {
        val good = PasswordHasher.hash("x", PasswordHasher.Params(1024, 1, 1))
        PasswordHasher.parse(good)
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse(good.replace("argon2id", "argon2i")) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse(good.replace("v=19", "v=16")) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse(good.replace("m=1024", "m=99999999")) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse(good.replace("t=1", "t=1000")) }
        assertFailsWith<IllegalArgumentException> { PasswordHasher.parse("plaintext") }
    }

    // RFC 6238 Appendix B, SHA-1 rows (8 digits); the key is ASCII "12345678901234567890".
    private val rfcKey = "12345678901234567890".toByteArray()

    @Test
    fun totpMatchesRfc6238Vectors() {
        val totp = Totp(rfcKey, digits = 8)
        val vectors = mapOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130",
        )
        for ((t, code) in vectors) assertEquals(code, totp.codeAt(totp.step(Instant.ofEpochSecond(t))), "T=$t")
        // 6-digit codes are the last 6 digits.
        assertEquals("287082", Totp(rfcKey).codeAt(1))
    }

    @Test
    fun totpAcceptsCurrentAndPreviousStepOnlyDrift() {
        val totp = Totp(rfcKey)
        val now = Instant.ofEpochSecond(1_700_000_015) // mid-step
        val cur = totp.step(now)
        assertEquals(cur, totp.matchingStep(totp.codeAt(cur), now))
        assertEquals(cur - 1, totp.matchingStep(totp.codeAt(cur - 1), now))
        assertNull(totp.matchingStep(totp.codeAt(cur - 2), now), "two steps old is expired")
        assertNull(totp.matchingStep(totp.codeAt(cur + 1), now), "future step is not accepted")
    }

    @Test
    fun totpCodeIsAcceptedOnlyOnce() {
        val totp = Totp(rfcKey)
        assertTrue(totp.consume(100))
        assertFalse(totp.consume(100), "replay")
        assertFalse(totp.consume(99), "an older step after a newer one was used")
        assertTrue(totp.consume(101))
    }

    @Test
    fun base32DecodesAuthenticatorSecrets() {
        assertTrue(rfcKey.contentEquals(Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")))
        assertTrue(rfcKey.contentEquals(Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")))
        assertFailsWith<IllegalArgumentException> { Totp.decodeBase32("not base32!") }
    }

    @Test
    fun authConfigComesFromEnvAndFailsLoudly() {
        val phc = PasswordHasher.hash("pw", PasswordHasher.Params(1024, 1, 1))
        val c = AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc))
        assertFalse(c.totpEnabled)
        assertTrue(AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TOTP_SECRET" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).totpEnabled)
        assertFalse(AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TOTP_SECRET" to " ")).totpEnabled)
        assertFailsWith<ConfigException> { AuthConfig.fromEnv(emptyMap()) }
        assertFailsWith<ConfigException> { AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to "hunter2")) }
        assertFailsWith<ConfigException> { AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TOTP_SECRET" to "GEZDGNBV")) }
        assertFailsWith<ConfigException> { AuthConfig.fromEnv(mapOf("AUTH_PASSWORD_HASH" to phc, "AUTH_TOTP_SECRET" to "1!")) }
    }

    @Test
    fun rateLimiterPerAddressTriggersAndRecovers() {
        val l = LoginRateLimiter(perAddress = 5, global = 100, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-11-12T00:00:00Z")
        repeat(5) { assertNull(l.begin("a", t0.plusSeconds(it.toLong()))) }
        assertEquals(15 * 60L - 4, l.begin("a", t0.plusSeconds(4)))
        assertNull(l.begin("b", t0.plusSeconds(5)), "other addresses are unaffected")
        assertEquals(1L, l.begin("a", t0.plusSeconds(15 * 60 - 1)))
        assertNull(l.begin("a", t0.plusSeconds(15 * 60)), "first failure left the window")
    }

    @Test
    fun rateLimiterSuccessClearsAndUncountedIsForgotten() {
        val l = LoginRateLimiter(perAddress = 2, global = 100)
        val t = Instant.parse("2026-11-12T00:00:00Z")
        assertNull(l.begin("a", t))
        l.uncounted("a", t)
        assertNull(l.begin("a", t))
        assertNull(l.begin("a", t))
        assertTrue(l.begin("a", t) != null)
        val l2 = LoginRateLimiter(perAddress = 2, global = 100)
        assertNull(l2.begin("a", t))
        assertNull(l2.begin("a", t))
        l2.succeeded("a", t)
        assertNull(l2.begin("a", t))
    }

    @Test
    fun rateLimiterGlobalLimitSpansAddresses() {
        val l = LoginRateLimiter(perAddress = 5, global = 3)
        val t = Instant.parse("2026-11-12T00:00:00Z")
        assertNull(l.begin("a", t))
        assertNull(l.begin("b", t))
        assertNull(l.begin("c", t))
        assertTrue(l.begin("d", t.plusSeconds(60)) != null)
        assertNull(l.begin("d", t.plus(Duration.ofMinutes(15))))
    }
}
