
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



