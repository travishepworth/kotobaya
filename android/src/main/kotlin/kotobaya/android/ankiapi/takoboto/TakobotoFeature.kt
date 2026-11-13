package kotobaya.android.ankiapi.takoboto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.ankiapi.takoboto.ui.TakobotoDebug
import org.koin.compose.getKoin
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
/** BUG-8: both databases are read on IO, never in composition (the sync writer may hold the app one). */
private fun loadDebug(map: AnkiIdMap, store: SqlDelightDeviceStore): TakobotoDebugUiState {
    val ready = runCatching {
        store.read { tx ->
            tx.learning.all(LearningTable.NOTE_TYPE).any {
                it["name"] == kotobaya.contracts.sync.JsonString(TakobotoVocab.NAME) &&
                    (it["deleted_at"] ?: kotobaya.contracts.sync.JsonNull) == kotobaya.contracts.sync.JsonNull
            }
        }
    }.getOrDefault(false)
    return TakobotoDebugUiState(
        calls = ProviderCallLog.snapshot(),
        added = map.counter(AnkiIds.COUNT_ADDED),
        duplicates = map.counter(AnkiIds.COUNT_DUPLICATE),
        refused = map.counter(AnkiIds.COUNT_REFUSED),
        noteTypeReady = ready,
    )
}

@Composable
private fun TakobotoDebugRoute() {
    val koin = getKoin()
    val vm: TakobotoDebugViewModel = viewModel {
        val map = koin.get<AnkiIdMap>()
        val store = koin.get<SqlDelightDeviceStore>()
        TakobotoDebugViewModel({ loadDebug(map, store) })
    }
    val state by vm.state.collectAsState()
    TakobotoDebug(state, vm)
}

/** THE registration for featureEntries. */
val takobotoFeature = FeatureEntry(
    id = "takoboto",
    module = takobotoModule,
    settingsSections = listOf(SettingsSection("takoboto", R.string.ankiapi_section_title) { TakobotoDebugRoute() }),
)
