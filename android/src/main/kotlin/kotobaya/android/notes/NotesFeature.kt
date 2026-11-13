package kotobaya.android.notes

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.di.FeatureEntry
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncTriggers
import kotobaya.contracts.render.KanjiData
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.koin.dsl.module

/** Note creation over the shared sync store, minter and installed dictionary (T305b). */
val notesModule = module {
    single { EntryMatcher(InstalledCandidateSource(get<InstalledDictionary>())) }
    single {
        val store = get<SqlDelightDeviceStore>()
        DuplicateCheck(
            object : LearningReader {
                override fun <R> read(block: (LearningTables) -> R): R = store.read { block(it.learning) }
            },
            get(),
        )
    }
    single {
        val dict = get<InstalledDictionary>()
        val koin = getKoin()
        NoteCreator<SqlLearningTables>(
            store = get<SqlDelightDeviceStore>(),
            minter = get<OpMinter<SqlLearningTables>>(),
            matcher = get(),
            ids = UuidV7Source(SecureRandom().asKotlinRandom()),
            clock = Clock { System.currentTimeMillis() },
            kanjiData = { literal ->
                dict.kanji(literal)?.let { KanjiData(it.literal, it.meanings, it.onReadings, it.kunReadings, it.strokeCount) }
            },
            afterCreate = { koin.getOrNull<SyncTriggers>()?.localChange() },
        )
    }
}

/** THE registration for featureEntries. */
val notesFeature = FeatureEntry(id = "notes", module = notesModule)
