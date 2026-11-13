package kotobaya.android.nav

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.decks.DeckSource
import kotobaya.android.decks.DecksViewModel
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.home.HomeViewModel
import kotobaya.android.home.ui.TodayIntents
import kotobaya.android.home.ui.TodayScreen
import kotobaya.android.pairing.DevicesPort
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.studyDayOf
import kotobaya.android.review.ReviewRoute
import kotobaya.android.review.ui.LocalReviewExit
import kotobaya.android.settings.ui.SettingsScreen
import kotobaya.android.struggle.STRUGGLE_ROUTE
import kotobaya.android.struggle.StruggleDeepLink
import kotobaya.android.sync.SyncStatusRepository
import kotobaya.android.ui.theme.KbSystemBars
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode
import kotobaya.db.app.AppDatabase
import org.koin.compose.getKoin
import org.koin.core.Koin

/**
 * One navigation destination. [topLevel] routes are the shell's tabs ([Tab]); others are pushed on top (Struggle,
 * a deck's study session, a settings section).
 */
data class NavRoute(
    val route: String,
    @StringRes val labelRes: Int,
    val topLevel: Boolean = false,
    val content: @Composable () -> Unit,
)

object Routes {
    const val HOME = "home"
    const val REVIEW = "review"
    const val SEARCH = "search"
    const val DECKS = "decks"
    const val SETTINGS = "settings"

    /** Removed from the IA (I-5): media import and the reader are web tasks. Kept so old links resolve to Today. */
    const val MEDIA = "media"

    /** A deck's study session started from Today: `review/deck/<id>`. */
    const val REVIEW_DECK = "review/deck"

    /** Settings opened on one section: `settings/<id>`. */
    const val SETTINGS_SECTION = "settings/section"
}

private val TAB_ROUTES = Tab.entries.map { it.route }.toSet()

/**
 * Every route of the app: the shell's own (Today, Settings: they belong to the shell, which owns the appearance
 * choice and the Today layout), then the features' (Review, Search, Decks, Struggle). Only [Tab] routes are top
 * level; the Media route is dropped (DESIGN §4.2).
 */
fun allRoutes(registry: FeatureRegistry, navigate: (String) -> Unit = {}): List<NavRoute> {
    val byRoute = LinkedHashMap<String, NavRoute>()
    registry.routes.filter { it.route != Routes.MEDIA }.forEach { byRoute[it.route] = it }
    byRoute[Routes.HOME] = NavRoute(Routes.HOME, R.string.nav_home) { TodayRoute(registry, navigate) }
    byRoute[Routes.SETTINGS] = NavRoute(Routes.SETTINGS, R.string.nav_settings) { SettingsRoute(registry, null) }
    val ordered = Tab.entries.mapNotNull { byRoute[it.route] } + byRoute.values.filter { it.route !in TAB_ROUTES }
    return ordered.map { it.copy(topLevel = it.route in TAB_ROUTES) }
}

/** Today's glue: pairing state, the decks (read through the Decks ViewModel) and the sync chip slot. */
@Composable
private fun TodayRoute(registry: FeatureRegistry, navigate: (String) -> Unit) {
    val koin = getKoin()
    val home: HomeViewModel = viewModel { HomeViewModel({ koin.get<DevicesPort>().isPaired() }) }
    val homeState by home.state.collectAsState()
    val source = remember { koin.getOrNull<DeckSource>() }
    val decks: DecksViewModel? = source?.let { s ->
        viewModel(key = "today-decks") { DecksViewModel(s, koin.getOrNull<SyncStatusRepository>()?.status) }
    }
    val decksState = decks?.state?.collectAsState()?.value
    val studyDate by produceState<LocalDate?>(null) { value = withContext(Dispatchers.IO) { currentStudyDate(koin) } }
    TodayScreen(
        unpaired = homeState.unpaired,
        decks = if (decks == null) emptyList() else decksState?.decks,
        statusChips = registry.homeStatusChips,
        studyDate = studyDate,
        intents = object : TodayIntents {
            override fun startReview() = navigate(Routes.REVIEW)
            override fun studyDeck(deckId: String) = navigate("${Routes.REVIEW_DECK}/$deckId")
            override fun openDecks() = navigate(Routes.DECKS)
            override fun openStruggle() = navigate(STRUGGLE_ROUTE)
            override fun openSearch() = navigate(Routes.SEARCH)
            override fun pair() = navigate("${Routes.SETTINGS_SECTION}/devices")
        },
    )
}

/** The study day now, from the replica's `scheduler.timezone` / `day_rollover_hour` (the queue's own rules). Off main. */
private fun currentStudyDate(koin: Koin): LocalDate? {
    val db = koin.getOrNull<AppDatabase>() ?: return null
    return runCatching {
        val raw = db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ }
        val st = QueueSettings.from(raw)
        studyDayOf(System.currentTimeMillis(), st.timezone, st.dayRolloverHour).date
    }.getOrNull()
}

/** Settings' glue: every feature's section plus Appearance (stored by [Appearance]). */
@Composable
private fun SettingsRoute(registry: FeatureRegistry, section: String?) {
    val context = LocalContext.current
    val mode by Appearance.mode.collectAsState()
    SettingsScreen(
        sections = registry.settingsSections,
        appearance = mode ?: ThemeMode.System,
        onAppearance = { Appearance.set(context, it) },
        initialSection = section,
    )
}

/** The app's root: the theme (with the stored appearance choice) around the navigation shell. */
@Composable
fun AppRoot(registry: FeatureRegistry) {
    val context = LocalContext.current
    val initial = remember { Appearance.load(context) }
    val stored by Appearance.mode.collectAsState()
    KotobayaTheme(stored ?: initial) {
        KbSystemBars()
        AppNav(registry)
    }
}

@Composable
fun AppNav(registry: FeatureRegistry) {
    val nav = rememberNavController()
    val navigate: (String) -> Unit = { r -> navigateTo(nav, r) }
    val routes = allRoutes(registry, navigate)
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val struggleLink by StruggleDeepLink.pending.collectAsState()
    LaunchedEffect(struggleLink) {
        if (struggleLink != null) nav.navigate(STRUGGLE_ROUTE) { launchSingleTop = true }
    }
    AppShell(
        selected = Tab.of(route),
        onSelect = { tab -> navigateTo(nav, tab.route) },
        immersive = route?.startsWith(Routes.REVIEW) == true,
    ) {
        NavHost(nav, startDestination = Routes.HOME) {
            val exit = { if (!nav.popBackStack()) navigateTo(nav, Routes.HOME) }
            routes.forEach { r ->
                composable(r.route) {
                    if (r.route == Routes.REVIEW) {
                        CompositionLocalProvider(LocalReviewExit provides exit) { r.content() }
                    } else {
                        r.content()
                    }
                }
            }
            composable("${Routes.REVIEW_DECK}/{deckId}") { entry ->
                val id = entry.arguments?.getString("deckId").orEmpty()
                CompositionLocalProvider(LocalReviewExit provides exit) { ReviewRoute(deckId = id, key = "today-study-$id") }
            }
            composable("${Routes.SETTINGS_SECTION}/{section}") { entry ->
                SettingsRoute(registry, entry.arguments?.getString("section"))
            }
        }
    }
}

/** Tabs replace each other above Today (one Today at the bottom of the stack); pushed pages stack. */
private fun navigateTo(nav: NavHostController, route: String) {
    if (route in TAB_ROUTES) {
        nav.navigate(route) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    } else {
        nav.navigate(route) { launchSingleTop = true }
    }
}
