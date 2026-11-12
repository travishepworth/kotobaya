package kotobaya.android

import kotobaya.core.coreVersion
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun androidSeesCore() {
        assertTrue(coreVersion().startsWith("kotobaya-core"))
    }
}
