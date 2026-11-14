package kotobaya.android.review

import android.content.SharedPreferences
import kotobaya.contracts.fsrs.Button

/** Which button a swipe grades with; null disables that direction. */
data class SwipeMapping(val right: Button?, val left: Button?)

/** Review settings (app-level; the per-session furigana toggle lives in [ReviewSession]). */
data class ReviewPrefs(
    val furigana: Boolean = true,
    val pitch: Boolean = true,
    val autoplay: Boolean = true,
    /** jpdb five-button mode: Nothing, Something, Hard, Okay, Easy. */
    val fiveButtons: Boolean = false,
    val swipe: SwipeMapping = SwipeMapping(right = Button.GOOD, left = Button.AGAIN),
) {
    /** The buttons shown, by rating. */
    val buttons: List<Button>
        get() = if (fiveButtons) listOf(Button.NOTHING, Button.SOMETHING, Button.HARD, Button.OKAY, Button.EASY)
        else listOf(Button.AGAIN, Button.HARD, Button.GOOD, Button.EASY)

    /** The button a swipe in the direction grades with, as one of [buttons] (or null). */
    fun swipeButton(right: Boolean): Button? {
        val b = (if (right) swipe.right else swipe.left) ?: return null
        return buttons.firstOrNull { it == b } ?: buttons.firstOrNull { it.rating == b.rating }
    }
}

/** [ReviewPrefs] in SharedPreferences. */
class ReviewPrefsStore(prefsProvider: () -> SharedPreferences) {
    constructor(prefs: SharedPreferences) : this({ prefs })

    /** Opened on first read/write so Koin injection on the main thread never touches the prefs file. */
    private val prefs: SharedPreferences by lazy(prefsProvider)

    fun read(): ReviewPrefs {
        val d = ReviewPrefs()
        return ReviewPrefs(
            furigana = prefs.getBoolean(K_FURIGANA, d.furigana),
            pitch = prefs.getBoolean(K_PITCH, d.pitch),
            autoplay = prefs.getBoolean(K_AUTOPLAY, d.autoplay),
            fiveButtons = prefs.getBoolean(K_FIVE, false),
            swipe = SwipeMapping(button(K_SWIPE_RIGHT, d.swipe.right), button(K_SWIPE_LEFT, d.swipe.left)),
        )
    }

    fun write(p: ReviewPrefs) {
        prefs.edit()
            .putBoolean(K_FURIGANA, p.furigana).putBoolean(K_PITCH, p.pitch).putBoolean(K_AUTOPLAY, p.autoplay)
            .putBoolean(K_FIVE, p.fiveButtons)
            .putString(K_SWIPE_RIGHT, p.swipe.right?.wire ?: NONE).putString(K_SWIPE_LEFT, p.swipe.left?.wire ?: NONE)
            .apply()
    }

    private fun button(key: String, default: Button?): Button? {
        val v = prefs.getString(key, null) ?: return default
        return if (v == NONE) null else Button.entries.firstOrNull { it.wire == v }
    }

    private companion object {
        const val K_FURIGANA = "review.furigana"
        const val K_PITCH = "review.pitch"
        const val K_AUTOPLAY = "review.autoplay"
        const val K_FIVE = "review.five_buttons"
        const val K_SWIPE_RIGHT = "review.swipe_right"
        const val K_SWIPE_LEFT = "review.swipe_left"
        const val NONE = "none"
    }
}
