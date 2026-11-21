package kotobaya.android.queue

import kotobaya.android.di.FeatureEntry
import kotobaya.sync.client.store.bookkeeping
import org.koin.dsl.module

/** Koin wiring of the queue loader (system clock; tests construct the loader directly). */
val queueFeature = FeatureEntry(
    id = "queue",
    module = module {
        // AND-PERF: one gate for every loader, so home, widget, notifications and review conflate their builds
        single { LoadGate() }
        // PERF-LF: one process-wide cache of dictionary headwords by entry seq (also the shell's queue and summary)
        single {
            val koin = getKoin()
            fun dict() = koin.getOrNull<kotobaya.android.dict.InstalledDictionary>()
            HeadwordCache(
                generation = { dict()?.let { d -> runCatching { d.generation }.getOrNull() } ?: 0L },
                lookup = { seq -> dict()?.let { d -> runCatching { d.entry(seq)?.headword }.getOrNull() } },
                batch = { seqs -> dict()?.let { d -> runCatching { d.headwords(seqs) }.getOrNull() }.orEmpty() },
            )
        }
        single { KanjiSource(get<kotobaya.android.dict.InstalledDictionary>()) }
        factory {
            val koin = getKoin()
            val headwords = get<HeadwordCache>()
            LocalQueueLoader(
                get(),
                System::currentTimeMillis,
                // C-27: a deck note has fields {}; its expressionKanji come from the dictionary headword, as the
                // PendingEntry.expressionKanji it was drawn with
                headwordOf = headwords::headword,
                prefetchHeadwords = headwords::prefetch,
                awaitReady = {
                    // readers wait for the startup replay (SYNC.md 10.5); the sync module may be absent in tests
                    koin.getOrNull<kotobaya.android.sync.SyncBootstrap>()?.ready?.takeUnless { it.isCompleted }
                        ?.let { kotlinx.coroutines.runBlocking { it.await() } }
                },
                gate = get(),
                // ADR-042: the dictionary facts, cached for the life of the process
                kanji = runCatching { koin.get<KanjiSource>() }.getOrDefault(KanjiSource.NONE),
                materializer = object : EntryMaterializer {
                    // resolved per call: the notes and sync modules may be absent (tests) and are wired after this one
                    private fun notes() = koin.getOrNull<EntryMaterializer>()

                    override fun materialize(kanji: List<String>, entries: List<kotobaya.contracts.queue.PendingEntry>): Set<String> =
                        notes()?.materialize(kanji, entries) ?: emptySet()

                    override fun kanjiKinds(literals: Collection<String>): Map<String, List<String>> =
                        notes()?.kanjiKinds(literals) ?: super.kanjiKinds(literals)

                    override fun plan(entries: List<EntryRef>, burySiblings: Boolean): Map<EntryRef, EntryPlan> =
                        notes()?.plan(entries, burySiblings) ?: emptyMap()

                    override fun ready(): Boolean {
                        if (notes()?.ready() != true) return false
                        val pairing = koin.getOrNull<kotobaya.android.pairing.PairingRepository>() ?: return true
                        val paired = pairing.isPaired
                        if (!paired) return true
                        val store = koin.getOrNull<kotobaya.android.sync.SqlDelightDeviceStore>() ?: return false
                        val (lastSyncAt, rebuild) = store.read { it.bookkeeping.lastSyncAt to it.bookkeeping.rebuildPending }
                        return materializeAllowed(paired, lastSyncAt, rebuild)
                    }
                },
            )
        }
    },
)
