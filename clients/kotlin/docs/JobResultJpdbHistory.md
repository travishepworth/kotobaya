
# JobResultJpdbHistory

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cardsCreated** | **kotlin.Int** | Vocabulary cards created (kanji cards are &#x60;kanji_cards_created&#x60;). |  |
| **kanjiCarded** | **kotlin.Int** | Kanji rows mapped to a kanji note and a card of kind &#x60;keyword&#x60; (char → keyword) or &#x60;writing&#x60; (keyword → char). |  |
| **kanjiCardsCreated** | **kotlin.Int** | Kanji cards created. |  |
| **kanjiNotesCreated** | **kotlin.Int** | Kanji notes created (one per character, shared by both arrays). |  |
| **kanjiNotesExisting** | **kotlin.Int** | Distinct characters that already had a live kanji note. |  |
| **kanjiTotal** | **kotlin.Int** | Kanji records in the export (rows of &#x60;cards_kanji_char_keyword&#x60; and &#x60;cards_kanji_keyword_char&#x60;). |  |
| **kanjiUnmapped** | [**kotlin.collections.List&lt;JobResultJpdbHistoryKanjiUnmappedInner&gt;**](JobResultJpdbHistoryKanjiUnmappedInner.md) | Kanji rows whose &#x60;character&#x60; is not exactly one Unicode code point. Their events count as &#x60;reviews_skipped&#x60;. |  |
| **notesCreated** | **kotlin.Int** | Vocabulary notes created (kanji notes are &#x60;kanji_notes_created&#x60;). |  |
| **notesExisting** | **kotlin.Int** | Mapped vocabulary that already had a live note (kanji - &#x60;kanji_notes_existing&#x60;). |  |
| **reviewsAlreadyImported** | **kotlin.Int** | Reviews skipped because an earlier run imported them. |  |
| **reviewsImported** | **kotlin.Int** | Reviews logged by this run. |  |
| **reviewsSkipped** | **kotlin.Int** | Events of unmapped rows (&#x60;unmapped&#x60;, &#x60;kanji_unmapped&#x60;), and rating events when &#x60;import_reviews&#x60; is false. |  |
| **reviewsTotal** | **kotlin.Int** | Review events in the export (every element of every row&#39;s &#x60;reviews[]&#x60;, all four arrays, status events included). |  |
| **reviewsUnknownGrade** | **kotlin.Int** | Events of a mapped row whose &#x60;grade&#x60; is none of the known grades; nothing is written for them. |  |
| **statusEventsKnown** | **kotlin.Int** | &#x60;known&#x60; events of mapped rows (vocab - a word status; kanji - nothing beyond the note and card; never a review). |  |
| **statusEventsUnknown** | **kotlin.Int** | &#x60;unknown&#x60; events of mapped rows (card creation only, no review). |  |
| **unmapped** | [**kotlin.collections.List&lt;JobResultJpdbHistoryUnmappedInner&gt;**](JobResultJpdbHistoryUnmappedInner.md) | Vocabulary that maps to no JMdict entry. |  |
| **vocabularyCarded** | **kotlin.Int** | Mapped vocabulary rows with at least one event other than &#x60;known&#x60;; each has a note and a card after the run (unless &#x60;import_reviews&#x60; is false). |  |
| **vocabularyStatusOnly** | **kotlin.Int** | Mapped vocabulary rows whose events are all &#x60;known&#x60;; they get a word status only, no note or card. |  |
| **vocabularyTotal** | **kotlin.Int** | Vocabulary records in the export (rows of &#x60;cards_vocabulary_jp_en&#x60; and &#x60;cards_vocabulary_en_jp&#x60;). |  |
| **wordStatusesSet** | **kotlin.Int** | Overrides written (&#x60;word.status_set&#x60; ops emitted). |  |



