package kotobaya.android.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.R
import kotobaya.android.ankiapi.takoboto.TakobotoDebugUiState
import kotobaya.android.ankiapi.takoboto.ui.TakobotoDebug
import kotobaya.android.ankiconnect.AnkiConnectSettingsIntents
import kotobaya.android.ankiconnect.AnkiConnectSettingsUiState
import kotobaya.android.ankiconnect.AnkiConnectStatus
import kotobaya.android.ankiconnect.ui.AnkiConnectSettings
import kotobaya.android.decks.DecksViewModel
import kotobaya.android.decks.ui.DecksScreen
import kotobaya.android.demo.ui.DemoSection
import kotobaya.android.di.SettingsSection
import kotobaya.android.dict.DictSettingsIntents
import kotobaya.android.dict.DictSettingsUiState
import kotobaya.android.dict.InstallState
import kotobaya.android.dict.ui.DictSettingsSection
import kotobaya.android.grammar.GrammarCaptureIntents
import kotobaya.android.grammar.GrammarCaptureUiState
import kotobaya.android.grammar.ui.CaptureEntrySlot
import kotobaya.android.grammar.ui.GrammarCaptureEntry
import kotobaya.android.grammar.ui.NoCaptureEntry
import kotobaya.android.intake.DeckOption
import kotobaya.android.intake.IntakeIntents
import kotobaya.android.intake.IntakeUiState
import kotobaya.android.intake.ui.IntakeSheet
import kotobaya.android.media.MediaSettingsIntents
import kotobaya.android.media.MediaSettingsUiState
import kotobaya.android.media.StorageBreakdown
import kotobaya.android.media.browse.MediaBrowseViewModel
import kotobaya.android.media.browse.ui.MediaBrowseScreen
import kotobaya.android.media.ui.MediaSettingsSection
import kotobaya.android.notify.NotifySettingsIntents
import kotobaya.android.notify.NotifySettingsUiState
import kotobaya.android.notify.ui.NotificationRationaleScreen
import kotobaya.android.notify.ui.NotifySettings
import kotobaya.android.pairing.DevicesViewModel
import kotobaya.android.pairing.ui.DevicesSection
import kotobaya.android.push.PushSettingsIntents
import kotobaya.android.push.PushSettingsUiState
import kotobaya.android.push.PushUi
import kotobaya.android.push.ui.PushSettings
import kotobaya.android.review.ReviewViewModel
import kotobaya.android.review.ui.ReviewScreen
import kotobaya.android.search.SearchMode
import kotobaya.android.search.SearchViewModel
import kotobaya.android.search.ui.SearchScreen
import kotobaya.android.settings.AboutUiState
import kotobaya.android.settings.StudyKey
import kotobaya.android.settings.StudySettingsIntents
import kotobaya.android.settings.StudySettingsUiState
import kotobaya.android.settings.ui.AboutSection
import kotobaya.android.settings.ui.SettingsScreen
import kotobaya.android.settings.ui.StudySection
import kotobaya.android.struggle.StrugglePage
import kotobaya.android.struggle.StruggleViewModel
import kotobaya.android.struggle.ui.StruggleScreen
import kotobaya.android.sync.SyncFlowIntents
import kotobaya.android.sync.SyncFlowViewModel
import kotobaya.android.sync.SyncPage
import kotobaya.android.sync.ui.SyncChipWithFlow
import kotobaya.android.sync.ui.SyncFlow
import kotobaya.android.sync.ui.SyncSettingsSection
import kotobaya.android.ui.HomeScreen
import kotobaya.android.ui.KotobayaTheme

/** One screen (or screen state) of the catalogue: [name] is a stable file-name-safe id. */
class DemoScreen(val name: String, val content: @Composable () -> Unit)

/**
 * UI-FOUND-APP: every screen of the app in its demo state, for the Compose previews and the Roborazzi screenshot
 * matrix. The real ViewModels run over the demo ports with an immediate dispatcher, so what shows is what the app
 * shows in demo mode, without Koin or a database. Screens whose data is only local UI state (settings sections that
 * read SharedPreferences) get a fixed UiState.
 */
object DemoScreens {
    /** Dispatches inline: every demo port answers at once, so the first frame shows the loaded state. */
    private val now: CoroutineDispatcher = Dispatchers.Unconfined

    private val noCapture: CaptureEntrySlot = NoCaptureEntry

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        KotobayaTheme { Surface(Modifier.fillMaxSize()) { content() } }
    }

    private fun screen(name: String, content: @Composable () -> Unit) = DemoScreen(name) { Frame(content) }

    @Composable
    private fun <V> rememberVm(vararg keys: Any?, create: () -> V): V = remember(*keys) { create() }

    /** The screens of [data]'s scenario; the empty scenario skips detail pages that need an item. */
    fun all(data: DemoData): List<DemoScreen> = buildList {
        val store by lazy { DemoStore(data) }
        val firstDeck = data.decks.firstOrNull()?.optString("id")
        val firstMedia = data.media.firstOrNull()
        val longWord = data.entries.firstOrNull { it.optString("headword") == "取る" }?.optLong("seq")
        val noEntryNote = data.notes.firstOrNull { it.isNull("entry_seq") }?.optString("id")

        add(screen("home") { HomeWithChip(data, unpaired = false) })
        add(screen("home-unpaired") { HomeWithChip(data, unpaired = true) })

        add(screen("review-front") { Review(store, flip = false) })
        add(screen("review-back") { Review(store, flip = true) })

        add(
            screen("search-words") {
                val vm = rememberVm { SearchViewModel(DemoSearchPort(data), now).apply { setQuery("to"); search() } }
                val s by vm.state.collectAsState()
                SearchScreen(s, vm)
            },
        )
        add(
            screen("search-cards") {
                val vm = rememberVm { SearchViewModel(DemoSearchPort(data), now).apply { setMode(SearchMode.CARDS); search() } }
                val s by vm.state.collectAsState()
                SearchScreen(s, vm)
            },
        )
        if (longWord != null) {
            add(
                screen("word-detail") {
                    val vm = rememberVm { SearchViewModel(DemoSearchPort(data), now).apply { openWord(longWord) } }
                    val s by vm.state.collectAsState()
                    SearchScreen(s, vm)
                },
            )
        }
        if (noEntryNote != null) {
            add(
                screen("note-detail") {
                    val vm = rememberVm { SearchViewModel(DemoSearchPort(data), now).apply { openNote(noEntryNote) } }
                    val s by vm.state.collectAsState()
                    SearchScreen(s, vm)
                },
            )
        }

        add(
            screen("decks") {
                val vm = rememberVm { DecksViewModel(DemoDeckSource(data), null, now) }
                val s by vm.state.collectAsState()
                DecksScreen(s, vm)
            },
        )
        if (firstDeck != null) {
            add(
                screen("deck-detail") {
                    val vm = rememberVm { DecksViewModel(DemoDeckSource(data), null, now).apply { open(firstDeck) } }
                    val s by vm.state.collectAsState()
                    DecksScreen(s, vm)
                },
            )
        }

        add(
            screen("media") {
                val vm = rememberVm { MediaBrowseViewModel(DemoMediaBrowsePort(data), now) }
                val s by vm.state.collectAsState()
                MediaBrowseScreen(s, vm, noCapture)
            },
        )
        if (firstMedia != null) {
            add(
                screen("media-detail") {
                    val vm = rememberVm {
                        MediaBrowseViewModel(DemoMediaBrowsePort(data), now).apply { open(state.value.rows.orEmpty().first()) }
                    }
                    val s by vm.state.collectAsState()
                    MediaBrowseScreen(s, vm, noCapture)
                },
            )
        }

        add(screen("struggle") { Struggle(data, null) })
        if (data.hotWords.isNotEmpty()) add(screen("struggle-word") { Struggle(data, "word") })
        add(screen("struggle-leeches") { Struggle(data, "leeches") })
        add(screen("struggle-confusables") { Struggle(data, "confusables") })
        add(screen("struggle-drill") { Struggle(data, "drill") })

        add(screen("sync-flow") { SyncPageOf(store, SyncPage.SYNC) })
        add(screen("sync-rejected") { SyncPageOf(store, SyncPage.REJECTED) })

        add(screen("settings") { SettingsScreen(settingsSections(data, store)) })
        settingsSections(data, store).forEach { s -> add(screen("settings-${s.id}") { s.content() }) }

        add(
            screen("grammar-capture") {
                val text = data.sentences.firstOrNull()?.optString("text") ?: "猫が日本語を勉強している。"
                GrammarCaptureEntry(text, GrammarCaptureUiState(open = true, span = 0 until 1, title = text.take(1), pattern = text.take(1), rule = text.take(1)), object : GrammarCaptureIntents {})
            },
        )
        add(
            screen("intake") {
                IntakeSheet(
                    IntakeUiState(
                        word = "猫",
                        sentence = data.sentences.firstOrNull()?.optString("text").orEmpty(),
                        decks = data.decks.filter { it.optString("type") == "user" }.map { DeckOption(it.optString("id"), it.optString("name")) },
                    ),
                    object : IntakeIntents {},
                    onClose = {},
                )
            },
        )
        add(screen("notify-permission") { NotificationRationaleScreen() })
    }

    @Composable
    private fun HomeWithChip(data: DemoData, unpaired: Boolean) {
        val sync = rememberVm { SyncFlowViewModel(DemoSyncFlowPort(DemoStore(data)), now) }
        val s by sync.state.collectAsState()
        HomeScreen(listOf { SyncChipWithFlow(s, sync) }, unpaired = unpaired)
    }

    @Composable
    private fun Review(store: DemoStore, flip: Boolean) {
        val vm = rememberVm { ReviewViewModel(store.reviewBackend(), io = now).apply { enter(); if (flip) flip() } }
        val s by vm.state.collectAsState()
        ReviewScreen(s, vm)
    }

    @Composable
    private fun Struggle(data: DemoData, page: String?) {
        val vm = rememberVm {
            StruggleViewModel(DemoStruggleSource(data), io = now).apply {
                val word = (state.value.home.words as? kotobaya.android.media.browse.Loaded.Data)?.value?.firstOrNull()
                when (page) {
                    "word" -> word?.let { open(StrugglePage.Word(it)) }
                    "leeches" -> open(StrugglePage.Leeches)
                    "confusables" -> open(StrugglePage.Confusables)
                    "drill" -> open(StrugglePage.Drill("hot_words"))
                }
            }
        }
        val s by vm.state.collectAsState()
        StruggleScreen(s, vm, noCapture)
    }

    @Composable
    private fun SyncPageOf(store: DemoStore, page: SyncPage) {
        val vm = rememberVm { SyncFlowViewModel(DemoSyncFlowPort(store), now).apply { openFlow(); if (page == SyncPage.REJECTED) openRejected() } }
        val s by vm.state.collectAsState()
        SyncFlow(s, vm)
    }

    /** Settings > every section, in registry order (the screen sorts them). */
    private fun settingsSections(data: DemoData, store: DemoStore): List<SettingsSection> = listOf(
        SettingsSection("devices", R.string.devices_title) {
            val vm = rememberVm { DevicesViewModel(DemoDevicesPort(data), now) }
            val s by vm.state.collectAsState()
            DevicesSection(s, vm)
        },
        SettingsSection("dict", R.string.dict_section_title) {
            DictSettingsSection(DictSettingsUiState(info = null, bytes = 0, scope = "top60k", install = InstallState.Idle), object : DictSettingsIntents {})
        },
        SettingsSection("notify", R.string.notify_section_title) { NotifySettings(NotifySettingsUiState(nudges = true, evening = false), object : NotifySettingsIntents {}) },
        SettingsSection("push", R.string.push_section_title) { PushSettings(PushSettingsUiState(PushUi.OFF, hasEndpoint = false), object : PushSettingsIntents {}) },
        SettingsSection("media", R.string.media_section_title) {
            MediaSettingsSection(
                MediaSettingsUiState(
                    breakdown = StorageBreakdown(
                        cardAudioBytes = 48_234_496, cardAudioCount = 812, mediaBytes = 12_582_912, coverBytes = 1_048_576,
                        dictionaryBytes = 96_468_992,
                    ),
                    prefetchDays = 2, budgetMb = 500, allowMetered = false,
                ),
                object : MediaSettingsIntents {},
            )
        },
        SettingsSection("takoboto", R.string.ankiapi_section_title) { TakobotoDebug(TakobotoDebugUiState(added = 12, duplicates = 3, noteTypeReady = true), object : kotobaya.android.ankiapi.takoboto.TakobotoDebugIntents {}) },
        SettingsSection("ankiconnect", R.string.ankiconnect_section_title) {
            AnkiConnectSettings(
                AnkiConnectSettingsUiState(enabled = true, port = "8765", savedPort = 8765, apiKey = "", origins = "http://localhost", status = AnkiConnectStatus.Running(8765)),
                object : AnkiConnectSettingsIntents {},
            )
        },
        SettingsSection("sync", R.string.sync_title) {
            val vm = rememberVm { SyncFlowViewModel(DemoSyncFlowPort(store), now) }
            val s by vm.state.collectAsState()
            SyncSettingsSection(s, vm)
        },
        SettingsSection("study", R.string.study_title) {
            StudySection(
                StudySettingsUiState(mapOf(StudyKey.NEW_PER_DAY to "20", StudyKey.REVIEWS_PER_DAY to "200", StudyKey.ROLLOVER_HOUR to "4")),
                object : StudySettingsIntents {},
            )
        },
        SettingsSection("demo", R.string.demo_section_title) {
            DemoSection(DemoSettingsUiState(available = true, enabled = true, scenario = data.scenario), object : DemoSettingsIntents {})
        },
        SettingsSection("about", R.string.about_title) {
            AboutSection(AboutUiState("0.1.7", R.string.about_flavor_ankidroid, data.meta.optString("server_version")))
        },
    )
}
