package kotobaya.android.shell.web

/** The shell's origin (SHELL.md 1): `WebViewAssetLoader`'s default domain, https. */
object ShellOrigin {
    const val HOST: String = "appassets.androidplatform.net"
    const val ORIGIN: String = "https://$HOST"

    /** True for `/api/...`, `/shell/...` (and the bare prefixes): never served from the bundle (SHELL.md 2.7, 6.1). */
    fun isApiPath(path: String): Boolean {
        val p = path.trimStart('/')
        return p == "api" || p == "shell" || p.startsWith("api/") || p.startsWith("shell/")
    }
}

/** SPA paths (SHELL.md 6.1 "Path rule"). */
object NavPath {
    val PATTERN: Regex = Regex("^/[A-Za-z0-9\\-._~/?&=%+]*$")

    /** [path] if it satisfies the path rule, else `/`. */
    fun sanitize(path: String?): String = if (path != null && BridgeCodec.isValidNavPath(path)) path else "/"

    /**
     * The SPA path (path plus `?query`, no fragment) of [url] when it is on the shell origin and satisfies the
     * path rule; null otherwise. Used to remember the last path for the launcher (SHELL.md 6.1).
     */
    fun ofUrl(url: String?): String? {
        if (url == null || !url.startsWith(ShellOrigin.ORIGIN)) return null
        val rest = url.substring(ShellOrigin.ORIGIN.length).substringBefore('#')
        if (rest.isEmpty()) return "/"
        if (!rest.startsWith("/")) return null
        if (ShellOrigin.isApiPath(rest.substringBefore('?'))) return null
        return rest.takeIf { BridgeCodec.isValidNavPath(it) }
    }
}

/**
 * Maps a URL path on the shell origin to a file of the bundled SPA (`assets/web/`, SHELL-A1).
 * A path whose last segment has no extension (and `/`) is the SPA fallback `index.html`; paths under
 * `/api/` and `/shell/` are never served from the bundle (SHELL.md 2.7, 6.1).
 */
object AssetPaths {
    const val ROOT: String = "web"
    const val INDEX: String = "$ROOT/index.html"

    /** The asset path for [urlPath] (already percent-decoded), or null when nothing of the bundle may answer it. */
    fun resolve(urlPath: String): String? {
        if (ShellOrigin.isApiPath(urlPath)) return null
        val p = urlPath.trimStart('/')
        val segments = p.split('/')
        if (segments.any { it == "." || it == ".." } || '\\' in p || '\u0000' in p) return null
        val last = segments.last()
        if (last.isEmpty() || '.' !in last) return INDEX
        return "$ROOT/$p"
    }

    /** MIME type by extension; `application/octet-stream` when unknown. */
    fun mimeType(assetPath: String): String = when (assetPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "avif" -> "image/avif"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "txt" -> "text/plain"
        "xml" -> "application/xml"
        "mp3" -> "audio/mpeg"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    /** `utf-8` for text types, else null. */
    fun encoding(mime: String): String? =
        if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml" ||
            mime == "application/manifest+json" || mime == "application/xml"
        ) {
            "utf-8"
        } else {
            null
        }
}
