package kotobaya.android.shell.api.review

import kotobaya.android.shell.api.LocalRoute

/** The review group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // SHELL-L2: the queue (QueueRoute.kt)
    LocalRoute("getReviewQueue", "GET", "/api/v1/review/queue") { call -> getReviewQueue(call) },
)
