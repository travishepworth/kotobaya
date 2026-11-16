package kotobaya.android.shell.api.struggle

import kotobaya.android.shell.api.LocalRoute

/** The struggle group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // SHELL-L8: hot words, hot kanji, leeches (StruggleRoutes.kt)
    LocalRoute("listHotWords", "GET", "/api/v1/words/hot") { call -> listHotWords(call) },
    LocalRoute("listHotKanji", "GET", "/api/v1/kanji/hot") { call -> listHotKanji(call) },
    LocalRoute("listLeeches", "GET", "/api/v1/words/leeches") { call -> listLeeches(call) },
    // SHELL-L8: the drill session (DrillRoute.kt); stores nothing, mints nothing
    LocalRoute("createDrillSession", "POST", "/api/v1/drill/session") { call -> createDrillSession(call) },
)
