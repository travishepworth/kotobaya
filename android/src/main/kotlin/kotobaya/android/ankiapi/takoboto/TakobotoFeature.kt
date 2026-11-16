package kotobaya.android.ankiapi.takoboto

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
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.LearningReader
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.APP_DB_DRIVER
import kotobaya.android.notes.NoteChangeLog
import kotobaya.android.notes.NoteWordIndex
import kotobaya.android.ankiapi.store.ChecksumIndex
import app.cash.sqldelight.db.SqlDriver
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
        val entries = InstalledEntryLookup(get<InstalledDictionary>())
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
            entries = entries,
            ids = ids,
            clock = clock,
            words = get<NoteWordIndex>(),
            csums = ChecksumIndex(get<SqlDriver>(APP_DB_DRIVER), get<NoteChangeLog>(), entries),
        )
    }
    // SHELL-A7: notes added by Takoboto / AnkiProvider callers reach the SPA as `note.created`
    single<AnkiStore> {
        val store = get<TakobotoAnkiStore<SqlLearningTables>>()
        val pairing = get<kotobaya.android.pairing.PairingRepository>()
        kotobaya.android.shell.events.EventingAnkiStore(store, store::add, get<kotobaya.android.shell.events.ShellEvents>()) { pairing.deviceId }
    }
    single(named("takobotoStartup"), createdAtStart = true) {
        val koin = getKoin()
        TakobotoStartup {
            val s = koin.get<TakobotoAnkiStore<SqlLearningTables>>()
            s.ensureNoteType()
            s.warmIndexes() // AND-TAKO-PERF2: build/catch up the lookup indexes before Takoboto asks
        }.also { it.start() }
    }
}

/** THE registration for featureEntries. */
val takobotoFeature = FeatureEntry(
    id = "takoboto",
    module = takobotoModule,
)
