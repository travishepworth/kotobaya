
# Note

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cards** | [**kotlin.collections.List&lt;Card&gt;**](Card.md) | Live cards of the note, ordered by kind. Their &#x60;note&#x60; property is omitted. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **createdByDeviceId** | **kotlin.String** | Origin device of the creating op. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **entrySeq** | **kotlin.Long** | Dictionary entry (vocab notes); null when unmatched. |  |
| **fields** | **kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt;** | Field values by key: HTML fragments; audio as &#x60;[sound:&lt;sha256&gt;]&#x60;, images as &#x60;&lt;img src&#x3D;\&quot;blob:&lt;sha256&gt;\&quot;&gt;&#x60;. For referenced notes an empty or missing field means \&quot;render from the dictionary\&quot;. |  |
| **grammarPointId** | **kotlin.String** | Grammar point (grammar notes). |  |
| **id** | **kotlin.String** | Note id. |  |
| **kanjiLiteral** | **kotlin.String** | Kanji (kanji notes). |  |
| **language** | **kotlin.String** | BCP 47; always &#x60;ja&#x60; in v1. |  |
| **mergedIntoId** | **kotlin.String** | Set on a merge loser - the survivor&#39;s id. Only visible with &#x60;include_deleted&#x3D;true&#x60; in lists. |  |
| **mnemonic** | **kotlin.String** | Mnemonic (Markdown); empty if none. |  |
| **noteTypeId** | **kotlin.String** | Note type. Immutable. |  |
| **origin** | **kotlin.String** | How the note was created: &#x60;manual&#x60;, &#x60;takoboto&#x60;, &#x60;yomitan&#x60;, &#x60;ankiconnect&#x60;, &#x60;share&#x60;, &#x60;reader&#x60;, &#x60;deck&#x60;, &#x60;jpdb_import&#x60;, &#x60;api&#x60;, &#x60;auto_kanji&#x60;, ... (open set). |  |
| **sourceMediaId** | **kotlin.String** | Soft reference to the media; may dangle. |  |
| **sourceSentence** | **kotlin.String** | Sentence the note was mined from, by value; empty if none. |  |
| **sourceSentenceId** | **kotlin.String** | Soft reference to the sentence; may dangle. |  |
| **tags** | **kotlin.collections.List&lt;kotlin.String&gt;** | Tags, de-duplicated, sorted ascending. &#x60;leech&#x60; is never stored. |  |
| **type** | [**NoteBaseType**](NoteBaseType.md) | Equals the note type&#39;s &#x60;base_type&#x60;. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |
| **deckIds** | **kotlin.collections.List&lt;kotlin.String&gt;** | C-25 (additive). Ids of your live decks (&#x60;deleted_at&#x60; null; enabled or not) that hold this note: a live item &#x60;n:&lt;id&gt;&#x60; (&#x60;app.deck_item.note_id&#x60;, &#x60;deleted_at&#x60; null) whose &#x60;&lt;id&gt;&#x60; is this note or a merge loser of it (&#x60;merged_into_id&#x60; &#x3D; this note&#39;s id), or, when &#x60;entry_seq&#x60; is non-null, a live item &#x60;e:&lt;entry_seq&gt;&#x60;. This is the membership the review queue uses (core &#x60;DeckMembership&#x60;) minus smart decks, which never appear. Draw order: &#x60;deck.priority&#x60; ascending, then deck id ascending (order of the lower-case UUID text). No duplicates. Always empty for a tombstoned note and for a merge loser (ask the survivor). Servers that implement C-25 send it on every &#x60;Note&#x60;. Absent on older servers: clients treat absent as \&quot;unknown\&quot;, never as \&quot;in no deck\&quot;.  |  [optional] |



