package kotobaya.android.shell.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.shell.web.BridgeCodec

private const val SEND = "android.intent.action.SEND"
private const val PROCESS = "android.intent.action.PROCESS_TEXT"
private const val VIEW = "android.intent.action.VIEW"

/** SHELL-A4 acceptance: one case per row of the design table (ADR-036 section 4), plus hostile inputs. */
class ShellLinksTest {
    private fun view(uri: String?) = ShellLinks.pathFor(VIEW, null, null, null, uri)
    private fun send(text: String?, type: String? = "text/plain") = ShellLinks.pathFor(SEND, type, text, null, null)

    @Test
    fun launcherIsNoNavigation() {
        assertNull(ShellLinks.pathForOrNull("android.intent.action.MAIN", null, null, null, null))
        assertEquals("/", ShellLinks.pathFor(null, null, null, null, null))
    }

    @Test
    fun reviewLinkWidgetTileAndNotification() {
        assertEquals("/review", view("kotobaya://review"))
    }

    @Test
    fun wordLinkAndHotWordWidget() {
        assertEquals("/dictionary?seq=1358280", view("kotobaya://word/1358280"))
        assertEquals("/dictionary?seq=1358280", view("kotobaya://struggle/word/1358280"))
    }

    @Test
    fun struggleLink() {
        assertEquals("/struggle", view("kotobaya://struggle"))
    }

    @Test
    fun serverEventNotifications() {
        assertEquals("/settings", view("kotobaya://settings"))
        assertEquals("/media", view("kotobaya://media"))
    }

    @Test
    fun shareSheetWordAndSentence() {
        assertEquals("/search?q=%E7%8C%AB", send("猫"))
        assertEquals("/search?q=%E7%8C%AB%E3%81%8C%E5%AF%9D%E3%81%A6%E3%81%84%E3%82%8B%E3%80%82", send("猫が寝ている。"))
    }

    @Test
    fun processText() {
        assertEquals("/search?q=%E9%A3%9F%E3%81%B9%E3%82%8B", ShellLinks.pathFor(PROCESS, "text/plain", null, "食べる", null))
    }

    @Test
    fun addLinkWithWordAndSentence() {
        assertEquals(
            "/search?q=%E7%8C%AB&sentence=%E7%8C%AB%20%E3%81%8C",
            view("kotobaya://add?word=%E7%8C%AB&sentence=%E7%8C%AB%20%E3%81%8C"),
        )
        assertEquals("/search?q=%E7%8C%AB", view("kotobaya://add?word=%E7%8C%AB"))
        assertEquals("/search?q=a%26b%3Dc%2Fd", view("kotobaya://add?word=a%26b%3Dc%2Fd"))
    }

    @Test
    fun pairLinkCarriesTheWholeUriEncoded() {
        val uri = "kotobaya://pair?server=https%3A%2F%2Fk.example%2Fapi%2Fv1&code=ABC123"
        assertEquals("/login?pair=" + ShellLinks.encode(uri), view(uri))
        assertTrue(BridgeCodec.isValidNavPath(view(uri)))
    }

    @Test
    fun hostileInputsGiveSafePaths() {
        val hostile = listOf(
            view("//evil"),
            view("javascript:alert(1)"),
            view("kotobaya://word/12\n3"),
            view("kotobaya://word/12/../x"),
            view("kotobaya://word/abc"),
            view("kotobaya://evil"),
            view("kotobaya://add"),
            view("https://evil.example/review"),
            view(null),
            send("//evil"),
            send("javascript:alert(1)"),
            send("line1\nline2 //x"),
            send("\u0000<script>"),
            send(null),
            send("  "),
            send("x", type = "image/png"),
        )
        for (p in hostile) assertTrue(BridgeCodec.isValidNavPath(p), "unsafe: $p")
        assertEquals("/", view("//evil"))
        assertEquals("/", view("javascript:alert(1)"))
        assertEquals("/", view("kotobaya://word/12\n3"))
        assertEquals("/", send("x", type = "image/png"))
        // shared text is data, never path syntax
        assertEquals("/search?q=%2F%2Fevil", send("//evil"))
        assertEquals("/search?q=javascript%3Aalert%281%29", send("javascript:alert(1)"))
        assertTrue("\n" !in send("a\nb"))
    }

    @Test
    fun longTextIsTruncatedTo2000CharsBeforeEncoding() {
        val p = send("a".repeat(10_000))
        assertEquals("/search?q=" + "a".repeat(2000), p)
        assertTrue(BridgeCodec.isValidNavPath(p))
    }

    @Test
    fun encodedPathOver2048ShortensTheQuery() {
        val p = send("猫".repeat(1000)) // 2000 chars -> 18 000 encoded
        assertTrue(p.length <= 2048 && p.startsWith("/search?q=%E7%8C%AB"), p.take(40))
        assertTrue(BridgeCodec.isValidNavPath(p))
        // sentence is dropped first
        val long = "kotobaya://add?word=a&sentence=" + ShellLinks.encode("猫".repeat(500))
        assertEquals("/search?q=a", view(long))
    }
}
