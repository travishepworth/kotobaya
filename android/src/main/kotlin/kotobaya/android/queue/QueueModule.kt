package kotobaya.android.queue

import kotobaya.android.di.FeatureEntry
import kotobaya.sync.client.store.bookkeeping
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap

/** Process-lifetime cache of dictionary headwords by entry seq (a dictionary update that changes one shows after a restart). */
private val HEADWORDS = named("queueHeadwords")

/** Koin wiring of the queue loader (system clock; tests construct the loader directly). */
val queueFeature = FeatureEntry(
    id = "queue",
    module = module {
        // AND-PERF: one gate for every loader, so home, widget, notifications and review conflate their builds
        single { LoadGate() }
        single(HEADWORDS) { ConcurrentHashMap<Long, String>() }
        single { KanjiSource(get<kotobaya.android.dict.InstalledDictionary>()) }
        factory {
            val koin = getKoin()
            val headwords = get<ConcurrentHashMap<Long, String>>(HEADWORDS)
            LocalQueueLoader(
                get(),
                System::currentTimeMillis,
                // C-27: a deck note has fields {}; its expressionKanji come from the dictionary headword, as the
                // PendingEntry.expressionKanji it was drawn with
                headwordOf = { seq ->
                    headwords[seq] ?: koin.getOrNull<kotobaya.android.dict.InstalledDictionary>()
                        ?.let { d -> runCatching { d.entry(seq)?.headword }.getOrNull() }
                        ?.also { headwords[seq] = it }
                },
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
                        val coordinator = koin.getOrNull<kotobaya.android.sync.SyncCoordinator>() ?: return false
                        val store = koin.getOrNull<kotobaya.android.sync.SqlDelightDeviceStore>() ?: return false
                        val (lastSyncAt, rebuild) = store.read { it.bookkeeping.lastSyncAt to it.bookkeeping.rebuildPending }
                        return materializeAllowed(paired, coordinator.running.value, coordinator.consecutiveFailures, lastSyncAt, rebuild)
                    }
                },
            )
        }
    },
)
