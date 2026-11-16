package kotobaya.android.shell.api.decks

import kotobaya.android.shell.api.LocalRoute

/** The decks group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // SHELL-L7: decks (DeckRoutes.kt)
    LocalRoute("listDecks", "GET", "/api/v1/decks") { call -> listDecks(call) },
    LocalRoute("createDeck", "POST", "/api/v1/decks") { call -> createDeck(call) },
    LocalRoute("getDeck", "GET", "/api/v1/decks/{deck_id}") { call -> getDeck(call) },
    LocalRoute("updateDeck", "PATCH", "/api/v1/decks/{deck_id}") { call -> updateDeck(call) },
    LocalRoute("deleteDeck", "DELETE", "/api/v1/decks/{deck_id}") { call -> deleteDeck(call) },
    // deck items (DeckItemRoutes.kt)
    LocalRoute("listDeckItems", "GET", "/api/v1/decks/{deck_id}/items") { call -> listDeckItems(call) },
    LocalRoute("addDeckItems", "POST", "/api/v1/decks/{deck_id}/items") { call -> addDeckItems(call) },
    LocalRoute("putDeckItem", "PUT", "/api/v1/decks/{deck_id}/items/{item_key}") { call -> putDeckItem(call) },
    LocalRoute("removeDeckItem", "DELETE", "/api/v1/decks/{deck_id}/items/{item_key}") { call -> removeDeckItem(call) },
)
