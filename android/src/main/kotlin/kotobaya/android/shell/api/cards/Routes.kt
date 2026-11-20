package kotobaya.android.shell.api.cards

import kotobaya.android.shell.api.LocalRoute

/** The single-card writes (LF-A8, SHELL.md 4.3 / L-10); the card reads live in the actions group. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("createCard", "POST", "/api/v1/cards") { call -> createCard(call) },
    LocalRoute("updateCard", "PATCH", "/api/v1/cards/{card_id}") { call -> updateCard(call) },
    LocalRoute("deleteCard", "DELETE", "/api/v1/cards/{card_id}") { call -> deleteCard(call) },
)
