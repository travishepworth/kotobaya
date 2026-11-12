package kotobaya.android.di

import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.allRoutes
import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureRegistryTest {
    @Test
    fun registeringAFeatureAddsItsRoute() {
        val fake = FeatureEntry(id = "fake", routes = listOf(NavRoute("fake/detail", 0) {}))
        val routes = allRoutes(FeatureRegistry(listOf(fake))).map { it.route }
        assertTrue("fake/detail" in routes)
        assertTrue("home" in routes)
    }
}
