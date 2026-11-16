package kotobaya.android.shell.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SHELL-A3 acceptance: which bundle file answers a path of the shell origin (SHELL.md 2.7, 6.1). */
class AssetPathTest {
    @Test
    fun spaRoutesFallBackToIndex() {
        for (p in listOf("/", "", "/review", "/decks/abc", "/decks/abc/", "/dictionary", "/kanji/績", "/decks/01931f40-2000-7000-8000-000000000d01")) {
            assertEquals("web/index.html", AssetPaths.resolve(p), p)
        }
    }

    @Test
    fun filesAreServedFromTheBundle() {
        assertEquals("web/_app/x.js", AssetPaths.resolve("/_app/x.js"))
        assertEquals("web/_app/immutable/entry/start.abc123.js", AssetPaths.resolve("/_app/immutable/entry/start.abc123.js"))
        assertEquals("web/shell-manifest.json", AssetPaths.resolve("/shell-manifest.json"))
        assertEquals("web/favicon.png", AssetPaths.resolve("/favicon.png"))
    }

    @Test
    fun apiAndShellPathsAreNeverServedFromAssets() {
        for (p in listOf("/api/v1/notes", "/api/v1/blobs/" + "a".repeat(64), "/api", "/api/", "/shell/v1/info", "/shell", "/api/v1/x.js")) {
            assertNull(AssetPaths.resolve(p), p)
        }
    }

    @Test
    fun dotSegmentsAreRefused() {
        assertNull(AssetPaths.resolve("/../secret.txt"))
        assertNull(AssetPaths.resolve("/_app/../../x.js"))
        assertNull(AssetPaths.resolve("/./index.html"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("text/html", AssetPaths.mimeType("web/index.html"))
        assertEquals("text/javascript", AssetPaths.mimeType("web/_app/x.js"))
        assertEquals("text/css", AssetPaths.mimeType("web/_app/a.CSS"))
        assertEquals("application/json", AssetPaths.mimeType("web/shell-manifest.json"))
        assertEquals("font/woff2", AssetPaths.mimeType("web/f.woff2"))
        assertEquals("image/svg+xml", AssetPaths.mimeType("web/i.svg"))
        assertEquals("application/wasm", AssetPaths.mimeType("web/x.wasm"))
        assertEquals("application/octet-stream", AssetPaths.mimeType("web/LICENSE"))
        assertEquals("utf-8", AssetPaths.encoding("text/javascript"))
        assertNull(AssetPaths.encoding("image/png"))
    }

    @Test
    fun lastPathOfAUrl() {
        assertEquals("/review", NavPath.ofUrl("https://appassets.androidplatform.net/review"))
        assertEquals("/", NavPath.ofUrl("https://appassets.androidplatform.net"))
        assertEquals("/dictionary?seq=1358280", NavPath.ofUrl("https://appassets.androidplatform.net/dictionary?seq=1358280#top"))
        assertNull(NavPath.ofUrl("https://evil.example/review"))
        assertNull(NavPath.ofUrl("https://appassets.androidplatform.net.evil.example/review"))
        assertNull(NavPath.ofUrl("https://appassets.androidplatform.net/api/v1/notes"))
        assertNull(NavPath.ofUrl("https://appassets.androidplatform.net//evil"))
        assertEquals("/", NavPath.sanitize("javascript:alert(1)"))
        assertEquals("/", NavPath.sanitize(null))
        assertEquals("/review", NavPath.sanitize("/review"))
    }
}
