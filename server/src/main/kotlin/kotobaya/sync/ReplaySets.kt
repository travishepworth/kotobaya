package kotobaya.sync

import org.jooq.DSLContext
import java.util.UUID

/**
 * Reader of SYNC.md 9.1: the ids of card [cardId]'s replay set in replay order — every `review_log`
 * row of the card or of a card merged into it, not undone, of a schedule-feeding type, ordered by
 * `(reviewed_at, hlc, id)` (`hlc` and `id` byte-wise). The T203 replay engine and the scenario
 * tests read it; it never writes.
 */
object ReplaySets {
    fun ids(dsl: DSLContext, cardId: UUID): List<UUID> = dsl.fetch(
        "select r.id from app.review_log r where (r.card_id = ? or r.card_id in (select c.id from app.card c where c.merged_into_id = ?)) " +
            "and r.undone_at is null and r.review_type in ('scheduled', 'learning', 'manual') " +
            "order by r.reviewed_at, r.hlc collate \"C\", r.id::text collate \"C\"",
        cardId, cardId,
    ).map { it.get(0, UUID::class.java) }
}
