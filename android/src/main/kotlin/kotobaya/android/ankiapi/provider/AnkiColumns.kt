package kotobaya.android.ankiapi.provider

import android.database.MatrixCursor
import kotobaya.android.ankiapi.provider.AnkiContract.Card
import kotobaya.android.ankiapi.provider.AnkiContract.CardTemplate
import kotobaya.android.ankiapi.provider.AnkiContract.Deck
import kotobaya.android.ankiapi.provider.AnkiContract.Field
import kotobaya.android.ankiapi.provider.AnkiContract.Model
import kotobaya.android.ankiapi.provider.AnkiContract.Note

/**
 * Column name -> value, per row kind. The order of each table is the default projection (what a
 * `null` projection returns). Changing what a client sees (e.g. after a real Takoboto call log)
 * means editing these tables only.
 */
internal class ColumnTable<T>(private val columns: List<Pair<String, (T) -> Any?>>) {
    private val byName = columns.toMap()
    val defaultProjection: Array<String> = columns.map { it.first }.toTypedArray()

    /** Builds a cursor; unknown projected columns are reported to [onUnknown] and answered with null. */
    fun cursor(projection: Array<out String>?, items: List<T>, onUnknown: (String) -> Unit): MatrixCursor {
        val cols: Array<String> = projection?.let { arrayOf(*it) } ?: defaultProjection
        cols.filterNot { it in byName }.forEach(onUnknown)
        return MatrixCursor(cols, items.size).apply {
            items.forEach { item -> addRow(cols.map { byName[it]?.invoke(item) }) }
        }
    }
}

/** A template row needs its model and ord. */
internal data class TemplateRef(val model: AnkiModel, val ord: Int)

/** A field-name row of `models/#/fields`. */
internal data class FieldRef(val ord: Int, val name: String)

internal object AnkiColumns {
    val note = ColumnTable<AnkiNote>(
        listOf(
            Note.ID to { it.id },
            Note.GUID to { it.guid },
            Note.MID to { it.modelId },
            Note.MOD to { it.modSeconds },
            Note.USN to { -1 },
            Note.TAGS to { AnkiContract.joinTags(it.tags) },
            Note.FLDS to { AnkiContract.joinFields(it.fields) },
            Note.SFLD to { it.sortField },
            Note.CSUM to { it.checksum },
            Note.FLAGS to { 0 },
            Note.DATA to { "" },
        ),
    )

    val model = ColumnTable<AnkiModel>(
        listOf(
            Model.ID to { it.id },
            Model.NAME to { it.name },
            Model.FIELD_NAMES to { AnkiContract.joinFields(it.fields) },
            Model.NUM_CARDS to { it.templates.size },
            Model.CSS to { it.css },
            Model.DECK_ID to { it.deckId },
            Model.SORT_FIELD_INDEX to { it.sortFieldIndex },
            Model.TYPE to { it.type },
            Model.LATEX_HEADER to { it.latexHeader },
            Model.LATEX_FOOTER to { it.latexFooter },
        ),
    )

    val field = ColumnTable<FieldRef>(
        listOf(
            Field.ID to { it.ord.toLong() },
            Field.NAME to { it.name },
            Field.ORD to { it.ord },
        ),
    )

    val template = ColumnTable<TemplateRef>(
        listOf(
            CardTemplate.ID to { it.ord.toLong() },
            CardTemplate.MODEL_ID to { it.model.id },
            CardTemplate.ORD to { it.ord },
            CardTemplate.NAME to { it.model.templates[it.ord].name },
            CardTemplate.QUESTION_FORMAT to { it.model.templates[it.ord].questionFormat },
            CardTemplate.ANSWER_FORMAT to { it.model.templates[it.ord].answerFormat },
            CardTemplate.BROWSER_QUESTION_FORMAT to { it.model.templates[it.ord].browserQuestionFormat },
            CardTemplate.BROWSER_ANSWER_FORMAT to { it.model.templates[it.ord].browserAnswerFormat },
        ),
    )

    val card = ColumnTable<AnkiCard>(
        listOf(
            Card.NOTE_ID to { it.noteId },
            Card.CARD_ORD to { it.ord },
            Card.CARD_NAME to { it.name },
            Card.DECK_ID to { it.deckId },
            Card.QUESTION to { it.question },
            Card.ANSWER to { it.answer },
            Card.QUESTION_SIMPLE to { it.question },
            Card.ANSWER_SIMPLE to { it.answer },
            Card.ANSWER_PURE to { it.answer },
        ),
    )

    val deck = ColumnTable<AnkiDeck>(
        listOf(
            Deck.DECK_ID to { it.id },
            Deck.DECK_NAME to { it.name },
            Deck.DECK_COUNTS to { "[0,0,0]" },
            Deck.OPTIONS to { "{}" },
            Deck.DECK_DYN to { if (it.dynamic) 1 else 0 },
            Deck.DECK_DESC to { it.description },
        ),
    )
}
