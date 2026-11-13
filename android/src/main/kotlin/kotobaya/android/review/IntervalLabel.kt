package kotobaya.android.review

import androidx.annotation.StringRes
import kotobaya.android.R

/** A preview interval as a string resource and its count; the screen resolves it through the i18n layer. */
data class IntervalLabel(@StringRes val res: Int, val count: Int) {
    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR

        fun of(ms: Long): IntervalLabel = when {
            ms < MIN -> IntervalLabel(R.string.review_interval_now, 0)
            ms < HOUR -> IntervalLabel(R.string.review_interval_minutes, (ms / MIN).toInt())
            ms < DAY -> IntervalLabel(R.string.review_interval_hours, (ms / HOUR).toInt())
            ms < 30 * DAY -> IntervalLabel(R.string.review_interval_days, (ms / DAY).toInt())
            ms < 365 * DAY -> IntervalLabel(R.string.review_interval_months, (ms / (30 * DAY)).toInt())
            else -> IntervalLabel(R.string.review_interval_years, (ms / (365 * DAY)).toInt())
        }
    }
}
