package kotobaya.android.shell.api.browse

import kotobaya.android.shell.api.LocalRoute

/** The browse group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("listCards", "GET", "/api/v1/cards") { call -> listCards(call) },
    LocalRoute("listNotes", "GET", "/api/v1/notes") { call -> listNotes(call) },
)
