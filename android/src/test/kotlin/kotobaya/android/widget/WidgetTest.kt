package kotobaya.android.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.db.openAppDatabase
import kotobaya.android.queue.LocalQueueLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WidgetTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private lateinit var sql: SQLiteDatabase
    private lateinit var data: WidgetData

    @Before
    fun setUp() {
        val db = openAppDatabase(context)
        db.localQueueQueries.liveDecks().executeAsList() // forces schema creation on the file
        sql = SQLiteDatabase.openDatabase(context.getDatabasePath(APP_DB_FILE).path, null, SQLiteDatabase.OPEN_READWRITE)
        data = WidgetData(LocalQueueLoader(db, { now }), SqliteWidgetSource(context))
    }

    @After
    fun tearDown() = sql.close()

    private fun uuid(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)

    private fun addVocab(n: Int, entrySeq: Long, expression: String) = sql.execSQL(
        "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) VALUES " +
            "('${uuid(1, n)}', '00000000-0000-7000-8000-000000000101', 'vocab', $entrySeq, " +
            "'{\"expression\":\"$expression\",\"reading\":\"r$n\",\"meaning\":\"m$n\"}', 'dev', $n, $n)",
    )

    private fun addCard(n: Int, noteN: Int, state: String, due: Long?, lapses: Int = 0) = sql.execSQL(
        "INSERT INTO card (id, note_id, kind, state, due_at, lapses, created_at, updated_at) VALUES " +
            "('${uuid(2, n)}', '${uuid(1, noteN)}', 'meaning', '$state', ${due ?: "NULL"}, $lapses, 0, 0)",
    )

    private fun fixture() {
        for (i in 1..4) addVocab(i, 100L + i, "w$i")
        addCard(1, 1, "review", now - hour, lapses = 3) // overdue: bucket 0
        addCard(2, 2, "review", now - 1000, lapses = 1) // overdue: bucket 0
        addCard(3, 3, "review", now + 2 * hour + 5, lapses = 0) // bucket 2
        addCard(4, 4, "new", null)
        sql.execSQL(
            "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating) VALUES " +
                "('${uuid(4, 1)}', '${uuid(2, 1)}', 'd', 'h1', ${now - 1000}, 'scheduled', 3)",
        )
    }

    @Test
    fun snapshotHasCountsForecastProgressAndHotWord() {
        fixture()
        val s = data.snapshot()
        assertEquals(3, s.due) // the loader counts a card due later in the study day too
        assertEquals(1, s.new)
        assertEquals(2, s.forecast[0])
        assertEquals(1, s.forecast[2])
        assertEquals(24, s.forecast.size)
        assertEquals(1, s.doneToday)
        assertEquals(0.2f, s.progress) // 1 done of 1 + 3 + 1
        assertTrue(s.hotWord?.expression in setOf("w1", "w2")) // the lapsed words; the day picks one
        assertEquals(false, s.isEmpty)
    }

    @Test
    fun emptyDatabaseIsTheEmptyState() {
        val s = data.snapshot()
        assertEquals(0, s.due)
        assertEquals(0, s.new)
        assertEquals(List(24) { 0 }, s.forecast)
        assertEquals(1f, s.progress)
        assertNull(s.hotWord)
        assertTrue(s.isEmpty)
    }

    @Test
    fun hotWordRotationIsDeterministicPerDayAndAdvancesDaily() {
        val words = (1..3).map { HotWord(it.toLong(), "w$it", "r", "m") }
        val day = 1_800_000_000_000L
        val pick = WidgetData.pickHotWord(words, day)
        assertEquals(pick, WidgetData.pickHotWord(words, day))
        assertEquals(pick, WidgetData.pickHotWord(words, day + 3_600_000L)) // same study day start => same word
        val seen = (0 until 3).map { WidgetData.pickHotWord(words, day + it * 86_400_000L) }.toSet()
        assertEquals(words.toSet(), seen)
        assertNull(WidgetData.pickHotWord(emptyList(), day))
    }

    @Test
    fun deletedNotesAndNeverLapsedWordsAreNotHot() {
        addVocab(1, 101, "w1")
        addCard(1, 1, "review", now + hour, lapses = 0)
        assertTrue(SqliteWidgetSource(context).hotCandidates().isEmpty())
        sql.execSQL("UPDATE card SET lapses = 2")
        assertEquals(1, SqliteWidgetSource(context).hotCandidates().size)
        sql.execSQL("UPDATE note SET deleted_at = 1")
        assertTrue(SqliteWidgetSource(context).hotCandidates().isEmpty())
    }

    private val sizes = listOf(DpSize(110.dp, 40.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp))

    @Test
    fun threeSizesComposeWithData() = runBlocking {
        val s = WidgetSnapshot(3, 2, List(24) { it % 5 }, 4, HotWord(7, "猫", "ねこ", "cat"))
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(sizes[0])
            provideComposable { WidgetContent(s, sizes[0]) }
            onNode(hasText("3 due · 2 new")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(sizes[1])
            provideComposable { WidgetContent(s, sizes[1]) }
            onNode(hasText("Today: 4 of 9")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(sizes[2])
            provideComposable { WidgetContent(s, sizes[2]) }
            onNode(hasText("猫")).assertExists()
        }
    }

    @Test
    fun threeSizesComposeOnAnEmptyDatabase() = runBlocking {
        for (size in sizes) {
            runGlanceAppWidgetUnitTest {
                setContext(context)
                setAppWidgetSize(size)
                provideComposable { WidgetContent(data.snapshot(), size) }
                onNode(hasText("Nothing due")).assertExists()
            }
        }
    }

    @Test
    fun forecastRowNeverExceedsTenChildren() {
        for (n in listOf(0, 1, 7, 8, 9, 24, 48, 100)) {
            val bars = forecastBars(List(n) { 1 })
            assertTrue(bars.size <= 10, "n=$n gave ${bars.size} bars")
            assertEquals(n, bars.sum())
        }
    }

    @Test
    fun tileLaunchesTheReviewIntent() {
        val service = Robolectric.buildService(ReviewTileService::class.java).create().get()
        var launched: Intent? = null
        service.launcher = { launched = it }
        service.onClick()
        val i = assertNotNull(launched)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("kotobaya://review", i.data.toString())
        assertEquals("kotobaya.android.MainActivity", i.component?.className)
    }
}
