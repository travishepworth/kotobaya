package kotobaya.android.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotobaya.android.R
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.i18n.T
import kotobaya.android.ui.HomeScreen
import kotobaya.android.ui.PlaceholderScreen

/**
 * One navigation destination. [topLevel] routes appear in the bottom bar; others are pushed
 * on top (detail screens).
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
    const val MEDIA = "media"
}

private fun shellRoutes(registry: FeatureRegistry): List<NavRoute> = listOf(
    NavRoute(Routes.HOME, R.string.nav_home, topLevel = true) { HomeScreen(registry.homeStatusChips) },
    NavRoute(Routes.REVIEW, R.string.nav_review, topLevel = true) { PlaceholderScreen(R.string.nav_review) },
    NavRoute(Routes.SEARCH, R.string.nav_search, topLevel = true) { PlaceholderScreen(R.string.nav_search) },
    NavRoute(Routes.DECKS, R.string.nav_decks, topLevel = true) { PlaceholderScreen(R.string.nav_decks) },
    NavRoute(Routes.SETTINGS, R.string.nav_settings, topLevel = true) { PlaceholderScreen(R.string.nav_settings) },
)

/**
 * Shell routes plus feature routes. A feature route with the same path as a shell route
 * replaces its placeholder (that is how T304+ fill in screens); other feature routes are appended.
 */
fun allRoutes(registry: FeatureRegistry): List<NavRoute> {
    val byRoute = LinkedHashMap<String, NavRoute>()
    (shellRoutes(registry) + registry.routes).forEach { byRoute[it.route] = it }
    return byRoute.values.toList()
}

@Composable
fun AppNav(registry: FeatureRegistry) {
    val nav = rememberNavController()
    val routes = allRoutes(registry)
    val current by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                routes.filter { it.topLevel }.forEach { r ->
                    NavigationBarItem(
                        selected = current?.destination?.route == r.route,
                        onClick = {
                            nav.navigate(r.route) {
                                popUpTo(Routes.HOME)
                                launchSingleTop = true
                            }
                        },
                        icon = {},
                        label = { Text(T(r.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            routes.forEach { r -> composable(r.route) { r.content() } }
        }
    }
}
