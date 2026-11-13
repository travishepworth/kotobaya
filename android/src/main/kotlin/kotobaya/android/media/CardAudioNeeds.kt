package kotobaya.android.media

import kotobaya.android.review.CardPage
import kotobaya.android.review.CardRows
import kotobaya.contracts.render.CardRenderer
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.store.DeviceStore

/**
 * The audio blobs the due and soon-due cards need: the card cache rows (state, `due_at`) joined to their notes, each
 * side rendered by core's renderer, whose `audio` list is the hashes (audio is referenced from note fields and found
 * by the renderer, never parsed here). Suspended, deleted and merged cards are skipped. New cards (no due time) count
 * as due now, at most [maxNew] of them in creation order.
 */
class CardAudioNeeds<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val renderer: CardRenderer = DefaultCardRenderer,
    private val maxNew: Int = 100,
) : AudioNeedsSource {
    override fun needs(nowMs: Long, windowDays: Int): List<AudioNeed> {
        val horizon = nowMs + windowDays.toLong() * 86_400_000L
        return store.transaction { tx ->
            val t = tx.learning
            val cards = t.all(LearningTable.CARD).filter { c ->
                CardRows.live(c) && !CardRows.refused(c) && (c["suspended"] as? JsonBool)?.value != true
            }
            val (fresh, scheduled) = cards.partition { (it["due_at"] as? JsonNumber) == null }
            val chosen = scheduled.filter { ((it["due_at"] as JsonNumber).value.toLong()) <= horizon } +
                fresh.sortedBy { (it["created_at"] as? JsonNumber)?.value ?: 0.0 }.take(maxNew)
            val out = LinkedHashMap<String, Long>()
            for (c in chosen) {
                val due = (c["due_at"] as? JsonNumber)?.value?.toLong() ?: nowMs
                val note = CardRows.get(t, LearningTable.NOTE, CardRows.str(c["note_id"]) ?: continue) ?: continue
                if (!CardRows.live(note)) continue
                val type = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: continue) ?: continue
                val info = CardRows.noteTypeInfo(type)
                for (side in CardSide.entries) {
                    val r = renderer.render(
                        RenderInput(
                            noteType = info, kind = CardRows.str(c["kind"]).orEmpty(), fields = CardRows.fieldsOf(note),
                            mnemonic = "", dictionary = null, cloze = null, side = side,
                            options = RenderOptions(furigana = false, pitch = false, showMnemonic = false, mediaUrlTemplate = CardPage.MEDIA_URL_TEMPLATE),
                        ),
                    )
                    for (h in r.audio) out.merge(h, due, ::minOf)
                }
            }
            out.map { (h, d) -> AudioNeed(h, d) }
        }
    }
}
