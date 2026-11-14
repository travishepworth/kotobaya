package kotobaya.android.ui.theme

import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeApiTest {
    @Test
    fun themeModeFollowsSystemOnlyInSystemMode() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun themeModeStorageRoundTripsAndDefaultsToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStorageKey(it.storageKey)) }
        assertEquals(ThemeMode.System, ThemeMode.fromStorageKey(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStorageKey("sepia"))
    }

    @Test
    fun deckColourIdsMatchTokensAndDefaultToHai() {
        assertEquals(listOf("ai", "shu", "matcha", "kohaku", "fuji", "sora", "sakura", "aoi", "hai"), KbDeckColor.entries.map { it.id })
        assertEquals(KbDeckColor.Hai, KbDeckColor.fromId(null))
        assertEquals(KbDeckColor.Hai, KbDeckColor.fromId("purple"))
        assertEquals(KbDeckColor.Sora, KbDeckColor.fromId("sora"))
    }

    @Test
    fun japaneseRolesCarryTheJapaneseLocaleAndLatinRolesDoNot() {
        val t = kbTextStyles()
        listOf(t.jpHero, t.jpDisplay, t.jpBody, t.reading).forEach { assertEquals(LocaleList("ja"), it.localeList) }
        listOf(t.body, t.label, t.overline).forEach { assertNull(it.localeList) }
    }

    @Test
    fun rubyIsSixTenthsOfTheBaseButNeverBelowTheMinimum() {
        val t = kbTextStyles()
        assertEquals(24.sp, t.ruby(t.jpDisplay).fontSize)
        assertEquals(KbType.RUBY_MIN_SP.sp, t.ruby(t.caption).fontSize)
    }

    @Test
    fun darkAndLightDifferAndToneHelpersPickTheRightPair() {
        assertTrue(KbColorsLight.bg != KbColorsDark.bg)
        assertEquals(KbTone(KbColorsDark.gradeGood, KbColorsDark.gradeGoodContainer), KbColorsDark.grade(KbGrade.Good))
        assertEquals(KbTone(KbColorsLight.stateLeech, KbColorsLight.stateLeechContainer), KbColorsLight.state(KbCardState.Leech))
    }
}
