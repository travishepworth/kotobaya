package kotobaya.android.shell.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SHELL-L9: the L-7 lookup is wired into the plain-JVM device (JDBC) with the fixture's release as the server's. */
class ParityDictLookupTest {
    @Test
    fun theFixturePackageIsAuthoritativeAndPresenceRunsOverJdbc() {
        Class.forName("org.sqlite.JDBC") // DriverManager may not have loaded it yet when this test runs alone
        val d = ParityDevice("00000000-0000-7000-8000-0000000000d1", 1_700_000_000_000L, emptyList())
        assertEquals(ParityDictionary.fixture.releaseId, d.gapCache.serverReleaseId())
        assertTrue(d.dictLookup.isAuthoritative())
        val seq = ParityDictionary.fixture.seqs.first()
        assertEquals(setOf(seq), JdbcPackageSource(d.dictStore.installed).presentEntries(listOf(seq, -1L)))
    }
}
