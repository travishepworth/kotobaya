package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.dict.SearchText
import kotobaya.grammar.cards.GrammarCardSentenceSource
import kotobaya.server.formatInstant
import kotobaya.server.json
import org.jooq.DSLContext
import java.time.Instant

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"
internal fun msOf(col: String) = "(extract(epoch from $col) * 1000)::bigint"

/** One card as the API's `Card` (with `note`), plus what the scheduler needs to preview it. */
internal class CardView(val json: ObjectNode, val presetId: String, val schedule: CardSchedule, val noteType: String)

/** Read side of the review API: rows as the API's JSON. */
internal class ReviewViews(private val dsl: DSLContext, private val leechThreshold: Int) {
    /** `Card` JSON with the note summary for each id that exists (deleted and merged cards included). */
    fun cards(ids: Collection<String>): Map<String, CardView> {
        if (ids.isEmpty()) return emptyMap()
        val rows = dsl.fetch(
            "select c.id::text, jsonb_build_object(" +
                "'id', c.id, 'note_id', c.note_id, 'kind', c.kind, 'preset_id', c.preset_id, 'suspended', c.suspended, " +
                "'buried_until', ${ts("c.buried_until")}, 'flags', c.flags, 'state', c.state, 'step', c.step, 'stability', c.stability, " +
                "'difficulty', c.difficulty, 'due_at', ${ts("c.due_at")}, 'interval_days', c.interval_days, " +
                "'last_review_at', ${ts("c.last_review_at")}, 'reps', c.reps, 'lapses', c.lapses, " +
                "'created_at', ${ts("c.created_at")}, 'updated_at', ${ts("c.updated_at")}, 'deleted_at', ${ts("c.deleted_at")}, " +
                "'merged_into_id', c.merged_into_id, 'note', jsonb_build_object(" +
                "'id', n.id, 'note_type_id', n.note_type_id, 'type', n.type, 'entry_seq', n.entry_seq, " +
                "'kanji_literal', n.kanji_literal, 'grammar_point_id', n.grammar_point_id, 'fields', n.fields, " +
                "'tags', to_jsonb(n.tags), 'mnemonic', n.mnemonic, 'source_sentence', n.source_sentence))::text, " +
                "c.preset_id::text, ${msOf("c.due_at")}, ${msOf("c.last_review_at")}, n.note_type_id::text " +
                "from app.card c join app.note n on n.id = c.note_id where c.id = any(?::uuid[])",
            ids.toTypedArray(),
        )
        return rows.associate { r ->
            val node = json.readTree(r.get(1, String::class.java)) as ObjectNode
            node.put("leech", node["lapses"].asInt() >= leechThreshold)
            val schedule = CardSchedule(
                state = CardState.entries.first { it.wire == node["state"].asText() },
                step = node["step"].takeUnless { it.isNull }?.asInt(),
                stability = node["stability"].takeUnless { it.isNull }?.asDouble(),
                difficulty = node["difficulty"].takeUnless { it.isNull }?.asDouble(),
                dueAt = (r.get(3) as Number?)?.toLong(),
                intervalDays = node["interval_days"].takeUnless { it.isNull }?.asInt(),
                lastReviewAt = (r.get(4) as Number?)?.toLong(),
                reps = node["reps"].asInt(),
                lapses = node["lapses"].asInt(),
            )
            r.get(0, String::class.java) to CardView(node, r.get(2, String::class.java), schedule, r.get(5, String::class.java))
        }
    }

    /** The card as it is now, resolved to its survivor when merged. */
    fun survivorOf(cardId: String): String? =
        dsl.fetchOptional("select coalesce(merged_into_id, id)::text from app.card where id = ?::uuid", cardId)
            .map { it.get(0, String::class.java) }.orElse(null)

    /** `ReviewLog` JSON of a `review_log` row, or null. */
    fun review(id: String): ObjectNode? =
        dsl.fetchOptional(
            "select jsonb_build_object('id', id, 'card_id', card_id, 'device_id', device_id, 'hlc', hlc::text, " +
                "'reviewed_at', ${ts("reviewed_at")}, 'utc_offset_min', utc_offset_min, 'review_type', review_type, " +
                "'rating', rating, 'button', button, 'duration_ms', duration_ms, 'manual_action', manual_action, " +
                "'manual_due_at', ${ts("manual_due_at")}, 'sentence_id', sentence_id, 'import_source', import_source, " +
                "'undone_at', ${ts("undone_at")})::text from app.review_log where id = ?::uuid",
            id,
        ).map { json.readTree(it.get(0, String::class.java)) as ObjectNode }.orElse(null)

    /**
     * `QueueItem`s for [cards] in order. [reviewTypes] gives each card's `review_type`; items of type `drill`/`cram` get a
     * null `preview`, the others the core scheduler's preview for an answer given at [nowMs]. Cards that vanished are dropped.
     */
    fun items(cards: List<String>, reviewTypes: Map<String, String>, nowMs: Long, scheduler: Scheduler): List<ObjectNode> {
        val views = cards(cards)
        val presets = if (reviewTypes.values.any { it == "scheduled" || it == "learning" }) loadPresets(dsl) else emptyMap()
        return cards.mapNotNull { id ->
            val v = views[id] ?: return@mapNotNull null
            val type = reviewTypes[id] ?: "learning"
            val note = v.json["note"]
            val item = json.createObjectNode()
            item.set<JsonNode>("card", v.json)
            item.set<JsonNode>("entry", note["entry_seq"].takeUnless { it.isNull }?.let { entry(note["entry_seq"].asLong()) } ?: json.nullNode())
            item.set<JsonNode>("kanji", note["kanji_literal"].takeUnless { it.isNull }?.let { kanji(it.asText()) } ?: json.nullNode())
            // C-15: grammar cards (not production) show a rotating example sentence from the user's media.
            val gid = note["grammar_point_id"].takeUnless { it.isNull }?.asText()
            val grammarCloze = gid != null && v.json["kind"].asText() != "production"
            val picked = if (grammarCloze && gid != null) GrammarCardSentenceSource.itemFor(dsl, id, gid, v.json["reps"].asInt()) else null
            item.set<JsonNode>("sentence", picked?.first ?: json.nullNode())
            if (grammarCloze) item.set<JsonNode>("grammar_sentence", picked?.second ?: json.nullNode())
            item.put("review_type", type)
            if (type == "drill" || type == "cram") {
                item.putNull("preview")
            } else {
                val params = scheduler.paramsOf(effectivePreset(v.presetId, presets))
                val out = scheduler.preview(v.schedule, nowMs, params, null, null).associateBy { it.rating.value }
                val preview = json.createObjectNode()
                for ((key, rating) in listOf("again" to 1, "hard" to 2, "good" to 3, "easy" to 4)) {
                    val o = checkNotNull(out[rating]) { "preview lacks rating $rating" }
                    preview.putObject(key)
                        .put("due_at", formatInstant(Instant.ofEpochMilli(checkNotNull(o.after.dueAt) { "preview without due_at" })))
                        .put("interval_days", o.after.intervalDays ?: 0)
                }
                item.set<JsonNode>("preview", preview)
            }
            item
        }
    }

    // ---------------------------------------------------------------- dictionary (same shapes as GET /dict/entries/{seq}, /kanji/{literal})

    /** `DictEntry` JSON, or null when the entry is not in the dictionary. */
    fun entry(seq: Long): JsonNode? {
        val e = dsl.fetchOne("select * from ref.dict_entry where seq = ?", seq) ?: return null
        val freqs = dsl.fetch(
            "select f.list_id, f.rank from ref.entry_freq f join ref.freq_list l on l.id = f.list_id where f.seq = ? order by l.priority",
            seq,
        ).map { mapOf("list_id" to it.get("list_id", String::class.java), "rank" to it.get("rank", Int::class.java)) }
        val pitch = dsl.fetch("select reading, positions from ref.entry_pitch where seq = ? order by reading", seq)
            .map { mapOf("reading" to it.get("reading", String::class.java), "positions" to it.get("positions", Array<Short>::class.java).map { p -> p.toInt() }) }
        val kanjiForms = json.readTree(e.get("kanji_forms", String::class.java))
        val kanji = LinkedHashSet<String>()
        for (f in kanjiForms) kanji += kanjiOf(f["text"].asText())
        val status = dsl.fetchOptional("select status from app.word_status where entry_seq = ?", seq).map { it.get(0, String::class.java) }.orElse("new")
        val noteId = dsl.fetchValue("select id::text from app.note where entry_seq = ? and deleted_at is null limit 1", seq) as String?
        return json.valueToTree(
            linkedMapOf(
                "seq" to seq,
                "language" to e.get("language", String::class.java),
                "headword" to e.get("headword", String::class.java),
                "headword_reading" to e.get("headword_reading", String::class.java),
                "is_common" to e.get("is_common", Boolean::class.java),
                "kanji_forms" to kanjiForms,
                "readings" to json.readTree(e.get("readings", String::class.java)),
                "senses" to json.readTree(e.get("senses", String::class.java)),
                "pos" to e.get("pos", Array<String>::class.java).toList(),
                "misc" to e.get("misc", Array<String>::class.java).toList(),
                "freq_rank" to e.get("freq_rank", Int::class.javaObjectType),
                "retired" to (e.get("retired_release_id") != null),
                "freqs" to freqs,
                "pitch" to pitch,
                "kanji" to kanji.toList(),
                "status" to status,
                "note_id" to noteId,
            ),
        )
    }

    /** `Kanji` JSON, or null when the literal is not in `ref.kanji`. */
    fun kanji(literal: String): JsonNode? {
        val k = dsl.fetchOne("select * from ref.kanji where literal = ?", literal) ?: return null
        val noteId = dsl.fetchValue("select id::text from app.note where kanji_literal = ? and deleted_at is null limit 1", literal) as String?
        return json.valueToTree(
            linkedMapOf(
                "literal" to literal,
                "on_readings" to k.get("on_readings", Array<String>::class.java).toList(),
                "kun_readings" to k.get("kun_readings", Array<String>::class.java).toList(),
                "nanori" to k.get("nanori", Array<String>::class.java).toList(),
                "meanings" to k.get("meanings", Array<String>::class.java).toList(),
                "stroke_count" to k.get("stroke_count", Int::class.javaObjectType),
                "grade" to k.get("grade", Int::class.javaObjectType),
                "jlpt" to k.get("jlpt", Int::class.javaObjectType),
                "freq_rank" to k.get("freq_rank", Int::class.javaObjectType),
                "radical" to k.get("radical", String::class.java),
                "components" to k.get("components", Array<String>::class.java).toList(),
                "note_id" to noteId,
            ),
        )
    }

    companion object {
        /** Distinct kanji of [text] in order of first appearance (HTML tags ignored; the iteration marks 々 and 〇 do not count). */
        fun kanjiOf(text: String): List<String> {
            val plain = text.replace(Regex("<[^>]*>"), "")
            val out = LinkedHashSet<String>()
            var i = 0
            while (i < plain.length) {
                val cp = plain.codePointAt(i)
                if (SearchText.isKanji(cp) && cp != 0x3005 && cp != 0x3007) out += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            return out.toList()
        }

        /** Every preset row (a handful), by id. */
        fun loadPresets(dsl: DSLContext): Map<String, PresetRow> =
            dsl.fetch("select to_jsonb(p)::text from app.fsrs_params p").associate { rec ->
                val n = json.readTree(rec.get(0, String::class.java))
                n["id"].asText() to PresetRow(
                    id = n["id"].asText(),
                    desiredRetention = n["desired_retention"].asDouble(),
                    maximumIntervalDays = n["maximum_interval_days"].asInt(),
                    learningStepsSeconds = n["learning_steps_s"].map(JsonNode::asInt),
                    relearningStepsSeconds = n["relearning_steps_s"].map(JsonNode::asInt),
                    fuzz = n["fuzz"].asBoolean(),
                    easyDays = n["easy_days"].map(JsonNode::asDouble),
                    fsrsVersion = n["fsrs_version"]?.takeUnless { it.isNull }?.asText(),
                    weights = n["weights"]?.takeUnless { it.isNull }?.map(JsonNode::asDouble),
                    weightsVersion = n["weights_version"].asInt(),
                    deleted = !(n["deleted_at"]?.isNull ?: true),
                )
            }

        /** The card's preset, or the Default preset when that row is tombstoned or missing (SYNC.md 9.1). */
        fun effectivePreset(presetId: String, presets: Map<String, PresetRow>): PresetRow {
            val own = presets[presetId]
            return if (own == null || own.deleted) checkNotNull(presets[Reserved.DEFAULT_PRESET_ID]) { "the Default preset is missing" } else own
        }
    }
}
