package kotobaya.android.shell.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SHELL-A4 acceptance: SHELL.md 6.2 native side. */
class BackProtocolTest {
    private val sent = mutableListOf<String>()
    private var fallbacks = 0
    private var moves = 0
    private var n = 0
    private var scheduled: (() -> Unit)? = null
    private var cancelled = 0
    private var transportUp = true

    private val p = BackProtocol(
        sendBack = { id -> if (transportUp) sent += id; transportUp },
        timer = BackTimer { ms, block ->
            assertEquals(300, ms)
            scheduled = block
            ({ scheduled = null; cancelled++ })
        },
        fallback = { fallbacks++ },
        moveToBack = { moves++ },
        newId = { "id${++n}" },
    )

    @Test
    fun handledTrueConsumesTheBackPress() {
        p.onBackState(true)
        p.onBackPressed()
        assertEquals(listOf("id1"), sent)
        p.onBackResult("id1", true)
        assertEquals(0, fallbacks)
        assertEquals(0, moves)
        assertEquals(1, cancelled)
        assertEquals(null, scheduled)
    }

    @Test
    fun handledFalseLeavesTheApp() {
        p.onBackState(true)
        p.onBackPressed()
        p.onBackResult("id1", false)
        assertEquals(1, moves)
        assertEquals(0, fallbacks)
    }

    @Test
    fun noAnswerWithin300msUsesTheFallbackAndALateAnswerIsIgnored() {
        p.onBackState(true)
        p.onBackPressed()
        scheduled?.invoke()
        assertEquals(1, fallbacks)
        p.onBackResult("id1", false)
        p.onBackResult("id1", true)
        assertEquals(0, moves)
        assertEquals(1, fallbacks)
    }

    @Test
    fun secondPressWhilePendingUsesTheFallbackAtOnce() {
        p.onBackState(true)
        p.onBackPressed()
        p.onBackPressed()
        assertEquals(1, fallbacks)
        assertEquals(listOf("id1"), sent)
        p.onBackResult("id1", false) // the first one is forgotten
        assertEquals(0, moves)
    }

    @Test
    fun wrongIdIsIgnored() {
        p.onBackState(true)
        p.onBackPressed()
        p.onBackResult("other", false)
        assertEquals(0, moves)
        assertTrue(scheduled != null)
    }

    @Test
    fun unsendableBackFallsBack() {
        transportUp = false
        p.onBackState(true)
        p.onBackPressed()
        assertEquals(1, fallbacks)
    }

    @Test
    fun enabledOnlyWhileCanHandleAndResetByPageStart() {
        val changes = mutableListOf<Boolean>()
        val q = BackProtocol({ true }, { _, _ -> {} }, {}, {}, { "x" }, { changes += it })
        assertFalse(q.enabled)
        q.onBackState(true)
        q.onBackState(true)
        q.onBackState(false)
        q.onBackState(true)
        q.onPageStarted()
        assertEquals(listOf(true, false, true, false), changes)
        assertFalse(q.enabled)
    }
}
