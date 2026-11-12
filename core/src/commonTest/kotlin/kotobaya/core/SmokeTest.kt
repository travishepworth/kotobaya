package kotobaya.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun coreVersionIsStable() {
        assertEquals("kotobaya-core-0", coreVersion())
    }
}
