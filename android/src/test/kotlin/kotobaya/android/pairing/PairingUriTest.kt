package kotobaya.android.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingUriTest {
    @Test
    fun validUri() {
        val t = PairingUri.parse("kotobaya://pair?server=https%3A%2F%2Fkotobaya.example.net%2Fapi%2Fv1&code=K7Q2M9XD")
        assertEquals(PairingTarget("https://kotobaya.example.net/api/v1", "K7Q2M9XD"), t)
    }

    @Test
    fun missingCode() {
        assertNull(PairingUri.parse("kotobaya://pair?server=https%3A%2F%2Fx.example%2Fapi%2Fv1"))
        assertNull(PairingUri.parse("kotobaya://pair?server=https%3A%2F%2Fx.example%2Fapi%2Fv1&code="))
    }

    @Test
    fun wrongScheme() {
        assertNull(PairingUri.parse("https://pair?server=https%3A%2F%2Fx.example&code=K7Q2M9XD"))
        assertNull(PairingUri.parse("kotobaya://add?word=x&code=K7Q2M9XD"))
        assertNull(PairingUri.parse("kotobaya://pair?server=ftp%3A%2F%2Fx&code=K7Q2M9XD"))
        assertNull(PairingUri.parse("kotobaya://pair?server=%ZZ&code=K7Q2M9XD"))
    }

    @Test
    fun manualEntryNormalisation() {
        assertEquals("https://h.example/api/v1", PairingUri.normalizeServerUrl("https://h.example/"))
        assertEquals("https://h.example/api/v1", PairingUri.normalizeServerUrl("https://h.example/api/v1"))
        assertNull(PairingUri.normalizeServerUrl("h.example"))
        assertEquals("K7Q2M9XD", PairingUri.normalizeCode("k7q2-m9xd "))
    }
}
