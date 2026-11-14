package kotobaya.search.sql

/** Unicode NFKC (platform normaliser: `java.text.Normalizer` on JVM/Android, `String.prototype.normalize` on JS). */
internal expect fun nfkc(s: String): String

/**
 * `dict_entry_form.norm` of [s] (SCHEMA.md 4.5): NFKC, then katakana U+30A1..U+30F6 and the iteration marks
 * ヽ ヾ shifted down by 0x60 to hiragana; the prolonged sound mark ー and everything else stay. Same rule as the
 * worker's `normalize_form` and the server's `DictApi.normalise` (without trimming).
 */
internal fun normalizeForm(s: String): String {
    val n = nfkc(s)
    val sb = StringBuilder(n.length)
    for (ch in n) {
        val cp = ch.code
        sb.append(if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) (cp - 0x60).toChar() else ch)
    }
    return sb.toString()
}
