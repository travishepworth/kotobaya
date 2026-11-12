package kotobaya.android.ankiapi.provider

import android.net.Uri
import java.security.MessageDigest

/**
 * The parts of AnkiDroid's public `com.ichi2.anki.FlashCardsContract` this stand-in answers,
 * restated so we have no dependency on AnkiDroid. Written from memory of the contract (S01); any
 * URI or column a real client uses that is missing here shows up in [ProviderCallLog].
 *
 * Only one installed app can own [AUTHORITY] and [READ_WRITE_PERMISSION]: real AnkiDroid and the
 * other Kotobaya flavor must be uninstalled first.
 */
object AnkiContract {
    const val AUTHORITY: String = "com.ichi2.anki.flashcards"
    const val READ_WRITE_PERMISSION: String = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** Anki joins note fields (and model field names) with the unit separator. */
    const val FIELD_SEPARATOR: String = "\u001f"

    val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
    val NOTES_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    val MODELS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "models")
    val DECKS_URI: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")

    object Note {
        const val ID: String = "_id"
        const val GUID: String = "guid"
        const val MID: String = "mid"
        const val MOD: String = "mod"
        const val USN: String = "usn"
        const val TAGS: String = "tags"
        const val FLDS: String = "flds"
        const val SFLD: String = "sfld"
        const val CSUM: String = "csum"
        const val FLAGS: String = "flags"
        const val DATA: String = "data"
        const val DECK_ID_QUERY_PARAM: String = "deckId"
        const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.com.ichi2.anki.note"
        const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.com.ichi2.anki.note"
    }

    object Model {
        const val ID: String = "_id"
        const val NAME: String = "name"
        const val FIELD_NAMES: String = "field_names"
        const val NUM_CARDS: String = "num_cards"
        const val CSS: String = "css"
        const val DECK_ID: String = "deck_id"
        const val SORT_FIELD_INDEX: String = "sort_field_index"
        const val TYPE: String = "type"
        const val LATEX_HEADER: String = "latex_header"
        const val LATEX_FOOTER: String = "latex_footer"
        const val CURRENT_MODEL_ID: String = "current"
        const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.com.ichi2.anki.model"
        const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.com.ichi2.anki.model"
    }

    /** `models/#/fields` is not in AnkiDroid's contract; the spike answered it, so we keep it. */
    object Field {
        const val ID: String = "_id"
        const val NAME: String = "name"
        const val ORD: String = "ord"
    }

    object CardTemplate {
        const val ID: String = "_id"
        const val MODEL_ID: String = "model_id"
        const val ORD: String = "ord"
        const val NAME: String = "name"
        const val QUESTION_FORMAT: String = "question_format"
        const val ANSWER_FORMAT: String = "answer_format"
        const val BROWSER_QUESTION_FORMAT: String = "browser_question_format"
        const val BROWSER_ANSWER_FORMAT: String = "browser_answer_format"
        const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.com.ichi2.anki.model.template"
        const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.com.ichi2.anki.model.template"
    }

    object Card {
        const val NOTE_ID: String = "note_id"
        const val CARD_ORD: String = "ord"
        const val CARD_NAME: String = "card_name"
        const val DECK_ID: String = "deck_id"
        const val QUESTION: String = "question"
        const val ANSWER: String = "answer"
        const val QUESTION_SIMPLE: String = "question_simple"
        const val ANSWER_SIMPLE: String = "answer_simple"
        const val ANSWER_PURE: String = "answer_pure"
        const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.com.ichi2.anki.card"
        const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.com.ichi2.anki.card"
    }

    object Deck {
        const val DECK_ID: String = "deck_id"
        const val DECK_NAME: String = "deck_name"
        const val DECK_COUNTS: String = "deck_count"
        const val OPTIONS: String = "options"
        const val DECK_DYN: String = "deck_dyn"
        const val DECK_DESC: String = "deck_desc"
        const val CONTENT_TYPE: String = "vnd.android.cursor.dir/vnd.com.ichi2.anki.deck"
        const val CONTENT_ITEM_TYPE: String = "vnd.android.cursor.item/vnd.com.ichi2.anki.deck"
    }

    /** Splits `flds`/`field_names`, keeping empty trailing fields (Anki's `splitFields`). */
    fun splitFields(joined: String): List<String> = joined.split(FIELD_SEPARATOR)

    fun joinFields(fields: List<String>): String = fields.joinToString(FIELD_SEPARATOR)

    /** Anki tags travel as one space-separated string. */
    fun splitTags(joined: String?): List<String> =
        joined.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun joinTags(tags: List<String>): String = tags.joinToString(" ")

    /** Longest input [stripHtmlMedia] processes; longer fields are truncated first (bounds regex cost). */
    const val MAX_STRIP_CHARS: Int = 64 * 1024

    /**
     * Anki's `fieldChecksum`: the first 8 hex digits of SHA-1 over the field with HTML stripped.
     * Anki takes it over the note's FIRST field (not the sort field). AddContentApi computes it on
     * the client for `csum in (...)` duplicate checks, so a store must produce the same number.
     */
    fun fieldChecksum(field: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(stripHtmlMedia(field).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8).toLong(16)
    }

    /** Anki's `stripHTMLMedia`: tags removed, image file names kept, basic entities decoded. Also `sfld`. */
    fun stripHtmlMedia(field: String): String = field.take(MAX_STRIP_CHARS)
        .replace(Regex("(?s)<!--.*?-->"), "")
        .replace(Regex("(?is)<style.*?>.*?</style>"), "")
        .replace(Regex("(?is)<script.*?>.*?</script>"), "")
        .replace(Regex("(?i)<img[^>]+src=[\"']?([^\"'>]+)[\"']?[^>]*>"), " $1 ")
        .replace(Regex("(?s)<.*?>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
