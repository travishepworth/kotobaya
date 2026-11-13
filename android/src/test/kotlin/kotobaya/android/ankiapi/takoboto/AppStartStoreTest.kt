package kotobaya.android.ankiapi.takoboto

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiProvider
import kotobaya.android.ankiapi.provider.AnkiStoreHolder
import kotobaya.android.ankiapi.store.DeferredAnkiStore
import kotobaya.android.app.KotobayaApp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** T305a review carry-over: the app registers the real store eagerly in onCreate; a provider call reaches it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = KotobayaApp::class, sdk = [34])
class AppStartStoreTest {
    @After
    fun tearDown() {
        AnkiStoreHolder.reset()
        stopKoin()
    }

    @Test
    fun aProviderCallAfterAppStartReachesTheRealStore() {
        val app: Application = ApplicationProvider.getApplicationContext()
        AnkiStoreHolder.waitMillis = 0 // registered already: no waiting
        assertIs<DeferredAnkiStore>(AnkiStoreHolder.await())
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY)
        app.contentResolver.query(AnkiContract.MODELS_URI, arrayOf("name"), null, null, null)!!.use { c ->
            val names = buildList { while (c.moveToNext()) add(c.getString(0)) }
            assertEquals(listOf(TakobotoVocab.NAME), names)
        }
    }
}
