package kotobaya.render

import kotobaya.cards.TemplateFields.escapeHtml

/** Pitch-accent spans (`entry_pitch` downsteps) for a kana reading. No svg: plain spans styled by `card.css`. */
internal object Pitch {
    /** Small kana that join the preceding mora (っ, ー and ん are morae of their own). */
    private const val SMALL = "ゃゅょぁぃぅぇぉゎゕゖャュョァィゥェォヮヵヶ"

    private const val PARTICLE = "○"

    private fun name(drop: Int, size: Int): String? = when {
        drop == 0 -> "heiban"
        drop == 1 -> "atamadaka"
        drop == size -> "odaka"
        drop < size -> "nakadaka"
        else -> null
    }

    fun morae(reading: String): List<String> {
        val out = ArrayList<String>()
        for (c in reading) {
            if (c in SMALL && out.isNotEmpty()) out[out.size - 1] = out.last() + c else out.add(c.toString())
        }
        return out
    }

    /**
     * One `<span class="kb-pitch">` per distinct non-negative drop, in the given order. Mora i (1-based)
     * is high iff `(i == 1 && drop == 1) || (i >= 2 && (drop == 0 || i <= drop))`; mora `drop` also gets
     * `kb-pitch-drop`. Order is the caller's (dictionary order, most common first). A drop on the last mora
     * (odaka, or a one-mora atamadaka) adds a trailing low particle slot (`○`) so the drop is visible. Each
     * pattern ends with its name as text (`<small>`). A drop past the last mora marks no drop and has no name.
     */
    fun html(reading: String, drops: List<Int>): String {
        val morae = morae(reading)
        if (morae.isEmpty()) return ""
        val sb = StringBuilder()
        for (drop in drops.distinct()) {
            if (drop < 0) continue
            sb.append("<span class=\"kb-pitch\">")
            morae.forEachIndexed { idx, mora ->
                val i = idx + 1
                val high = (i == 1 && drop == 1) || (i >= 2 && (drop == 0 || i <= drop))
                sb.append("<span class=\"kb-mora ").append(if (high) "kb-pitch-high" else "kb-pitch-low")
                if (i == drop) sb.append(" kb-pitch-drop")
                sb.append("\">").append(escapeHtml(mora)).append("</span>")
            }
            if (drop == morae.size) {
                sb.append("<span class=\"kb-mora kb-pitch-low\">").append(PARTICLE).append("</span>")
            }
            name(drop, morae.size)?.let { sb.append("<small>").append(it).append("</small>") }
            sb.append("</span>")
        }
        return sb.toString()
    }
}
