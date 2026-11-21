package kotobaya.android.shell.api.media

import kotobaya.android.shell.api.LocalRoute

/** The media library (LF-A8b, SHELL.md 4.3 / L-10): rename and delete as device ops, reads from the replica. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("listMedia", "GET", "/api/v1/media") { call -> listMedia(call) },
    LocalRoute("getMedia", "GET", "/api/v1/media/{media_id}") { call -> getMedia(call) },
    LocalRoute("updateMedia", "PATCH", "/api/v1/media/{media_id}") { call -> updateMedia(call) },
    LocalRoute("deleteMedia", "DELETE", "/api/v1/media/{media_id}") { call -> deleteMedia(call) },
)
