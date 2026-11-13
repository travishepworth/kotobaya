package kotobaya.android.vm

import androidx.annotation.StringRes

/** A user-visible message as a string resource and its format arguments; the view resolves it through the i18n layer. */
data class UiText(@StringRes val res: Int, val args: List<Any>) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
}
