package kotobaya.android.notes

import app.cash.sqldelight.db.SqlDriver
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
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The app database driver the sync store writes through (syncModule's `SYNC_DRIVER`); note indexes live in that file. */
val APP_DB_DRIVER = named("syncAppDriver")

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
    // AND-TAKO-PERF2: device-only note change feed and the duplicate word rule's index
    single { NoteChangeLog(get<SqlDriver>(APP_DB_DRIVER)) }
    single { NoteWordIndex(get<SqlDriver>(APP_DB_DRIVER), get()) }
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
            words = get(),
        )
    }
}

/** THE registration for featureEntries. */
val notesFeature = FeatureEntry(id = "notes", module = notesModule)
