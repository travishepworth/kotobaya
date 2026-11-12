package kotobaya.spike.ankistandin

/**
 * Subset of AnkiDroid's public `com.ichi2.anki.FlashCardsContract` (api module), restated so the
 * spike has no dependency on AnkiDroid. Column names are the strings AnkiDroid's provider uses.
 */
object Contract {
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** Anki joins note fields with the unit separator. */
    const val FIELD_SEPARATOR = "\u001f"

    object Note {
        const val ID = "_id"
        const val GUID = "guid"
        const val MID = "mid"
        const val MOD = "mod"
        const val USN = "usn"
        const val TAGS = "tags"
        const val FLDS = "flds"
        const val SFLD = "sfld"
        const val CSUM = "csum"
        const val FLAGS = "flags"
        const val DATA = "data"
        const val DECK_ID_QUERY_PARAM = "deckId"
        val DEFAULT_PROJECTION = arrayOf(ID, GUID, MID, MOD, USN, TAGS, FLDS, SFLD, CSUM, FLAGS, DATA)
    }

    object Model {
        const val ID = "_id"
        const val NAME = "name"
        const val FIELD_NAMES = "field_names"
        const val NUM_CARDS = "num_cards"
        const val CSS = "css"
        const val DECK_ID = "deck_id"
        const val SORT_FIELD_INDEX = "sort_field_index"
        const val TYPE = "type"
        const val LATEX_HEADER = "latex_header"
        const val LATEX_FOOTER = "latex_footer"
        const val CURRENT_MODEL_ID = "current"
        val DEFAULT_PROJECTION =
            arrayOf(ID, NAME, FIELD_NAMES, NUM_CARDS, CSS, DECK_ID, SORT_FIELD_INDEX, TYPE, LATEX_HEADER, LATEX_FOOTER)
    }

    object CardTemplate {
        const val ID = "_id"
        const val MODEL_ID = "model_id"
        const val ORD = "ord"
        const val NAME = "name"
        const val QUESTION_FORMAT = "question_format"
        const val ANSWER_FORMAT = "answer_format"
        const val BROWSER_QUESTION_FORMAT = "browser_question_format"
        const val BROWSER_ANSWER_FORMAT = "browser_answer_format"
        val DEFAULT_PROJECTION = arrayOf(
            ID, MODEL_ID, ORD, NAME, QUESTION_FORMAT, ANSWER_FORMAT, BROWSER_QUESTION_FORMAT, BROWSER_ANSWER_FORMAT,
        )
    }

    object Card {
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val CARD_NAME = "card_name"
        const val DECK_ID = "deck_id"
        const val QUESTION = "question"
        const val ANSWER = "answer"
        const val QUESTION_SIMPLE = "question_simple"
        const val ANSWER_SIMPLE = "answer_simple"
        const val ANSWER_PURE = "answer_pure"
        val DEFAULT_PROJECTION = arrayOf(
            NOTE_ID, CARD_ORD, CARD_NAME, DECK_ID, QUESTION, ANSWER, QUESTION_SIMPLE, ANSWER_SIMPLE, ANSWER_PURE,
        )
    }

    object Deck {
        const val DECK_ID = "deck_id"
        const val DECK_NAME = "deck_name"
        const val DECK_COUNTS = "deck_count"
        const val OPTIONS = "options"
        const val DECK_DYN = "deck_dyn"
        const val DECK_DESC = "deck_desc"
        val DEFAULT_PROJECTION = arrayOf(DECK_ID, DECK_NAME, DECK_COUNTS, OPTIONS, DECK_DYN, DECK_DESC)
    }
}
