package kotobaya.android.shell.api.mining

import kotobaya.android.shell.api.LocalRoute

/** The mining group's local routes (docs/ANDROID-SHELL.md section 5). Only this group's ticket edits this file. */
val routes: List<LocalRoute> = listOf(
    // SHELL-L5: dictionary reads, `local_or_proxy` by rule L-7 (DictRoutes.kt, DictSearch.kt)
    LocalRoute("searchDictionary", "GET", "/api/v1/dict/search") { call -> searchDictionary(call) },
    LocalRoute("getDictEntry", "GET", "/api/v1/dict/entries/{seq}") { call -> getDictEntry(call) },
    LocalRoute("listEntryExamples", "GET", "/api/v1/dict/entries/{seq}/examples") { call -> listEntryExamples(call) },
    LocalRoute("listDictHeadwords", "GET", "/api/v1/dict/headwords") { call -> listDictHeadwords(call) },
    LocalRoute("getDictRelease", "GET", "/api/v1/dict/release") { call -> getDictRelease(call) },
    LocalRoute("getKanji", "GET", "/api/v1/kanji/{literal}") { call -> getKanji(call) },
    LocalRoute("listKanjiWords", "GET", "/api/v1/kanji/{literal}/words") { call -> listKanjiWords(call) },
    // mining (CreateNoteRoute.kt)
    LocalRoute("createNote", "POST", "/api/v1/notes") { call -> createNote(call) },
)
