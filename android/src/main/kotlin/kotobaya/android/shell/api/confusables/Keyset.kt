package kotobaya.android.shell.api.confusables

import java.util.Base64
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.actions.CodePointOrder

/**
 * The keyset paging of the LF-A8b lists (`listConfusables`, `listGrammarPoints`, `listMedia`): the items are sorted by
 * their key (a list of strings compared column by column by [Keyset.columns]); a cursor names the key of the last item
 * shown, so an item written between two pages never repeats or hides another. Cursors are opaque, bound to the
 * request's filters by a hash, and only ever passed back to the shell (SHELL.md L-3).
 */
internal object Keyset {
    private const val SEP = '\u0000' // never in a stored text: U+0000 cannot be minted (C-6)

    /** Integer column, descending (`updated_at desc`). */
    val LONG_DESC: Comparator<String> = Comparator { a, b -> b.toLong().compareTo(a.toLong()) }

    /** Text column in code-point order. */
    val TEXT_ASC: Comparator<String> = CodePointOrder

    val TEXT_DESC: Comparator<String> = CodePointOrder.reversed()

    /** Nullable integer (`""` = NULL), descending with NULLs last (`jlpt desc nulls last`). */
    val INT_DESC_NULLS_LAST: Comparator<String> = Comparator { a, b ->
        when {
            a.isEmpty() && b.isEmpty() -> 0
            a.isEmpty() -> 1
            b.isEmpty() -> -1
            else -> b.toLong().compareTo(a.toLong())
        }
    }

    fun columns(vararg c: Comparator<String>): Comparator<List<String>> = Comparator { a, b ->
        var r = 0
        var i = 0
        while (r == 0 && i < c.size) {
            r = c[i].compare(a[i], b[i])
            i++
        }
        r
    }

    fun encode(key: List<String>, hash: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString((key + hash).joinToString(SEP.toString()).toByteArray(Charsets.UTF_8))

    /**
     * The key a cursor names; a malformed cursor, one of another request shape ([hash]) or one whose parts [valid]
     * rejects adds `422` at `/cursor` to [e] and returns null.
     */
    fun decode(raw: String?, size: Int, hash: String, e: FieldErrors, valid: (List<String>) -> Boolean): List<String>? {
        if (raw == null) return null
        val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split(SEP) }.getOrNull()
        val key = parts?.takeIf { it.size == size + 1 && it[size] == hash }?.dropLast(1)?.takeIf { runCatching { valid(it) }.getOrDefault(false) }
        if (key == null) e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
        return key
    }

    /** One page: the items after [after] in [order], at most [limit], and the key of the last one when more follow. */
    fun <T> page(items: Sequence<T>, key: (T) -> List<String>, order: Comparator<List<String>>, after: List<String>?, limit: Int): Pair<List<T>, List<String>?> {
        val window = items.map { key(it) to it }
            .filter { after == null || order.compare(it.first, after) > 0 }
            .sortedWith(compareBy(order) { it.first })
            .take(limit + 1)
            .toList()
        val shown = window.take(limit)
        return shown.map { it.second } to (if (window.size > limit) shown.last().first else null)
    }

    /** `true` when [s] is a decimal integer (a cursor's numeric part). */
    fun isLong(s: String): Boolean = s.toLongOrNull() != null
}
