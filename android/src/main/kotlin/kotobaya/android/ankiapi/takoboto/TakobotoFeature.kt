package kotobaya.android.ankiapi.takoboto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.ankiapi.store.AnkiIdMap
import kotobaya.android.ankiapi.store.AnkiIds
import kotobaya.android.ankiapi.store.InstalledEntryLookup
import kotobaya.android.ankiapi.store.SqliteAnkiIdMap
import kotobaya.android.ankiapi.store.TakobotoAnkiStore
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.i18n.T
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.LearningReader
import kotobaya.android.notes.NoteCreator
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Runs [TakobotoAnkiStore.ensureNoteType] once in the background when the app starts. */
class TakobotoStartup(private val ensure: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch { runCatching(ensure) }
    }
}

/** The production AnkiDroid store (T305c) over the shared sync store, minter, note creator and dictionary. */
val takobotoModule = module {
    single<AnkiIdMap> { SqliteAnkiIdMap(androidContext()) }
    single {
        // A Takoboto call can cold-start the process, making our read the database's first use. When the very
        // first use is a SqlDelightDeviceStore transaction the schema is not visible to it (found under
        // Robolectric: "table note_type does not exist" for the whole process), so open it with a plain query first.
        runCatching { get<AppDatabase>().syncOpsQueries.getSyncState(SyncStateKeys.DEVICE_ID).executeAsOneOrNull() }
        val store = get<SqlDelightDeviceStore>()
        val minter = get<OpMinter<SqlLearningTables>>()
        val ids = UuidV7Source(SecureRandom().asKotlinRandom())
        val clock = Clock { System.currentTimeMillis() }
        TakobotoAnkiStore<SqlLearningTables>(
            store = store,
            reader = object : LearningReader {
                override fun <R> read(block: (LearningTables) -> R): R = store.read { block(it.learning) }
            },
            minter = minter,
            noteTypes = NoteTypeEnsurer(store, minter, ids, clock),
            creator = get<NoteCreator<SqlLearningTables>>(),
            duplicates = get<DuplicateCheck>(),
            map = get(),
            entries = InstalledEntryLookup(get<InstalledDictionary>()),
            ids = ids,
            clock = clock,
        )
    }
    single<AnkiStore> { get<TakobotoAnkiStore<SqlLearningTables>>() }
    single(named("takobotoStartup"), createdAtStart = true) {
        val koin = getKoin()
        TakobotoStartup { koin.get<TakobotoAnkiStore<SqlLearningTables>>().ensureNoteType() }.also { it.start() }
    }
}

/** Debug section: counts of notes added, whether the note type exists, unknown calls and the last provider calls. */
@Composable
private fun TakobotoDebug() {
    val map: AnkiIdMap = koinInject()
    val store: SqlDelightDeviceStore = koinInject()
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot = remember(refresh) { ProviderCallLog.snapshot() }
    val counts = remember(refresh) {
        Triple(map.counter(AnkiIds.COUNT_ADDED), map.counter(AnkiIds.COUNT_DUPLICATE), map.counter(AnkiIds.COUNT_REFUSED))
    }
    val ready = remember(refresh) {
        runCatching {
            store.read { tx ->
                tx.learning.all(LearningTable.NOTE_TYPE).any {
                    it["name"] == kotobaya.contracts.sync.JsonString(TakobotoVocab.NAME) &&
                        (it["deleted_at"] ?: kotobaya.contracts.sync.JsonNull) == kotobaya.contracts.sync.JsonNull
                }
            }
        }.getOrDefault(false)
    }
    val unknown = snapshot.filter { ProviderCallLog.UNKNOWN_MARK in it }
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.ankiapi_counts, counts.first, counts.second, counts.third))
        Text(T(if (ready) R.string.ankiapi_note_type_ready else R.string.ankiapi_note_type_missing))
        Text(T(R.string.ankiapi_unknown_title, unknown.size), style = MaterialTheme.typography.titleSmall)
        if (unknown.isEmpty()) Text(T(R.string.ankiapi_none))
        unknown.takeLast(DEBUG_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(T(R.string.ankiapi_recent_title), style = MaterialTheme.typography.titleSmall)
        if (snapshot.isEmpty()) Text(T(R.string.ankiapi_none))
        snapshot.takeLast(DEBUG_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { refresh++ }) { Text(T(R.string.ankiapi_refresh)) }
    }
}

private const val DEBUG_LINES = 30

/** THE registration for featureEntries. */
val takobotoFeature = FeatureEntry(
    id = "takoboto",
    module = takobotoModule,
    settingsSections = listOf(SettingsSection("takoboto", R.string.ankiapi_section_title) { TakobotoDebug() }),
)
