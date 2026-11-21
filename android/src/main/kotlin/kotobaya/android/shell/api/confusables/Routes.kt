package kotobaya.android.shell.api.confusables

import kotobaya.android.shell.api.LocalRoute

/** Confusable pairs (LF-A8b, SHELL.md 4.3 / L-10): the write and both lists from the replica. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("listWordConfusables", "GET", "/api/v1/words/{seq}/confusables") { call -> listWordConfusables(call) },
    LocalRoute("listConfusables", "GET", "/api/v1/confusables") { call -> listConfusables(call) },
    LocalRoute("setConfusable", "PUT", "/api/v1/confusables/{note_a_id}/{note_b_id}/{reason}") { call -> setConfusable(call) },
)
