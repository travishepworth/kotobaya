package kotobaya.search.sql

internal actual fun nfkc(s: String): String = s.asDynamic().normalize("NFKC").unsafeCast<String>()
