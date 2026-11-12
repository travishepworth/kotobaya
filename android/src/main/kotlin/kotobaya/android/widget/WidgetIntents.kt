package kotobaya.android.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotobaya.android.MainActivity

/** Intents the widget and the tile launch. Deep links are `kotobaya://...` VIEW intents to [MainActivity]. */
object WidgetIntents {
    const val REVIEW_URI = "kotobaya://review"

    fun review(context: Context): Intent = view(context, REVIEW_URI)

    fun word(context: Context, entrySeq: Long): Intent = view(context, "kotobaya://word/$entrySeq")

    private fun view(context: Context, uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
