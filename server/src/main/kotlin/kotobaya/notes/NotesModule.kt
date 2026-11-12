package kotobaya.notes

import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.cards.CardGenerator
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Handlers for notes and note types (T201b), keyed by operationId for `AppDeps.handlers`.
 *
 * Every write is an op minted through [opApply] (CLAUDE.md rule 8). Wiring (Main.kt, not this ticket's
 * path): `handlers += NotesModule(db, sync.opApply).handlers`, with the same [OpApply] the sync module uses.
 */
class NotesModule(
    db: Database,
    opApply: OpApply,
    cards: CardGenerator = DefaultCardGenerator,
    basePath: String = "/api/v1",
) {
    private val types = NoteTypeApi(db, opApply)
    private val notes = NoteApi(db, opApply, cards, basePath)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listNoteTypes" to { types.list(this) },
        "createNoteType" to { types.create(this) },
        "getNoteType" to { types.get(this) },
        "updateNoteType" to { types.update(this) },
        "deleteNoteType" to { types.delete(this) },
        "listNotes" to { notes.list(this) },
        "createNote" to { notes.create(this) },
        "getNote" to { notes.get(this) },
        "updateNote" to { notes.update(this) },
        "deleteNote" to { notes.delete(this) },
    )
}
