
# JobResultJpdbHistory

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cardsCreated** | **kotlin.Int** | Vocabulary &#x60;meaning&#x60; cards created, at most one per carded group, so about equal to &#x60;notes_created&#x60; (kanji cards are &#x60;kanji_cards_created&#x60;). |  |
| **kanjiCarded** | **kotlin.Int** | Kanji rows mapped to a kanji note and a card of kind &#x60;keyword&#x60; (char → keyword) or &#x60;writing&#x60; (keyword → char), known-only rows included. &#x60;writing&#x60; cards and known-only cards are created suspended. |  |
| **kanjiCardsCreated** | **kotlin.Int** | Kanji cards created (one per (character, kind) without a live card), suspended ones included. |  |
| **kanjiCardsSuspended** | **kotlin.Int** | Of &#x60;kanji_cards_created&#x60;, those created with &#x60;suspended &#x3D; true&#x60; - every created &#x60;writing&#x60; card plus created &#x60;keyword&#x60; cards of known-only groups. Existing cards are never counted or changed. Added by C-4. |  |
| **kanjiNotesCreated** | **kotlin.Int** | Kanji notes created (one per character, shared by both arrays). |  |
| **kanjiNotesExisting** | **kotlin.Int** | Distinct characters that already had a live kanji note. |  |
| **kanjiTotal** | **kotlin.Int** | Kanji records in the export (rows of &#x60;cards_kanji_char_keyword&#x60; and &#x60;cards_kanji_keyword_char&#x60;). |  |
| **kanjiUnmapped** | [**kotlin.collections.List&lt;JobResultJpdbHistoryKanjiUnmappedInner&gt;**](JobResultJpdbHistoryKanjiUnmappedInner.md) | Kanji rows whose &#x60;character&#x60; is not exactly one Unicode code point. Their events count as &#x60;reviews_skipped&#x60;. |  |
| **notesCreated** | **kotlin.Int** | Vocabulary notes created, one per carded group (distinct seq), not per row (kanji notes are &#x60;kanji_notes_created&#x60;). |  |
| **notesExisting** | **kotlin.Int** | Carded vocabulary groups (distinct seqs) that already had a live note (kanji - &#x60;kanji_notes_existing&#x60;). |  |
| **reviewsAlreadyImported** | **kotlin.Int** | Reviews skipped because an earlier run imported them. |  |
| **reviewsDuplicateDropped** | **kotlin.Int** | Events of mapped rows dropped as exact duplicates - same &#x60;timestamp&#x60; and &#x60;grade&#x60; as an earlier event of the same group (vocab - rows sharing a seq; kanji - rows sharing character and card kind). Any grade, status events included. Added by C-4. |  |
| **reviewsImported** | **kotlin.Int** | Reviews logged by this run. |  |
| **reviewsSkipped** | **kotlin.Int** | Events of unmapped rows (&#x60;unmapped&#x60;, &#x60;kanji_unmapped&#x60;) and of &#x60;cards_vocabulary_en_jp&#x60; rows (any grade), and rating events when &#x60;import_reviews&#x60; is false. |  |
| **reviewsTotal** | **kotlin.Int** | Review events in the export (every element of every row&#39;s &#x60;reviews[]&#x60;, all four arrays, status events included). |  |
| **reviewsUnknownGrade** | **kotlin.Int** | Events of a mapped row that are malformed (not an object with an integer &#x60;timestamp&#x60; and a string &#x60;grade&#x60;) or whose &#x60;grade&#x60; is none of the known grades; nothing is written for them. |  |
| **statusEventsKnown** | **kotlin.Int** | &#x60;known&#x60; events of mapped rows, duplicates excluded, never a review. All of them count here: those of status-only vocab groups (which may set a word status), those of carded vocab groups (ignored; FSRS schedules from the reviews) and those of kanji groups (a known-only kanji card is created suspended). |  |
| **statusEventsUnknown** | **kotlin.Int** | &#x60;unknown&#x60; events of mapped rows, duplicates excluded (card creation only, no review). |  |
| **unmapped** | [**kotlin.collections.List&lt;JobResultJpdbHistoryUnmappedInner&gt;**](JobResultJpdbHistoryUnmappedInner.md) | &#x60;cards_vocabulary_jp_en&#x60; rows that map to no JMdict entry, one element per row. |  |
| **vocabularyCarded** | **kotlin.Int** | Mapped &#x60;cards_vocabulary_jp_en&#x60; rows whose group (rows sharing the mapped seq) is carded - it has at least one kept non-&#x60;known&#x60; event, or no well-formed events; the group&#39;s note has a &#x60;meaning&#x60; card after the run (unless &#x60;import_reviews&#x60; is false). One note and one card per group, not per row. |  |
| **vocabularyEnJpIgnored** | **kotlin.Int** | Rows of &#x60;cards_vocabulary_en_jp&#x60; (C-4). Never mapped, never listed in &#x60;unmapped&#x60;; no note, card, status or review; their events count as &#x60;reviews_skipped&#x60;. Added by C-4. |  |
| **vocabularyStatusOnly** | **kotlin.Int** | Mapped &#x60;cards_vocabulary_jp_en&#x60; rows whose group&#39;s kept well-formed events are all &#x60;known&#x60; (at least one); the word gets status &#x60;known&#x60; (unless it already has an override or &#x60;import_word_status&#x60; is false), no note or card. |  |
| **vocabularyTotal** | **kotlin.Int** | Vocabulary records in the export (rows of &#x60;cards_vocabulary_jp_en&#x60; and &#x60;cards_vocabulary_en_jp&#x60;). |  |
| **wordStatusesSet** | **kotlin.Int** | Overrides written (&#x60;word.status_set&#x60; ops emitted), at most one per status-only vocab group. |  |



