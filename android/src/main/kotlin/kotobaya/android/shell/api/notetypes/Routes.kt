package kotobaya.android.shell.api.notetypes

import kotobaya.android.shell.api.LocalRoute

/** Note type writes (LF-A8, SHELL.md 4.3 / L-10); `listNoteTypes` / `getNoteType` live in the identity group. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("createNoteType", "POST", "/api/v1/note-types") { call -> createNoteType(call) },
    LocalRoute("updateNoteType", "PATCH", "/api/v1/note-types/{note_type_id}") { call -> updateNoteType(call) },
    LocalRoute("deleteNoteType", "DELETE", "/api/v1/note-types/{note_type_id}") { call -> deleteNoteType(call) },
)
