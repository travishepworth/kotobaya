package kotobaya.android.decks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.i18n.T
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewScreen
import kotobaya.android.sync.SyncStatusRepository
import org.koin.compose.getKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val PAGE = 50

/** Decks tab (read-only): replaces the shell's Decks placeholder. Deck create/rename/delete are left for later. */
val decksFeature = FeatureEntry(
    id = "decks",
    module = module {
        factory { DeckRepository(get<SqlDriver>(named("syncAppDriver")), get<LocalQueueLoader>()) }
    },
    routes = listOf(NavRoute(Routes.DECKS, R.string.nav_decks, topLevel = true) { DecksRoute() }),
)

private enum class Mode { LIST, DETAIL, STUDY }

@Composable
private fun DecksRoute() {
    val koin = getKoin()
    val repo = remember { koin.get<DeckRepository>() }
    val backend = remember { koin.getOrNull<ReviewBackend<*>>() }
    // Bumped by a manual refresh and by every committed sync write, so counts follow the replica.
    val syncStatus = remember { koin.getOrNull<SyncStatusRepository>()?.status }?.collectAsState()?.value
    var refresh by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(Mode.LIST) }
    var selected by remember { mutableStateOf<String?>(null) }

    when (mode) {
        Mode.LIST -> DeckList(repo, refresh, syncStatus, onRefresh = { refresh++ }) { selected = it; mode = Mode.DETAIL }
        Mode.DETAIL -> DeckDetail(
            repo, selected.orEmpty(), refresh, syncStatus,
            onBack = { mode = Mode.LIST },
            onStudy = { mode = Mode.STUDY },
        )
        Mode.STUDY -> {
            BackHandler { refresh++; mode = Mode.DETAIL }
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = { refresh++; mode = Mode.DETAIL }) { Text(T(R.string.decks_back)) }
                ReviewScreen(backend, onSessionEnd = { refresh++ }, deckId = selected)
            }
        }
    }
}

@Composable
private fun <V> rememberLoaded(vararg keys: Any?, load: () -> V): V? {
    var value by remember { mutableStateOf<V?>(null) }
    LaunchedEffect(*keys) { value = withContext(Dispatchers.IO) { load() } }
    return value
}

@Composable
private fun typeLabel(type: String): String = T(
    when (type) {
        "media" -> R.string.decks_type_media
        "list" -> R.string.decks_type_list
        "smart" -> R.string.decks_type_smart
        else -> R.string.decks_type_user
    },
)

@Composable
private fun countsLine(d: DeckSummary): String =
    T(R.string.decks_counts, d.counts.due, d.counts.new, d.counts.learning, d.totalCards)

@Composable
private fun DeckList(repo: DeckRepository, refresh: Int, syncKey: Any?, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    val decks = rememberLoaded(refresh, syncKey) { repo.decks() }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(T(R.string.decks_title), Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text(T(R.string.decks_refresh)) }
        }
        when {
            decks == null -> Text(T(R.string.decks_loading))
            decks.isEmpty() -> Text(T(R.string.decks_empty))
            else -> LazyColumn {
                items(decks, key = { it.id }) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(d.id) }.padding(vertical = 8.dp)) {
                        Text(d.name)
                        Text(typeLabel(d.type) + if (d.enabled) "" else " · " + T(R.string.decks_disabled))
                        Text(countsLine(d))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckDetail(
    repo: DeckRepository,
    id: String,
    refresh: Int,
    syncKey: Any?,
    onBack: () -> Unit,
    onStudy: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val deck = rememberLoaded(id, refresh, syncKey) { repo.deck(id) }
    var limit by remember(id) { mutableIntStateOf(PAGE) }
    val words = rememberLoaded(id, limit, refresh, syncKey) { repo.words(id, limit, 0) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        TextButton(onClick = onBack) { Text(T(R.string.decks_back)) }
        if (deck == null) {
            Text(T(R.string.decks_loading))
            return@Column
        }
        Text(deck.name)
        Text(T(R.string.decks_detail_type, typeLabel(deck.type)))
        Text(countsLine(deck))
        if (deck.type == "smart") {
            deck.query?.let { Text(T(R.string.decks_detail_query, it)) }
            Text(T(R.string.decks_smart_note))
        }
        Button(onClick = onStudy) { Text(T(R.string.decks_study)) }
        Text(T(R.string.decks_words))
        if (words.isNullOrEmpty()) {
            if (words != null) Text(T(R.string.decks_words_empty))
        } else {
            LazyColumn {
                items(words, key = { it.itemKey }) { w -> Text(w.label, Modifier.padding(vertical = 2.dp)) }
                if (words.size >= limit) item {
                    TextButton(onClick = { limit += PAGE }) { Text(T(R.string.decks_more)) }
                }
            }
        }
    }
}
