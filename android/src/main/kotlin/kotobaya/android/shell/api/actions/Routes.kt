package kotobaya.android.shell.api.actions

import kotobaya.android.shell.api.LocalRoute

/** The actions group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // SHELL-L4: bulk card actions (BulkRoute.kt)
    LocalRoute("bulkCards", "POST", "/api/v1/cards/bulk") { call -> bulkCards(call) },
    // word status (WordStatusRoutes.kt)
    LocalRoute("getWordStatus", "GET", "/api/v1/words/{seq}/status") { call -> getWordStatus(call) },
    LocalRoute("setWordStatus", "PUT", "/api/v1/words/{seq}/status") { call -> setWordStatus(call) },
    LocalRoute("clearWordStatus", "DELETE", "/api/v1/words/{seq}/status") { call -> clearWordStatus(call) },
    // notes (NoteRoutes.kt)
    LocalRoute("getNote", "GET", "/api/v1/notes/{note_id}") { call -> getNote(call) },
    LocalRoute("updateNote", "PATCH", "/api/v1/notes/{note_id}") { call -> updateNote(call) },
    LocalRoute("deleteNote", "DELETE", "/api/v1/notes/{note_id}") { call -> deleteNote(call) },
    // cards (CardRoutes.kt)
    LocalRoute("getCard", "GET", "/api/v1/cards/{card_id}") { call -> getCard(call) },
    LocalRoute("listCardReviews", "GET", "/api/v1/cards/{card_id}/reviews") { call -> listCardReviews(call) },
)
